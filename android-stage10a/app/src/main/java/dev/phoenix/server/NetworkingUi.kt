package dev.phoenix.server

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.phoenix.core.ServerController.State

// ---------------------------------------------------------------- Provider descriptions

/** A short label under a provider's name; green for plain facts, amber for a trade-off. */
data class Badge(val text: String, val color: Color = Palette.Good)

private val playitBadges = listOf(Badge("Free"), Badge("All in one"), Badge("Recommended"))
private val netBirdBadges = listOf(Badge("Free"), Badge("Alternative", Palette.Info), Badge("Higher ping (EU)", Palette.Warn))
private val nameBadges = listOf(Badge("Free"), Badge("Custom address", Palette.Info), Badge("Easy"))
private val regionalBadges = listOf(Badge("Always Free tier"), Badge("Advanced", Palette.Warn), Badge("Lower ping near you", Palette.Info))

@OptIn(ExperimentalLayoutApi::class)
@Composable fun Badges(badges: List<Badge>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.forEach { badge ->
            Text(badge.text, color = badge.color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(badge.color.copy(alpha = 0.13f))
                    .border(1.dp, badge.color.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

/**
 * One networking option: icon, name, live status, badges and a one-line description, with its on/off switch. Tapping the
 * card opens its controls below; the switch never acts on its own, the caller confirms what it replaces first.
 */
@Composable fun ProviderCard(
    title: String, icon: ImageVector, tint: Color, status: String, tone: Tone, badges: List<Badge>, description: String,
    expanded: Boolean, onExpand: () -> Unit, checked: Boolean?, onCheckedChange: (Boolean) -> Unit, switchEnabled: Boolean = true,
    highlight: Boolean = false, subtitle: String? = null, compact: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.CardHigh)
            .border(1.dp, if (highlight) Palette.Brand.copy(alpha = 0.55f) else if (tone == Tone.OK && checked == true) Palette.Good.copy(alpha = 0.35f) else Palette.Line, shape),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 14.dp, top = 14.dp, end = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            IconTile(icon, tint, size = 44.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Name, then the live status on its own line, so a long status never squeezes the name.
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = Palette.Muted, style = MaterialTheme.typography.labelMedium) }
                    StatusText(status, tone, Modifier.padding(top = 2.dp))
                }
                Badges(badges)
                // Collapsed, a live summary (small status icons) can stand in for the description.
                if (compact != null && !expanded) compact() else Text(description, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (checked != null) Switch(checked, onCheckedChange, enabled = switchEnabled, colors = phoenixSwitchColors(),
                    modifier = Modifier.padding(start = 6.dp))
                Icon(Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Hide $title controls" else "Show $title controls", tint = Palette.Muted,
                    modifier = Modifier.padding(top = 4.dp).rotate(if (expanded) 180f else 0f))
            }
        }
        if (expanded) {
            HorizontalDivider(color = Palette.Line)
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/**
 * An experimental option: one short dashed-amber row with a lab-flask icon and an EXPERIMENTAL tag until opened, so it
 * reads as optional and unfinished next to the regular routes. Off by default.
 */
@Composable fun ExperimentalCard(
    title: String, status: String, tone: Tone, expanded: Boolean, onExpand: () -> Unit, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, switchEnabled: Boolean, content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Card)
            .drawBehind {
                drawRoundRect(Palette.Warn.copy(alpha = 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            },
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Science, contentDescription = null, tint = Palette.Warn, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Tag("EXPERIMENTAL", Palette.Warn)
                    if (checked) StatusText(status, tone, Modifier.padding(start = 8.dp))
                }
            }
            Switch(checked, onCheckedChange, enabled = switchEnabled, colors = phoenixSwitchColors(), modifier = Modifier.scale(0.8f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Hide $title" else "Show $title", tint = Palette.Muted,
                modifier = Modifier.padding(start = 2.dp).rotate(if (expanded) 180f else 0f))
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Which provider cards are open; remembered across rotation, one key per card. */
class ExpandedCards(initial: Set<String>) {
    var open by mutableStateOf(initial)
    fun isOpen(key: String) = key in open
    fun toggle(key: String) { open = if (key in open) open - key else open + key }
    fun show(key: String) { open = open + key }
}

@Composable fun rememberExpandedCards(initial: Set<String>): ExpandedCards {
    var saved by rememberSaveable { mutableStateOf(initial.joinToString(",")) }
    val cards = remember { ExpandedCards(saved.split(',').filter { it.isNotBlank() }.toSet()) }
    LaunchedEffect(cards.open) { saved = cards.open.joinToString(",") }
    return cards
}

// ---------------------------------------------------------------- Settings › Networking

/** A route change the owner asked for and must confirm, because it replaces or turns off the route players use. */
private data class PendingSwitch(val to: PublicRoute, val run: () -> Unit)

@Composable fun NetworkingSection(ctx: ScreenContext) {
    val d = ctx.d
    val view = d.net
    val cards = rememberExpandedCards(setOfNotNull(view.selected?.key ?: PublicRoute.PLAYIT.key))
    var pending by remember { mutableStateOf<PendingSwitch?>(null) }
    var warnExperimental by remember { mutableStateOf(false) }
    fun request(to: PublicRoute, run: () -> Unit) {
        val current = view.selected?.takeIf { it.isPublic && it != to }
        if (current != null || to == PublicRoute.LOCAL) pending = PendingSwitch(to, run) else run()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        view.switching?.let { WorkingLine(it.step) }
        (view.switchError ?: d.routeError)?.let { Banner(it, Tone.ERROR) }

        val playitState = view.provider(PublicRoute.PLAYIT)
        val playitOn = view.selected == PublicRoute.PLAYIT || d.requestedRoute == PublicRoute.PLAYIT || view.switching?.to == PublicRoute.PLAYIT
        ProviderCard("Playit.gg", Icons.Rounded.Router, Palette.Info, playitState.status, NetworkStatus.tone(playitState, view), playitBadges,
            "Routing option 1. Java, Bedrock and voice from anywhere, no port forwarding. Free account; you approve Phoenix in your browser.",
            cards.isOpen("playit"), { cards.toggle("playit") }, playitOn, { on ->
                cards.show("playit")
                if (on) request(PublicRoute.PLAYIT) { ctx.online.connectPlayit() }
                else if (view.selected == PublicRoute.PLAYIT) request(PublicRoute.LOCAL) { ctx.vm.turnOffPublicAccess() }
                else ctx.vm.cancelPlayitSignIn()
            }, switchEnabled = view.switching == null, highlight = view.selected == null,
            compact = if (view.selected == PublicRoute.PLAYIT && playitState.configured) ({ StatusChips(NetworkStatus.checklist(d), d.voiceChatPort > 0) }) else null) { PlayitDetails(ctx) }

        val netBirdState = view.provider(PublicRoute.NETBIRD_EU)
        val netBirdOn = view.selected == PublicRoute.NETBIRD_EU || d.requestedRoute == PublicRoute.NETBIRD_EU || view.switching?.to == PublicRoute.NETBIRD_EU
        ProviderCard("NetBird", Icons.Rounded.VpnLock, Palette.Info, netBirdState.status, NetworkStatus.tone(netBirdState, view), netBirdBadges,
            "Routing option 2. Uses NetBird's hosted proxy in Europe, so players far from Europe get higher ping.",
            cards.isOpen("netbird"), { cards.toggle("netbird") }, netBirdOn, { on ->
                cards.show("netbird")
                if (on) request(PublicRoute.NETBIRD_EU) { ctx.online.turnOnNetBird(d) }
                else if (view.selected == PublicRoute.NETBIRD_EU) request(PublicRoute.LOCAL) { ctx.online.noteNetBirdOff(); ctx.vm.turnOffPublicAccess() }
                else if (d.netBirdLive) ctx.online.disconnect() else ctx.online.noteNetBirdOff()
            }, switchEnabled = view.switching == null && !d.busy) { NetBirdDetails(ctx) }

        val name = view.name
        val addOn = d.nameListing?.takeIf { !it.active }?.let { listOf(Badge(if (it.needsSignIn) "Add-on: sign-in needed" else "Add-on available", Palette.Info)) }.orEmpty()
        ProviderCard("Minecraft.How", Icons.Rounded.Language, Palette.Brand, NetworkStatus.nameStatus(name), NetworkStatus.nameTone(name), nameBadges + addOn,
            "Optional friendly address for whichever route you use. It's a DNS name, not another relay, so it doesn't change ping.",
            cards.isOpen("name"), { cards.toggle("name") }, if (name.hostname != null) name.enabled else null,
            { ctx.vm.setCustomNameEnabled(it) }, subtitle = "Optional") { MinecraftHowDetails(ctx) }

        val regional = view.provider(PublicRoute.NETBIRD_REGIONAL)
        ExperimentalCard("Regional Optimized", if (d.regionalEnabled) regional.status else "Off",
            if (d.regionalEnabled) NetworkStatus.tone(regional, view) else Tone.IDLE,
            cards.isOpen("regional"), { cards.toggle("regional") }, d.regionalEnabled, { on ->
                if (on) cards.show("regional")
                if (on && !d.regionalWarningAcknowledged) warnExperimental = true else ctx.vm.setRegionalEnabled(on)
            }, switchEnabled = view.selected != PublicRoute.NETBIRD_REGIONAL && !d.busy) {
            Badges(regionalBadges)
            Text("Routing option 3: another relay, like Playit.gg or NetBird, but your own, on an Oracle Cloud Always Free VM near " +
                "your players. Needs a separate Oracle account and extra setup.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            RegionalDetails(ctx, onUse = { request(PublicRoute.NETBIRD_REGIONAL) { ctx.vm.switchPlayersToRegional(true) } },
                onLeave = { request(PublicRoute.NETBIRD_EU) { ctx.vm.switchPlayersToRegional(false) } })
        }
        if (view.selected != null && view.selected.isPublic) TextButton(onClick = { request(PublicRoute.LOCAL) { ctx.vm.turnOffPublicAccess() } },
            enabled = view.switching == null) { Text("Turn off public access", color = Palette.Muted) }
    }
    pending?.let { change -> RouteSwitchDialog(d, change.to, onConfirm = { pending = null; change.run() }, onDismiss = { pending = null }) }
    if (warnExperimental) ExperimentalWarningDialog(onConfirm = { warnExperimental = false; ctx.vm.setRegionalEnabled(true) }, onDismiss = { warnExperimental = false })
}

/** Says what a route change replaces, that accounts are kept, and whether players have to reconnect. */
@Composable private fun RouteSwitchDialog(d: Dashboard, to: PublicRoute, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val from = d.net.selected?.takeIf { it.isPublic }
    val name = d.net.name.hostname?.takeIf { d.net.name.enabled }
    val title = if (to == PublicRoute.LOCAL) "Turn off public access?" else "Switch to ${to.title}?"
    val body = buildString {
        if (to == PublicRoute.LOCAL) {
            append("${from?.title ?: "The public route"} stops, so only friends on the same Wi-Fi can join. Its account and addresses are kept for next time.")
        } else {
            append("${from?.title} is the route friends use now. Phoenix prepares ${to.title} first and keeps ${from?.title} working until it is ready; then ${from?.title} stops. ")
            append("Your ${from?.title} account and addresses are kept. ")
            if (d.phase == State.ONLINE) append("Players online now are disconnected and need to rejoin. ")
            append(if (name != null) "$name moves to ${to.title} within a few minutes; meanwhile share the new ${to.title} address." else "Share the new ${to.title} address with friends.")
            if (d.voiceChatPort > 0) append(" Restart the server afterwards so voice chat uses the new route.")
        }
    }
    ConfirmDialog(title, body, if (to == PublicRoute.LOCAL) "Turn off" else "Switch", onConfirm = onConfirm, onDismiss = onDismiss, destructive = to == PublicRoute.LOCAL)
}

/** Shown once before the Oracle setup appears. Turning it on creates nothing and changes no route. */
@Composable fun ExperimentalWarningDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Palette.CardHigh,
        icon = { Icon(Icons.Rounded.Science, contentDescription = null, tint = Palette.Warn) },
        title = { Text("Regional Optimized is experimental", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "It needs your own Oracle Cloud account, separate from Phoenix.",
                    "Oracle may ask for a payment card and identity checks when you sign up.",
                    "Only eligible resources are Always Free; Phoenix's template uses only those, and Oracle's terms apply.",
                    "Setup takes extra steps, and the VM may need occasional care in Oracle's console.",
                    "Low ping depends on the region you pick and on players' networks.",
                    "Moving players to it later disconnects the players online at that moment.",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                Text("Turning this on only shows the setup. Nothing is created and your current route keeps working.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("I understand, show setup", color = Palette.Brand, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Muted) } },
    )
}

/** Playit account, approval, tunnels and diagnostics. */
@Composable private fun PlayitDetails(ctx: ScreenContext) {
    val d = ctx.d
    val view = d.net
    val playit = view.provider(PublicRoute.PLAYIT)
    val claimOpen = d.playitClaimUrl != null
    when {
        claimOpen -> {
            Banner(playit.detail ?: "Waiting for your approval on playit.gg", Tone.WORKING)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Open playit.gg again", { ctx.online.open(d.playitClaimUrl!!) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.OpenInNew)
                SecondaryButton("Cancel", { ctx.vm.cancelPlayitSignIn() }, Modifier.weight(0.6f))
            }
        }
        !playit.configured -> {
            Text("Sign in with your own free Playit account in your browser and approve “Phoenix”. Phoenix never sees your password; " +
                "Playit gives this phone its own agent key, stored encrypted here.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            playit.detail?.takeIf { playit.health == LinkHealth.ERROR }?.let { Banner(it, Tone.WARN) }
            // With another route in use this only links the account; the switch above moves players, after confirming.
            val otherRoute = view.selected?.takeIf { it.isPublic && it != PublicRoute.PLAYIT }
            PrimaryButton(if (otherRoute == null) "Connect Playit.gg" else "Connect account", { ctx.online.connectPlayit(andUse = otherRoute == null) },
                Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login, enabled = d.net.switching == null)
            otherRoute?.let { Text("${it.title} stays the route friends use until you turn Playit on.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        else -> {
            val playitTone = NetworkStatus.tone(playit, view)
            val selected = view.selected == PublicRoute.PLAYIT
            if (selected) StatusChips(NetworkStatus.checklist(d), d.voiceChatPort > 0)
            playit.detail?.takeIf { playit.health != LinkHealth.CONNECTED || d.playitProblems.isNotEmpty() }?.let {
                Text(it, color = playitTone.takeIf { t -> t != Tone.IDLE }?.color ?: Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (d.playitAccount == "guest") Text("This agent is on a Playit guest account. Claim it on playit.gg to keep it.", color = Palette.Warn,
                style = MaterialTheme.typography.bodySmall)
            if (playit.status == "Authentication required") PrimaryButton("Connect Playit again",
                { ctx.online.connectPlayit(andUse = view.selected == PublicRoute.PLAYIT || view.selected == null) }, Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Rounded.Login)
            if (playitOverLimit(view)) PlayitLimitActions(ctx)
            playit.endpoints?.let { endpoints ->
                SubDropdown("Addresses", Icons.Rounded.Link, null) { EndpointList(endpoints, d.voiceChatPort > 0) }
            }
            if (selected) {
                val items = NetworkStatus.checklist(d).filterIndexed { i, _ -> i != VOICE_ITEM || d.voiceChatPort > 0 }
                val ready = items.count { it.tone == Tone.OK }
                SubDropdown("Can friends join?", Icons.Rounded.Checklist, if (ready == items.size) "All ready" else "$ready/${items.size}",
                    if (items.any { it.tone == Tone.ERROR }) Tone.ERROR else if (ready == items.size) Tone.OK else Tone.IDLE) { ChecklistRows(d) }
            }
            val account = when {
                playit.health == LinkHealth.ERROR && playit.status == "Authentication required" -> "Sign in again"
                playitOverLimit(view) -> "Agent limit reached"
                else -> when (d.playitAccount) { "guest" -> "Guest account"; "email_not_verified" -> "Email not verified"; else -> "Connected" }
            }
            val accountTone = if (d.playitAccount == "guest" || d.playitAccount == "email_not_verified" || account == "Sign in again" ||
                account == "Agent limit reached") Tone.WARN else Tone.OK
            SubDropdown("Account", Icons.Rounded.ManageAccounts, account, accountTone) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBox("Account", account, accountTone, Modifier.weight(1f))
                    StatusBox("Agent", playit.status, playitTone, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("playit.gg", { ctx.online.open("https://playit.gg/account/agents") }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.OpenInNew)
                    SecondaryButton("Disconnect", { ctx.vm.forgetPlayit() }, Modifier.weight(1f), enabled = view.selected != PublicRoute.PLAYIT,
                        contentColor = Palette.Bad)
                }
                Text("Disconnect forgets the key on this phone. Your tunnels stay in your Playit account; delete the agent there to revoke it.",
                    color = Palette.Faint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Playit refused this phone's agent because the account's agent limit is full, or a switch is waiting for a free slot. */
fun playitOverLimit(view: NetworkView): Boolean = view.provider(PublicRoute.PLAYIT).status == "Agent limit reached" ||
    view.switching?.to == PublicRoute.PLAYIT && view.switching.step.startsWith("Over Playit's agent limit")

/** Over the agent limit: where to free a slot, and (when no switch is waiting already) trying again afterwards. */
@Composable fun PlayitLimitActions(ctx: ScreenContext) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("playit.gg agents", { ctx.online.open("https://playit.gg/account/agents") }, Modifier.weight(1f),
            icon = Icons.AutoMirrored.Rounded.OpenInNew)
        if (ctx.d.net.switching == null) PrimaryButton("Try again", { ctx.online.connectPlayit(andUse = true) }, Modifier.weight(1f),
            enabled = !ctx.d.busy)
    }
}

/** A labelled value in a dark box, such as "Account: Connected". */
@Composable fun StatusBox(label: String, value: String, tone: Tone, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        StatusText(value, tone, bold = true)
    }
}

/** The provider's assigned addresses, each copyable: Java (port only when needed), Bedrock host and port, voice. */
@Composable fun EndpointList(endpoints: RouteEndpoints, voiceInstalled: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(vertical = 4.dp)) {
        endpoints.java?.let { EndpointRow("Java", it.text, if (it.port == null) "No port needed" else null) { copyText(context, "Java address", it.text) } }
        endpoints.bedrockForList?.let { b ->
            EndpointRow("Bedrock", b.host, "Port ${b.port}" + (endpoints.bedrock?.host?.takeIf { it != b.host }?.let { " · also $it" } ?: "")) { copyText(context, "Bedrock address", b.host) }
        }
        if (voiceInstalled) endpoints.voice?.let { EndpointRow("Voice", it.text, "UDP, set in Simple Voice Chat automatically") { copyText(context, "Voice address", it.text) } }
    }
}

@Composable private fun EndpointRow(label: String, value: String, note: String?, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onCopy).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            FitText(value, Palette.Good, maxSize = 14.sp, minSize = 10.sp)
            note?.let { Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
        }
        IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $label address", tint = Palette.Muted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable fun ChecklistBox(d: Dashboard) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Can friends join?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        ChecklistRows(d)
    }
}

/** The checklist with each item's explanation, and how long the last start took. */
@Composable fun ChecklistRows(d: Dashboard) {
    NetworkStatus.checklist(d).filterIndexed { i, _ -> i != VOICE_ITEM || d.voiceChatPort > 0 }
        .forEachIndexed { index, item -> StepRow(index + 1, item.title, item.detail, item.tone) }
    val ready = d.net.readiness
    if (ready.serverReadyMs != null || ready.publicReadyMs != null) Text(
        listOfNotNull(ready.serverReadyMs?.let { "Server ready ${it / 1000} s after Start" }, ready.publicReadyMs?.let { "reachable by friends after ${it / 1000} s" })
            .joinToString(" · "), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
}

/** Index of "Voice ready" in [NetworkStatus.checklist]; hidden without Simple Voice Chat. */
private const val VOICE_ITEM = 4
/** Short names and icons for the checklist, in [NetworkStatus.checklist] order. */
private val checkChips = listOf(Icons.Rounded.Person to "Account", Icons.Rounded.Router to "Relay", Icons.Rounded.Coffee to "Java",
    Icons.Rounded.ViewInAr to "Bedrock", Icons.Rounded.Mic to "Voice", Icons.Rounded.Public to "Address")

/** The checklist at a glance: one small colored icon and word per item (green ready, blue checking, red broken). */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun StatusChips(items: List<ChecklistItem>, voice: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, item ->
            if (i == VOICE_ITEM && !voice) return@forEachIndexed
            val (icon, label) = checkChips.getOrElse(i) { Icons.Rounded.Check to item.title }
            val color = if (item.tone == Tone.IDLE) Palette.Muted else item.tone.color
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** A fold-out section inside a card: title and a one-line summary; the details only when opened. */
@Composable fun SubDropdown(title: String, icon: ImageVector, summary: String?, summaryTone: Tone = Tone.IDLE, initiallyOpen: Boolean = false,
                            content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Bg)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            summary?.let { Text(it, color = if (summaryTone == Tone.IDLE) Palette.Muted else summaryTone.color, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false)) }
            Icon(Icons.Rounded.ExpandMore, contentDescription = if (open) "Hide $title" else "Show $title", tint = Palette.Muted,
                modifier = Modifier.padding(start = 4.dp).rotate(if (open) 180f else 0f))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

// ---------------------------------------------------------------- Home

/**
 * The route and the optional custom name at a glance, as small chips in the same words as Settings. They replaced two
 * large cards under the controls, which pushed the server's numbers below the fold (2026-10-12). An unused name is only
 * "Optional": a name that isn't set up never shows a DNS state.
 */
@Composable fun ServiceChips(d: Dashboard, modifier: Modifier = Modifier) {
    val view = d.net
    val provider = NetworkStatus.homeProvider(view)
    val name = view.name
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // A linked route that isn't the one in use is ready to turn on, not broken.
        val status = if (provider.health == LinkHealth.OFF && provider.configured && !provider.selected) "Ready" else provider.status
        ServiceChip(routeIcon(provider.route), provider.route.title, status, NetworkStatus.tone(provider, view), Modifier.weight(1f))
        if (name.hostname == null) ServiceChip(Icons.Rounded.Language, "Minecraft.How", "Optional", Tone.IDLE, Modifier.weight(1f))
        else ServiceChip(Icons.Rounded.Language, "Minecraft.How", NetworkStatus.nameStatus(name), NetworkStatus.nameTone(name), Modifier.weight(1f))
    }
}

fun routeIcon(route: PublicRoute): ImageVector = when (route) {
    PublicRoute.PLAYIT -> Icons.Rounded.Router
    PublicRoute.NETBIRD_EU -> Icons.Rounded.VpnLock
    else -> Icons.Rounded.Cloud
}

@Composable private fun ServiceChip(icon: ImageVector, title: String, status: String, tone: Tone, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.CardHigh).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 5.dp, end = 5.dp))
        StatusDot(tone, 6.dp)
        Text(status, color = if (tone == Tone.IDLE) Palette.Muted else tone.color, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false))
    }
}

/** Opens Settings, where the route and the name are managed. */
@Composable fun ManageButton(ctx: ScreenContext) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.CardHigh).clickable { ctx.vm.navigate(Screen.SETTINGS) }
        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Manage", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
    }
}

