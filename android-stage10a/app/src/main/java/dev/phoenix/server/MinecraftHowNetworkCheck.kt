package dev.phoenix.server

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Minecraft.How serves its page styles and scripts from cdn.minecraft.how (a Bunny CDN alias).
 * When this network's DNS cannot resolve that name while minecraft.how itself resolves, every
 * Minecraft.How page renders as unstyled HTML. This is a read-only name lookup: a failed lookup
 * does not say why it failed, so Phoenix reports what it saw and never changes network settings.
 * Separate from connecting an account: it affects how pages look, not who is signed in.
 */
internal object MinecraftHowNetworkCheck {
    enum class Result { OK, CDN_UNRESOLVED, OFFLINE }

    const val CDN_HOST = "cdn.minecraft.how"
    private const val SITE_HOST = "minecraft.how"

    fun check(resolve: (String) -> Boolean = ::resolves): Result {
        val site = resolve(SITE_HOST)
        val cdn = resolve(CDN_HOST)
        return when {
            site && cdn -> Result.OK
            site -> Result.CDN_UNRESOLVED
            else -> Result.OFFLINE
        }
    }

    const val CDN_UNRESOLVED_MESSAGE =
        "Phoenix could not look up cdn.minecraft.how on this network (minecraft.how itself was found), so Minecraft.How pages " +
            "may load without their styles and buttons. Try mobile data or another Wi-Fi, or ask whoever runs this network " +
            "to check its DNS. Android's Private DNS setting is another option. Phoenix does not change network settings."

    const val OFFLINE_MESSAGE = "Phoenix could not look up minecraft.how. Check your internet connection, then tap Retry."

    private fun resolves(host: String): Boolean = try {
        InetAddress.getAllByName(host).isNotEmpty()
    } catch (_: UnknownHostException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
