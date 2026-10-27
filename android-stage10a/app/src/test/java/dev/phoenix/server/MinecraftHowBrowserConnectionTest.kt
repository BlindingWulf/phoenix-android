package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

class MinecraftHowBrowserConnectionTest {
    // Explicit fake contract: these are fixture paths, never configured in the app.
    private val contract = MinecraftHowBrowserConnection.Contract("fixture-client", "/fixture/authorize", "/fixture/exchange", MinecraftHowBrowserConnection.CALLBACK_URI)
    private fun query(source: String) = source.substringAfter('?').split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    private fun journal() = MinecraftHowHandoffJournal(MemoryStore())
    private fun callback(attempt: MinecraftHowBrowserConnection.Attempt, extra: String = "code=fixture-code") = "${contract.callback}?state=${attempt.state}&$extra"

    @Test fun uniqueAttemptsUseS256AndExchangeBoundCodeOnceBeforeVerification() {
        val requests = mutableListOf<HttpRequest>()
        var verified = 0
        val connection = MinecraftHowBrowserConnection(contract, journal(), HttpTransport {
            requests += it
            HttpResponse(200, mapOf("Set-Cookie" to listOf("fixture=fake; Secure; HttpOnly; Path=/")))
        })
        val first = connection.begin()!!
        val second = MinecraftHowBrowserConnection(contract, journal()).begin()!!
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.verifier, second.verifier)
        val authorization = query(first.authorizationUrl)
        assertEquals("S256", authorization["code_challenge_method"])
        assertEquals("google", authorization["provider"])
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray())), authorization["code_challenge"])
        assertEquals(MinecraftHowBrowserConnection.Outcome.CONNECTED, connection.complete(first, callback(first)) { verified++; true })
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(first, callback(first)) { verified++; true })
        assertEquals(1, requests.size)
        assertEquals(1, verified)
        val posted = query("?" + requests.single().body)
        assertEquals(first.verifier, posted["code_verifier"])
        assertEquals(contract.callback.toString(), posted["redirect_uri"])
        assertEquals("POST", requests.single().method)
        assertEquals(contract.exchangePath, requests.single().path)
        assertNull(requests.single().headers["Cookie"])
    }

    @Test fun callbackRequiresExactReturnStateAndUnambiguousFields() {
        var requests = 0
        val connection = MinecraftHowBrowserConnection(contract, journal(), HttpTransport { requests++; HttpResponse(200) })
        val invalid = listOf<(MinecraftHowBrowserConnection.Attempt) -> String>(
            { "https://evil.example/?state=${it.state}&code=x" },
            { "${contract.callback}?state=wrong&code=x" },
            { callback(it, "code=x&code=y") },
            { callback(it, "code=x&access_token=untrusted") },
            { callback(it) + "#fragment" },
            { "${MinecraftHowBrowserConnection.CALLBACK_SCHEME}://connected/other?state=${it.state}&code=x" },
            { callback(it, "code=x&error=access_denied") },
        )
        for (makeCallback in invalid) {
            val attempt = connection.begin()!!
            assertEquals(MinecraftHowBrowserConnection.Outcome.INVALID_CALLBACK, connection.complete(attempt, makeCallback(attempt)) { true })
        }
        assertEquals(0, requests)
    }

    @Test fun timeoutOrRedirectIsNeverRetriedOrFollowed() {
        var writes = 0
        for (status in listOf(0, 302, 409, 429, 500)) {
            val connection = MinecraftHowBrowserConnection(contract, journal(), HttpTransport {
                writes++
                if (status == 0) throw IOException("fixture failure")
                HttpResponse(status, mapOf("Location" to listOf("https://evil.example/")))
            })
            val attempt = connection.begin()!!
            assertEquals(MinecraftHowBrowserConnection.Outcome.EXCHANGE_UNCONFIRMED, connection.complete(attempt, callback(attempt)) { true })
            assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(attempt, callback(attempt)) { true })
        }
        assertEquals(5, writes)
    }

    @Test fun successfulHttpNeedsVerifiedNativeAccountSession() {
        val connection = MinecraftHowBrowserConnection(contract, journal(), HttpTransport { HttpResponse(200, mapOf("Set-Cookie" to listOf("fixture=fake; Secure"))) })
        val attempt = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.SESSION_UNVERIFIED, connection.complete(attempt, callback(attempt)) { false })
        val noCookies = MinecraftHowBrowserConnection(contract, journal(), HttpTransport { HttpResponse(200, body = "{\"success\":true}") })
        val other = noCookies.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.SESSION_UNVERIFIED, noCookies.complete(other, callback(other)) { error("Must not install") })
    }

    @Test fun expiredOrCanceledAttemptsNeverExchange() {
        var now = 1000L
        var writes = 0
        val connection = MinecraftHowBrowserConnection(contract, journal(), HttpTransport { writes++; HttpResponse(200) }, { now })
        val attempt = connection.begin()!!
        now += 600001
        assertEquals(MinecraftHowBrowserConnection.Outcome.EXPIRED, connection.complete(attempt, callback(attempt)) { true })
        val canceled = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CANCELED, connection.complete(canceled, null) { true })
        val denied = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CANCELED, connection.complete(denied, callback(denied, "error=access_denied")) { true })
        assertEquals(0, writes)
    }

    @Test fun contractsRejectOffsiteAndMalformedDestinations() {
        for (path in listOf("https://evil.example/token", "//evil.example/token", "/token?other=1", "/a/../token", "/token#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { MinecraftHowBrowserConnection.Contract("fixture", path, "/token", contract.callback) }
        }
        assertThrows(IllegalArgumentException::class.java) { MinecraftHowBrowserConnection.Contract("fixture", "/auth", "/token", URI("${MinecraftHowBrowserConnection.CALLBACK_SCHEME}://evil")) }
    }

    @Test fun sensitiveAttemptStringsAreNotExposedByToString() {
        val attempt = MinecraftHowBrowserConnection(contract, journal()).begin()!!
        assertFalse(attempt.toString().contains(attempt.state))
        assertFalse(attempt.toString().contains(attempt.verifier))
        assertEquals("MinecraftHowBrowserContract", contract.toString())
    }
}
