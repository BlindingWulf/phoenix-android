package dev.phoenix.server

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.phoenix.core.ServerController.State

/** What the Players & Console screen shows: both windows, or one of them full screen. */
private enum class PanelMode { BOTH, PLAYERS, CONSOLE }

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlayersScreen(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    // Read inside the console panel only, so the twice-a-second console poll doesn't recompose the players list.
    val console = vm.console.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var frozen by remember { mutableStateOf<List<ConsoleEntry>?>(null) }
    var banning by remember { mutableStateOf<String?>(null) }
    var access by rememberSaveable { mutableStateOf(false) }
    // Player addresses in the console are hidden on screen by default; the server's own log keeps them.
    var showAddresses by rememberSaveable { mutableStateOf(false) }
    // Phoenix's own internal lines are hidden by default; warnings and errors never are.
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(PanelMode.BOTH) }
    val keyboard = WindowInsets.isImeVisible
    val online = d.phase == State.ONLINE
    val count = if (online) d.playerCount ?: d.players.size else null
    val playersPanel = @Composable { m: Modifier, collapsible: Boolean ->
        PlayersPanel(d, m, grid = mode == PanelMode.PLAYERS, onCommand = vm::playerCommand, onBan = { banning = it },
            onMessage = { input = "/tell $it "; mode = PanelMode.BOTH }, onAccess = { access = true },
            fullscreen = mode == PanelMode.PLAYERS, onFullscreen = { mode = if (mode == PanelMode.PLAYERS) PanelMode.BOTH else PanelMode.PLAYERS },
            onCollapse = if (collapsible) ({ mode = PanelMode.CONSOLE }) else null)
    }
    val consolePanel = @Composable { m: Modifier ->
        ConsolePanel(
            online, frozen ?: console.value.orEmpty(), input, { input = it }, paused = frozen != null, modifier = m, consoleNote = d.consoleNote,
            onSend = { if (input.isNotBlank()) { vm.sendConsoleInput(input); input = "" } },
            onClear = { frozen = null; vm.clearConsole() },
            onPause = { frozen = if (frozen == null) console.value.orEmpty() else null },
            showAddresses = showAddresses, onToggleAddresses = { showAddresses = !showAddresses }, compact = ctx.wide,
            fullscreen = mode == PanelMode.CONSOLE, onFullscreen = { mode = if (mode == PanelMode.CONSOLE) PanelMode.BOTH else PanelMode.CONSOLE },
            diagnostics = diagnostics, onToggleDiagnostics = { diagnostics = !diagnostics }, onExportLog = vm::exportServerLog,
        )
    }
    // With one window full screen, a green button above it brings the other one back.
    val showOther = @Composable {
        when (mode) {
            PanelMode.CONSOLE -> ShowPill("Show players", Icons.Rounded.Group, count) { mode = PanelMode.BOTH }
            PanelMode.PLAYERS -> ShowPill("Show console", Icons.Rounded.Terminal, null) { mode = PanelMode.BOTH }
            PanelMode.BOTH -> Unit
        }
    }
    val bottom = if (keyboard) 8.dp else ctx.bottomInset + if (ctx.wide) 4.dp else 0.dp
    if (ctx.wide && mode == PanelMode.BOTH) Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding().padding(bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Landscape: the header sits above the players list only, so the console starts at the top of the screen and gets
        // the full height, with its input anchored at its bottom.
        Column(Modifier.weight(0.36f).fillMaxHeight()) {
            if (!keyboard) { TopBrandBar(); ScreenHeader("Players & Console", Icons.Rounded.Group); Spacer(Modifier.height(8.dp)) }
            else Spacer(Modifier.statusBarsPadding().height(10.dp))
            playersPanel(Modifier.weight(1f).fillMaxWidth(), false)
        }
        consolePanel(Modifier.weight(0.64f).fillMaxHeight().statusBarsPadding().padding(top = 10.dp))
    } else if (ctx.wide) Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding().padding(bottom = bottom)) {
        // Landscape, one window full screen: the brand and title share one line with the way back, so the window keeps the height.
        if (!keyboard) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_phoenix), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            BrandTitle(size = 18.sp)
            Text("Players & Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp).weight(1f))
            showOther()
        } else Spacer(Modifier.statusBarsPadding().height(10.dp))
        if (mode == PanelMode.PLAYERS) playersPanel(Modifier.weight(1f).fillMaxWidth(), false) else consolePanel(Modifier.weight(1f).fillMaxWidth())
    } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).imePadding().padding(bottom = bottom)) {
        TopBrandBar()
        ScreenHeader("Players & Console", Icons.Rounded.Group)
        Spacer(Modifier.height(8.dp))
        when (mode) {
            PanelMode.BOTH -> {
                if (!keyboard) { playersPanel(Modifier.fillMaxWidth().heightIn(max = 250.dp), true); Spacer(Modifier.height(12.dp)) }
                consolePanel(Modifier.weight(1f).fillMaxWidth())
            }
            PanelMode.CONSOLE -> {
                if (!keyboard) { showOther(); Spacer(Modifier.height(8.dp)) }
                consolePanel(Modifier.weight(1f).fillMaxWidth())
            }
            PanelMode.PLAYERS -> {
                showOther(); Spacer(Modifier.height(8.dp))
                playersPanel(Modifier.weight(1f).fillMaxWidth(), false)
            }
        }
    }
    banning?.let { name ->
        ConfirmDialog("Ban ${name.trimStart('.')}?", "They are disconnected and can't join again until you unban them with /pardon $name.",
            "Ban", onConfirm = { vm.playerCommand("ban", name) }, onDismiss = { banning = null }, destructive = true)
    }
    if (access) {
        val lists by vm.access.collectAsStateWithLifecycle()
        AccessDialog(lists, onCommand = vm::accessCommand, onDismiss = { access = false })
    }
}

