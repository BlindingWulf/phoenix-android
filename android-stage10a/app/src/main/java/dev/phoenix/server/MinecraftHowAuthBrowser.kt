package dev.phoenix.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService

/**
 * Picks a real, separate browser for the Google handoff. Any standards-following browser works
 * because the return is an ordinary redirect to Phoenix's callback scheme:
 * Auth Tab (Chrome 137+) > Custom Tab (Via, Firefox, Samsung, Edge, ...) > plain browser view.
 * Phoenix's own embedded WebView is never a candidate.
 */
internal object MinecraftHowAuthBrowser {
    enum class Mode { AUTH_TAB, CUSTOM_TAB, EXTERNAL }
    class Selection(val packageName: String, val mode: Mode)

    fun select(context: Context): Selection? = runCatching {
        // Default browser when it supports Custom Tabs, otherwise the first installed provider.
        val customTabs = CustomTabsClient.getPackageName(context, null)
        if (customTabs != null && customTabs != context.packageName) {
            val mode = if (CustomTabsClient.isAuthTabSupported(context, customTabs)) Mode.AUTH_TAB else Mode.CUSTOM_TAB
            return@runCatching Selection(customTabs, mode)
        }
        externalBrowser(context)?.let { Selection(it, Mode.EXTERNAL) }
    }.getOrNull()

    fun customTabsProviders(context: Context): List<String> = runCatching {
        context.packageManager.queryIntentServices(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), 0)
            .map { it.serviceInfo.packageName }.distinct()
    }.getOrDefault(emptyList())

    private fun externalBrowser(context: Context): String? {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://minecraft.how/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val pm = context.packageManager
        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val candidates = pm.queryIntentActivities(view, 0).map { it.activityInfo.packageName }.distinct()
        // "android" is the system disambiguation dialog, not a browser.
        return (listOfNotNull(default) + candidates).firstOrNull { it != "android" && it != context.packageName }
    }
}
