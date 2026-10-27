package dev.phoenix.server

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Phoenix spawn preparation: the optional `phoenix-spawnprep` server mod generates the terrain around spawn before the
 * server logs "Done". Everything here is plain Kotlin (no Android types) so it is unit tested; the contract is the mod's
 * docs/PHOENIX_SPAWN_PREP.md. The server and Phoenium work fully without the mod.
 */

enum class SpawnPrepState { IDLE, STARTING, RUNNING, DONE, READY, SKIPPED, FAILED }

/** The live run, read from the mod's "[PhoenixSpawnPrep]" console lines. Idle until the mod says something. */
data class SpawnPrepStatus(
    val state: SpawnPrepState = SpawnPrepState.IDLE,
    /** START's reason, or SKIP's reason. */
    val reason: String? = null,
    val dimension: String? = null,
    val centerChunk: String? = null,
    val radiusBlocks: Int? = null,
    val total: Int = 0,
    val done: Int = 0,
    val percent: Double = 0.0,
    val generated: Int = 0,
    val existing: Int = 0,
    val failed: Int = 0,
    val chunksPerSecond: Double? = null,
    val etaSeconds: Int? = null,
    val inFlight: Int? = null,
    val heapUsedMb: Int? = null,
    val heapMaxMb: Int? = null,
    /** COMPLETE, PARTIAL or CANCELLED, from DONE or READY. */
    val result: String? = null,
    val seconds: Double? = null,
    /** The text after FAILED. */
    val message: String? = null,
    /** The user asked to cancel and DONE has not arrived yet. */
    val cancelling: Boolean = false,
) {
    /** Preparation is running now (the server is still starting). */
    val active get() = state == SpawnPrepState.STARTING || state == SpawnPrepState.RUNNING

    /** "Preparing the area around spawn: 50 % (about 15 s left)"; null when nothing is running. */
    fun headline(): String? {
        if (!active) return null
        if (cancelling) return "Cancelling…"
        val eta = etaSeconds?.takeIf { it >= 0 }?.let { if (it < 120) " (about $it s left)" else " (about ${(it + 59) / 60} min left)" } ?: ""
        return "Preparing the area around spawn: ${percent.roundToInt()} %$eta"
    }

    /** "544 / 1,089 chunks". */
    fun chunkLine(): String = "${count(done)} / ${count(total)} chunks"
}

private fun count(value: Int) = String.format(Locale.US, "%,d", value)

/** Reads the mod's console lines. Field order is not relied on; unknown or garbled values keep what was known before. */
object SpawnPrepLog {
    const val TAG = "[PhoenixSpawnPrep]"
    private val results = setOf("COMPLETE", "PARTIAL", "CANCELLED")
    // Only the server's own log prefix may stand in front of the tag, so a player's chat or /say cannot fake a status.
    private val logPrefix = Regex("""^(?:\[\d{2}:\d{2}:\d{2}] )?(?:\[[^\]/]*/(?:INFO|WARN|ERROR|FATAL|DEBUG)](?::| \([^)]*\)) )?$""")

    /** The text after "[PhoenixSpawnPrep] ", or null when this is not one of the mod's own log lines. */
    private fun body(line: String): String? {
        val at = line.indexOf(TAG)
        if (at < 0 || !logPrefix.matches(line.substring(0, at))) return null
        return line.substring(at + TAG.length).trim()
    }

    private fun kindOf(body: String) = body.takeWhile { it != ' ' && it != ':' }

    /** The every-2-seconds PROGRESS line: shown as progress, not in the console. */
    fun isProgress(line: String) = line.indexOf(TAG) >= 0 && body(line)?.let { kindOf(it) == "PROGRESS" } == true

