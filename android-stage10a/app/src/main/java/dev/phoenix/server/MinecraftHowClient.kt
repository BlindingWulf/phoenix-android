package dev.phoenix.server

import org.phoenix.core.network.netbird.NetBirdApiException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Same-origin Minecraft.How DNS client. It follows the public Laravel/Inertia
 * routes and payloads served by the website; it never stores account cookies or
 * API tokens. Call it from a worker thread and supply the current WebView cookie
 * header for each operation.
 */
class MinecraftHowClient internal constructor(
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    @Volatile private var inertiaVersion: String? = null

    enum class EnsureState {
        SAVED,
        CHECKED,
        NEEDS_SIGN_IN,
        INVALID_INPUT,
        ZONE_UNAVAILABLE,
        CONFLICT,
        RECOVERY_REQUIRED,
        FAILED,
        /** Minecraft.How answered HTTP 429; no request is sent until the persisted cooldown ends. */
        RATE_LIMITED,
    }

    data class SessionVerification(
        val signedIn: Boolean,
        val detail: String,
        val domainCount: Int,
        /** False when Minecraft.How could not be reached or answered unexpectedly; not proof of sign-out. */
        val reachable: Boolean = true,
        /** The account's own username/display name from the authenticated page, for "Signed in as". */
        val accountLabel: String? = null,
        /** Minecraft.How's numeric user id; compares accounts across sessions without trusting display names. */
        val accountId: Long? = null,
    )

    data class ZoneList(
        val signedIn: Boolean,
        val zones: List<String>,
        val detail: String,
    )

    data class DomainSummary(val id: Long, val hostname: String)

    enum class DnsSnapshotState { READ, NEEDS_SIGN_IN, INVALID_HOSTNAME, NOT_FOUND, CONFLICT, FAILED }

    data class DnsRecordSnapshot(
        val id: Long,
        val type: String,
        val name: String?,
        val content: String,
        val ttl: Int,
        val priority: Int?,
        val port: Int?,
    )

    data class DnsSnapshotResult(
        val state: DnsSnapshotState,
        val accountId: Long? = null,
        val domainId: Long? = null,
        val hostname: String? = null,
        val records: List<DnsRecordSnapshot> = emptyList(),
        val detail: String,
    )

    /** True only when the in-memory cookie header also has the CSRF cookie needed for DNS writes. */
    fun hasDnsWriteSession(cookieHeader: String): Boolean = cookieHeader.isNotBlank() && csrfToken(cookieHeader) != null

    /**
     * Browser-style refresh for an expired DNS security cookie: one authenticated same-origin GET,
     * returning the cookies Minecraft.How issued in that reply when they pass the receipt checks.
     * Nothing is stored here; the caller installs them in Phoenix's own jar and re-verifies the account.
     */
    fun refreshedSessionCookies(cookieHeader: String): List<String>? {
        if (cookieHeader.isBlank()) return null
        val response = runCatching { send(HttpRequest("GET", DNS_INDEX_PATH, pageHeaders(cookieHeader), null)) }
            .getOrNull() ?: return null
        if (response.statusCode !in 200..299) return null
        val issued = response.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
        return MinecraftHowBrowserCookies.validate(issued)?.setCookieHeaders
    }

    data class HostnameResult(
        val state: EnsureState,
        val hostname: String?,
        val detail: String,
        val accountId: Long? = null,
        val domainId: Long? = null,
    )

    /** Checks the real account and offered suffixes without allocating a relay or writing DNS. */
    fun checkHostname(cookie: String, hostname: String, zones: Collection<String> = MinecraftHowDns.supportedSuffixes): HostnameResult =
        manageHostname(cookie, hostname, zones, create = false)

    /** Reserves just the hostname. A durable callback runs before the only possible POST. */
    fun reserveHostname(
        cookie: String,
        hostname: String,
        zones: Collection<String> = MinecraftHowDns.supportedSuffixes,
        allowCreate: Boolean = true,
        pendingAccountId: Long? = null,
        beforeCreate: (Long) -> Boolean = { true },
    ): HostnameResult = manageHostname(cookie, hostname, zones, true, allowCreate, pendingAccountId, beforeCreate)

    private fun manageHostname(
        cookie: String,
        hostname: String,
        zones: Collection<String>,
        create: Boolean,
        allowCreate: Boolean = true,
        pendingAccountId: Long? = null,
        beforeCreate: (Long) -> Boolean = { true },
    ): HostnameResult {
        val clean = runCatching { MinecraftHowDns.normalizeHostname(hostname, zones) }.getOrNull()
            ?: return HostnameResult(EnsureState.INVALID_INPUT, null, "Use 2–63 letters, numbers or hyphens, with no hyphen at either end.")
        if (cookie.isBlank()) return HostnameResult(EnsureState.NEEDS_SIGN_IN, clean, "Sign in to Minecraft.How to check or save this hostname.")
        var writeStarted = false
        return try {
            val index = getPage(cookie, DNS_INDEX_PATH)
            val accountId = index.authenticatedUser()?.long("id")
                ?: return HostnameResult(EnsureState.NEEDS_SIGN_IN, clean, "Sign in to Minecraft.How to save this hostname.")
            if (pendingAccountId != null && pendingAccountId != accountId) {
                return HostnameResult(EnsureState.RECOVERY_REQUIRED, clean, "An earlier hostname save belongs to another account. Sign in with that account to confirm its result.")
            }
            val owned = ownedDomains(cookie, index).filter { it.hostname == clean }
            if (owned.size > 1) return HostnameResult(EnsureState.CONFLICT, clean, "Minecraft.How returned duplicate owned hostnames. No changes were made.")
            if (owned.size == 1) return HostnameResult(EnsureState.SAVED, clean, "This hostname belongs to your Minecraft.How account. Relay setup can happen later.", accountId, owned.single().id)
            val offered = getPage(cookie, DNS_CREATE_PATH)
            if (offered.authenticatedUser()?.long("id") != accountId) {
                return HostnameResult(EnsureState.NEEDS_SIGN_IN, clean, "The account changed while checking the hostname. Refresh sign-in and try again.")
            }
            if (!MinecraftHowDns.validateHostname(clean, offered.parentZones())) {
                return HostnameResult(EnsureState.ZONE_UNAVAILABLE, clean, "Minecraft.How does not currently offer this suffix. Refresh the domain choices.")
            }
            if (!create) return HostnameResult(EnsureState.CHECKED, clean,
                "The name format and suffix are valid. Save hostname to request it from Minecraft.How; availability is confirmed by the save.", accountId)
            if (!allowCreate) return HostnameResult(EnsureState.RECOVERY_REQUIRED, clean,
                "The earlier save is still unconfirmed. Phoenix checked the account without repeating the create request.", accountId)
            if (csrfToken(cookie).isNullOrBlank()) return HostnameResult(EnsureState.NEEDS_SIGN_IN, clean, "Reopen sign-in so Minecraft.How's security cookie is available.")
            if (!beforeCreate(accountId)) return HostnameResult(EnsureState.FAILED, clean, "Could not save hostname progress on this device. No request was sent.")
            writeStarted = true
            val mutation = runCatching { mutate(cookie, "POST", DNS_INDEX_PATH, listOf("domain" to clean)) }
            val readback = getPage(cookie, DNS_INDEX_PATH)
            if (readback.authenticatedUser()?.long("id") != accountId) return HostnameResult(EnsureState.RECOVERY_REQUIRED, clean, "The account changed before the save could be confirmed.", accountId)
            val saved = ownedDomains(cookie, readback).filter { it.hostname == clean }
            if (saved.size == 1) return HostnameResult(EnsureState.SAVED, clean, "Hostname saved in your Minecraft.How account. Start the server and connect its public address when ready.", accountId, saved.single().id)
            val rejection = mutation.getOrNull()?.let { domainRejection(it.body) }
            val error = mutation.exceptionOrNull() as? ClientError
            if (rejection != null || error?.state == EnsureState.INVALID_INPUT || error?.state == EnsureState.NEEDS_SIGN_IN) {
                return HostnameResult(error?.state ?: EnsureState.CONFLICT, clean,
                    rejection ?: error!!.safeMessage, accountId)
            }
            HostnameResult(EnsureState.RECOVERY_REQUIRED, clean, "The save could not be confirmed. Check again to read the account without creating the hostname twice.", accountId)
        } catch (error: ClientError) {
            HostnameResult(if (writeStarted) EnsureState.RECOVERY_REQUIRED else error.state, clean, error.safeMessage)
        } catch (_: Exception) {
            HostnameResult(if (writeStarted) EnsureState.RECOVERY_REQUIRED else EnsureState.FAILED, clean,
                if (writeStarted) "The connection failed after saving began. Check again to confirm the result without repeating the request."
                else "Could not reach Minecraft.How to check this hostname. Your choice has not been rejected; retry when connected.")
        }
    }

    private fun domainRejection(body: String): String? {
        val root = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull() ?: return null
        val errors = root.map("props")?.map("errors") ?: root.map("errors") ?: return null
        val value = errors["domain"]
        val message = (value as? String) ?: (value as? List<*>)?.firstOrNull() as? String
        return message?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }?.take(240)
    }

    data class DomainList(
        val signedIn: Boolean,
        val domains: List<DomainSummary>,
        val detail: String,
    )

    data class EnsureResult(
        val state: EnsureState,
        val hostname: String?,
        val domainId: Long? = null,
        val createdHostname: Boolean = false,
        val createdRecords: Int = 0,
        /** Matches the authenticated DNS record list, not a public resolver or player connection. */
        val javaRecordsMatch: Boolean = false,
        /** Matches the hostname route; Bedrock players still need the assigned UDP port. */
        val bedrockRecordsMatch: Boolean = false,
        val detail: String,
    )

    fun verifySession(cookieHeader: String): SessionVerification {
        if (cookieHeader.isBlank()) {
            return SessionVerification(false, "Sign in to Minecraft.How first.", 0)
        }
        return try {
            val page = getPage(cookieHeader, DNS_INDEX_PATH)
            val user = page.authenticatedUser()
            val count = ownedDomains(cookieHeader, page).size
            if (user != null) {
                val label = listOf("username", "name").firstNotNullOfOrNull { key ->
                    user.string(key)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && it.none(Char::isISOControl) }
                }
                val hostnames = if (count == 1) "1 hostname" else "$count hostnames"
                SessionVerification(true, (label?.let { "Signed in as $it" } ?: "Signed in") + " · $hostnames", count, accountLabel = label, accountId = user.long("id"))
            } else {
                SessionVerification(false, "Signed out of Minecraft.How. Sign in to manage your hostname.", 0)
            }
        } catch (error: ClientError) {
            if (error.state == EnsureState.NEEDS_SIGN_IN) SessionVerification(false, "Signed out of Minecraft.How. Sign in to manage your hostname.", 0)
            else SessionVerification(false, "Minecraft.How answered unexpectedly while confirming sign-in. Refresh to retry.", 0, reachable = false)
        } catch (_: Exception) {
            SessionVerification(false, "Could not reach Minecraft.How to confirm sign-in. Check the connection and refresh.", 0, reachable = false)
        }
    }

    fun loadZones(cookieHeader: String): ZoneList {
        val session = verifySession(cookieHeader)
        if (!session.signedIn) return ZoneList(false, emptyList(), session.detail)
        return try {
            val page = getPage(cookieHeader, DNS_CREATE_PATH)
            val zones = (page.props["parentDomains"] as? List<*>).orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.get("domain") as? String }
                .map { it.trim().trimEnd('.').lowercase(Locale.ROOT) }
                .filter(::isValidDnsHostname)
                .distinct()
            ZoneList(true, zones, if (zones.isEmpty()) "Minecraft.How did not return any available DNS zones." else "DNS zones loaded from Minecraft.How.")
        } catch (_: Exception) {
            ZoneList(true, emptyList(), "Could not load Minecraft.How DNS zones. Retry when the account is online.")
        }
    }

    fun listDomains(cookieHeader: String): DomainList {
        val session = verifySession(cookieHeader)
        if (!session.signedIn) return DomainList(false, emptyList(), session.detail)
        return try {
            val page = getPage(cookieHeader, DNS_INDEX_PATH)
            val domains = ownedDomains(cookieHeader, page)
            DomainList(true, domains, "Owned Minecraft.How DNS domains loaded.")
        } catch (_: Exception) {
            DomainList(true, emptyList(), "Could not load your Minecraft.How DNS domains.")
        }
    }

    /**
     * Reads the authenticated owned-domain page and its record-detail page without
     * issuing any write. Cookies are used only on the current worker-thread request
     * and are never included in this result.
     */
    fun readDnsRecordSnapshot(cookieHeader: String, hostname: String): DnsSnapshotResult {
        val cleanHostname = try {
            MinecraftHowDns.normalizeHostname(hostname)
        } catch (_: IllegalArgumentException) {
            return DnsSnapshotResult(DnsSnapshotState.INVALID_HOSTNAME, detail = "Enter a valid Minecraft.How hostname.")
        }
        if (cookieHeader.isBlank()) {
            return DnsSnapshotResult(DnsSnapshotState.NEEDS_SIGN_IN, hostname = cleanHostname,
                detail = "Sign in to Minecraft.How before reading DNS records.")
        }
        return try {
            val index = getPage(cookieHeader, DNS_INDEX_PATH)
            val accountId = index.authenticatedUser()?.long("id")
                ?: return DnsSnapshotResult(DnsSnapshotState.NEEDS_SIGN_IN, hostname = cleanHostname,
                    detail = "Sign in to Minecraft.How before reading DNS records.")
            val owned = ownedDomains(cookieHeader, index)
            val matches = owned.filter { it.hostname.equals(cleanHostname, ignoreCase = true) }
            if (matches.isEmpty()) {
                return DnsSnapshotResult(DnsSnapshotState.NOT_FOUND, accountId = accountId, hostname = cleanHostname,
                    detail = "This hostname is not present in the signed-in Minecraft.How account.")
            }
            if (matches.size != 1) {
                return DnsSnapshotResult(DnsSnapshotState.CONFLICT, accountId = accountId, hostname = cleanHostname,
                    detail = "Minecraft.How returned duplicate owned hostnames; DNS records were not read.")
            }
            val domain = matches.single()
            val records = getDomainRecords(cookieHeader, domain.id, accountId, cleanHostname).map { record ->
                DnsRecordSnapshot(record.id, record.type, record.name, record.content,
                    record.ttl, record.priority, record.port)
            }
            DnsSnapshotResult(DnsSnapshotState.READ, accountId, domain.id, cleanHostname, records,
                "Authenticated DNS records read without changes.")
        } catch (error: ClientError) {
            val state = when (error.state) {
                EnsureState.NEEDS_SIGN_IN -> DnsSnapshotState.NEEDS_SIGN_IN
                EnsureState.INVALID_INPUT -> DnsSnapshotState.INVALID_HOSTNAME
                else -> DnsSnapshotState.FAILED
            }
            DnsSnapshotResult(state, hostname = cleanHostname, detail = error.safeMessage)
        } catch (_: Exception) {
            DnsSnapshotResult(DnsSnapshotState.FAILED, hostname = cleanHostname,
                detail = "Could not read the authenticated Minecraft.How DNS records.")
        }
    }

    /**
     * Ensures an owned hostname has one apex A/AAAA/CNAME route and one Java SRV
     * record pointing at the allocated relay. Existing conflicting records are
     * left untouched. After every write the domain is read back before deciding
     * whether the next write is safe; an ambiguous write is never blindly replayed.
     */
    fun ensureHostnameAndRecords(
        cookieHeader: String,
        hostname: String,
        relayHost: String,
        relayTcpPort: Int,
        relayUdpPort: Int,
        allowMutations: Boolean = true,
        availableZones: Collection<String> = MinecraftHowDns.supportedSuffixes,
        allowHostnameCreation: Boolean = true,
        /** Where the Java SRV points when Java uses another host than the route record (Playit); null: the relay. */
        javaSrvHost: String? = null,
    ): EnsureResult {
        val cleanHostname = try {
            MinecraftHowDns.normalizeHostname(hostname, availableZones)
        } catch (_: IllegalArgumentException) {
            return result(EnsureState.INVALID_INPUT, null, "Enter a valid Minecraft.How hostname.")
        }
        val cleanRelay = normalizeRelayHost(relayHost)
            ?: return result(EnsureState.INVALID_INPUT, cleanHostname, "The assigned relay endpoint is invalid.")
        if (relayTcpPort !in PORT_RANGE || relayUdpPort !in PORT_RANGE) {
            return result(EnsureState.INVALID_INPUT, cleanHostname, "The assigned relay port is invalid.")
        }
        val srvHost = javaSrvHost?.let { normalizeRelayHost(it)?.takeIf { host -> host.kind == RelayKind.HOSTNAME }
            ?: return result(EnsureState.INVALID_INPUT, cleanHostname, "The Java address must be a hostname for an SRV record.") }
        if (cookieHeader.isBlank()) {
            return result(EnsureState.NEEDS_SIGN_IN, cleanHostname, "Sign in to Minecraft.How before creating DNS records.")
        }
        if (csrfToken(cookieHeader).isNullOrBlank()) {
            return result(EnsureState.NEEDS_SIGN_IN, cleanHostname, "Reload Minecraft.How sign-in so the DNS security cookie is available.")
        }

        val firstIndex = try {
            getPage(cookieHeader, DNS_INDEX_PATH)
        } catch (error: ClientError) {
            return result(error.asEnsureState(), cleanHostname, error.safeMessage)
        } catch (_: Exception) {
            return result(EnsureState.FAILED, cleanHostname, "Could not read the Minecraft.How DNS account.")
        }
        if (firstIndex.authenticatedUser() == null) {
            return result(EnsureState.NEEDS_SIGN_IN, cleanHostname, "Sign in to Minecraft.How before creating DNS records.")
        }

        var createdHostname = false
        val ownedDomains = try {
            ownedDomains(cookieHeader, firstIndex)
        } catch (error: ClientError) {
            return result(error.asEnsureState(), cleanHostname, error.safeMessage)
        } catch (_: Exception) {
            return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "Minecraft.How did not return the owned hostname list; no create is safe.")
        }
        val matches = ownedDomains.filter { it.hostname.equals(cleanHostname, ignoreCase = true) }
        if (matches.size > 1) {
            return result(EnsureState.CONFLICT, cleanHostname, "More than one owned domain has this hostname; no changes were made.")
        }
        var domain = matches.singleOrNull()
        if (domain == null) {
            if (!allowHostnameCreation) {
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname,
                    "The saved hostname is no longer present in this account. Phoenix will not create or reserve a hostname.")
            }
            if (!allowMutations) {
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The pending hostname is not present in the account yet. Reopen DNS setup to reconcile before another write.")
            }
            val normalizedZones = availableZones.map { it.trim().trim('.').lowercase(Locale.ROOT) }
            val zone = normalizedZones.firstOrNull { cleanHostname.endsWith(".$it") }
                ?: return result(EnsureState.INVALID_INPUT, cleanHostname, "Choose a hostname under a Minecraft.How DNS zone.")
            val zones = try {
                getPage(cookieHeader, DNS_CREATE_PATH).parentZones()
            } catch (error: ClientError) {
                return result(error.asEnsureState(), cleanHostname, error.safeMessage)
            } catch (_: Exception) {
                return result(EnsureState.FAILED, cleanHostname, "Could not load the Minecraft.How DNS zone list.")
            }
            if (zone !in zones) {
                return result(EnsureState.ZONE_UNAVAILABLE, cleanHostname, "Minecraft.How does not currently offer the selected DNS zone to this account.")
            }
            val createResult = runCatching {
                mutate(cookieHeader, "POST", DNS_INDEX_PATH, listOf("domain" to cleanHostname))
            }
            // Always reconcile with an authenticated GET before treating a create as complete.
            val reconciled = runCatching { ownedDomains(cookieHeader, getPage(cookieHeader, DNS_INDEX_PATH)) }.getOrNull()
            domain = reconciled?.singleOrNull { it.hostname.equals(cleanHostname, ignoreCase = true) }
            if (domain == null) {
                val createError = createResult.exceptionOrNull()
                val detail = if (createError != null) {
                    "Minecraft.How's hostname creation result is uncertain. Retry will check the account first; no duplicate was sent."
                } else {
                    "Minecraft.How did not return the requested hostname after creation. No duplicate was sent."
                }
                if (createError is ClientError && createError.state in RETRYABLE_AFTER_USER_ACTION) {
                    return result(createError.state, cleanHostname, createError.safeMessage)
                }
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, detail)
            }
            createdHostname = true
        }

        val domainId = domain.id
        val routeRecord = routeRecord(cleanHostname, cleanRelay)
        val srvRecord = srvRecord(cleanHostname, srvHost ?: cleanRelay, relayTcpPort)
        var records = try {
            getDomainRecords(cookieHeader, domainId).relativeTo(cleanHostname)
        } catch (error: ClientError) {
            return result(error.asEnsureState(), cleanHostname, error.safeMessage, domainId, createdHostname)
        } catch (_: Exception) {
            return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The owned hostname exists, but its records could not be read safely.", domainId, createdHostname)
        }

        var createdRecords = 0
        var lastWriteError: ClientError? = null
        fun reconcileAfterCreate(record: RecordIntent): Boolean {
            val mutation = runCatching { mutateRecord(cookieHeader, domainId, record) }
            lastWriteError = mutation.exceptionOrNull() as? ClientError
            // Re-read after both success and failure; never retry an ambiguous write in this call.
            records = runCatching { getDomainRecords(cookieHeader, domainId).relativeTo(cleanHostname) }.getOrNull()
                ?: return false
            if (records.any { it.matches(record) }) {
                if (mutation.isSuccess) createdRecords++
                return true
            }
            return false
        }

        val routeState = routeRecordState(records, routeRecord)
        if (routeState == MatchState.CONFLICT) {
            return result(EnsureState.CONFLICT, cleanHostname, "The hostname already has a different A, AAAA or CNAME record. Existing records were left untouched.", domainId, createdHostname, createdRecords)
        }
        if (routeState == MatchState.MISSING) {
            if (!allowMutations) {
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The pending route record is not present yet. Reopen DNS setup to reconcile before another write.", domainId, createdHostname, createdRecords)
            }
            if (!reconcileAfterCreate(routeRecord)) {
                lastWriteError?.takeIf { it.state in RETRYABLE_AFTER_USER_ACTION }?.let {
                    return result(it.state, cleanHostname, it.safeMessage, domainId, createdHostname, createdRecords)
                }
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The route record write could not be confirmed. Reopen DNS setup to reconcile it before retrying.", domainId, createdHostname, createdRecords)
            }
        }

        val srvState = srvRecordState(records, srvRecord)
        if (srvState == MatchState.CONFLICT) {
            return result(EnsureState.CONFLICT, cleanHostname, "The Java SRV name already points elsewhere or uses another port. Existing records were left untouched.", domainId, createdHostname, createdRecords)
        }
        if (srvState == MatchState.MISSING) {
            if (!allowMutations) {
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The pending Java SRV record is not present yet. Reopen DNS setup to reconcile before another write.", domainId, createdHostname, createdRecords)
            }
            if (!reconcileAfterCreate(srvRecord)) {
                lastWriteError?.takeIf { it.state in RETRYABLE_AFTER_USER_ACTION }?.let {
                    return result(it.state, cleanHostname, it.safeMessage, domainId, createdHostname, createdRecords)
                }
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The Java SRV write could not be confirmed. Reopen DNS setup to reconcile it before retrying.", domainId, createdHostname, createdRecords)
            }
        }

        // Final authoritative account readback. This says nothing about public propagation or gameplay.
        records = try {
            getDomainRecords(cookieHeader, domainId).relativeTo(cleanHostname)
        } catch (_: Exception) {
            return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "DNS records were saved, but the final account readback failed.", domainId, createdHostname, createdRecords)
        }
        val routeMatches = routeRecordState(records, routeRecord) == MatchState.MATCH
        val srvMatches = srvRecordState(records, srvRecord) == MatchState.MATCH
        if (!routeMatches || !srvMatches) {
            return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "Minecraft.How did not return the complete requested record set.", domainId, createdHostname, createdRecords)
        }
        return EnsureResult(
            state = EnsureState.SAVED,
            hostname = cleanHostname,
            domainId = domainId,
            createdHostname = createdHostname,
            createdRecords = createdRecords,
            javaRecordsMatch = true,
            bedrockRecordsMatch = true,
            detail = "Minecraft.How account records match the assigned relay. Public DNS propagation and player connectivity are not verified; Bedrock players need UDP port $relayUdpPort.",
        )
    }

    /**
     * Points an owned hostname's single apex route and its Java SRV at another relay, editing the existing records in
     * place (missing ones are created). Used to move players between the shared EU proxy and the US proxy, and back.
     * Every write is confirmed by an authenticated readback before the next one; an unconfirmed write is never replayed.
     * Either order of partial completion leaves a working address (Java follows the SRV, Bedrock follows the route).
     */
    fun switchRoute(
        cookieHeader: String,
        hostname: String,
        relayHost: String,
        relayTcpPort: Int,
        availableZones: Collection<String> = MinecraftHowDns.supportedSuffixes,
        /** Where the Java SRV points when Java uses another host than the route record (Playit); null: the relay. */
        javaSrvHost: String? = null,
    ): EnsureResult {
        val cleanHostname = try {
            MinecraftHowDns.normalizeHostname(hostname, availableZones)
        } catch (_: IllegalArgumentException) {
            return result(EnsureState.INVALID_INPUT, null, "Enter a valid Minecraft.How hostname.")
        }
        val cleanRelay = normalizeRelayHost(relayHost)
            ?: return result(EnsureState.INVALID_INPUT, cleanHostname, "The new relay address is invalid.")
        if (relayTcpPort !in PORT_RANGE) return result(EnsureState.INVALID_INPUT, cleanHostname, "The new Java port is invalid.")
        val srvHost = javaSrvHost?.let { normalizeRelayHost(it)?.takeIf { host -> host.kind == RelayKind.HOSTNAME }
            ?: return result(EnsureState.INVALID_INPUT, cleanHostname, "The Java address must be a hostname for an SRV record.") }
        if (cookieHeader.isBlank() || csrfToken(cookieHeader).isNullOrBlank()) {
            return result(EnsureState.NEEDS_SIGN_IN, cleanHostname, "Reload Minecraft.How sign-in so the DNS security cookie is available.")
        }
        val domainId = try {
            val owned = ownedDomains(cookieHeader, getPage(cookieHeader, DNS_INDEX_PATH))
                .filter { it.hostname.equals(cleanHostname, ignoreCase = true) }
            if (owned.size != 1) return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The hostname is not uniquely owned by this account; no changes were made.")
            owned.single().id
        } catch (error: ClientError) {
            return result(error.asEnsureState(), cleanHostname, error.safeMessage)
        } catch (_: Exception) {
            return result(EnsureState.FAILED, cleanHostname, "Could not read the Minecraft.How DNS account.")
        }
        val wanted = listOf(routeRecord(cleanHostname, cleanRelay), srvRecord(cleanHostname, srvHost ?: cleanRelay, relayTcpPort))
        var changed = 0
        for (intent in wanted) {
            val records = try {
                getDomainRecords(cookieHeader, domainId).relativeTo(cleanHostname)
            } catch (error: ClientError) {
                return result(error.asEnsureState(), cleanHostname, error.safeMessage, domainId)
            } catch (_: Exception) {
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "The hostname's records could not be read safely.", domainId)
            }
            val current = if (intent.type == "SRV") records.filter { it.type == "SRV" && normalizeDnsName(it.name.orEmpty()) == intent.name }
                else records.filter { it.name.isNullOrBlank() && it.type in ROUTE_RECORD_TYPES }
            if (current.size > 1) {
                return result(EnsureState.CONFLICT, cleanHostname, "The hostname has more than one ${if (intent.type == "SRV") "Java SRV" else "A/AAAA/CNAME"} record; no changes were made to it.", domainId)
            }
            if (current.singleOrNull()?.matches(intent) == true) continue
            val write = runCatching {
                current.singleOrNull()?.let { updateRecord(cookieHeader, domainId, it.id, intent) } ?: mutateRecord(cookieHeader, domainId, intent)
            }
            val confirmed = runCatching { getDomainRecords(cookieHeader, domainId).relativeTo(cleanHostname).any { it.matches(intent) } }.getOrDefault(false)
            if (!confirmed) {
                (write.exceptionOrNull() as? ClientError)?.takeIf { it.state in RETRYABLE_AFTER_USER_ACTION || it.state == EnsureState.INVALID_INPUT }
                    ?.let { return result(it.state, cleanHostname, it.safeMessage, domainId, createdRecords = changed) }
                return result(EnsureState.RECOVERY_REQUIRED, cleanHostname, "A DNS change could not be confirmed. Check the hostname's records before switching again.", domainId, createdRecords = changed)
            }
            changed++
        }
        return EnsureResult(EnsureState.SAVED, cleanHostname, domainId, false, changed, javaRecordsMatch = true, bedrockRecordsMatch = true,
            detail = "Players now resolve $cleanHostname to ${cleanRelay.value}" +
                (srvHost?.let { " (Java: ${it.value} port $relayTcpPort)" } ?: " (Java port $relayTcpPort)") +
                ". Old answers can stay cached for a few minutes.")
    }

    private fun updateRecord(cookieHeader: String, domainId: Long, recordId: Long, record: RecordIntent) {
        val fields = mutableListOf(
            "type" to record.type,
            "name" to record.name,
            "content" to record.content,
            "ttl" to record.ttl.toString(),
            "prio" to record.priority?.toString(),
        )
        if (record.type == "SRV") fields += "port" to record.port?.toString()
        // Laravel method spoofing: the site's own form PUTs this route; a form POST with _method is its standard equivalent.
        mutate(cookieHeader, "POST", "$DNS_INDEX_PATH/$domainId/records/$recordId", fields + ("_method" to "PUT"))
    }

    /** Laravel pagination is part of the live DNS contract. Read every page before any create. */
    private fun ownedDomains(cookie: String, initial: InertiaPage): List<DomainSummary> {
        val account = initial.authenticatedUser()?.long("id")
            ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Sign in to Minecraft.How before managing hostnames.")
        val pagination = initial.props["domains"] as? Map<*, *> ?: return initial.domainSummaries()
        val last = pagination.long("last_page")?.takeIf { it in 1..20 }
            ?: throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How did not return complete hostname pagination; no create is safe.")
        val total = pagination.long("total")?.takeIf { it >= 0 }
            ?: throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How did not return a hostname total; no create is safe.")
        val result = mutableListOf<DomainSummary>()
        for (number in 1..last.toInt()) {
            val page = if (number == 1) initial else getPage(cookie, "$DNS_INDEX_PATH?page=$number")
            val meta = page.props["domains"] as? Map<*, *>
            if (page.authenticatedUser()?.long("id") != account || meta?.long("current_page") != number.toLong() ||
                meta.long("last_page") != last || meta.long("total") != total) {
                throw ClientError(EnsureState.RECOVERY_REQUIRED, "The account or hostname list changed while reading it. Refresh before saving.")
            }
            result += page.domainSummaries()
        }
        if (result.size.toLong() != total || result.map { it.id }.distinct().size != result.size) {
            throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How returned an incomplete hostname list; no create is safe.")
        }
        return result
    }

    private fun getPage(cookieHeader: String, path: String): InertiaPage {
        var response = executeSameOriginRedirects(
            HttpRequest("GET", path, pageHeaders(cookieHeader), null),
        )
        // An Inertia version mismatch is a GET-only page reload. Bootstrap the real
        // version from the provider's HTML, then retry this idempotent GET once.
        if (response.statusCode == INERTIA_CONFLICT_STATUS) {
            bootstrapVersionFromConflict(cookieHeader, response)
            response = executeSameOriginRedirects(HttpRequest("GET", path, pageHeaders(cookieHeader), null))
        }
        if (response.statusCode == INERTIA_CONFLICT_STATUS) {
            throw ClientError(EnsureState.FAILED, "Minecraft.How still reports an Inertia page-version mismatch after refresh.")
        }
        if (response.statusCode == 401 || response.statusCode == 403 || response.statusCode == CSRF_EXPIRED_STATUS) throw ClientError(EnsureState.NEEDS_SIGN_IN, "Sign in to Minecraft.How before managing DNS.")
        if (response.statusCode !in 200..299) throw ClientError(EnsureState.FAILED, "Minecraft.How DNS request failed with HTTP ${response.statusCode}.")
        val root = runCatching { Json.parse(response.body) as? Map<*, *> }.getOrNull()
            ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How did not return an authenticated DNS page.")
        val props = root.map("props") ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How did not return an authenticated DNS page.")
        (root["version"] as? String)?.takeIf(String::isNotBlank)?.let { inertiaVersion = it }
        return InertiaPage(props)
    }

    private fun mutate(cookieHeader: String, method: String, path: String, fields: List<Pair<String, String?>>): HttpResponse {
        val token = csrfToken(cookieHeader)
            ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Reload Minecraft.How sign-in so the DNS security cookie is available.")
        val headers = pageHeaders(cookieHeader) + mapOf(
            "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "X-XSRF-TOKEN" to token,
        )
        val body = fields.joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value.orEmpty())}" }
        val response = executeSameOriginRedirects(HttpRequest(method, path, headers, body))
        if (response.statusCode == INERTIA_CONFLICT_STATUS) {
            // Never replay a mutation after an Inertia 409. Refresh the bootstrap
            // version only; the caller will reconcile through an authenticated GET.
            runCatching { bootstrapVersionFromConflict(cookieHeader, response) }
            throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How requested a page-version refresh during the save. Phoenix did not repeat the write; reconcile the account state before another attempt.")
        }
        if (response.statusCode == 401 || response.statusCode == 403) throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How sign-in expired before saving DNS.")
        // Laravel 419 means the XSRF token was rejected before the controller ran. Never
        // replay with the same token; the user reloads sign-in to get a fresh cookie.
        if (response.statusCode == CSRF_EXPIRED_STATUS) throw ClientError(EnsureState.NEEDS_SIGN_IN,
            "Minecraft.How's security token expired. Reload Minecraft.How sign-in; Phoenix did not repeat the write.")
        if (response.statusCode == 422) throw ClientError(EnsureState.INVALID_INPUT,
            domainRejection(response.body) ?: "Minecraft.How rejected this hostname or record. Check the name and try another if it is taken.")
        if (response.statusCode !in 200..299) throw ClientError(EnsureState.FAILED, "Minecraft.How rejected a DNS change with HTTP ${response.statusCode}.")
        return response
    }

    private fun mutateRecord(cookieHeader: String, domainId: Long, record: RecordIntent) {
        val fields = mutableListOf(
            "type" to record.type,
            "name" to record.name,
            "content" to record.content,
            "ttl" to record.ttl.toString(),
            "prio" to record.priority?.toString(),
        )
        if (record.type == "SRV") fields += "port" to record.port?.toString()
        mutate(cookieHeader, "POST", "$DNS_INDEX_PATH/$domainId/records", fields)
    }

    private fun getDomainRecords(
        cookieHeader: String,
        domainId: Long,
        expectedAccountId: Long? = null,
        expectedHostname: String? = null,
    ): List<AccountRecord> {
        val page = getPage(cookieHeader, "$DNS_INDEX_PATH/$domainId")
        if (expectedAccountId != null && page.authenticatedUser()?.long("id") != expectedAccountId) {
            throw ClientError(EnsureState.RECOVERY_REQUIRED, "The Minecraft.How account changed while reading DNS records.")
        }
        val domain = page.props.map("domain") ?: throw ClientError(EnsureState.FAILED, "Minecraft.How did not return the selected domain.")
        val responseId = domain.long("id")
        if (responseId != domainId) throw ClientError(EnsureState.FAILED, "Minecraft.How returned a different DNS domain.")
        if (expectedHostname != null && domain.string("domain")?.let(::normalizeDnsName) != expectedHostname) {
            throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How returned a different hostname while reading DNS records.")
        }
        val rawRecords = domain["dns_records"] as? List<*>
            ?: throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How did not return a complete record list; no additional write is safe.")
        return rawRecords.map { raw ->
            val record = raw as? Map<*, *> ?: throw incompleteRecords()
            val id = record.long("id")?.takeIf { it > 0 } ?: throw incompleteRecords()
            val type = record.string("type")?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: throw incompleteRecords()
            val content = record.string("content") ?: throw incompleteRecords()
            if (!record.containsKey("name") || (record["name"] != null && record["name"] !is String)) throw incompleteRecords()
            AccountRecord(
                id = id,
                type = type,
                name = record.string("name")?.trim()?.takeIf(String::isNotEmpty),
                content = content,
                ttl = record.long("ttl")?.toInt() ?: 600,
                priority = record.long("prio")?.toInt(),
                port = record.long("port")?.toInt(),
            )
        }
    }

    private fun incompleteRecords() = ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How returned an incomplete record; no additional write is safe.")

    /**
     * Every provider request goes through here. During a cooldown nothing is sent; an HTTP 429 records a cooldown from
     * Retry-After (bounded to 30 days) or a local backoff (60 s doubling to 15 min) and is never retried automatically.
     */
    private fun send(request: HttpRequest): HttpResponse {
        val now = clock()
        val until = cooldownUntilEpochMillis
        if (until > now) throw ClientError(EnsureState.RATE_LIMITED, rateLimitMessage(until, now))
        val response = transport.execute(request)
        if (response.statusCode == 429) {
            val local = (LOCAL_BACKOFF_MS shl minOf(consecutiveLimits, 4)).coerceAtMost(MAX_LOCAL_BACKOFF_MS)
            consecutiveLimits++
            val deadline = NetBirdApiException.parseRetryAfter(response.header("Retry-After"), now)
                ?.takeIf { it > now } ?: (now + local)
            cooldownUntilEpochMillis = deadline
            runCatching { cooldownSink?.invoke(deadline) }
            throw ClientError(EnsureState.RATE_LIMITED, rateLimitMessage(deadline, now))
        }
        consecutiveLimits = 0
        return response
    }

    private fun rateLimitMessage(until: Long, now: Long): String =
        "Minecraft.How asked Phoenix to slow down. Try again after " +
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until)) +
            " (in ${(until - now + 999) / 1000}s)."

    private fun executeSameOriginRedirects(initial: HttpRequest): HttpResponse {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            val response = send(request)
            val location = response.header("Location")
            if (response.statusCode !in REDIRECT_STATUSES || location.isNullOrBlank()) return response
            if (hop == MAX_REDIRECTS) throw ClientError(EnsureState.FAILED, "Minecraft.How redirected too many times.")
            val nextPath = sameOriginPath(location)
                ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How redirected away from its secure account site.")
            // Laravel/Inertia converts write redirects to 303. 302 is accepted for its
            // legacy post-redirect-get path and is always converted to a same-origin GET.
            request = HttpRequest("GET", nextPath, request.headers.filterKeys { it.equals("Cookie", true) || it.equals("Accept", true) || it.equals("X-Inertia", true) || it.equals("X-Inertia-Version", true) || it.equals("X-Requested-With", true) }, null)
        }
        throw ClientError(EnsureState.FAILED, "Minecraft.How redirect handling failed.")
    }

    private fun bootstrapVersionFromConflict(cookieHeader: String, response: HttpResponse) {
        val location = response.header("X-Inertia-Location")
            ?: throw ClientError(EnsureState.FAILED, "Minecraft.How requested a page refresh without a safe location.")
        val path = sameOriginPath(location)
            ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How requested a page refresh outside its secure account site.")
        val htmlResponse = executeHtmlSameOriginRedirects(
            HttpRequest("GET", path, htmlHeaders(cookieHeader), null),
        )
        if (htmlResponse.statusCode !in 200..299) {
            throw ClientError(EnsureState.FAILED, "Minecraft.How page-version bootstrap failed with HTTP ${htmlResponse.statusCode}.")
        }
        val bootstrap = MinecraftHowInertia.parseHtmlBootstrap(htmlResponse.body)
            ?: throw ClientError(EnsureState.FAILED, "Minecraft.How page did not contain a valid Inertia bootstrap.")
        inertiaVersion = bootstrap.version
    }

    private fun executeHtmlSameOriginRedirects(initial: HttpRequest): HttpResponse {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            val response = send(request)
            val location = response.header("Location")
            if (response.statusCode !in HTML_REDIRECT_STATUSES || location.isNullOrBlank()) return response
            if (hop == MAX_REDIRECTS) throw ClientError(EnsureState.FAILED, "Minecraft.How page bootstrap redirected too many times.")
            val nextPath = sameOriginPath(location)
                ?: throw ClientError(EnsureState.NEEDS_SIGN_IN, "Minecraft.How page bootstrap redirected away from its secure account site.")
            request = HttpRequest("GET", nextPath, initial.headers, null)
        }
        throw ClientError(EnsureState.FAILED, "Minecraft.How page bootstrap redirect handling failed.")
    }

    private fun pageHeaders(cookieHeader: String): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        put("X-Inertia", "true")
        put("X-Requested-With", "XMLHttpRequest")
        inertiaVersion?.let { put("X-Inertia-Version", it) }
        put("Cookie", cookieHeader)
    }

    private fun htmlHeaders(cookieHeader: String): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml",
        "Cookie" to cookieHeader,
    )

    private fun sameOriginPath(location: String): String? = runCatching {
        val base = URL(ORIGIN)
        val target = URL(base, location)
        if (target.protocol != "https" || !target.host.equals(base.host, ignoreCase = true) || target.port !in listOf(-1, 443) || target.userInfo != null) return null
        val path = target.file.ifBlank { "/" }
        if (!path.startsWith("/") || path.startsWith("//")) return null
        path
    }.getOrNull()

    private fun routeRecord(hostname: String, relay: RelayHost): RecordIntent = when (relay.kind) {
        RelayKind.IPV4 -> RecordIntent("A", null, relay.value)
        RelayKind.IPV6 -> RecordIntent("AAAA", null, relay.value)
        RelayKind.HOSTNAME -> RecordIntent("CNAME", null, relay.value)
    }

    private fun srvRecord(hostname: String, relay: RelayHost, tcpPort: Int) = RecordIntent(
        type = "SRV",
        name = "_minecraft._tcp",
        content = if (relay.kind == RelayKind.HOSTNAME) relay.value else hostname,
        priority = 0,
        port = tcpPort,
    )

    private fun routeRecordState(records: List<AccountRecord>, expected: RecordIntent): MatchState {
        val routeRecords = records.filter { it.name.isNullOrBlank() && it.type in ROUTE_RECORD_TYPES }
        if (routeRecords.isEmpty()) return MatchState.MISSING
        return if (routeRecords.size == 1 && routeRecords.single().matches(expected)) MatchState.MATCH else MatchState.CONFLICT
    }

    /**
     * Provider record names may be blank, "@", the full hostname or a fully qualified
     * sub-name. Normalize them relative to the owned hostname so an existing apex route
     * or SRV is never mistaken for a missing one (which would write a conflicting A/CNAME).
     */
    private fun List<AccountRecord>.relativeTo(hostname: String): List<AccountRecord> = map { record ->
        val name = normalizeDnsName(record.name.orEmpty())
        val relative = when {
            name.isEmpty() || name == "@" || name == hostname -> null
            name.endsWith(".$hostname") -> name.removeSuffix(".$hostname")
            else -> name
        }
        record.copy(name = relative)
    }

    private fun srvRecordState(records: List<AccountRecord>, expected: RecordIntent): MatchState {
        val srvRecords = records.filter { it.type == "SRV" && normalizeDnsName(it.name.orEmpty()) == expected.name }
        if (srvRecords.isEmpty()) return MatchState.MISSING
        return if (srvRecords.size == 1 && srvRecords.single().matches(expected)) MatchState.MATCH else MatchState.CONFLICT
    }

    private fun result(
        state: EnsureState,
        hostname: String?,
        detail: String,
        domainId: Long? = null,
        createdHostname: Boolean = false,
        createdRecords: Int = 0,
    ) = EnsureResult(state, hostname, domainId, createdHostname, createdRecords, detail = detail)

    private data class InertiaPage(val props: Map<*, *>) {
        fun authenticatedUser(): Map<*, *>? {
            val user = props.map("auth")?.get("user") as? Map<*, *> ?: return null
            return user.takeIf { it.long("id")?.let { id -> id > 0L } == true }
        }

        fun domainSummaries(): List<DomainSummary> = (((props["domains"] as? List<*>) ?: (props.map("domains")?.get("data") as? List<*>))
            ?: throw ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How did not return the owned hostname list; no additional create is safe."))
            .map { item ->
                val value = item as? Map<*, *> ?: throw incompleteDomains()
                val id = value.long("id")?.takeIf { it > 0 } ?: throw incompleteDomains()
                val hostname = value.string("domain")?.let(::normalizeDnsName)?.takeIf(::isValidDnsHostname) ?: throw incompleteDomains()
                DomainSummary(id, hostname)
            }

        private fun incompleteDomains() = ClientError(EnsureState.RECOVERY_REQUIRED, "Minecraft.How returned an incomplete owned hostname list; no create is safe.")

        fun parentZones(): Set<String> = (props["parentDomains"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("domain") as? String }
            .map { normalizeDnsName(it) }
            .filter(::isValidDnsHostname)
            .toSet()
    }

    private data class RelayHost(val value: String, val kind: RelayKind)
    private enum class RelayKind { HOSTNAME, IPV4, IPV6 }
    private enum class MatchState { MATCH, MISSING, CONFLICT }
    private data class RecordIntent(
        val type: String,
        val name: String?,
        val content: String,
        val ttl: Int = DEFAULT_TTL,
        val priority: Int? = null,
        val port: Int? = null,
    )
    private data class AccountRecord(
        val id: Long,
        val type: String,
        val name: String?,
        val content: String,
        val ttl: Int,
        val priority: Int?,
        val port: Int?,
    ) {
        fun matches(expected: RecordIntent): Boolean =
            type == expected.type &&
                normalizeDnsName(name.orEmpty()) == normalizeDnsName(expected.name.orEmpty()) &&
                normalizeContent(content) == normalizeContent(expected.content) &&
                (expected.priority == null || priority == expected.priority) &&
                (expected.port == null || port == expected.port)
    }

    private class ClientError(val state: EnsureState, val safeMessage: String) : Exception(safeMessage) {
        fun asEnsureState(): EnsureState = state
    }

    companion object {
        private const val LOCAL_BACKOFF_MS = 60_000L
        private const val MAX_LOCAL_BACKOFF_MS = 15 * 60_000L

        /** Process-wide provider cooldown after HTTP 429. The app restores and persists it (survives restarts). */
        @Volatile var cooldownUntilEpochMillis: Long = 0L
            private set
        @Volatile private var consecutiveLimits = 0
        @Volatile var cooldownSink: ((Long) -> Unit)? = null
        @Volatile internal var clock: () -> Long = System::currentTimeMillis
        fun restoreCooldown(deadlineEpochMillis: Long) { cooldownUntilEpochMillis = deadlineEpochMillis; consecutiveLimits = 0 }

        const val ORIGIN = "https://minecraft.how"
        const val DNS_INDEX_PATH = "/dashboard/dns"
        const val DNS_CREATE_PATH = "/dashboard/dns/create"
        private const val DEFAULT_TTL = 600
        private const val MAX_REDIRECTS = 3
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val PORT_RANGE = 1..65535
        private const val INERTIA_CONFLICT_STATUS = 409
        private const val CSRF_EXPIRED_STATUS = 419
        private val RETRYABLE_AFTER_USER_ACTION = setOf(EnsureState.NEEDS_SIGN_IN, EnsureState.RATE_LIMITED)
        private val REDIRECT_STATUSES = setOf(302, 303)
        private val HTML_REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val ROUTE_RECORD_TYPES = setOf("A", "AAAA", "CNAME")
        private val HOSTNAME_REGEX = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
        private val IPV4_REGEX = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")

        private fun csrfToken(cookieHeader: String): String? {
            val raw = cookieHeader.split(';').asSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("XSRF-TOKEN=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.takeIf(String::isNotBlank)
                ?: return null
            return runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)
        }

        private fun normalizeRelayHost(value: String): RelayHost? {
            val clean = value.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (clean.isEmpty() || clean.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            if (IPV4_REGEX.matches(clean)) {
                val parts = clean.split('.').mapNotNull(String::toIntOrNull)
                return if (parts.size == 4 && parts.all { it in 0..255 }) RelayHost(clean, RelayKind.IPV4) else null
            }
            if (clean.contains(':')) {
                if (clean.any { it !in "0123456789abcdef:." }) return null
                val valid = runCatching { java.net.InetAddress.getByName(clean) is Inet6Address }.getOrDefault(false)
                return if (valid) RelayHost(clean, RelayKind.IPV6) else null
            }
            return clean.takeIf(::isValidDnsHostname)?.let { RelayHost(it, RelayKind.HOSTNAME) }
        }

        private fun isValidDnsHostname(value: String): Boolean = value.length <= 253 && HOSTNAME_REGEX.matches(value)
        private fun normalizeDnsName(value: String): String = value.trim().trimEnd('.').lowercase(Locale.ROOT)
        private fun normalizeContent(value: String): String = normalizeDnsName(value)
        private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>
        private fun Map<*, *>.string(key: String): String? = when (val value = this[key]) {
            is String -> value
            is Number -> value.toString()
            else -> null
        }
        private fun Map<*, *>.long(key: String): Long? = when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}

internal data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String?,
)

internal data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()
}

internal fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        require(request.path.startsWith('/') && !request.path.startsWith("//"))
        val connection = (URL(MinecraftHowClient.ORIGIN + request.path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = request.method
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (request.body != null) {
                doOutput = true
                setFixedLengthStreamingMode(request.body.toByteArray(StandardCharsets.UTF_8).size)
            }
        }
        try {
            request.body?.let { body -> connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use(::readBounded)?.toString(StandardCharsets.UTF_8).orEmpty()
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            return HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_RESPONSE_BYTES) { "Minecraft.How response exceeded the size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}

/** Small strict JSON reader keeps the client testable without Android's stub JSON classes. */
internal object Json {
    fun parse(source: String): Any? {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= 2 * 1024 * 1024) { "JSON response is too large" }
        return Parser(source).parse()
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            val result = value(0)
            whitespace()
            require(index == source.length) { "Unexpected trailing JSON data" }
            return result
        }

        private fun value(depth: Int): Any? {
            require(depth <= 48) { "JSON nesting is too deep" }
            whitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> throw IllegalArgumentException("Invalid JSON value")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            index++
            whitespace()
            val result = LinkedHashMap<String, Any?>()
            if (consume('}')) return result
            while (true) {
                whitespace()
                require(index < source.length && source[index] == '"') { "Expected a JSON object key" }
                val key = stringValue()
                whitespace()
                require(consume(':')) { "Expected ':' after JSON object key" }
                result[key] = value(depth)
                whitespace()
                if (consume('}')) return result
                require(consume(',')) { "Expected ',' in JSON object" }
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            index++
            whitespace()
            val result = ArrayList<Any?>()
            if (consume(']')) return result
            while (true) {
                result += value(depth)
                whitespace()
                if (consume(']')) return result
                require(consume(',')) { "Expected ',' in JSON array" }
            }
        }

        private fun stringValue(): String {
            require(consume('"')) { "Expected JSON string" }
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < source.length) { "Truncated JSON escape" }
                        when (val escape = source[index++]) {
                            '"', '\\', '/' -> result.append(escape)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Truncated JSON unicode escape" }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid JSON unicode escape")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        }
                    }
                    else -> {
                        require(char >= ' ') { "Control character in JSON string" }
                        result.append(char)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun numberValue(): Number {
            val start = index
            consume('-')
            require(index < source.length) { "Truncated JSON number" }
            if (source[index] == '0') index++ else {
                require(source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            var floating = false
            if (consume('.')) {
                floating = true
                require(index < source.length && source[index].isDigit()) { "Invalid JSON fraction" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index] in "eE") {
                floating = true
                index++
                if (index < source.length && source[index] in "+-") index++
                require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val text = source.substring(start, index)
            return if (floating) text.toDouble() else text.toLongOrNull() ?: text.toDouble()
        }

        private fun <T> literal(text: String, result: T): T {
            require(source.regionMatches(index, text, 0, text.length)) { "Invalid JSON literal" }
            index += text.length
            return result
        }

        private fun whitespace() {
            while (index < source.length && source[index] in " \t\r\n") index++
        }

        private fun consume(char: Char): Boolean {
            if (index >= source.length || source[index] != char) return false
            index++
            return true
        }
    }
}
