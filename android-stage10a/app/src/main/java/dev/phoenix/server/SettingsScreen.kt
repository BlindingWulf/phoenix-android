package dev.phoenix.server

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.phoenix.core.ServerController.State

private enum class SettingsSection(val title: String, val subtitle: String, val icon: ImageVector) {
    NETWORK("Networking", "Choose one route for play. With Playit.gg, NetBird can still help your custom name show in Java's server list (Minecraft.How).", Icons.Rounded.Hub),
    SERVER("Server Settings", "Server name, game mode, and more", Icons.Rounded.Tune),
    DIAGNOSTICS("Performance", "Memory, live numbers and checks", Icons.Rounded.Speed),
    ABOUT("About", "Version, components and support", Icons.Rounded.Info),
}

/**
 * Four sections. Portrait: collapsible accordions (Networking open at first), each remembering its state. Landscape:
 * the section list on the left and the chosen section on the right, as before. A closed section isn't composed at all,
 * so it costs nothing and makes no requests.
 */
@Composable fun SettingsScreen(ctx: ScreenContext) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.NETWORK) }
    var open by rememberSaveable { mutableStateOf(setOf(SettingsSection.NETWORK.name)) }
    if (ctx.wide) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(0.34f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBrandBar()
            ScreenHeader("Settings", Icons.Rounded.Settings)
            SettingsSection.entries.forEach { MenuRow(it, it == section) { section = it } }
        }
        LazyColumn(
            Modifier.weight(0.66f).padding(start = 16.dp),
            contentPadding = PaddingValues(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp, bottom = ctx.bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { item(key = section.name) { SectionPanel(section, expanded = true, collapsible = false, onToggle = {}) { SectionContent(section, ctx) } } }
    } else LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = ctx.bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBrandBar() }
        item { ScreenHeader("Settings", Icons.Rounded.Settings) }
        SettingsSection.entries.forEach { s ->
            item(key = s.name) {
                SectionPanel(s, expanded = s.name in open, collapsible = true,
                    onToggle = { open = if (s.name in open) open - s.name else open + s.name }) { SectionContent(s, ctx) }
            }
        }
    }
}

@Composable private fun SectionContent(section: SettingsSection, ctx: ScreenContext) {
    when (section) {
        SettingsSection.NETWORK -> NetworkingSection(ctx)
        SettingsSection.SERVER -> ServerSettingsPanel(ctx)
        SettingsSection.DIAGNOSTICS -> DiagnosticsPanel(ctx)
        SettingsSection.ABOUT -> AboutPanel(ctx)
    }
}

/** A section card: its header always, its content only while expanded. */
@Composable private fun SectionPanel(section: SettingsSection, expanded: Boolean, collapsible: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Palette.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = collapsible, onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(section.icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(section.subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (collapsible) Icon(Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse ${section.title}" else "Expand ${section.title}",
                    tint = Palette.Muted, modifier = Modifier.rotate(if (expanded) 180f else 0f))
            }
            if (expanded) Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) { content() }
        }
    }
}

@Composable private fun MenuRow(section: SettingsSection, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Palette.CardHigh else Palette.Card)
            .border(1.dp, if (selected) Palette.Brand.copy(alpha = 0.5f) else Palette.Line, shape).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, tint = if (selected) Palette.Brand else Palette.Text, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(section.title, fontWeight = FontWeight.SemiBold)
            Text(section.subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

// ---------------------------------------------------------------- Networking: NetBird and Minecraft.How controls

@Composable private fun Overflow(content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = Palette.Muted) }
        DropdownMenu(open, { open = false }, containerColor = Palette.CardHigh) { content { open = false } }
    }
}

