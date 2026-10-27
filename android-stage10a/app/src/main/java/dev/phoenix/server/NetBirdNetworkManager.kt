package dev.phoenix.server

import android.content.Context
import org.json.JSONObject
import org.phoenix.core.network.netbird.NetBirdByop
import org.phoenix.core.network.netbird.NetBirdServicesClient
import org.phoenix.core.network.netbird.NetBirdProvisioningCoordinator
import org.phoenix.core.network.netbird.NetBirdApiException
import org.phoenix.core.network.netbird.ProxyPairing
import java.util.UUID

/** Permanent service management. Transport and hostname changes remain independent adapters. */
class NetBirdNetworkManager(context: Context) {
    private val app = context.applicationContext
    private val store = NetBirdStore(context)
    private val transport = NetBirdTransport(context)
    @Volatile private var detail = "Public services are created automatically after NetBird sign-in."
    @Volatile private var phase = "NOT_CONFIGURED"
    @Volatile private var busy = false
    @Volatile private var lastSnapshot: Snapshot? = null

    /**
     * [link], [account] and [services] are the independent setup stages shown to the user.
     * [activity] is non-null exactly when something is in progress, and names what it waits for.
     */
    data class Snapshot(val phase: String, val detail: String, val busy: Boolean,
        val canProvision: Boolean, val canReconnect: Boolean, val hasIdentity: Boolean,
        val publicAddress: String?, val bedrockAddress: String?, val peerIp: String?,
        val link: String = "", val account: String = "", val services: String = "", val activity: String? = null,
        /** The phone's NetBird tunnel is up, and a browser sign-in is open, read from the local client only. */
        val linkUp: Boolean = false, val authorizing: Boolean = false,
        /** The client is running in this process (connected or not), and the phone has a device identity. */
        val live: Boolean = false, val enrolled: Boolean = false)

