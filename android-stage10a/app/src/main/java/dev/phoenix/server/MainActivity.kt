package dev.phoenix.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.phoenix.core.ServerController.State
import kotlin.math.roundToInt

/** Phoenix: Home, Mods, Worlds, Players and Settings tabs over one bottom bar. */
class MainActivity : ComponentActivity() {
    private val phoenix: PhoenixViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) enableDebugStrictMode()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            openTab(intent)
        }
        setContent { PhoenixTheme { PhoenixApp(phoenix) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTab(intent)
    }

    private fun openTab(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TAB)?.let { name -> Screen.entries.firstOrNull { it.name == name } }?.let(phoenix::navigate)
    }

    companion object {
        private const val EXTRA_TAB = "dev.phoenix.server.TAB"

        /** Debug builds only: logs main-thread disk and network access and leaked resources. Never crashes the app. */
        private fun enableDebugStrictMode() {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects()
                .detectActivityLeaks().penaltyLog().build())
        }

        /** Opens Settings, where online play (NetBird) and the server address (Minecraft.How) are managed. */
        fun settingsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, Screen.SETTINGS.name)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}

/** What every tab needs: the latest state, actions, the layout width and room for the bottom bar. */
class ScreenContext(
    val d: Dashboard,
    val vm: PhoenixViewModel,
    val online: OnlinePlay,
    val wide: Boolean,
    val bottomInset: Dp,
    val confirm: (LifecycleOperation) -> Unit,
)

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector? = null, val drawable: Int? = null)
private val tabs = listOf(
    Tab(Screen.HOME, "Home", Icons.Rounded.Home),
    Tab(Screen.MODS, "Mods", drawable = R.drawable.ic_cube),
    Tab(Screen.WORLD, "Worlds", Icons.Rounded.Layers),
    Tab(Screen.PLAYERS, "Players", Icons.Rounded.Group),
    Tab(Screen.SETTINGS, "Settings", Icons.Rounded.Settings),
)
private val BarHeight = 64.dp
private val RailWidth = 80.dp
/** Five rail items fit a 360 dp landscape phone with status and navigation bars. */
private val RailItemHeight = 56.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PhoenixApp(vm: PhoenixViewModel) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) { vm.refreshMinecraftHow(throttled = true); onPauseOrDispose { } }
    val online = rememberOnlinePlay(vm)
    var confirm by remember { mutableStateOf<LifecycleOperation?>(null) }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bar = remember { BarScroll() }
    bar.max = with(density) { (BarHeight + navBottom).toPx() }
    LaunchedEffect(screen) { bar.offset = 0f }
    val keyboard = WindowInsets.isImeVisible
    val tabStates = rememberSaveableStateHolder()
    val resources = LocalContext.current.resources
    // Decode the hero art while the splash shows, so Home and Worlds never wait for it.
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { HeroArt.load(resources) } }
    val d = dashboard
    if (d == null) { Splash(); return }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg).nestedScroll(bar)) {
        // Landscape uses a rail on the right instead of the bottom bar, so Settings stays bottom-right in both orientations
        // and the short landscape height goes to content.
        val rail = maxWidth > maxHeight
        val layoutDirection = LocalLayoutDirection.current
        val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
        // The rail's divider sits outside RailWidth, so it counts too; otherwise content ran 1 dp under it.
        val railSpace = if (rail) RailWidth + DividerDefaults.Thickness + sideInsets.calculateEndPadding(layoutDirection) else 0.dp
        val contentWidth = maxWidth - railSpace - (if (rail) sideInsets.calculateStartPadding(layoutDirection) else 0.dp)
        bar.enabled = !rail && screen != Screen.PLAYERS
        val ctx = ScreenContext(d, vm, online, contentWidth >= 600.dp, (if (rail) 0.dp else BarHeight) + navBottom + 16.dp) { confirm = it }
        val content = if (rail) Modifier.padding(start = sideInsets.calculateStartPadding(layoutDirection), end = railSpace) else Modifier
        // Tabs switch instantly, like YouTube; each keeps its scroll position.
        Box(Modifier.fillMaxSize().then(content)) {
            tabStates.SaveableStateProvider(screen.name) {
                when (screen) {
                    Screen.HOME -> HomeScreen(ctx)
                    Screen.MODS -> ModsScreen(ctx)
                    Screen.WORLD -> WorldsScreen(ctx)
                    Screen.PLAYERS -> PlayersScreen(ctx)
                    Screen.SETTINGS -> SettingsScreen(ctx)
                }
            }
        }
        // A strip behind the status bar so scrolled content never runs under the clock.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Palette.Bg.copy(alpha = 0.94f)))
        val error = d.errorMessage?.takeIf { it != dismissedError }
        if (error != null) Banner(
            error, Tone.ERROR, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            onDismiss = { dismissedError = error; vm.clearError() },
        )
        if (rail) SideRail(screen, vm::navigate, Modifier.align(Alignment.CenterEnd))
        else if (!keyboard) BottomBar(screen, ctx.wide, vm::navigate,
            Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, bar.offset.roundToInt()) })
    }
    confirm?.let { op ->
        val (title, body) = when (op) {
            LifecycleOperation.STOP -> "Stop the server?" to "Players are disconnected. The world is saved first."
            LifecycleOperation.RESTART -> "Restart the server?" to "Players are disconnected while it restarts. The world is saved first."
            LifecycleOperation.START -> "Start the server?" to ""
        }
        ConfirmDialog(title, body, if (op == LifecycleOperation.STOP) "Stop" else "Restart",
            onConfirm = { vm.lifecycle(op) }, onDismiss = { confirm = null }, destructive = op == LifecycleOperation.STOP)
    }
}

