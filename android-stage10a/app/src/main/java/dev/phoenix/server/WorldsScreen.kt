package dev.phoenix.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.phoenix.core.ServerController.State
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private sealed interface WorldDialog {
    data object Create : WorldDialog
    data object Import : WorldDialog
    data object Restore : WorldDialog
    data object Pregen : WorldDialog
    data object Chunks : WorldDialog
    data object ChunkyInfo : WorldDialog
    data class Load(val world: String) : WorldDialog
    data class Delete(val world: String) : WorldDialog
    data class RestoreOne(val backup: BackupInfo) : WorldDialog
    data class DeleteBackup(val backup: BackupInfo) : WorldDialog
}

private val dateFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
private fun date(ms: Long) = dateFormat.format(Date(ms))

@Composable fun WorldsScreen(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    val snapshot by vm.worlds.collectAsStateWithLifecycle()
    val mods by vm.mods.collectAsStateWithLifecycle()
    LaunchedEffect(d.busy) { if (!d.busy) vm.refreshLists() }
    var dialog by remember { mutableStateOf<WorldDialog?>(null) }
    // What a document picker result is for; saved so it survives the picker even if Android recreates the screen.
    var pendingName by rememberSaveable { mutableStateOf("") }
    var dimension by rememberSaveable { mutableStateOf("overworld") }
    val form = remember { PregenForm() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importWorld(it, pendingName) } }
    val chunkImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importChunks(it, dimension) } }
    val worldExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportWorld(pendingName, it) } }
    val backupExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportBackup(pendingName, it) } }
    val worlds = snapshot?.worlds.orEmpty()
    val backups = snapshot?.backups.orEmpty()
    val active = worlds.firstOrNull { it.active }
    val chunky = mods?.firstOrNull { it.id == "chunky" && it.enabled }
    val idle = !d.busy

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ctx.bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBrandBar() }
        if (d.busy && d.progress != null) item { WorkingLine(d.progress) }
        item {
            Panel {
                PanelHeader("Worlds", Icons.Rounded.Public, trailing = { SmallAction("Create World", Icons.Rounded.Add, { dialog = WorldDialog.Create }, enabled = idle) })
                if (snapshot == null) Text("Reading worlds…", color = Palette.Info)
                worlds.forEach { world ->
                    WorldRow(world, ctx, idle,
                        onLoad = { dialog = WorldDialog.Load(world.name) },
                        onBackup = { vm.backupWorld(world.name) },
                        onExport = { pendingName = world.name; worldExporter.launch("${world.name}-${System.currentTimeMillis()}.zip") },
                        onDelete = { dialog = WorldDialog.Delete(world.name) })
                }
            }
        }
        item {
            Panel {
                PanelHeader("World Actions", Icons.Rounded.Settings)
                val tiles = listOf<@Composable (Modifier) -> Unit>(
                    { ActionTile(Icons.Rounded.Add, "Create World", "Generate a new world", it, highlight = true, enabled = idle) { dialog = WorldDialog.Create } },
                    { ActionTile(Icons.Rounded.Download, "Import World", "Import from file", it, enabled = idle) { dialog = WorldDialog.Import } },
                    { ActionTile(Icons.Rounded.Backup, "Backup World", "Save world data", it, enabled = idle && active?.created == true) { active?.let { w -> vm.backupWorld(w.name) } } },
                    { ActionTile(Icons.Rounded.Restore, "Restore World", "Restore from backup", it, enabled = idle) { dialog = WorldDialog.Restore } },
                )
                (if (ctx.wide) tiles.chunked(4) else tiles.chunked(2)).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it(Modifier.weight(1f).fillMaxHeight()) } }
                }
            }
        }
        item {
            ChunkyPanel(ctx, chunky, active, form, dimension, { dimension = it },
                onStart = { dialog = WorldDialog.Pregen }, onImport = { dialog = WorldDialog.Chunks }, onInfo = { dialog = WorldDialog.ChunkyInfo })
        }
        item { WorldInfoPanel(ctx, active, backups.firstOrNull { it.world == active?.name }) }
    }

    when (val current = dialog) {
        null -> Unit
        WorldDialog.Create -> NameDialog("Create a world", if (d.phase == State.ONLINE) "The server restarts into the new world." else "It's generated the next time the server starts.",
            confirm = "Create", withSeed = true, onDismiss = { dialog = null }) { name, seed -> vm.createWorld(name, seed) }
        WorldDialog.Import -> NameDialog("Import a world", "Pick a world ZIP made with Minecraft Java Edition. It's added next to your current world.",
            confirm = "Choose ZIP", withSeed = false, onDismiss = { dialog = null }) { name, _ ->
            pendingName = name; importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        WorldDialog.Restore -> RestoreDialog(backups, idle, onDismiss = { dialog = null },
            onRestore = { dialog = WorldDialog.RestoreOne(it) },
            onExport = { pendingName = it.fileName; dialog = null; backupExporter.launch(it.fileName) },
            onDelete = { dialog = WorldDialog.DeleteBackup(it) })
        is WorldDialog.Load -> ConfirmDialog("Load ${current.world}?",
            if (d.phase == State.ONLINE || d.phase == State.STARTING) "The server saves this world and restarts with ${current.world}. Players reconnect afterwards."
            else "${current.world} is used the next time the server starts.",
            "Load", onConfirm = { vm.loadWorld(current.world) }, onDismiss = { dialog = null })
        is WorldDialog.Delete -> ConfirmDialog("Delete ${current.world}?", "The world folder is removed from this phone. Its backups are kept.",
            "Delete", onConfirm = { vm.deleteWorld(current.world) }, onDismiss = { dialog = null }, destructive = true)
        is WorldDialog.RestoreOne -> ConfirmDialog("Restore ${current.backup.world}?",
            "${current.backup.world} goes back to ${date(current.backup.createdMs)}. The current copy is kept aside first." +
                if (current.backup.world == active?.name && d.phase == State.ONLINE) " The server restarts." else "",
            "Restore", onConfirm = { vm.restoreBackup(current.backup.fileName) }, onDismiss = { dialog = WorldDialog.Restore }, destructive = true)
        is WorldDialog.DeleteBackup -> ConfirmDialog("Delete this backup?", "${current.backup.world}, ${date(current.backup.createdMs)}. This can't be undone.",
            "Delete", onConfirm = { vm.deleteBackup(current.backup.fileName) }, onDismiss = { dialog = WorldDialog.Restore }, destructive = true)
        WorldDialog.Pregen -> PregenWarning(form.radius.toIntOrNull() ?: 0, onDismiss = { dialog = null }, onImport = { dialog = WorldDialog.Chunks },
            onStart = { vm.chunkyStart(dimension, form.x.toIntOrNull() ?: 0, form.z.toIntOrNull() ?: 0, form.radius.toIntOrNull() ?: 0) })
        WorldDialog.Chunks -> ChunkImportDialog(active?.name ?: d.levelName, d.worldSeed, dimension, { dimension = it }, onDismiss = { dialog = null }) {
            dialog = null; chunkImporter.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        WorldDialog.ChunkyInfo -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = Palette.CardHigh,
            title = { Text("Pre-generation", fontWeight = FontWeight.Bold) },
            text = { Text("Chunky builds the world around a center point ahead of time, so exploring causes less lag. It's slow on a phone; " +
                "generating on a computer with the same seed and importing the files here is much faster.", color = Palette.Muted) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("OK", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
        )
    }
}

/** A view of the Minecraft artwork chosen from the world's name, so each world has its own picture. */
@Composable fun WorldThumbnail(name: String, modifier: Modifier, grayscale: Boolean = false) {
    val h = abs(name.hashCode())
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.CardHigh)) {
        HeroArtImage(
            colorFilter = if (grayscale) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.7f; scaleY = 1.7f
                translationX = ((h % 100) / 100f - 0.5f) * size.width * 0.6f
                translationY = (((h / 100) % 100) / 100f - 0.5f) * size.height * 0.6f
            }.alpha(if (grayscale) 0.6f else 1f),
        )
    }
}

