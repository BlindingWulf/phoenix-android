package dev.phoenix.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class MinecraftHowProviderOptions(
    val providers: List<MinecraftHowSignInPolicy.Provider>,
    val usedFallback: Boolean,
)

/** Fetches only the public shared Inertia props needed to mirror the site's login provider list. */
internal object MinecraftHowProviderCatalog {
    private const val MAX_REDIRECTS = 2
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 8_000
    private const val READ_TIMEOUT_MILLIS = 8_000
    private const val OPTIONS_URL = "https://minecraft.how/?login=1"

    fun load(): MinecraftHowProviderOptions = try {
        fetch()
    } catch (_: Exception) {
        fallback()
    }

    private fun fetch(): MinecraftHowProviderOptions {
        var current = URL(OPTIONS_URL)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Too many Minecraft.How redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Minecraft.How redirect had no target")
                    val target = URL(current, location)
                    if (!isSameOrigin(target)) throw IOException("Minecraft.How options redirected off site")
                    current = target
                    redirects++
                    continue
                }
                if (status !in 200..299) throw IOException("Minecraft.How options unavailable")
                val body = connection.inputStream.use(::readBounded).toString(StandardCharsets.UTF_8)
                val bootstrap = MinecraftHowInertia.parseHtmlBootstrap(body)
                    ?: throw IOException("Minecraft.How bootstrap was invalid")
                return parse(bootstrap.props) ?: throw IOException("Minecraft.How provider options were invalid")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun isSameOrigin(target: URL): Boolean =
        target.protocol.equals("https", ignoreCase = true) &&
            target.host.equals("minecraft.how", ignoreCase = true) &&
            target.port in listOf(-1, 443) && target.userInfo == null &&
            target.file.startsWith("/") && !target.file.startsWith("//")

    private fun parse(props: Map<*, *>): MinecraftHowProviderOptions? = try {
        val rawOrder = props["oauthProviderOrder"] as? List<*>
        val order = rawOrder?.mapNotNull { it as? String }
        val enabledObject = props["oauthProvidersEnabled"] as? Map<*, *>
        val enabled = enabledObject?.entries?.mapNotNull { (key, value) ->
            val provider = key as? String ?: return@mapNotNull null
            val isEnabled = value as? Boolean ?: return@mapNotNull null
            provider to isEnabled
        }?.toMap()
        require(order != null && enabled != null && order.all { enabled.containsKey(it) })
        MinecraftHowProviderOptions(
            providers = MinecraftHowSignInPolicy.enabledProviders(order, enabled),
            usedFallback = false,
        )
    } catch (_: Exception) {
        null
    }

    private fun fallback() = MinecraftHowProviderOptions(
        providers = emptyList(),
        usedFallback = true,
    )

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_BODY_BYTES) throw IOException("Minecraft.How options response too large")
            output.write(buffer, 0, count)
        }
    }
}
