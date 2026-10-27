package dev.phoenix.server

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import org.phoenix.core.network.netbird.NetBirdHttpTransport
import org.phoenix.core.network.netbird.NetBirdApiException
import org.phoenix.core.network.netbird.NetBirdServicesClient
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Per-user OAuth session received from the embedded client's supported browser flow. */
object NetBirdOAuthSession {
    data class Credential(val token: String, val scheme: NetBirdServicesClient.AuthScheme)
    fun save(context: Context, accessToken: String, refreshToken: String, expiresIn: Long, tokenEndpoint: String, clientId: String,
             selectedToken: String = accessToken, useIdToken: Boolean = false) {
        require(validToken(accessToken) && validToken(selectedToken))
        require(expiresIn in 1..31536000 && clientId.isNotBlank())
        endpoint(tokenEndpoint)
        val store = NetBirdStore(context)
        store.locked {
            val config = store.configuration()
            store.saveConfiguration(config.put("token", accessToken).put("authScheme", "OAUTH_BEARER")
                .put("accessToken", accessToken).put("selectedToken", selectedToken).put("useIdToken", useIdToken)
                .put("apiTokenKind", "UNVALIDATED")
                .put("refreshToken", refreshToken).put("expiresAt", System.currentTimeMillis() + expiresIn * 1000)
                .put("tokenEndpoint", tokenEndpoint).put("clientId", clientId))
        }
    }
    enum class AccountState { NONE, EXPIRED, SAVED, VERIFIED }

    @Volatile private var lastAccountState = AccountState.NONE
    /** One store for the status reads the dashboard makes every second; it holds only paths and shared locks. */
    @Volatile private var statusStore: NetBirdStore? = null
    private fun statusStore(context: Context) = statusStore ?: NetBirdStore(context.applicationContext).also { statusStore = it }

    /**
     * Local-only view of the saved account session; never makes a network request and never
     * waits for the store, so the 1 s status poll stays responsive while provisioning holds it.
     */
    /** What the account check needs, without the tokens themselves. */
    private data class AccountView(val oauth: Boolean, val expiresAt: Long, val refreshable: Boolean, val tokenKind: String)

    fun accountState(context: Context): AccountState = runCatching {
        val view = statusStore(context).peekDerived("account") { _, config ->
            AccountView(config.optString("authScheme") == "OAUTH_BEARER" && config.optString("token").isNotBlank(),
                config.optLong("expiresAt"), config.optString("refreshToken").isNotBlank(), config.optString("apiTokenKind"))
        } ?: return NetworkIntent.lastKnown(null, NetBirdLastKnown.account(context), lastAccountState)
        when {
            !view.oauth -> AccountState.NONE
            view.expiresAt <= System.currentTimeMillis() + 60000 && !view.refreshable -> AccountState.EXPIRED
            view.tokenKind in setOf("ACCESS", "SELECTED") -> AccountState.VERIFIED
            else -> AccountState.SAVED
        }.also { if (it != lastAccountState) NetBirdLastKnown.rememberAccount(context, it); lastAccountState = it }
    }.getOrDefault(lastAccountState)

    fun hasSession(context: Context): Boolean = runCatching {
        val config = NetBirdStore(context).configuration()
        config.optString("authScheme") == "OAUTH_BEARER" && config.optString("token").isNotBlank() &&
            (config.optLong("expiresAt") > System.currentTimeMillis() + 60000 || config.optString("refreshToken").isNotBlank())
    }.getOrDefault(false)