/** Can friends join, Manage and Share on one line; below it the two services side by side, then the same-Wi-Fi address. */
@Composable private fun FriendsBox(ctx: ScreenContext, options: List<AddressOption>) {
    val d = ctx.d
    val context = LocalContext.current
    val (join, joinTone) = joinStatus(d)
    val color = if (joinTone == Tone.IDLE) Palette.Muted else joinTone.color
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(shape).background(Palette.Bg).border(1.dp, Palette.Line, shape)
        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (joinTone == Tone.OK) Icons.Rounded.Link else Icons.Rounded.Info, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(join, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (joinTone == Tone.OK) Text("Share this address with friends to join.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
            ManageButton(ctx)
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Addresses.shareText(d.serverName, options))
                context.startActivity(Intent.createChooser(send, "Share server address"))
            }) {
                Icon(Icons.Rounded.Share, contentDescription = "Share server address", tint = Palette.Brand, modifier = Modifier.size(20.dp))
            }
        }
        ServiceChips(d, Modifier.padding(end = 4.dp))
        d.localAddress?.let { LocalAddressChip(it, Modifier.padding(end = 4.dp)) }
    }
}

/**
 * The addresses friends type, for the selected route: the verified custom name first and the provider's own address as
 * the alternative, switched with a segmented control that keeps its choice. Both lead to the same server. Below them,
 * whether friends can join, Share, and the services behind the address.
 */