    fun snapshot(): Snapshot = synchronized(this) {
        val tunnel = transport.snapshot()
        val link = linkLine(tunnel)
        // The provisioning worker holds the journal's cross-process lock around network writes.
        // Keep the main-thread status display responsive using the last durable local view.
        if (busy) {
            val base = lastSnapshot ?: Snapshot("PROVISIONING", detail, true, false, false, false, null, null, tunnel.peerIp)
            return@synchronized base.copy(phase = "PROVISIONING", detail = detail, busy = true, canProvision = false,
                peerIp = tunnel.peerIp, link = link, services = detail, activity = detail,
                linkUp = tunnel.connected, authorizing = tunnel.authorizing, live = tunnel.live)
        }
        // Never wait on the store lock while another thread holds it; the last derived view is shown instead.
        val view = runCatching { store.peekDerived("status", ::storeView) }.getOrElse {
            val failure = it.message ?: "Encrypted state needs recovery"
            return@synchronized Snapshot("RECOVERY_REQUIRED", failure, false, false, false, false, null, null, null,
                link = link, account = "Unknown until saved NetBird state is recovered.", services = failure,
                linkUp = tunnel.connected, authorizing = tunnel.authorizing, live = tunnel.live)
        }
        // First read after launch while the store is held: show a neutral "checking" line rather than block.
        if (view == null) return@synchronized (lastSnapshot ?: Snapshot("CHECKING", "Reading saved NetBird state", false, false, false, NetworkIntent.lastKnown(null, NetBirdLastKnown.hasIdentity(app), false), null, null, tunnel.peerIp))
            .copy(peerIp = tunnel.peerIp, link = link, linkUp = tunnel.connected, authorizing = tunnel.authorizing, live = tunnel.live)
        val allocations = view.phase == NetBirdProvisioningCoordinator.Phase.READY
        val retryAt = maxOf(view.retryAfter, view.peerRetryUntil)
        val coolingDown = retryAt > System.currentTimeMillis()
        val retrySeconds = ((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
        val displayPhase=when {
            coolingDown -> "RATE_LIMITED"
            tunnel.authorizing -> "AUTHORIZING_ACCOUNT"
            tunnel.busy -> "CONNECTING"
            phase=="NOT_CONFIGURED" && allocations -> "ALLOCATED"
            phase=="NOT_CONFIGURED" && tunnel.connected -> "PEER_CONNECTED"
            else -> phase
        }
        val services = when {
            coolingDown -> "NetBird asked Phoenix to wait. You can try again after " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(retryAt)) +
                " (in ${retrySeconds}s); Phoenix does not retry in the background."
            allocations -> "Java TCP ${view.tcpPublicPort} · Bedrock UDP ${view.udpPublicPort} (kept while offline)"
            phase == "RECOVERY_REQUIRED" -> detail
            view.phase == NetBirdProvisioningCoordinator.Phase.PROVISIONING -> "Setup stopped part-way; Phoenix finishes it safely next time."
            else -> "Not created yet"
        }
        val activity = when {
            tunnel.authorizing -> "Finish signing in to NetBird in your browser"
            tunnel.busy -> tunnel.detail
            else -> null
        }
        Snapshot(displayPhase, services, busy || tunnel.busy,
            view.hasToken && tunnel.connected && !tunnel.busy && !coolingDown,
            allocations, allocations,
            if (view.tcpPublicPort > 0) "${view.cluster}:${view.tcpPublicPort}" else null,
            if (view.udpPublicPort > 0) "${view.cluster}:${view.udpPublicPort}" else null,
            tunnel.peerIp, link, accountLine(tunnel), services, activity, tunnel.connected, tunnel.authorizing,
            live = tunnel.live, enrolled = view.enrolled).also {
            if (lastSnapshot?.hasIdentity != it.hasIdentity) NetBirdLastKnown.rememberHasIdentity(app, it.hasIdentity)
            lastSnapshot=it
        }
    }

    /** The non-secret parts of the encrypted store that the status display needs. */
    private data class StoreView(val phase: NetBirdProvisioningCoordinator.Phase, val retryAfter: Long, val tcpPublicPort: Int,
        val udpPublicPort: Int, val cluster: String?, val hasToken: Boolean, val peerRetryUntil: Long, val enrolled: Boolean)

    private fun storeView(journal: NetBirdProvisioningCoordinator.Snapshot, config: JSONObject) = StoreView(
        journal.phase(), journal.retryAfterEpochMillis() ?: 0L, journal.tcpPublicPort(), journal.udpPublicPort(), journal.cluster(),
        config.optString("token").isNotBlank(), config.optLong("peerRetryUntil"), config.optBoolean("identityEnrolled"))

    private fun linkLine(tunnel: NetBirdTransport.Snapshot): String =
        if (tunnel.connected) "${tunnel.detail}${tunnel.peerIp?.let { " ($it)" }.orEmpty()}" else tunnel.detail

    private fun accountLine(tunnel: NetBirdTransport.Snapshot): String = accountLine(app, tunnel)

    companion object {
        /** How long a pairing code stays valid; the VM gives up after the same 48 hours. */
        const val PAIRING_WINDOW_MS = 48L * 3600 * 1000
        const val MAX_PAIRING_FAILURES = 30
        /** Account stage wording shared by the dashboard and the NetBird screen. Local reads only. */
        fun accountLine(app: Context, tunnel: NetBirdTransport.Snapshot): String {
            if (tunnel.authorizing) return "Finish signing in in your browser (valid for 5 minutes)"
            val state = NetBirdOAuthSession.accountState(app)
            val event = tunnel.accountDetail?.takeIf { !it.startsWith("Waiting") && !it.contains("authorized on this phone") }
            return when (state) {
                NetBirdOAuthSession.AccountState.VERIFIED -> "Signed in"
                NetBirdOAuthSession.AccountState.SAVED -> "Signed in"
                NetBirdOAuthSession.AccountState.EXPIRED -> event
                    ?: "Sign-in expired. Hosting keeps working; sign in again to change services."
                NetBirdOAuthSession.AccountState.NONE -> event ?: "Not signed in"
            }
        }
    }
    private fun client(credential: NetBirdOAuthSession.Credential) = NetBirdServicesClient(UrlConnectionNetBirdTransport(),
        credential.token, credential.scheme)

    fun provision(tcpPort: Int, udpPort: Int): Snapshot = operation {
        store.locked {
            val saved = store.load()
            val retryAt = maxOf(saved.retryAfterEpochMillis() ?: 0L, store.configuration().optLong("peerRetryUntil"))
            check(retryAt <= System.currentTimeMillis()) { "NetBird asked Phoenix to wait until $retryAt; connection status updates automatically" }
            val tunnel = transport.snapshot()
            check(tunnel.connected && !tunnel.peerIp.isNullOrBlank()) { "Authorize and connect NetBird on this phone before saving services" }
            var config = store.configuration()
            check(config.optLong("peerRetryUntil")<=System.currentTimeMillis()) { "NetBird peer API rate limited; retry after ${config.optLong("peerRetryUntil")}" }
            check(config.optString("token").isNotBlank()) { "Authorize your NetBird account in Phoenix first" }
            val credential=NetBirdOAuthSession.credential(app)
            config=store.configuration() // Preserve any rotated OAuth token written during refresh.
            val api = client(credential)
            val peerApi = NetBirdPeerClient(UrlConnectionNetBirdTransport(), credential.token, credential.scheme)
            val peer = peerApi.resolvePeer(tunnel.peerIp!!)
            val peerId = peer.getString("id")
            if (config.has("peerId")) check(config.getString("peerId") == peerId) { "The connected phone peer differs from the permanent services' saved peer" }
            check(peer.optBoolean("connected")) { "NetBird Cloud has not confirmed that the hosting phone is connected" }
            peerApi.preserveHostingSession(peerId)
            val clusters = api.listClusters().filter { it.isNetBirdShared && it.online() }
            if(config.has("cluster"))check(clusters.any { it.address()==config.getString("cluster") }) { "The saved shared NetBird cluster is unavailable; allocations have been preserved" }
            if (!config.has("tcpDomain")) {
                // Phoenix services aimed at this phone's own NetBird identity mean its saved journal was lost: recover those
                // instead of making duplicates. Services of another install (another phone, or Phoenix before a reinstall) target
                // another peer; they are left alone and this install gets its own, under its own key.
                val phoenix = api.listServices().filter { it.name().startsWith("Phoenix ") || it.domain().matches(Regex("phoenix-[a-z0-9]+-(java|bedrock)\\..*")) }
                check(phoenix.none { service -> service.targets().any { it.targetId() == peerId } }) {
                    "This phone already has Phoenix services in your NetBird account from an earlier setup. Recover their saved allocation journal before creating services."
                }
                // NetBird lists each shared cluster's own address as a free domain and sets
                // target_cluster only on custom domains, so a free domain names its cluster itself.
                val (freeDomain, cluster) = api.listDomains().filter { it.isFree && it.validated() }
                    .firstNotNullOfOrNull { candidate ->
                        val address = candidate.targetCluster() ?: candidate.domain()
                        clusters.firstOrNull { it.address() == address }?.let { candidate to it }
                    }
                    ?: error("This account has no available hosted shared NetBird domain for permanent services")
                val allocationKey = config.optString("allocationKey").ifBlank { UUID.randomUUID().toString().replace("-", "").take(20) }
                config.put("allocationKey", allocationKey).put("peerId", peerId)
                    .put("tcpDomain", "phoenix-$allocationKey-java.${freeDomain.domain()}")
                    .put("udpDomain", "phoenix-$allocationKey-bedrock.${freeDomain.domain()}")
                    .put("cluster", cluster.address())
                store.saveConfiguration(config) // Intent is durable before the first service POST.
            }
            val state = store.load()
            val plan = state.plan() ?: NetBirdProvisioningCoordinator.Plan("baseline", peerId,
                "Phoenix ${config.getString("allocationKey")} Java", config.getString("tcpDomain"), tcpPort,
                "Phoenix ${config.getString("allocationKey")} Bedrock", config.getString("udpDomain"), udpPort,
                config.getString("cluster"))
            check(plan.tcpLocalPort() == tcpPort && plan.udpLocalPort() == udpPort) { "Listener ports changed; reconcile the permanent targets before reconnecting" }
            val result = NetBirdProvisioningCoordinator(api, store).provision(plan)
            phase = if (result.tcp().active() && result.udp().active()) "SERVICES_ACTIVE" else "ALLOCATED"
            detail = "Java TCP ${result.tcp().publicPort()} · Bedrock UDP ${result.udp().publicPort()} saved"
        }
    }
    fun reconnect(tcpPort: Int, udpPort: Int): Snapshot {
        val tunnel=transport.reconnect()
        val state = store.load()
        if (state.phase() != NetBirdProvisioningCoordinator.Phase.READY) {
            detail = "No public ports yet. Tap Set up online play."
            return snapshot()
        }
        check(state.plan().tcpLocalPort()==tcpPort && state.plan().udpLocalPort()==udpPort) { "Listener ports changed; reconcile the permanent targets before reconnecting" }
        phase="ALLOCATED"
        detail=if(tunnel.connected) "Online play connected" else "Reconnecting online play…"
        // Starting Minecraft reconnects the saved device identity. It never allocates services or
        // requires a fresh management token; authenticated reconciliation happens on explicit Save.
        return snapshot()
    }
    fun endpoints(): NetBirdProvisioningCoordinator.Result = store.load().savedResult()

    /** A blocking read of whether permanent services exist; for one-time decisions, never for status polling. */
    fun hasSavedPorts(): Boolean = runCatching { store.load().phase() == NetBirdProvisioningCoordinator.Phase.READY }.getOrDefault(false)

    /** The saved public cluster and ports, or null before services exist. Cached like the status view: no decrypt per call. */
    data class PublicPorts(val cluster: String?, val tcp: Int, val udp: Int)
    fun publicPorts(): PublicPorts? = runCatching {
        store.peekDerived("public-ports") { journal, _ ->
            if (journal.phase() == NetBirdProvisioningCoordinator.Phase.READY) PublicPorts(journal.cluster(), journal.tcpPublicPort(), journal.udpPublicPort())
            else PublicPorts(null, 0, 0)
        }
    }.getOrNull()?.takeIf { it.cluster != null && it.tcp > 0 && it.udp > 0 }

    // ------------------------------------------------------------ Regional proxy (NetBird Bring Your Own Proxy)

    /**
     * Local view of Phoenix's own regional proxy. [stage] is NOT_SET_UP, PAIRING (a pairing code exists and Phoenix waits
     * for the Oracle VM to call), WAITING_FOR_PROXY (paired; the proxy has not registered with NetBird yet),
     * PROXY_OFFLINE, PROXY_ONLINE (services not all active yet) or READY. [ip] is the VM's public address.
     */
    data class RegionalProxy(val stage: String = "NOT_SET_UP", val detail: String = "Not set up", val ip: String? = null,
        val apex: String? = null, val forwards: String? = null, val checkedAt: Long = 0L,
        val region: String? = null, val shape: String? = null, val pairingExpiresAt: Long = 0L) {
        val ready get() = stage == "READY" && ip != null
    }

    /** The open pairing: the code the owner pastes into Oracle, and the local port Phoenix listens on for the VM. */
    class PendingPairing(val code: ProxyPairing.Code, val localPort: Int, val expiresAt: Long) {
        override fun toString() = "PendingPairing{port=$localPort, expiresAt=$expiresAt}"
    }

    /** Never waits for the store; the last derived view is reused while another thread holds it. */
    fun regionalProxy(): RegionalProxy = runCatching {
        store.peekDerived("byop") { _, config -> regionalProxyOf(config.optJSONObject("byop")) }
    }.getOrNull() ?: lastRegionalProxy
    @Volatile private var lastRegionalProxy = RegionalProxy()

    private fun regionalProxyOf(byop: JSONObject?): RegionalProxy {
        if (byop == null) return RegionalProxy()
        return RegionalProxy(byop.optString("stage", "NOT_SET_UP"), byop.optString("detail", "Not set up"),
            byop.optString("ip").ifBlank { null }, byop.optString("apex").ifBlank { null },
            byop.optString("forwards").ifBlank { null }, byop.optLong("checkedAt"),
            byop.optString("region").ifBlank { null }, byop.optString("shape").ifBlank { null },
            byop.optJSONObject("pairing")?.optLong("expiresAt") ?: 0L).also { lastRegionalProxy = it }
    }

    /**
     * Opens a one-time pairing for a new Oracle proxy and returns the code the owner pastes into Oracle's form. Checks
     * first that this NetBird account may manage proxies and proxy tokens, so nothing is deployed for an account that
     * can't use it. A temporary TCP service on NetBird's shared cluster lets the VM reach this phone; it is deleted once
     * pairing finishes or is cancelled. The proxy token is created now, while the owner's NetBird session is fresh (NetBird
     * issues no refresh token, and Oracle may take a while), and stays in this encrypted store until a VM proves the
     * code; it is revoked if the pairing is cancelled or replaced.
     */
    fun startRegionalPairing(ports: NetBirdByop.Ports, localPort: Int): String = store.locked {
        check(store.load().phase() == NetBirdProvisioningCoordinator.Phase.READY) {
            "Set up Easy online play first. It stays as the fallback while the regional proxy is tested."
        }
        val api = client(NetBirdOAuthSession.credential(app))
        try { api.listClusters(); api.listProxyTokens() }
        catch (denied: NetBirdApiException) {
            if (denied.code() == NetBirdApiException.Code.PERMISSION_DENIED)
                error("Your NetBird role can't manage proxies. Ask the account owner to make you an admin, or use your own NetBird account.")
            throw denied
        }
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: JSONObject()
        check(byop.optString("apex").isBlank() || byop.optString("stage") == "PAIRING") {
            "A regional proxy already exists. Remove it first to pair a new one."
        }
        byop.optJSONObject("pairing")?.let { old ->
            old.optString("serviceId").takeIf { it.isNotBlank() }?.let { deleteQuietly(api, it) }
            old.optString("tokenId").takeIf { it.isNotBlank() }?.let { revokeQuietly(api, it) }
        }
        val key = config.getString("allocationKey")
        val cluster = config.getString("cluster")
        val spec = NetBirdServicesClient.ServiceSpec.minecraft("Phoenix $key Pairing", "phoenix-$key-pair.$cluster",
            NetBirdServicesClient.Mode.TCP, config.getString("peerId"), localPort)
        var service = api.createService(spec)
        if (service.listenPort() == 0) service = api.getService(service.id())
        check(service.listenPort() > 0) { "NetBird did not assign a pairing port. Try again in a minute." }
        val code = ProxyPairing.Code(cluster, service.listenPort(), ports, ProxyPairing.newSecret(java.security.SecureRandom()))
        val expiresAt = System.currentTimeMillis() + PAIRING_WINDOW_MS
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val token = try { api.createProxyToken("${NetBirdByop.TOKEN_NAME_PREFIX}$key-$stamp", 0) }
            catch (failure: Exception) { deleteQuietly(api, service.id()); throw failure }
        byop.put("pairing", JSONObject().put("code", code.encode()).put("serviceId", service.id())
                .put("tokenId", token.id()).put("token", token.plainToken())
                .put("localPort", localPort).put("expiresAt", expiresAt).put("failures", 0))
            .put("stage", "PAIRING")
            .put("detail", "Waiting for your Oracle proxy to pair. Keep Phoenix open with NetBird on.")
        store.saveConfiguration(config.put("byop", byop))
        regionalProxyOf(byop)
        code.encode()
    }

    /** The pairing Phoenix is waiting on, or null. */
    fun pendingPairing(): PendingPairing? = store.locked {
        val pairing = store.configuration().optJSONObject("byop")?.optJSONObject("pairing") ?: return@locked null
        if (pairing.optLong("expiresAt") <= System.currentTimeMillis()) return@locked null
        PendingPairing(ProxyPairing.Code.parse(pairing.getString("code")), pairing.getInt("localPort"), pairing.getLong("expiresAt"))
    }

    /**
     * Called by the pairing listener once the VM has proven the pairing code: hands over the pairing's proxy token. If
     * the VM then fails to store it, the token stays with the pairing for the VM's next attempt.
     */
    fun issueProxyToken(): ProxyPairing.Issued = store.locked {
        val pairing = store.configuration().optJSONObject("byop")?.optJSONObject("pairing") ?: error("No pairing is open")
        ProxyPairing.Issued(pairing.getString("token"), NetBirdServicesClient.DEFAULT_BASE_URI.toString()) {}
    }

    /** The VM stored its token: closes the pairing service and remembers the VM. Its proxy registers within minutes. */
    fun finishPairing(vm: ProxyPairing.VmInfo): RegionalProxy = store.locked {
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: JSONObject()
        val pairing = byop.optJSONObject("pairing")
        pairing?.optString("serviceId")?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { deleteQuietly(client(NetBirdOAuthSession.credential(app)), id) }
                .onFailure { byop.put("staleServiceId", id) } // Removed at the next signed-in check.
        }
        pairing?.optString("tokenId")?.takeIf { it.isNotBlank() }?.let { byop.put("tokenId", it) }
        byop.remove("pairing")
        byop.put("ip", vm.ip()).put("apex", NetBirdByop.apexFor(vm.ip())).put("region", vm.region()).put("shape", vm.shape())
            .put("arch", vm.arch()).put("pairedAt", System.currentTimeMillis())
            .put("stage", "WAITING_FOR_PROXY")
            .put("detail", "Paired with your proxy in ${vm.region().ifBlank { "Oracle Cloud" }}. It starts and connects within about 3 minutes.")
        store.saveConfiguration(config.put("byop", byop))
        regionalProxyOf(byop)
    }