/** NetBird's account, connection and ports. Whether it is the route players use is the card's switch. */
@Composable fun NetBirdDetails(ctx: ScreenContext) {
    val d = ctx.d
    val online = ctx.online
    val (status, tone) = netBirdStatus(d)
    val next = online.next(d)
    val expired = !d.netBirdSignedIn && d.netBirdAccount.contains("expired", ignoreCase = true)
    val (account, accountTone) = when {
        d.netBirdAuthorizing -> "Signing in…" to Tone.WORKING
        d.netBirdSignedIn -> "Signed in" to Tone.OK
        expired -> "Expired" to Tone.WARN
        else -> "Not signed in" to Tone.IDLE
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusBox("Account", account, accountTone, Modifier.weight(1f))
        StatusBox("Connection", status, tone, Modifier.weight(1f))
        Overflow { close ->
            DropdownMenuItem(text = { Text("Sign in again") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null) },
                enabled = d.netBirdEnrolled && !d.netBirdAuthorizing, onClick = { close(); online.signInAgain(d) })
            DropdownMenuItem(text = { Text("Reconnect") }, leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                enabled = d.networkCanReconnect && d.phase == State.ONLINE && !d.busy && !d.networkBusy && d.net.selected?.usesNetBird == true,
                onClick = { close(); ctx.vm.retryTunnelSetup() })
            DropdownMenuItem(text = { Text("Open NetBird dashboard") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                onClick = { close(); online.open("https://app.netbird.io/") })
        }
    }
    if (expired) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hosting keeps working. Sign in again only to change ports.", color = Palette.Muted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { online.signInAgain(d) }, enabled = d.netBirdEnrolled) { Text("Sign in again", color = Palette.Warn, fontWeight = FontWeight.Bold) }
    }
    d.net.provider(PublicRoute.NETBIRD_EU).endpoints?.let { EndpointList(it, d.voiceChatPort > 0) }
    // Account setup steps: shown when NetBird was turned on (or is the route) and still needs them.
    val wanted = d.net.selected == PublicRoute.NETBIRD_EU || d.requestedRoute == PublicRoute.NETBIRD_EU || d.netBirdAuthorizing || d.networkBusy
    if (next != null && wanted) {
        PrimaryButton(next.label, next.run, Modifier.fillMaxWidth(), loading = next.busy, enabled = !d.busy)
        if (next.busy) d.networkActivity?.let { Text(it, color = Palette.Info, style = MaterialTheme.typography.bodySmall) }
    } else if (!d.networkHasIdentity && !wanted) Text("Turn NetBird on to sign in with your free NetBird account in your browser.",
        color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    if (d.net.selected == PublicRoute.NETBIRD_EU) ChecklistBox(d)
    online.note?.let { (text, noteTone) -> Banner(text, noteTone, onDismiss = online::dismissNote) }
}

/** The optional custom name: account, name, records and checks. Its switch (on the card) turns its use on or off. */
@Composable fun MinecraftHowDetails(ctx: ScreenContext) {
    val d = ctx.d
    val online = ctx.online
    val name = d.net.name
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("A free, easy name instead of numbers. Online play works without it, and it never changes ping.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Overflow { close ->
            if (d.minecraftHowHasSession || d.minecraftHowLinked) DropdownMenuItem(text = { Text("Sign out") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) }, onClick = { close(); online.signOutMinecraftHow() })
            DropdownMenuItem(text = { Text("Google account? Set up in browser") }, leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, contentDescription = null) },
                onClick = { close(); online.state.browserSetup = true })
            DropdownMenuItem(text = { Text("Open minecraft.how") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                onClick = { close(); online.open("https://minecraft.how/") })
        }
    }
    name.hostname?.let { hostname ->
        HostnameBox(hostname, dnsReady(d))
        name.detail?.let { Text(it, color = NetworkStatus.nameTone(name).takeIf { t -> t != Tone.IDLE }?.color ?: Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        d.nameListing?.let { NameListingBox(ctx, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Test DNS", ctx.vm::testCustomName, Modifier.weight(1f), icon = Icons.Rounded.Dns,
                enabled = name.enabled && d.net.active != null && !d.busy)
            // Not when Minecraft.How already publishes the right records: then only DNS caches lag, and saving again changes nothing.
            if (name.enabled && !dnsReady(d) && !name.recordsLive && d.minecraftHowLinked && d.net.active != null) PrimaryButton("Point to ${d.net.active!!.route.title}",
                { ctx.vm.linkCustomName(hostname) }, Modifier.weight(1f), enabled = !d.busy && !d.networkBusy && d.net.switching == null,
                loading = name.health == NameHealth.UPDATING)
        }
    }
    when {
        online.state.browserSetup -> BrowserSetup(ctx, onClose = { online.state.browserSetup = false })
        !d.minecraftHowLinked -> {
            if (d.minecraftHowHasSession || d.minecraftHowChecking) Text(if (d.minecraftHowChecking) "Checking your sign-in…" else d.minecraftHowState,
                color = if (d.minecraftHowChecking) Palette.Info else toneOf(d.minecraftHowState).color, style = MaterialTheme.typography.bodySmall)
            PrimaryButton(if (d.minecraftHostname == null) "Sign in to pick a name" else "Sign in to Minecraft.How", { online.signInMinecraftHow() },
                Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login, enabled = !d.busy && !d.networkBusy, loading = d.minecraftHowChecking)
            TextButton(onClick = { online.state.browserSetup = true }) {
                Text("Use a Google account? Set up in your browser", color = Palette.Info)
            }
        }
        else -> NameEditor(ctx)
    }
    Text("Java uses the name alone (an SRV record finds the port). Bedrock needs the name plus the port shown on Home. Voice chat keeps " +
        "the route's own address.", color = Palette.Faint, style = MaterialTheme.typography.bodySmall)
}

/**
 * Playit + NetBird + custom name: Playit Free only answers Java's server list for its own name, so the name's Java
 * record can lead to Phoenix's front door on NetBird instead. Pings show the server; games still run through Playit.
 */
@Composable private fun NameListingBox(ctx: ScreenContext, listing: NameListing) {
    val shape = RoundedCornerShape(12.dp)
    // Opens by itself when the owner has something to do; otherwise one short row.
    var open by rememberSaveable { mutableStateOf(false) }
    val needsAction = listing.needsSignIn || (listing.enabled && !listing.netBirdReady)
    val accent = when { listing.active -> Palette.Good; needsAction || listing.enabled -> Palette.Info; else -> Palette.Muted }
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.Bg).border(1.dp, accent.copy(alpha = if (listing.active) 0.35f else 0.45f), shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Extension, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Add-on: Java server list", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Tag(when { listing.active -> "ON"; listing.needsSignIn -> "SIGN IN"; !listing.enabled -> "OFF"; else -> "RECOMMENDED" },
                        if (listing.active) Palette.Good else if (listing.enabled) Palette.Info else Palette.Muted)
                    Text("Playit + NetBird", color = Palette.Faint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                }
            }
            // Turning it on is the whole setup: without NetBird's ports yet, it starts NetBird's connect and sign-in too.
            Switch(listing.enabled, { on -> ctx.vm.setNameListing(on); if (on && !listing.netBirdReady) ctx.online.connectForName() },
                enabled = !ctx.d.busy, colors = phoenixSwitchColors(), modifier = Modifier.scale(0.8f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = if (open || needsAction) "Hide add-on" else "Show add-on", tint = Palette.Muted,
                modifier = Modifier.rotate(if (open || needsAction) 180f else 0f))
        }
        if (open || needsAction) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listing.detail?.let {
            Text(it, color = when { listing.active -> Palette.Good; !listing.enabled -> Palette.Muted; else -> Palette.Warn },
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Java's server list then shows your name with the server's note and players. NetBird carries only that ping and the " +
            "first second of a join; games run through Playit at the same ping.", color = Palette.Faint, style = MaterialTheme.typography.bodySmall)
        if (listing.needsSignIn)
            // Connect, not sign-in only: it also starts NetBird when it isn't running, and the setup carries on after the sign-in.
            SecondaryButton("Sign in to NetBird", { ctx.online.connectForName() }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login,
                enabled = !ctx.d.busy && !ctx.d.netBirdAuthorizing)
        if (listing.enabled && !listing.netBirdReady)
            SecondaryButton("Set up NetBird (free)", { ctx.online.connectForName() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Hub,
                // Off while its setup runs (the detail above says which step).
                enabled = !ctx.d.busy && !ctx.d.networkBusy && listing.detail?.endsWith("…") != true)
        }
    }
}

