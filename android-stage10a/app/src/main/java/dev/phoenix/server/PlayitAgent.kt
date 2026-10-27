package dev.phoenix.server

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs Playit's official agent (playitd, built from the pinned upstream source) as a child process and reads its state
 * over its local IPC socket. One instance at most. The agent key reaches it only through a 0600 file in Phoenix's
 * no-backup storage, never on its command line, and the file is deleted once the agent has loaded it. The IPC socket
 * lives in Phoenix's private files directory, so no other app can connect to it; it is never a network listener.
 * The agent carries all game traffic itself; nothing here is in the data path.
 */
class PlayitAgent(context: Context, private val log: (String) -> Unit) {
    enum class Phase { STOPPED, STARTING, RUNNING, INVALID_KEY, OVER_LIMIT, ERROR, CRASHED }

    /** One tunnel as the running agent reports it: its public address and the local target it forwards to. */
    data class Tunnel(val displayAddress: String, val destination: String, val disabledReason: String?)

    data class State(
        val phase: Phase = Phase.STOPPED,
        val detail: String? = null,
        val tunnels: List<Tunnel> = emptyList(),
        val pendingTunnels: Int = 0,
        /** "guest", "email_not_verified", "verified" or null when unknown. */
        val accountStatus: String? = null,
        /** Elapsed-realtime when the agent last reported Running; 0 if never in this run. */
        val runningSince: Long = 0L,
    )

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "playit").apply { mkdirs() }
    private val secretDir = File(app.noBackupFilesDir, "playit").apply { mkdirs() }
    private val socket = File(dir, "playitd.sock")
    private val secretFile = File(secretDir, "agent.secret")
    private val binary = File(app.applicationInfo.nativeLibraryDir, BINARY)
    private val monitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "phoenix-playit-agent").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var keySource: (() -> CharArray?)? = null
    @Volatile private var wanted = false
    @Volatile var state = State(); private set
    /**
     * The last thing Playit refused, kept after the agent stops (which resets [state]) until it runs again: OVER_LIMIT
     * or INVALID_KEY, else null. Lets the screens keep saying why instead of "Off" or "Connected".
     */
    @Volatile var lastRefusal: Phase? = null; private set
    /** Called once when Playit rejects the key (agent deleted or revoked), so the manager can remember it across restarts. */
    @Volatile var onInvalidKey: (() -> Unit)? = null
    private var poll: ScheduledFuture<*>? = null
    private val exits = ArrayDeque<Long>()
    private var nextStartAt = 0L

    val installed get() = binary.canExecute()
    val running get() = wanted

    /** Starts the agent, or keeps the running one. [key] is read once per process start and wiped after use. */
    fun start(key: () -> CharArray?) = synchronized(lock) {
        keySource = key
        if (wanted && process?.isAlive == true) return@synchronized
        wanted = true
        exits.clear(); nextStartAt = 0L
        state = State(Phase.STARTING, "Starting the Playit agent")
        poll?.cancel(false)
        poll = monitor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { log("Playit agent check failed: ${it.javaClass.simpleName}") } },
            0, 2, TimeUnit.SECONDS)
    }

    /** Stops the agent and removes its key file and socket. Tunnels and the saved key stay for the next start. */
    fun stop() = synchronized(lock) {
        wanted = false
        poll?.cancel(false); poll = null
        process?.let { running ->
            runCatching { request("stop") }
            if (!running.waitFor(3, TimeUnit.SECONDS)) { running.destroy(); if (!running.waitFor(2, TimeUnit.SECONDS)) running.destroyForcibly() }
        }
        process = null
        cleanup()
        state = State()
    }

    private fun tick() = synchronized(lock) {
        if (!wanted) return@synchronized
        val current = process
        if (current == null || !current.isAlive) {
            if (current != null) onExit(current.exitValue())
            if (!wanted || state.phase == Phase.CRASHED || state.phase == Phase.INVALID_KEY) return@synchronized
            if (SystemClock.elapsedRealtime() < nextStartAt) return@synchronized
            launch()
            return@synchronized
        }
        val lifecycle = runCatching { request("get_state") }.getOrNull() ?: return@synchronized
        state = parseLifecycle(lifecycle, state, SystemClock.elapsedRealtime())
        when (state.phase) {
            Phase.RUNNING -> { secretFile.delete(); lastRefusal = null }
            Phase.OVER_LIMIT -> lastRefusal = Phase.OVER_LIMIT
            Phase.INVALID_KEY -> {
                // A revoked or deleted agent: stop instead of letting it wait forever for a new secret.
                lastRefusal = Phase.INVALID_KEY
                log("Playit no longer accepts this phone's agent key. Sign in to Playit again.")
                current.destroy(); process = null; cleanup()
                runCatching { onInvalidKey?.invoke() }
            }
            else -> Unit
        }
    }

    /** A new key (a new approval) or a disconnect: an older refusal no longer applies. */
    fun forgetRefusal() { lastRefusal = null }

    /** Starts the agent again now (after the owner freed a slot on playit.gg, for example); no effect when it isn't wanted. */
    fun restart() = synchronized(lock) {
        if (!wanted) return@synchronized
        process?.let { running -> runCatching { request("stop") }; if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroy() }
        process = null; cleanup()
        exits.clear(); nextStartAt = 0L
        state = State(Phase.STARTING, "Connecting to Playit")
    }

    private fun launch() {
        check(binary.canExecute()) { "The Playit agent is missing from this build" }
        // A leftover socket from a process Android killed would make the new agent refuse to start.
        if (socket.exists() && runCatching { request("get_status") }.isFailure) socket.delete()
        if (socket.exists()) { runCatching { request("stop") }; SystemClock.sleep(500); socket.delete() }
        val key = keySource?.invoke() ?: run { state = State(Phase.INVALID_KEY, "Sign in to Playit"); return }
        try {
            writeSecret(key)
        } finally { key.fill('\u0000') }
        val builder = ProcessBuilder(binary.absolutePath, "--secret-path", secretFile.absolutePath, "--socket-path", socket.absolutePath)
            .directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("HOME", dir.absolutePath)
            put("TMPDIR", app.cacheDir.absolutePath)
            put("PLAYIT_LOG", "info")
            put("NO_COLOR", "1")
            put("RUST_LOG_STYLE", "never")
        }
        val started = builder.start()
        process = started
        state = State(Phase.STARTING, "Connecting to Playit")
        Thread({ drain(started.inputStream) }, "phoenix-playit-log").apply { isDaemon = true }.start()
    }

    private fun onExit(code: Int) {
        process = null
        socket.delete()
        val now = SystemClock.elapsedRealtime()
        exits.addLast(now)
        while (exits.isNotEmpty() && now - exits.first() > RESTART_WINDOW_MS) exits.removeFirst()
        if (state.phase == Phase.INVALID_KEY) return
        if (exits.size >= MAX_RESTARTS) {
            state = state.copy(phase = Phase.CRASHED, detail = "The Playit agent stopped ${exits.size} times in 10 minutes (last exit code $code). Turn Playit off and on to try again.")
            log(state.detail!!)
            cleanup()
            return
        }
        val delay = BACKOFF_MS[(exits.size - 1).coerceIn(0, BACKOFF_MS.lastIndex)]
        nextStartAt = now + delay
        state = state.copy(phase = Phase.ERROR, detail = "The Playit agent stopped (exit code $code); restarting in ${delay / 1000} s")
        log(state.detail!!)
    }

    private fun writeSecret(key: CharArray) {
        secretFile.delete()
        val bytes = String(key).toByteArray(Charsets.US_ASCII)
        try {
            java.io.FileOutputStream(secretFile).use { out ->
                android.system.Os.chmod(secretFile.absolutePath, 0x180) // 0600
                out.write(bytes)
            }
        } finally { bytes.fill(0) }
    }

    private fun cleanup() {
        secretFile.delete()
        socket.delete()
    }

    /** One request over a fresh connection; returns the response object, or throws. Lines are bounded. */
    private fun request(type: String): JSONObject {
        LocalSocket().use { client ->
            client.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            client.soTimeout = 3000
            val input = client.inputStream
            val hello = JSONObject(readLine(input) ?: error("no hello"))
            check(hello.optString("message_kind") == "hello") { "unexpected IPC greeting" }
            val version = hello.optJSONObject("data")?.optJSONObject("protocol")?.optInt("ipc_version") ?: -1
            check(version == IPC_VERSION) { "Playit agent IPC version $version is not supported" }
            client.outputStream.apply {
                write("{\"ipc_version\":$IPC_VERSION,\"request_id\":1,\"request\":{\"type\":\"$type\"}}\n".toByteArray())
                flush()
            }
            repeat(32) {
                val line = readLine(input) ?: error("IPC closed")
                val message = JSONObject(line)
                if (message.optString("message_kind") == "response") {
                    return message.optJSONObject("data")?.optJSONObject("response") ?: error("empty IPC response")
                }
            }
            error("no IPC response")
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) return bytes.toString("UTF-8")
            bytes.write(b)
            check(bytes.size() <= MAX_LINE) { "IPC line too long" }
        }
    }

    /** Agent output: only connection milestones and warnings reach Phoenix's console, at most a few lines a minute. */
    private fun drain(output: InputStream) {
        var shown = 0L; var budget = 6
        // The agent's own output (it never prints the key) is kept in a small file for troubleshooting.
        val logFile = File(dir, "agent.log").apply { if (length() > 512 * 1024) renameTo(File(dir, "agent.1.log")) }
        val writer = runCatching { java.io.FileWriter(logFile, true).buffered() }.getOrNull()
        // Stopping the agent (a revoked key, Stop, a restart) closes this stream mid-read. That ends the log, nothing more:
        // uncaught, it crashed the whole app on 2026-10-12 (InterruptedIOException on phoenix-playit-log, twice in 20 s).
        try {
            output.bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = ANSI.replace(raw, "").trim().take(300)
                    writer?.let { runCatching { it.write(line); it.newLine(); it.flush() } }
                    val now = SystemClock.elapsedRealtime()
                    if (now - shown > 60_000) { shown = now; budget = 6 }
                    val important = "playit connected" in line || " WARN" in line || " ERROR" in line || "error" in line.lowercase()
                    if (important && budget > 0) { budget--; log("Playit agent: ${line.substringAfter("playitd").trim().ifEmpty { line }}") }
                }
            }
        } catch (_: java.io.IOException) {
        } finally { writer?.let { runCatching { it.close() } } }
    }

    companion object {
        const val BINARY = "libplayitagent.so"
        private const val IPC_VERSION = 2
        private const val MAX_LINE = 256 * 1024
        private const val MAX_RESTARTS = 5
        private const val RESTART_WINDOW_MS = 10 * 60_000L
        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)
        private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

        /** Reads the agent's lifecycle response ({"type":"state","data":{"state":...,"data":...}}) into [State]. */
        fun parseLifecycle(response: JSONObject, previous: State, now: Long): State {
            if (response.optString("type") == "error") {
                return previous.copy(detail = response.optJSONObject("data")?.optString("message")?.take(200))
            }
            val lifecycle = response.optJSONObject("data") ?: return previous
            val data = lifecycle.optJSONObject("data")
            return when (lifecycle.optString("state")) {
                "running" -> {
                    val tunnels = data?.optJSONArray("tunnels")?.let { array ->
                        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
                            Tunnel(it.optString("display_address"), it.optString("destination"),
                                it.optString("disabled_reason").takeIf { reason -> reason.isNotBlank() && reason != "null" })
                        }
                    }.orEmpty()
                    State(Phase.RUNNING, null, tunnels, data?.optJSONArray("pending_tunnels")?.length() ?: 0,
                        data?.optString("account_status")?.takeIf { it.isNotBlank() },
                        if (previous.phase == Phase.RUNNING) previous.runningSince else now)
                }
                "starting" -> previous.copy(phase = Phase.STARTING, detail = "Connecting to Playit")
                "waiting_for_secret", "has_invalid_secret" -> State(Phase.INVALID_KEY, "Playit no longer accepts this phone's sign-in")
                "disabled_over_limit" -> State(Phase.OVER_LIMIT, "Your Playit account has more agents than its plan allows. Remove an unused agent on playit.gg.")
                "error" -> previous.copy(phase = Phase.ERROR, detail = data?.optString("message")?.take(200) ?: "The Playit agent reported an error")
                "stopping" -> previous.copy(phase = Phase.STARTING, detail = "Restarting the Playit agent")
                else -> previous
            }
        }
    }
}