@Composable private fun WorldRow(
    world: WorldInfo, ctx: ScreenContext, idle: Boolean,
    onLoad: () -> Unit, onBackup: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val d = ctx.d
    var menu by remember { mutableStateOf(false) }
    val running = d.phase == State.ONLINE || d.phase == State.STARTING || d.phase == State.RESTARTING
    val buttons = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (world.active) CompactButton(if (running) "Loaded" else "Play", { ctx.vm.lifecycle(LifecycleOperation.START) },
                icon = Icons.Rounded.PlayArrow, primary = true, enabled = idle && !running && isStopped(d))
            else CompactButton("Load", onLoad, icon = Icons.Rounded.PlayArrow, enabled = idle)
            CompactButton("Backup", onBackup, icon = Icons.Rounded.Backup, enabled = idle && world.created)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${world.name}", tint = Palette.Muted) }
                DropdownMenu(menu, { menu = false }, containerColor = Palette.CardHigh) {
                    DropdownMenuItem(text = { Text("Export ZIP") }, leadingIcon = { Icon(Icons.Rounded.IosShare, contentDescription = null) },
                        enabled = idle && world.created, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete", color = if (world.active) Palette.Faint else Palette.Bad) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = if (world.active) Palette.Faint else Palette.Bad) },
                        enabled = idle && !world.active, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
    InnerPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorldThumbnail(world.name, Modifier.size(if (ctx.wide) 104.dp else 92.dp, if (ctx.wide) 64.dp else 70.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(world.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (world.active) Tag("Current", Palette.Brand)
                }
                if (world.created) Row(verticalAlignment = Alignment.CenterVertically) {
                    Meta(Icons.Rounded.SdStorage, gb(world.sizeBytes))
                    world.lastPlayedMs?.let { Spacer(Modifier.width(12.dp)); Meta(Icons.Rounded.Schedule, date(it)) }
                } else Text("New. Created when the server starts.", color = Palette.Info, style = MaterialTheme.typography.bodySmall)
                if (!ctx.wide) buttons()
            }
            if (ctx.wide) buttons()
        }
    }
}

@Composable private fun Meta(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
    Text(text, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
}

@Composable private fun ActionTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, highlight: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(Palette.CardHigh).border(1.dp, Palette.Line, shape).clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlight) Palette.Brand else Palette.Text, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = if (highlight) Palette.Brand else Palette.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- Chunky

@Composable private fun ChunkyPanel(
    ctx: ScreenContext, chunky: ModInfo?, active: WorldInfo?, form: PregenForm, dimension: String, onDimension: (String) -> Unit,
    onStart: () -> Unit, onImport: () -> Unit, onInfo: () -> Unit,
) {
    val d = ctx.d
    val vm = ctx.vm
    val installed = chunky != null
    val status = d.chunky
    val running = status.state == "RUNNING" || status.state == "STARTING"
    val usable = installed && d.chunkyLoaded && d.phase == State.ONLINE && !d.busy
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Chunky Pregeneration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (installed) StatusText("Chunky installed", Tone.OK) else StatusText("Chunky not installed", Tone.ERROR)
                    chunky?.version?.let { Spacer(Modifier.width(8.dp)); Tag("v$it", Palette.Muted) }
                }
            }
            IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Info, contentDescription = "About pre-generation", tint = Palette.Muted) }
        }
        InnerPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorldThumbnail(active?.name ?: d.levelName, Modifier.size(92.dp, 62.dp), grayscale = !installed)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(if (installed) active?.name ?: d.levelName else "Chunky Not Installed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(if (installed) "Pre-generate chunks to reduce lag and explore new terrain." else "Install Chunky in Mods to pre-generate here. Importing chunks works without it.",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                !installed -> SmallAction("Open Mods", ImageVector.vectorResource(R.drawable.ic_cube), { vm.navigate(Screen.MODS) })
                d.phase == State.ONLINE && !d.chunkyLoaded -> Banner("Restart the server to load Chunky.", Tone.WARN, actionLabel = "Restart",
                    onAction = { ctx.confirm(LifecycleOperation.RESTART) })
                d.phase != State.ONLINE -> Text("Start the server to pre-generate.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            val fieldsEnabled = usable && !running
            Row(Modifier.alpha(if (installed) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Center X", form.x, { form.x = it }, Modifier.weight(1f), enabled = fieldsEnabled)
                NumberField("Center Z", form.z, { form.z = it }, Modifier.weight(1f), enabled = fieldsEnabled)
                NumberField("Radius", form.radius, { form.radius = it }, Modifier.weight(1f), enabled = fieldsEnabled)
            }
            val dimensionPicker = @Composable { m: Modifier -> DimensionPicker(dimension, onDimension, m, enabled = !running && !d.busy) }
            val startButton = @Composable { m: Modifier ->
                PrimaryButton(if (running) "Running" else "Start Pregen", onStart, m, icon = Icons.Rounded.PlayArrow,
                    enabled = usable && !running && (form.radius.toIntOrNull() ?: 0) in 100..30000, loading = status.state == "STARTING")
            }
            val importButton = @Composable { m: Modifier -> SecondaryButton("Import", onImport, m, icon = Icons.Rounded.Download, enabled = !d.busy) }
            if (ctx.wide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                dimensionPicker(Modifier.weight(1.2f)); startButton(Modifier.weight(1f)); importButton(Modifier.weight(0.8f))
            } else {
                dimensionPicker(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { startButton(Modifier.weight(1.3f)); importButton(Modifier.weight(1f)) }
            }
            if (status.state != "IDLE") ChunkyProgress(status)
            if (installed && (status.state == "RUNNING" || status.state == "PAUSED")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton("Pause", { vm.chunkyCommand("pause") }, Modifier.weight(1f), Icons.Rounded.Pause, enabled = status.state == "RUNNING" && d.phase == State.ONLINE)
                CompactButton("Resume", { vm.chunkyCommand("continue") }, Modifier.weight(1f), Icons.Rounded.PlayArrow, enabled = status.state == "PAUSED" && d.phase == State.ONLINE)
                CompactButton("Cancel", { vm.chunkyCommand("cancel") }, Modifier.weight(1f), Icons.Rounded.Close, enabled = d.phase == State.ONLINE)
            }
            if (status.needsConfirm && d.phase == State.ONLINE) Banner("Chunky asks you to confirm.", Tone.WARN, actionLabel = "Confirm", onAction = { vm.chunkyCommand("confirm") })
        }
    }
}

/** Center and radius typed for Chunky, in blocks. */
private class PregenForm {
    var x by mutableStateOf("0")
    var z by mutableStateOf("0")
    var radius by mutableStateOf("1000")
}

@Composable private fun ChunkyProgress(status: ChunkyStatus) {
    val percent = status.percent ?: 0.0
    val tone = when (status.state) { "DONE" -> Tone.OK; "PAUSED" -> Tone.WARN; "CANCELLED" -> Tone.IDLE; else -> Tone.WORKING }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusText(when (status.state) {
                "DONE" -> "Finished"; "PAUSED" -> "Paused"; "CANCELLED" -> "Cancelled"; "STARTING" -> "Starting…"; else -> "Generating"
            }, tone, Modifier.weight(1f), bold = true)
            Text("%.1f%%".format(percent), color = tone.color, fontWeight = FontWeight.Bold)
        }
        Meter((percent / 100).toFloat(), color = if (tone == Tone.WORKING) Palette.Brand else tone.color)
        val detail = listOfNotNull(
            status.processed?.let { "%,d chunks".format(it) }, status.rate?.let { "%.0f chunks/s".format(it) }, status.eta?.let { "ETA $it" },
        ).joinToString("  ·  ")
        if (detail.isNotEmpty()) Text(detail, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        status.lastMessage?.takeIf { status.state != "RUNNING" }?.let { Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
    }
}

@Composable private fun DimensionIcon(dimension: String) {
    when (dimension) {
        "the_nether" -> Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = Color(0xFFE5533D), modifier = Modifier.size(22.dp))
        "the_end" -> Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = Color(0xFFB98CF5), modifier = Modifier.size(22.dp))
        else -> Image(painterResource(R.drawable.ic_grass_block), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable private fun DimensionPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(50.dp).clip(shape).background(Palette.Bg).border(1.dp, Palette.Line, shape)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 12.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DimensionIcon(selected)
            Text(ChunkImport.label(selected), modifier = Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.Medium)
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Choose dimension", tint = Palette.Muted)
        }
        DropdownMenu(open, { open = false }, containerColor = Palette.CardHigh) {
            ChunkImport.dimensions.forEach { dim ->
                DropdownMenuItem(text = { Text(ChunkImport.label(dim)) }, leadingIcon = { DimensionIcon(dim) }, onClick = { onSelect(dim); open = false })
            }
        }
    }
}