/** Who can join: the whitelist switch, whitelisted names (Java, or Bedrock with a "." prefix) and banned players. */
@Composable private fun AccessDialog(lists: AccessLists?, onCommand: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val online = lists?.online == true
    val valid = name.trim().matches(Regex("\\.?[A-Za-z0-9_ ]{1,32}")) && !name.trim().endsWith(" ")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text("Who can join", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lists == null) Text("Reading the server's lists…", color = Palette.Info)
                else {
                    if (!online) Banner("Start the server to change these lists.", Tone.IDLE)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Whitelist", fontWeight = FontWeight.SemiBold)
                            Text(if (lists.whitelistOn) "Only listed players can join" else "Off: anyone with the address can join",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(lists.whitelistOn, { onCommand(if (it) "whitelist-on" else "whitelist-off", null) }, enabled = online, colors = phoenixSwitchColors())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(name, { name = it.take(33) }, singleLine = true, enabled = online, modifier = Modifier.weight(1f),
                            placeholder = { Text("Player name") }, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp))
                        Spacer(Modifier.width(8.dp))
                        CompactButton("Add", { onCommand("whitelist-add", name.trim()); name = "" }, primary = true, enabled = online && valid)
                    }
                    Text("Bedrock players: put a dot before the gamertag, like .Steve.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    if (lists.whitelist.isEmpty()) Text("No one is on the whitelist.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    lists.whitelist.forEach { player ->
                        AccessRow(player, "Remove", online) { onCommand("whitelist-remove", player) }
                    }
                    HorizontalDivider(color = Palette.Line)
                    Text("Banned players", fontWeight = FontWeight.SemiBold)
                    if (lists.banned.isEmpty()) Text("No one is banned.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    lists.banned.forEach { player -> AccessRow(player, "Unban", online) { onCommand("pardon", player) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
    )
}

@Composable private fun AccessRow(player: String, action: String, enabled: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Card).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelAvatar(player, 28.dp)
        Text(player, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onAction, enabled = enabled) { Text(action, color = if (enabled) Palette.Brand else Palette.Faint, fontWeight = FontWeight.SemiBold) }
    }
}

/** The green button that brings the hidden window back, with the player count when it is the players. */
@Composable private fun ShowPill(text: String, icon: ImageVector, count: Int?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.clip(shape).background(Palette.Good.copy(alpha = 0.14f)).border(1.dp, Palette.Good.copy(alpha = 0.5f), shape)
        .clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.Good, modifier = Modifier.size(18.dp))
        Text(text, color = Palette.Good, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
        count?.let { CountBadge("$it") }
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Palette.Good, modifier = Modifier.padding(start = 2.dp).size(20.dp))
    }
}

