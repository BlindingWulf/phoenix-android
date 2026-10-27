package dev.phoenix.server

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.phoenix.core.playit.PlayitApiException
import org.phoenix.core.playit.PlayitClaimClient
import org.phoenix.core.playit.PlayitControlPlaneClient
import org.phoenix.core.playit.PlayitCredential
import org.phoenix.core.playit.UrlConnectionPlayitTransport
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What one Phoenix tunnel carries. [JAVA_NAME] is a raw TCP tunnel to the same Java port for a custom name: Playit's
 * Minecraft Java tunnel answers only its own name on the free plan, and Java's server list pings with the name the
 * player typed, so a custom name needs a tunnel that doesn't read the handshake.
 */
enum class PlayitRole(val label: String, val tunnelName: String) {
    JAVA("Java", "Phoenix Java"), JAVA_NAME("Java for your name", "Phoenix Java Name"),
    BEDROCK("Bedrock", "Phoenix Bedrock"), VOICE("Voice chat", "Phoenix Voice");
}

/** One of this agent's tunnels, from Playit's run-data: assigned (with an address) or still pending. */
data class PlayitTunnelInfo(
    val id: UUID,
    val name: String,
    val tunnelType: String?,
    val portType: String?,
    val localPort: Int?,
    val displayAddress: String?,
    val pending: Boolean,
    val disabledReason: String? = null,
)

/** Pure tunnel matching and address rules, unit tested without Android or the network. */
object PlayitTunnels {
    /** The local port each role needs; voice only while Simple Voice Chat is installed. */
    fun wanted(ports: ServerPorts): Map<PlayitRole, Int> = buildMap {
        put(PlayitRole.JAVA, ports.java)
        put(PlayitRole.JAVA_NAME, ports.java)
        put(PlayitRole.BEDROCK, ports.bedrock)
        if (ports.voice > 0) put(PlayitRole.VOICE, ports.voice)
    }

    /**
     * The existing tunnel for [role]: the one Phoenix saved, else one of the right kind named by Phoenix, else one of the
     * right kind already targeting [localPort]. Never another kind of tunnel, so the owner's own tunnels are left alone.
     */
    fun find(tunnels: List<PlayitTunnelInfo>, role: PlayitRole, savedId: UUID?, localPort: Int): PlayitTunnelInfo? {
        savedId?.let { id -> tunnels.firstOrNull { it.id == id }?.let { return it } }
        val kind = tunnels.filter { matchesKind(it, role) }
        return kind.firstOrNull { it.name == role.tunnelName } ?: kind.firstOrNull { it.localPort == localPort }
    }

    fun matchesKind(tunnel: PlayitTunnelInfo, role: PlayitRole): Boolean = when (role) {
        PlayitRole.JAVA -> tunnel.tunnelType == "minecraft-java"
        PlayitRole.JAVA_NAME -> tunnel.tunnelType.isNullOrBlank() && tunnel.portType == "tcp" && tunnel.name == role.tunnelName
        PlayitRole.BEDROCK -> tunnel.tunnelType == "minecraft-bedrock"
        PlayitRole.VOICE -> tunnel.tunnelType.isNullOrBlank() && (tunnel.portType == null || tunnel.portType == "udp") &&
            tunnel.name == role.tunnelName
    }

    /**
     * Player-facing endpoints from the assigned tunnels. Java keeps Playit's port-less name when it has one (its own SRV
     * record supplies the port); [javaSrv] is the concrete host and port behind it, for a custom name's SRV record.
     */
    fun endpoints(byRole: Map<PlayitRole, PlayitTunnelInfo>, javaSrv: Endpoint?): RouteEndpoints {
        fun address(role: PlayitRole) = byRole[role]?.takeIf { !it.pending && it.disabledReason == null }?.displayAddress?.let(Endpoint::parse)
        val java = address(PlayitRole.JAVA)
        return RouteEndpoints(java, address(PlayitRole.BEDROCK)?.takeIf { it.port != null }, address(PlayitRole.VOICE)?.takeIf { it.port != null },
            javaSrv ?: java?.takeIf { it.port != null })
    }

