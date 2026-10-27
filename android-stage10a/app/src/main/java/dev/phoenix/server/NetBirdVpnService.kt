package dev.phoenix.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import android.net.Uri
import io.netbird.gomobile.android.Auth
import io.netbird.gomobile.android.Client
import io.netbird.gomobile.android.DNSList
import io.netbird.gomobile.android.DnsReadyListener
import io.netbird.gomobile.android.EnvList
import io.netbird.gomobile.android.IFaceDiscover
import io.netbird.gomobile.android.NetworkChangeListener
import io.netbird.gomobile.android.OAuthSessionListener
import io.netbird.gomobile.android.PlatformFiles
import io.netbird.gomobile.android.TunAdapter
import io.netbird.gomobile.android.URLOpener
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Android's single VpnService TUN and the embedded NetBird v0.80 client. */
class NetBirdVpnService : VpnService() {
    private val appFiles by lazy { AndroidPlatformFiles(this) }
    private val tun by lazy { AndroidTunAdapter(this) }
    private val runner: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "phoenix-netbird-client") }
    private val authRunner: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "phoenix-netbird-auth") }
    private val tunEvents: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "phoenix-netbird-tun") }
    private val mainHandler = Handler(Looper.getMainLooper())
    // The Go client's status() is a JNI call; polling it on the main thread could stall the UI during reconnects.
    private val statusThread = HandlerThread("phoenix-netbird-status").apply { start() }
    private val statusHandler = Handler(statusThread.looper)
    private val running = AtomicBoolean(false)
    private val authorizationRunning = AtomicBoolean(false)
    private val goPeerConnected = AtomicBoolean(false)
    private val setupContinuationSent = AtomicBoolean(false)
    @Volatile private var client: Client? = null
    @Volatile private var closing = false
    /** elapsedRealtime when the current client started, for the "Connecting… Ns" line. */
    @Volatile private var connectingSince = 0L
    private val physicalNetworks = ConcurrentHashMap.newKeySet<Network>()
    private lateinit var connectivity: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var notifiedNetworks: Set<Network> = emptySet()
    @Volatile private var notifiedHostDns: List<String> = emptyList()

    private val statusRefresh = object : Runnable {
        override fun run() {
            if (running.get()) {
                val status = runCatching { client?.status().orEmpty() }.getOrDefault("Connecting")
                observeGoStatus(status)
                publishLink(describeGoStatus(status), live = true)
                // Quick while connecting, relaxed once connected; status stays local to the Go client.
                statusHandler.postDelayed(this, statusPollDelayMs(status))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerConnectivity()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android restarts a sticky service with no intent; that must not undo the owner's switch-off.
        val action = intent?.action ?: if (NetBirdTransport.ownerTurnedOff(this)) ACTION_DISCONNECT else ACTION_RECONNECT
        if (action == ACTION_DISCONNECT) {
            closing = true
            publishLink("Disconnected. Phone identity and permanent services are saved.", live = false)
            client?.stop()
            statusHandler.removeCallbacks(statusRefresh)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!NetBirdTransport.supportsBundledClient()) {
            publishLink("Unsupported Android CPU architecture for the bundled NetBird client.", live = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (VpnService.prepare(this) != null && action != ACTION_AUTH_ONLY) {
            publishLink("Allow the VPN request to connect this phone.", live = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startAsForeground(action)
        when (action) {
            ACTION_AUTH_ONLY -> authorizeManagementSession()
            ACTION_CONNECT -> startPeer(interactive = true, authorizeManagement = true)
            else -> startPeer(interactive = false, authorizeManagement = false)
        }
        return START_STICKY
    }

    private fun startPeer(interactive: Boolean, authorizeManagement: Boolean) {
        if (!running.compareAndSet(false, true)) {
            if (authorizeManagement && !NetBirdOAuthSession.hasSession(this)) beginManagementAuthorization(BrowserOpener(this) {})
            else if (authorizeManagement) {
                val status = runCatching { client?.status().orEmpty() }.getOrDefault("")
                if (status == "Connected") {
                    // MainActivity has just persisted a fresh setup request while the peer was
                    // already online, so treat this explicit Connect action as the ready edge.
                    setupContinuationSent.set(false)
                    observeGoStatus(status)
                    continuePendingSetupIfReady()
                }
            }
            return
        }
        closing = false
        val store = NetBirdStore(this)
        val enrolled = runCatching { store.configuration().optBoolean("identityEnrolled") }.getOrDefault(false)
        if (!interactive && !enrolled) {
            running.set(false)
            publishLink("This phone isn't set up yet. Tap Set up online play.", live = false)
            stopSelf()
            return
        }
        if (interactive && !enrolled) authorizationRunning.set(true)

        val engine = Client(
            Build.VERSION.SDK_INT.toLong(),
            Build.MODEL?.take(80)?.ifBlank { "Android phone" } ?: "Android phone",
            "Phoenix/0.4",
            tun,
            AndroidInterfaceDiscover(),
            AndroidNetworkListener(),
        )
        engine.setOAuthSessionListener(object : OAuthSessionListener {
            override fun onOAuthSession(
                accessToken: String,
                refreshToken: String,
                selectedToken: String,
                expiresIn: Long,
                tokenEndpoint: String,
                clientID: String,
                useIDToken: Boolean,
            ) {
                // Persist through Android Keystore-backed storage; never log or broadcast tokens.
                val saved = runCatching {
                    NetBirdOAuthSession.save(
                        applicationContext, accessToken, refreshToken, expiresIn, tokenEndpoint, clientID,
                        selectedToken = selectedToken, useIdToken = useIDToken,
                    )
                }
                authorizationRunning.set(false)
                saved.onSuccess { publishAccount("NetBird account authorized on this phone.") }
                saved.onFailure {
                    publishAccount("NetBird account authorization could not be saved securely. Retry from this screen.")
                }
                runCatching {
                    val current = store.configuration()
                    store.saveConfiguration(current.put("identityEnrolled", true))
                }
                if (saved.isSuccess) onOAuthSessionSaved()
            }

            override fun onOAuthSessionError(error: Exception) {
                authorizationRunning.set(false)
                publishAccount("NetBird browser sign-in was not completed (the sign-in page expires after 5 minutes). Tap Authorize and connect this phone for a fresh sign-in page.")
            }
        })
        client = engine
        val interactiveOpen = BrowserOpener(this) {
            authorizationRunning.set(false)
            val current = store.configuration()
            store.saveConfiguration(current.put("identityEnrolled", true))
        }
        val dns = hostDnsServers().also { notifiedHostDns = it }.let(::dnsList)
        val env = EnvList()

        if (interactive && authorizeManagement && enrolled && !NetBirdOAuthSession.hasSession(this)) {
            // A saved peer can reconnect without a browser. Management access needs its own
            // supported SSO session, so obtain it only after a user explicitly opens Connect.
            beginManagementAuthorization(interactiveOpen)
        }

        running.set(true)
        connectingSince = android.os.SystemClock.elapsedRealtime()
        publishLink(if (interactive && !enrolled) "Waiting for NetBird browser sign-in to register this phone…" else "Connecting this phone with its saved NetBird identity…", live = true)
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
        runner.execute {
            try {
                if (interactive && !enrolled) {
                    engine.run(appFiles, interactiveOpen, false, dns, EmptyDnsReadyListener(), env)
                } else {
                    engine.runWithoutLogin(appFiles, dns, EmptyDnsReadyListener(), env)
                }
                if (!closing) publishLink("NetBird stopped. Tap Set up online play to reconnect.", live = false)
            } catch (_: Throwable) {
                if (!closing) publishLink("NetBird couldn't start. Tap Set up online play to retry.", live = false)
            } finally {
                if (client === engine) client = null
                running.set(false)
                authorizationRunning.set(false)
                statusHandler.removeCallbacks(statusRefresh)
                if (!closing) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun authorizeManagementSession(opener: BrowserOpener = BrowserOpener(this) {}) {
        val configFile = File(appFiles.configurationFilePath())
        if (!configFile.isFile) {
            publishAccount("Connect this phone first so NetBird can create its device identity.")
            finishAuthOnlyIfIdle()
            return
        }
        beginManagementAuthorization(opener)
    }

    private fun beginManagementAuthorization(opener: BrowserOpener) {
        if (!authorizationRunning.compareAndSet(false, true)) {
            publishAccount("NetBird sign-in is already open in the browser. Finish it there, then return to Phoenix.")
            return
        }
        publishAccount("Waiting for NetBird sign-in in the browser…")
        authRunner.execute { acquireOAuthSession(opener) }
    }

    private fun acquireOAuthSession(opener: BrowserOpener) {
        try {
            val auth = Auth(appFiles.configurationFilePath(), "", null)
            auth.acquireOAuthSession(object : OAuthSessionListener {
                override fun onOAuthSession(
                    accessToken: String,
                    refreshToken: String,
                    selectedToken: String,
                    expiresIn: Long,
                    tokenEndpoint: String,
                    clientID: String,
                    useIDToken: Boolean,
                ) {
                    val saved = runCatching {
                        NetBirdOAuthSession.save(
                            applicationContext, accessToken, refreshToken, expiresIn, tokenEndpoint, clientID,
                            selectedToken = selectedToken, useIdToken = useIDToken,
                        )
                    }
                    authorizationRunning.set(false)
                    saved.onSuccess {
                        publishAccount("NetBird account authorized on this phone.")
                    }.onFailure {
                        publishAccount("NetBird returned an unsupported account session. Retry browser sign-in.")
                    }
                    if (saved.isSuccess) onOAuthSessionSaved()
                    finishAuthOnlyIfIdle()
                }

                override fun onOAuthSessionError(error: Exception) {
                    authorizationRunning.set(false)
                    publishAccount("NetBird browser sign-in was not completed (the sign-in page expires after 5 minutes). Tap Authorize and connect this phone for a fresh sign-in page.")
                    finishAuthOnlyIfIdle()
                }
            }, opener, false)
        } catch (_: Throwable) {
            authorizationRunning.set(false)
            publishAccount("NetBird could not open browser sign-in. Reopen this screen and retry.")
            finishAuthOnlyIfIdle()
        }
    }

    private fun finishAuthOnlyIfIdle() {
        if (!running.get()) mainHandler.postDelayed({
            if (!running.get()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, 1800)
    }

    private fun registerConnectivity() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { physicalNetworks.add(network); networkChanged() }
            override fun onLost(network: Network) { physicalNetworks.remove(network); networkChanged() }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network in physicalNetworks) refreshHostDns()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) physicalNetworks.add(network)
                else physicalNetworks.remove(network)
                networkChanged()
            }
        }
        networkCallback = callback
        runCatching {
            connectivity.allNetworks.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) physicalNetworks.add(network)
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerNetworkCallback(request, callback)
        }
        notifiedNetworks = physicalNetworks.toSet()
    }

    private fun networkChanged() {
        val current = physicalNetworks.toSet()
        val switched = current != notifiedNetworks
        notifiedNetworks = current
        // Only a real network switch marks NetBird's connections stale. Capability updates
        // (signal strength, bandwidth) arrive every few seconds and would drop live game relays.
        if (switched) client?.let { engine ->
            runCatching {
                engine.setNetworkAvailable(current.isNotEmpty())
                engine.notifyNetworkChange()
            }
        }
        refreshHostDns()
        updateUnderlyingNetworks()
    }

    /** Hands NetBird the new upstream resolvers only when the underlying DNS servers change. */
    private fun refreshHostDns() {
        val servers = hostDnsServers()
        if (servers == notifiedHostDns) return
        notifiedHostDns = servers
        client?.let { engine -> runCatching { engine.onUpdatedHostDNS(dnsList(servers)) } }
    }

    /**
     * Upstream resolvers for every name outside the NetBird zone. The Go client answers all DNS on
     * the tunnel, so an empty list would leave the whole phone without DNS while the VPN is up.
     */
    private fun hostDnsServers(): List<String> = physicalNetworks.flatMap { network ->
        runCatching { connectivity.getLinkProperties(network)?.dnsServers.orEmpty() }.getOrDefault(emptyList())
    }.filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().sorted()

    private fun dnsList(servers: List<String>): DNSList =
        DNSList().also { list -> servers.forEach { server -> runCatching { list.add(server) } } }

    private fun updateUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val activeNetworks = physicalNetworks.toTypedArray()
            runCatching { setUnderlyingNetworks(activeNetworks) }
        }
    }

    /**
     * Records the phone-link state. [live] marks text that describes a running client; snapshot()
     * ignores it once no client is running, so a killed process can never leave "Connected" behind.
     */
    private fun publishLink(detail: String, live: Boolean) {
        val status = detail.take(240)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val peerIp = tun.peerIp ?: prefs.getString(KEY_PEER_IP, null)
        if (prefs.getString(KEY_LINK, null) == status && prefs.getBoolean(KEY_LINK_LIVE, false) == live &&
            prefs.getString(KEY_PEER_IP, null) == peerIp) return
        prefs.edit().putString(KEY_LINK, status).putBoolean(KEY_LINK_LIVE, live)
            .putString(KEY_PEER_IP, peerIp.orEmpty()).remove("detail").remove("connected").apply()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        if (running.get()) updateNotification(status)
    }

    /** Account (browser SSO) events are kept apart from link status so polling cannot overwrite them. */
    private fun publishAccount(detail: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACCOUNT, detail.take(240)).apply()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    /** Status polling is local to the Go client. Cloud setup starts only on a peer/session edge. */
    private fun observeGoStatus(status: String) {
        val connected = status == "Connected"
        val wasConnected = goPeerConnected.getAndSet(connected)
        if (!connected) {
            setupContinuationSent.set(false)
            return
        }
        if (!wasConnected) continuePendingSetupIfReady()
    }

    /** An account-only SSO can finish after the peer is already live. */
    private fun onOAuthSessionSaved() {
        val connected = runCatching { client?.status() == "Connected" }.getOrDefault(false)
        goPeerConnected.set(connected)
        if (connected) continuePendingSetupIfReady()
        runCatching { PhoenixBackend.get(applicationContext).onNetBirdSignedIn() }
        // NetBird's sign-in page ends on its own "Login Successful" page; bring Phoenix back instead of leaving the user
        // there. Android may refuse this from the background on some versions, which only leaves the browser in front.
        runCatching {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    private fun continuePendingSetupIfReady() {
        if (!running.get() || !goPeerConnected.get() || !NetBirdOAuthSession.hasSession(this)) return
        if (!setupContinuationSent.compareAndSet(false, true)) return
        runCatching { PhoenixBackend.get(applicationContext).continueNetBirdSetup() }
            .onFailure { setupContinuationSent.set(false) }
    }

    private fun startAsForeground(action: String) {
        val notification = notification(if (action == ACTION_AUTH_ONLY) "Waiting for NetBird sign-in in the browser…" else "Connecting this phone to NetBird…")
        if (Build.VERSION.SDK_INT >= 34) {
            val type = if (action == ACTION_AUTH_ONLY) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(detail: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail))
    }

    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, NetBirdTransport.launchIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        val stop = PendingIntent.getService(this, 1,
            Intent(this, NetBirdVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NetBird Cloud is active")
            .setContentText(detail.take(90))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NetBird phone connection", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        closing = true
        statusHandler.removeCallbacks(statusRefresh)
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        client?.stop()
        publishLink("Disconnected. Phone identity and permanent services are saved.", live = false)
        if (activeInstance === this) activeInstance = null
        runner.shutdownNow()
        authRunner.shutdownNow()
        tunEvents.shutdownNow()
        statusThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        closing = true
        running.set(false)
        goPeerConnected.set(false)
        setupContinuationSent.set(false)
        authorizationRunning.set(false)
        client?.stop()
        publishLink("Android revoked the VPN permission. Phone identity and permanent services are saved.", live = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    private inner class AndroidNetworkListener : NetworkChangeListener {
        override fun onNetworkChanged(routes: String) {
            // NetBird calls this synchronously while publishing routes. Defer pulling settings so
            // it cannot re-enter the route manager while its update lock is held.
            if (!tunEvents.isShutdown) tunEvents.execute {
                runCatching {
                    val settings = client?.getTunSettings() ?: return@runCatching
                    val fd = tun.rebuild(settings.getRoutes(), settings.getSearchDomains())
                    client?.renewTun(fd.toLong())
                }.onFailure { publishLink("NetBird route update failed; reconnect the phone tunnel.", live = false) }
            }
        }
        override fun setInterfaceIP(ip: String) { tun.updateAddr(ip) }
        override fun setInterfaceIPv6(ip: String) { tun.updateAddr(ip) }
    }

    private class EmptyDnsReadyListener : DnsReadyListener { override fun onReady() {} }

    private class AndroidPlatformFiles(context: Context) : PlatformFiles {
        private val root = File(context.noBackupFilesDir, "netbird-client").apply { mkdirs() }
        private val cache = File(context.cacheDir, "netbird-client").apply { mkdirs() }
        private val wireGuardSocketDir = File(context.cacheDir, "wireguard").apply {
            check(isDirectory || mkdirs()) { "Could not create the NetBird WireGuard socket directory" }
        }
        private val config = File(root, "config.json")
        private val state = File(root, "state.json")
        override fun configurationFilePath(): String = config.absolutePath
        override fun stateFilePath(): String = state.absolutePath
        override fun cacheDir(): String = cache.absolutePath
    }

    private class BrowserOpener(private val context: Context, private val onLogin: () -> Unit) : URLOpener {
        override fun open(url: String, userCode: String) {
            val uri = Uri.parse(url)
            check(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "NetBird supplied an invalid browser authorization URL"
            }
            val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
        }
        override fun onLoginSuccess() {
            runCatching(onLogin)
            context.sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(context.packageName))
        }
    }

    private class AndroidInterfaceDiscover : IFaceDiscover {
        override fun iFaces(): String {
            val lines = mutableListOf<String>()
            val all: Enumeration<JavaNetworkInterface> = JavaNetworkInterface.getNetworkInterfaces() ?: return ""
            for (iface in Collections.list(all)) {
                val name = iface.name ?: continue
                if (!iface.isUp || iface.isLoopback || name.startsWith("tun") || name.startsWith("wg")) continue
                val addresses = iface.interfaceAddresses.mapNotNull(::formatAddress).distinct()
                if (addresses.isEmpty()) continue
                val broadcast = iface.interfaceAddresses.any { it.broadcast != null }
                lines += listOf(name, iface.index.toString(), iface.mtu.coerceAtLeast(0).toString(),
                    iface.isUp.toString(), broadcast.toString(), iface.isLoopback.toString(),
                    iface.isPointToPoint.toString(), iface.supportsMulticast().toString()).joinToString(" ") + " | " + addresses.joinToString(" ")
            }
            return lines.joinToString("\n")
        }

        private fun formatAddress(address: InterfaceAddress): String? {
            val ip = address.address ?: return null
            if (ip.isLoopbackAddress || ip.isLinkLocalAddress) return null
            return "${ip.hostAddress.substringBefore('%')}/${address.networkPrefixLength}"
        }
    }

    private class AndroidTunAdapter(private val service: NetBirdVpnService) : TunAdapter {
        @Volatile private var address = ""
        @Volatile private var addressV6 = ""
        @Volatile private var mtu = 1280
        @Volatile private var dns = ""
        @Volatile private var searchDomains = ""
        @Volatile private var routes = ""
        @Volatile var peerIp: String? = null
            private set

        override fun configureInterface(address: String, addressV6: String, mtu: Long, dns: String, searchDomains: String, routes: String): Long {
            this.address = address
            this.addressV6 = addressV6
            this.mtu = mtu.toInt().coerceIn(576, 9000)
            this.dns = dns
            this.searchDomains = searchDomains
            this.routes = routes
            peerIp = address.substringBefore('/').takeIf { it.contains('.') }
            return establishTun(routes, searchDomains).toLong()
        }

        fun rebuild(newRoutes: String, newSearchDomains: String): Int {
            routes = newRoutes
            searchDomains = newSearchDomains
            return establishTun(newRoutes, newSearchDomains)
        }

        override fun updateAddr(address: String) {
            this.address = address
            peerIp = address.substringBefore('/').takeIf { it.contains('.') }
        }

        override fun protectSocket(fd: Int): Boolean = service.protect(fd)

        private fun establishTun(routes: String, domains: String): Int {
            val builder = service.Builder().setSession("NetBird").setMtu(mtu)
                .setConfigureIntent(PendingIntent.getActivity(service, 2, NetBirdTransport.launchIntent(service), PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
            addAddress(builder, address)
            addAddress(builder, addressV6)
            split(dns).forEach { server -> builder.addDnsServer(parseAddress(server)) }
            split(domains).forEach { domain -> if (domain.length <= 253 && !domain.contains('/')) builder.addSearchDomain(domain) }
            split(routes).forEach { route -> addRoute(builder, route) }
            val descriptor: ParcelFileDescriptor = builder.establish() ?: error("Android did not establish the NetBird VPN interface")
            return descriptor.detachFd()
        }

        private fun addAddress(builder: Builder, value: String) {
            val prefix = parsePrefix(value) ?: return
            builder.addAddress(prefix.first, prefix.second)
        }

        private fun addRoute(builder: Builder, value: String) {
            val prefix = parsePrefix(value) ?: return
            builder.addRoute(prefix.first, prefix.second)
        }

        private fun parsePrefix(value: String): Pair<InetAddress, Int>? {
            if (value.isBlank()) return null
            val parts = value.split('/', limit = 2)
            require(parts.size == 2)
            val ip = parseAddress(parts[0])
            val bits = if (ip is Inet4Address) 32 else 128
            val prefix = parts[1].toInt()
            require(prefix in 0..bits)
            return ip to prefix
        }

        private fun parseAddress(value: String): InetAddress {
            require(value.matches(Regex("[0-9a-fA-F:.]+"))) { "VPN interface received a non-numeric address" }
            return InetAddress.getByName(value)
        }

        private fun split(value: String): List<String> = value.split(';').map(String::trim).filter(String::isNotBlank)
    }

    companion object {
        const val ACTION_CONNECT = "dev.phoenix.server.netbird.CONNECT"
        const val ACTION_RECONNECT = "dev.phoenix.server.netbird.RECONNECT"
        const val ACTION_AUTH_ONLY = "dev.phoenix.server.netbird.AUTH_ONLY"
        const val ACTION_DISCONNECT = "dev.phoenix.server.netbird.DISCONNECT"
        const val ACTION_STATE_CHANGED = "dev.phoenix.server.netbird.STATE_CHANGED"
        private const val PREFS = "netbird_transport_state"
        private const val KEY_LINK = "linkDetail"
        private const val KEY_LINK_LIVE = "linkLive"
        private const val KEY_ACCOUNT = "accountDetail"
        private const val KEY_PEER_IP = "peerIp"
        const val NOT_CONNECTED = "Not connected. Reconnects when the server starts."
        private const val CHANNEL_ID = "netbird_vpn"
        private const val NOTIFICATION_ID = 7403
        @Volatile private var activeInstance: NetBirdVpnService? = null

        /** Live state comes from the running client only; saved text never claims a live link. */
        fun snapshot(context: Context): NetBirdTransport.Snapshot {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val service = activeInstance
            val live = service?.running?.get() == true
            val goStatus = if (live) runCatching { service?.client?.status().orEmpty() }.getOrDefault("") else ""
            val connected = live && goStatus == "Connected"
            val authorizing = service?.authorizationRunning?.get() == true
            val saved = prefs.getString(KEY_LINK, null)?.takeIf(String::isNotBlank)
            val detail = when {
                live && goStatus in setOf("", "Connecting", "Idle") -> {
                    // NetBird's first connection can take 30 s or more; show that it is still working.
                    val seconds = service?.connectingSince?.takeIf { it > 0L }?.let { (android.os.SystemClock.elapsedRealtime() - it) / 1000 }
                    describeGoStatus(goStatus.ifBlank { "Connecting" }) + (seconds?.takeIf { it >= 3 }?.let { " ${it}s" }.orEmpty())
                }
                live -> describeGoStatus(goStatus.ifBlank { "Connecting" })
                saved == null || prefs.getBoolean(KEY_LINK_LIVE, false) -> NOT_CONNECTED
                else -> saved
            }
            return NetBirdTransport.Snapshot(connected, detail,
                prefs.getString(KEY_PEER_IP, null)?.takeIf(String::isNotBlank),
                // Only transitional states show progress; NeedsLogin/LoginFailed wait for the user.
                busy = authorizing || (live && goStatus in setOf("", "Idle", "Connecting")),
                authorizing = authorizing, live = live,
                accountDetail = prefs.getString(KEY_ACCOUNT, null)?.takeIf(String::isNotBlank))
        }

        /** One peer as the running client sees it. [relayed] false with [connected] true is a direct WireGuard path. */
        data class PeerPath(val overlayIp: String, val connected: Boolean, val relayed: Boolean, val latencyMs: Long,
            val localCandidate: String, val remoteCandidate: String, val remoteEndpoint: String,
            val bytesRx: Long = 0L, val bytesTx: Long = 0L)

        /** The running client's peer table. A JNI call into the Go client, so call it off the main thread. */
        fun peerPaths(): List<PeerPath> {
            val engine = activeInstance?.takeIf { it.running.get() }?.client ?: return emptyList()
            return runCatching {
                val peers = engine.peersList()
                (0 until peers.size()).mapNotNull { index ->
                    peers.get(index)?.let { peer ->
                        // internal/peer.ConnStatus: 0 idle, 1 connecting, 2 connected.
                        PeerPath(peer.ip.orEmpty(), peer.connStatus == 2L, peer.relayed, peer.latencyMs,
                            peer.localIceCandidateType.orEmpty(), peer.remoteIceCandidateType.orEmpty(), peer.remoteIceCandidateEndpoint.orEmpty(),
                            peer.bytesRx, peer.bytesTx)
                    }
                }
            }.getOrDefault(emptyList())
        }

        /**
         * Service-side poll cadence. States that wait on the user (sign-in needed or failed) can
         * last for hours, so they poll slowly instead of waking the JNI client every 1.5 s.
         */
        fun statusPollDelayMs(status: String): Long = when (status) {
            "Connected" -> 5000L
            "NeedsLogin", "SessionExpired", "LoginFailed" -> 15000L
            else -> 1500L
        }

        /** Maps the Go client's status names (internal.StatusType) to plain phone-link text. */
        fun describeGoStatus(status: String): String = when (status) {
            "Connected" -> "Phone connected to NetBird"
            "Connecting" -> "Connecting this phone to NetBird…"
            "Idle" -> "Starting the NetBird client…"
            "NeedsLogin", "SessionExpired" -> "NetBird asks this phone to sign in again. Tap Authorize and connect this phone."
            "LoginFailed" -> "NetBird sign-in for this phone failed. Tap Authorize and connect this phone to retry."
            else -> "Connecting this phone to NetBird…"
        }
    }
}