// ---------------------------------------------------------------- World info

@Composable private fun WorldInfoPanel(ctx: ScreenContext, active: WorldInfo?, lastBackup: BackupInfo?) {
    val d = ctx.d
    val context = LocalContext.current
    val summary by ctx.vm.storage.collectAsStateWithLifecycle()
    // The meter follows the volume the world is on: the microSD once moved there, else the phone.
    val onCard = summary?.worldsOnCard == true
    val total = if (onCard) summary?.cardTotalBytes?.takeIf { summary?.cardLabel != null } else d.storageTotalBytes
    val free = if (onCard) summary?.cardFreeBytes?.takeIf { summary?.cardLabel != null } else d.storageFreeBytes
    val where = if (onCard) summary?.cardLabel?.let { "microSD ($it)" } else "phone"
    val storage = @Composable { m: Modifier ->
        InfoCell(Icons.Rounded.SdStorage, "World Storage", active?.takeIf { it.created }?.let { gb(it.sizeBytes) } ?: "—", m) {
            if (total != null && free != null && total > 0) {
                val used = (total - free).toFloat() / total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meter(used, Modifier.weight(1f)); Text("  ${gb(free)} free on $where", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
                }
            } else if (onCard) Text(summary?.warning ?: "microSD missing", color = Tone.WARN.color, style = MaterialTheme.typography.labelSmall)
        }
    }
    val seed = @Composable { m: Modifier ->
        InfoCell(Icons.Rounded.Grass, "World Seed", d.worldSeed ?: "Shown once the server runs", m.clickable(enabled = d.worldSeed != null) {
            d.worldSeed?.let { copyText(context, "World seed", it) }
        }, mono = d.worldSeed != null)
    }
    val backup = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            InfoCell(Icons.Rounded.Schedule, "Last Backup", lastBackup?.let { date(it.createdMs) } ?: "Never", Modifier.weight(1f))
            CompactButton("Backup Now", { active?.let { ctx.vm.backupWorld(it.name) } }, icon = Icons.Rounded.Backup, enabled = !d.busy && active?.created == true)
        }
    }
    Panel {
        PanelHeader("World Info", Icons.Rounded.Info)
        if (ctx.wide) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            storage(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); seed(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); backup(Modifier.weight(1.3f))
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                storage(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); seed(Modifier.weight(1f))
            }
            HorizontalDivider(color = Palette.Line)
            backup(Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun InfoCell(icon: ImageVector, label: String, value: String, modifier: Modifier, mono: Boolean = false, extra: (@Composable () -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = 2, overflow = TextOverflow.Ellipsis)
            extra?.invoke()
        }
    }
}