@Composable private fun HostnameBox(hostname: String, ready: Boolean) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Bg).border(1.dp, if (ready) Palette.Good.copy(alpha = 0.35f) else Palette.Line, shape)
            .clickable { copyText(context, "Server address", hostname) }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FitText(hostname, if (ready) Palette.Good else Palette.Text, Modifier.weight(1f), maxSize = 16.sp)
        IconButton(onClick = { copyText(context, "Server address", hostname) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy server address", tint = if (ready) Palette.Good else Palette.Muted)
        }
    }
}

/** Choosing, checking and saving a name while signed in to Minecraft.How. */
@Composable private fun NameEditor(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    val zones = d.minecraftHowZones.ifEmpty { MinecraftHowDns.supportedSuffixes }
    val choice = remember(d.minecraftHostname, zones) {
        val saved = d.minecraftHostname?.let { runCatching { MinecraftHowDns.normalizeHostname(it, zones) }.getOrNull() }
        val suffix = zones.firstOrNull { saved?.endsWith(".$it") == true } ?: zones.first()
        (saved?.removeSuffix(".$suffix") ?: "") to suffix
    }
    var label by remember(choice) { mutableStateOf(choice.first) }
    var suffix by remember(choice) { mutableStateOf(choice.second) }
    var suffixMenu by remember { mutableStateOf(false) }
    val requested = MinecraftHowDns.composeHostname(label, suffix, zones)
    val idle = !d.busy && !d.minecraftHowChecking
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            label, { label = it.trim().lowercase().take(63) }, singleLine = true, modifier = Modifier.weight(1.2f),
            label = { Text("Public hostname") }, placeholder = { Text("my-server") },
            isError = label.isNotEmpty() && requested == null, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp),
        )
        Box(Modifier.weight(1f).padding(top = 8.dp)) {
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(shape).background(Palette.Bg).border(1.dp, Palette.Line, shape)
                    .clickable(enabled = zones.size > 1) { suffixMenu = true }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FitText(".$suffix", Palette.Text, Modifier.weight(1f), maxSize = 15.sp, minSize = 10.sp, bold = false)
                if (zones.size > 1) Icon(Icons.Rounded.ExpandMore, contentDescription = "Choose domain", tint = Palette.Muted)
            }
            DropdownMenu(suffixMenu, { suffixMenu = false }, containerColor = Palette.CardHigh) {
                zones.forEach { zone -> DropdownMenuItem(text = { Text(".$zone") }, onClick = { suffix = zone; suffixMenu = false }) }
            }
        }
    }
    when {
        label.isNotEmpty() && requested == null -> Text("Use 2–63 letters, numbers or hyphens.", color = Palette.Bad, style = MaterialTheme.typography.bodySmall)
        d.hostnameCheckName == requested && requested != null -> Text(d.hostnameCheckDetail, color = toneOf(d.hostnameCheckDetail).color, style = MaterialTheme.typography.bodySmall)
    }
    val saved = d.minecraftHostname != null && requested == d.minecraftHostname
    if (!saved) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Check", { requested?.let(vm::checkMinecraftHostname) }, Modifier.weight(1f), enabled = requested != null && idle)
        PrimaryButton("Save name", { requested?.let(vm::reserveMinecraftHostname) }, Modifier.weight(1f), enabled = requested != null && idle)
    }
}

