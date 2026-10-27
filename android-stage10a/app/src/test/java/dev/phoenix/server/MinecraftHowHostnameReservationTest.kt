package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MinecraftHowHostnameReservationTest {
    private val hostname = "friends.minecraft.co.com"
    private val cookie = "XSRF-TOKEN=fixture; session=fixture"
    private fun index(saved: Boolean = false, account: Int = 7, domains: String? = null) = HttpResponse(200, body =
        """{"version":"v","props":{"auth":{"user":{"id":$account}},"domains":${domains ?: if (saved) """[{"id":31,"domain":"$hostname"}]""" else "[]"}}}""")
    private fun offered(account: Int = 7) = HttpResponse(200, body =
        """{"version":"v","props":{"auth":{"user":{"id":$account}},"parentDomains":[{"domain":"minecraft.co.com"}]}}""")

    @Test fun checkingANameMakesOnlyReadsAndDoesNotClaimAvailability() {
        val calls = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            calls += request
            if (request.path.endsWith("/create")) offered() else index()
        })
        val result = client.checkHostname(cookie, hostname)
        assertEquals(MinecraftHowClient.EnsureState.CHECKED, result.state)
        assertTrue(result.detail.contains("availability is confirmed by the save"))
        assertTrue(calls.all { it.method == "GET" })
    }

    @Test fun hostnameCreationNeedsNoRelayAndCommitsBeforeTheSinglePost() {
        var committed = false
        var saved = false
        var posts = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "POST" -> {
                    assertTrue(committed)
                    assertEquals("/dashboard/dns", request.path)
                    assertEquals("domain=friends.minecraft.co.com", request.body)
                    posts++; saved = true; HttpResponse(200, body = "{}")
                }
                request.path.endsWith("/create") -> offered()
                else -> index(saved)
            }
        })
        val result = client.reserveHostname(cookie, hostname, beforeCreate = { account ->
            assertEquals(7L, account); committed = true; true
        })
        assertEquals(MinecraftHowClient.EnsureState.SAVED, result.state)
        assertEquals(31L, result.domainId)
        assertEquals(1, posts)
        assertEquals(MinecraftHowClient.EnsureState.SAVED, client.reserveHostname(cookie, hostname).state)
        assertEquals(1, posts)
    }

    @Test fun lostReplyIsConfirmedByReadbackWithoutAnotherWrite() {
        var saved = false
        var posts = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") { posts++; saved = true; throw IOException("lost reply") }
            if (request.path.endsWith("/create")) offered() else index(saved)
        })
        assertEquals(MinecraftHowClient.EnsureState.SAVED, client.reserveHostname(cookie, hostname).state)
        assertEquals(1, posts)
    }

    @Test fun unresolvedWriteAndRestorationNeverReplayThePost() {
        var posts = 0
        val transport = HttpTransport { request ->
            if (request.method == "POST") { posts++; throw IOException("unknown") }
            if (request.path.endsWith("/create")) offered() else index()
        }
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, MinecraftHowClient(transport).reserveHostname(cookie, hostname).state)
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED,
            MinecraftHowClient(transport).reserveHostname(cookie, hostname, allowCreate = false, pendingAccountId = 7).state)
        assertEquals(1, posts)
    }

    @Test fun providerValidationRejectionIsShownWithoutClaimingASave() {
        var posts = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") {
                posts++; HttpResponse(422, body = """{"errors":{"domain":["This hostname is already taken."]}}""")
            } else if (request.path.endsWith("/create")) offered() else index()
        })
        val result = client.reserveHostname(cookie, hostname)
        assertEquals(MinecraftHowClient.EnsureState.INVALID_INPUT, result.state)
        assertEquals("This hostname is already taken.", result.detail)
        assertEquals(1, posts)
    }

    @Test fun changedAccountCannotConfirmOrReconcileTheEarlierAccountsSave() {
        var wrote = false
        var posts = 0
        val transport = HttpTransport { request ->
            if (request.method == "POST") { posts++; wrote = true; HttpResponse(200, body = "{}") }
            else if (request.path.endsWith("/create")) offered()
            else index(saved = wrote, account = if (wrote) 9 else 7)
        }
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, MinecraftHowClient(transport).reserveHostname(cookie, hostname).state)
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED,
            MinecraftHowClient(transport).reserveHostname(cookie, hostname, allowCreate = false, pendingAccountId = 7).state)
        assertEquals(1, posts)
    }

    @Test fun incompleteAccountDataOrFailedJournalCommitPreventsCreatingAnything() {
        var posts = 0
        val malformed = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") posts++
            index(domains = "[{\"id\":1}]")
        })
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, malformed.reserveHostname(cookie, hostname).state)
        val normal = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") posts++
            if (request.path.endsWith("/create")) offered() else index()
        })
        assertEquals(MinecraftHowClient.EnsureState.FAILED, normal.reserveHostname(cookie, hostname, beforeCreate = { false }).state)
        assertEquals(0, posts)
    }
    @Test fun livePaginatedShapeCountsAndFindsOwnershipOnLaterPagesWithoutWriting() {
        val paths = mutableListOf<String>()
        val client = MinecraftHowClient(HttpTransport { request ->
            assertEquals("GET", request.method)
            paths += request.path
            val page = if (request.path.contains("page=2")) 2 else 1
            val data = if (page == 2) "[{\"id\":31,\"domain\":\"$hostname\"}]" else "[{\"id\":30,\"domain\":\"other.minecraft.co.com\"}]"
            index(domains = "{\"data\":$data,\"current_page\":$page,\"last_page\":2,\"total\":2}")
        })
        assertEquals(2, client.verifySession(cookie).domainCount)
        assertEquals(2, client.listDomains(cookie).domains.size)
        assertEquals(MinecraftHowClient.EnsureState.SAVED, client.reserveHostname(cookie, hostname).state)
        assertTrue(paths.contains("/dashboard/dns?page=2"))
    }

    @Test fun incompletePaginationAndAccountChangeAcrossPagesPreventWrites() {
        for (secondAccount in listOf(7, 9)) {
            val client = MinecraftHowClient(HttpTransport { request ->
                assertEquals("GET", request.method)
                val page = if (request.path.contains("page=2")) 2 else 1
                index(account = if (page == 2) secondAccount else 7,
                    domains = "{\"data\":[],\"current_page\":$page,\"last_page\":2,\"total\":1}")
            })
            assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, client.reserveHostname(cookie, hostname).state)
        }
    }

}