    /** A connection failed to prove the code. After [MAX_PAIRING_FAILURES] the pairing is closed for safety. */
    fun pairingRejected(): Boolean = store.locked {
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: return@locked false
        val pairing = byop.optJSONObject("pairing") ?: return@locked false
        val failures = pairing.optInt("failures") + 1
        pairing.put("failures", failures)
        store.saveConfiguration(config.put("byop", byop))
        if (failures >= MAX_PAIRING_FAILURES) {
            cancelRegionalPairing("Pairing closed after repeated wrong codes. Start again for a new code.")
            true
        } else false
    }

    fun cancelRegionalPairing(detail: String = "Pairing cancelled."): RegionalProxy = store.locked {
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: JSONObject()
        byop.optJSONObject("pairing")?.let { pairing ->
            val api = client(NetBirdOAuthSession.credential(app)) // Cancelling needs a session: the token must be revoked.
            pairing.optString("serviceId").takeIf { it.isNotBlank() }?.let { deleteQuietly(api, it) }
            pairing.optString("tokenId").takeIf { it.isNotBlank() }?.let { revokeQuietly(api, it) }
        }
        byop.remove("pairing")
        if (byop.optString("stage") == "PAIRING") byop.put("stage", "NOT_SET_UP").put("detail", detail)
        store.saveConfiguration(config.put("byop", byop))
        regionalProxyOf(byop)
    }

