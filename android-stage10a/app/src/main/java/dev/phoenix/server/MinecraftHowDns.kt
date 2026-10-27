package dev.phoenix.server

import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Read-only Minecraft.How DNS validation. DNS answers establish only that the
 * public records match an allocated relay; they do not establish player reachability.
 */
class MinecraftHowDns(private val lookup: DnsRecordLookup = AndroidDnsRecordLookup()) {
    enum class State { VERIFIED, UNKNOWN, INVALID_HOSTNAME }

    data class Verification(
        val state: State,
        val hostname: String?,
        val relayHost: String?,
        val javaDnsMatches: Boolean,
        val bedrockDnsMatches: Boolean,
        val javaAddress: String?,
        val bedrockAddress: String?,
        val detail: String,
        /** A record that was asked for came back empty; resolvers may repeat that answer for this many seconds. */
        val negativeTtlSeconds: Int? = null,
    )

    companion object {
        const val TYPE_A = 1
        const val TYPE_NS = 2
        const val TYPE_AAAA = 28
        const val TYPE_CNAME = 5
        const val TYPE_SRV = 33

        /** Public Minecraft.How dashboard zones; refresh from `parentDomains` before creation. */
        val supportedSuffixes: List<String> = listOf(
            "minecraft.co.com",
            "counter-strike.how",
            "minecraft.how",
            "kal.how",
            "gta.how",
            "cs2.my",
        )

        private val labelPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val hostnamePattern = Regex("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
        private val ipv4Pattern = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        private const val QUERY_TIMEOUT_SECONDS = 8L
        private val QUERY_EXECUTOR = Executors.newFixedThreadPool(6) { runnable ->
            Thread(runnable, "phoenix-minecraft-how-verify").apply { isDaemon = true }
        }

        @JvmStatic
        fun normalizeHostname(value: String, allowedZones: Collection<String> = supportedSuffixes): String {
            val normalized = value.trim().trimEnd('.').lowercase(Locale.ROOT)
            require(normalized.isNotEmpty() && normalized.length <= 253 && hostnamePattern.matches(normalized)) {
                "Invalid Minecraft.How hostname"
            }
            val zones = allowedZones.map { it.trim().trim('.').lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
            val usesSupportedZone = zones.any { suffix ->
                normalized.endsWith(".$suffix") && normalized.removeSuffix(".$suffix").let { it.length in 2..63 && labelPattern.matches(it) }
            }
            require(usesSupportedZone) { "Choose a hostname under a Minecraft.How DNS zone" }
            return normalized
        }

        @JvmStatic
        fun validateHostname(value: String, allowedZones: Collection<String> = supportedSuffixes): Boolean =
            runCatching { normalizeHostname(value, allowedZones) }.isSuccess

        @JvmStatic
        fun composeHostname(
            label: String,
            suffix: String,
            allowedZones: Collection<String> = supportedSuffixes,
        ): String? {
            val cleanLabel = label.trim().lowercase(Locale.ROOT)
            val cleanSuffix = suffix.trim().trimStart('.').trimEnd('.').lowercase(Locale.ROOT)
            val zones = allowedZones.map { it.trim().trim('.').lowercase(Locale.ROOT) }
            if (cleanLabel.length !in 2..63 || !labelPattern.matches(cleanLabel) || cleanSuffix !in zones) return null
            return "$cleanLabel.$cleanSuffix".takeIf { validateHostname(it, allowedZones) }
        }
    }

    fun validate(
        hostname: String,
        relayHost: String,
        relayTcpPort: Int,
        relayUdpPort: Int,
        allowedZones: Collection<String> = supportedSuffixes,
        /** The Java SRV target when Java uses another host than the route record (Playit); null: the relay. */
        javaSrvHost: String? = null,
    ): Verification {
        val normalizedHostname = try {
            normalizeHostname(hostname, allowedZones)
        } catch (_: IllegalArgumentException) {
            return Verification(
                State.INVALID_HOSTNAME, null, null, false, false, null, null,
                "Enter a valid hostname under one of the currently available Minecraft.How DNS zones.",
            )
        }
        val normalizedRelay = normalizeRelayHost(relayHost)
        val srvHost = javaSrvHost?.let { normalizeRelayHost(it)?.takeUnless(::isIpAddress) }
        if (normalizedRelay == null || !validPort(relayTcpPort) || !validPort(relayUdpPort) || (javaSrvHost != null && srvHost == null)) {
            return Verification(
                State.UNKNOWN, normalizedHostname, normalizedRelay, false, false, null, null,
                "The assigned relay endpoint is unavailable, so DNS cannot be checked yet.",
            )
        }

        val cnameName = normalizedHostname
        val srvName = "_minecraft._tcp.$normalizedHostname"
        val relayIsAddress = isIpAddress(normalizedRelay)
        val queryTargets = buildList {
            add(normalizedHostname to TYPE_A)
            add(normalizedHostname to TYPE_AAAA)
            add(cnameName to TYPE_CNAME)
            add(srvName to TYPE_SRV)
            if (!relayIsAddress) {
                add(normalizedRelay to TYPE_A)
                add(normalizedRelay to TYPE_AAAA)
            }
        }
        val results = runCatching {
            val work = queryTargets.map { (name, type) -> Callable { lookup.query(name, type) } }
            QUERY_EXECUTOR.invokeAll(work, QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .map { future -> if (future.isCancelled) DnsMessage(-1, emptyList()) else runCatching { future.get() }.getOrDefault(DnsMessage(-1, emptyList())) }
        }.getOrNull()
        if (results == null) {
            return Verification(
                State.UNKNOWN, normalizedHostname, normalizedRelay, false, false, null, null,
                "DNS could not be checked. No hostname availability or connection result is inferred.",
            )
        }
        val answers = DnsAnswers(
            hostA = results[0],
            hostAAAA = results[1],
            hostCname = results[2],
            service = results[3],
            relayAddresses = results.drop(4),
        )

        val hostRecords = answers.hostA.answers + answers.hostAAAA.answers + answers.hostCname.answers
        val hostCnameTargets = hostRecords
            .filter { it.type == TYPE_CNAME && normalizedOwner(it.owner) == normalizedHostname }
            .map { normalizeDnsName(it.target.orEmpty()) }
            .toSet()
        val expectedAddresses = if (relayIsAddress) {
            setOf(normalizedRelay)
        } else {
            answers.relayAddresses.flatMap { it.answers }
                .filter { it.type in setOf(TYPE_A, TYPE_AAAA) && normalizedOwner(it.owner) == normalizedRelay }
                .mapNotNull { normalizeIpAddress(it.address.orEmpty()) }
                .toSet()
        }
        val hostAddresses = hostRecords
            .filter { it.type in setOf(TYPE_A, TYPE_AAAA) && normalizedOwner(it.owner) == normalizedHostname }
            .mapNotNull { normalizeIpAddress(it.address.orEmpty()) }
            .toSet()
        // All records attached directly to the chosen hostname must route to this relay.
        // A single matching address among stale or unrelated records is not sufficient.
        val cnameMatchesRelay = hostCnameTargets == setOf(normalizedRelay) && hostAddresses.isEmpty()
        val addressMatchesRelay = hostCnameTargets.isEmpty() && expectedAddresses.isNotEmpty() &&
            hostAddresses.isNotEmpty() && hostAddresses.all(expectedAddresses::contains)
        val dnsRouteMatches = cnameMatchesRelay || addressMatchesRelay

        val serviceRecords = answers.service.answers.filter {
            it.type == TYPE_SRV && normalizedOwner(it.owner) == srvName
        }
        val expectedSrvTarget = srvHost ?: if (relayIsAddress) normalizedHostname else normalizedRelay
        val srvMatchesRelay = serviceRecords.isNotEmpty() && serviceRecords.all {
            normalizeDnsName(it.target.orEmpty()) == expectedSrvTarget && it.port == relayTcpPort
        }
        // Java follows the SRV record; it also needs the name itself only when the SRV targets the name.
        val javaMatches = srvMatchesRelay && (srvHost != null || dnsRouteMatches)
        val bedrockMatches = dnsRouteMatches

        val detail = when {
            javaMatches && bedrockMatches -> "DNS records point to your server."
            !dnsRouteMatches && relayIsAddress -> "No A record points to $normalizedRelay yet. New records can take a few minutes."
            !dnsRouteMatches -> "No CNAME record points to $normalizedRelay yet. New records can take a few minutes."
            // An SRV that isn't there at all is usually one not seen yet, not a wrong one.
            serviceRecords.isEmpty() -> "The name points to the right place; Java's SRV record (port $relayTcpPort, target $expectedSrvTarget) isn't visible yet."
            else -> "The name points to the right place, but the Java SRV record needs port $relayTcpPort and target $expectedSrvTarget " +
                "(it says ${serviceRecords.joinToString { "${normalizeDnsName(it.target.orEmpty())}:${it.port}" }})."
        }
        // The empty answers that matter: the route record when it doesn't match yet, and Java's SRV when it's missing.
        val emptyAnswers = buildList {
            if (!dnsRouteMatches) addAll(listOf(answers.hostA, answers.hostAAAA, answers.hostCname))
            if (serviceRecords.isEmpty()) add(answers.service)
        }
        return Verification(
            if (javaMatches && bedrockMatches) State.VERIFIED else State.UNKNOWN,
            normalizedHostname,
            normalizedRelay,
            javaMatches,
            bedrockMatches,
            if (javaMatches) normalizedHostname else null,
            if (bedrockMatches) "$normalizedHostname:$relayUdpPort" else null,
            detail,
            emptyAnswers.filter { it.answers.isEmpty() }.mapNotNull { it.negativeTtlSeconds }.maxOrNull(),
        )
    }

    private data class DnsAnswers(
        val hostA: DnsMessage,
        val hostAAAA: DnsMessage,
        val hostCname: DnsMessage,
        val service: DnsMessage,
        val relayAddresses: List<DnsMessage>,
    )

    private fun normalizeRelayHost(value: String): String? {
        val clean = value.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean.length > 253 || clean.any { it == '/' || it == '@' || it.isWhitespace() }) return null
        if (normalizeIpAddress(clean) != null) return normalizeIpAddress(clean)
        return clean.takeIf { hostnamePattern.matches(it) }
    }

    private fun normalizeDnsName(value: String): String = value.trim().trimEnd('.').lowercase(Locale.ROOT)
    private fun normalizedOwner(value: String): String = normalizeDnsName(value)
    private fun validPort(value: Int): Boolean = value in 1..65535

    private fun isIpAddress(value: String): Boolean = normalizeIpAddress(value) != null

    private fun normalizeIpAddress(value: String): String? {
        val clean = value.trim().trim('[', ']').lowercase(Locale.ROOT)
        if (ipv4Pattern.matches(clean)) {
            val octets = clean.split('.').mapNotNull { it.toIntOrNull() }
            return clean.takeIf { octets.size == 4 && octets.all { part -> part in 0..255 } }
        }
        if (!clean.contains(':') || clean.any { it !in "0123456789abcdef:." }) return null
        return runCatching {
            val address = java.net.InetAddress.getByName(clean)
            address.hostAddress?.substringBefore('%')?.lowercase(Locale.ROOT)
        }.getOrNull()
    }

}

data class DnsRecord(
    val owner: String,
    val type: Int,
    val target: String? = null,
    val address: String? = null,
    val port: Int? = null,
)

/** [negativeTtlSeconds]: for an empty answer, how long resolvers may keep repeating it (from the zone's SOA). */
data class DnsMessage(val responseCode: Int, val answers: List<DnsRecord>, val negativeTtlSeconds: Int? = null)

fun interface DnsRecordLookup {
    fun query(name: String, type: Int): DnsMessage
}