@Composable fun AddressesCard(ctx: ScreenContext, wide: Boolean, options: List<AddressOption>) {
    val d = ctx.d
    val context = LocalContext.current
    var choice by rememberSaveable { mutableStateOf(0) }
    val index = choice.coerceIn(0, options.lastIndex)
    val option = options[index]
    Surface(shape = RoundedCornerShape(18.dp), color = Palette.Card.copy(alpha = 0.96f), contentColor = Palette.Text, border = BorderStroke(1.dp, Palette.Line)) {
        Column {
            if (options.size > 1) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp).clip(CircleShape).background(Palette.Bg).padding(3.dp)) {
                    options.forEachIndexed { i, item ->
                        val selected = i == index
                        Text(if (item.custom) "Custom address" else item.label, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (selected) Palette.OnBrand else Palette.Muted, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).clip(CircleShape).background(if (selected) Palette.Brand else Color.Transparent)
                                .clickable { choice = i }.padding(vertical = 8.dp))
                    }
                }
            }
            val java = @Composable { m: Modifier ->
                AddressRow(R.drawable.ic_java, "Java TCP", option.java.host, option.java.port?.toString(), m) { copyText(context, "Java address", option.java.text) }
            }
            val bedrock = @Composable { m: Modifier ->
                // A bare IP looks like anyone's; say whose it is (Playit's server, never this phone's or the owner's network).
                val owner = d.net.active?.route?.title?.takeIf { !option.custom && option.bedrock?.isIpAddress == true }?.let { "$it server" }
                option.bedrock?.let { b -> AddressRow(R.drawable.ic_grass_block, "Bedrock UDP", b.host, (b.port ?: 19132).toString(), m, note = owner) { copyText(context, "Bedrock address", b.host) } }
                    ?: AddressRow(R.drawable.ic_grass_block, "Bedrock UDP", "Not available on this route", null, m, enabled = false) {}
            }
            if (wide) Row(Modifier.height(IntrinsicSize.Min)) {
                java(Modifier.weight(1f)); VerticalDivider(color = Palette.Line); bedrock(Modifier.weight(1f))
            } else {
                java(Modifier); HorizontalDivider(Modifier.padding(start = 72.dp), color = Palette.Line); bedrock(Modifier)
            }
            // Bedrock's app (2026) shows players and ping only for servers saved by IP; with a name it stays on "Loading
            // ping" although joining works. Measured on the owner's PC: the IP shows status, the name doesn't.
            val bedrockIp = d.net.active?.endpoints?.bedrockIp
            val bedrockPort = option.bedrock?.port
            if (option.custom && bedrockIp != null && bedrockPort != null) Row(
                Modifier.fillMaxWidth().clickable { copyText(context, "Bedrock IP", bedrockIp) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(buildAnnotatedString {
                    append("Bedrock's server list shows players only for an IP: ")
                    withStyle(SpanStyle(color = Palette.Good, fontWeight = FontWeight.Bold)) { append(bedrockIp) }
                    append(" port $bedrockPort")
                }, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { copyText(context, "Bedrock IP", bedrockIp) }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy Bedrock IP", tint = Palette.Good, modifier = Modifier.size(18.dp))
                }
            }
            if (option.custom && d.net.selected == PublicRoute.PLAYIT && !d.playitJavaNamesOpen)
                Text("Java: in the server list this name shows \"Can't connect\", but Join Server works. Playit Free answers list " +
                    "pings only for its own name (changing that is Premium), so add the Playit address to see status and players.",
                    color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            HorizontalDivider(color = Palette.Line)
            FriendsBox(ctx, options)
        }
    }
}

/** One edition's address in bold green: what friends type in Minecraft. */
@Composable fun AddressRow(art: Int, title: String, host: String, port: String?, modifier: Modifier, enabled: Boolean = true, note: String? = null,
                           onCopy: () -> Unit) {
    Row(modifier.clickable(enabled = enabled, onClick = onCopy).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtTile(art, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FitText(host, if (enabled) Palette.Good else Palette.Muted, maxSize = 16.sp)
            if (!enabled) Unit
            else if (port != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Port $port", color = Palette.Good, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                note?.let { Text(" · $it", color = Palette.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            else Text("No port needed", color = Palette.Muted, fontSize = 13.sp)
        }
        if (enabled) IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy $title address", tint = Palette.Good) }
    }
}

/** The same-Wi-Fi Java address as a chip in the same style as the service chips; tap to copy. */
@Composable fun LocalAddressChip(local: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.CardHigh).clickable { copyText(context, "Local address", local) }
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
        Text("Same Wi-Fi: ", color = Palette.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 5.dp))
        Text(local, color = Palette.Text, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