    /**
     * One explicit check: finds Phoenix's online regional proxy cluster, creates or migrates the Java, Bedrock and voice
     * services onto it at the server's real ports, verifies they are active, hides Phoenix's old offline clusters and
     * revokes a rotated token once its replacement is online. The shared EU services are never touched.
     */
    fun checkRegionalProxy(ports: NetBirdByop.Ports): RegionalProxy = store.locked {
        val credential = NetBirdOAuthSession.credential(app)
        val api = client(credential)
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: JSONObject()
        val clusters = api.listClusters()
        val cluster = NetBirdByop.selectCluster(clusters, byop.optString("apex").ifBlank { null })
        val now = System.currentTimeMillis()
        val stage = byop.optString("stage", "NOT_SET_UP")
        byop.put("checkedAt", now)
        byop.optString("staleServiceId").takeIf { it.isNotBlank() }?.let { deleteQuietly(api, it); byop.remove("staleServiceId") }
        when {
            cluster == null && stage == "PAIRING" -> Unit
            cluster == null -> byop.put("stage", if (byop.optString("apex").isNotBlank()) "WAITING_FOR_PROXY" else "NOT_SET_UP")
                .put("detail", if (byop.optString("apex").isNotBlank())
                    "Paired; the proxy has not connected to NetBird yet. It usually takes about 3 minutes after pairing."
                else "NetBird account checked at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now)) +
                    ": its proxy API answers (${clusters.size} cluster${if (clusters.size == 1) "" else "s"} visible), no regional proxy yet.")
            !cluster.online() -> byop.put("stage", "PROXY_OFFLINE").put("ip", NetBirdByop.ipv4Of(cluster.address())).put("apex", cluster.address())
                .put("detail", "The proxy is offline. Oracle may have stopped the VM; start it in the Oracle console.")
            else -> {
                val tunnel = transport.snapshot()
                val peerId = config.optString("peerId").ifBlank {
                    check(tunnel.connected && !tunnel.peerIp.isNullOrBlank()) { "Connect this phone to NetBird before setting up the regional proxy" }
                    NetBirdPeerClient(UrlConnectionNetBirdTransport(), credential.token, credential.scheme).resolvePeer(tunnel.peerIp!!).getString("id")
                }
                val key = config.optString("allocationKey").ifBlank { error("Set up Easy online play first") }
                val outcome = NetBirdByop.reconcile(api, key, NetBirdByop.plan(key, peerId, cluster.address(), ports), cluster.address(), true)
                val previousApex = byop.optString("apex")
                if (previousApex.isNotBlank() && previousApex != cluster.address()) {
                    byop.optString("previousTokenId").takeIf { it.isNotBlank() }?.let { revokeQuietly(api, it) }
                    byop.remove("previousTokenId")
                }
                clusters.filter { it.isAccount && !it.online() && it.address() != cluster.address() && NetBirdByop.ipv4Of(it.address()) != null }
                    .forEach { stale -> runCatching { api.deleteCluster(stale.address()) } }
                val forwards = outcome.services().joinToString(" · ") { "${it.name().substringAfter(" Regional ")} ${it.mode()?.wireValue()?.uppercase()} ${it.listenPort()}" }
                byop.put("apex", cluster.address()).put("ip", NetBirdByop.ipv4Of(cluster.address())).put("peerId", peerId)
                    .put("forwards", forwards)
                    .put("stage", if (outcome.allActive()) "READY" else "PROXY_ONLINE")
                    .put("detail", if (outcome.allActive()) "Proxy online · $forwards"
                        else "Proxy online; NetBird is still starting: " + outcome.services().joinToString { "${it.name().substringAfter(" Regional ")} ${it.status() ?: "pending"}" })
            }
        }
        store.saveConfiguration(config.put("byop", byop))
        regionalProxyOf(byop)
    }

