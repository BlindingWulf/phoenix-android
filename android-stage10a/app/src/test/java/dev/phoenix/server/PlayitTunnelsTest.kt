package dev.phoenix.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.playit.PlayitApiException
import java.util.UUID

class PlayitTunnelsTest {
    private val javaId = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bedrockId = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val voiceId = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val ownId = UUID.fromString("00000000-0000-0000-0000-00000000000d")
    private val tunnels = listOf(
        PlayitTunnelInfo(javaId, "Phoenix Java", "minecraft-java", "tcp", 25565, "java-name.gl.joinmc.link", pending = false),
        PlayitTunnelInfo(bedrockId, "Phoenix Bedrock", "minecraft-bedrock", "udp", 19132, "bedrock-name.gl.at.ply.gg:6695", pending = false),
        PlayitTunnelInfo(voiceId, "Phoenix Voice", null, "udp", 24454, "voice-name.gl.at.ply.gg:7712", pending = false),
        // The owner's own tunnel for something else on the same agent: never reused.
        PlayitTunnelInfo(ownId, "My other server", null, "udp", 24454, "other.gl.at.ply.gg:1111", pending = false),
    )

    @Test fun voiceIsWantedOnlyWithSimpleVoiceChatAndRealPortsAreUsed() {
        assertEquals(mapOf(PlayitRole.JAVA to 25570, PlayitRole.JAVA_NAME to 25570, PlayitRole.BEDROCK to 19133), PlayitTunnels.wanted(ServerPorts(25570, 19133, 0)))
        assertEquals(24460, PlayitTunnels.wanted(ServerPorts(25565, 19132, 24460))[PlayitRole.VOICE])
    }

    @Test fun existingTunnelsAreFoundBySavedIdThenNameThenPortAndOnlyOfTheRightKind() {
        assertEquals(javaId, PlayitTunnels.find(tunnels, PlayitRole.JAVA, null, 25565)?.id)
        assertEquals(javaId, PlayitTunnels.find(tunnels, PlayitRole.JAVA, null, 25570)?.id) // found by name; port updated in place
        assertEquals(bedrockId, PlayitTunnels.find(tunnels, PlayitRole.BEDROCK, bedrockId, 19140)?.id)
        assertEquals(voiceId, PlayitTunnels.find(tunnels, PlayitRole.VOICE, null, 24454)?.id)
        val withoutOurs = tunnels.filter { it.id != voiceId }
        assertNull("another UDP tunnel on the same port is not Phoenix's voice tunnel", PlayitTunnels.find(withoutOurs, PlayitRole.VOICE, null, 24454))
        assertNull(PlayitTunnels.find(tunnels.filter { it.tunnelType != "minecraft-bedrock" }, PlayitRole.BEDROCK, null, 19132))
        val stale = UUID.randomUUID()
        assertEquals("a saved ID that no longer exists falls back to matching", javaId, PlayitTunnels.find(tunnels, PlayitRole.JAVA, stale, 25565)?.id)
    }

    @Test fun customNameTunnelIsPhoenixsOwnRawTcpTunnelOnly() {
        val nameId = UUID.fromString("00000000-0000-0000-0000-00000000000e")
        val named = PlayitTunnelInfo(nameId, "Phoenix Java Name", null, "tcp", 25565, "name-tcp.gl.at.ply.gg:41000", pending = false)
        val ownTcp = PlayitTunnelInfo(ownId, "My web server", null, "tcp", 25565, "web.gl.at.ply.gg:2222", pending = false)
        assertEquals(nameId, PlayitTunnels.find(tunnels + ownTcp + named, PlayitRole.JAVA_NAME, null, 25565)?.id)
        assertNull("the owner's own TCP tunnel on the Java port is left alone", PlayitTunnels.find(tunnels + ownTcp, PlayitRole.JAVA_NAME, null, 25565))
        assertEquals("the raw tunnel is never taken for the Minecraft Java tunnel", javaId, PlayitTunnels.find(listOf(named) + tunnels, PlayitRole.JAVA, null, 25565)?.id)
    }

    @Test fun endpointsUsePlayitsAddressesAndNeverInventPorts() {
        val byRole = mapOf(PlayitRole.JAVA to tunnels[0], PlayitRole.BEDROCK to tunnels[1], PlayitRole.VOICE to tunnels[2])
        val endpoints = PlayitTunnels.endpoints(byRole, Endpoint("java-name.gl.at.ply.gg", 25565))
        assertEquals(Endpoint("java-name.gl.joinmc.link", null), endpoints.java)
        assertEquals(Endpoint("bedrock-name.gl.at.ply.gg", 6695), endpoints.bedrock)
        assertEquals(Endpoint("voice-name.gl.at.ply.gg", 7712), endpoints.voice)
        assertEquals(Endpoint("java-name.gl.at.ply.gg", 25565), endpoints.javaTarget)
        // Pending or disabled tunnels have no usable address yet.
        val pending = byRole + (PlayitRole.BEDROCK to tunnels[1].copy(pending = true)) + (PlayitRole.VOICE to tunnels[2].copy(disabledReason = "AccountOverLimit"))
        val partial = PlayitTunnels.endpoints(pending, null)
        assertNull(partial.bedrock); assertNull(partial.voice); assertNull("no SRV target is guessed", partial.javaTarget)
        // A UDP address without a port is unusable, so it isn't shown.
        assertNull(PlayitTunnels.endpoints(mapOf(PlayitRole.BEDROCK to tunnels[1].copy(displayAddress = "bedrock-name.gl.at.ply.gg")), null).bedrock)
    }

