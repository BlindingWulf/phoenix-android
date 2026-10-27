package dev.phoenix.server

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs

/** The pack dialogs: naming a new or saved pack, renaming, editing, using and deleting one. */
private sealed interface PackDialog {
    data object NewEmpty : PackDialog
    data object SaveCurrent : PackDialog
    data class Rename(val id: String, val name: String) : PackDialog
    data class Use(val id: String) : PackDialog
    data class Delete(val id: String, val name: String) : PackDialog
}

/** What a picked .zip, .mrpack or .jar may be: the system picker labels some of them as plain binaries. */
private val packTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/x-modrinth-modpack+zip", "application/octet-stream", "*/*")

@Composable fun ModsScreen(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    val mods by vm.mods.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    // Re-read the folder as soon as an add or other action finishes.
    LaunchedEffect(d.busy) { if (!d.busy) vm.refreshLists() }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var details by remember { mutableStateOf<ModInfo?>(null) }
    var removing by remember { mutableStateOf<ModInfo?>(null) }
    var dialog by remember { mutableStateOf<PackDialog?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addMod) }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importModPack) }
    val packModPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> val id = editing; if (uri != null && id != null) vm.addModToPack(id, uri) }
    val vanilla = d.providerDisplayName == "Vanilla"
    val all = mods.orEmpty()
    val shown = all.filter {
        (filter == 0 || (filter == 1) == it.enabled) &&
            (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.id?.contains(query.trim(), ignoreCase = true) == true)
    }
    val packList = packs?.packs.orEmpty()

    val installed = @Composable {
        Panel {
            PanelHeader("Installed mods", Icons.Rounded.Extension, trailing = {
                SmallAction("Add mod", Icons.Rounded.Add, { picker.launch(arrayOf("*/*")) }, enabled = !vanilla && !d.busy)
            })
            OutlinedTextField(
                query, { query = it.take(60) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search mods…") }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("All (${all.size})", filter == 0, { filter = 0 })
                ChoicePill("Enabled (${all.count { it.enabled }})", filter == 1, { filter = 1 })
                ChoicePill("Disabled (${all.count { !it.enabled }})", filter == 2, { filter = 2 })
            }
            when {
                mods == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.Info, strokeWidth = 2.dp)
                    Text("Reading the mods folder…", color = Palette.Info, modifier = Modifier.padding(start = 10.dp))
                }
                shown.isEmpty() -> Text(
                    if (all.isEmpty()) "No mods yet. Tap Add mod to install a Fabric server mod (.jar)." else "No mods match.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column {
                    shown.forEachIndexed { index, mod ->
                        if (index > 0) HorizontalDivider(color = Palette.Line)
                        ModRow(mod, enabled = !vanilla, onToggle = { vm.setModEnabled(mod.fileName, it) },
                            onDetails = { details = mod }, onRemove = { removing = mod })
                    }
                }
            }
            Text("Mods load when the server starts. If it fails to start after a mod change, Phoenix puts back the mods from the last good start.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    val packPanel = @Composable {
        Panel {
            PanelHeader("Mod packs", Icons.Rounded.Inventory2, "Saved sets of mods. Use one to switch the server's mods in one step.", trailing = {
                SmallAction("Add pack", Icons.Rounded.Add, { packPicker.launch(packTypes) }, enabled = !vanilla && !d.busy)
            })
            when {
                packs == null -> Text("Reading mod packs…", color = Palette.Info, style = MaterialTheme.typography.bodyMedium)
                packList.isEmpty() -> Text("No packs yet. Add a .zip of server mods or a Modrinth .mrpack, or save the mods you use now.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                else -> Column {
                    packList.forEachIndexed { index, pack ->
                        if (index > 0) HorizontalDivider(color = Palette.Line)
                        PackRow(pack, enabled = !vanilla && !d.busy, onUse = { dialog = PackDialog.Use(pack.id) }, onEdit = { editing = pack.id },
                            onRename = { dialog = PackDialog.Rename(pack.id, pack.name) }, onDelete = { dialog = PackDialog.Delete(pack.id, pack.name) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Save current", { dialog = PackDialog.SaveCurrent }, Modifier.weight(1f), icon = Icons.Rounded.Save,
                    enabled = !vanilla && !d.busy && all.any { it.enabled && !it.required })
                SecondaryButton("New pack", { dialog = PackDialog.NewEmpty }, Modifier.weight(1f), icon = Icons.Rounded.CreateNewFolder, enabled = !vanilla && !d.busy)
            }
            Text("Packs are checked before use: Minecraft and Fabric versions, missing dependencies and duplicates. Phoenix's Fabric API, Geyser and Floodgate are added to every pack.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    val findMore = @Composable {
        Panel {
            PanelHeader("Find more mods", Icons.Rounded.Explore, "Fabric mods for Minecraft 26.2 that run on servers. Download the .jar, then tap Add mod.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Modrinth", { ctx.online.open("https://modrinth.com/mods") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.OpenInNew)
                SecondaryButton("Get Chunky", { ctx.online.open("https://modrinth.com/mod/chunky") }, Modifier.weight(1f), icon = Icons.Rounded.Map)
            }
            Text("Chunky pre-generates your world from the Worlds tab.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ctx.bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBrandBar() }
        item { ScreenHeader("Mods", ImageVector.vectorResource(R.drawable.ic_cube)) }
        if (vanilla) item { Banner("Mods need Fabric. Switch the server software in Settings.", Tone.WARN, actionLabel = "Settings", onAction = { vm.navigate(Screen.SETTINGS) }) }
        packs?.rollback?.let { text -> item { Banner(text, Tone.WARN, onDismiss = vm::dismissPackNotices) } }
        if (d.pendingRestart) item {
            Banner("Restart the server to apply mod changes.", Tone.WARN, actionLabel = "Restart", onAction = { ctx.confirm(LifecycleOperation.RESTART) })
        }
        if (d.busy && d.progress != null) item { WorkingLine(d.progress) }
        if (ctx.wide) item {
            // Wide screens and landscape: installed mods on the left, packs and links on the right.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1.1f)) { installed() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { packPanel(); findMore() }
            }
        } else {
            item { installed() }
            item { packPanel() }
            item { findMore() }
        }
    }

    details?.let { mod ->
        AlertDialog(
            onDismissRequest = { details = null }, containerColor = Palette.CardHigh,
            icon = { ModIcon(mod.id, mod.name, mod.enabled, 48.dp) },
            title = { Text(mod.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    mod.description?.let { Text(it, color = Palette.Text, style = MaterialTheme.typography.bodyMedium) }
                    mod.version?.let { InfoRow("Version", it) }
                    mod.id?.let { InfoRow("Mod id", it, mono = true) }
                    InfoRow("File", mod.fileName, mono = true)
                    InfoRow("Size", gb(mod.sizeBytes))
                    if (mod.required) Text(
                        if (mod.id == SpawnPrepManifest.MOD_ID) "Phoenix installs this mod for you. Turn it off in Settings › Server Settings (Prepare the area around spawn)."
                        else "Phoenix installs and checks this mod so Bedrock players can join.",
                        color = Palette.Info, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
        )
    }
    removing?.let { mod ->
        ConfirmDialog("Remove ${mod.name}?", "The mod file is removed from the server. Worlds keep any blocks or items it added, which may disappear.",
            "Remove", onConfirm = { vm.removeMod(mod.fileName) }, onDismiss = { removing = null }, destructive = true)
    }
    packs?.lastImport?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissPackNotices, containerColor = Palette.CardHigh,
            title = { Text("Added \"${result.name}\"", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${result.added} mod${if (result.added == 1) "" else "s"} added to the pack.", color = Palette.Text)
                    if (result.skipped.isNotEmpty()) {
                        Text("Left out:", color = Palette.Muted, fontWeight = FontWeight.SemiBold)
                        result.skipped.forEach { Text("• $it", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("Check the pack's status before you use it.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { editing = result.id; vm.dismissPackNotices() }) { Text("Open pack", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = vm::dismissPackNotices) { Text("Close", color = Palette.Muted) } },
        )
    }
    editing?.let { id ->
        val pack = packList.firstOrNull { it.id == id }
        if (pack == null) { if (packs != null) editing = null }
        else PackEditor(pack, busy = d.busy, onAddMod = { packModPicker.launch(arrayOf("*/*")) }, onRemoveMod = { vm.removeModFromPack(id, it) },
            onRename = { dialog = PackDialog.Rename(id, pack.name) }, onUse = { dialog = PackDialog.Use(id) }, onDismiss = { editing = null })
    }
    when (val open = dialog) {
        null -> Unit
        PackDialog.NewEmpty -> PackNameDialog("New mod pack", "An empty pack. Add mods to it, then use it when it's ready.", "", "Create",
            onDismiss = { dialog = null }) { vm.createModPack(it) }
        PackDialog.SaveCurrent -> PackNameDialog("Save current mods", "Copies the mods that are on now into a new pack. Nothing changes on the server.",
            "", "Save", onDismiss = { dialog = null }) { vm.saveModsAsPack(it) }
        is PackDialog.Rename -> PackNameDialog("Rename pack", "", open.name, "Rename", onDismiss = { dialog = null }) { vm.renameModPack(open.id, it) }
        is PackDialog.Use -> {
            val pack = packList.firstOrNull { it.id == open.id }
            if (pack == null) dialog = null
            else ConfirmDialog(
                "Use \"${pack.name}\"?",
                buildString {
                    append("The server's mods become this pack's ${pack.mods.size} mods. ")
                    append("Mods that are on now and aren't saved in a pack are saved as a new pack first, so nothing is lost. ")
                    if (!isStopped(d)) append("The server saves the world and restarts. ")
                    append("If it fails to start, Phoenix puts back the previous mods.")
                },
                "Use pack", onConfirm = { vm.useModPack(pack.id) }, onDismiss = { dialog = null },
            )
        }
        is PackDialog.Delete -> ConfirmDialog("Delete \"${open.name}\"?", "The pack and its copies of the mods are deleted. The mods on the server don't change.",
            "Delete", onConfirm = { vm.deleteModPack(open.id); if (editing == open.id) editing = null }, onDismiss = { dialog = null }, destructive = true)
    }
}

@Composable private fun PackRow(pack: ModPackInfo, enabled: Boolean, onUse: () -> Unit, onEdit: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.Inventory2, if (pack.inUse) Palette.Brand else Palette.Muted, size = 42.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(pack.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${pack.mods.size} mod${if (pack.mods.size == 1) "" else "s"} · ${gb(pack.sizeBytes)}", color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                when {
                    pack.inUse -> Tag("In use", Palette.Good)
                    pack.blocked -> Tag("Needs fixing", Palette.Bad)
                    pack.issues.isNotEmpty() -> Tag("Check", Palette.Warn)
                }
            }
        }
        if (!pack.inUse) CompactButton("Use", onUse, primary = true, enabled = enabled && !pack.blocked && pack.mods.isNotEmpty())
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${pack.name}", tint = Palette.Muted) }
            DropdownMenu(menu, { menu = false }, containerColor = Palette.CardHigh) {
                DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete", color = Palette.Bad) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Palette.Bad) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Edit Pack: the pack's mods, its check results, add and remove mods. Changes affect the pack only, until it is used. */
@Composable private fun PackEditor(
    pack: ModPackInfo, busy: Boolean, onAddMod: () -> Unit, onRemoveMod: (String) -> Unit, onRename: () -> Unit, onUse: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onRename) { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Rename ${pack.name}", tint = Palette.Muted) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val blocking = pack.issues.filter { it.blocking }
                val warnings = pack.issues.filter { !it.blocking }
                when {
                    pack.mods.isEmpty() -> StatusText("Empty: add mods to this pack.", Tone.IDLE)
                    blocking.isNotEmpty() -> StatusText("Needs fixing before it can be used", Tone.ERROR, bold = true)
                    else -> StatusText(if (pack.inUse) "In use on the server" else "Ready to use", Tone.OK, bold = true)
                }
                (blocking + warnings).forEach { issue ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (issue.blocking) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber, contentDescription = null,
                            tint = if (issue.blocking) Palette.Bad else Palette.Warn, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Text(issue.message, color = Palette.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider(color = Palette.Line)
                pack.mods.forEach { mod ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModIcon(mod.id, mod.name, true, 34.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(mod.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(mod.version?.let { "v$it · ${gb(mod.sizeBytes)}" } ?: gb(mod.sizeBytes), color = Palette.Muted,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveMod(mod.fileName) }, enabled = !busy) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove ${mod.name} from the pack", tint = Palette.Muted)
                        }
                    }
                }
                SecondaryButton("Add mod to pack", onAddMod, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = !busy)
                if (pack.inUse) Text("Edits change the saved pack. Tap Use again later to load them on the server.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (!pack.inUse) TextButton(onClick = { onUse(); onDismiss() }, enabled = !busy && !pack.blocked && pack.mods.isNotEmpty()) {
                Text("Use pack", color = if (!busy && !pack.blocked && pack.mods.isNotEmpty()) Palette.Brand else Palette.Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done", color = Palette.Muted) } },
    )
}

@Composable private fun PackNameDialog(title: String, body: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val clean = runCatching { ModPackStore.checkName(name) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (body.isNotEmpty()) Text(body, color = Palette.Muted)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Pack name") }, singleLine = true, colors = phoenixFieldColors(),
                    shape = RoundedCornerShape(12.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { clean?.let { onDone(it); onDismiss() } }, enabled = clean != null) {
                Text(confirm, color = if (clean != null) Palette.Brand else Palette.Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

@Composable private fun ModRow(mod: ModInfo, enabled: Boolean, onToggle: (Boolean) -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onDetails).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ModIcon(mod.id, mod.name, mod.enabled, 42.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(mod.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (mod.enabled) Palette.Text else Palette.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mod.version?.let { "v$it" } ?: gb(mod.sizeBytes), color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (mod.required) Tag(if (mod.id == SpawnPrepManifest.MOD_ID) "Managed" else "Required", Palette.Info)
            }
        }
        Switch(mod.enabled, onToggle, enabled = enabled && !mod.required, colors = phoenixSwitchColors())
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${mod.name}", tint = Palette.Muted) }
            DropdownMenu(menu, { menu = false }, containerColor = Palette.CardHigh) {
                DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    onClick = { menu = false; onDetails() })
                if (!mod.required) DropdownMenuItem(text = { Text("Remove", color = Palette.Bad) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Palette.Bad) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

private val modColors = listOf(Color(0xFF5B8DEF), Color(0xFF3FB0AC), Color(0xFFE07A2E), Color(0xFFB66DE0), Color(0xFFDB5B7A), Color(0xFF8BC34A))

/** A colored tile with the mod's initial; known Phoenix mods get fixed colors. */
@Composable private fun ModIcon(id: String?, name: String, enabled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val color = when (id) {
        "fabric-api" -> Color(0xFFDBB46B)
        "geyser-fabric" -> Color(0xFF2F7DD1)
        "floodgate" -> Color(0xFF3FB0AC)
        "chunky" -> Color(0xFFE07A2E)
        else -> modColors[abs((id ?: name).hashCode()) % modColors.size]
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = if (enabled) 1f else 0.35f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.42f).sp)
    }
}
