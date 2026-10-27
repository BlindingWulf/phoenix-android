package dev.phoenix.server

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.phoenix.core.ServerController.State

/**
 * Oracle Cloud regions Phoenix suggests as a home region, by where most players are. Oracle's sign-up page shows the
 * regions it currently offers; Always Free VMs run only in the account's home region, which cannot be changed later.
 * Phoenix never promises capacity: the real region is reported by the proxy itself when it pairs.
 */
object OracleRegions {
    data class Region(val id: String, val place: String)
    data class Area(val key: String, val label: String, val regions: List<Region>)

    val areas = listOf(
        Area("us", "United States", listOf(Region("us-ashburn-1", "US East (Ashburn)"), Region("us-chicago-1", "US Midwest (Chicago)"),
            Region("us-phoenix-1", "US West (Phoenix)"), Region("us-sanjose-1", "US West (San Jose)"))),
        Area("ca", "Canada", listOf(Region("ca-toronto-1", "Canada Southeast (Toronto)"), Region("ca-montreal-1", "Canada Southeast (Montreal)"))),
        Area("eu", "Europe", listOf(Region("eu-frankfurt-1", "Germany Central (Frankfurt)"), Region("uk-london-1", "UK South (London)"),
            Region("eu-amsterdam-1", "Netherlands Northwest (Amsterdam)"), Region("eu-paris-1", "France Central (Paris)"),
            Region("eu-madrid-1", "Spain Central (Madrid)"), Region("eu-stockholm-1", "Sweden Central (Stockholm)"))),
    )

    fun area(key: String?) = areas.firstOrNull { it.key == key }
    fun place(regionId: String?) = areas.flatMap { it.regions }.firstOrNull { it.id == regionId }?.place ?: regionId
}

/** Oracle Resource Manager deployment of the proxy stack (deploy/oracle-proxy in the sources). */
object OracleDeploy {
    const val SIGN_UP = "https://signup.cloud.oracle.com/"
    private const val CREATE_STACK = "https://cloud.oracle.com/resourcemanager/stacks/create"
    const val TEMPLATE_ASSET = "phoenix-oracle-proxy.zip"

    /** True when this build names a published copy of the stack, so Oracle can load it from a link. */
    val hasPublishedStack get() = BuildConfig.ORACLE_STACK_ZIP_URL.isNotBlank()

    /** Oracle's "Deploy to Oracle Cloud" page for the published stack, or the empty create-stack page. */
    fun deployUrl(): String =
        if (hasPublishedStack) "$CREATE_STACK?zipUrl=${BuildConfig.ORACLE_STACK_ZIP_URL}" else CREATE_STACK

    /**
     * Saves the stack bundled in this APK to Downloads, for builds without a published copy: on Oracle's create-stack
     * page the owner picks "My configuration", then ".Zip file", and chooses it. Returns the file name.
     */
    fun saveTemplate(context: Context): String {
        val name = "phoenix-oracle-proxy.zip"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save to Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> context.assets.open(TEMPLATE_ASSET).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (failure: Exception) {
            resolver.delete(uri, null, null); throw failure
        }
        return name
    }
}

// ---------------------------------------------------------------- Regional Optimized (experimental)

/**
 * The owner's own proxy on a free Oracle Cloud VM, shown only after the experimental switch was confirmed. Four short
 * steps; Oracle's own form details, capacity errors and removal live under Help. Nothing is created in Oracle by
 * Phoenix, and players move only when the owner confirms, through the same switch path as every route.
 */