    /** A message for the owner about a failed Playit API call for [what]. */
    fun explain(error: PlayitApiException, what: String): String = when (error.code()) {
        PlayitApiException.Code.INVALID_AGENT_CREDENTIAL, PlayitApiException.Code.CREDENTIAL_UNAVAILABLE,
        PlayitApiException.Code.NOT_AUTHORIZED -> "Authentication required: Playit no longer accepts this phone's sign-in. Connect Playit again."
        PlayitApiException.Code.ACCOUNT_VERIFICATION_REQUIRED -> "Playit asks you to verify your account's email first. Verify it on playit.gg, then try again."
        PlayitApiException.Code.PREMIUM_REQUIRED -> "Playit says $what needs Playit Premium on your account. Phoenix never buys anything."
        PlayitApiException.Code.RATE_LIMITED -> "Playit asked Phoenix to slow down. Try again in a minute."
        PlayitApiException.Code.TRANSPORT_FAILURE -> "Unable to reach Playit. Check the phone's internet connection."
        PlayitApiException.Code.TUNNEL_ALLOCATION_UNAVAILABLE, PlayitApiException.Code.REGION_UNAVAILABLE ->
            "Playit has no free address for $what on your account. Remove an unused tunnel on playit.gg, then try again."
        PlayitApiException.Code.AGENT_SCOPE_RESTRICTED, PlayitApiException.Code.AGENT_TYPE_NOT_SUPPORTED ->
            "This Playit agent can't manage its own tunnels. Connect Playit again from Phoenix."
        PlayitApiException.Code.AGENT_VERSION_UNSUPPORTED -> "Playit hasn't seen this phone's agent online with a supported version yet. Try again in a minute."
        PlayitApiException.Code.PROVIDER_INTERNAL_ERROR -> "Playit had an internal error setting up $what. Try again shortly."
        else -> "Playit refused $what (${error.code().name.lowercase().replace('_', ' ')})."
    }
}

/**
 * Playit.gg for Phoenix: the browser claim that links an agent to the owner's own Playit account, the encrypted agent
 * key, the Java/Bedrock/voice tunnels on the server's real ports, and the official agent process. The account is the
 * owner's; Phoenix holds only the agent key Playit issued for this phone and never sees a password or cookie.
 */
class PlayitManager(context: Context, private val log: (String) -> Unit) {
    enum class ClaimPhase { NONE, WAITING_FOR_VISIT, WAITING_FOR_APPROVAL, DENIED, EXPIRED, FAILED }
    data class ClaimView(val phase: ClaimPhase, val url: String?, val detail: String?)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("phoenix-playit", Context.MODE_PRIVATE)
    private val secrets = PlayitSecrets(app)
    val agent = PlayitAgent(app, log)
    private val claimWork = Executors.newSingleThreadScheduledExecutor { Thread(it, "phoenix-playit-claim").apply { isDaemon = true } }
    private val tunnelLock = Any()
    @Volatile private var claim = ClaimView(ClaimPhase.NONE, null, null)
    @Volatile private var pollBackoff = 0
    @Volatile private var claimTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile var lastError: String? = null; private set
    @Volatile var working: String? = null; private set
    /** Called once when a browser claim is approved and the key is saved. */
    @Volatile var onLinked: (() -> Unit)? = null

    init {
        // Remembered so a restarted app doesn't start the agent again with a key Playit already rejected.
        agent.onInvalidKey = { prefs.edit().putBoolean(PREF_AUTH_FAILED, true).apply() }
        resumeClaim()
    }

    fun hasKey(): Boolean = prefs.getBoolean(PREF_HAS_KEY, false)
    fun claim(): ClaimView = claim
    /** The last addresses Playit assigned. They stay in use while an older derivation is refreshed (see [endpointsStale]). */
    fun savedEndpoints(): RouteEndpoints? = prefs.getString(PREF_ENDPOINTS, null)?.let(::decodeEndpoints)
    /** Saved by older rules; read again from Playit at the next check, without blanking anything meanwhile. */
    fun endpointsStale(): Boolean = prefs.getInt(PREF_ENDPOINTS_VERSION, 1) < ENDPOINTS_VERSION
    fun authFailed(): Boolean = agent.state.phase == PlayitAgent.Phase.INVALID_KEY || prefs.getBoolean(PREF_AUTH_FAILED, false)

