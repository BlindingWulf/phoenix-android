package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MinecraftHowSignInStateTest {
    private fun dnsPage(user: String) =
        """{"component":"Dns/Index","version":"v","props":{"auth":{"user":$user},"domains":[{"id":1,"domain":"a.example.com"}]}}"""

    @Test fun verifiedSessionNamesTheAccountAndCountsHostnames() {
        val client = MinecraftHowClient(HttpTransport { HttpResponse(200, body = dnsPage("""{"id":7,"username":"steve","name":"Steve"}""")) })
        val result = client.verifySession("fake=only")
        assertTrue(result.signedIn)
        assertTrue(result.reachable)
        assertEquals("steve", result.accountLabel)
        assertEquals("Signed in as steve · 1 hostname", result.detail)
        assertEquals(1, result.domainCount)
    }

    @Test fun signedOutIsReachableButUnreachableIsNotTreatedAsSignedOut() {
        val signedOut = MinecraftHowClient(HttpTransport { HttpResponse(200, body = dnsPage("null")) }).verifySession("fake=only")
        assertFalse(signedOut.signedIn)
        assertTrue(signedOut.reachable)

        val forbidden = MinecraftHowClient(HttpTransport { HttpResponse(401) }).verifySession("fake=only")
        assertFalse(forbidden.signedIn)
        assertTrue(forbidden.reachable)

        val offline = MinecraftHowClient(HttpTransport { throw IOException("fixture offline") }).verifySession("fake=only")
        assertFalse(offline.signedIn)
        assertFalse(offline.reachable)

        val serverError = MinecraftHowClient(HttpTransport { HttpResponse(503) }).verifySession("fake=only")
        assertFalse(serverError.signedIn)
        assertFalse(serverError.reachable)
    }

    @Test fun unsafeAccountLabelsAreDropped() {
        val client = MinecraftHowClient(HttpTransport { HttpResponse(200, body = dnsPage("""{"id":7,"username":"bad\nname","name":""}""")) })
        val result = client.verifySession("fake=only")
        assertTrue(result.signedIn)
        assertNull(result.accountLabel)
    }

    @Test fun callbackSchemeIsPerBuildAndContractStaysOffUntilProviderIssuesOne() {
        assertEquals(BuildConfig.APPLICATION_ID + ".minecraft-how", MinecraftHowBrowserConnection.CALLBACK_SCHEME)
        assertEquals("${BuildConfig.APPLICATION_ID}.minecraft-how://connected", MinecraftHowBrowserConnection.CALLBACK_URI.toString())
        // This build ships without a Minecraft.How-issued contract, so Google cannot start a handoff.
        assertNull(MinecraftHowBrowserConnection.configuredContract())
        assertNull(MinecraftHowBrowserConnection.configuredContract("client", "", "/exchange"))
        assertNull(MinecraftHowBrowserConnection.configuredContract("client", "https://evil.example/a", "/exchange"))
        assertNotNull(MinecraftHowBrowserConnection.configuredContract("fixture-client", "/fixture/authorize", "/fixture/exchange"))
    }
}

class MinecraftHowNetworkCheckTest {
    @Test fun distinguishesBlockedCdnFromOffline() {
        assertEquals(MinecraftHowNetworkCheck.Result.OK, MinecraftHowNetworkCheck.check { true })
        assertEquals(MinecraftHowNetworkCheck.Result.CDN_UNRESOLVED, MinecraftHowNetworkCheck.check { it != MinecraftHowNetworkCheck.CDN_HOST })
        assertEquals(MinecraftHowNetworkCheck.Result.OFFLINE, MinecraftHowNetworkCheck.check { false })
    }
}
