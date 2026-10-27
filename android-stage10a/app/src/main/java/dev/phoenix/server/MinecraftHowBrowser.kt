package dev.phoenix.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens Minecraft.How account and DNS management in the user's browser session. */
object MinecraftHowBrowser {
    const val DNS_DASHBOARD_URL = "https://minecraft.how/dashboard/dns"

    fun openDnsDashboard(context: Context) = open(context, DNS_DASHBOARD_URL)

    /**
     * Minecraft.How's own "Continue with Google" route, opened in the user's browser because Google refuses
     * sign-in inside apps. Google's page comes up directly and the site lands on its DNS page afterwards.
     * The sign-in stays in the browser; Phoenix never sees or intercepts it.
     */
    fun openGoogleSignIn(context: Context) = open(context, MinecraftHowSignInPolicy.providerAuthUrl(MinecraftHowSignInPolicy.Provider.GOOGLE))

    private fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
        }.getOrElse {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
