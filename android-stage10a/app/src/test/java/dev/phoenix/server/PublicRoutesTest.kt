package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicRoutesTest {
    private val playitEndpoints = RouteEndpoints(
        java = Endpoint("java-name.gl.joinmc.link", null),
        bedrock = Endpoint("bedrock-name.gl.at.ply.gg", 6695),
        voice = Endpoint("voice-name.gl.at.ply.gg", 7712),
        javaSrv = Endpoint("java-name.gl.at.ply.gg", 25565),
    )

    private fun view(route: PublicRoute?, health: LinkHealth, endpoints: RouteEndpoints?, name: CustomName = CustomName()) = NetworkView(
        selected = route,
        providers = PublicRoute.entries.associateWith { ProviderState(it, selected = it == route, configured = it == route, health = if (it == route) health else LinkHealth.OFF,
            endpoints = if (it == route) endpoints else null) },
        name = name,
    )

    @Test fun aFreshInstallHasNoRouteAndExistingNetBirdInstallsKeepTheirs() {
        assertNull(PublicRoute.migrate(null, netBirdHasPorts = false, legacyPublicRoute = null))
        assertEquals(PublicRoute.NETBIRD_EU, PublicRoute.migrate(null, netBirdHasPorts = true, legacyPublicRoute = "eu"))
        assertEquals(PublicRoute.NETBIRD_REGIONAL, PublicRoute.migrate(null, netBirdHasPorts = true, legacyPublicRoute = "us"))
        assertEquals(PublicRoute.PLAYIT, PublicRoute.migrate("playit", netBirdHasPorts = true, legacyPublicRoute = "us"))
        assertTrue(PublicRoute.NETBIRD_EU.usesNetBird && PublicRoute.NETBIRD_REGIONAL.usesNetBird && !PublicRoute.PLAYIT.usesNetBird)
        assertFalse(PublicRoute.LOCAL.isPublic)
    }

    @Test fun endpointsParseHostsPortsAndIpv6AndRejectGarbage() {
        assertEquals(Endpoint("a.example", 123), Endpoint.parse("a.example:123"))
        assertEquals(Endpoint("a.example", null), Endpoint.parse("a.example"))
        assertEquals(Endpoint("2001:db8::1", 19132), Endpoint.parse("[2001:db8::1]:19132"))
        assertNull(Endpoint.parse("a.example:99999"))
        assertNull(Endpoint.parse("bad host:1"))
        assertNull(Endpoint.parse(""))
        assertEquals("a.example", Endpoint("a.example", null).text)
        assertTrue(Endpoint("203.0.113.9", 1).isIpAddress)
    }

    @Test fun noAddressIsShownUntilTheRouteIsConnected() {
        assertTrue(Addresses.options(view(null, LinkHealth.NOT_SET_UP, null)).isEmpty())
        assertTrue(Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTING, playitEndpoints)).isEmpty())
        assertTrue(Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.ERROR, playitEndpoints)).isEmpty())
        assertTrue(Addresses.options(view(PublicRoute.LOCAL, LinkHealth.CONNECTED, null)).isEmpty())
    }

    @Test fun playitOnlyShowsTheAssignedJavaNameWithoutPortAndBedrockWithItsPort() {
        val options = Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints))
        assertEquals(1, options.size)
        assertFalse(options[0].custom)
        assertEquals("java-name.gl.joinmc.link", options[0].java.text)
        assertEquals(Endpoint("bedrock-name.gl.at.ply.gg", 6695), options[0].bedrock)
        assertEquals("Playit.gg address", options[0].label)
    }

    @Test fun aVerifiedNameIsPrimaryAndTheProviderAddressStaysAsTheAlternative() {
        val name = CustomName(enabled = true, hostname = "fun.minecraft.co.com", health = NameHealth.ACTIVE,
            verifiedFor = playitEndpoints.fingerprint, bedrockVerified = true)
        val options = Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints, name))
        assertEquals(2, options.size)
        assertTrue(options[0].custom)
        assertEquals(Endpoint("fun.minecraft.co.com", null), options[0].java)
        // Bedrock has no SRV: the name plus the provider's real UDP port.
        assertEquals(Endpoint("fun.minecraft.co.com", 6695), options[0].bedrock)
        assertFalse(options[1].custom)
        assertEquals("java-name.gl.joinmc.link", options[1].java.text)
        val share = Addresses.shareText("Phoenix", options)
        assertTrue(share.contains("Java Edition: fun.minecraft.co.com"))
        assertTrue(share.contains("same server"))
        assertTrue(share.contains("Bedrock Edition: bedrock-name.gl.at.ply.gg  Port: 6695"))
    }

    @Test fun aNameVerifiedForOldAddressesFallsBackToTheProviderAtOnce() {
        val stale = CustomName(enabled = true, hostname = "fun.minecraft.co.com", health = NameHealth.ACTIVE,
            verifiedFor = RouteEndpoints(Endpoint("eu1.netbird.services", 20001), Endpoint("eu1.netbird.services", 20002), null).fingerprint)
        val options = Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints, stale))
        assertEquals(listOf(false), options.map { it.custom })
        val off = stale.copy(enabled = false, verifiedFor = playitEndpoints.fingerprint)
        assertEquals(listOf(false), Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints, off)).map { it.custom })
        val failing = stale.copy(health = NameHealth.ERROR, verifiedFor = playitEndpoints.fingerprint)
        assertEquals(listOf(false), Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints, failing)).map { it.custom })
    }

    @Test fun javaOnlyVerificationKeepsTheProvidersBedrockAddress() {
        val name = CustomName(enabled = true, hostname = "fun.minecraft.co.com", health = NameHealth.ACTIVE,
            verifiedFor = playitEndpoints.fingerprint, bedrockVerified = false)
        val custom = Addresses.options(view(PublicRoute.PLAYIT, LinkHealth.CONNECTED, playitEndpoints, name)).first()
        assertEquals(Endpoint("bedrock-name.gl.at.ply.gg", 6695), custom.bedrock)
    }

    @Test fun netBirdAndRegionalShowTheirOwnPortsAndAnyChangeInvalidatesTheFingerprint() {
        val eu = RouteEndpoints(Endpoint("eu1.netbird.services", 20001), Endpoint("eu1.netbird.services", 20002), Endpoint("eu1.netbird.services", 9471))
        val options = Addresses.options(view(PublicRoute.NETBIRD_EU, LinkHealth.CONNECTED, eu))
        assertEquals("eu1.netbird.services:20001", options.single().java.text)
        assertEquals(eu.java, eu.javaTarget)
        val regional = RouteEndpoints(Endpoint("203.0.113.7", 25565), Endpoint("203.0.113.7", 19132), null)
        assertEquals("203.0.113.7:25565", Addresses.options(view(PublicRoute.NETBIRD_REGIONAL, LinkHealth.CONNECTED, regional)).single().java.text)
        assertFalse(eu.fingerprint == eu.copy(voice = Endpoint("eu1.netbird.services", 9472)).fingerprint)
        assertEquals(playitEndpoints.javaSrv, playitEndpoints.javaTarget)
    }
}
