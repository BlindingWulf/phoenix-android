package dev.phoenix.server

import android.net.DnsResolver
import android.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android system-resolver adapter for read-only A, CNAME and SRV lookups. */
class AndroidDnsRecordLookup : DnsRecordLookup {
    override fun query(name: String, type: Int): DnsMessage {
        val cancellation = CancellationSignal()
        val completed = CountDownLatch(1)
        val result = AtomicReference<DnsMessage?>()
        fun finish(message: DnsMessage) {
            if (result.compareAndSet(null, message)) completed.countDown()
        }
        try {
            DnsResolver.getInstance().rawQuery(
                null,
                name,
                DnsResolver.CLASS_IN,
                type,
                DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                callbackExecutor,
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        finish(runCatching { DnsWire.parse(answer, rcode) }
                            .getOrDefault(DnsMessage(responseCode = -1, answers = emptyList())))
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        finish(DnsMessage(responseCode = -1, answers = emptyList()))
                    }
                },
            )
        } catch (_: Exception) {
            finish(DnsMessage(responseCode = -1, answers = emptyList()))
        }
        try {
            if (!completed.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancellation.cancel()
            }
        } catch (_: InterruptedException) {
            cancellation.cancel()
            Thread.currentThread().interrupt()
        }
        return result.get() ?: DnsMessage(responseCode = -1, answers = emptyList())
    }

    private companion object {
        const val QUERY_TIMEOUT_SECONDS = 7L
        val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phoenix-minecraft-how-dns").apply { isDaemon = true }
        }
    }
}

/** Bounded DNS message parser for the record types used by Phoenix. */
internal object DnsWire {
    fun parse(packet: ByteArray, callbackRcode: Int): DnsMessage {
        require(packet.size >= HEADER_SIZE) { "DNS response header is truncated" }
        val flags = u16(packet, 2)
        require(flags and 0x8000 != 0) { "DNS response flag is missing" }
        val packetRcode = flags and 0x000f
        val rcode = if (callbackRcode >= 0) callbackRcode else packetRcode
        val questionCount = u16(packet, 4)
        val answerCount = u16(packet, 6)
        val authorityCount = u16(packet, 8)
        var cursor = HEADER_SIZE
        repeat(questionCount) {
            cursor = readName(packet, cursor).nextOffset
            require(cursor + 4 <= packet.size) { "DNS question is truncated" }
            cursor += 4
        }

        val records = ArrayList<DnsRecord>(answerCount.coerceAtMost(MAX_ANSWERS))
        repeat(answerCount.coerceAtMost(MAX_ANSWERS)) {
            val ownerResult = readName(packet, cursor)
            val owner = ownerResult.name
            cursor = ownerResult.nextOffset
            require(cursor + RR_HEADER_SIZE <= packet.size) { "DNS resource record header is truncated" }
            val type = u16(packet, cursor)
            val recordClass = u16(packet, cursor + 2)
            val dataLength = u16(packet, cursor + 8)
            val dataStart = cursor + RR_HEADER_SIZE
            val dataEnd = dataStart + dataLength
            require(dataEnd <= packet.size) { "DNS resource record data is truncated" }
            if (recordClass == DnsResolver.CLASS_IN) {
                when (type) {
                    MinecraftHowDns.TYPE_A -> if (dataLength == 4) {
                        records += DnsRecord(owner, type, address = (0 until 4).joinToString(".") { (packet[dataStart + it].toInt() and 0xff).toString() })
                    }
                    MinecraftHowDns.TYPE_AAAA -> if (dataLength == 16) {
                        val addressBytes = packet.copyOfRange(dataStart, dataEnd)
                        val address = java.net.InetAddress.getByAddress(addressBytes).hostAddress?.substringBefore('%')
                        if (address != null) records += DnsRecord(owner, type, address = address.lowercase())
                    }
                    MinecraftHowDns.TYPE_CNAME, MinecraftHowDns.TYPE_NS -> {
                        val target = readName(packet, dataStart)
                        require(target.nextOffset <= dataEnd) { "DNS ${if (type == MinecraftHowDns.TYPE_NS) "NS" else "CNAME"} record is malformed" }
                        records += DnsRecord(owner, type, target = target.name)
                    }
                    MinecraftHowDns.TYPE_SRV -> if (dataLength >= 7) {
                        val priority = u16(packet, dataStart)
                        val weight = u16(packet, dataStart + 2)
                        val port = u16(packet, dataStart + 4)
                        val target = readName(packet, dataStart + 6)
                        require(target.nextOffset <= dataEnd) { "DNS SRV record is malformed" }
                        // Priority and weight are parsed to validate the whole record, but the
                        // expected routing contract depends only on its target and port.
                        if (priority >= 0 && weight >= 0) records += DnsRecord(owner, type, target = target.name, port = port)
                    }
                }
            }
            cursor = dataEnd
        }
        // An empty answer carries the zone's SOA in the authority section: resolvers keep "no such record" for the smaller
        // of its TTL and its minimum field (RFC 2308). Only read when there are no answers; a truncated tail is ignored.
        val negativeTtl = if (records.isNotEmpty() || answerCount > MAX_ANSWERS) null else runCatching {
            var ttl: Int? = null
            repeat(authorityCount.coerceAtMost(MAX_ANSWERS)) {
                cursor = readName(packet, cursor).nextOffset
                require(cursor + RR_HEADER_SIZE <= packet.size) { "DNS authority record is truncated" }
                val type = u16(packet, cursor)
                val recordTtl = u32(packet, cursor + 4)
                val dataEnd = cursor + RR_HEADER_SIZE + u16(packet, cursor + 8)
                require(dataEnd <= packet.size && dataEnd >= cursor + RR_HEADER_SIZE + 4) { "DNS authority record data is truncated" }
                if (type == TYPE_SOA) ttl = minOf(recordTtl, u32(packet, dataEnd - 4)).toInt()
                cursor = dataEnd
            }
            ttl
        }.getOrNull()
        return DnsMessage(rcode, records, negativeTtl)
    }

