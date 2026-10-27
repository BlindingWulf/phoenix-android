package dev.phoenix.server

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.phoenix.core.ServerController.State
import kotlin.math.min
import kotlin.math.roundToInt

@Composable fun HomeScreen(ctx: ScreenContext) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (ctx.wide) Row(Modifier.fillMaxSize()) {
        HeroPane(ctx.d, Modifier.weight(0.36f).fillMaxHeight()) { ctx.vm.navigate(Screen.SETTINGS) }
        LazyColumn(
            Modifier.weight(0.64f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = ctx.bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "address") { if (ctx.d.installed) AddressCard(ctx, wide = true) else CreateServerCard(ctx.d, ctx.vm) }
            homeItems(ctx, Modifier)
        }
    } else LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = ctx.bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            HeroHeader(ctx.d, onSettings = { ctx.vm.navigate(Screen.SETTINGS) }) {
                Box(Modifier.padding(horizontal = 16.dp)) { if (ctx.d.installed) AddressCard(ctx, wide = false) else CreateServerCard(ctx.d, ctx.vm) }
            }
        }
        homeItems(ctx, Modifier.padding(horizontal = 16.dp))
    }
}

private fun LazyListScope.homeItems(ctx: ScreenContext, pad: Modifier) {
    val d = ctx.d
    if (!d.installed) return
    item(key = "controls") { ServerControls(ctx, pad) }
    item(key = "metrics") {
        val storage by ctx.vm.storage.collectAsStateWithLifecycle()
        MetricsGrid(d, storage, ctx.wide, pad)
    }
    if (d.thermalStatus >= 2) item(key = "warm") { Banner("The phone is getting warm. The server may slow down.", Tone.WARN, pad) }
    d.worldErrors?.let { errors -> item(key = "world-errors") { WorldErrorsCard(ctx, errors, pad) } }
    d.serverCrash?.let { crash -> item(key = "crash") {
        Banner("The server crashed: ${crash.summary}." + (crash.report?.let { " Minecraft saved $it in crash-reports." } ?: "") +
            if (crash.savedAtStop) "" else " The final save didn't run, so the world is as of its last autosave.", Tone.ERROR, pad, onDismiss = ctx.vm::dismissServerCrash)
    } }
    if (d.systemKillNotice) item(key = "killed") { Banner(ServerExit.KILL_ADVICE, Tone.WARN, pad, onDismiss = ctx.vm::dismissKillNotice) }
    val next = ctx.online.next(d).takeIf { d.net.selected?.usesNetBird == true || d.requestedRoute?.usesNetBird == true }
    // Not set up at all: the address card already offers the first step. The route and the name are chips in that card.
    if (next != null && !d.networkHasIdentity && Addresses.options(d.net).isNotEmpty()) item(key = "setup") { FinishSetupCard(ctx, next, pad) }
}

// ---------------------------------------------------------------- Hero

@Composable private fun HeroImage(modifier: Modifier) {
    HeroArtImage(modifier)
}

@Composable private fun HeroStatus(d: Dashboard) {
    val (status, tone) = serverStatus(d)
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone, 9.dp)
        Text(status, color = if (tone == Tone.IDLE) Palette.Text else tone.color, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 7.dp))
        Text("  ·  ${d.serverName}", color = Palette.Text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A settings shortcut over the artwork's top corner. */
@Composable private fun SettingsShortcut(onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = Palette.Text, modifier = Modifier.size(24.dp))
    }
}

/** Phone layout: the artwork fades into the page and the address card sits over its lower edge. */
@Composable private fun HeroHeader(d: Dashboard, onSettings: () -> Unit, card: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        HeroImage(Modifier.fillMaxWidth().height(300.dp))
        Box(Modifier.fillMaxWidth().height(300.dp).background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.6f), 0.3f to Color.Transparent, 0.55f to Color.Transparent, 1f to Palette.Bg)))
        Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(14.dp))
            BrandTitle(size = 30.sp, shadow = true)
            Spacer(Modifier.height(8.dp))
            HeroStatus(d)
            Spacer(Modifier.height(104.dp))
            card()
        }
        SettingsShortcut(onSettings, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 14.dp))
    }
}