@Composable private fun CountBadge(text: String) {
    Text(text, color = Palette.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Bg).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A compact header button (36 dp), so a phone held upright fits the title, count and every action on one line. */
@Composable private fun HeaderButton(icon: ImageVector, description: String, tint: Color = Palette.Muted, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable private fun PlayersPanel(
    d: Dashboard, modifier: Modifier, grid: Boolean, onCommand: (String, String) -> Unit, onBan: (String) -> Unit, onMessage: (String) -> Unit,
    onAccess: () -> Unit, fullscreen: Boolean, onFullscreen: () -> Unit, onCollapse: (() -> Unit)?,
) {
    val online = d.phase == State.ONLINE
    Panel(modifier, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (online) Tone.OK else Tone.IDLE, 10.dp)
            Text("Online Players", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            CountBadge(if (online) "${d.playerCount ?: d.players.size} / ${d.maxPlayers}" else "Offline")
            HeaderButton(Icons.Rounded.VerifiedUser, "Who can join: whitelist and bans", Palette.Text, onAccess)
            onCollapse?.let { HeaderButton(Icons.Rounded.ExpandLess, "Hide players", onClick = it) }
            HeaderButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fullscreen) "Leave full screen" else "Players full screen", onClick = onFullscreen)
        }
        when {
            !online -> Text("Start the server to see who's playing.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            d.players.isEmpty() -> Text("No one is online yet. Share the address from Home.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            // Full screen: as many columns as fit, so a big server shows everyone at once.
            grid -> LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(d.players, key = { it.name }) { PlayerRow(it, onCommand, onBan, onMessage, spaced = false) }
            }
            else -> Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                d.players.forEach { key(it.name) { PlayerRow(it, onCommand, onBan, onMessage) } }
            }
        }
    }
}

/** The player's real skin face once it has loaded, the generated avatar until then or if there is none. */
@Composable private fun PlayerFace(player: PlayerInfo, size: Dp) {
    val context = LocalContext.current.applicationContext
    val face by produceState(PlayerSkins.cached(player.uuid)?.asImageBitmap(), player.uuid) {
        val uuid = player.uuid ?: return@produceState
        if (value == null) value = withContext(Dispatchers.IO) { PlayerSkins.face(context, uuid) }?.asImageBitmap()
    }
    face?.let {
        Image(it, contentDescription = null, filterQuality = FilterQuality.None, contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)))
    } ?: PixelAvatar(player.name, size)
}

@Composable private fun PlayerRow(player: PlayerInfo, onCommand: (String, String) -> Unit, onBan: (String) -> Unit, onMessage: (String) -> Unit, spaced: Boolean = true) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.CardHigh).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PlayerFace(player, 36.dp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(player.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (player.op) Image(painterResource(R.drawable.ic_crown), contentDescription = "Operator", modifier = Modifier.padding(start = 6.dp).size(16.dp))
            }
            Text(if (player.bedrock) "Bedrock" else "Java", color = if (player.bedrock) Palette.Info else Palette.Brand,
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        player.onlineForMs?.let { Text(shortElapsed(it), color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${player.displayName}", tint = Palette.Muted) }
            DropdownMenu(menu, { menu = false }, containerColor = Palette.CardHigh) {
                DropdownMenuItem(text = { Text("Message") }, leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
                    onClick = { menu = false; onMessage(player.name) })
                DropdownMenuItem(text = { Text(if (player.op) "Remove operator" else "Make operator") },
                    leadingIcon = { Icon(if (player.op) Icons.Rounded.RemoveModerator else Icons.Rounded.AddModerator, contentDescription = null) },
                    onClick = { menu = false; onCommand(if (player.op) "deop" else "op", player.name) })
                DropdownMenuItem(text = { Text("Kick") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                    onClick = { menu = false; onCommand("kick", player.name) })
                DropdownMenuItem(text = { Text("Ban", color = Palette.Bad) }, leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = null, tint = Palette.Bad) },
                    onClick = { menu = false; onBan(player.name) })
            }
        }
    }
    if (spaced) Spacer(Modifier.height(6.dp))
}