@Composable private fun Splash() {
    Column(Modifier.fillMaxSize().background(Palette.Bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_phoenix), contentDescription = null, modifier = Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        BrandTitle(size = 26.sp)
    }
}

@Composable private fun BottomBar(current: Screen, wide: Boolean, onSelect: (Screen) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().background(Palette.Card)) {
        HorizontalDivider(color = Palette.Line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(BarHeight), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val selected = tab.screen == current
                val tint = if (selected) Palette.Brand else Palette.Muted
                val item = Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab) { onSelect(tab.screen) }
                val label = @Composable {
                    Text(tab.label, fontSize = if (wide) 15.sp else 12.sp, color = tint,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
                val icon = tab.icon ?: ImageVector.vectorResource(tab.drawable!!)
                if (wide) Row(item, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)); label()
                } else Column(item, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(25.dp)); Spacer(Modifier.height(3.dp)); label()
                }
            }
        }
    }
}

/** The landscape navigation: the same five tabs down the right edge, Settings alone at the bottom. Never slides away. */
@Composable private fun SideRail(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxHeight().background(Palette.Card)) {
        VerticalDivider(color = Palette.Line)
        Column(
            Modifier.fillMaxHeight()
                // Not safeDrawing: it includes the keyboard, which squeezed the five rail items into the space above it.
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                .width(RailWidth).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.dropLast(1).forEach { RailItem(it, it.screen == current, onSelect) }
            Spacer(Modifier.weight(1f))
            RailItem(tabs.last(), tabs.last().screen == current, onSelect)
        }
    }
}