    /**
     * Turns the regional proxy off in NetBird: deletes Phoenix's regional services, revokes its proxy tokens and hides
     * its clusters. The shared EU services stay. The Oracle VM itself is deleted by the owner (Destroy in Resource Manager).
     */
    fun removeRegionalProxy(): RegionalProxy = store.locked {
        val api = client(NetBirdOAuthSession.credential(app))
        val config = store.configuration()
        val byop = config.optJSONObject("byop") ?: JSONObject()
        byop.optJSONObject("pairing")?.let { pairing ->
            pairing.optString("serviceId").takeIf { it.isNotBlank() }?.let { deleteQuietly(api, it) }
            pairing.optString("tokenId").takeIf { it.isNotBlank() }?.let { revokeQuietly(api, it) }
        }
        val key = config.optString("allocationKey")
        if (key.isNotBlank()) api.listServices().filter { it.name().startsWith("Phoenix $key Regional ") || it.name() == "Phoenix $key Pairing" }
            .forEach { deleteQuietly(api, it.id()) }
        listOf(byop.optString("tokenId"), byop.optString("previousTokenId")).filter { it.isNotBlank() }.forEach { revokeQuietly(api, it) }
        // Tokens left by an earlier install of Phoenix on this account carry the same prefix.
        if (key.isNotBlank()) api.listProxyTokens().filter { !it.revoked() && it.name().startsWith("${NetBirdByop.TOKEN_NAME_PREFIX}$key-") }
            .forEach { revokeQuietly(api, it.id()) }
        api.listClusters().filter { it.isAccount && NetBirdByop.ipv4Of(it.address()) != null }.forEach { runCatching { api.deleteCluster(it.address()) } }
        val cleared = JSONObject().put("stage", "NOT_SET_UP")
            .put("detail", "Regional proxy removed from NetBird. Delete its stack in Oracle Resource Manager (Destroy) to remove the VM.")
        store.saveConfiguration(config.put("byop", cleared))
        regionalProxyOf(cleared)
    }