/** Landscape layout: the artwork fills the left column. */
@Composable private fun HeroPane(d: Dashboard, modifier: Modifier, onSettings: () -> Unit) {
    Box(modifier) {
        HeroImage(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.6f to Color.Transparent, 1f to Palette.Bg)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.35f to Color.Transparent)))
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            BrandTitle(size = 24.sp, shadow = true)
            Spacer(Modifier.height(8.dp))
            HeroStatus(d)
        }
        SettingsShortcut(onSettings, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
    }
}

// ---------------------------------------------------------------- Addresses

/**
 * The top card, in this order: the live addresses; no Wi-Fi; public access off (same Wi-Fi only); a route that is set
 * up and waits for the server (empty address box); and only for a new or signed-out owner, the short getting-started list.
 */
@Composable private fun AddressCard(ctx: ScreenContext, wide: Boolean) {
    when (HomeCards.address(ctx.d)) {
        HomeAddressCard.ADDRESSES -> AddressesCard(ctx, wide, Addresses.options(ctx.d.net))
        HomeAddressCard.NO_WIFI -> NoWifiCard()
        HomeAddressCard.LOCAL_ONLY -> LocalOnlyCard(ctx, wide)
        HomeAddressCard.WAITING -> WaitingAddressesCard(ctx, wide)
        HomeAddressCard.SETUP -> OnlineSetupPrompt(ctx)
    }
}

/** No Wi-Fi: the server needs it to start comfortably (mobile data would carry every player's traffic). */
@Composable private fun NoWifiCard() {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.WifiOff, Palette.Warn, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Wi-Fi isn't connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Connect to Wi-Fi to get started. Hosting on mobile data uses a lot of data.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The address box before the route is live: same layout as the real one, empty, with what happens next. */
@Composable private fun WaitingAddressesCard(ctx: ScreenContext, wide: Boolean) {
    val d = ctx.d
    val view = d.net
    val provider = view.active ?: NetworkStatus.homeProvider(view)
    val tone = NetworkStatus.tone(provider, view)
    val (join, joinTone) = joinStatus(d)
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.Card.copy(alpha = 0.96f), contentColor = Palette.Text, border = BorderStroke(1.dp, Palette.Line)) {
        Column {
            val java = @Composable { m: Modifier -> AddressRow(R.drawable.ic_java, "Java TCP", "Shows when the server starts", null, m, enabled = false) {} }
            val bedrock = @Composable { m: Modifier -> AddressRow(R.drawable.ic_grass_block, "Bedrock UDP", "Shows when the server starts", null, m, enabled = false) {} }
            if (wide) Row(Modifier.height(IntrinsicSize.Min)) { java(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); bedrock(Modifier.weight(1f)) }
            else { java(Modifier); HorizontalDivider(Modifier.padding(start = 72.dp), color = Palette.Line); bedrock(Modifier) }
            HorizontalDivider(color = Palette.Line)
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                view.switching?.let { WorkingLine(it.step) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusText(if (provider.health == LinkHealth.OFF) "${provider.route.title} ${provider.status.lowercase()} · ${provider.detail ?: "Ready to start the server"}" else join,
                        if (provider.health == LinkHealth.OFF) tone else joinTone, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ManageButton(ctx)
                }
                provider.detail?.takeIf { tone == Tone.ERROR }?.let { Text(it, color = Palette.Bad, style = MaterialTheme.typography.bodySmall) }
                if (provider.route == PublicRoute.PLAYIT && provider.status == "Authentication required")
                    PrimaryButton("Connect Playit again", { ctx.online.connectPlayit() }, Modifier.fillMaxWidth(), enabled = !d.busy)
                if (provider.route == PublicRoute.PLAYIT && playitOverLimit(view)) PlayitLimitActions(ctx)
                ctx.online.note?.let { (text, noteTone) -> Banner(text, noteTone, onDismiss = ctx.online::dismissNote) }
                ServiceChips(d)
                d.localAddress?.let { LocalAddressChip(it) }
            }
        }
    }
}

