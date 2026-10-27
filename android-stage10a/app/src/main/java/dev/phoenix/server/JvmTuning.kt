package dev.phoenix.server

import java.io.File

/**
 * Opt-in JVM flags for the Minecraft launch, off by default so the pinned launch stays as measured.
 * Kept free of Android types so the exact flags are unit tested. Rationale and measurement: PERFORMANCE_QUICK_WINS.md.
 */
object JvmTuning {
    const val PREF = "jvm-tuning-v1"
    /** At or below this heap, Serial GC's single collector thread costs less than G1's regions and concurrent threads. */
    const val SERIAL_MAX_HEAP_MB = 768L
    const val CDS_ARCHIVE = "phoenix-server-cds.jsa"

    fun flags(enabled: Boolean, heapMb: Long, cacheDir: File): List<String> {
        if (!enabled) return emptyList()
        val gc = if (heapMb <= SERIAL_MAX_HEAP_MB) listOf("-XX:+UseSerialGC")
        else listOf("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100")
        // Pre-touch would commit the whole heap at start; on a phone that is RAM Android may want back.
        // The CDS archive is written on the first clean exit and mapped afterwards; -Xshare:auto ignores a bad or missing one.
        // Phoenix's metrics agent is loaded with -javaagent, which Java refuses during a CDS dump unless allowed (measured on
        // the phone: "Must enable AllowArchivingWithJavaAgent"); the agent transforms no classes.
        return gc + listOf(
            "-XX:-AlwaysPreTouch",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+AllowArchivingWithJavaAgent",
            "-Xshare:auto",
            "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=${File(cacheDir, CDS_ARCHIVE).absolutePath}",
        )
    }

    /** Seconds after the JVM starts before the metrics agent may treat the server as idle (a typical start takes ~60 s). */
    const val IDLE_HEAP_AFTER_S = 120

    /**
     * Arguments for Phoenix's metrics agent. `idleHeap`: from that long after the start, once no collection ran for a
     * minute, the agent switches G1's manageable flags to a periodic collection (30 s) and 10-30% free heap, so an idle
     * server gives unused heap back to the phone; at the first burst of collections it restores the JVM's defaults, so a
     * busy server keeps its larger heap. Measured on the test phone (PERFORMANCE_QUICK_WINS.md): as fixed launch flags these
     * saved ~85-125 MB idle but doubled collections under load (P99 tick 22.5 -> 32.6 ms) and slowed the start.
     */
    fun agentArguments(): String = "idleHeap=$IDLE_HEAP_AFTER_S"

    /** Debug builds only: the file in Phoenix's files directory whose flags are added to the next server start. */
    const val EXPERIMENT_FILE = "jvm-experiment.txt"

    /**
     * Extra launch flags for measuring on a test phone without a rebuild (debug builds only): one per line, `#` starts a
     * comment. Only `-XX:` and `-D` options without spaces are taken; anything else is ignored.
     */
    fun experimentFlags(text: String?): List<String> = text.orEmpty().lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() && it.none(Char::isWhitespace) && (it.startsWith("-XX:") || it.startsWith("-D")) }
        .toList()
}

/**
 * Java 25's AOT cache for the server JVM (JEP 483/514/515) in Phoenix's own two steps, since its launcher has no `java`
 * binary for the one-step workflow: a start records what the JVM loads and profiles (written at its clean stop), Phoenix
 * then assembles the cache while the server is stopped, and later starts map it. Measured on the test phone: joinable in
 * ~48 s instead of ~59 s; the learning start took 8 s longer, its stop 20 s longer, and assembling 12 s. The JVM checks
 * class path and options itself and simply starts without a cache that doesn't fit.
 */
object AotCache {
    /** True only once the owner turns it on; Phoenix turns it off again when Java refused to start with it. */
    const val PREF = "faster-starts-aot"
    /** Off for a new install: the AOT cache crashed the C1 compiler on the certified server stack (Stage 9). */
    const val DEFAULT_ENABLED = false
    const val CONFIG = "server.aotconf"
    const val CACHE = "server.aot"
    const val KEY = "server.aot.key"
    /** Room for the recording (~155 MB) and the cache (~150 MB) with plenty to spare. */
    const val MIN_FREE_BYTES = 1024L * 1_048_576

    enum class Mode { NONE, RECORD, USE }

    /** Use a cache made for exactly [key]; otherwise learn again, if there is room for it. */
    fun mode(enabled: Boolean, cacheExists: Boolean, savedKey: String?, key: String, freeBytes: Long): Mode = when {
        !enabled -> Mode.NONE
        cacheExists && savedKey == key -> Mode.USE
        freeBytes >= MIN_FREE_BYTES -> Mode.RECORD
        else -> Mode.NONE
    }

    fun flags(mode: Mode, dir: File): List<String> = when (mode) {
        Mode.USE -> listOf("-XX:AOTCache=${File(dir, CACHE).absolutePath}")
        Mode.RECORD -> listOf("-XX:AOTMode=record", "-XX:AOTConfiguration=${File(dir, CONFIG).absolutePath}")
        Mode.NONE -> emptyList()
    }

    /** What a cache belongs to: the app build and its install, the Java runtime, and the server jars. */
    fun key(appVersion: Long, installedAt: Long, runtimeManifest: String, jars: List<File>): String =
        (listOf("$appVersion", "$installedAt", "${runtimeManifest.hashCode()}") + jars.map { "${it.name}:${it.length()}:${it.lastModified()}" })
            .joinToString("|")
}

