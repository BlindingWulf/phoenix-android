package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AuthoritativeDnsLookupTest {
    private val zone = "minecraft.co.com"
    private val hostname = "gaming.minecraft.co.com"
    private val srvName = "_minecraft._tcp.$hostname"
    private val ns1 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private val ns2 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))

    /** The phone's resolver: knows the zone's name servers, but still repeats the "no SRV" it cached before the record existed. */
    private val cachingResolver = DnsRecordLookup { name, type ->
        when {
            name == zone && type == MinecraftHowDns.TYPE_NS -> DnsMessage(0, listOf(
                DnsRecord(zone, type, target = "maceio.ns.porkbun.com."), DnsRecord(zone, type, target = "curitiba.ns.porkbun.com.")))
            name == hostname && type == MinecraftHowDns.TYPE_A -> DnsMessage(0, listOf(DnsRecord(hostname, type, address = "147.185.221.215")))
            else -> DnsMessage(0, emptyList(), negativeTtlSeconds = 468)
        }
    }
    private val zoneServer = DnsRecordLookup { name, type ->
        when {
            name == hostname && type == MinecraftHowDns.TYPE_A -> DnsMessage(0, listOf(DnsRecord(hostname, type, address = "147.185.221.215")))
            name == srvName && type == MinecraftHowDns.TYPE_SRV -> DnsMessage(0, listOf(DnsRecord(srvName, type, target = "example-tunnel.tun.ply.gg.", port = 45360)))
            else -> DnsMessage(0, emptyList(), negativeTtlSeconds = 1800)
        }
    }
    private fun resolveNs(host: String) = listOf(if (host.startsWith("curitiba")) ns1 else ns2)

    @Test
    fun picksTheMostSpecificZone() {
        assertEquals("minecraft.co.com", AuthoritativeDnsLookup.zoneOf("Gaming.Minecraft.co.com.", MinecraftHowDns.supportedSuffixes))
        assertEquals("minecraft.how", AuthoritativeDnsLookup.zoneOf("x.minecraft.how", listOf("how", "minecraft.how")))
        assertNull(AuthoritativeDnsLookup.zoneOf("example.com", MinecraftHowDns.supportedSuffixes))
    }

    @Test
    fun zoneNamesGoToTheZonesServersAndOthersToTheResolver() {
        val asked = mutableListOf<String>()
        val lookup = AuthoritativeDnsLookup(zone, cachingResolver, serverFor = { address ->
            DnsRecordLookup { name, type -> asked += "${address.hostAddress} $name"; zoneServer.query(name, type) } }, resolve = ::resolveNs)

        val srv = lookup.query(srvName, MinecraftHowDns.TYPE_SRV)
        assertEquals(45360, srv.answers.single().port)
        assertTrue(lookup.reached)
        // Name servers are tried in a fixed order (sorted by name): curitiba first.
        assertEquals(listOf("10.0.0.1 $srvName"), asked)

        val outside = lookup.query("example-tunnel.tun.ply.gg", MinecraftHowDns.TYPE_A)
        assertEquals(1, asked.size)
        assertTrue(outside.answers.isEmpty())
    }

    /** The case from 2026-10-12: Minecraft.How publishes the right records, the phone's network still says "no SRV". */
    @Test
    fun recordsAreLiveAtTheSourceWhileTheNetworkStillRemembersNoSrv() {
        fun check(lookup: DnsRecordLookup) = MinecraftHowDns(lookup).validate(hostname, "147.185.221.215", 45360, 45361,
            javaSrvHost = "example-tunnel.tun.ply.gg")
        val source = check(AuthoritativeDnsLookup(zone, cachingResolver, serverFor = { zoneServer }, resolve = ::resolveNs))
        assertEquals(MinecraftHowDns.State.VERIFIED, source.state)

        val network = check(cachingResolver)
        assertEquals(MinecraftHowDns.State.UNKNOWN, network.state)
        assertTrue(network.bedrockDnsMatches)
        assertTrue(network.detail, network.detail.contains("isn't visible yet"))
        assertEquals(468, network.negativeTtlSeconds)
    }

    @Test
    fun aServerThatDoesNotAnswerFallsToTheNextAndNoneMeansNotReached() {
        val refused = DnsRecordLookup { _, _ -> DnsMessage(5, emptyList()) }
        val silent = DnsRecordLookup { _, _ -> DnsMessage(-1, emptyList()) }
        val second = AuthoritativeDnsLookup(zone, cachingResolver, serverFor = { if (it == ns1) refused else zoneServer }, resolve = ::resolveNs)
        assertEquals(1, second.query(hostname, MinecraftHowDns.TYPE_A).answers.size)
        assertTrue(second.reached)

        val none = AuthoritativeDnsLookup(zone, cachingResolver, serverFor = { silent }, resolve = ::resolveNs)
        assertEquals(-1, none.query(hostname, MinecraftHowDns.TYPE_A).responseCode)
        assertFalse(none.reached)

        // No name servers known (the resolver couldn't say): nothing is asked, nothing claimed.
        val unknown = AuthoritativeDnsLookup(zone, { _, _ -> DnsMessage(-1, emptyList()) }, serverFor = { zoneServer }, resolve = ::resolveNs)
        assertEquals(-1, unknown.query(hostname, MinecraftHowDns.TYPE_A).responseCode)
        assertFalse(unknown.reached)
    }
}
