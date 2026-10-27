package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test

class MinecraftHowInertiaTest {
    @Test fun rawV3ScriptPreservesEntitiesInsideJsonStrings() {
        val bootstrap = MinecraftHowInertia.parseHtmlBootstrap("""<html><script data-page="app" type="application/json">{"version":"current","props":{"message":"literal &quot; and &amp;","auth":{"user":null}}}</script></html>""")
        assertNotNull(bootstrap)
        assertEquals("current", bootstrap!!.version)
        assertEquals("literal &quot; and &amp;", bootstrap.props["message"])
    }

    @Test fun legacyAttributeIsDecodedExactlyOnce() {
        val bootstrap = MinecraftHowInertia.parseHtmlBootstrap("""<div id="app" data-page="{&quot;version&quot;:&quot;old&quot;,&quot;props&quot;:{&quot;message&quot;:&quot;&amp;quot;&quot;}}"></div>""")
        assertEquals("&quot;", bootstrap!!.props["message"])
    }

    @Test fun incompleteAndAmbiguousBootstrapsFailClosed() {
        assertNull(MinecraftHowInertia.parseHtmlBootstrap("""<script data-page="app" type="application/json">{"version":"v"}</script>"""))
        val script = """<script data-page="app" type="application/json">{"version":"v","props":{}}</script>"""
        assertNull(MinecraftHowInertia.parseHtmlBootstrap(script + script))
        assertNull(MinecraftHowInertia.parseHtmlBootstrap(script.replace("application/json", "text/javascript")))
    }

    @Test fun getVersionConflictBootstrapsHtmlAndRetainsVersionAcrossRedirect() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when (requests.size) {
                1 -> HttpResponse(409, mapOf("X-Inertia-Location" to listOf("https://minecraft.how/dashboard/dns")))
                2 -> {
                    assertNull(request.headers["X-Inertia"])
                    assertEquals("fake=only", request.headers["Cookie"])
                    HttpResponse(200, body = bootstrap())
                }
                3 -> {
                    assertEquals("current", request.headers["X-Inertia-Version"])
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns?fresh=1")))
                }
                4 -> {
                    assertEquals("current", request.headers["X-Inertia-Version"])
                    HttpResponse(200, body = """{"version":"current","props":{"auth":{"user":{"id":9}},"domains":[]}}""")
                }
                else -> error("Unexpected request")
            }
        })
        assertTrue(client.verifySession("fake=only").signedIn)
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun offsiteVersionRefreshDoesNotSendCookieToAnotherOrigin() {
        var requests = 0
        val client = MinecraftHowClient(HttpTransport {
            requests++
            HttpResponse(409, mapOf("X-Inertia-Location" to listOf("https://evil.example/")))
        })
        assertFalse(client.verifySession("fake=only").signedIn)
        assertEquals(1, requests)
    }

    @Test fun mutationVersionConflictIsReconciledWithoutReplayingPost() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "POST" -> {
                    writes++
                    HttpResponse(409, mapOf("X-Inertia-Location" to listOf("/dashboard/dns")))
                }
                request.headers["X-Inertia"] == null -> HttpResponse(200, body = bootstrap())
                request.path == "/dashboard/dns/create" -> HttpResponse(200, body = """{"version":"current","props":{"parentDomains":[{"domain":"minecraft.co.com"}]}}""")
                else -> HttpResponse(200, body = """{"version":"current","props":{"auth":{"user":{"id":9}},"domains":[]}}""")
            }
        })
        val result = client.ensureHostnameAndRecords("fake=only; XSRF-TOKEN=fake", "fixture0test.minecraft.co.com", "relay.example.net", 20000, 20001)
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(1, writes)
    }

    private fun bootstrap() = """<script data-page="app" type="application/json">{"version":"current","props":{"auth":{"user":null}}}</script>"""
}