    @Test fun apiFailuresBecomeActionableMessagesWithoutProviderText() {
        fun message(code: PlayitApiException.Code) = PlayitTunnels.explain(PlayitApiException(code, 400), "the Bedrock tunnel")
        assertTrue(message(PlayitApiException.Code.INVALID_AGENT_CREDENTIAL).startsWith("Authentication required"))
        assertTrue(message(PlayitApiException.Code.PREMIUM_REQUIRED).contains("Premium") && message(PlayitApiException.Code.PREMIUM_REQUIRED).contains("never buys"))
        assertTrue(message(PlayitApiException.Code.TRANSPORT_FAILURE).startsWith("Unable to reach Playit"))
        assertTrue(message(PlayitApiException.Code.RATE_LIMITED).contains("slow down"))
        assertTrue(message(PlayitApiException.Code.TUNNEL_ALLOCATION_UNAVAILABLE).contains("Remove an unused tunnel"))
        assertTrue(message(PlayitApiException.Code.ACCOUNT_VERIFICATION_REQUIRED).contains("verify"))
    }

    @Test fun savedEndpointsRoundTrip() {
        val endpoints = RouteEndpoints(Endpoint("j.example", null), Endpoint("b.example", 6695), null, Endpoint("t.example", 25565), bedrockIp = "203.0.113.7")
        assertEquals(endpoints, PlayitManager.decodeEndpoints(PlayitManager.encodeEndpoints(endpoints)))
        assertNull(PlayitManager.decodeEndpoints("not json"))
    }

    @Test fun agentLifecycleIsReadFromItsIpcStateAndRevokedKeysAreRecognized() {
        val running = JSONObject("""{"type":"state","data":{"state":"running","data":{"version":"1.0.12","tunnels":[
            {"display_address":"java-name.gl.joinmc.link","destination":"127.0.0.1:25565","is_disabled":false,"disabled_reason":null},
            {"display_address":"bedrock-name.gl.at.ply.gg:6695","destination":"127.0.0.1:19132","is_disabled":true,"disabled_reason":"PortsOverLimit"}],
            "pending_tunnels":[{"id":"x","status_msg":"allocating"}],"notices":[],"account_status":"verified","agent_id":"a","login_link":null,"start_time":1}}}""")
        val state = PlayitAgent.parseLifecycle(running, PlayitAgent.State(PlayitAgent.Phase.STARTING), now = 1234L)
        assertEquals(PlayitAgent.Phase.RUNNING, state.phase)
        assertEquals(2, state.tunnels.size)
        assertNull(state.tunnels[0].disabledReason)
        assertEquals("PortsOverLimit", state.tunnels[1].disabledReason)
        assertEquals(1, state.pendingTunnels)
        assertEquals("verified", state.accountStatus)
        assertEquals(1234L, state.runningSince)
        assertEquals("staying connected keeps the original time", 1234L, PlayitAgent.parseLifecycle(running, state, now = 9999L).runningSince)

        val invalid = JSONObject("""{"type":"state","data":{"state":"has_invalid_secret","data":{"code":"invalid_secret","message":"x","retryable":true,"details":null}}}""")
        assertEquals(PlayitAgent.Phase.INVALID_KEY, PlayitAgent.parseLifecycle(invalid, state, 0).phase)
        val waiting = JSONObject("""{"type":"state","data":{"state":"waiting_for_secret"}}""")
        assertEquals(PlayitAgent.Phase.INVALID_KEY, PlayitAgent.parseLifecycle(waiting, state, 0).phase)
        val overLimit = JSONObject("""{"type":"state","data":{"state":"disabled_over_limit","data":{"code":"agent_disabled_over_limit","message":"x","retryable":true}}}""")
        assertEquals(PlayitAgent.Phase.OVER_LIMIT, PlayitAgent.parseLifecycle(overLimit, state, 0).phase)
        val starting = JSONObject("""{"type":"state","data":{"state":"starting"}}""")
        assertEquals(PlayitAgent.Phase.STARTING, PlayitAgent.parseLifecycle(starting, state, 0).phase)
    }
}
