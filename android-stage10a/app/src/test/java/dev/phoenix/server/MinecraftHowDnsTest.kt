package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MinecraftHowDnsTest {
    @Test
    fun normalizesAndAcceptsOnlySupportedMinecraftHowZones() {
        assertEquals("friend-hangout.minecraft.co.com", MinecraftHowDns.normalizeHostname(" Friend-Hangout.Minecraft.CO.COM. "))
        assertTrue(MinecraftHowDns.validateHostname("world.minecraft.how"))
        MinecraftHowDns.supportedSuffixes.forEach { zone ->
            assertTrue("expected $zone to be accepted", MinecraftHowDns.validateHostname("phoenix.$zone"))
        }
        assertTrue(MinecraftHowDns.validateHostname("phoenix.next-zone.test", listOf("next-zone.test")))
        assertFalse(MinecraftHowDns.validateHostname("bad_label.minecraft.co.com"))
        assertFalse(MinecraftHowDns.validateHostname("a.minecraft.co.com"))
        assertFalse(MinecraftHowDns.validateHostname("minecraft.co.com"))
        assertFalse(MinecraftHowDns.validateHostname("world.example.com"))
        assertEquals("friend-hangout.minecraft.co.com", MinecraftHowDns.composeHostname("friend-hangout", ".minecraft.co.com"))
        assertEquals("phoenix.cs2.my", MinecraftHowDns.composeHostname("phoenix", "cs2.my"))
        assertNull(MinecraftHowDns.composeHostname("-bad", "minecraft.co.com"))
        assertNull(MinecraftHowDns.composeHostname("a", "minecraft.co.com"))
    }

    @Test
    fun cnameAndJavaSrvMustBothMatchTheAllocatedRelay() {
        val hostname = "friend-hangout.minecraft.co.com"
        val relay = "allocated.openport.pw"
        val lookup = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_CNAME -> msg(DnsRecord(hostname, type, target = "$relay."))
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(
                    DnsRecord(name, type, target = "$relay.", port = 24718),
                )
                name == relay && type == MinecraftHowDns.TYPE_A -> msg(DnsRecord(relay, type, address = "198.51.100.9"))
                else -> DnsMessage(0, emptyList())
            }
        }

        val result = MinecraftHowDns(lookup).validate(hostname, relay, 24718, 24718)

        assertEquals(MinecraftHowDns.State.VERIFIED, result.state)
        assertTrue(result.javaDnsMatches)
        assertTrue(result.bedrockDnsMatches)
        assertEquals(hostname, result.javaAddress)
        assertEquals("$hostname:24718", result.bedrockAddress)
        // DNS alone never claims that players can join.
        assertEquals("DNS records point to your server.", result.detail)
        assertFalse(result.detail.contains("join"))
    }

    @Test
    fun playitSplitsBedrocksRouteRecordFromJavasSrvTarget() {
        val hostname = "friend-hangout.minecraft.co.com"
        val bedrockHost = "bedrock-name.gl.at.ply.gg"
        val javaHost = "java-name.gl.at.ply.gg"
        fun lookupWith(srvTarget: String, cname: String) = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_CNAME -> msg(DnsRecord(hostname, type, target = "$cname."))
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(DnsRecord(name, type, target = "$srvTarget.", port = 25565))
                else -> DnsMessage(0, emptyList())
            }
        }
        val good = MinecraftHowDns(lookupWith(javaHost, bedrockHost)).validate(hostname, bedrockHost, 25565, 6695, javaSrvHost = javaHost)
        assertEquals(MinecraftHowDns.State.VERIFIED, good.state)
        assertEquals("$hostname:6695", good.bedrockAddress)

        // An SRV still aimed at the Bedrock host would send Java to the wrong Playit name: not verified.
        val wrongSrv = MinecraftHowDns(lookupWith(bedrockHost, bedrockHost)).validate(hostname, bedrockHost, 25565, 6695, javaSrvHost = javaHost)
        assertEquals(MinecraftHowDns.State.UNKNOWN, wrongSrv.state)
        assertFalse(wrongSrv.javaDnsMatches)

        // Java alone matching (old CNAME still cached) is reported, but the name isn't used until Bedrock matches too.
        val staleRoute = MinecraftHowDns(lookupWith(javaHost, "old.relay.example")).validate(hostname, bedrockHost, 25565, 6695, javaSrvHost = javaHost)
        assertEquals(MinecraftHowDns.State.UNKNOWN, staleRoute.state)
        assertTrue(staleRoute.javaDnsMatches)
        assertFalse(staleRoute.bedrockDnsMatches)
    }

    @Test
    fun directIpv4AndIpv6RoutesCanMatchIndependentlyOfGameplayReachability() {
        val hostname = "friend-hangout.minecraft.co.com"
        val ipv4Lookup = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_A -> msg(DnsRecord(hostname, type, address = "198.51.100.9"))
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(
                    DnsRecord(name, type, target = "$hostname.", port = 24718),
                )
                else -> DnsMessage(0, emptyList())
            }
        }
        val ipv4 = MinecraftHowDns(ipv4Lookup).validate(hostname, "198.51.100.9", 24718, 19144)
        assertTrue(ipv4.javaDnsMatches)
        assertTrue(ipv4.bedrockDnsMatches)
        assertEquals("$hostname:19144", ipv4.bedrockAddress)

        val ipv6Lookup = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_AAAA -> msg(
                    DnsRecord(hostname, type, address = "2001:db8:0:0:0:0:0:10"),
                )
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(
                    DnsRecord(name, type, target = hostname, port = 24718),
                )
                else -> DnsMessage(0, emptyList())
            }
        }
        val ipv6 = MinecraftHowDns(ipv6Lookup).validate(hostname, "2001:db8::10", 24718, 19144)
        assertTrue(ipv6.javaDnsMatches)
        assertTrue(ipv6.bedrockDnsMatches)
    }

    @Test
    fun matchingAddressWithoutMatchingSrvIsUnknownAndDoesNotClaimAvailability() {
        val hostname = "friend-hangout.minecraft.co.com"
        val relay = "allocated.openport.pw"
        val lookup = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_A -> msg(DnsRecord(hostname, type, address = "198.51.100.9"))
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(
                    DnsRecord(name, type, target = relay, port = 24719),
                )
                name == relay && type == MinecraftHowDns.TYPE_A -> msg(DnsRecord(relay, type, address = "198.51.100.9"))
                else -> DnsMessage(0, emptyList())
            }
        }

        val result = MinecraftHowDns(lookup).validate(hostname, relay, 24718, 24718)

        assertEquals(MinecraftHowDns.State.UNKNOWN, result.state)
        assertFalse(result.javaDnsMatches)
        assertTrue(result.bedrockDnsMatches)
        assertEquals("$hostname:24718", result.bedrockAddress)
        assertTrue(result.detail.contains("Java SRV record needs port 24718"))
        // A wrong record says what it says now, so it can't be mistaken for one that isn't visible yet.
        assertTrue(result.detail, result.detail.contains("it says $relay:24719"))
        assertFalse(result.detail.contains("available"))
        assertNull(result.negativeTtlSeconds)
    }

    @Test
    fun oneMatchingRecordDoesNotHideConflictingPublishedRecords() {
        val hostname = "friend-hangout.minecraft.co.com"
        val relay = "allocated.openport.pw"
        val lookup = lookup { name, type ->
            when {
                name == hostname && type == MinecraftHowDns.TYPE_A -> msg(
                    DnsRecord(hostname, type, address = "198.51.100.9"),
                    DnsRecord(hostname, type, address = "198.51.100.10"),
                )
                name == "_minecraft._tcp.$hostname" && type == MinecraftHowDns.TYPE_SRV -> msg(
                    DnsRecord(name, type, target = relay, port = 24718),
                    DnsRecord(name, type, target = "other.example.net", port = 24718),
                )
                name == relay && type == MinecraftHowDns.TYPE_A -> msg(DnsRecord(relay, type, address = "198.51.100.9"))
                else -> DnsMessage(0, emptyList())
            }
        }

        val result = MinecraftHowDns(lookup).validate(hostname, relay, 24718, 19132)

        assertEquals(MinecraftHowDns.State.UNKNOWN, result.state)
        assertFalse(result.javaDnsMatches)
        assertFalse(result.bedrockDnsMatches)
    }

    @Test
    fun unmatchedDnsAndInvalidHostnameRemainUnknownRatherThanAvailable() {
        val hostname = "friend-hangout.minecraft.co.com"
        val relay = "allocated.openport.pw"
        val queryCount = AtomicInteger()
        val lookup = lookup { _, _ -> queryCount.incrementAndGet(); DnsMessage(3, emptyList()) }

        val unmatched = MinecraftHowDns(lookup).validate(hostname, relay, 24718, 24718)
        val invalid = MinecraftHowDns(lookup).validate("not-ours.example.com", relay, 24718, 24718)

        assertEquals(MinecraftHowDns.State.UNKNOWN, unmatched.state)
        assertFalse(unmatched.javaDnsMatches)
        assertTrue(unmatched.detail.contains("No CNAME record points to"))
        assertFalse(unmatched.detail.contains("available"))
        assertEquals(MinecraftHowDns.State.INVALID_HOSTNAME, invalid.state)
        assertEquals(6, queryCount.get())
    }

    @Test
    fun invalidRelayEndpointDoesNotPerformDnsQueries() {
        val queryCount = AtomicInteger()
        val lookup = lookup { _, _ -> queryCount.incrementAndGet(); DnsMessage(0, emptyList()) }

        val result = MinecraftHowDns(lookup).validate("friend.minecraft.how", "relay.openport.pw:24718", 24718, 24718)

        assertEquals(MinecraftHowDns.State.UNKNOWN, result.state)
        assertFalse(result.javaDnsMatches)
        assertEquals(0, queryCount.get())
    }

    private fun lookup(block: (String, Int) -> DnsMessage) = DnsRecordLookup(block)
    private fun msg(vararg records: DnsRecord) = DnsMessage(responseCode = 0, answers = records.toList())
}
