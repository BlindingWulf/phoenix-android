package dev.phoenix.server

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * App-side browser handoff. A provider-approved client/authorize/exchange contract
 * is required; there are deliberately no guessed Minecraft.How endpoints or IDs.
 * This does not turn a Google token or browser navigation into a DNS account session.
 *
 * The [journal] is the only authority on whether an attempt may still exchange its code. Saved
 * screen state carries just an attempt id, so an old or replayed snapshot can never re-arm one.
 */
internal class MinecraftHowBrowserConnection(
    private val contract: Contract,
    private val journal: MinecraftHowHandoffJournal,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    class Contract(val clientId: String, val authorizePath: String, val exchangePath: String, val callback: URI) {
        init {
            require(clientId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
            for (path in listOf(authorizePath, exchangePath)) {
                val uri = URI(path)
                require(path.startsWith('/') && !path.startsWith("//") && !uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null && uri.normalize().path == path)
            }
            require(callback.scheme == CALLBACK_SCHEME && callback.host == "connected" && callback.rawPath.orEmpty().isEmpty() && callback.rawUserInfo == null && callback.port == -1 && callback.rawQuery == null && callback.rawFragment == null)
        }
        override fun toString() = "MinecraftHowBrowserContract"
    }

    class Attempt internal constructor(
        /** Random handle saved with the screen; it carries no authority by itself. */
        val id: String,
        internal val state: String,
        internal val verifier: String,
        internal val startedAt: Long,
        val authorizationUrl: String,
    ) {
        override fun toString() = "MinecraftHowBrowserAttempt"
    }

    enum class Outcome { CONNECTED, CANCELED, INVALID_CALLBACK, EXPIRED, ALREADY_USED, EXCHANGE_UNCONFIRMED, SESSION_UNVERIFIED, NOT_RECORDED }

    /** What a recreated sign-in screen should do with its saved attempt id. */
    sealed interface Resume {
        /** Still waiting for the browser; a callback may complete it once. */
        class Pending(val attempt: Attempt) : Resume
        /** The code may already have been spent and the result is unknown: verify read-only, never resend. */
        object Unresolved : Resume
        /** The saved screen refers to an attempt that is no longer current; it was discarded. */
        object Stale : Resume
        object None : Resume
    }

    /** Cookie installation is caller-owned and happens only after native account verification. */
    fun interface SessionReceiver {
        fun verifyAndInstall(setCookieHeaders: List<String>): Boolean
    }

    /** Returns null when the attempt could not be durably recorded; the browser must not be opened then. */
    fun begin(): Attempt? {
        val state = randomUrlToken(32)
        val verifier = randomUrlToken(64)
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val query = form(listOf("client_id" to contract.clientId, "provider" to "google", "response_type" to "code",
            "redirect_uri" to contract.callback.toString(), "state" to state, "code_challenge" to challenge,
            "code_challenge_method" to "S256"))
        val attempt = Attempt(randomUrlToken(16), state, verifier, clockMillis(), MinecraftHowClient.ORIGIN + contract.authorizePath + "?" + query)
        return attempt.takeIf { journal.start(it) }
    }

    /** The attempt a browser callback may complete, whichever screen instance receives it. */
    fun pending(): Attempt? = journal.pending()

    fun resume(savedAttemptId: String?): Resume {
        val record = journal.current() ?: return if (savedAttemptId != null) Resume.Stale else Resume.None
        return when (record.phase) {
            MinecraftHowHandoffJournal.Phase.EXCHANGING, MinecraftHowHandoffJournal.Phase.UNCERTAIN -> Resume.Unresolved
            MinecraftHowHandoffJournal.Phase.PENDING -> {
                val attempt = journal.pending()
                when {
                    attempt != null && attempt.id == savedAttemptId -> Resume.Pending(attempt)
                    // A fresh screen (or a snapshot of a different attempt) abandons the leftover attempt.
                    else -> { journal.finish(record.id, onlyIfPending = true); if (savedAttemptId != null) Resume.Stale else Resume.None }
                }
            }
            MinecraftHowHandoffJournal.Phase.FINISHED -> if (savedAttemptId != null) Resume.Stale else Resume.None
        }
    }

    /** Call after the read-only account check that follows [Resume.Unresolved]. */
    fun acknowledgeUnresolved() = journal.acknowledgeUnresolved()

    /**
     * One POST maximum per attempt, including after timeout, redirects, provider errors, process
     * death or screen restoration. The journal moves to EXCHANGING (and drops the verifier) before
     * the request is sent, so nothing can send it again.
     */
    fun complete(attempt: Attempt, callback: String?, receiver: SessionReceiver): Outcome {
        val elapsed = clockMillis() - attempt.startedAt
        val early = when {
            elapsed !in 0..MAX_AGE_MS -> Outcome.EXPIRED
            callback == null -> Outcome.CANCELED
            else -> null
        }
        if (early != null) return if (journal.finish(attempt.id, onlyIfPending = true)) early else Outcome.ALREADY_USED
        val params = runCatching {
            val uri = URI(callback)
            require(uri.scheme == contract.callback.scheme && uri.rawAuthority == contract.callback.rawAuthority && uri.rawPath.orEmpty() == contract.callback.rawPath.orEmpty() && uri.rawFragment == null)
            val query = uri.rawQuery ?: error("Missing response")
            require(query.length <= 4096)
            val values = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                require(name !in values)
                values[name] = value
            }
            require(values.keys.all { it in setOf("state", "code", "error") })
            require(MessageDigest.isEqual(attempt.state.toByteArray(), values["state"].orEmpty().toByteArray()))
            values
        }.getOrNull()
        val code = params?.takeIf { it["error"] == null }?.get("code")?.takeIf { it.length in 1..2048 && it.none(Char::isISOControl) }
        if (code == null) {
            val outcome = if (params?.get("error") != null && params["code"] == null) Outcome.CANCELED else Outcome.INVALID_CALLBACK
            return if (journal.finish(attempt.id, onlyIfPending = true)) outcome else Outcome.ALREADY_USED
        }
        val verifier = when (val claim = journal.beginExchange(attempt.id)) {
            MinecraftHowHandoffJournal.Claim.NotPending -> return Outcome.ALREADY_USED
            MinecraftHowHandoffJournal.Claim.NotRecorded -> return Outcome.NOT_RECORDED
            is MinecraftHowHandoffJournal.Claim.Claimed -> claim.verifier
        }
        val response = runCatching {
            transport.execute(HttpRequest("POST", contract.exchangePath,
                mapOf("Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8", "Accept" to "application/json"),
                form(listOf("grant_type" to "authorization_code", "client_id" to contract.clientId,
                    "redirect_uri" to contract.callback.toString(), "code" to code, "code_verifier" to verifier))))
        }.getOrNull()
        if (response == null || response.statusCode !in 200..299) {
            journal.markUncertain(attempt.id)
            return Outcome.EXCHANGE_UNCONFIRMED
        }
        val cookies = response.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
        val outcome = if (cookies.isNotEmpty() && runCatching { receiver.verifyAndInstall(cookies) }.getOrDefault(false)) Outcome.CONNECTED else Outcome.SESSION_UNVERIFIED
        journal.finish(attempt.id, onlyIfPending = false)
        return outcome
    }

    companion object {
        /** Matches the manifest's `${applicationId}.minecraft-how`, so each build owns its own return. */
        val CALLBACK_SCHEME: String = BuildConfig.APPLICATION_ID + ".minecraft-how"
        val CALLBACK_URI: URI = URI("$CALLBACK_SCHEME://connected")

        /** The provider-issued contract compiled into this build, or null while Minecraft.How has not issued one. */
        fun configuredContract(
            clientId: String = BuildConfig.MINECRAFT_HOW_CLIENT_ID,
            authorizePath: String = BuildConfig.MINECRAFT_HOW_AUTHORIZE_PATH,
            exchangePath: String = BuildConfig.MINECRAFT_HOW_EXCHANGE_PATH,
        ): Contract? {
            if (clientId.isBlank() || authorizePath.isBlank() || exchangePath.isBlank()) return null
            return runCatching { Contract(clientId, authorizePath, exchangePath, CALLBACK_URI) }.getOrNull()
        }

        internal const val MAX_AGE_MS = 10 * 60 * 1000L
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun randomUrlToken(size: Int): String = encode(ByteArray(size).also { SecureRandom().nextBytes(it) })
        private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        private fun form(fields: List<Pair<String, String>>) = fields.joinToString("&") {
            URLEncoder.encode(it.first, StandardCharsets.UTF_8.name()) + "=" + URLEncoder.encode(it.second, StandardCharsets.UTF_8.name())
        }
    }
}