// ---------------------------------------------------------------- Dialogs

@Composable private fun NameDialog(title: String, body: String, confirm: String, withSeed: Boolean, onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var seed by rememberSaveable { mutableStateOf("") }
    val clean = runCatching { WorldManager.checkName(name) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = Palette.Muted)
                OutlinedTextField(name, { name = it.take(32) }, label = { Text("World name") }, singleLine = true, colors = phoenixFieldColors(),
                    isError = name.isNotEmpty() && clean == null, shape = RoundedCornerShape(12.dp),
                    supportingText = { if (name.isNotEmpty() && clean == null) Text("Letters, numbers, - and _ only", color = Palette.Bad) })
                if (withSeed) OutlinedTextField(seed, { seed = it.take(128) }, label = { Text("Seed (optional)") }, singleLine = true,
                    colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { clean?.let { onDone(it, seed); onDismiss() } }, enabled = clean != null) {
                Text(confirm, color = if (clean != null) Palette.Brand else Palette.Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

@Composable private fun RestoreDialog(
    backups: List<BackupInfo>, idle: Boolean, onDismiss: () -> Unit,
    onRestore: (BackupInfo) -> Unit, onExport: (BackupInfo) -> Unit, onDelete: (BackupInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text("Backups", fontWeight = FontWeight.Bold) },
        text = {
            if (backups.isEmpty()) Text("No backups yet. Tap Backup on a world to make one.", color = Palette.Muted)
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                backups.forEach { backup ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.world, fontWeight = FontWeight.SemiBold)
                            Text("${date(backup.createdMs)}  ·  ${gb(backup.sizeBytes)}", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onRestore(backup) }, enabled = idle) { Icon(Icons.Rounded.Restore, contentDescription = "Restore", tint = if (idle) Palette.Brand else Palette.Faint) }
                        IconButton(onClick = { onExport(backup) }, enabled = idle) { Icon(Icons.Rounded.IosShare, contentDescription = "Export", tint = Palette.Muted) }
                        IconButton(onClick = { onDelete(backup) }, enabled = idle) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Palette.Bad) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
    )
}

/** Shown before Chunky starts: pre-generating on a phone is slow, and importing is usually the better choice. */
@Composable private fun PregenWarning(radius: Int, onDismiss: () -> Unit, onImport: () -> Unit, onStart: () -> Unit) {
    val side = radius * 2 / 16 + 1
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Palette.Warn) },
        title = { Text("This will take a while", fontWeight = FontWeight.Bold) },
        text = {
            Text("About ${"%,d".format(side.toLong() * side)} chunks. On a phone this can take hours, the phone gets warm and players may notice lag.\n\n" +
                "It's recommended to pre-generate this world on a computer with Chunky (same seed, Minecraft 26.2) and import the files instead.",
                color = Palette.Muted)
        },
        confirmButton = {
            TextButton(onClick = { onStart(); onDismiss() }) { Text("Start anyway", color = Palette.Warn, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) }
                TextButton(onClick = onImport) { Text("Import file", color = Palette.Brand, fontWeight = FontWeight.Bold) }
            }
        },
    )
}

@Composable private fun ChunkImportDialog(world: String, seed: String?, dimension: String, onDimension: (String) -> Unit, onDismiss: () -> Unit, onPick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text("Import pre-generated chunks", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pick a ZIP of the world generated on a computer, or of just its region folder. Use the same seed${seed?.let { " ($it)" } ?: ""} and Minecraft 26.2.",
                    color = Palette.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChunkImport.dimensions.forEach { dim -> ChoicePill(ChunkImport.label(dim), dim == dimension, { onDimension(dim) }) }
                }
                Text("Phoenix backs up $world first. If the server is running, it restarts for the import.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onPick) { Text("Choose ZIP", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}
