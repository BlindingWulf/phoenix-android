package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DnsWireTest {
    @Test
    fun parsesAaaaCnameAndSrvAnswers() {
        val hostname = "friend.minecraft.how"
        val relay = "relay.openport.pw"
        val srvName = "_minecraft._tcp.$hostname"
        val answers = listOf(
            resourceRecord(hostname, MinecraftHowDns.TYPE_A, byteArrayOf(198.toByte(), 51, 100, 9)),
            resourceRecord(hostname, MinecraftHowDns.TYPE_AAAA, byteArrayOf(
                0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 9,
            )),
            resourceRecord(hostname, MinecraftHowDns.TYPE_CNAME, encodeName(relay)),
            resourceRecord(srvName, MinecraftHowDns.TYPE_SRV, srvData(relay, 25573)),
        )
        val packet = response(answers)

        val parsed = DnsWire.parse(packet, callbackRcode = 0)

        assertEquals(0, parsed.responseCode)
        assertEquals(4, parsed.answers.size)
        assertTrue(parsed.answers.any { it.type == MinecraftHowDns.TYPE_A && it.address == "198.51.100.9" })
        assertTrue(parsed.answers.any { it.type == MinecraftHowDns.TYPE_AAAA && it.address?.contains("2001:db8") == true })
        assertTrue(parsed.answers.any { it.type == MinecraftHowDns.TYPE_CNAME && it.target == relay })
        assertTrue(parsed.answers.any { it.type == MinecraftHowDns.TYPE_SRV && it.target == relay && it.port == 25573 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedDnsHeader() {
        DnsWire.parse(byteArrayOf(0, 1, 2), callbackRcode = 0)
    }

    @Test
    fun parsesNameServerAnswers() {
        val zone = "minecraft.co.com"
        val packet = response(listOf(
            resourceRecord(zone, MinecraftHowDns.TYPE_NS, encodeName("curitiba.ns.porkbun.com")),
            resourceRecord(zone, MinecraftHowDns.TYPE_NS, encodeName("maceio.ns.porkbun.com")),
        ))

        val parsed = DnsWire.parse(packet, callbackRcode = -1)

        assertEquals(listOf("curitiba.ns.porkbun.com", "maceio.ns.porkbun.com"), parsed.answers.map { it.target })
        assertNull(parsed.negativeTtlSeconds)
    }

    /** The answer the owner's network gave on 2026-10-12: no SRV, and the zone's SOA saying how long to remember that. */
    @Test
    fun emptyAnswerReportsTheZonesNegativeCachingTime() {
        val soa = resourceRecord("minecraft.co.com", 6, soaData(minimum = 1800), ttl = 468)
        val nodata = DnsWire.parse(response(emptyList(), authority = listOf(soa)), callbackRcode = -1)
        assertEquals(0, nodata.responseCode)
        assertTrue(nodata.answers.isEmpty())
        assertEquals(468, nodata.negativeTtlSeconds)

        // The smaller of the record's TTL and the SOA minimum wins (RFC 2308).
        val fresh = DnsWire.parse(response(emptyList(), authority = listOf(resourceRecord("minecraft.co.com", 6, soaData(minimum = 300), ttl = 3600))), -1)
        assertEquals(300, fresh.negativeTtlSeconds)

        // NXDOMAIN carries the code from the packet when the resolver gave none.
        val nxdomain = DnsWire.parse(response(emptyList(), authority = listOf(soa), flags = 0x8183), callbackRcode = -1)
        assertEquals(3, nxdomain.responseCode)
    }

    @Test
    fun buildsNonRecursiveQueries() {
        val query = DnsWire.query(0x4242, "_minecraft._tcp.gaming.minecraft.co.com", MinecraftHowDns.TYPE_SRV, recursionDesired = false)
        assertEquals(0x42, query[0].toInt()); assertEquals(0x42, query[1].toInt())
        assertEquals(0, query[2].toInt() and 0x01) // RD clear: a zone's own server answers only from its zone
        assertEquals(1, query[5].toInt()) // one question
        assertEquals("_minecraft", String(query, 13, 10, Charsets.US_ASCII))
        assertEquals(MinecraftHowDns.TYPE_SRV, ((query[query.size - 4].toInt() and 0xff) shl 8) or (query[query.size - 3].toInt() and 0xff))
        val recursive = DnsWire.query(1, "example.com", MinecraftHowDns.TYPE_A, recursionDesired = true)
        assertEquals(1, recursive[2].toInt() and 0x01)
    }

    private fun response(records: List<ByteArray>, authority: List<ByteArray> = emptyList(), flags: Int = 0x8180): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeShort(0x1234)
            data.writeShort(flags)
            data.writeShort(0)
            data.writeShort(records.size)
            data.writeShort(authority.size)
            data.writeShort(0)
            records.forEach(data::write)
            authority.forEach(data::write)
        }
        bytes.toByteArray()
    }

    private fun resourceRecord(owner: String, type: Int, rdata: ByteArray, ttl: Int = 300): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            data.write(encodeName(owner))
            data.writeShort(type)
            data.writeShort(1)
            data.writeInt(ttl)
            data.writeShort(rdata.size)
            data.write(rdata)
        }
        bytes.toByteArray()
    }

    private fun soaData(minimum: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            data.write(encodeName("curitiba.ns.porkbun.com"))
            data.write(encodeName("dns.cloudflare.com"))
            listOf(2417302282L.toInt(), 10000, 2400, 604800, minimum).forEach(data::writeInt)
        }
        bytes.toByteArray()
    }

    private fun srvData(target: String, port: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            data.writeShort(0)
            data.writeShort(0)
            data.writeShort(port)
            data.write(encodeName(target))
        }
        bytes.toByteArray()
    }

    private fun encodeName(name: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { data ->
            name.trimEnd('.').split('.').forEach { label ->
                val encoded = label.toByteArray(Charsets.US_ASCII)
                data.writeByte(encoded.size)
                data.write(encoded)
            }
            data.writeByte(0)
        }
        bytes.toByteArray()
    }
}
