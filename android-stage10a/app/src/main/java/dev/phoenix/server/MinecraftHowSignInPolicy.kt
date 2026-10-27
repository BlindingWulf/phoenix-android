package dev.phoenix.server

import java.net.URI
import java.util.Locale

/** Strict top-level navigation rules for the Minecraft.How embedded sign-in. */
internal object MinecraftHowSignInPolicy {
    enum class Provider { GOOGLE, DISCORD, MICROSOFT, FACEBOOK, TWITCH, STEAM }

    private val defaultProviderOrder = listOf(
        Provider.DISCORD,
        Provider.FACEBOOK,
        Provider.STEAM,
        Provider.TWITCH,
        Provider.MICROSOFT,
        Provider.GOOGLE,
    )

    private val providersBySlug = Provider.values().associateBy { it.slug }

    private data class Route(val provider: Provider, val host: String, val pathRules: List<Regex>)

    private val routes = listOf(
        Route(Provider.GOOGLE, "accounts.google.com", listOf(Regex("^/(?:o/oauth2/.*|v3/signin/.*|signin/.*)$"))),
        Route(Provider.DISCORD, "discord.com", listOf(Regex("^/(?:api/)?oauth2/authorize/?$"), Regex("^/(?:login|register|verify)/?$"))),
        Route(Provider.MICROSOFT, "login.microsoftonline.com", listOf(Regex("^/[^/]+/(?:oauth2/(?:v2\\.0/)?authorize|login|GetCredentialType|reprocess)(?:/.*)?$"))),
        Route(Provider.MICROSOFT, "login.live.com", listOf(Regex("^/(?:oauth20_authorize\\.srf|login\\.srf|ppsecure/.*)$"))),
        Route(Provider.FACEBOOK, "www.facebook.com", listOf(Regex("^/(?:v[0-9.]+/)?dialog/oauth/?$"), Regex("^/login(?:\\.php)?/?$"))),
        Route(Provider.FACEBOOK, "m.facebook.com", listOf(Regex("^/(?:v[0-9.]+/)?dialog/oauth/?$"), Regex("^/login(?:\\.php)?/?$"), Regex("^/unified/login_via/app/?$"))),
        Route(Provider.FACEBOOK, "facebook.com", listOf(Regex("^/(?:v[0-9.]+/)?dialog/oauth/?$"), Regex("^/login(?:\\.php)?/?$"))),
        Route(Provider.TWITCH, "id.twitch.tv", listOf(Regex("^/oauth2/authorize/?$"))),
        Route(Provider.TWITCH, "www.twitch.tv", listOf(Regex("^/(?:login|activate)/?$"))),
        Route(Provider.TWITCH, "passport.twitch.tv", listOf(Regex("^/login(?:/.*)?$"))),
        Route(Provider.STEAM, "steamcommunity.com", listOf(Regex("^/(?:openid(?:/.*)?|login/home/?)$")))
    )

    fun isMinecraftHowUrl(value: String): Boolean = parseSecureUri(value)?.host?.equals("minecraft.how", ignoreCase = true) == true

    fun isAllowedTopLevelUrl(value: String): Boolean {
        val uri = parseSecureUri(value) ?: return false
        if (uri.host.equals("minecraft.how", ignoreCase = true)) return true
        // Google disallows OAuth inside an embedded user agent controlled by the app.
        return providerFor(uri)?.let { it != Provider.GOOGLE } == true
    }

    fun isDnsDashboardUrl(value: String): Boolean {
        val uri = parseSecureUri(value) ?: return false
        if (!uri.host.equals("minecraft.how", ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        return path == "/dashboard/dns" || path == "/dashboard/dns/"
    }

    fun isProviderCallbackUrl(value: String): Boolean {
        val uri = parseSecureUri(value) ?: return false
        if (!uri.host.equals("minecraft.how", ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        return path.matches(Regex("^/auth/(?:discord|facebook|steam|twitch|microsoft|google)/callback/?$"))
    }

    fun providerFor(value: String): Provider? = providerFor(parseSecureUri(value) ?: return null)

    /** Providers shown by Minecraft.How's login modal when shared props omit an order. */
    fun defaultProviders(): List<Provider> = defaultProviderOrder

    /** Parse the site-owned order/enabled props without accepting unknown provider names. */
    fun enabledProviders(order: List<String>?, enabled: Map<String, Boolean>?): List<Provider> {
        val sourceOrder = order?.mapNotNull { providersBySlug[it.trim().lowercase(Locale.ROOT)] }
            ?: defaultProviderOrder
        return sourceOrder.distinct().filter { enabled?.get(it.slug) != false }
    }

    /** Google refuses sign-in inside apps, so it is offered in Phoenix's sheet only when a browser can finish it. */
    fun inAppProviders(providers: List<Provider>, browserContractAvailable: Boolean): List<Provider> =
        providers.filter { it != Provider.GOOGLE || browserContractAvailable }

    /** Plain-language reason for a navigation the embedded sign-in refused. */
    fun blockedNavigationMessage(url: String): String =
        if (providerFor(url) == Provider.GOOGLE) {
            "Google doesn't allow sign-in inside Phoenix. Use another option here, or sign in at minecraft.how in your browser."
        } else {
            "Phoenix blocked navigation outside the verified HTTPS sign-in routes."
        }

    /** Mirrors the site's first-party OAuth route and fixed intended destination. */
    fun providerAuthUrl(provider: Provider): String =
        "https://minecraft.how/auth/${provider.slug}?redirect=%2Fdashboard%2Fdns"

    fun providerDisplayName(provider: Provider): String = when (provider) {
        Provider.GOOGLE -> "Google"
        Provider.DISCORD -> "Discord"
        Provider.MICROSOFT -> "Microsoft"
        Provider.FACEBOOK -> "Facebook"
        Provider.TWITCH -> "Twitch"
        Provider.STEAM -> "Steam"
    }

    fun canStartProvider(consented: Boolean, providerEnabled: Boolean): Boolean =
        consented && providerEnabled

    fun publicPolicyUrl(path: String): String? = when (path) {
        "/terms-of-service", "/privacy-policy" -> "https://minecraft.how$path"
        else -> null
    }

    private val Provider.slug: String
        get() = name.lowercase(Locale.ROOT)

    private fun parseSecureUri(value: String): URI? {
        val uri = try { URI(value) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.isOpaque) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun providerFor(uri: URI): Provider? {
        val host = uri.host.lowercase(Locale.ROOT)
        val path = uri.rawPath ?: "/"
        return routes.firstOrNull { route -> route.host == host && route.pathRules.any { it.matches(path) } }?.provider
    }
}