@Composable fun RegionalDetails(ctx: ScreenContext, onUse: () -> Unit, onLeave: () -> Unit) {
    val d = ctx.d
    val vm = ctx.vm
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    val stage = d.regionalProxyStage
    if (!d.regionalEnabled) {
        Text("Your own relay near your players, on an Oracle Cloud Always Free VM (US, Canada or Europe). It needs a separate Oracle " +
            "account and extra setup; lower ping depends on the region and players' networks. Turn it on to see the setup.",
            color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    val canManage = d.netBirdSignedIn && d.netBirdLinkUp && !d.busy
    val easyReady = d.networkHasIdentity
    val place = OracleRegions.place(d.regionalProxyRegion)
    val area = d.regionalArea ?: "us"
    Text("Where are most of your players?", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OracleRegions.areas.forEach { ChoicePill(it.label, area == it.key, { vm.chooseRegionalArea(it.key) }) }
    }
    val paired = stage !in setOf("NOT_SET_UP", "PAIRING")
    StepRow(1, "Connect Oracle", "Free Oracle Cloud account; pick a home region such as ${OracleRegions.area(area)?.regions?.firstOrNull()?.place}.",
        if (paired) Tone.OK else Tone.IDLE)
    StepRow(2, "Deploy the free VM", when (stage) {
        "PAIRING" -> "Code ready until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(d.regionalPairingExpiresAt))
        else -> if (easyReady) "Phoenix copies a one-time code; paste it into Oracle's form" else "Set up NetBird first; the regional relay uses it"
    }, when { paired -> Tone.OK; stage == "PAIRING" -> Tone.WORKING; easyReady -> Tone.IDLE; else -> Tone.WARN })
    StepRow(3, "Pair automatically", if (paired) "Paired with ${place ?: "your VM"}" else "Happens a few minutes after Oracle finishes",
        if (paired) Tone.OK else if (stage == "PAIRING") Tone.WORKING else Tone.IDLE)
    StepRow(4, "Verify connection", d.regionalRouteReport ?: d.regionalProxyDetail.takeIf { paired },
        when { stage == "READY" && d.regionalRouteReport?.startsWith("Java answers") == true -> Tone.OK; stage == "PROXY_OFFLINE" -> Tone.WARN; paired -> Tone.WORKING; else -> Tone.IDLE })
    when (stage) {
        "NOT_SET_UP" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Oracle sign-up", { ctx.online.open(OracleDeploy.SIGN_UP) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.OpenInNew)
            PrimaryButton("Get pairing code", { vm.startRegionalSetup() }, Modifier.weight(1f), enabled = canManage && easyReady)
        }
        "PAIRING" -> {
            PrimaryButton("Copy code and open Oracle", {
                val code = vm.regionalPairingCode()
                if (code == null) note = "The pairing code expired. Cancel and get a new one." to Tone.ERROR
                else {
                    val saved = if (OracleDeploy.hasPublishedStack) null else runCatching { OracleDeploy.saveTemplate(context) }
                    if (saved?.isFailure == true) note = "Could not save the template: ${saved.exceptionOrNull()?.message}" to Tone.ERROR
                    else {
                        copySensitive(context, "Phoenix pairing code", code)
                        ctx.online.open(OracleDeploy.deployUrl())
                        note = "Code copied" + (if (saved != null) "; template saved to Downloads" else "") + ". Paste it into Oracle's form and tap Create." to Tone.OK
                    }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.ContentCopy)
            TextButton(onClick = { vm.cancelRegionalSetup() }, enabled = !d.busy) { Text("Cancel pairing (revokes the code)", color = Palette.Muted) }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Check", { vm.checkRegionalProxy() }, Modifier.weight(1f), icon = Icons.Rounded.Refresh, enabled = canManage)
            if (d.net.selected == PublicRoute.NETBIRD_REGIONAL) SecondaryButton("Move players to NetBird EU", onLeave,
                Modifier.weight(1f), enabled = !d.busy && d.net.switching == null)
            else PrimaryButton("Use for players", onUse, Modifier.weight(1f), enabled = canManage && stage == "READY" && d.net.switching == null)
        }
    }
    if (!canManage && !d.busy && stage != "NOT_SET_UP") SignInHint(ctx)
    d.regionalTrafficBytes?.let {
        Text("About ${gb(it)} through your relay this month. Oracle's Always Free allowance is currently 10 TB of outbound transfer a month, " +
            "subject to Oracle's terms and your account.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
    note?.let { (text, noteTone) -> Banner(text, noteTone, onDismiss = { note = null }) }
    Row {
        TextButton(onClick = { help = !help }) { Text(if (help) "Hide help" else "Help and troubleshooting", color = Palette.Info) }
        if (stage != "NOT_SET_UP") TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Technical details", color = Palette.Muted) }
    }
    if (help) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Oracle sign-up: Always Free VMs exist only in your home region, which can't be changed later. Oracle may ask for a card and identity checks; Phoenix's template only uses Always Free resources and never upgrades your account.",
            if (OracleDeploy.hasPublishedStack) "Deploy: sign in to Oracle, paste the code into Phoenix pairing code, keep Run apply ticked and tap Create."
            else "Deploy: Phoenix saves phoenix-oracle-proxy.zip to Downloads. In Oracle choose My configuration › .Zip file › that file, tap Next, paste the code into Phoenix pairing code, tap Next, keep Run apply ticked and tap Create.",
            "Pairing: keep Phoenix open with NetBird on; it pairs a few minutes after Oracle finishes. The code works once, for 48 hours.",
            "Out of host capacity: edit the stack, choose the other free VM type (Ampere) or another availability domain, and apply again, or try later. A home-region message means switching the console to that region first.",
            "Removing: tap Remove under Technical details (revokes the relay's NetBird token), then Destroy the stack in Oracle's Resource Manager.",
        ).forEach { Text("•  $it", color = Palette.Muted, style = MaterialTheme.typography.bodySmall) }
    }
    if (details && stage != "NOT_SET_UP") Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoRow("Relay address", d.regionalProxyIp ?: "—", mono = true)
        InfoRow("Oracle region", d.regionalProxyRegion ?: "—")
        InfoRow("VM", d.regionalProxyShape ?: "—")
        d.regionalPath?.let { Text(it, color = if (it.startsWith("Direct")) Palette.Good else Palette.Warn, style = MaterialTheme.typography.bodySmall) }
        Text("Open ports: Java TCP, Bedrock UDP, voice UDP and NetBird WireGuard UDP 51820. No SSH or web ports. An Always Free VM has no " +
            "paid DDoS protection; its firewall limits each address's connection rate.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { confirmRemove = true }, enabled = canManage && d.net.selected != PublicRoute.NETBIRD_REGIONAL) { Text("Remove", color = Palette.Bad) }
    }
    if (confirmRemove) ConfirmDialog(
        "Remove the regional relay?",
        "Phoenix deletes its regional services and revokes the relay's token in NetBird. Then delete the VM in Oracle: Resource Manager › " +
            "Stacks › your Phoenix stack › Destroy.",
        "Remove", onConfirm = { vm.removeRegionalProxy() }, onDismiss = { confirmRemove = false }, destructive = true,
    )
}

/** NetBird issues no refresh token, so account actions need a fresh browser sign-in after it expires. */
@Composable private fun SignInHint(ctx: ScreenContext) {
    val d = ctx.d
    when {
        !d.netBirdLinkUp -> Text("Turn on NetBird first.", color = Palette.Warn, style = MaterialTheme.typography.bodySmall)
        !d.netBirdSignedIn -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your NetBird sign-in expired. Hosting keeps working; sign in again to set this up.", color = Palette.Warn,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { ctx.online.signInAgain(d) }, enabled = d.netBirdEnrolled && !d.netBirdAuthorizing) {
                Text("Sign in", color = Palette.Warn, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Copies a secret: Android 13+ hides sensitive clips from its clipboard preview. */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = android.content.ClipData.newPlainText(label, text)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(clip)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU)
        android.widget.Toast.makeText(context, "$label copied", android.widget.Toast.LENGTH_SHORT).show()
}
