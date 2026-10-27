package dev.phoenix.server

/**
 * The one public route friends use to reach the server. Exactly one is selected at a time. Minecraft.How is not a
 * route: it is an optional name for whichever route is selected. Pure Kotlin, so Home, Settings and the backend share
 * one definition and it is unit tested.
 */
enum class PublicRoute(val key: String, val title: String) {
    PLAYIT("playit", "Playit.gg"),
    NETBIRD_EU("netbird-eu", "NetBird"),
    NETBIRD_REGIONAL("netbird-regional", "Regional Optimized"),
    LOCAL("local", "Local only");

    /** Both NetBird routes run the phone's NetBird client; the regional proxy forwards to the phone through it. */
    val usesNetBird get() = this == NETBIRD_EU || this == NETBIRD_REGIONAL
    val isPublic get() = this != LOCAL

    companion object {
        fun of(key: String?): PublicRoute? = entries.firstOrNull { it.key == key }

        /**
         * The route an install had before routes were selected explicitly: NetBird when it already has public ports
         * (on the regional proxy when players were moved there), otherwise none, so a fresh install recommends Playit
         * without claiming anything is connected.
         */
        fun migrate(saved: String?, netBirdHasPorts: Boolean, legacyPublicRoute: String?): PublicRoute? = of(saved) ?: when {
            !netBirdHasPorts -> null
            legacyPublicRoute == "us" -> NETBIRD_REGIONAL
            else -> NETBIRD_EU
        }
    }
}

/** A public address as players type it. [port] is null when Java finds the port itself through an SRV record. */
data class Endpoint(val host: String, val port: Int?) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "Invalid public host" }
        require(port == null || port in 1..65535) { "Invalid public port" }
    }
    val text get() = if (port == null) host else "$host:$port"
    val isIpAddress get() = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host.contains(':')

    companion object {
        /** "host:port", "[v6]:port" or "host" (no port). Null for anything else. */
        fun parse(value: String?): Endpoint? {
            val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                if (text.startsWith("[")) {
                    val host = text.substringAfter('[').substringBefore(']')
                    val port = text.substringAfter("]", "").removePrefix(":").takeIf { it.isNotEmpty() }?.toInt()
                    Endpoint(host, port)
                } else if (text.count { it == ':' } == 1) Endpoint(text.substringBefore(':'), text.substringAfter(':').toInt())
                else Endpoint(text, null)
            }.getOrNull()
        }
    }
}

/**
 * Where the selected route sends each kind of traffic, exactly as the provider reported it. The Java [javaSrv] is the
 * real host and port behind a Java address that needs no port (Playit's own SRV), which a custom name's SRV must target.
 * [nameJava], when set, is where a custom name's Java SRV leads instead: Phoenix's front door (Playit + NetBird + name).
 */
data class RouteEndpoints(val java: Endpoint?, val bedrock: Endpoint?, val voice: Endpoint?, val javaSrv: Endpoint? = null,
                          val nameJava: Endpoint? = null, val bedrockIp: String? = null) {
    /** Changes whenever any address changes, so a DNS check made for older addresses is never reused. */
    val fingerprint: String get() = listOf(java?.text, javaSrv?.text, bedrock?.text, voice?.text).joinToString("|") +
        (nameJava?.let { "|name=${it.text}" } ?: "") + (bedrockIp?.let { "|b4=$it" } ?: "")
    /**
     * Bedrock as players should save it: the provider's IPv4 address when known. Bedrock's server list (2026 app versions)
     * stays on "Loading ping" for servers saved by a name that also has IPv6, though joining works.
     */
    val bedrockForList: Endpoint? get() = bedrock?.let { b -> bedrockIp?.let { Endpoint(it, b.port) } ?: b }
    /** The concrete host and port behind the Java address: what an SRV record must point at. */
    val javaTarget: Endpoint? get() = javaSrv ?: java?.takeIf { it.port != null }
}

/** How one provider is doing, in the same terms on Home and in Settings. */
enum class LinkHealth { NOT_SET_UP, OFF, CONNECTING, CONNECTED, ERROR }

/** One provider's state as both Home and Settings show it. Derived only from the backend's single source of truth. */
data class ProviderState(
    val route: PublicRoute,
    /** This is the selected public route. */
    val selected: Boolean = false,
    /** The provider has an account or credentials on this phone. */
    val configured: Boolean = false,
    val health: LinkHealth = LinkHealth.NOT_SET_UP,
    /** A few words: "Connected", "Not connected", "Authentication required". */
    val status: String = "Not set up",
    /** What to do about it, for warnings and errors. */
    val detail: String? = null,
    /** Addresses reported by the provider; never shown as connected until [health] is CONNECTED. */
    val endpoints: RouteEndpoints? = null,
    /** An operation (sign-in, tunnel setup) is running for this provider. */
    val working: Boolean = false,
)