@Composable private fun ConsolePanel(
    online: Boolean, lines: List<ConsoleEntry>, input: String, onInput: (String) -> Unit, paused: Boolean, modifier: Modifier,
    onSend: () -> Unit, onClear: () -> Unit, onPause: () -> Unit,
    showAddresses: Boolean, onToggleAddresses: () -> Unit, compact: Boolean, fullscreen: Boolean, onFullscreen: () -> Unit,
    diagnostics: Boolean, onToggleDiagnostics: () -> Unit, onExportLog: (Uri) -> Unit,
    consoleNote: String? = null,
) {
    val context = LocalContext.current
    // Diagnostic lines are INFO-level Phoenix internals and status replies; they are the only lines a filter may hide.
    val shown = remember(lines, diagnostics) { if (diagnostics) lines else lines.filterNot { it.line.kind == Console.Kind.DIAGNOSTIC } }
    val hidden = lines.size - shown.size
    val warnings = remember(lines) { lines.count { it.line.kind == Console.Kind.WARN } }
    val errors = remember(lines) { lines.count { it.line.kind == Console.Kind.ERROR || it.line.kind == Console.Kind.CRASH } }
    // Stack traces start collapsed. Which ones the reader opened is kept by entry id, and forgotten once the entry leaves the window.
    val openTraces = remember { mutableStateMapOf<Long, Boolean>() }
    LaunchedEffect(lines) { val live = lines.mapTo(HashSet<Long>()) { it.id }; openTraces.keys.retainAll(live) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atLatest by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
    // Keyed rows keep their place when a line arrives, so at the newest line the console stopped following (Phone B
    // 2026-10-20). A reader at the newest line has the next layout pinned there instead; SideEffect runs before that layout.
    val newest = shown.firstOrNull()?.id
    val followed = remember { arrayOfNulls<Long>(1) }
    SideEffect { if (newest != followed[0]) { followed[0] = newest; if (atLatest) listState.requestScrollToItem(0) } }
    var menu by remember { mutableStateOf(false) }
    // The whole raw log file goes to the chosen document, not only the lines on screen.
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(onExportLog) }
    Panel(modifier, padding = 12.dp, spacing = if (compact) 8.dp else 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(22.dp))
            Text("Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (paused) Tag("Paused", Palette.Warn)
            HeaderButton(if (showAddresses) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if (showAddresses) "Hide player IP addresses" else "Show player IP addresses", onClick = onToggleAddresses)
            // Copies the lines on screen, oldest first, with each kept stack trace. The export has the whole file.
            HeaderButton(Icons.Rounded.ContentCopy, "Copy console") {
                copyText(context, "Console", shown.asReversed().joinToString("\n") { Console.plainText(it.line, showAddresses) })
            }
            Box {
                HeaderButton(Icons.Rounded.MoreVert, "More console actions") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = Palette.CardHigh) {
                    DropdownMenuItem(text = { Text(if (diagnostics) "Hide diagnostic lines" else "Show diagnostic lines") },
                        onClick = { menu = false; onToggleDiagnostics() })
                    DropdownMenuItem(text = { Text("Export full log") }, onClick = { menu = false; exporter.launch("phoenix-server-log.txt") })
                    DropdownMenuItem(text = { Text("Clear console") }, onClick = { menu = false; onClear() })
                }
            }
            HeaderButton(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume console" else "Pause console", Palette.Text, onPause)
            HeaderButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fullscreen) "Leave full screen" else "Console full screen", onClick = onFullscreen)
        }
        // Warning and error counts stay visible even when the reader has scrolled away from them.
        if (lines.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${shown.size} shown", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
            if (warnings > 0) Tag("$warnings ${if (warnings == 1) "warning" else "warnings"}", Palette.Warn)
            if (errors > 0) Tag("$errors ${if (errors == 1) "error" else "errors"}", Palette.Bad)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Console)) {
            if (lines.isEmpty()) Text(if (online) "No output yet" else "Start the server to see its console", color = Palette.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            // Newest first with reverseLayout: new lines enter at the bottom, and a reader scrolled up keeps their place
            // because rows are keyed. Each row's styled text is built once.
            else LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.id }) { entry ->
                    ConsoleLine(entry.line, showAddresses, expanded = openTraces[entry.id] == true,
                        onToggleTrace = { openTraces[entry.id] = openTraces[entry.id] != true })
                }
                // Last in the list, so it sits at the top of the console, above the oldest line.
                if (hidden > 0 || diagnostics) item(key = "diagnostics") { DiagnosticsRow(hidden, diagnostics, onToggleDiagnostics) }
            }
            if (!atLatest && lines.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.CardHigh).clickable(onClick = { scope.launch { listState.animateScrollToItem(0) } })
                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = Palette.Good, modifier = Modifier.size(18.dp))
                Text("Latest", color = Palette.Good, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (consoleNote != null) Text(consoleNote, style = MaterialTheme.typography.bodySmall, color = Palette.Warn)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, onInput, enabled = online && consoleNote == null, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text(if (consoleNote != null) "Console input is off" else if (online) "Chat, or /command" else "Server is offline") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(12.dp), colors = phoenixFieldColors(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = online && consoleNote == null && input.isNotBlank(), modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Brand, contentColor = Palette.OnBrand,
                    disabledContainerColor = Palette.CardHigh, disabledContentColor = Palette.Faint)) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
        if (!compact) Text("Plain text is chat from [Server]. Start with / for a command.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** The "N diagnostic lines hidden · Show" row at the top of the console; it turns into "Hide" once they are shown. */
@Composable private fun DiagnosticsRow(hidden: Int, showing: Boolean, onToggle: () -> Unit) {
    val text = if (showing) "Showing diagnostic lines · Hide" else "$hidden diagnostic ${if (hidden == 1) "line" else "lines"} hidden · Show"
    Text(text, color = Palette.Info, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp))
}