/**
 * Durable record of the single browser handoff in flight. Every transition is one atomic,
 * synchronously committed write; a write that is not confirmed fails closed (no browser, no POST).
 * Lives in app-private preferences, which are excluded from backup and device transfer.
 */
internal class MinecraftHowHandoffJournal(private val store: Store) {
    interface Store {
        fun read(): Map<String, String>
        /** Atomically replaces the whole record; returns true only once it is durably written. */
        fun replace(values: Map<String, String>): Boolean
    }

    enum class Phase { PENDING, EXCHANGING, UNCERTAIN, FINISHED }
    class Record(val id: String, val phase: Phase)

    sealed interface Claim {
        class Claimed(val verifier: String) : Claim
        object NotPending : Claim
        object NotRecorded : Claim
    }

    fun start(attempt: MinecraftHowBrowserConnection.Attempt): Boolean = synchronized(LOCK) {
        store.replace(mapOf(ID to attempt.id, PHASE to Phase.PENDING.name, STATE to attempt.state, VERIFIER to attempt.verifier,
            STARTED to attempt.startedAt.toString(), URL to attempt.authorizationUrl))
    }

    fun current(): Record? = synchronized(LOCK) { readRecord() }

    fun pending(): MinecraftHowBrowserConnection.Attempt? = synchronized(LOCK) {
        val values = store.read()
        if (readRecord(values)?.phase != Phase.PENDING) return null
        MinecraftHowBrowserConnection.Attempt(values[ID] ?: return null, values[STATE] ?: return null, values[VERIFIER] ?: return null,
            values[STARTED]?.toLongOrNull() ?: return null, values[URL] ?: return null)
    }

