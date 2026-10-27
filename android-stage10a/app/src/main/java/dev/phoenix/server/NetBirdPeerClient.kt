package dev.phoenix.server

import org.json.JSONArray
import org.json.JSONObject
import org.phoenix.core.network.netbird.NetBirdHttpTransport
import org.phoenix.core.network.netbird.NetBirdServicesClient
import org.phoenix.core.network.netbird.NetBirdApiException
import java.net.URI

/** Peer API adapter, independent of DNS and transport enrollment. Never accepts account passwords. */
internal class NetBirdPeerClient(private val transport: NetBirdHttpTransport, private val token: String,
    private val scheme: NetBirdServicesClient.AuthScheme = NetBirdServicesClient.AuthScheme.PERSONAL_ACCESS_TOKEN) {
    fun resolvePeer(overlayIp: String): JSONObject {
        val peers = JSONArray(request("GET", "/api/peers"))
        val matches = (0 until peers.length()).map { peers.getJSONObject(it) }
            .filter { it.optString("ip") == overlayIp || it.optString("ipv6") == overlayIp }
        check(matches.size == 1) { "The connected phone is not uniquely present in this NetBird account" }
        return getPeer(matches.single().getString("id"))
    }
    fun getPeer(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return JSONObject(request("GET", "/api/peers/$id")).also {
            check(it.getString("id") == id) { "NetBird returned a different peer" }
            check(!it.optBoolean("ephemeral", true)) { "Hosting requires a persistent NetBird peer" }
        }
    }
    fun preserveHostingSession(id: String): JSONObject {
        val peer = getPeer(id)
        if (peer.optBoolean("login_expiration_enabled", true) || peer.optBoolean("inactivity_expiration_enabled", true)) {
            val update = JSONObject().put("name", peer.getString("name"))
                .put("ssh_enabled", peer.getBoolean("ssh_enabled"))
                .put("login_expiration_enabled", false).put("inactivity_expiration_enabled", false)
            request("PUT", "/api/peers/$id", update.toString())
        }
        val verified = getPeer(id)
        check(verified.has("login_expiration_enabled") && !verified.getBoolean("login_expiration_enabled") &&
            verified.has("inactivity_expiration_enabled") && !verified.getBoolean("inactivity_expiration_enabled")) {
            "NetBird did not confirm disabled hosting-peer session expiration"
        }
        val flags=verified.optJSONObject("local_flags")
        check(flags!=null && flags.has("block_inbound") && !flags.getBoolean("block_inbound")) {
            "NetBird peer is configured to block incoming game traffic"
        }
        return verified
    }
    private fun request(method: String, path: String, body: String = ""): String {
        require(token.isNotBlank() && token.none { it.isWhitespace() || it.isISOControl() })
        val prefix=if(scheme==NetBirdServicesClient.AuthScheme.OAUTH_BEARER)"Bearer" else "Token"
        val response = transport.execute(NetBirdHttpTransport.Request(method, URI("https://api.netbird.io$path"),
            mapOf("Authorization" to "$prefix $token", "Accept" to "application/json", "Content-Type" to "application/json"), body))
        if(response.statusCode()==429) {
            val now=System.currentTimeMillis()
            val retry=NetBirdApiException.parseRetryAfter(response.header("Retry-After").orElse(null),now) ?: (now+60000)
            throw NetBirdApiException(NetBirdApiException.Code.RATE_LIMITED,429,retry,null)
        }
        check(response.statusCode() in 200..299) { "NetBird peer $method failed (HTTP ${response.statusCode()}); account access or retry is required" }
        return response.body()
    }
}