    /**
     * Starts the official claim: a random code whose Playit page the owner opens in their browser, signs in to their own
     * account and approves. Returns that page. Polling runs in the background (every 3 s, bounded) and survives the app
     * being backgrounded; a restart within the claim window resumes it.
     */
    fun beginClaim(): String {
        cancelClaim()
        val client = PlayitClaimClient(UrlConnectionPlayitTransport(), VERSION_TEXT)
        val newClaim = client.createClaim(PlayitClaimClient.AgentType.SELF_MANAGED)
        secrets.update { it.put("claimCode", newClaim.claimCode()).put("claimStartedAt", System.currentTimeMillis()) }
        claim = ClaimView(ClaimPhase.WAITING_FOR_VISIT, newClaim.claimUrl(), "Approve Phoenix in your browser")
        schedulePoll(0)
        return newClaim.claimUrl()
    }

    fun cancelClaim() {
        claimTask?.cancel(false); claimTask = null
        secrets.update { it.remove("claimCode"); it.remove("claimStartedAt"); it }
        claim = ClaimView(ClaimPhase.NONE, null, null)
    }

    private fun resumeClaim() {
        val saved = runCatching { secrets.read() }.getOrNull() ?: return
        val started = saved.optLong("claimStartedAt", 0L)
        if (!saved.has("claimCode")) return
        if (System.currentTimeMillis() - started > CLAIM_WINDOW_MS) { cancelClaim(); return }
        claim = ClaimView(ClaimPhase.WAITING_FOR_VISIT, PlayitClaimClient.CLAIM_PAGE_URL + saved.getString("claimCode"), "Approve Phoenix in your browser")
        schedulePoll(1)
    }