    /** PENDING → EXCHANGING, erasing the verifier from storage before the caller may send it. */
    fun beginExchange(id: String): Claim = synchronized(LOCK) {
        val values = store.read()
        val verifier = values[VERIFIER]
        if (readRecord(values)?.let { it.id == id && it.phase == Phase.PENDING } != true || verifier == null) return Claim.NotPending
        if (!store.replace(mapOf(ID to id, PHASE to Phase.EXCHANGING.name))) return Claim.NotRecorded
        Claim.Claimed(verifier)
    }

    fun markUncertain(id: String): Boolean = transition(id, Phase.UNCERTAIN) { it == Phase.EXCHANGING }

    /** Ends the attempt. With [onlyIfPending], returns false if it had already been used or replaced. */
    fun finish(id: String, onlyIfPending: Boolean): Boolean =
        transition(id, Phase.FINISHED) { !onlyIfPending || it == Phase.PENDING }

    fun acknowledgeUnresolved(): Boolean = synchronized(LOCK) {
        val record = readRecord() ?: return true
        if (record.phase != Phase.EXCHANGING && record.phase != Phase.UNCERTAIN) return true
        store.replace(mapOf(ID to record.id, PHASE to Phase.FINISHED.name))
    }

    private inline fun transition(id: String, to: Phase, allowed: (Phase) -> Boolean): Boolean = synchronized(LOCK) {
        val record = readRecord()
        if (record == null || record.id != id || !allowed(record.phase)) return false
        store.replace(mapOf(ID to id, PHASE to to.name))
    }

    private fun readRecord(values: Map<String, String> = store.read()): Record? {
        val id = values[ID] ?: return null
        val phase = values[PHASE]?.let { name -> Phase.entries.firstOrNull { it.name == name } } ?: return null
        return Record(id, phase)
    }

    companion object {
        private val LOCK = Any()
        private const val ID = "id"
        private const val PHASE = "phase"
        private const val STATE = "state"
        private const val VERIFIER = "verifier"
        private const val STARTED = "startedAt"
        private const val URL = "authorizationUrl"

        fun forContext(context: android.content.Context) = MinecraftHowHandoffJournal(object : Store {
            private val prefs = context.applicationContext.getSharedPreferences("minecraft_how_handoff", android.content.Context.MODE_PRIVATE)
            override fun read(): Map<String, String> = try {
                prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
            } catch (_: RuntimeException) {
                emptyMap()
            }
            override fun replace(values: Map<String, String>): Boolean = try {
                prefs.edit().clear().apply { values.forEach { (key, value) -> putString(key, value) } }.commit()
            } catch (_: RuntimeException) {
                false
            }
        })
    }
}