/**
 * For Google accounts: Google won't sign in inside apps, so the user adds the records on minecraft.how in their own
 * browser and Phoenix only checks public DNS before using the name. The records follow the selected route.
 */
@Composable private fun BrowserSetup(ctx: ScreenContext, onClose: () -> Unit) {
    val d = ctx.d
    var name by rememberSaveable { mutableStateOf("") }
    val clean = remember(name) { runCatching { MinecraftHowDns.normalizeHostname(name, MinecraftHowDns.supportedSuffixes) }.getOrNull() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Bg).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Set up in your browser", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = Palette.Muted) }
        }
        val records = d.nameRecords
        if (records == null) {
            Text("Connect Playit.gg or NetBird first. Phoenix then shows exactly what to enter.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val context = LocalContext.current
        val opened = ctx.online.state.googleOpened
        var agreed by rememberSaveable { mutableStateOf(opened) }
        StepRow(1, "Sign in with Google",
            if (opened) "Opened in your browser. Finish signing in there; you land on the DNS page." else "Google's sign-in page opens in your browser (Google blocks sign-in inside apps).",
            if (opened) Tone.OK else Tone.IDLE)
        if (!opened) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, { agreed = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.Brand, checkmarkColor = Palette.OnBrand, uncheckedColor = Palette.Muted))
            Column(Modifier.weight(1f)) {
                Text("I agree to Minecraft.How's terms", style = MaterialTheme.typography.bodyMedium)
                Row {
                    Text("Terms of Service", color = Palette.Info, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { MinecraftHowSignInPolicy.publicPolicyUrl("/terms-of-service")?.let(ctx.online::open) }.padding(vertical = 4.dp))
                    Text("  ·  ", color = Palette.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Privacy Policy", color = Palette.Info, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { MinecraftHowSignInPolicy.publicPolicyUrl("/privacy-policy")?.let(ctx.online::open) }.padding(vertical = 4.dp))
                }
            }
        }
        GoogleButton(if (opened) "Open Google sign-in again" else "Continue with Google", enabled = agreed) {
            MinecraftHowBrowser.openGoogleSignIn(context); ctx.online.state.googleOpened = true
        }
        StepRow(2, "Pick your name, then add these two records", "On the DNS page tap Add Domain, choose a parent domain such as minecraft.co.com, type a subdomain and tap Add Subdomain. Open it and add the records below. Tap a value to copy it.", Tone.IDLE)
        // An IP address needs an A record, and the SRV then targets the name itself; a hostname uses a CNAME.
        val byAddress = records.apex.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        DnsRecordRow(if (byAddress) "A" else "CNAME", "Name: leave empty", records.apex)
        DnsRecordRow("SRV", "Name: _minecraft._tcp · Priority 0 · Port ${records.javaPort}",
            records.srvTarget ?: if (byAddress) "your full name, e.g. my-server.${MinecraftHowDns.supportedSuffixes.first()}" else records.apex)
        Text("Bedrock players then use your name with port ${records.bedrockPort}.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        StepRow(3, "Enter the full name", "Phoenix checks the records, then uses this name.", Tone.IDLE)
        OutlinedTextField(
            name, { name = it.trim().lowercase().take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Full server address") }, placeholder = { Text("my-server.${MinecraftHowDns.supportedSuffixes.first()}") },
            isError = name.isNotEmpty() && clean == null, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp),
            supportingText = { if (name.isNotEmpty() && clean == null) Text("Use a name ending in .${MinecraftHowDns.supportedSuffixes.first()} or another Minecraft.How domain.", color = Palette.Bad) },
        )
        PrimaryButton("Check and use this name", { clean?.let(ctx.vm::useBrowserHostname) }, Modifier.fillMaxWidth(), enabled = clean != null && !d.busy && !d.networkBusy)
        if (d.minecraftHostname != null && d.minecraftHostname == clean) Banner(
            if (dnsReady(d)) "${d.minecraftHostname} points to your server." else d.dnsState,
            if (dnsReady(d)) Tone.OK else Tone.WARN,
        )
    }
}

/** Google's sign-in in the Google red Minecraft.How uses, marked as opening the browser. */
@Composable private fun GoogleButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val red = Color(0xFFEA4335)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(red.copy(alpha = 0.30f).compositeOver(Palette.Card)).border(1.dp, red.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.45f).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Text("G", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Opens your browser", tint = Palette.Muted, modifier = Modifier.size(16.dp))
    }
}