/** Public access off: only the same-Wi-Fi addresses, where to turn a route on, and the routes as small chips. */
@Composable private fun LocalOnlyCard(ctx: ScreenContext, wide: Boolean) {
    val d = ctx.d
    val context = LocalContext.current
    val java = d.lanJavaAddress ?: return NoWifiCard()
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.Card.copy(alpha = 0.96f), contentColor = Palette.Text, border = BorderStroke(1.dp, Palette.Line)) {
        Column {
            Text("Same Wi-Fi only", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            val javaRow = @Composable { m: Modifier ->
                AddressRow(R.drawable.ic_java, "Java TCP", java.substringBeforeLast(':'), java.substringAfterLast(':'), m) { copyText(context, "Java address", java) }
            }
            val bedrockRow = @Composable { m: Modifier ->
                d.localBedrockAddress?.let { b -> AddressRow(R.drawable.ic_grass_block, "Bedrock UDP", b.substringBeforeLast(':'), b.substringAfterLast(':'), m) { copyText(context, "Bedrock address", b.substringBeforeLast(':')) } }
            }
            if (wide) Row(Modifier.height(IntrinsicSize.Min)) { javaRow(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); bedrockRow(Modifier.weight(1f)) }
            else { javaRow(Modifier); HorizontalDivider(Modifier.padding(start = 72.dp), color = Palette.Line); bedrockRow(Modifier) }
            HorizontalDivider(color = Palette.Line)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Public access is off", color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { ctx.vm.navigate(Screen.SETTINGS) }) { Text("Turn on", color = Palette.Info, fontWeight = FontWeight.Bold) }
            }
            // What is ready to turn on, as a small icon bar instead of two large cards below the controls.
            ServiceChips(d, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

/**
 * New or signed-out owner: what to do to get friends in, as three short steps. Only Playit needs a sign-in; the name is
 * optional. A route chosen but not set up (NetBird, Oracle) says what it waits for, in the same words as Settings.
 */
@Composable private fun OnlineSetupPrompt(ctx: ScreenContext) {
    val d = ctx.d
    val view = d.net
    val provider = NetworkStatus.homeProvider(view)
    val tone = NetworkStatus.tone(provider, view)
    val claimOpen = d.playitClaimUrl != null
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Public, Palette.Info, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Let friends join from anywhere", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (view.selected == null || provider.route == PublicRoute.PLAYIT) "Free. No router setup. About a minute."
                    else "${provider.route.title}: ${provider.status}", color = if (view.selected == null) Palette.Muted else tone.color,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        view.switching?.let { WorkingLine(it.step) }
        val playit = view.provider(PublicRoute.PLAYIT)
        // A saved key isn't "Connected" while Playit refuses the agent (deleted, revoked, or over the plan's agent limit).
        val playitDone = playit.configured && playit.health != LinkHealth.ERROR
        StepRow(1, "Connect Playit.gg", when {
            claimOpen -> "Approve Phoenix on playit.gg in your browser"
            playitDone -> "Connected"
            playit.configured -> playit.detail ?: playit.status
            else -> "Sign in (or sign up free) and approve Phoenix"
        }, if (playitDone) Tone.OK else if (claimOpen) Tone.WORKING else Tone.WARN)
        StepRow(2, "Start the server", if (d.phase == State.ONLINE) "Running" else "Your address appears here", if (d.phase == State.ONLINE) Tone.OK else Tone.IDLE)
        NetworkStatus.homeNameLine(d.net.name).let { (line, tone) -> StepRow(3, "Optional: a custom name", line, tone) }
        provider.detail?.takeIf { view.selected != null && tone == Tone.ERROR }?.let { Text(it, color = tone.color, style = MaterialTheme.typography.bodySmall) }
        when {
            playitOverLimit(view) -> PlayitLimitActions(ctx)
            view.switching != null -> Unit
            claimOpen -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Open playit.gg again", { ctx.online.open(d.playitClaimUrl!!) }, Modifier.weight(1f))
                SecondaryButton("Cancel", { ctx.vm.cancelPlayitSignIn() }, Modifier.weight(0.6f))
            }
            view.selected == null || (provider.route == PublicRoute.PLAYIT && !playitDone) ->
                PrimaryButton("Connect Playit.gg", { ctx.online.connectPlayit() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Public, enabled = !d.busy)
            provider.status == "Authentication required" && provider.route == PublicRoute.PLAYIT ->
                PrimaryButton("Connect Playit again", { ctx.online.connectPlayit() }, Modifier.fillMaxWidth(), enabled = !d.busy)
        }
        TextButton(onClick = { ctx.vm.navigate(Screen.SETTINGS) }) { Text("Other options: NetBird and more in Settings", color = Palette.Info) }
        ctx.online.note?.let { (text, noteTone) -> Banner(text, noteTone, onDismiss = ctx.online::dismissNote) }
        d.localAddress?.let { Text("Friends on the same Wi-Fi can join $it now.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---------------------------------------------------------------- Controls and numbers

@Composable private fun ServerControls(ctx: ScreenContext, modifier: Modifier) {
    val d = ctx.d
    val starting = d.phase == State.STARTING || d.phase == State.RESTARTING
    val stopping = d.phase == State.STOPPING
    // An ERROR can leave Java running (a failed start ending, a stop that timed out): Stop applies then, Start after it ends.
    val errorAlive = d.phase == State.ERROR && d.processAlive
    val canStart = ServerGate.startOffered(d.phase, d.processAlive, d.busy)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (starting) "Starting" else "Start", { ctx.vm.lifecycle(LifecycleOperation.START) }, Modifier.weight(1.15f),
                icon = Icons.Rounded.PlayArrow, enabled = canStart, loading = starting)
            SecondaryButton(if (stopping) "Stopping" else "Stop", { ctx.confirm(LifecycleOperation.STOP) }, Modifier.weight(1f),
                icon = Icons.Rounded.Stop, enabled = ServerGate.stopOffered(d.phase, d.processAlive), contentColor = Palette.Bad)
            SecondaryButton("Restart", { ctx.confirm(LifecycleOperation.RESTART) }, Modifier.weight(1f),
                icon = Icons.Rounded.RestartAlt, enabled = d.phase == State.ONLINE && !d.busy)
        }
        // The spawn area is being built before the server accepts players: real progress and a way out, not a silent wait.
        if (starting && d.spawnPrep.active) SpawnPrepCard(d.spawnPrep, ctx.vm::spawnPrepCancel)
        else if (starting || stopping || (d.busy && d.progress != null)) WorkingLine(d.progress ?: d.detail)
        // Stopped: what the last run did, and that an interrupted one continues at the next start.
        if ((isStopped(d) || d.phase == State.ERROR) && !d.busy) d.spawnPrepLast?.let { last ->
            if (last.willResume) Banner("Spawn preparation will resume on the next start", Tone.WORKING)
            Text(last.describe(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (errorAlive) TextButton(onClick = ctx.vm::forceStop) {
            Text("Force stop (unsaved changes may be lost)", color = Palette.Bad)
        }
        if (d.pendingRestart) Banner("Restart the server to apply your changes.", Tone.WARN, actionLabel = "Restart",
            onAction = { ctx.confirm(LifecycleOperation.RESTART) })
    }
}

/**
 * The area around spawn is being generated while the server starts (the optional spawn preparation mod). The server is
 * not joinable yet; Cancel makes it finish starting now and the area then fills in as people explore.
 */
@Composable private fun SpawnPrepCard(status: SpawnPrepStatus, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Info.copy(alpha = 0.10f))
            .border(1.dp, Palette.Info.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Info, strokeWidth = 2.dp)
            Text(status.headline().orEmpty(), color = Palette.Info, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp))
        }
        if (status.total > 0 && !status.cancelling) Meter((status.percent / 100).toFloat(), color = Palette.Info)
        else LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Palette.Info, trackColor = Palette.CardHigh)
        if (status.total > 0) Text(
            status.chunkLine() + if (status.failed > 0) " · ${status.failed} failed" else "",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Players can join once this finishes. Cancel starts the server now; the rest of the area is built as people explore.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        SecondaryButton(if (status.cancelling) "Cancelling…" else "Cancel", onCancel, Modifier.fillMaxWidth(), icon = Icons.Rounded.Close,
            enabled = !status.cancelling, contentColor = Palette.Bad)
    }
}

/** A blue progress strip so the user can see something is happening and what. */
@Composable fun WorkingLine(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Info.copy(alpha = 0.10f))
            .border(1.dp, Palette.Info.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Info, strokeWidth = 2.dp)
            Text(text, color = Palette.Info, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp))
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Palette.Info, trackColor = Palette.CardHigh)
    }
}

