package dev.phoenix.server

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

/** App-facing control seam for the embedded NetBird VPN client. */
class NetBirdTransport(context: Context) {
    private val app = context.applicationContext

    data class Snapshot(
        val connected: Boolean,
        val detail: String,
        val peerIp: String?,
        val busy: Boolean = false,
        val authorizing: Boolean = false,
        /** A NetBird client is running in this process right now. */
        val live: Boolean = false,
        /** Latest browser sign-in event, kept separate from the phone-link status. */
        val accountDetail: String? = null,
    )

    fun snapshot(): Snapshot {
        if (!supportsBundledClient()) {
            return Snapshot(false, "This build includes the NetBird client for arm64 Android devices.", null)
        }
        return NetBirdVpnService.snapshot(app)
    }

    /** Restores a previously authorized peer only. It never opens a consent screen or browser. */
    fun reconnect(): Snapshot {
        if (!supportsBundledClient()) return snapshot()
        val identityExists = runCatching {
            NetBirdStore(app).configuration().optBoolean("identityEnrolled")
        }.getOrDefault(false)
        if (!identityExists) return Snapshot(false, "Authorize this phone in NetBird before reconnecting.", null)
        if (VpnService.prepare(app) != null) {
            return Snapshot(false, "Android VPN permission is needed. Open NetBird settings in Phoenix to reconnect.", null)
        }
        start(app, NetBirdVpnService.ACTION_RECONNECT)
        return snapshot().let { if (it.connected) it else it.copy(detail = "Connecting this phone with its saved NetBird identity…") }
    }

    /** Explicit user disconnect. The local peer identity and permanent services remain saved. */
    fun stop() {
        app.startService(Intent(app, NetBirdVpnService::class.java).setAction(NetBirdVpnService.ACTION_DISCONNECT))
    }

    companion object {
        private const val OWNER_PREFS = "netbird-owner-choice"
        private const val KEY_OWNER_OFF = "turned-off"
        /** Phoenix's own preferences; the same file and key the backend uses for a pending route request. */
        private const val PHOENIX_PREFS = "phoenix-stage10a"
        private const val KEY_REQUESTED_ROUTE = "requested-route"

        /** Whether the owner turned NetBird off with its switch; automatic paths then leave it off until the owner turns it on. */
        fun ownerTurnedOff(context: Context): Boolean = runCatching {
            context.applicationContext.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNER_OFF, false)
        }.getOrDefault(false)

        /**
         * Records the owner's NetBird switch. Turning it off also drops a pending NetBird request, so nothing switches to
         * NetBird later by itself. Turning it on clears the record.
         */
        fun recordOwnerChoice(context: Context, turnedOff: Boolean) {
            val app = context.applicationContext
            runCatching { app.getSharedPreferences(OWNER_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWNER_OFF, turnedOff).commit() }
            if (!turnedOff) return
            val phoenix = app.getSharedPreferences(PHOENIX_PREFS, Context.MODE_PRIVATE)
            val requested = PublicRoute.of(phoenix.getString(KEY_REQUESTED_ROUTE, null))
            if (NetworkIntent.requestAfterNetBirdOff(requested) != requested) phoenix.edit().remove(KEY_REQUESTED_ROUTE).apply()
        }

        /** Online play is managed in Settings › Networking; notifications and Android's VPN settings open it there. */
        fun launchIntent(context: Context): Intent = MainActivity.settingsIntent(context)

        internal fun start(context: Context, action: String) {
            // Connect and sign-in are the owner's own requests, so they also clear an earlier switch-off.
            if (action == NetBirdVpnService.ACTION_CONNECT || action == NetBirdVpnService.ACTION_AUTH_ONLY) recordOwnerChoice(context, false)
            val intent = Intent(context, NetBirdVpnService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        internal fun supportsBundledClient(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }
}
