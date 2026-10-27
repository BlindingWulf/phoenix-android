package dev.phoenix.server

import org.phoenix.core.MinecraftStatusProbe
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The few Minecraft Java packets the front door speaks, for 26.2 (protocol 776). Packet IDs and layouts were taken from
 * the 26.2 server itself (its packet report and the packet classes); other protocol versions are never answered here.
 */
object FrontDoorProtocol {
    const val PROTOCOL = MinecraftStatusProbe.STATUS_PROTOCOL
    const val INTENT_STATUS = 1
    const val INTENT_LOGIN = 2
    private const val HANDSHAKE = 0x00
    private const val LOGIN_HELLO = 0x00
    const val LOGIN_FINISHED = 0x02
    const val LOGIN_ACKNOWLEDGED = 0x03
    const val CONFIG_TRANSFER = 0x0B

    data class Handshake(val protocol: Int, val host: String, val port: Int, val intent: Int)
    data class Hello(val name: String, val id: UUID)

    fun readVarInt(input: InputStream): Int {
        var value = 0
        for (i in 0 until 5) {
            val b = input.read()
            if (b < 0) throw EOFException()
            value = value or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return value
        }
        throw IOException("VarInt too long")
    }

    fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        do {
            val bits = v and 0x7F
            v = v ushr 7
            out.write(if (v != 0) bits or 0x80 else bits)
        } while (v != 0)
    }

    /** One length-prefixed packet (its ID and data), at most [max] bytes. */
    fun readFrame(input: InputStream, max: Int): ByteArray {
        val length = readVarInt(input)
        if (length < 1 || length > max) throw IOException("Packet size $length out of range")
        val frame = ByteArray(length)
        DataInputStream(input).readFully(frame)
        return frame
    }

    /** [frame] as it travels: its length, then the frame. */
    fun prefixed(frame: ByteArray): ByteArray = ByteArrayOutputStream(frame.size + 5).also { writeVarInt(it, frame.size); it.write(frame) }.toByteArray()

    fun packetId(frame: ByteArray): Int = readVarInt(ByteArrayInputStream(frame))

    private fun packet(id: Int, body: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeVarInt(out, id)
        body(out)
        return prefixed(bytes.toByteArray())
    }

    private fun readString(input: InputStream, maxChars: Int): String {
        val length = readVarInt(input)
        if (length < 0 || length > maxChars * 3) throw IOException("String too long")
        val bytes = ByteArray(length)
        DataInputStream(input).readFully(bytes)
        return String(bytes, Charsets.UTF_8).also { if (it.length > maxChars) throw IOException("String too long") }
    }

    private fun writeString(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun writeUuid(out: DataOutputStream, id: UUID) { out.writeLong(id.mostSignificantBits); out.writeLong(id.leastSignificantBits) }

    fun parseHandshake(frame: ByteArray): Handshake? = runCatching {
        val input = DataInputStream(ByteArrayInputStream(frame))
        if (readVarInt(input) != HANDSHAKE) return null
        Handshake(readVarInt(input), readString(input, 255), input.readUnsignedShort(), readVarInt(input))
    }.getOrNull()

    fun parseHello(frame: ByteArray): Hello? = runCatching {
        val input = DataInputStream(ByteArrayInputStream(frame))
        if (readVarInt(input) != LOGIN_HELLO) return null
        Hello(readString(input, 16), UUID(input.readLong(), input.readLong()))
    }.getOrNull()

    fun handshake(host: String, port: Int, intent: Int): ByteArray = packet(HANDSHAKE) {
        writeVarInt(it, PROTOCOL); writeString(it, host); it.writeShort(port); writeVarInt(it, intent)
    }

    fun hello(name: String, id: UUID): ByteArray = packet(LOGIN_HELLO) { writeString(it, name); writeUuid(it, id) }

    fun loginAcknowledged(): ByteArray = packet(LOGIN_ACKNOWLEDGED) {}

    /** Login finished without encryption: the player's own profile (no properties), then a session ID (new in 26.x). */
    fun loginFinished(hello: Hello, session: UUID): ByteArray = packet(LOGIN_FINISHED) {
        writeUuid(it, hello.id); writeString(it, hello.name); writeVarInt(it, 0); writeUuid(it, session)
    }

    /** Minecraft's own Transfer (configuration state): the client reconnects to [host]:[port] by itself. */
    fun transfer(host: String, port: Int): ByteArray = packet(CONFIG_TRANSFER) { writeString(it, host); writeVarInt(it, port) }

    fun parseTransfer(frame: ByteArray): Endpoint? = runCatching {
        val input = DataInputStream(ByteArrayInputStream(frame))
        if (readVarInt(input) != CONFIG_TRANSFER) return null
        Endpoint(readString(input, 255), readVarInt(input))
    }.getOrNull()
}

