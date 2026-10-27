package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftHowDnsSnapshotTest {
    private val cookie = "laravel_session=fixture; XSRF-TOKEN=fixture-token"
    private val hostname = "example-server.minecraft.co.com"
    private val accountId = 9L
    private val domainId = 73L

    @Test
    fun readsOwnedHostnameAndCompleteRecordsUsingOnlyGetRequests() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when (request.path) {
                "/dashboard/dns" -> HttpResponse(200, body = indexPage(accountId, ownedDomain()))
                "/dashboard/dns/$domainId" -> HttpResponse(200, body = detailPage(accountId, domainId, hostname, completeRecords()))
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.readDnsRecordSnapshot(cookie, hostname)

        assertEquals(MinecraftHowClient.DnsSnapshotState.READ, result.state)
        assertEquals(accountId, result.accountId)
        assertEquals(domainId, result.domainId)
        assertEquals(hostname, result.hostname)
        assertEquals(2, result.records.size)
        assertEquals("CNAME", result.records[0].type)
        assertEquals("relay.example.net", result.records[0].content)
        assertEquals(600, result.records[0].ttl)
        assertEquals("_minecraft._tcp", result.records[1].name)
        assertEquals(0, result.records[1].priority)
        assertEquals(25571, result.records[1].port)
        assertTrue(requests.all { it.method == "GET" })
        assertEquals(listOf("/dashboard/dns", "/dashboard/dns/$domainId"), requests.map { it.path })
        assertTrue(requests.all { it.headers["Cookie"] == cookie })
        assertFalse(result.toString().contains(cookie))
    }

    @Test
    fun refusesRecordDetailsWhenAuthenticatedAccountChanges() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when (request.path) {
                "/dashboard/dns" -> HttpResponse(200, body = indexPage(accountId, ownedDomain()))
                "/dashboard/dns/$domainId" -> HttpResponse(200, body = detailPage(accountId + 1, domainId, hostname, completeRecords()))
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.readDnsRecordSnapshot(cookie, hostname)

        assertEquals(MinecraftHowClient.DnsSnapshotState.FAILED, result.state)
        assertEquals(hostname, result.hostname)
        assertTrue(result.records.isEmpty())
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test
    fun missingHostnameStopsAfterOwnedListReadWithoutWrites() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            assertEquals("GET", request.method)
            assertEquals("/dashboard/dns", request.path)
            HttpResponse(200, body = indexPage(accountId, ""))
        })

        val result = client.readDnsRecordSnapshot(cookie, hostname)

        assertEquals(MinecraftHowClient.DnsSnapshotState.NOT_FOUND, result.state)
        assertEquals(accountId, result.accountId)
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.method != "GET" })
    }

    @Test
    fun incompleteRecordListFailsClosedAndReturnsNoPartialSnapshot() {
        val requests = mutableListOf<HttpRequest>()
        val client = MinecraftHowClient(HttpTransport { request ->
            requests += request
            when (request.path) {
                "/dashboard/dns" -> HttpResponse(200, body = indexPage(accountId, ownedDomain()))
                "/dashboard/dns/$domainId" -> HttpResponse(200, body = detailPage(
                    accountId, domainId, hostname,
                    """[{"id":1,"type":"CNAME","name":null,"ttl":600}]""",
                ))
                else -> error("Unexpected request ${request.method} ${request.path}")
            }
        })

        val result = client.readDnsRecordSnapshot(cookie, hostname)

        assertEquals(MinecraftHowClient.DnsSnapshotState.FAILED, result.state)
        assertTrue(result.records.isEmpty())
        assertTrue(requests.all { it.method == "GET" })
    }

    private fun ownedDomain(): String =
        """{"id":$domainId,"domain":"$hostname"}"""

    private fun indexPage(userId: Long, domain: String): String {
        val data = if (domain.isEmpty()) "" else domain
        val total = if (domain.isEmpty()) 0 else 1
        return """{"component":"Dns/Index","props":{"auth":{"user":{"id":$userId}},"domains":{"data":[$data],"current_page":1,"last_page":1,"total":$total}},"url":"/dashboard/dns","version":"fixture"}"""
    }

    private fun detailPage(userId: Long, id: Long, name: String, records: String): String =
        """{"component":"Dns/Show","props":{"auth":{"user":{"id":$userId}},"domain":{"id":$id,"domain":"$name","dns_records":$records}},"url":"/dashboard/dns/$id","version":"fixture"}"""

    private fun completeRecords(): String =
        """[{"id":1,"type":"CNAME","name":null,"content":"relay.example.net","ttl":600,"prio":null,"port":null},{"id":2,"type":"SRV","name":"_minecraft._tcp","content":"relay.example.net","ttl":600,"prio":0,"port":25571}]"""
}