private fun gbNumber(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)

@Composable private fun MetricsGrid(d: Dashboard, storage: StorageSummary?, wide: Boolean, modifier: Modifier) {
    val online = d.phase == State.ONLINE
    // Measured TPS when available; otherwise from the average tick time (20 is the cap).
    val tps = d.tps ?: d.mspt?.let { min(20.0, 1000.0 / it.coerceAtLeast(0.1)) }
    val tile = Modifier.fillMaxHeight()
    val players = @Composable { m: Modifier ->
        MetricTile("Players", if (online) "${d.playerCount ?: d.players.size}" else "—", m.then(tile), Icons.Rounded.Group,
            unit = if (online) "/ ${d.maxPlayers}" else null)
    }
    val tpsTile = @Composable { m: Modifier ->
        MetricTile("TPS", if (online) tps?.let { "%.1f".format(it) } ?: "…" else "—", m.then(tile), Icons.Rounded.MonitorHeart,
            unit = if (online && d.tps == null && tps != null) "avg" else null,
            tone = if (!online || tps == null) Tone.IDLE else if (tps >= 19) Tone.OK else if (tps >= 15) Tone.WARN else Tone.ERROR)
    }
    val mspt = @Composable { m: Modifier ->
        MetricTile("MSPT", if (online) d.mspt?.let { "%.1f".format(it) } ?: "…" else "—", m.then(tile), Icons.Rounded.Speed,
            unit = if (online && d.mspt != null) "ms" else null,
            tone = d.mspt?.takeIf { online }?.let { if (it <= 40) Tone.OK else if (it <= 50) Tone.WARN else Tone.ERROR } ?: Tone.IDLE)
    }
    val uptime = @Composable { m: Modifier ->
        MetricTile("Uptime", if (online) shortElapsed(d.uptimeMs) else "—", m.then(tile), Icons.Rounded.Schedule)
    }
    val ram = @Composable { m: Modifier ->
        val used = d.heapUsedBytes; val max = d.heapMaxBytes
        if (online && used != null && max != null && max > 0) MetricTile("RAM", gbNumber(used), m.then(tile), Icons.Rounded.Memory,
            unit = "/ ${gb(max)}", meter = used.toFloat() / max)
        else {
            val total = d.phoneMemTotalBytes; val free = d.phoneMemAvailableBytes
            MetricTile("Phone RAM", if (total != null && free != null) gbNumber(total - free) else "—", m.then(tile), Icons.Rounded.Memory,
                unit = total?.let { "/ ${gb(it)}" }, meter = if (total != null && free != null && total > 0) (total - free).toFloat() / total else null)
        }
    }
    val battery = @Composable { m: Modifier ->
        val level = d.batteryPercent
        MetricTile("Battery", level?.let { "$it%" } ?: "—", m.then(tile),
            if (d.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
            unit = d.batteryTempC?.let { "${it.roundToInt()}°C" }, meter = level?.let { it / 100f },
            tone = if (level != null && level < 20 && !d.charging) Tone.WARN else Tone.IDLE)
    }
    val rows = if (wide) listOf(listOf(players, tpsTile, mspt, uptime), listOf(ram, battery))
        else listOf(listOf(players, tpsTile, mspt), listOf(uptime, ram, battery))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                // Wide: Storage and Networking share the second row with RAM and Battery, one slot each.
                if (wide && row.size < 4) {
                    StorageTile(d, storage, Modifier.weight(1f).fillMaxHeight())
                    NetworkTile(d, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        // Narrow: Storage and Networking sit together on their own row, half each.
        if (!wide) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StorageTile(d, storage, Modifier.weight(1f).fillMaxHeight())
            NetworkTile(d, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Phone and microSD used/free in one box, with where worlds live and a missing-card warning. */
@Composable private fun StorageTile(d: Dashboard, s: StorageSummary?, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Palette.Card).border(1.dp, Palette.Line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Storage", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        val phoneTotal = s?.phoneTotalBytes?.takeIf { it > 0 } ?: d.storageTotalBytes
        val phoneFree = s?.takeIf { it.phoneTotalBytes > 0 }?.phoneFreeBytes ?: d.storageFreeBytes
        val phoneTone = if (phoneFree != null && phoneFree < 2L * 1_073_741_824) Tone.WARN else Tone.IDLE
        NetLine("Phone", Pair(if (phoneTotal != null && phoneFree != null) "${gb(phoneFree)} free of ${gb(phoneTotal)}" else "…", phoneTone))
        NetLine("microSD", when {
            s == null -> "…" to Tone.IDLE
            s.cardLabel == null -> (if (s.worldsOnCard) "missing" to Tone.WARN else "none" to Tone.IDLE)
            else -> "${gb(s.cardFreeBytes)} free of ${gb(s.cardTotalBytes)}" to Tone.IDLE
        })
        if (s != null) Text("Worlds ${s.worldsWhere}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        s?.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.WARN.color, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable private fun NetworkTile(d: Dashboard, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Palette.Card).border(1.dp, Palette.Line, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Networking", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        val provider = NetworkStatus.homeProvider(d.net)
        NetLine(provider.route.title, provider.status to NetworkStatus.tone(provider, d.net))
        NetLine("Minecraft.How", minecraftHowStatus(d))
    }
}

@Composable private fun NetLine(name: String, status: Pair<String, Tone>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status.second, 8.dp)
        Text(
            buildAnnotatedString {
                append("$name ")
                withStyle(SpanStyle(color = if (status.second == Tone.IDLE) Palette.Muted else status.second.color, fontWeight = FontWeight.SemiBold)) { append(status.first) }
            },
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp),
        )
    }
}

// ---------------------------------------------------------------- Setup cards

/** The server stopped after reporting world damage: the newest backup to restore, never restored without asking. */
@Composable private fun WorldErrorsCard(ctx: ScreenContext, errors: WorldErrors, modifier: Modifier) {
    var confirm by remember { mutableStateOf(false) }
    val backup = errors.backup
    val taken = backup?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it.createdMs)) }
    Banner(
        "The server stopped after ${errors.count} world error${if (errors.count == 1) "" else "s"} in \"${errors.world}\" (${errors.first}). " +
            (taken?.let { "The last backup is from $it." } ?: "There is no backup of this world yet."),
        Tone.ERROR, modifier,
        actionLabel = if (backup != null) "Restore last backup" else null, onAction = { confirm = true },
        onDismiss = ctx.vm::dismissWorldErrors,
    )
    if (confirm && backup != null) ConfirmDialog("Restore ${errors.world}?",
        "${errors.world} goes back to $taken. The current copy is kept aside first.",
        "Restore", onConfirm = ctx.vm::restoreLastBackup, onDismiss = { confirm = false }, destructive = true)
}