/**
 * The custom name's front door for Playit + NetBird + Minecraft.How. Playit Free answers Java only under its own name,
 * and Java's server list pings with the name the player typed, so the name's SRV leads here instead, through a NetBird
 * TCP service (NetBird doesn't check names):
 *  - server-list pings are passed straight to the Minecraft server, so the list shows its name, note and players;
 *  - a 26.2 join gets Minecraft's own Transfer to Playit's Java address, so the game itself runs through Playit.
 * Anything else (another game version, an existing transfer) is passed to the server unchanged.
 */
class NameFrontDoor(private val log: (String) -> Unit) {
    /** Where joins are sent: Playit's own Java address (its name and port). Nothing is transferred without it. */
    @Volatile var transferTo: Endpoint? = null
    /** The Minecraft server's local Java port. */
    @Volatile var serverPort: Int = 25565
    @Volatile var transfers = 0; private set
    @Volatile private var listener: ServerSocket? = null
    private val workers = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) {
        Thread(it, "phoenix-front-door").apply { isDaemon = true }
    }

    val listening: Boolean get() = listener?.isClosed == false
    /** The port it listens on (useful when started on port 0), or 0 when closed. */
    val localPort: Int get() = listener?.takeIf { !it.isClosed }?.localPort ?: 0

    @Synchronized fun start(port: Int) {
        if (listening && listener?.localPort == port) return
        stop()
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 32) }
        listener = socket
        Thread({ acceptLoop(socket) }, "phoenix-front-door-accept").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        listener?.let { runCatching { it.close() } }
        listener = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (closed: IOException) { break }
            try { workers.execute { client.use { runCatching { handle(it) } } } }
            catch (busy: RejectedExecutionException) { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val first = FrontDoorProtocol.readFrame(input, MAX_HANDSHAKE)
        val handshake = FrontDoorProtocol.parseHandshake(first) ?: return
        val target = transferTo
        if (handshake.intent == FrontDoorProtocol.INTENT_LOGIN && handshake.protocol == FrontDoorProtocol.PROTOCOL && target?.port != null) {
            val hello = FrontDoorProtocol.parseHello(FrontDoorProtocol.readFrame(input, 256)) ?: return
            out.write(FrontDoorProtocol.loginFinished(hello, UUID.randomUUID()))
            out.flush()
            // The client confirms and enters configuration, where Transfer is allowed. Its first packet is the confirmation.
            if (FrontDoorProtocol.packetId(FrontDoorProtocol.readFrame(input, 32_768)) != FrontDoorProtocol.LOGIN_ACKNOWLEDGED) return
            out.write(FrontDoorProtocol.transfer(target.host, target.port))
            out.flush()
            if (hello.name != SELF_CHECK_NAME) {
                transfers++
                log("${hello.name} joined through your custom name and was handed to Playit")
            }
            // Let the client act on it and close first.
            runCatching { client.soTimeout = 5_000; while (input.read() >= 0) Unit }
            return
        }
        splice(client, input, first, if (handshake.intent == FrontDoorProtocol.INTENT_STATUS) 15_000 else 60_000)
    }

    /** Passes the connection to the local server unchanged: the handshake already read, then both directions. */
    private fun splice(client: Socket, input: InputStream, handshake: ByteArray, idleMs: Int) {
        Socket().use { upstream ->
            upstream.connect(InetSocketAddress("127.0.0.1", serverPort), 3_000)
            upstream.tcpNoDelay = true
            upstream.soTimeout = idleMs
            client.soTimeout = idleMs
            val toServer = upstream.getOutputStream()
            toServer.write(FrontDoorProtocol.prefixed(handshake))
            toServer.flush()
            val back = Thread({
                runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
                runCatching { client.shutdownOutput() }
            }, "phoenix-front-door-pipe").apply { isDaemon = true; start() }
            runCatching { input.copyTo(toServer) }
            runCatching { upstream.shutdownOutput() }
            back.join(idleMs.toLong())
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 16
        private const val MAX_HANDSHAKE = 1_024
        /** The name Phoenix's own end-to-end check logs in with; it is never reported as a player. */
        const val SELF_CHECK_NAME = "PhoenixCheck"

        /**
         * Phoenix's own end-to-end check of the join path from outside: logs in at [host]:[port] naming [nameInHandshake]
         * like a player's client and returns where the front door transferred it, or null. Nothing reaches Minecraft.
         */
        fun checkTransfer(host: String, port: Int, nameInHandshake: String, timeoutMs: Int): Endpoint? = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val input = BufferedInputStream(socket.getInputStream())
                out.write(FrontDoorProtocol.handshake(nameInHandshake, port, FrontDoorProtocol.INTENT_LOGIN))
                out.write(FrontDoorProtocol.hello(SELF_CHECK_NAME, UUID(0, 1)))
                out.flush()
                if (FrontDoorProtocol.packetId(FrontDoorProtocol.readFrame(input, 4_096)) != FrontDoorProtocol.LOGIN_FINISHED) return null
                out.write(FrontDoorProtocol.loginAcknowledged())
                out.flush()
                FrontDoorProtocol.parseTransfer(FrontDoorProtocol.readFrame(input, 1_024))
            }
        }.getOrNull()
    }
}