@Composable private fun RailItem(tab: Tab, selected: Boolean, onSelect: (Screen) -> Unit) {
    val tint = if (selected) Palette.Brand else Palette.Muted
    Column(
        Modifier.fillMaxWidth().height(RailItemHeight).selectable(selected, role = Role.Tab) { onSelect(tab.screen) },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon ?: ImageVector.vectorResource(tab.drawable!!), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(tab.label, fontSize = 11.sp, color = tint, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Slides the bottom bar away while content scrolls toward its end and brings it back on any scroll back, like YouTube. */
private class BarScroll : NestedScrollConnection {
    var max = 0f
    var enabled = true
    var offset by mutableFloatStateOf(0f)

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled) return Offset.Zero
        // Only real scrolling moves the bar, so short pages keep it; pulling down at the top shows it.
        val delta = if (consumed.y != 0f) consumed.y else available.y.coerceAtLeast(0f)
        offset = (offset - delta).coerceIn(0f, max)
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (enabled && offset > 0f && offset < max) animate(offset, if (offset > max / 2) max else 0f) { value, _ -> offset = value }
        return Velocity.Zero
    }
}

// ---------------------------------------------------------------- State shared by the tabs

private val stoppedStates = listOf(State.READY, State.STOPPED)

fun isStopped(d: Dashboard) = d.phase in stoppedStates

/** The server's state in a word and a color. */
fun serverStatus(d: Dashboard): Pair<String, Tone> = when {
    d.phase == State.ERROR -> "Error" to Tone.ERROR
    d.phase == State.STARTING -> "Starting…" to Tone.WORKING
    d.phase == State.STOPPING -> "Stopping…" to Tone.WORKING
    d.phase == State.RESTARTING -> "Restarting…" to Tone.WORKING
    d.phase == State.RECOVERING -> "Recovering…" to Tone.WORKING
    d.phase == State.INSTALLING || (d.busy && d.phase == State.NOT_INSTALLED) -> "Installing…" to Tone.WORKING
    d.phase == State.ONLINE -> "Online" to Tone.OK
    d.phase == State.NOT_INSTALLED -> "Not set up" to Tone.IDLE
    else -> "Offline" to Tone.IDLE
}

/** The custom name is verified in public DNS for the selected route's current addresses. */
fun dnsReady(d: Dashboard) = d.net.name.health == NameHealth.ACTIVE

/** Can friends join right now, and if not, why. Same wording on Home and in Settings. */
fun joinStatus(d: Dashboard): Pair<String, Tone> = NetworkStatus.joinStatus(d)

fun netBirdStatus(d: Dashboard): Pair<String, Tone> = when {
    d.netBirdAuthorizing -> "Waiting for sign-in" to Tone.WORKING
    d.netBirdLinkUp -> "Connected" to Tone.OK
    d.networkBusy -> "Connecting…" to Tone.WORKING
    d.netBirdLive -> "Not connected" to Tone.WARN
    netBirdStoppedUnexpectedly(d) -> "Stopped unexpectedly" to Tone.ERROR
    // The server is up but NetBird isn't running, so friends can't reach it from outside.
    d.networkHasIdentity && d.phase == State.ONLINE -> "Not connected" to Tone.WARN
    // Set up and waiting: NetBird starts by itself with the server, so this is a ready state, not "off".
    d.networkHasIdentity -> "Idle" to Tone.OK
    else -> "Not set up" to Tone.IDLE
}

/**
 * The NetBird client ended on its own (not the user's Turn off): its last words are kept by NetBirdVpnService. The
 * user's own disconnect and Android revoking the VPN have their own wording and are not counted.
 */
fun netBirdStoppedUnexpectedly(d: Dashboard) = !d.netBirdLive && !d.netBirdLinkUp &&
    (d.netBirdLink.startsWith("NetBird stopped") || d.netBirdLink.startsWith("NetBird couldn't start") || d.netBirdLink.startsWith("NetBird route update failed"))

fun minecraftHowStatus(d: Dashboard): Pair<String, Tone> = NetworkStatus.nameStatus(d.net.name) to NetworkStatus.nameTone(d.net.name)

// ---------------------------------------------------------------- Online play (NetBird and Minecraft.How sign-in)

class OnlinePlayState {
    var note by mutableStateOf<Pair<String, Tone>?>(null)
    var browserSetup by mutableStateOf(false)
    /** Google sign-in was opened in the browser from the sign-in screen. */
    var googleOpened by mutableStateOf(false)
    var continueAfterSignIn = false
}

/** The next online-play step and whether it is already running. */
data class NextStep(val label: String, val busy: Boolean, val run: () -> Unit)

/**
 * The NetBird and Minecraft.How flows, started from Home and Settings. NetBird signs in through the
 * user's browser; Minecraft.How through its own sign-in screen. Phoenix never sees a password.
 */
class OnlinePlay(
    private val context: Context,
    private val vm: PhoenixViewModel,
    private val vpnConsent: ManagedActivityResultLauncher<Intent, ActivityResult>,
    private val minecraftHowSignIn: ManagedActivityResultLauncher<Intent, ActivityResult>,
    val state: OnlinePlayState,
) {
    val note get() = state.note
    fun dismissNote() { state.note = null }

    /** The one thing left to do for online play, or null when friends can already be invited. */
    fun next(d: Dashboard): NextStep? {
        val portsReady = d.networkHasIdentity && d.publicAddress != null
        val phoneReady = d.netBirdLive && d.netBirdSignedIn && !d.networkBusy
        return when {
            d.netBirdAuthorizing -> NextStep("Waiting for browser sign-in", true) {}
            d.networkActivity != null || d.autoSetupState == "PENDING" || d.autoSetupState == "RUNNING" -> NextStep("Setting up", true) {}
            portsReady && (d.minecraftHostname == null || d.autoSetupState == "COMPLETE") -> null
            !phoneReady -> NextStep(if (d.netBirdSignedIn || d.networkHasIdentity) "Connect online play" else "Set up online play", false) { connect(d) }
            !portsReady && d.minecraftHostname == null -> NextStep("Create public ports", false) { createPorts() }
            d.autoSetupState == "NEEDS_SIGN_IN" -> NextStep("Sign in to Minecraft.How", false) { signInMinecraftHow(continueAfter = true) }
            else -> NextStep("Finish setup", false) { continueSetup() }
        }
    }

    /** Asks Android for VPN permission if needed, then starts NetBird; the browser opens if NetBird needs a sign-in. */
    fun connect(d: Dashboard) {
        if (!NetBirdTransport.supportsBundledClient()) { state.note = "This build includes NetBird for arm64 phones only." to Tone.ERROR; return }
        // Queue the saved-hostname setup so it continues once NetBird connects; without a name only ports are made.
        if (d.minecraftHostname != null) vm.requestAutoNetworkSetup()
        val request = VpnService.prepare(context)
        if (request != null) vpnConsent.launch(request) else startConnect()
    }

    /**
     * Sets NetBird up only for the custom name's front door: VPN permission, sign-in and its free ports, without making
     * NetBird the route or moving the name; Playit stays the route. The ports are made by the backend as soon as NetBird
     * is connected and signed in, which may already be the case.
     */
    fun connectForName() {
        NetBirdTransport.recordOwnerChoice(context, false) // Turning the name on is the owner asking for NetBird again.
        if (!NetBirdTransport.supportsBundledClient()) { state.note = "This build includes NetBird for arm64 phones only." to Tone.ERROR; return }
        // Connect also opens NetBird's sign-in when its session is missing, and resumes setup when the phone is already online.
        vm.requestFrontDoorSetup { ready ->
            if (ready) state.note = null
            else { val request = VpnService.prepare(context); if (request != null) vpnConsent.launch(request) else startConnect() }
        }
    }

    internal fun startConnect() {
        NetBirdTransport.start(context, NetBirdVpnService.ACTION_CONNECT)
        state.note = "Connecting. Your browser opens if NetBird needs you to sign in." to Tone.WORKING
    }

    fun signInAgain(d: Dashboard) {
        if (!d.netBirdEnrolled) { state.note = "Connect this phone first so NetBird can create its device identity." to Tone.WARN; return }
        NetBirdTransport.start(context, NetBirdVpnService.ACTION_AUTH_ONLY)
        state.note = "Your browser opens for NetBird sign-in." to Tone.WORKING
    }

    /** The owner turned NetBird off: remembered, so no automatic path starts it again until they turn it on. */
    fun noteNetBirdOff() = NetBirdTransport.recordOwnerChoice(context, true)

    fun disconnect() { noteNetBirdOff(); NetBirdTransport(context).stop(); state.note = null }

    /** Playit's approval page opens in the browser; the owner signs in there and approves Phoenix. */
    fun connectPlayit(andUse: Boolean = true) {
        state.note = "Opening Playit in your browser…" to Tone.WORKING
        vm.connectPlayit(andUse) { url ->
            if (url == null) state.note = null
            else { open(url); state.note = "Sign in on playit.gg and approve Phoenix. This screen updates by itself." to Tone.WORKING }
        }
    }

    /** NetBird becomes the route now if its ports exist; otherwise its sign-in starts and the switch follows setup. */
    fun turnOnNetBird(d: Dashboard) {
        NetBirdTransport.recordOwnerChoice(context, false)
        vm.requestNetBird { switched -> if (!switched) connect(d) }
    }
    fun createPorts() { vm.provisionPorts(); state.note = null }

    fun continueSetup() {
        vm.requestAutoNetworkSetup()
        NetBirdTransport.start(context, NetBirdVpnService.ACTION_CONNECT)
        state.note = null
    }

    fun signInMinecraftHow(continueAfter: Boolean = false) {
        state.continueAfterSignIn = continueAfter
        minecraftHowSignIn.launch(MinecraftHowSignInActivity.createIntent(context))
    }

    fun signOutMinecraftHow() { MinecraftHowSession.signOut(context) { vm.refreshMinecraftHow() } }

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

@Composable fun rememberOnlinePlay(vm: PhoenixViewModel): OnlinePlay {
    val context = LocalContext.current
    val state = remember { OnlinePlayState() }
    var play: OnlinePlay? = null
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) play?.startConnect()
        else state.note = "VPN permission wasn't granted, so nothing was started." to Tone.ERROR
    }
    // The account state shown afterwards always comes from Minecraft.How itself, whatever the screen returned.
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(MinecraftHowSignInActivity.EXTRA_USE_BROWSER_SETUP, false) == true) state.browserSetup = true
        if (result.data?.getBooleanExtra(MinecraftHowSignInActivity.EXTRA_GOOGLE_OPENED, false) == true) state.googleOpened = true
        vm.refreshMinecraftHow()
        if (result.resultCode == Activity.RESULT_OK && state.continueAfterSignIn) play?.continueSetup()
        state.continueAfterSignIn = false
    }
    return remember(vm, consent, signIn) { OnlinePlay(context, vm, consent, signIn, state) }.also { play = it }
}