    private fun schedulePoll(delaySeconds: Long) {
        claimTask = claimWork.schedule({ runCatching { pollClaim() }.onFailure {
            claim = ClaimView(ClaimPhase.FAILED, null, "Couldn't check the Playit approval. Try again.")
        } }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun pollClaim() {
        val saved = secrets.read()
        val code = saved.optString("claimCode").takeIf { it.isNotBlank() } ?: return
        if (System.currentTimeMillis() - saved.optLong("claimStartedAt") > CLAIM_WINDOW_MS) {
            cancelClaim(); claim = ClaimView(ClaimPhase.EXPIRED, null, "The Playit approval page expired. Connect again to get a new one.")
            return
        }
        val client = PlayitClaimClient(UrlConnectionPlayitTransport(), VERSION_TEXT)
        val url = PlayitClaimClient.CLAIM_PAGE_URL + code
        val result = try {
            client.poll(client.restoreClaim(code, PlayitClaimClient.AgentType.SELF_MANAGED))
        } catch (error: PlayitApiException) {
            if (error.code() == PlayitApiException.Code.INVALID_CLAIM_CODE) {
                cancelClaim(); claim = ClaimView(ClaimPhase.EXPIRED, null, "Playit no longer knows that approval code. Connect again.")
                return
            }
            // Only network trouble and rate limits are worth retrying; anything Playit refused outright stops here.
            if (error.code() != PlayitApiException.Code.TRANSPORT_FAILURE && error.code() != PlayitApiException.Code.RATE_LIMITED &&
                error.code() != PlayitApiException.Code.PROVIDER_INTERNAL_ERROR) {
                cancelClaim()
                claim = ClaimView(ClaimPhase.FAILED, null, "Playit refused the sign-in request (${error.code().name.lowercase().replace('_', ' ')}). Try Connect again.")
                return
            }
            pollBackoff = (pollBackoff + 1).coerceAtMost(5)
            claim = claim.copy(detail = if (error.code() == PlayitApiException.Code.RATE_LIMITED) "Playit asked Phoenix to slow down; still waiting"
                else "Can't reach Playit right now; retrying")
            schedulePoll(minOf(60L, 3L shl pollBackoff))
            return
        }
        pollBackoff = 0
        when (result.state()) {
            PlayitClaimClient.ClaimState.PENDING_USER_VISIT -> { claim = ClaimView(ClaimPhase.WAITING_FOR_VISIT, url, "Open the Playit page and sign in"); schedulePoll(3) }
            PlayitClaimClient.ClaimState.PENDING_APPROVAL -> { claim = ClaimView(ClaimPhase.WAITING_FOR_APPROVAL, url, "Approve the agent on the Playit page"); schedulePoll(3) }
            PlayitClaimClient.ClaimState.DENIED -> { cancelClaim(); claim = ClaimView(ClaimPhase.DENIED, null, "The Playit request was declined. Nothing was changed.") }
            PlayitClaimClient.ClaimState.EXPIRED -> { cancelClaim(); claim = ClaimView(ClaimPhase.EXPIRED, null, "The Playit approval page expired. Connect again to get a new one.") }
            PlayitClaimClient.ClaimState.APPROVED -> {
                result.credential().use { credential ->
                    credential.useSecret { key ->
                        val text = String(key)
                        secrets.update { it.put("agentKey", text).also { json -> json.remove("claimCode"); json.remove("claimStartedAt") } }
                    }
                }
                // A different agent means different tunnels: forget the old agent's saved IDs and addresses.
                check(prefs.edit().putBoolean(PREF_HAS_KEY, true).remove(PREF_AUTH_FAILED).remove(PREF_AGENT_ID)
                    .remove(PREF_ENDPOINTS).apply { PlayitRole.entries.forEach { remove(idKey(it)); remove(createKey(it)) } }.commit())
                claimTask = null
                claim = ClaimView(ClaimPhase.NONE, null, null)
                agent.forgetRefusal()
                log("Playit connected to your account. Setting up tunnels…")
                onLinked?.invoke()
            }
        }
    }

    /** Forgets the agent key on this phone. The agent stays in the Playit account; remove it there to revoke it. */
    fun forget() {
        agent.stop()
        agent.forgetRefusal()
        cancelClaim()
        secrets.update { it.remove("agentKey"); it }
        check(prefs.edit().clear().commit())
    }

    /** The decrypted key for one use; callers wipe it. Null when none is saved. */
    private fun key(): CharArray? = runCatching { secrets.read().optString("agentKey").takeIf { it.isNotBlank() }?.toCharArray() }.getOrNull()

    /** Not with a key Playit rejected: that only fails again (and a new approval clears the mark). */
    fun startAgent() { if (hasKey() && !prefs.getBoolean(PREF_AUTH_FAILED, false) && agent.installed) agent.start(::key) }
    /** The account is over Playit's agent limit until the owner deletes an agent on playit.gg (kept after the agent stops). */
    fun overAgentLimit(): Boolean = agent.state.phase == PlayitAgent.Phase.OVER_LIMIT ||
        (agent.lastRefusal == PlayitAgent.Phase.OVER_LIMIT && agent.state.phase != PlayitAgent.Phase.RUNNING)
    fun stopAgent() = agent.stop()

    /**
     * Makes sure this agent has a tunnel for each role on the server's real ports and returns the addresses Playit
     * assigned. Idempotent: existing tunnels are found and reused (a changed local port is updated in place), and a
     * create whose result was unclear is reconciled against run-data before anything is created again.
     */
    fun ensureTunnels(ports: ServerPorts, progress: (String) -> Unit): Pair<RouteEndpoints, Map<PlayitRole, String>> = synchronized(tunnelLock) {
        val key = key() ?: throw RouteException("Connect your Playit account first.")
        val credential = try { PlayitCredential.fromStoredSecret(key) } finally { key.fill('\u0000') }
        credential.use { cred ->
            val client = PlayitControlPlaneClient(UrlConnectionPlayitTransport(), cred)
            try {
                working = "Checking your Playit tunnels"; progress(working!!)
                var run = client.getAgentRunData()
                prefs.edit().putString(PREF_AGENT_ID, run.agentId().toString()).remove(PREF_AUTH_FAILED).apply()
                val problems = linkedMapOf<PlayitRole, String>()
                // Roles Playit refused for this plan; asked once, then left out until the account is connected again.
                val skipped = if (prefs.getString(PREF_NAME_TUNNEL, null) == "premium") mutableSetOf(PlayitRole.JAVA_NAME) else mutableSetOf()
                for ((role, port) in PlayitTunnels.wanted(ports).filterKeys { it !in skipped }) {
                    val known = infos(run)
                    val existing = PlayitTunnels.find(known, role, prefs.getString(idKey(role), null)?.let(UUID::fromString), port)
                    try {
                        if (existing == null) {
                            working = "Creating the Playit ${role.label} tunnel"; progress(working!!)
                            check(prefs.edit().putLong(createKey(role), System.currentTimeMillis()).commit())
                            val spec = when (role) {
                                PlayitRole.JAVA -> PlayitControlPlaneClient.TunnelSpec.minecraftJava()
                                PlayitRole.JAVA_NAME -> PlayitControlPlaneClient.TunnelSpec.tcp("Minecraft Java for a custom name")
                                PlayitRole.BEDROCK -> PlayitControlPlaneClient.TunnelSpec.minecraftBedrock()
                                PlayitRole.VOICE -> PlayitControlPlaneClient.TunnelSpec.udp("Simple Voice Chat")
                            }
                            val id = client.createTunnel(run.agentId(), LOCAL_IP, port, role.tunnelName, spec, true)
                            check(prefs.edit().putString(idKey(role), id.toString()).remove(createKey(role)).commit())
                            log("Playit ${role.label} tunnel created for local port $port")
                        } else {
                            prefs.edit().putString(idKey(role), existing.id.toString()).remove(createKey(role)).apply()
                            if (existing.localPort != null && existing.localPort != port) {
                                working = "Pointing the Playit ${role.label} tunnel at port $port"; progress(working!!)
                                client.configureTunnelTarget(existing.id, LOCAL_IP, port)
                                log("Playit ${role.label} tunnel now forwards to local port $port")
                            }
                        }
                    } catch (error: PlayitApiException) {
                        if (role == PlayitRole.JAVA) throw error
                        if (role == PlayitRole.JAVA_NAME && error.code() == PlayitApiException.Code.PREMIUM_REQUIRED) {
                            prefs.edit().putString(PREF_NAME_TUNNEL, "premium").apply()
                            skipped += role
                            log("Playit Free doesn't offer the raw TCP tunnel that would let a custom name show in Java's server list " +
                                "(Premium only). Joining through the name still works.")
                        } else problems[role] = PlayitTunnels.explain(error, "the ${role.label} tunnel")
                    }
                }
                // New tunnels are pending until Playit assigns an address; wait a bounded time.
                working = "Waiting for Playit to assign addresses"; progress(working!!)
                val deadline = SystemClock.elapsedRealtime() + 60_000
                var byRole: Map<PlayitRole, PlayitTunnelInfo>
                while (true) {
                    run = client.getAgentRunData()
                    val known = infos(run)
                    byRole = PlayitTunnels.wanted(ports).keys.mapNotNull { role ->
                        prefs.getString(idKey(role), null)?.let(UUID::fromString)?.let { id -> known.firstOrNull { it.id == id } }?.let { role to it }
                    }.toMap()
                    // A tunnel Playit doesn't list yet is still being set up, like a pending one. One that failed isn't awaited.
                    val waiting = PlayitTunnels.wanted(ports).keys.filter { it !in problems && it !in skipped }.any { role -> byRole[role]?.let { it.pending || it.displayAddress.isNullOrBlank() } ?: true }
                    if (!waiting || SystemClock.elapsedRealtime() > deadline) break
                    SystemClock.sleep(2_000)
                }
                log("Playit tunnels: " + PlayitTunnels.wanted(ports).keys.filter { it !in skipped }.joinToString("; ") { role ->
                    byRole[role]?.let { "${role.label} ${it.tunnelType ?: it.portType ?: "?"}${if (it.pending) " pending" else ""}" +
                        " address=${if (it.displayAddress.isNullOrBlank()) "none" else "yes"}" + (it.disabledReason?.let { r -> " disabled=$r" } ?: "") }
                        ?: "${role.label} not listed yet"
                })
                byRole.forEach { (role, tunnel) ->
                    if (tunnel.pending) problems[role] = "Playit hasn't assigned the ${role.label} address yet; Phoenix checks again at the next start."
                    tunnel.disabledReason?.let { problems[role] = "Playit disabled the ${role.label} tunnel ($it)." }
                }
                // A custom name's SRV goes to the raw TCP tunnel when there is one, so the server list works too;
                // otherwise to the Java tunnel's own target, where only joining works.
                val direct = byRole[PlayitRole.JAVA_NAME]?.takeIf { !it.pending && it.disabledReason == null }?.let { javaTarget(client, it) }
                val javaSrv = direct ?: javaTarget(client, byRole[PlayitRole.JAVA])
                prefs.edit().putString(PREF_NAME_CHECK, if (direct != null) "direct" else "checked").apply()
                val bedrockIp = byRole[PlayitRole.BEDROCK]?.takeIf { !it.pending && it.disabledReason == null }?.let { bedrockIpv4(client, it) }
                val endpoints = PlayitTunnels.endpoints(byRole, javaSrv).copy(bedrockIp = bedrockIp)
                if (endpoints.java == null) throw RouteException(problems[PlayitRole.JAVA] ?: "Playit hasn't assigned the Java address yet. Try again in a minute.")
                check(prefs.edit().putString(PREF_ENDPOINTS, encodeEndpoints(endpoints)).putInt(PREF_ENDPOINTS_VERSION, ENDPOINTS_VERSION).commit())
                lastError = null
                endpoints to problems
            } catch (error: PlayitApiException) {
                if (error.code() == PlayitApiException.Code.INVALID_AGENT_CREDENTIAL) prefs.edit().putBoolean(PREF_AUTH_FAILED, true).apply()
                lastError = PlayitTunnels.explain(error, "the Java tunnel")
                throw RouteException(lastError!!)
            } finally { working = null }
        }
    }

    /**
     * A custom name answers in Java's server list as well as for joining: its SRV leads to the raw TCP tunnel, which
     * doesn't check names. False while only Playit's Minecraft Java tunnel is available (joins work, the list doesn't).
     */
    fun customNamesListed(): Boolean = prefs.getString(PREF_NAME_CHECK, null) == "direct"

    /**
     * The host and port a custom name's SRV must copy for Java: Playit's own published SRV record for its Java name.
     * Playit Free routes Java by the name a client sends, and joining clients send the SRV target, so it has to be
     * Playit's name (measured 2026-10-11: its raw "*.ip.gl.ply.gg" host and the IP are refused). The allocation's
     * port with Playit's name is the fallback when the SRV can't be read.
     */
    private fun javaTarget(client: PlayitControlPlaneClient, java: PlayitTunnelInfo?): Endpoint? {
        val tunnel = java ?: return null
        val display = Endpoint.parse(tunnel.displayAddress) ?: return null
        if (display.port != null) return display
        runCatching {
            AndroidDnsRecordLookup().query("_minecraft._tcp.${display.host}", MinecraftHowDns.TYPE_SRV).answers
                .firstOrNull { it.type == MinecraftHowDns.TYPE_SRV && it.target != null && it.port != null }
                ?.let { Endpoint(it.target!!.trimEnd('.'), it.port) }
        }.getOrNull()?.let { return it }
        return runCatching {
            client.getTunnelInventory().firstOrNull { it.id() == tunnel.id }?.allocations()?.firstNotNullOfOrNull { allocation ->
                allocation.port().orElse(null)?.let { Endpoint(display.host, it) }
            }
        }.getOrNull()
    }

    /**
     * Playit's IPv4 address for the Bedrock tunnel, from its allocation (else from DNS). Playit's names also carry IPv6,
     * and Bedrock's server list stays on "Loading ping" for such names; the plain IPv4 (or a name with only this A
     * record) shows the server's name and players.
     */
    private fun bedrockIpv4(client: PlayitControlPlaneClient, tunnel: PlayitTunnelInfo): String? {
        fun ipv4(text: String?) = text?.takeIf { IPV4.matches(it) }
        runCatching {
            client.getTunnelInventory().firstOrNull { it.id() == tunnel.id }?.allocations()?.firstNotNullOfOrNull { ipv4(it.ip().orElse(null)) }
        }.getOrNull()?.let { return it }
        val host = Endpoint.parse(tunnel.displayAddress)?.host ?: return null
        return runCatching { java.net.InetAddress.getAllByName(host).firstOrNull { it is java.net.Inet4Address }?.hostAddress }.getOrNull()
    }

    private fun infos(run: PlayitControlPlaneClient.AgentRunData): List<PlayitTunnelInfo> =
        run.tunnels().map { PlayitTunnelInfo(it.id(), it.name(), it.tunnelType().orElse(null), it.portType().orElse(null),
            it.localPort().orElse(null), it.displayAddress().orElse(null), false, it.disabledReason().orElse(null)) } +
        run.pending().map { PlayitTunnelInfo(it.id(), it.name(), null, null, null, null, true) }

    private fun idKey(role: PlayitRole) = "tunnel-${role.name.lowercase()}"
    private fun createKey(role: PlayitRole) = "creating-${role.name.lowercase()}"

    companion object {
        private const val LOCAL_IP = "127.0.0.1"
        private const val PREF_HAS_KEY = "has-key"
        private const val PREF_AUTH_FAILED = "auth-failed"
        private const val PREF_AGENT_ID = "agent-id"
        private const val PREF_ENDPOINTS = "endpoints"
        private const val PREF_ENDPOINTS_VERSION = "endpoints-version"
        /**
         * 2: Java's SRV target is Playit's own published SRV target (its name). 3: Java's name check was tried.
         * 4: the SRV goes to the raw TCP tunnel for custom names. 5: Bedrock's IPv4 address is recorded.
         */
        private const val ENDPOINTS_VERSION = 5
        private val IPV4 = Regex("""^(\d{1,3})(\.\d{1,3}){3}$""")
        private const val PREF_NAME_CHECK = "java-name-check"
        private const val PREF_NAME_TUNNEL = "java-name-tunnel"
        /** Phoenix gives up waiting for an approval after this long; the owner can start again. */
        private const val CLAIM_WINDOW_MS = 15 * 60_000L
        /** Shown to the owner on Playit's approval page; Playit refuses more than 30 characters. */
        private const val VERSION_TEXT = "Phoenix Server playitd 1.0.12"

        fun encodeEndpoints(endpoints: RouteEndpoints): String = JSONObject()
            .put("java", endpoints.java?.text).put("javaSrv", endpoints.javaSrv?.text)
            .put("bedrock", endpoints.bedrock?.text).put("voice", endpoints.voice?.text).put("bedrockIp", endpoints.bedrockIp).toString()

        fun decodeEndpoints(text: String): RouteEndpoints? = runCatching {
            val json = JSONObject(text)
            fun read(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(Endpoint::parse)
            RouteEndpoints(read("java"), read("bedrock"), read("voice"), read("javaSrv"),
                bedrockIp = json.optString("bedrockIp").takeIf { it.isNotBlank() && it != "null" })
        }.getOrNull()
    }
}

/** The agent key and any open claim code, in one Keystore-encrypted, backup-excluded file. */
private class PlayitSecrets(context: Context) {
    private val file = AtomicFile(File(File(context.noBackupFilesDir, "playit").apply { mkdirs() }, "secrets.enc"))
    private val alias = "phoenix.playit.v2"

    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val packed = file.readFully()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false) ?: error("Playit key store missing"), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            val plain = cipher.doFinal(packed, 12, packed.size - 12)
            try { return JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
        } finally { packed.fill(0) }
    }

    @Synchronized fun update(change: (JSONObject) -> JSONObject) {
        val document = change(runCatching { read() }.getOrDefault(JSONObject()))
        val plain = document.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val packed = cipher.iv + cipher.doFinal(plain)
        val stream = file.startWrite()
        try { stream.write(packed); file.finishWrite(stream) } catch (failure: Exception) { file.failWrite(stream); throw failure }
        finally { plain.fill(0); packed.fill(0) }
    }

    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