/**
 * One console entry. Warnings and errors carry their level word; a crash is a red card with its cause and report name.
 * A stack trace is a single "Show stack trace" link, collapsed until the reader opens it.
 */
@Composable private fun ConsoleLine(line: Console.Line, showAddresses: Boolean, expanded: Boolean, onToggleTrace: () -> Unit) {
    val color = when (line.kind) {
        Console.Kind.ERROR, Console.Kind.CRASH -> Palette.Bad
        Console.Kind.WARN -> Palette.Warn
        Console.Kind.JOIN -> Palette.Good
        Console.Kind.LEAVE -> Color(0xFFE0A86B)
        Console.Kind.CHAT -> Palette.Text
        Console.Kind.SENT -> Palette.Brand
        Console.Kind.PHOENIX -> Palette.Info
        // Server start, stop and save events are brighter than routine output, so they are easy to find.
        Console.Kind.SERVER, Console.Kind.SAVE -> Palette.Text
        Console.Kind.INFO -> Color(0xFFB4BCC6)
        Console.Kind.DIAGNOSTIC -> Palette.Faint
    }
    val crash = line.kind == Console.Kind.CRASH
    val emphasis = crash || line.kind == Console.Kind.ERROR || line.kind == Console.Kind.WARN
    val styled = remember(line, showAddresses) { buildAnnotatedString {
            line.time?.let { withStyle(SpanStyle(color = Palette.Muted)) { append("[$it] ") } }
            when (line.kind) {
                Console.Kind.ERROR -> withStyle(SpanStyle(color = Palette.Bad, fontWeight = FontWeight.Bold)) { append("ERROR ") }
                Console.Kind.WARN -> withStyle(SpanStyle(color = Palette.Warn, fontWeight = FontWeight.Bold)) { append("WARN ") }
                Console.Kind.CRASH -> withStyle(SpanStyle(color = Palette.Bad, fontWeight = FontWeight.Bold)) { append("CRASH ") }
                else -> Unit
            }
            when {
                line.kind == Console.Kind.SENT -> withStyle(SpanStyle(color = Palette.Brand, fontWeight = FontWeight.Bold)) { append("> ") }
                line.kind == Console.Kind.PHOENIX || line.text.startsWith("Playit agent: ") ->
                    withStyle(SpanStyle(color = Palette.Info, fontWeight = FontWeight.Bold)) { append("[Phoenix] ") }
                else -> withStyle(SpanStyle(color = Palette.Good)) { append("[Server] ") }
            }
            withStyle(SpanStyle(color = color, fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal)) {
                append(if (showAddresses) line.text else Console.hideAddresses(line.text))
            }
        } }
    val hasTrace = line.trace.isNotEmpty() || line.traceDropped > 0
    val card = if (crash) Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Bad.copy(alpha = 0.12f)).padding(8.dp) else Modifier
    Column(Modifier.fillMaxWidth().then(card)) {
        Text(styled, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        line.note?.let { Text("Crash report: $it", color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
        if (hasTrace) {
            Text(if (expanded) "Hide stack trace" else "Show stack trace · ${line.trace.size} lines", color = Palette.Info,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = onToggleTrace).padding(vertical = 2.dp))
            if (expanded) {
                val trace = line.trace.joinToString("\n") { if (showAddresses) it else Console.hideAddresses(it) }
                Text(trace, color = Palette.Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 2.dp))
                if (line.traceDropped > 0) Text("… ${line.traceDropped} more lines are in the full log", color = Palette.Muted,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