    // ------------------------------------------------------------ Simple Voice Chat on the shared EU cluster

    /** Public UDP port of the EU voice service, or 0 when there is none. Local read. */
    fun euVoicePort(): Int = runCatching { store.peekDerived("voice") { _, config -> config.optInt("euVoicePort") } }.getOrNull() ?: 0

    /**
     * Creates or updates the UDP service that forwards Simple Voice Chat through NetBird's shared cluster, next to the
     * Java and Bedrock services, or deletes it when voice chat is gone ([voicePort] 0). Returns the public port.
     */
    fun ensureEuVoice(voicePort: Int): Int = store.locked {
        if (store.load().phase() != NetBirdProvisioningCoordinator.Phase.READY) return@locked 0
        val config = store.configuration()
        val key = config.getString("allocationKey")
        val api = client(NetBirdOAuthSession.credential(app))
        val name = "Phoenix $key Voice"
        val existing = api.listServices().filter { it.name() == name }
        check(existing.size <= 1) { "More than one Phoenix voice service exists; remove the extra one in the NetBird dashboard" }
        val current = existing.firstOrNull()
        if (voicePort == 0) {
            current?.let { deleteQuietly(api, it.id()) }
            config.put("euVoicePort", 0).remove("euVoiceServiceId")
            store.saveConfiguration(config)
            return@locked 0
        }
        val spec = NetBirdServicesClient.ServiceSpec.minecraft(name, "phoenix-$key-voice.${config.getString("cluster")}",
            NetBirdServicesClient.Mode.UDP, config.getString("peerId"), voicePort)
        var service = when {
            current == null -> api.createService(spec)
            current.targets().firstOrNull()?.port() != voicePort -> api.updateService(current.id(), spec)
            else -> current
        }
        if (service.listenPort() == 0) service = api.getService(service.id())
        store.saveConfiguration(config.put("euVoicePort", service.listenPort()).put("euVoiceServiceId", service.id()))
        service.listenPort()
    }