    /** Called on the backend worker, with refresh serialized by the encrypted store lock. */
    fun credential(context: Context): Credential {
        val store = NetBirdStore(context)
        return store.locked {
            var config = store.configuration()
            check(config.optString("token").isNotBlank()) { "Authorize your NetBird account in Phoenix" }
            check(config.optString("authScheme") == "OAUTH_BEARER") { "Authorize your own NetBird account through the supported browser flow" }
            if (config.optLong("expiresAt") <= System.currentTimeMillis() + 60000) {
                val refresh = config.optString("refreshToken")
                check(refresh.isNotBlank()) { "NetBird authorization expired. Authorize again; permanent services remain saved." }
                val connection = endpoint(config.getString("tokenEndpoint")).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000; connection.readTimeout = 10000
                connection.instanceFollowRedirects = false; connection.useCaches = false; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val form = listOf("grant_type" to "refresh_token", "client_id" to config.getString("clientId"), "refresh_token" to refresh)
                    .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray(Charsets.UTF_8)
                try {
                    connection.setFixedLengthStreamingMode(form.size)
                    connection.outputStream.use { it.write(form) }
                    if(connection.responseCode==429) rateLimited(connection.getHeaderField("Retry-After"))
                    check(connection.responseCode in 200..299) { "NetBird authorization refresh failed (HTTP ${connection.responseCode}); authorize again without reallocating services" }
                    val bytes = connection.inputStream.use { input ->
                        val output=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(4096)
                        try {
                            while(true) {
                                val count=input.read(buffer)
                                if(count<0)break
                                check(output.size()+count<=65536) { "NetBird authorization response exceeds size bound" }
                                output.write(buffer,0,count)
                            }
                            output.toByteArray()
                        } finally { buffer.fill(0) }
                    }
                    check(bytes.size <= 65536) { "NetBird authorization response exceeds size bound" }
                    val response = try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
                    val seconds = response.getLong("expires_in")
                    check(seconds in 1..31536000)
                    val access = response.getString("access_token")
                    check(validToken(access)) { "NetBird returned an invalid access token; authorize again without reallocating services" }
                    val selected = if(config.optBoolean("useIdToken")) response.optString("id_token") else access
                    config = config.put("token", access).put("accessToken", access)
                        .put("selectedToken", selected).put("apiTokenKind", "UNVALIDATED")
                        .put("refreshToken", response.optString("refresh_token").ifBlank { refresh })
                        .put("expiresAt", System.currentTimeMillis() + seconds * 1000)
                    store.saveConfiguration(config)
                } finally { form.fill(0); connection.disconnect() }
            }
            if (config.optString("apiTokenKind") !in setOf("ACCESS", "SELECTED")) {
                // Peer enrollment and REST API token audiences can differ. Prove REST access
                // through a GET before allowing any peer-setting or service mutation.
                val candidates = linkedMapOf("ACCESS" to config.optString("accessToken"), "SELECTED" to config.optString("selectedToken"))
                    .filterValues { validToken(it) }.entries.distinctBy { it.value }
                var accepted: Map.Entry<String, String>? = null
                for (candidate in candidates) {
                    val response = UrlConnectionNetBirdTransport().execute(NetBirdHttpTransport.Request("GET",
                        URI.create("https://api.netbird.io/api/peers"),
                        mapOf("Authorization" to "Bearer ${candidate.value}", "Accept" to "application/json"), ""))
                    if(response.statusCode()==429) rateLimited(response.header("Retry-After").orElse(null))
                    if (response.statusCode() in 200..299) {
                        check(runCatching { JSONArray(response.body()) }.isSuccess) { "NetBird REST authorization returned an unexpected peer response" }
                        accepted = candidate
                        break
                    }
                    check(response.statusCode() in setOf(401, 403)) {
                        "NetBird REST authorization check failed (HTTP ${response.statusCode()}); saved services remain preserved"
                    }
                }
                check(accepted != null) { "NetBird browser authorization did not grant REST API access; saved services remain preserved" }
                config.put("token", accepted.value).put("apiTokenKind", accepted.key)
                store.saveConfiguration(config)
            }
            Credential(config.getString("token"), NetBirdServicesClient.AuthScheme.OAUTH_BEARER)
        }
    }
    private fun validToken(value: String) = value.isNotBlank() && value.none { it.isWhitespace() || it.isISOControl() }
    private fun rateLimited(header: String?): Nothing {
        val now=System.currentTimeMillis()
        throw NetBirdApiException(NetBirdApiException.Code.RATE_LIMITED,429,
            NetBirdApiException.parseRetryAfter(header,now) ?: (now+60000),null)
    }
    private fun endpoint(value: String): URI = URI(value).also {
        // Endpoint metadata comes from the authenticated NetBird Cloud management response,
        // not a user-entered URL. Restrict refresh credentials to the Cloud IdP origins.
        require(it.scheme == "https" && it.host in setOf("login.netbird.io", "netbird.eu.auth0.com") &&
            it.userInfo == null && it.port == -1 && it.rawQuery == null && it.rawFragment == null) {
            "Unsupported NetBird Cloud token endpoint; no credentials were sent"
        }
    }
}