/** One DNS record to copy into minecraft.how; the value is what has to match exactly. */
@Composable private fun DnsRecordRow(type: String, where: String, value: String) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.CardHigh).clickable { copyText(context, "$type record", value) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(type, fontWeight = FontWeight.Bold, color = Palette.Brand, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(where, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = Palette.Good, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $type value", tint = Palette.Muted, modifier = Modifier.size(18.dp))
    }
}

// ---------------------------------------------------------------- Server settings

private val regionCompressions = listOf("deflate" to "Standard (default)", "lz4" to "LZ4 (larger files)")
private val gamemodes = listOf("survival" to "Survival (default)", "creative" to "Creative", "adventure" to "Adventure", "spectator" to "Spectator")
private fun distanceOptions(recommended: Int) = PhoneTuning.RANGE.map { "$it" to if (it == recommended) "$it chunks (recommended)" else "$it chunks" }
private const val CUSTOM_SPAWN = "custom"
/** Off is Phoenix's default; "custom" opens a number field (1 to 256 blocks). */
private fun spawnProtectionOptions(current: Int) = listOf("0" to "Off (default)", "4" to "4 blocks", "8" to "8 blocks", "16" to "16 blocks",
    "32" to "32 blocks", CUSTOM_SPAWN to if (current in setOf(0, 4, 8, 16, 32)) "Custom…" else "Custom: $current blocks")
private val difficulties = listOf("peaceful" to "Peaceful", "easy" to "Easy", "normal" to "Normal", "hard" to "Hard")
private const val CUSTOM_PREP = "custom"
/** The radius presets (256 is the default); "custom" opens a number field (0 to 4096 blocks). */
private fun spawnPrepOptions(current: Int) = SpawnPrepConfig.RADIUS_CHOICES.map { r ->
    "$r" to if (r == SpawnPrepConfig.DEFAULT_RADIUS) "$r blocks (default)" else "$r blocks"
} + (CUSTOM_PREP to if (current in SpawnPrepConfig.RADIUS_CHOICES) "Custom…" else "Custom: $current blocks")

@Composable private fun ServerSettingsPanel(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    var renaming by remember { mutableStateOf(false) }
    var playersDialog by remember { mutableStateOf(false) }
    var spawnDialog by remember { mutableStateOf(false) }
    var prepDialog by remember { mutableStateOf(false) }
    val stopped = isStopped(d) && !d.busy
    val vanilla = d.providerDisplayName == "Vanilla"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Changes apply when the server restarts.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        if (d.pendingRestart) Banner("Restart the server to apply your changes.", Tone.WARN, Modifier.padding(vertical = 6.dp), actionLabel = "Restart",
            onAction = { ctx.confirm(LifecycleOperation.RESTART) })
        SettingRow("Server name", d.serverName, onClick = { renaming = true }, enabled = !d.busy)
        HorizontalDivider(color = Palette.Line)
        SettingRow("Max players", "${d.maxPlayers}", onClick = { playersDialog = true }, enabled = !d.busy)
        HorizontalDivider(color = Palette.Line)
        OptionRow("Default game mode", d.gamemode, gamemodes, enabled = !d.busy) { vm.updateSetting("gamemode", it) }
        HorizontalDivider(color = Palette.Line)
        OptionRow("Difficulty", d.difficulty, difficulties, enabled = !d.busy) { vm.updateSetting("difficulty", it) }
        HorizontalDivider(color = Palette.Line)
        OptionRow("View distance", "${d.viewDistance}", distanceOptions(PhoneTuning.VIEW), enabled = !d.busy) { vm.updateSetting("view-distance", it) }
        HorizontalDivider(color = Palette.Line)
        OptionRow("Simulation distance", "${d.simulationDistance}", distanceOptions(PhoneTuning.SIMULATION), enabled = !d.busy) { vm.updateSetting("simulation-distance", it) }
        Text("Lower distances mean less lag on a phone. Higher ones load more of the world around each player and need a faster phone.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        HorizontalDivider(color = Palette.Line)
        OptionRow("Spawn protection", if (d.spawnProtection in setOf(0, 4, 8, 16, 32)) "${d.spawnProtection}" else CUSTOM_SPAWN,
            spawnProtectionOptions(d.spawnProtection), enabled = !d.busy) { if (it == CUSTOM_SPAWN) spawnDialog = true else vm.updateSetting("spawn-protection", it) }
        Text(if (d.spawnProtection == 0) "Off: everyone can build and break blocks at spawn." else
            "Only operators can build or break blocks within ${d.spawnProtection} blocks of world spawn (Minecraft's own rule). " +
            "Make someone an operator from Players, or turn this off.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        HorizontalDivider(color = Palette.Line)
        SettingRow("Prepare the area around spawn", trailing = {
            Switch(d.spawnPrepEnabled && !vanilla, vm::setSpawnPrepEnabled, enabled = !d.busy && !vanilla, colors = phoenixSwitchColors())
        })
        if (d.spawnPrepEnabled && !vanilla) {
            OptionRow("Area radius", if (d.spawnPrepRadius in SpawnPrepConfig.RADIUS_CHOICES) "${d.spawnPrepRadius}" else CUSTOM_PREP,
                spawnPrepOptions(d.spawnPrepRadius), enabled = !d.busy) { if (it == CUSTOM_PREP) prepDialog = true else vm.setSpawnPrepRadius(it.toInt()) }
        }
        Text(when {
            vanilla -> "Needs Fabric: choose Fabric + Bedrock under Server software."
            !d.spawnPrepEnabled -> "Off: the server starts without it and the world is built as people explore."
            else -> "Before anyone can join, Phoenix builds the land within ${d.spawnPrepRadius} blocks of spawn, so exploring near spawn lags less. " +
                "Chunks that already exist are never changed. The first start takes longer: on a test phone about 3 minutes for 256 blocks and about 25 for 1280. " +
                "You can cancel from Home while it runs, and an interrupted run continues at the next start."
        }, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        if (d.spawnPrepEnabled && !vanilla && isStopped(d)) d.spawnPrepLast?.let { last ->
            Text(if (last.willResume) "Spawn preparation will resume on the next start. " + last.describe() else last.describe(),
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        HorizontalDivider(color = Palette.Line)
        OptionRow("World file compression", d.regionCompression, regionCompressions, enabled = !d.busy) { vm.updateSetting("region-file-compression", it) }
        Text("LZ4 files are about 30% bigger and weren't faster on a test phone. It applies to chunks saved after the next start; saved chunks stay readable.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        HorizontalDivider(color = Palette.Line)
        SettingRow("PvP", trailing = {
            Switch(d.pvp, { vm.updateSetting("pvp", it.toString()) }, enabled = !d.busy, colors = phoenixSwitchColors())
        })
        HorizontalDivider(color = Palette.Line)
        Text("Server software", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            ChoicePill("Fabric + Bedrock", !vanilla, { vm.selectProvider(false) }, enabled = stopped)
            ChoicePill("Vanilla", vanilla, { vm.selectProvider(true) }, enabled = stopped)
        }
        Text(if (stopped) "Fabric runs mods and lets Bedrock players join." else "Stop the server to change software.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(top = 8.dp))
        SettingRow("Worlds and backups", onClick = { vm.navigate(Screen.WORLD) })
        HorizontalDivider(color = Palette.Line)
        OptionRow("Automatic backups", "${d.autoBackupsKeep}", AutoBackups.choices, enabled = !d.busy) { vm.setAutoBackups(it.toInt()) }
        Text("Before each start, when the world changed since its newest backup. Restore them from Worlds.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    }
    if (renaming) TextDialog("Server name", "Shown in the Minecraft server list." + if (d.phase == State.ONLINE) " The server restarts to apply it." else "",
        d.serverName, onDismiss = { renaming = false }, valid = { it.isNotBlank() && it.length <= 64 }) { vm.renameServer(it.trim()) }
    if (playersDialog) TextDialog("Max players", "1 to 100 players.", "${d.maxPlayers}", number = true,
        onDismiss = { playersDialog = false }, valid = { it.toIntOrNull() in 1..100 }) { vm.updateSetting("max-players", it) }
    if (prepDialog) TextDialog("Spawn area radius", "Blocks around spawn to build before the server finishes starting: 0 to ${SpawnPrepConfig.MAX_RADIUS}. Larger areas make the first start longer.",
        "${d.spawnPrepRadius}", number = true, onDismiss = { prepDialog = false },
        valid = { SpawnPrepConfig.parseRadius(it) != null }, problem = SpawnPrepConfig::radiusProblem) { SpawnPrepConfig.parseRadius(it)?.let(vm::setSpawnPrepRadius) }
    if (spawnDialog) TextDialog("Spawn protection", "Blocks around world spawn that only operators can change: 1 to 256. Use Off in the list to turn it off.",
        "${d.spawnProtection.takeIf { it > 0 } ?: 16}", number = true, onDismiss = { spawnDialog = false },
        valid = { it.toIntOrNull() in 1..256 }) { vm.updateSetting("spawn-protection", it) }
}

private const val CUSTOM = -1

private fun memorySummary(d: Dashboard): String {
    val running = d.heapMaxBytes?.takeIf { d.phase == State.ONLINE }?.let { " · ${it / 1_048_576} MB now" } ?: ""
    return ServerMemory.label(d.memorySettingMb) + running
}

/** Dynamic is the safe default; a fixed size is allowed with a plain warning about what can go wrong. */
@Composable private fun MemoryDialog(d: Dashboard, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val presets = ServerMemory.choices(d.phoneMemTotalBytes)
    val maxCustom = ServerMemory.maxCustomMb(d.phoneMemTotalBytes)
    val savedIsCustom = d.memorySettingMb > 0 && d.memorySettingMb !in presets
    // CUSTOM selects the typed amount; any other value is Dynamic (0) or a preset.
    var choice by rememberSaveable { mutableIntStateOf(if (savedIsCustom) CUSTOM else d.memorySettingMb) }
    var customText by rememberSaveable { mutableStateOf(if (savedIsCustom) "${d.memorySettingMb}" else "") }
    val customMb = customText.toIntOrNull()
    val customValid = customMb != null && customMb in ServerMemory.MIN_CUSTOM..maxCustom
    val selected = if (choice == CUSTOM) customMb ?: 0 else choice
    val free = d.phoneMemAvailableBytes
    val dynamicNow = free?.let { ServerMemory.dynamicMb(it) }
    val options = listOf(0) + presets + CUSTOM
    val tooMuch = selected > 0 && free != null && selected.toLong() * 1_048_576 > free
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        icon = { Icon(Icons.Rounded.Memory, contentDescription = null, tint = Palette.Brand) },
        title = { Text("Server memory", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Banner("Dynamic is recommended. Phoenix gives Minecraft about 45% of the phone's free memory each time it starts, " +
                    "so Android is less likely to close it. You can choose a fixed amount instead.", Tone.OK)
                options.forEach { mb ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).selectable(choice == mb, onClick = { choice = mb }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == mb, onClick = { choice = mb }, colors = RadioButtonDefaults.colors(selectedColor = Palette.Brand, unselectedColor = Palette.Muted))
                        Column {
                            Text(when (mb) { 0 -> "Dynamic (recommended)"; CUSTOM -> "Custom"; else -> ServerMemory.label(mb) },
                                fontWeight = if (mb == 0) FontWeight.SemiBold else FontWeight.Normal)
                            if (mb == 0) Text(dynamicNow?.let { "About $it MB with what's free right now" } ?: "Chosen when the server starts",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                            if (mb == CUSTOM) Text("For phones with lots of RAM: ${ServerMemory.MIN_CUSTOM} MB to ${ServerMemory.label(maxCustom)}",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (choice == CUSTOM) {
                    NumberField("Memory in MB", customText, { customText = it.trimStart('-').take(6) }, Modifier.fillMaxWidth())
                    Text(when {
                        customMb == null -> "Type an amount, e.g. 4096 for 4 GB."
                        !customValid -> "Choose ${ServerMemory.MIN_CUSTOM} to $maxCustom MB on this phone."
                        else -> "= ${ServerMemory.label(customMb)}"
                    }, color = if (customMb != null && !customValid) Palette.Bad else Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                val warning = when {
                    selected == 0 -> null
                    tooMuch -> "More than this phone has free right now (${free!! / 1_048_576} MB). Android may close the server or the phone may slow down. Close other apps or pick less."
                    selected < 1024 -> "Fine for small worlds with a few players. Big worlds, mods or Chunky may lag."
                    else -> "This amount is reserved for Minecraft while it runs. If Android closes the server, switch back to Dynamic."
                }
                warning?.let { Banner(it, if (tooMuch) Tone.ERROR else Tone.WARN) }
                Text(if (d.phase == State.ONLINE) "Applies when the server restarts." else "Applies the next time the server starts.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            val canSave = choice != CUSTOM || customValid
            TextButton(onClick = { onDismiss(); if (selected != d.memorySettingMb) onSave(selected) }, enabled = canSave) {
                Text("Save", color = if (canSave) Palette.Brand else Palette.Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

@Composable private fun OptionRow(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(label, options.firstOrNull { it.first == value }?.second ?: value, onClick = { open = true }, enabled = enabled)
        DropdownMenu(open, { open = false }, containerColor = Palette.CardHigh, modifier = Modifier.align(Alignment.CenterEnd)) {
            options.forEach { (key, name) ->
                DropdownMenuItem(text = { Text(name, color = if (key == value) Palette.Brand else Palette.Text) },
                    trailingIcon = { if (key == value) Icon(Icons.Rounded.Check, contentDescription = null, tint = Palette.Brand) },
                    onClick = { open = false; if (key != value) onSelect(key) })
            }
        }
    }
}

@Composable private fun TextDialog(
    title: String, body: String, initial: String, onDismiss: () -> Unit, number: Boolean = false,
    valid: (String) -> Boolean, problem: (String) -> String? = { null }, onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = Palette.Muted)
                if (number) NumberField(title, text, { text = it })
                else OutlinedTextField(text, { text = it.take(64) }, singleLine = true, colors = phoenixFieldColors(), shape = RoundedCornerShape(12.dp))
                // why Save is unavailable, instead of a silently greyed button
                problem(text)?.let { Text(it, color = Palette.Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text); onDismiss() }, enabled = valid(text) && text != initial) {
                Text("Save", color = if (valid(text) && text != initial) Palette.Brand else Palette.Faint, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

// ---------------------------------------------------------------- Performance and About

@Composable private fun DiagnosticsPanel(ctx: ScreenContext) {
    val d = ctx.d
    val vm = ctx.vm
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { it?.let(vm::export) }
    fun mb(bytes: Long?) = bytes?.let { "${it / 1_048_576} MB" } ?: "—"
    val running = d.phase in listOf(State.STARTING, State.ONLINE, State.STOPPING, State.RESTARTING)
    var memoryDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingRow("Server memory (RAM)", memorySummary(d), onClick = { memoryDialog = true }, enabled = !d.busy)
        HorizontalDivider(color = Palette.Line)
        InfoRow("TPS (measured)", d.tps?.let { "%.1f".format(it) } ?: "—")
        InfoRow("Average tick", d.mspt?.let { "%.1f ms".format(it) } ?: "—")
        InfoRow("Java heap used / max", "${mb(d.heapUsedBytes)} / ${mb(d.heapMaxBytes)}")
        InfoRow("Server memory (RSS)", mb(d.serverRssBytes))
        InfoRow("Phone free memory", "${mb(d.phoneMemAvailableBytes)} of ${mb(d.phoneMemTotalBytes)}")
        InfoRow("Battery", "${d.batteryPercent?.let { "$it%" } ?: "—"}${if (d.charging) " · charging" else ""} · ${d.batteryTempC?.let { "%.1f°C".format(it) } ?: "—"}")
        InfoRow("Thermal level", "${d.thermalStatus}", valueColor = if (d.thermalStatus >= 2) Palette.Warn else Palette.Text)
        SettingRow("Faster starts", trailing = {
            Switch(d.fasterStarts, vm::setFasterStarts, colors = phoenixSwitchColors())
        })
        Text("Java remembers this server's start-up, so starts take about 10 s less. After an update it learns once again: that start and its stop take a little longer.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        SettingRow("Ease off when hot", trailing = {
            Switch(d.thermalEaseOff, vm::setThermalEaseOff, colors = phoenixSwitchColors())
        })
        Text(d.easedSimulation?.let { "Hot right now: simulation distance is $it until the phone cools down." }
            ?: "Before Android slows a hot phone, Phoenix lowers the simulation distance by one, and puts it back once the phone has cooled.",
            color = if (d.easedSimulation != null) Palette.Warn else Palette.Muted, style = MaterialTheme.typography.bodySmall)
        // Shown only when Phoenium is installed, on, and has its tile engine. Standard is Phoenium's own default mode.
        if (d.phoeniumEngineAvailable) {
            OptionRow("Phoenium mode", if (d.phoeniumAcceleration) "acceleration" else "standard",
                listOf("standard" to "Standard (default)", "acceleration" to "Acceleration"), enabled = !d.busy) { vm.setPhoeniumAcceleration(it == "acceleration") }
            Text(if (d.phoeniumAcceleration) "Runs Phoenium's tile engine, which spreads the world's work over several threads. Changes apply at the next Start."
                else "Runs Phoenium's standard mode. Changes apply at the next Start.",
                color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            if (d.phoeniumAcceleration) {
                var advanced by remember { mutableStateOf(false) }
                SettingRow("Advanced", onClick = { advanced = !advanced })
                if (advanced) {
                    OptionRow("Engine workers", "${d.phoeniumWorkers}", (PhoeniumTuning.MIN_WORKERS..d.phoeniumMaxWorkers).map { "$it" to "$it" },
                        enabled = !d.busy) { vm.setPhoeniumWorkers(it.toInt()) }
                    Text("2 is the default. More workers only help when the phone has spare cores.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Measure TPS", vm::measureTicks, Modifier.weight(1f), icon = Icons.Rounded.Timer, enabled = d.phase == State.ONLINE)
            SecondaryButton("Java checks", vm::probe, Modifier.weight(1f), icon = Icons.Rounded.Coffee, enabled = !d.busy && !running)
        }
        Text(d.runtimeResult, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        SecondaryButton("Export measurements", { exporter.launch("phoenix-metrics-${System.currentTimeMillis()}.jsonl") }, Modifier.fillMaxWidth(),
            icon = Icons.Rounded.IosShare, enabled = !d.busy)
    }
    if (memoryDialog) MemoryDialog(d, onDismiss = { memoryDialog = false }) { vm.setServerMemory(it) }
}

@Composable private fun AboutPanel(ctx: ScreenContext) {
    val d = ctx.d
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoRow("Phoenix", BuildConfig.VERSION_NAME)
        InfoRow("Minecraft", "26.2 · ${d.providerDisplayName}")
        InfoRow("Online play", d.net.selected?.title ?: "Not chosen yet")
        InfoRow("Playit agent", "playitd 1.0.12 (official, BSD-2-Clause)")
        InfoRow("NetBird client", "0.80 (official, BSD-3-Clause)")
        Text("Phoenix stores account keys encrypted on this phone and never exposes the console, RCON or its own controls through a tunnel. " +
            "Free relays limit abusive traffic but don't promise complete DDoS protection.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Need help? Performance › Export measurements saves a file with the server's measurements (no account keys) to share " +
            "with whoever supports your Phoenix install.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Phoenix keeps hosting with a notification. Android may stop it when the battery is low or the phone overheats; open Phoenix to start again.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        Text("Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.",
            color = Palette.Faint, style = MaterialTheme.typography.bodySmall)
        if (d.busy) SecondaryButton("Cancel download or import", ctx.vm::cancelInstall, Modifier.fillMaxWidth())
    }
}