    /** The new status after [line], or null when the line is not a status line of the mod. */
    fun update(status: SpawnPrepStatus, line: String): SpawnPrepStatus? {
        val text = body(line) ?: return null
        val fields = fields(text)
        fun int(key: String) = fields[key]?.toIntOrNull()?.takeIf { it >= 0 }
        fun number(key: String) = fields[key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        return when (kindOf(text)) {
            "START" -> SpawnPrepStatus(
                state = SpawnPrepState.STARTING, reason = fields["reason"]?.ifEmpty { null }, dimension = fields["dimension"]?.ifEmpty { null },
                centerChunk = fields["centerChunk"]?.ifEmpty { null }, radiusBlocks = int("radiusBlocks"), total = int("chunks") ?: 0,
            )
            "PROGRESS" -> {
                val heap = fields["heapMB"]?.split('/')?.takeIf { it.size == 2 }
                status.copy(
                    state = SpawnPrepState.RUNNING,
                    total = int("total") ?: status.total, done = int("done") ?: status.done,
                    percent = number("pct")?.coerceIn(0.0, 100.0) ?: status.percent,
                    generated = int("generated") ?: status.generated, existing = int("existing") ?: status.existing,
                    failed = int("failed") ?: status.failed,
                    chunksPerSecond = number("cps") ?: status.chunksPerSecond,
                    etaSeconds = int("etaS") ?: status.etaSeconds,
                    inFlight = int("inFlight") ?: status.inFlight,
                    heapUsedMb = heap?.get(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: status.heapUsedMb,
                    heapMaxMb = heap?.get(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: status.heapMaxMb,
                    cancelling = status.cancelling,
                )
            }
            "DONE" -> {
                val total = int("total") ?: status.total
                val done = int("done") ?: status.done
                status.copy(
                    state = SpawnPrepState.DONE, result = fields["state"]?.takeIf { it in results },
                    total = total, done = done, percent = if (total > 0) (done * 100.0 / total).coerceIn(0.0, 100.0) else status.percent,
                    generated = int("generated") ?: status.generated, existing = int("existing") ?: status.existing,
                    failed = int("failed") ?: status.failed, seconds = number("seconds") ?: status.seconds,
                    etaSeconds = null, cancelling = false,
                )
            }
            "READY" -> status.copy(state = SpawnPrepState.READY, result = fields["state"]?.takeIf { it in results } ?: status.result, etaSeconds = null, cancelling = false)
            "SKIP" -> status.copy(
                state = SpawnPrepState.SKIPPED, reason = fields["reason"]?.ifEmpty { null }, radiusBlocks = int("radiusBlocks") ?: status.radiusBlocks,
                etaSeconds = null, cancelling = false,
            )
            "FAILED" -> status.copy(
                state = SpawnPrepState.FAILED, message = text.removePrefix("FAILED").trimStart(':').trim().ifEmpty { null }, etaSeconds = null, cancelling = false,
            )
            else -> null
        }
    }

    /** `key=value` words; a word without "=" (or with an empty key) is skipped. */
    private fun fields(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (word in text.split(' ', '\t')) {
            val eq = word.indexOf('=')
            if (eq > 0) map.putIfAbsent(word.substring(0, eq), word.substring(eq + 1))
        }
        return map
    }
}

enum class SpawnPrepMarkerState { RUNNING, PARTIAL, COMPLETE, CANCELLED }

/** What `<world>/phoenix-spawnprep.json` says about the last run. */
data class SpawnPrepLast(
    val state: SpawnPrepMarkerState,
    val dimension: String?,
    val radiusBlocks: Int,
    val done: Int,
    val total: Int,
    val generated: Int,
    val existing: Int,
    val failed: Int,
    val updatedEpochMs: Long,
) {
    /** The mod continues a RUNNING (interrupted) or PARTIAL run by itself at the next start; Phoenix only says so. */
    val willResume get() = state == SpawnPrepMarkerState.RUNNING || state == SpawnPrepMarkerState.PARTIAL

    /** One plain sentence for the stopped server: state, radius, generated/existing, failed. */
    fun describe(): String {
        val tail = "(radius $radiusBlocks blocks): ${count(generated)} generated, ${count(existing)} already there, ${count(failed)} failed."
        val head = when (state) {
            SpawnPrepMarkerState.COMPLETE -> "complete, ${count(total)} chunks around spawn"
            SpawnPrepMarkerState.PARTIAL -> "partly done, ${count(total)} chunks around spawn"
            SpawnPrepMarkerState.CANCELLED -> "cancelled at ${count(done)} of ${count(total)} chunks"
            SpawnPrepMarkerState.RUNNING -> "stopped at ${count(done)} of ${count(total)} chunks"
        }
        return "Last spawn preparation: $head $tail"
    }
}

object SpawnPrepMarker {
    const val FILE_NAME = "phoenix-spawnprep.json"
    private const val MAX_BYTES = 256L * 1024

    /** Null for anything missing, corrupt or unfamiliar: Phoenix then simply shows nothing. */
    fun parse(text: String): SpawnPrepLast? = try {
        val json = JSONObject(text)
        val state = SpawnPrepMarkerState.values().firstOrNull { it.name == (json.opt("state") as? String) }
        fun whole(key: String): Int? = if (!json.has(key)) 0 else (json.opt(key) as? Number)?.toLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt()
        val updated = if (!json.has("updatedEpochMs")) 0L else (json.opt("updatedEpochMs") as? Number)?.toLong()
        val done = whole("done"); val total = whole("total"); val generated = whole("generated"); val existing = whole("existing")
        val failed = whole("failed"); val radius = whole("radiusBlocks")
        if (state == null || done == null || total == null || generated == null || existing == null || failed == null || radius == null || updated == null) null
        else SpawnPrepLast(state, (json.opt("dimension") as? String)?.ifEmpty { null }, radius, done, total, generated, existing, failed, updated)
    } catch (_: Exception) { null }

    fun read(file: File): SpawnPrepLast? = try {
        if (file.isFile && file.length() <= MAX_BYTES) parse(file.readText()) else null
    } catch (_: Exception) { null }
}

/** `config/phoenix-spawnprep.properties`. Phoenix owns `enabled` and `radiusBlocks`; every other line is kept as it is. */
object SpawnPrepConfig {
    const val DEFAULT_RADIUS = 256
    const val MAX_RADIUS = 4096
    val RADIUS_CHOICES = listOf(256, 512, 768, 1024, 1280)
    const val FILE_NAME = "phoenix-spawnprep.properties"

    fun clampRadius(value: Int) = value.coerceIn(0, MAX_RADIUS)

    /** A typed radius, or null when it is not a whole number from 0 to 4096. */
    fun parseRadius(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_RADIUS }

    /** Why [text] is not a radius Phoenix accepts, for the dialog; null when it is one. */
    fun radiusProblem(text: String): String? {
        val t = text.trim()
        if (parseRadius(t) != null) return null
        if (t.isEmpty()) return "Enter a radius in blocks."
        val n = t.toLongOrNull() ?: return "Use whole blocks only (digits)."
        return if (n < 0) "The radius cannot be negative." else "The largest radius is $MAX_RADIUS blocks."
    }

    /** [existing] with `enabled` and `radiusBlocks` set; other lines, comments and their order are untouched. */
    fun merge(existing: String?, enabled: Boolean, radiusBlocks: Int): String {
        val ours = mapOf("enabled" to "$enabled", "radiusBlocks" to "${clampRadius(radiusBlocks)}")
        val lines = existing?.takeIf { it.isNotBlank() }?.replace("\r\n", "\n")?.split('\n')?.toMutableList()?.also { if (it.last().isEmpty()) it.removeAt(it.lastIndex) }
            ?: mutableListOf()
        val seen = HashSet<String>()
        val out = ArrayList<String>(lines.size + 2)
        for (line in lines) {
            val trimmed = line.trim()
            val key = if (trimmed.isEmpty() || trimmed[0] == '#' || trimmed[0] == '!') null else trimmed.takeWhile { it != '=' && it != ':' && it != ' ' && it != '\t' }
            if (key == null || key !in ours) out += line
            else if (seen.add(key)) out += "$key=${ours.getValue(key)}"
            // else: a repeated key collapses into the first one.
        }
        for ((key, value) in ours) if (key !in seen) out += "$key=$value"
        return out.joinToString("\n", postfix = "\n")
    }

    /** Writes the merged file atomically, creating `config/` when needed. */
    fun write(file: File, enabled: Boolean, radiusBlocks: Int) {
        val folder = file.absoluteFile.parentFile!!.apply { mkdirs() }
        val merged = merge(if (file.isFile) file.readText() else null, enabled, radiusBlocks)
        val part = File(folder, ".${file.name}.part")
        try {
            part.writeText(merged)
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { part.delete() }
    }

    /** Updates the file only when it exists (used while the mod is off, so nothing is created for a mod that is not there). */
    fun writeIfPresent(file: File, enabled: Boolean, radiusBlocks: Int): Boolean {
        if (!file.isFile) return false
        write(file, enabled, radiusBlocks)
        return true
    }
}
