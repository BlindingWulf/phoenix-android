package dev.phoenix.server

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * Minecraft 26.x's own management API: JSON-RPC 2.0 over a WebSocket at "/", authorized with the secret Minecraft writes
 * into server.properties (`Authorization: Bearer`). Phoenix turns it on for 127.0.0.1 only, without TLS, and uses it for
 * settings that have no console command, such as the simulation distance. One short connection per call.
 */
class ManagementClient(private val port: Int, private val secret: String, private val timeoutMs: Int = 5_000) {
    /** Calls [method] with named [params] and returns its "result", or throws with the server's error message. */
    fun call(method: String, params: JSONObject? = null): Any? = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream().buffered())
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
        out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer $secret\r\n\r\n").toByteArray())
        out.flush()
        val status = readHttpHead(input)
        if (!status.startsWith("HTTP/1.1 101")) throw IOException("Management API refused the connection: ${status.substringBefore('\r')}")
        val id = random.nextInt(1_000_000)
        val request = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) request.put("params", params)
        out.write(WebSocketFrames.text(request.toString(), random)); out.flush()
        // Notifications (players joining, saves) may arrive before the answer; only the frame with our id counts.
        repeat(20) {
            val frame = WebSocketFrames.read(input) ?: throw IOException("Management API closed the connection")
            val reply = runCatching { JSONObject(frame) }.getOrNull() ?: return@repeat
            if (reply.optInt("id", -1) != id) return@repeat
            reply.optJSONObject("error")?.let { throw IOException(it.optString("message", "Management API error")) }
            runCatching { out.write(WebSocketFrames.close(random)); out.flush() }
            return@use reply.opt("result")
        }
        throw IOException("Management API didn't answer")
    }

    private fun readHttpHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        while (head.size() < 8192) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            val bytes = head.toByteArray()
            if (bytes.size >= 4 && bytes[bytes.size - 4] == '\r'.code.toByte() && bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() && bytes[bytes.size - 1] == '\n'.code.toByte()) break
        }
        return head.toString(Charsets.UTF_8.name())
    }

    companion object {
        private val random = SecureRandom()
        const val SIMULATION_DISTANCE = "minecraft:serversettings/simulation_distance"
        const val SET_SIMULATION_DISTANCE = "minecraft:serversettings/simulation_distance/set"
    }
}

/** The client side of RFC 6455 framing that Phoenix needs: masked text frames out, unfragmented text frames in. */
object WebSocketFrames {
    fun text(payload: String, random: java.util.Random): ByteArray = frame(0x1, payload.toByteArray(Charsets.UTF_8), random)
    fun close(random: java.util.Random): ByteArray = frame(0x8, ByteArray(0), random)

    private fun frame(opcode: Int, data: ByteArray, random: java.util.Random): ByteArray {
        val out = ByteArrayOutputStream(data.size + 14)
        out.write(0x80 or opcode)
        when {
            data.size < 126 -> out.write(0x80 or data.size)
            data.size < 65_536 -> { out.write(0x80 or 126); out.write(data.size shr 8); out.write(data.size and 0xFF) }
            else -> { out.write(0x80 or 127); for (shift in 56 downTo 0 step 8) out.write(((data.size.toLong() shr shift) and 0xFF).toInt()) }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        out.write(mask)
        for (i in data.indices) out.write(data[i].toInt() xor mask[i % 4].toInt())
        return out.toByteArray()
    }

    /** The next text frame's payload, skipping pings and other control frames; null when the server closes. */
    fun read(input: DataInputStream, max: Int = 1_048_576): String? {
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val second = input.readUnsignedByte()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length < 0 || length > max) throw IOException("Management API frame too large")
            val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val data = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (first and 0x0F) {
                0x1 -> return String(data, Charsets.UTF_8)
                0x8 -> return null
                else -> Unit
            }
        }
    }
}

/**
 * When to ease off before Android throttles the CPU: one step lower simulation distance while the phone runs hot, and the
 * owner's own distance back once it has been cool for two minutes. Pure so the thresholds are unit tested.
 */
object ThermalPolicy {
    const val PREF = "thermal-ease-off"
    /** PowerManager headroom: 1.0 is where Android starts severe throttling. */
    const val HOT_HEADROOM = 0.85f
    const val COOL_HEADROOM = 0.6f
    const val COOL_FOR_MS = 120_000L
    const val MIN_DISTANCE = 3

    enum class Action { NONE, EASE_OFF, RESTORE }

    /**
     * [status] is PowerManager's thermal status (2 = moderate), [headroom] its forecast (NaN or null when the phone has
     * none), [eased] whether Phoenix lowered the distance, [coolSince] when the phone last became cool.
     */
    fun next(status: Int, headroom: Float?, eased: Boolean, coolSince: Long?, now: Long): Action {
        val known = headroom?.takeIf { !it.isNaN() }
        val hot = status >= 2 || (known != null && known >= HOT_HEADROOM)
        val cool = status <= 1 && (known == null || known < COOL_HEADROOM)
        return when {
            hot && !eased -> Action.EASE_OFF
            eased && cool && coolSince != null && now - coolSince >= COOL_FOR_MS -> Action.RESTORE
            else -> Action.NONE
        }
    }

    fun isCool(status: Int, headroom: Float?) = status <= 1 && (headroom?.takeIf { !it.isNaN() }?.let { it < COOL_HEADROOM } ?: true)
}