    // ------------------------------------------------------------ The custom name's front door (Playit + NetBird + name)

    /** Public TCP port of the front-door service on the shared cluster, or 0 when there is none. Local read. */
    fun euNamePort(): Int = runCatching { store.peekDerived("name-door") { _, config -> config.optInt("euNamePort") } }.getOrNull() ?: 0

    /**
     * Creates or updates the TCP service that forwards the custom name's Java traffic to Phoenix's front door on
     * [localPort], or deletes it ([localPort] 0). NetBird's proxy doesn't check the name a client sends, which Playit Free
     * does. Returns the public port.
     */
    fun ensureEuName(localPort: Int): Int = store.locked {
        if (store.load().phase() != NetBirdProvisioningCoordinator.Phase.READY) return@locked 0
        val config = store.configuration()
        val key = config.getString("allocationKey")
        val api = client(NetBirdOAuthSession.credential(app))
        val name = "Phoenix $key Name"
        val existing = api.listServices().filter { it.name() == name }
        check(existing.size <= 1) { "More than one Phoenix name service exists; remove the extra one in the NetBird dashboard" }
        val current = existing.firstOrNull()
        if (localPort == 0) {
            current?.let { deleteQuietly(api, it.id()) }
            config.put("euNamePort", 0).remove("euNameServiceId")
            store.saveConfiguration(config)
            return@locked 0
        }
        val spec = NetBirdServicesClient.ServiceSpec.minecraft(name, "phoenix-$key-name.${config.getString("cluster")}",
            NetBirdServicesClient.Mode.TCP, config.getString("peerId"), localPort)
        var service = when {
            current == null -> api.createService(spec)
            current.targets().firstOrNull()?.port() != localPort -> api.updateService(current.id(), spec)
            else -> current
        }
        if (service.listenPort() == 0) service = api.getService(service.id())
        store.saveConfiguration(config.put("euNamePort", service.listenPort()).put("euNameServiceId", service.id()))
        service.listenPort()
    }

