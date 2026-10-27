package dev.phoenix.server

/**
 * Restarts after unexpected exits, at most [max] within [windowMs], so a crash loop ends by itself instead of restarting
 * forever. Pure Kotlin so the limit is unit tested.
 */
class CrashLoop(private val max: Int = 3, private val windowMs: Long = 15 * 60_000L) {
    private val times = ArrayDeque<Long>()

    /** Records an unexpected exit at [now] (elapsed ms); returns which restart this is (1..max), or null at the limit. */
    @Synchronized fun allow(now: Long): Int? {
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        if (times.size >= max) return null
        times.addLast(now)
        return times.size
    }
}

/** How the server's Java process ended, in plain words. */
object ServerExit {
    /** Java reports a process ended by a signal as 128 + the signal. SIGKILL can't be caught: nothing after the last autosave was saved. */
    fun killed(code: Int) = code == 137

    fun describe(code: Int): String = when (code) {
        137 -> "Android ended it"
        143 -> "it was asked to stop"
        0 -> "it exited by itself"
        -1 -> "its exit code is not known" // a server Phoenix reconnected to: its parent was another run of the app
        else -> "it crashed with exit code $code"
    }

    /** Shown once, the first time Android kills the server: what usually causes it and how to prevent it. */
    const val KILL_ADVICE = "Android ended the server process. Phones do this to free memory, and Android 12 and newer also " +
        "limit background child processes, which is how Phoenix runs Minecraft. If it happens again: keep Phoenix's " +
        "notification showing, close heavy apps, and on Android 14 or newer turn on Settings › System › Developer options › " +
        "\"Disable child process restrictions\"."
}

/**
 * World damage the server reported in its log: chunks or region files it couldn't read or write. The messages are the
 * ones Minecraft 26.2 itself logs (taken from its server jar); only WARN and ERROR lines count, so stack traces and
 * ordinary chunk messages never match.
 */
object ChunkErrors {
    private val level = Regex("""/(WARN|ERROR)]""")
    private val damage = Regex(
        "(?i)(failed to (load|read|save|store|parse|scan|bulk scan) (entity )?chunk)|(couldn't (load|read|save) chunk)|" +
            "(chunk file at .* (is in the wrong location|is missing level data))|(region ?file .* has (truncated header|an? invalid sector))|" +
            "(chunk .* (header is truncated|stream is truncated|is allocated, but stream is missing|has invalid chunk stream version))|" +
            "(error reading chunk)|(chunk (load|save) failure)"
    )

    /** Loads only. Once the server crashes or stops, it cancels the loads it had queued and logs each as failed. */
    private val loadOnly = Regex("(?i)(failed to (load|read|parse|scan|bulk scan) (entity )?chunk)|(couldn't (load|read) chunk)|(error reading chunk)|(chunk load failure)")
    private val stopping = Regex("""]: (Stopping server|Encountered an unexpected exception|Exception in server tick loop)""")

    /** Damage lines; after the stop or crash began, cancelled loads are a consequence, while failed saves still count. */
    fun find(lines: List<String>): List<String> {
        val stop = lines.indexOfFirst { stopping.containsMatchIn(it) }.let { if (it < 0) lines.size else it }
        return lines.filterIndexed { i, line ->
            level.containsMatchIn(line) && damage.containsMatchIn(line) && (i < stop || !loadOnly.containsMatchIn(line))
        }
    }
}

/** A crash the server's own log reports: what crashed, the report Minecraft or Java saved, and whether the stop still saved. */
data class ServerCrash(val summary: String, val report: String?, val savedAtStop: Boolean)

/**
 * Minecraft's crash (`Encountered an unexpected exception` with its exception and deepest cause, then the crash report it
 * saved), Java running out of memory, or a fatal error of Java itself. Read from the run's newest lines after it ended.
 */
object ServerCrashes {
    private val minecraft = Regex("""]: (Encountered an unexpected exception|Exception in server tick loop)""")
    private val exception = Regex("""^(?:Exception in thread "[^"]*" )?([\w.$]+(?:Exception|Error)): (.*)$""")
    private val cause = Regex("""^Caused by: ([\w.$]+): (.*)$""")
    private val report = Regex("""crash report has been saved to: (.+)$""")
    private val logLine = Regex("""^\[\d""")
    private const val SAVED = "All dimensions are saved"
    private const val FATAL = "# A fatal error has been detected by the Java Runtime Environment"

