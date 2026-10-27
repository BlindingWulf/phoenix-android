package dev.phoenix.server

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/** Owns the isolated, persistent WebView cookie profile used for Minecraft.How sign-in. */
object MinecraftHowSession {
    private const val SITE_ORIGIN = "https://minecraft.how"
    private const val DATA_DIRECTORY_SUFFIX = "minecraft-how-auth"
    private val profileLock = Any()
    @Volatile private var profilePrepared = false
    @Volatile private var profilePreparationFailed = false

    /**
     * Must run before any CookieManager/WebView use in the app process. A failed preparation
     * fails closed rather than falling back to the default shared WebView profile.
     */
    fun prepareWebViewDataDirectory(): Boolean = synchronized(profileLock) {
        if (profilePrepared) return true
        if (profilePreparationFailed) return false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WebView.setDataDirectorySuffix(DATA_DIRECTORY_SUFFIX)
            }
            profilePrepared = true
            true
        } catch (_: RuntimeException) {
            profilePreparationFailed = true
            false
        }
    }

    /** Returns the provider cookie header for an immediate HTTPS request; callers must not log or persist it. */
    fun cookieHeader(): String? {
        if (!prepareWebViewDataDirectory()) return null
        return try {
            CookieManager.getInstance().getCookie(SITE_ORIGIN)?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Stores cookies Minecraft.How itself issued in reply to Phoenix's own request, as a browser
     * would, and returns the updated header. Call on a worker thread; never log the result.
     */
    fun installIssuedCookies(setCookieHeaders: List<String>): String? {
        if (!prepareWebViewDataDirectory()) return null
        return try {
            val manager = CookieManager.getInstance()
            setCookieHeaders.forEach { manager.setCookie(SITE_ORIGIN, it) }
            manager.flush()
            cookieHeader()
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Flushes the WebView cookie jar. Call on a worker thread. */
    fun flushCookies() {
        if (!prepareWebViewDataDirectory()) return
        try {
            CookieManager.getInstance().flush()
        } catch (_: RuntimeException) {
            // WebView storage failures do not expose cookie contents or affect provider navigation.
        }
    }

    /**
     * Signs Phoenix out locally: clears this app's private Minecraft.How/provider sign-in profile only
     * (never another browser's data, never the Minecraft.How account itself). Call on the main thread;
     * [done] reports whether no Minecraft.How cookie remains, and may run on a worker thread.
     */
    fun signOut(context: Context, done: (Boolean) -> Unit) {
        if (!prepareWebViewDataDirectory()) { done(false); return }
        forgetVerified(context)
        try {
            WebStorage.getInstance().deleteAllData()
            val manager = CookieManager.getInstance()
            manager.removeAllCookies {
                // This callback runs on the main thread; the flush may block on disk, so it never runs here.
                MinecraftHowSignOutCompletion.complete(MinecraftHowSignOutCompletion.daemonThread, manager::flush, MinecraftHowSession::cookieHeader, done)
            }
        } catch (_: RuntimeException) {
            done(false)
        }
    }

    /**
     * Remembers only that Phoenix verified an account before (no name, id or cookie), so a later
     * signed-out read can say "expired" instead of "not signed in". Cleared by [signOut].
     */
    fun rememberVerified(context: Context) {
        verifiedPrefs(context).edit().putBoolean(KEY_VERIFIED_BEFORE, true).apply()
    }

    fun verifiedBefore(context: Context): Boolean = runCatching { verifiedPrefs(context).getBoolean(KEY_VERIFIED_BEFORE, false) }.getOrDefault(false)

    private fun forgetVerified(context: Context) {
        runCatching { verifiedPrefs(context).edit().remove(KEY_VERIFIED_BEFORE).apply() }
    }

    private const val KEY_VERIFIED_BEFORE = "verifiedBefore"
    private fun verifiedPrefs(context: Context) = context.applicationContext.getSharedPreferences("minecraft_how_account", Context.MODE_PRIVATE)

    /** Receipts must come from the first-party one-use code exchange, never from browser cookie extraction. */
    internal fun verifyAndInstallBrowserSession(headers: List<String>): Boolean = try {
        prepareWebViewDataDirectory() && MinecraftHowSessionSwitch.install(headers, object : MinecraftHowSessionSwitch.Jar {
            private val manager = CookieManager.getInstance()
            override fun header() = cookieHeader()
            override fun set(setCookie: String) = manager.setCookie(SITE_ORIGIN, setCookie)
            override fun flush() = manager.flush()
        }, MinecraftHowClient()::verifySession)
    } catch (_: Exception) {
        false
    }
}
