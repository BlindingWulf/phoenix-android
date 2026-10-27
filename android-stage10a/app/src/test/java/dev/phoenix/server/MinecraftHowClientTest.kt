package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MinecraftHowClientTest {
    private val cookies = "laravel_session=fake; XSRF-TOKEN=csrf%2Bvalue"
    private val hostname = "fixture0test.minecraft.co.com"
    private val relay = "relay.example.net"
    private val domainId = 10001L

    @Test
    fun verifySessionReadsInertiaAuthAndOnlyUsesTheSuppliedSameOriginCookie() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            HttpResponse(200, body = indexPage("[]"))
        })

        val result = client.verifySession(cookies)

        assertTrue(result.signedIn)
        assertEquals(0, result.domainCount)
        assertEquals("GET", requests.single().method)
        assertEquals("/dashboard/dns", requests.single().path)
        assertEquals(cookies, requests.single().headers["Cookie"])
        assertEquals("true", requests.single().headers["X-Inertia"])
        assertEquals("XMLHttpRequest", requests.single().headers["X-Requested-With"])
    }

    @Test
    fun dnsWriteSessionRequiresCsrfCookieBeforeNetworkAllocationCanStart() {
        val client = MinecraftHowClient(HttpTransport { error("Write-session check must not make a request") })
        assertTrue(client.hasDnsWriteSession(cookies))
        assertFalse(client.hasDnsWriteSession("laravel_session=fake"))
        assertFalse(client.hasDnsWriteSession("XSRF-TOKEN="))
    }

    @Test
    fun refreshedSessionCookiesReturnOnlyValidatedCookiesIssuedToOneSameOriginGet() {
        val issued = listOf(
            "XSRF-TOKEN=fresh%3D; Max-Age=7200; Path=/; Secure; SameSite=Lax",
            "fixture_session=fake; Max-Age=7200; Path=/; Secure; HttpOnly; SameSite=Lax",
        )
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            HttpResponse(200, mapOf("Set-Cookie" to issued), indexPage("[]"))
        })
        assertEquals(issued, client.refreshedSessionCookies("fixture_session=fake"))
        assertEquals("GET", requests.single().method)
        assertEquals("/dashboard/dns", requests.single().path)
        assertEquals("fixture_session=fake", requests.single().headers["Cookie"])

        val stale = "fixture_session=fake"
        assertNull(MinecraftHowClient(HttpTransport { HttpResponse(200, body = indexPage("[]")) }).refreshedSessionCookies(stale))
        assertNull(MinecraftHowClient(HttpTransport { HttpResponse(302, mapOf("Set-Cookie" to issued, "Location" to listOf("/login"))) })
            .refreshedSessionCookies(stale))
        assertNull(MinecraftHowClient(HttpTransport { HttpResponse(200, mapOf("Set-Cookie" to issued.map { it.replace(" Secure;", "") }), indexPage("[]")) })
            .refreshedSessionCookies(stale))
        assertNull(MinecraftHowClient(HttpTransport { throw IOException("fixture offline") }).refreshedSessionCookies(stale))
        assertNull(MinecraftHowClient(HttpTransport { error("A blank cookie must not make a request") }).refreshedSessionCookies(""))
    }

    @Test
    fun nativeProvisionUsesObservedRoutesAndPayloadsAndReconcilesEachWrite() {
        var domainExists = false
        val records = mutableListOf<Map<String, Any?>>()
        val posts = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" ->
                    HttpResponse(200, body = indexPage(if (domainExists) domainSummary(domainId, hostname) else "[]"))
                request.method == "GET" && request.path == "/dashboard/dns/create" ->
                    HttpResponse(200, body = """{"props":{"auth":{"user":{"id":9}},"parentDomains":[{"domain":"minecraft.co.com"},{"domain":"cs2.my"}]}}""")
                request.method == "POST" && request.path == "/dashboard/dns" -> {
                    posts += request
                    assertEquals("domain=$hostname", request.body)
                    assertEquals("application/x-www-form-urlencoded;charset=UTF-8", request.headers["Content-Type"])
                    assertEquals("csrf+value", request.headers["X-XSRF-TOKEN"])
                    domainExists = true
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId")))
                }
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" ->
                    HttpResponse(200, body = showPage(domainId, hostname, records))
                request.method == "POST" && request.path == "/dashboard/dns/$domainId/records" -> {
                    posts += request
                    val fields = decodeForm(request.body.orEmpty())
                    if (fields["type"] == "CNAME") {
                        assertEquals("", fields["name"])
                        assertEquals(relay, fields["content"])
                        assertEquals("600", fields["ttl"])
                        assertEquals("", fields["prio"])
                        assertFalse(fields.containsKey("port"))
                    } else {
                        assertEquals("SRV", fields["type"])
                        assertEquals("_minecraft._tcp", fields["name"])
                        assertEquals(relay, fields["content"])
                        assertEquals("600", fields["ttl"])
                        assertEquals("0", fields["prio"])
                        assertEquals("24718", fields["port"])
                    }
                    records += fields.toRecordMap(records.size + 1L)
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId")))
                }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.SAVED, result.state)
        assertEquals(hostname, result.hostname)
        assertEquals(domainId, result.domainId)
        assertTrue(result.createdHostname)
        assertEquals(2, result.createdRecords)
        assertTrue(result.javaRecordsMatch)
        assertTrue(result.bedrockRecordsMatch)
        assertEquals(3, posts.size)
        assertEquals("POST", posts[0].method)
        assertEquals("/dashboard/dns", posts[0].path)
        assertEquals("POST", posts[1].method)
        assertEquals("POST", posts[2].method)
    }

    @Test
    fun existingMatchingRecordsAreIdempotentAndDoNotCreateDuplicates() {
        val existing = mutableListOf(
            recordMap(1, "CNAME", null, relay, null, null),
            recordMap(2, "SRV", "_minecraft._tcp", relay, 0, 24718),
        )
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" -> HttpResponse(200, body = showPage(domainId, hostname, existing))
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.SAVED, result.state)
        assertFalse(result.createdHostname)
        assertEquals(0, result.createdRecords)
        assertTrue(requests.none { it.method == "POST" || it.method == "PUT" || it.method == "DELETE" })
    }

    @Test
    fun conflictingExistingRouteIsNeverOverwrittenOrDuplicated() {
        val records = listOf(recordMap(1, "A", null, "198.51.100.10", null, null))
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" -> HttpResponse(200, body = showPage(domainId, hostname, records))
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.CONFLICT, result.state)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test
    fun uncertainRecordWriteIsReadBackAndNotBlindlyRetried() {
        var recordPostCount = 0
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" -> HttpResponse(200, body = showPage(domainId, hostname, emptyList()))
                request.method == "POST" && request.path == "/dashboard/dns/$domainId/records" -> {
                    recordPostCount++
                    throw IOException("simulated connection loss after request")
                }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(1, recordPostCount)
        assertTrue(requests.count { it.method == "GET" && it.path == "/dashboard/dns/$domainId" } >= 2)
    }

    @Test
    fun noAuthOrMissingCsrfNeverWrites() {
        var postCount = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") postCount++
            HttpResponse(200, body = """{"props":{"auth":{"user":null},"domains":[]}}""")
        })

        val result = client.ensureHostnameAndRecords("laravel_session=only", hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.NEEDS_SIGN_IN, result.state)
        assertEquals(0, postCount)
        assertFalse(client.verifySession("laravel_session=only").signedIn)
    }

    @Test
    fun zoneMustBeReturnedByTheAuthenticatedCreatePageBeforeHostnamePost() {
        var postCount = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage("[]"))
                request.method == "GET" && request.path == "/dashboard/dns/create" -> HttpResponse(200, body = """{"props":{"auth":{"user":{"id":9}},"parentDomains":[{"domain":"minecraft.co.com"}]}}""")
                request.method == "POST" -> { postCount++; HttpResponse(204) }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, "phoenix.cs2.my", relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.ZONE_UNAVAILABLE, result.state)
        assertEquals(0, postCount)
    }

    @Test
    fun creationUsesTheCurrentlyLoadedAuthenticatedZoneList() {
        val dynamicZone = "minecraft.net"
        val dynamicHostname = "phoenix.$dynamicZone"
        var domainExists = false
        val records = mutableListOf<Map<String, Any?>>()
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" ->
                    HttpResponse(200, body = indexPage(if (domainExists) domainSummary(domainId, dynamicHostname) else "[]"))
                request.method == "GET" && request.path == "/dashboard/dns/create" ->
                    HttpResponse(200, body = """{"props":{"auth":{"user":{"id":9}},"parentDomains":[{"domain":"$dynamicZone"}]}}""")
                request.method == "POST" && request.path == "/dashboard/dns" -> {
                    assertEquals("domain=$dynamicHostname", request.body)
                    domainExists = true
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId")))
                }
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" ->
                    HttpResponse(200, body = showPage(domainId, dynamicHostname, records))
                request.method == "POST" && request.path == "/dashboard/dns/$domainId/records" -> {
                    val fields = decodeForm(request.body.orEmpty())
                    records += fields.toRecordMap(records.size + 1L)
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId")))
                }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(
            cookies, dynamicHostname, relay, 24718, 19144, availableZones = listOf(dynamicZone),
        )

        assertEquals(MinecraftHowClient.EnsureState.SAVED, result.state)
        assertEquals(dynamicHostname, result.hostname)
        assertEquals(2, result.createdRecords)
    }

    @Test
    fun uncertainRecoveryOnlyReadsAndNeverCreatesMissingRecordsOrHostname() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage("[]"))
                request.method == "POST" -> { writes++; HttpResponse(204) }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(
            cookies, hostname, relay, 24718, 19144, allowMutations = false,
        )

        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(0, writes)
    }

    @Test
    fun savedHostnameSetupNeverCreatesANameThatIsNoLongerOwned() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            assertEquals("GET", request.method)
            assertEquals("/dashboard/dns", request.path)
            HttpResponse(200, body = indexPage("[]"))
        })

        val result = client.ensureHostnameAndRecords(
            cookies, hostname, relay, 24718, 19144, allowHostnameCreation = false,
        )

        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertTrue(result.detail.contains("will not create or reserve"))
        assertEquals(1, requests.size)
    }

    @Test
    fun authenticatedIndexWithoutOwnedDomainsListDoesNotCreateHostname() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") writes++
            HttpResponse(200, body = """{"props":{"auth":{"user":{"id":1}}}}""")
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(0, writes)
    }

    @Test
    fun ownedDomainWithoutRecordListDoesNotCreateDnsRecords() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" ->
                    HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" ->
                    HttpResponse(200, body = """{"props":{"auth":{"user":{"id":1}},"domain":{"id":$domainId,"domain":"$hostname"}}}""")
                request.method == "POST" -> { writes++; HttpResponse(204) }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144)

        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(0, writes)
    }

    @Test
    fun sameOriginRedirectIsFollowedButOffsiteRedirectIsRejectedBeforeAnyCookieIsSent() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            HttpResponse(303, mapOf("Location" to listOf("https://evil.example/dashboard/dns")))
        })

        val result = client.verifySession(cookies)

        assertFalse(result.signedIn)
        assertEquals(1, requests.size)
        assertEquals(cookies, requests.single().headers["Cookie"])
    }

    @Test
    fun malformedOwnedDomainEntryDoesNotPermitDuplicateCreation() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") writes++
            HttpResponse(200, body = indexPage("[{\"domain\":\"$hostname\"}]"))
        })
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED,
            client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144).state)
        assertEquals(0, writes)
    }

    @Test
    fun malformedRecordEntryDoesNotPermitAnotherRecordWrite() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") writes++
            if (request.path == "/dashboard/dns") HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
            else HttpResponse(200, body = """{"props":{"domain":{"id":$domainId,"dns_records":[{"type":"CNAME","name":null,"content":"$relay"}]}}}""")
        })
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED,
            client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144).state)
        assertEquals(0, writes)
    }

    @Test
    fun conflictingSystemRouteIsPreservedAndBlocksDuplicateRoute() {
        var writes = 0
        val client = MinecraftHowClient(HttpTransport { request ->
            if (request.method == "POST") writes++
            if (request.path == "/dashboard/dns") HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
            else HttpResponse(200, body = showPage(domainId, hostname, listOf(recordMap(1,"A",null,"198.51.100.10",null,null))).replace("\"is_system\":false", "\"is_system\":true"))
        })
        assertEquals(MinecraftHowClient.EnsureState.CONFLICT,
            client.ensureHostnameAndRecords(cookies, hostname, relay, 24718, 19144).state)
        assertEquals(0, writes)
    }

    @Test
    fun ensureTreatsAtSignAndFullyQualifiedNamesAsExistingRecordsAndNeverAddsAConflictingRoute() {
        val fixtureHost = "fixture.minecraft.co.com"
        val posts = mutableListOf<HttpRequest>()
        fun clientWith(records: List<Map<String, Any?>>) = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" ->
                    HttpResponse(200, body = indexPage(domainSummary(domainId, fixtureHost)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" ->
                    HttpResponse(200, body = showPage(domainId, fixtureHost, records))
                request.method == "POST" -> { posts += request; HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId"))) }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        // An apex A stored as "@" is an existing route: a CNAME beside it must never be written.
        val conflicting = clientWith(listOf(
            mapOf("id" to 1L, "type" to "A", "name" to "@", "content" to "198.51.100.4", "prio" to null, "port" to null),
        )).ensureHostnameAndRecords(cookies, fixtureHost, relay, 24718, 19144, allowHostnameCreation = false)
        assertEquals(MinecraftHowClient.EnsureState.CONFLICT, conflicting.state)
        assertTrue(posts.isEmpty())

        // Records named with the full hostname already match; nothing is re-written or duplicated.
        val matching = clientWith(listOf(
            mapOf("id" to 1L, "type" to "A", "name" to fixtureHost, "content" to "203.0.113.7", "prio" to null, "port" to null),
            mapOf("id" to 2L, "type" to "SRV", "name" to "_minecraft._tcp.$fixtureHost.", "content" to fixtureHost, "prio" to 0, "port" to 24718),
        )).ensureHostnameAndRecords(cookies, fixtureHost, "203.0.113.7", 24718, 19144, allowHostnameCreation = false)
        assertEquals(MinecraftHowClient.EnsureState.SAVED, matching.state)
        assertEquals(0, matching.createdRecords)
        assertTrue(posts.isEmpty())

        // A fully qualified SRV on a stale port is a conflict, not a missing record to add beside it.
        val stalePort = clientWith(listOf(
            mapOf("id" to 1L, "type" to "A", "name" to null, "content" to "203.0.113.7", "prio" to null, "port" to null),
            mapOf("id" to 2L, "type" to "SRV", "name" to "_minecraft._tcp.$fixtureHost", "content" to fixtureHost, "prio" to 0, "port" to 25565),
        )).ensureHostnameAndRecords(cookies, fixtureHost, "203.0.113.7", 24718, 19144, allowHostnameCreation = false)
        assertEquals(MinecraftHowClient.EnsureState.CONFLICT, stalePort.state)
        assertTrue(posts.isEmpty())
    }

    @Test
    fun expiredCsrfTokenOn419AsksForSignInAndIsNeverReplayed() {
        val fixtureHost = "fixture.minecraft.co.com"
        val posts = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" ->
                    HttpResponse(200, body = indexPage(domainSummary(domainId, fixtureHost)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" ->
                    HttpResponse(200, body = showPage(domainId, fixtureHost, emptyList()))
                request.method == "POST" -> { posts += request; HttpResponse(419, body = "Page Expired") }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.ensureHostnameAndRecords(cookies, fixtureHost, relay, 24718, 19144, allowHostnameCreation = false)

        assertEquals(MinecraftHowClient.EnsureState.NEEDS_SIGN_IN, result.state)
        assertEquals(1, posts.size)
        assertEquals("/dashboard/dns/$domainId/records", posts.single().path)
        assertEquals(0, result.createdRecords)
    }

    /** A fake Minecraft.How account whose record edits (form POST + _method=PUT) change the records it returns. */
    private fun editableAccount(records: MutableList<Map<String, Any?>>, writes: MutableList<Map<String, String>>, applyWrites: Boolean = true) =
        MinecraftHowClient(HttpTransport { request ->
            when {
                request.method == "GET" && request.path == "/dashboard/dns" -> HttpResponse(200, body = indexPage(domainSummary(domainId, hostname)))
                request.method == "GET" && request.path == "/dashboard/dns/$domainId" -> HttpResponse(200, body = showPage(domainId, hostname, records))
                request.method == "POST" && request.path.startsWith("/dashboard/dns/$domainId/records/") -> {
                    val fields = decodeForm(request.body.orEmpty())
                    assertEquals("PUT", fields["_method"])
                    assertEquals("csrf+value", request.headers["X-XSRF-TOKEN"])
                    writes += fields
                    val id = request.path.substringAfterLast('/')
                    val index = records.indexOfFirst { it["id"].toString() == id }
                    assertTrue("updates target an existing record", index >= 0)
                    if (applyWrites) records[index] = fields.toRecordMap(id.toLong())
                    HttpResponse(303, mapOf("Location" to listOf("/dashboard/dns/$domainId")))
                }
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

    @Test
    fun switchRouteEditsTheExistingRecordsInPlaceAndSwitchesBack() {
        val records = mutableListOf(
            recordMap(11, "CNAME", null, relay, null, null),
            recordMap(12, "SRV", "_minecraft._tcp", relay, 0, 24718),
        )
        val writes = mutableListOf<Map<String, String>>()
        val client = editableAccount(records, writes)

        val toUs = client.switchRoute(cookies, hostname, "203.0.113.7", 25565)
        assertEquals(MinecraftHowClient.EnsureState.SAVED, toUs.state)
        assertEquals(2, toUs.createdRecords)
        assertEquals(listOf("A", "SRV"), writes.map { it["type"] })
        assertEquals("203.0.113.7", writes[0]["content"])
        assertEquals(hostname, writes[1]["content"])
        assertEquals("25565", writes[1]["port"])
        assertEquals(listOf("11", "12"), records.map { it["id"].toString() })

        writes.clear()
        val back = client.switchRoute(cookies, hostname, relay, 24718)
        assertEquals(MinecraftHowClient.EnsureState.SAVED, back.state)
        assertEquals(listOf("CNAME", "SRV"), writes.map { it["type"] })
        assertEquals(relay, records[0]["content"])
        assertEquals(24718, records[1]["port"])

        writes.clear()
        assertEquals(MinecraftHowClient.EnsureState.SAVED, client.switchRoute(cookies, hostname, relay, 24718).state)
        assertTrue("an already-matching route makes no writes", writes.isEmpty())
    }

    @Test
    fun switchRouteToPlayitPointsTheNameAtBedrocksHostAndTheSrvAtJavasRealTarget() {
        val records = mutableListOf(
            recordMap(11, "CNAME", null, relay, null, null),
            recordMap(12, "SRV", "_minecraft._tcp", relay, 0, 24718),
        )
        val writes = mutableListOf<Map<String, String>>()
        val client = editableAccount(records, writes)

        val result = client.switchRoute(cookies, hostname, "bedrock-name.gl.at.ply.gg", 25565, javaSrvHost = "java-name.gl.at.ply.gg")

        assertEquals(MinecraftHowClient.EnsureState.SAVED, result.state)
        assertEquals(listOf("CNAME", "SRV"), writes.map { it["type"] })
        assertEquals("bedrock-name.gl.at.ply.gg", records[0]["content"])
        assertEquals("java-name.gl.at.ply.gg", records[1]["content"])
        assertEquals(25565, records[1]["port"])
        assertEquals(listOf("11", "12"), records.map { it["id"].toString() })
        // An SRV record can't point at an IP address; nothing is written for one.
        writes.clear()
        assertEquals(MinecraftHowClient.EnsureState.INVALID_INPUT,
            client.switchRoute(cookies, hostname, "bedrock-name.gl.at.ply.gg", 25565, javaSrvHost = "203.0.113.9").state)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun switchRouteLeavesAmbiguousRecordsAlone() {
        val records = mutableListOf(
            recordMap(11, "CNAME", null, relay, null, null),
            recordMap(13, "A", null, "198.51.100.1", null, null),
            recordMap(12, "SRV", "_minecraft._tcp", relay, 0, 24718),
        )
        val writes = mutableListOf<Map<String, String>>()
        val result = editableAccount(records, writes).switchRoute(cookies, hostname, "203.0.113.7", 25565)
        assertEquals(MinecraftHowClient.EnsureState.CONFLICT, result.state)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun switchRouteNeverReplaysAnUnconfirmedEdit() {
        val records = mutableListOf(
            recordMap(11, "CNAME", null, relay, null, null),
            recordMap(12, "SRV", "_minecraft._tcp", relay, 0, 24718),
        )
        val writes = mutableListOf<Map<String, String>>()
        val result = editableAccount(records, writes, applyWrites = false).switchRoute(cookies, hostname, "203.0.113.7", 25565)
        assertEquals(MinecraftHowClient.EnsureState.RECOVERY_REQUIRED, result.state)
        assertEquals(1, writes.size)
    }

    private fun indexPage(domains: String) =
        """{"component":"Dns/Index","props":{"auth":{"user":{"id":9}},"domains":$domains},"url":"/dashboard/dns","version":"test"}"""

    private fun domainSummary(id: Long, domain: String) = "[{\"id\":$id,\"domain\":\"$domain\"}]"

    private fun showPage(id: Long, domain: String, records: List<Map<String, Any?>>): String {
        val recordsJson = records.joinToString(",") { record ->
            val name = record["name"] as? String
            val priority = record["prio"] as? Int
            val port = record["port"] as? Int
            """{"id":${record["id"]},"type":"${record["type"]}","name":${name?.let { "\"$it\"" } ?: "null"},"content":"${record["content"]}","ttl":600,"prio":${priority ?: "null"},"port":${port ?: "null"},"is_system":false}"""
        }
        return """{"component":"Dns/Show","props":{"auth":{"user":{"id":9}},"domain":{"id":$id,"domain":"$domain","dns_records":[$recordsJson]}},"url":"/dashboard/dns/$id","version":"test"}"""
    }

    private fun recordMap(id: Int, type: String, name: String?, content: String, prio: Int?, port: Int?) = mapOf(
        "id" to id,
        "type" to type,
        "name" to name,
        "content" to content,
        "prio" to prio,
        "port" to port,
    )

    private fun Map<String, String>.toRecordMap(id: Long): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to getValue("type"),
        "name" to getValue("name").takeIf(String::isNotEmpty),
        "content" to getValue("content"),
        "prio" to get("prio")?.toIntOrNull(),
        "port" to get("port")?.toIntOrNull(),
    )

    private fun decodeForm(body: String): Map<String, String> = body.split('&').filter(String::isNotEmpty).associate { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