    /** A standard query for [name] and [type]. Without [recursionDesired] a zone's own server answers only from its zone. */
    fun query(id: Int, name: String, type: Int, recursionDesired: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun short(value: Int) { out.write(value shr 8 and 0xff); out.write(value and 0xff) }
        short(id); short(if (recursionDesired) 0x0100 else 0); short(1); short(0); short(0); short(0)
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..MAX_LABEL_LENGTH) { "DNS label is malformed" }
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0); short(type); short(1)
        return out.toByteArray()
    }

    private data class NameResult(val name: String, val nextOffset: Int)

    private fun readName(packet: ByteArray, offset: Int): NameResult {
        require(offset in packet.indices) { "DNS name is missing" }
        var cursor = offset
        var nextOffset = -1
        var jumps = 0
        val labels = ArrayList<String>()
        while (true) {
            require(cursor < packet.size) { "DNS name is truncated" }
            val length = packet[cursor].toInt() and 0xff
            if (length and 0xc0 == 0xc0) {
                require(cursor + 1 < packet.size) { "DNS compression pointer is truncated" }
                val pointer = ((length and 0x3f) shl 8) or (packet[cursor + 1].toInt() and 0xff)
                require(pointer < packet.size && pointer != cursor) { "DNS compression pointer is invalid" }
                if (nextOffset < 0) nextOffset = cursor + 2
                cursor = pointer
                require(++jumps <= MAX_NAME_JUMPS) { "DNS compression pointer loop" }
                continue
            }
            require(length and 0xc0 == 0) { "Unsupported DNS label encoding" }
            cursor++
            if (length == 0) {
                if (nextOffset < 0) nextOffset = cursor
                break
            }
            require(length <= MAX_LABEL_LENGTH && cursor + length <= packet.size) { "DNS label is malformed" }
            labels += String(packet, cursor, length, Charsets.US_ASCII).lowercase()
            cursor += length
        }
        return NameResult(labels.joinToString("."), nextOffset)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < bytes.size) { "DNS integer is truncated" }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long = (u16(bytes, offset).toLong() shl 16) or u16(bytes, offset + 2).toLong()

    private const val TYPE_SOA = 6
    private const val HEADER_SIZE = 12
    private const val RR_HEADER_SIZE = 10
    private const val MAX_ANSWERS = 128
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_NAME_JUMPS = 32
}
