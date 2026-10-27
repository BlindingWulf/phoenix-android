package dev.phoenix.server

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.MinecraftStatusProbe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

class NameFrontDoorTest {
    private val player = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")
    private val logged = mutableListOf<String>()
    private val door = NameFrontDoor { synchronized(logged) { logged += it } }
    private var fakeServer: ServerSocket? = null

    @After fun close() { door.stop(); fakeServer?.close() }

    private fun frame(bytes: ByteArray) = FrontDoorProtocol.readFrame(ByteArrayInputStream(bytes), 4096)

    @Test fun handshakeAndHelloRoundTrip() {
        val hs = FrontDoorProtocol.parseHandshake(frame(FrontDoorProtocol.handshake("friends.example.com", 25565, FrontDoorProtocol.INTENT_LOGIN)))
        assertEquals(FrontDoorProtocol.Handshake(776, "friends.example.com", 25565, 2), hs)
        assertEquals(FrontDoorProtocol.Hello("Steve", player), FrontDoorProtocol.parseHello(frame(FrontDoorProtocol.hello("Steve", player))))
        assertNull("a status request isn't a hello", FrontDoorProtocol.parseHello(byteArrayOf(0x01)))
        assertNull("garbage isn't a handshake", FrontDoorProtocol.parseHandshake(byteArrayOf(0x00, 0x7F)))
    }

    @Test fun loginFinishedMatches262Layout() {
        // 26.2: profile (UUID, name, property count) then a session UUID; packet 0x02.
        val session = UUID(1, 2)
        val expected = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).run {
                writeByte(0x02)
                writeLong(player.mostSignificantBits); writeLong(player.leastSignificantBits)
                writeByte(5); write("Steve".toByteArray())
                writeByte(0)
                writeLong(1); writeLong(2)
            }
        }.toByteArray()
        assertArrayEquals(expected, frame(FrontDoorProtocol.loginFinished(FrontDoorProtocol.Hello("Steve", player), session)))
    }

    @Test fun transferMatches262Layout() {
        val packet = frame(FrontDoorProtocol.transfer("java-name.gl.at.ply.gg", 40095))
        assertEquals(0x0B, FrontDoorProtocol.packetId(packet))
        assertEquals(Endpoint("java-name.gl.at.ply.gg", 40095), FrontDoorProtocol.parseTransfer(packet))
        // Port is a VarInt: 40095 takes three bytes.
        assertEquals(1 + 1 + 27 + 3, packet.size)
    }

    /** A fake Minecraft server that answers one status request, as the real one does. */
    private fun fakeStatusServer(): Int {
        val socket = ServerSocket(0).also { fakeServer = it }
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val handshake = FrontDoorProtocol.parseHandshake(FrontDoorProtocol.readFrame(input, 1024))!!
                    check(handshake.intent == FrontDoorProtocol.INTENT_STATUS)
                    FrontDoorProtocol.readFrame(input, 16) // status request
                    val json = """{"version":{"name":"26.2","protocol":776},"players":{"max":50,"online":1},"description":"Phoenix Baseline"}""".toByteArray()
                    val body = ByteArrayOutputStream().also { FrontDoorProtocol.writeVarInt(it, 0); FrontDoorProtocol.writeVarInt(it, json.size); it.write(json) }.toByteArray()
                    client.getOutputStream().write(FrontDoorProtocol.prefixed(body))
                    client.getOutputStream().flush()
                }
            }
        }
        return socket.localPort
    }

    @Test fun serverListPingsReachTheServerUnderAnyName() {
        door.serverPort = fakeStatusServer()
        door.transferTo = Endpoint("java-name.gl.at.ply.gg", 40095)
        door.start(0)
        val status = MinecraftStatusProbe.query("127.0.0.1", door.localPort, "example-server.minecraft.co.com", 3000)
        assertTrue(status.contains("Phoenix Baseline") && status.contains("\"online\":1"))
    }

    @Test fun joinsAreHandedToPlayitWithoutReachingTheServer() {
        door.serverPort = 1 // nothing listens: a join must never be passed on
        door.transferTo = Endpoint("java-name.gl.at.ply.gg", 40095)
        door.start(0)
        assertEquals(Endpoint("java-name.gl.at.ply.gg", 40095),
            NameFrontDoor.checkTransfer("127.0.0.1", door.localPort, "example-server.minecraft.co.com", 3000))
        Thread.sleep(200)
        assertTrue("Phoenix's own check isn't reported as a player", synchronized(logged) { logged.isEmpty() })
        assertEquals(0, door.transfers)
    }

    @Test fun nothingIsTransferredWithoutPlayitsAddress() {
        door.serverPort = 1
        door.transferTo = null
        door.start(0)
        assertNull(NameFrontDoor.checkTransfer("127.0.0.1", door.localPort, "example-server.minecraft.co.com", 2000))
    }
}