/** The optional Minecraft.How name. Its states never block hosting. */
enum class NameHealth { NOT_SET_UP, OFF, SIGN_IN, UPDATING, PROPAGATING, ACTIVE, ERROR, UNREACHABLE }

data class CustomName(
    /** The owner turned the name on (it is kept when turned off). */
    val enabled: Boolean = false,
    val hostname: String? = null,
    val health: NameHealth = NameHealth.NOT_SET_UP,
    val detail: String? = null,
    /** [RouteEndpoints.fingerprint] that public DNS was last verified against; only then is the name the primary. */
    val verifiedFor: String? = null,
    /** Public DNS gave Bedrock's route record (A/CNAME) for those addresses; Java's SRV may verify alone. */
    val bedrockVerified: Boolean = false,
    /** Minecraft.How's own name servers publish the right records for the current addresses; only caches may lag. */
    val recordsLive: Boolean = false,
)

/** One milestone of getting friends in: unknown, in progress, done, failed or not applicable. */
enum class Check { UNKNOWN, PENDING, OK, FAILED, NOT_APPLICABLE }

/**
 * Separate milestones, never folded into one "ready": the app, the local server, the relay, and what was actually
 * reached through the public address. Voice cannot be probed from the phone, so OK there means "forwarded".
 */
data class Readiness(
    val server: Check = Check.UNKNOWN,
    val relay: Check = Check.UNKNOWN,
    val java: Check = Check.UNKNOWN,
    val bedrock: Check = Check.UNKNOWN,
    val voice: Check = Check.NOT_APPLICABLE,
    /** Milliseconds from Start to "Minecraft accepts connections", and to the public Java address answering. */
    val serverReadyMs: Long? = null,
    val publicReadyMs: Long? = null,
)

/** A route change in progress, shown while it runs; the previous route keeps working until it commits. */
data class RouteSwitch(val from: PublicRoute?, val to: PublicRoute, val step: String)

/** The whole networking picture for the UI. */
data class NetworkView(
    val selected: PublicRoute? = null,
    val providers: Map<PublicRoute, ProviderState> = PublicRoute.entries.associateWith { ProviderState(it) },
    val name: CustomName = CustomName(),
    val readiness: Readiness = Readiness(),
    val switching: RouteSwitch? = null,
    /** The last route change failed and the previous route was kept; why. */
    val switchError: String? = null,
    /** The owner confirmed the Regional Optimized warning once. */
    val experimentalAcknowledged: Boolean = false,
) {
    val active: ProviderState? get() = selected?.takeIf { it.isPublic }?.let { providers[it] }
    fun provider(route: PublicRoute): ProviderState = providers[route] ?: ProviderState(route)
}

/** Which address a card shows: the verified custom name first, the provider's own address as the alternative. */
data class AddressOption(val custom: Boolean, val label: String, val java: Endpoint, val bedrock: Endpoint?)

object Addresses {
    /**
     * The addresses friends can use for the selected route. Nothing until the provider is connected; the custom name
     * only when public DNS was verified for exactly these addresses, so a stale or failing name falls back at once.
     */
    fun options(view: NetworkView): List<AddressOption> {
        val active = view.active ?: return emptyList()
        if (active.health != LinkHealth.CONNECTED) return emptyList()
        val endpoints = active.endpoints ?: return emptyList()
        val java = endpoints.java ?: return emptyList()
        val provider = AddressOption(false, "${active.route.title} address", java, endpoints.bedrockForList)
        val name = view.name
        val hostname = name.hostname
        val verified = name.enabled && hostname != null && name.health == NameHealth.ACTIVE && name.verifiedFor == endpoints.fingerprint
        if (!verified) return listOf(provider)
        val custom = AddressOption(true, "Custom address", Endpoint(hostname!!, null),
            endpoints.bedrock?.let { if (name.bedrockVerified) Endpoint(hostname, it.port) else it })
        return listOf(custom, provider)
    }

    /** Text to share: every option for both editions, saying the addresses lead to the same server. */
    fun shareText(serverName: String, options: List<AddressOption>): String = buildString {
        append("Join my Minecraft server \"").append(serverName).append("\"!\n")
        options.forEachIndexed { index, option ->
            append(if (index == 0) "\n" else "\nOr the same server at its ${option.label.lowercase()}:\n")
            append("Java Edition: ").append(option.java.text).append('\n')
            option.bedrock?.let { append("Bedrock Edition: ").append(it.host).append("  Port: ").append(it.port ?: 19132).append('\n') }
            // Bedrock's app shows players and ping only for servers saved by IP; say so where the IP is offered.
            if (!option.custom && option.bedrock?.isIpAddress == true && options.any { it.custom })
                append("(Use this IP in Bedrock to see players in the server list.)\n")
        }
    }.trimEnd()
}