    private fun deleteQuietly(api: NetBirdServicesClient, serviceId: String) {
        try { api.deleteService(serviceId) }
        catch (gone: NetBirdApiException) { if (gone.code() != NetBirdApiException.Code.NOT_FOUND) throw gone }
    }

    private fun revokeQuietly(api: NetBirdServicesClient, tokenId: String) {
        try { api.revokeProxyToken(tokenId) }
        catch (gone: NetBirdApiException) {
            if (gone.code() != NetBirdApiException.Code.NOT_FOUND) throw gone
        }
    }
    private fun operation(action: () -> Unit): Snapshot {
        synchronized(this) {
            check(!busy) { "NetBird operation already in progress" }
            busy = true
            phase="PROVISIONING"
            detail="Configuring permanent Java TCP and Bedrock UDP services. Existing allocations are reused."
        }
        try { action() }
        catch (failure: Exception) {
            if(failure is org.phoenix.core.network.netbird.NetBirdApiException && failure.code()==org.phoenix.core.network.netbird.NetBirdApiException.Code.RATE_LIMITED) {
                store.locked { val config=store.configuration(); store.saveConfiguration(config.put("peerRetryUntil",failure.retryAfterEpochMillis() ?: (System.currentTimeMillis()+60000))) }
            }
            phase = "RECOVERY_REQUIRED"; detail = failure.message ?: "NetBird setup requires recovery"; throw failure
        }
        finally { busy = false }
        return snapshot()
    }
}