@Composable private fun FinishSetupCard(ctx: ScreenContext, next: NextStep, modifier: Modifier) {
    val d = ctx.d
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Language, Palette.Warn)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(d.minecraftHostname?.let { "Finish linking $it" } ?: "Finish online play setup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val detail = d.networkActivity ?: d.netBirdAccountDetail.takeIf { it.isNotBlank() && d.minecraftHostname != null }
                detail?.let { Text(it, color = if (next.busy) Palette.Info else Palette.Muted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        PrimaryButton(next.label, next.run, Modifier.fillMaxWidth(), loading = next.busy, enabled = !d.busy)
        ctx.online.note?.let { (text, tone) -> Banner(text, tone, onDismiss = ctx.online::dismissNote) }
    }
}

@Composable private fun CreateServerCard(d: Dashboard, vm: PhoenixViewModel) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("Phoenix Server") }
    var seed by rememberSaveable { mutableStateOf("") }
    var eula by rememberSaveable { mutableStateOf(false) }
    Panel {
        PanelHeader("Create your server", Icons.Rounded.AddCircle, "Minecraft 26.2 with Fabric. Java and Bedrock players can join.")
        OutlinedTextField(name, { name = it.take(64) }, label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = !d.busy, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp))
        OutlinedTextField(seed, { seed = it.take(128) }, label = { Text("World seed (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            enabled = !d.busy, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(eula, { eula = it }, enabled = !d.busy, colors = CheckboxDefaults.colors(checkedColor = Palette.Brand, checkmarkColor = Palette.OnBrand))
            Text(
                buildAnnotatedString {
                    append("I accept the ")
                    withStyle(SpanStyle(color = Palette.Brand, fontWeight = FontWeight.SemiBold)) { append("Minecraft EULA") }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/eula"))) },
            )
        }
        if (d.busy) {
            WorkingLine(d.progress ?: "Downloading…")
            SecondaryButton("Cancel", vm::cancelInstall, Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Create server", { vm.install(eula, name, seed) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download,
                enabled = eula && name.isNotBlank())
            Text("Phoenix downloads and checks the official server files. The world is made on first start.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
