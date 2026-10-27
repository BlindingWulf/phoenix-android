package dev.phoenix.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Locale

/** One DNS server asked directly over UDP port 53; every failure is an empty answer with response code -1. */
class UdpDnsLookup(
    private val server: InetAddress,
    private val recursionDesired: Boolean = false,
    private val timeoutMs: Int = 3000,
) : DnsRecordLookup {
    override fun query(name: String, type: Int): DnsMessage = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val id = random.nextInt(0x10000)
            val packet = DnsWire.query(id, name, type, recursionDesired)
            socket.send(DatagramPacket(packet, packet.size, server, 53))
            val buffer = ByteArray(4096)
            // Replies from anyone else, or to another question, are skipped (a few at most, each within the timeout).
            repeat(8) {
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val replyId = if (reply.length >= 2) ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff) else -1
                if (reply.address == server && replyId == id) return@use DnsWire.parse(buffer.copyOf(reply.length), callbackRcode = -1)
            }
            DnsMessage(-1, emptyList())
        }
    }.getOrDefault(DnsMessage(-1, emptyList()))

    private companion object { val random = SecureRandom() }
}

/**
 * Asks a zone's own name servers (no recursion), so the answer is what the zone publishes right now, and asking leaves
 * nothing in any resolver's cache. A public resolver asked before new records exist keeps "no such record" for the
 * zone's negative TTL (30 minutes on minecraft.co.com); on 2026-10-12 that hid a saved, correct Java SRV from the
 * phone's network for half an hour while Phoenix kept calling it missing. Names outside the zone go to [fallback].
 */
class AuthoritativeDnsLookup(
    zone: String,
    private val fallback: DnsRecordLookup,
    private val serverFor: (InetAddress) -> DnsRecordLookup = { UdpDnsLookup(it) },
    private val resolve: (String) -> List<InetAddress> = { host ->
        runCatching { InetAddress.getAllByName(host).filterIsInstance<Inet4Address>() }.getOrDefault(emptyList())
    },
) : DnsRecordLookup {
    private val zone = zone.trim().trim('.').lowercase(Locale.ROOT)

    /** One of the zone's servers answered. Without that, a result says nothing about what the zone publishes. */
    @Volatile var reached = false
        private set

    private val servers: List<DnsRecordLookup> by lazy {
        fallback.query(this.zone, MinecraftHowDns.TYPE_NS).answers
            .filter { it.type == MinecraftHowDns.TYPE_NS && !it.target.isNullOrBlank() }
            .map { it.target!!.trimEnd('.').lowercase(Locale.ROOT) }.distinct().sorted()
            .mapNotNull { resolve(it).firstOrNull() }.take(MAX_SERVERS).map(serverFor)
    }

    override fun query(name: String, type: Int): DnsMessage {
        val clean = name.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (clean != zone && !clean.endsWith(".$zone")) return fallback.query(name, type)
        for (server in servers) {
            val answer = server.query(clean, type)
            // NOERROR or NXDOMAIN is the zone speaking; anything else (no reply, REFUSED, SERVFAIL) tries the next server.
            if (answer.responseCode == 0 || answer.responseCode == 3) { reached = true; return answer }
        }
        return DnsMessage(-1, emptyList())
    }

    companion object {
        private const val MAX_SERVERS = 2

        /** The most specific of [zones] that [hostname] lies in, or null. */
        fun zoneOf(hostname: String, zones: Collection<String>): String? {
            val clean = hostname.trim().trimEnd('.').lowercase(Locale.ROOT)
            return zones.map { it.trim().trim('.').lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && clean.endsWith(".$it") }
                .maxByOrNull { it.length }
        }
    }
}