    fun find(lines: List<String>): ServerCrash? {
        val start = lines.indexOfFirst { minecraft.containsMatchIn(it) }
        if (start >= 0) {
            val after = lines.drop(start + 1)
            val trace = after.takeWhile { !logLine.containsMatchIn(it) }
            val top = trace.firstNotNullOfOrNull { exception.find(it.trim()) } ?: return ServerCrash("Minecraft crashed", reportIn(after), after.any { SAVED in it })
            val deepest = trace.mapNotNull { cause.find(it.trim()) }.lastOrNull()?.let { "${simple(it.groupValues[1])}: ${it.groupValues[2]}" }
            val what = if (top.groupValues[1] == "net.minecraft.ReportedException") top.groupValues[2] else "${simple(top.groupValues[1])}: ${top.groupValues[2]}"
            return ServerCrash(deepest?.let { "$what ($it)" } ?: what, reportIn(after), after.any { SAVED in it })
        }
        lines.indexOfFirst { "java.lang.OutOfMemoryError" in it }.takeIf { it >= 0 }?.let { i ->
            val oom = exception.find(lines[i].trim())
            return ServerCrash(oom?.let { "${simple(it.groupValues[1])}: ${it.groupValues[2]}" } ?: "OutOfMemoryError", null, lines.drop(i).any { SAVED in it })
        }
        val fatal = lines.indexOfFirst { it.startsWith(FATAL) }.takeIf { it >= 0 } ?: return null
        val after = lines.drop(fatal + 1)
        val signal = after.firstNotNullOfOrNull { Regex("""^#\s+(SIG[A-Z]+)""").find(it)?.groupValues?.get(1) }
        val file = after.firstNotNullOfOrNull { Regex("""^#\s+(\S*hs_err_pid\d+\.log)""").find(it)?.groupValues?.get(1) }
        return ServerCrash("Java itself crashed${signal?.let { " ($it)" } ?: ""}", file?.substringAfterLast('/'), false)
    }

    private fun reportIn(lines: List<String>) = lines.firstNotNullOfOrNull { report.find(it)?.groupValues?.get(1)?.trim()?.substringAfterLast('/')?.substringAfterLast('\\') }
    private fun simple(type: String) = type.substringAfterLast('.')
}

/** When Phoenix backs a world up by itself before a start, and which of its own backups it keeps. */
object AutoBackups {
    const val PREF = "auto-backups-keep"
    const val DEFAULT_KEEP = 3
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * A backup is due unless one newer than 24 hours exists and the world hasn't been saved since ([levelDatMs], the
     * world's level.dat, which Minecraft writes at every save and stop).
     */
    fun due(newestBackupMs: Long?, levelDatMs: Long, now: Long): Boolean =
        newestBackupMs == null || now - newestBackupMs >= DAY_MS || levelDatMs > newestBackupMs

    /** Automatic backups (oldest first) split into those to delete and those to keep: the newest [keep]. */
    fun rotate(oldestFirst: List<String>, keep: Int): Pair<List<String>, List<String>> {
        val distinct = oldestFirst.distinct()
        val cut = (distinct.size - keep.coerceAtLeast(1)).coerceAtLeast(0)
        return distinct.take(cut) to distinct.drop(cut)
    }

    /** Room for a stored copy of the world plus 512 MB the phone and the server keep. */
    fun enoughSpace(worldBytes: Long, freeBytes: Long): Boolean = freeBytes > worldBytes + 512L * 1_048_576

    val choices = listOf("3" to "Keep the last 3 (default)", "1" to "Keep the last 1", "5" to "Keep the last 5", "10" to "Keep the last 10", "0" to "Off")
}

/**
 * Leftover processes of this app from an earlier run (an old server JVM or Playit agent whose parent, the app, is gone):
 * Phoenix's own executables, not children of the running app. Android only shows an app its own processes in /proc.
 */
object Leftovers {
    val EXECUTABLES = setOf("libphoenixjvm.so", "libplayitagent.so")

    /** [ppid] of the process whose first command-line word is [command]; true when it should be ended. */
    fun isLeftover(command: String, ppid: Int, appPid: Int): Boolean =
        ppid != appPid && command.substringAfterLast('/') in EXECUTABLES

    fun isServer(command: String) = command.substringAfterLast('/') == "libphoenixjvm.so"
}
