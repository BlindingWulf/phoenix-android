package dev.phoenix.server

import java.net.HttpCookie
import java.util.Locale

/** Validates only cookies issued by the first-party TLS code exchange. */
internal object MinecraftHowBrowserCookies {
    class Receipt(val setCookieHeaders: List<String>, val cookieHeader: String) {
        override fun toString() = "MinecraftHowBrowserCookieReceipt"
    }

    fun validate(headers: List<String>): Receipt? = runCatching {
        require(headers.size in 1..16 && headers.all { it.length <= 8192 && '\r' !in it && '\n' !in it })
        val cookies = headers.flatMap(HttpCookie::parse)
        require(cookies.isNotEmpty() && cookies.map { it.name }.distinct().size == cookies.size)
        require(cookies.any { it.name == "XSRF-TOKEN" && it.value.isNotBlank() })
        require(cookies.all {
            it.secure && !it.hasExpired() && it.domain?.removePrefix(".")?.lowercase(Locale.ROOT).let { domain -> domain == null || domain == "minecraft.how" } &&
                it.path.let { path -> path == null || path == "/" } && it.name.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                it.value.none { char -> char.isISOControl() || char == ';' }
        })
        Receipt(headers.toList(), cookies.joinToString("; ") { "${it.name}=${it.value}" })
    }.getOrNull()
}

/**
 * Replaces, never merges, Phoenix's saved Minecraft.How session with one from a completed handoff.
 * The new session must verify on its own first, and the jar must afterwards read back as that same
 * account id, so a still-valid earlier account can never be mistaken for the one just chosen.
 */
internal object MinecraftHowSessionSwitch {
    /** Phoenix's private cookie jar for https://minecraft.how. */
    interface Jar {
        fun header(): String?
        fun set(setCookie: String)
        fun flush()
    }

    fun install(headers: List<String>, jar: Jar, verify: (String) -> MinecraftHowClient.SessionVerification): Boolean {
        val receipt = MinecraftHowBrowserCookies.validate(headers) ?: return false
        val chosen = verify(receipt.cookieHeader)
        val accountId = chosen.accountId
        // Nothing is touched until the new session proves itself; an earlier sign-in stays as it was.
        if (!chosen.signedIn || accountId == null) return false
        clear(jar)
        receipt.setCookieHeaders.forEach(jar::set)
        jar.flush()
        val installed = jar.header()
        if (installed != null && names(installed) == names(receipt.cookieHeader)) {
            val readBack = verify(installed)
            if (readBack.signedIn && readBack.accountId == accountId) return true
        }
        // Leftover or conflicting cookies: sign Phoenix out rather than keep an ambiguous account.
        clear(jar)
        jar.flush()
        return false
    }

    fun clear(jar: Jar) {
        for (name in names(jar.header().orEmpty())) {
            jar.set("$name=; Max-Age=0; Path=/; Secure")
            jar.set("$name=; Max-Age=0; Path=/; Secure; Domain=minecraft.how")
        }
    }

    private fun names(header: String): Set<String> =
        header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()
}
