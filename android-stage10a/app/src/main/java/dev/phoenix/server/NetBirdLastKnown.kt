package dev.phoenix.server

import android.content.Context

/**
 * The last NetBird facts this phone saw, in a small preference file, so the first status read after the app starts shows
 * them instead of "not set up" while the encrypted store is still being read. Used only until a live read succeeds.
 */
object NetBirdLastKnown {
    private const val PREFS = "netbird-last-known"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_IDENTITY = "has-identity"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun account(context: Context): NetBirdOAuthSession.AccountState? = runCatching {
        prefs(context).getString(KEY_ACCOUNT, null)?.let { NetBirdOAuthSession.AccountState.valueOf(it) }
    }.getOrNull()

    fun rememberAccount(context: Context, state: NetBirdOAuthSession.AccountState) {
        runCatching { prefs(context).edit().putString(KEY_ACCOUNT, state.name).apply() }
    }

    fun hasIdentity(context: Context): Boolean? = runCatching {
        prefs(context).takeIf { it.contains(KEY_IDENTITY) }?.getBoolean(KEY_IDENTITY, false)
    }.getOrNull()

    fun rememberHasIdentity(context: Context, value: Boolean) {
        runCatching { prefs(context).edit().putBoolean(KEY_IDENTITY, value).apply() }
    }
}
