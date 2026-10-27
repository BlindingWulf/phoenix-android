package dev.phoenix.server

import org.phoenix.core.ServerController.State
import java.io.File

/**
 * A Minecraft Java process that an earlier run of this app started and that is still running after that app process
 * ended. [classify] decides whether this app may take it over. It never signals a process: only RUNNING, STARTING and
 * STOPPING servers are adopted, and a STALE process (a reused pid, or not this server's process) is left alone.
 */
object LeftoverServer {
    enum class Outcome { NONE, STARTING, RUNNING, STOPPING, STALE }

    /** The process to check: the pid saved when the server started, with its start time then (null when none was saved). */
    data class Candidate(val pid: Int, val recordedStartTicks: Long?)

    /** What /proc says about a process now. [argv] is its command line; [startTicks] its start time (clock ticks since boot). */
    data class ProcessFacts(val alive: Boolean, val argv: List<String>, val startTicks: Long?, val ppid: Int? = null)

    /** What the server log says about its latest start: Done was logged, and a stop was logged after that. */
    data class LogFacts(val done: Boolean, val stopping: Boolean)

    /** Fields of /proc/<pid>/stat that Phoenix uses. */
    data class StatFields(val state: Char, val ppid: Int, val startTicks: Long)

    const val LAUNCHER = "libphoenixjvm.so"
    /** Linux reports process start times in clock ticks; the USER_HZ of Android is 100. */
    const val TICKS_PER_SECOND = 100L
    private const val START_LINE = "Starting minecraft server version"
    private const val STOP_LINE = "Stopping server"

    /**
     * RUNNING when Minecraft logged Done and answers its status probe, STARTING while it has not finished starting,
     * STOPPING once a stop was logged after Done. Any process that is not this server's (wrong launcher, wrong server
     * jar, or a start time other than the recorded one) is STALE, whatever the log says.
     */
    fun classify(candidate: Candidate?, process: ProcessFacts?, log: LogFacts, answering: Boolean, serverJar: String): Outcome {
        if (candidate == null || process == null || !process.alive) return Outcome.NONE
        val sameServer = isOurServer(process.argv, serverJar) &&
            (candidate.recordedStartTicks == null || candidate.recordedStartTicks == process.startTicks)
        return when {
            !sameServer -> Outcome.STALE
            log.stopping -> Outcome.STOPPING
            log.done && answering -> Outcome.RUNNING
            else -> Outcome.STARTING
        }
    }

    /** The launcher is the first command word, and this server's jar is one of its arguments. */
    fun isOurServer(argv: List<String>, serverJar: String): Boolean =
        argv.isNotEmpty() && argv[0].substringAfterLast('/') == LAUNCHER && argv.contains(serverJar)

    /** The server log since its latest start. Only the latest start counts, and a stop counts only after Done. */
    fun scanLog(lines: List<String>): LogFacts {
        val start = lines.indexOfLast { it.contains(START_LINE) }.coerceAtLeast(0)
        val done = (start until lines.size).firstOrNull { isDone(lines[it]) } ?: return LogFacts(done = false, stopping = false)
        return LogFacts(done = true, stopping = ((done + 1) until lines.size).any { lines[it].contains(STOP_LINE) })
    }

    /** Minecraft's server line; a mod's own "Done" (Geyser) lacks "For help, type". */
    private fun isDone(line: String) = line.contains("Done (") && line.contains("For help, type")

    /** The command name sits in parentheses and may hold spaces or parentheses, so the fields start after the last ')'. */
    fun parseStat(stat: String): StatFields? {
        val fields = stat.substringAfterLast(')', "").trim().split(' ')
        if (fields.size < 20) return null
        val state = fields[0].firstOrNull() ?: return null
        val ppid = fields[1].toIntOrNull() ?: return null
        val startTicks = fields[19].toLongOrNull() ?: return null
        return StatFields(state, ppid, startTicks)
    }

    /** A /proc/<pid>/cmdline, split into its arguments. */
    fun parseCmdline(raw: String): List<String> = raw.split('\u0000').filter { it.isNotEmpty() }

    /** How long a process has run: the system uptime less the time it started at. */
    fun uptimeMs(startTicks: Long, systemUptimeSeconds: Double): Long =
        ((systemUptimeSeconds * 1000).toLong() - startTicks * 1000 / TICKS_PER_SECOND).coerceAtLeast(0L)

    /** Reads /proc for [pid]. A zombie counts as gone. Only this app's own processes are readable, which is all that is needed. */
    fun readProcess(pid: Int): ProcessFacts {
        val stat = runCatching { parseStat(File("/proc/$pid/stat").readText()) }.getOrNull()
            ?: return ProcessFacts(alive = false, argv = emptyList(), startTicks = null)
        val argv = runCatching { parseCmdline(File("/proc/$pid/cmdline").readText()) }.getOrDefault(emptyList())
        return ProcessFacts(alive = stat.state != 'Z', argv = argv, startTicks = stat.startTicks, ppid = stat.ppid)
    }

    /** The pid of a server with [serverJar] that is not a child of [appPid] (its parent is gone), or null. */
    fun scanForServer(serverJar: String, appPid: Int): Int? =
        File("/proc").listFiles { f -> f.name.isNotEmpty() && f.name.all(Char::isDigit) }.orEmpty()
            .map { it.name.toInt() }
            .filter { it != appPid }
            .firstOrNull { pid ->
                val facts = readProcess(pid)
                facts.alive && isOurServer(facts.argv, serverJar) && facts.ppid != appPid
            }

    /** Seconds since the device booted, from /proc/uptime. */
    fun systemUptimeSeconds(): Double = runCatching { File("/proc/uptime").readText().substringBefore(' ').toDouble() }.getOrDefault(0.0)
}

/**
 * Which lifecycle buttons Home offers. Start is never offered while a Java process of the server is alive, including one
 * this app reconnected to. Stop is offered while a server runs, starts or restarts, and after an error that left Java running.
 */
object ServerGate {
    fun startOffered(phase: State, processAlive: Boolean, busy: Boolean): Boolean =
        !busy && !processAlive && (phase == State.READY || phase == State.STOPPED || phase == State.ERROR)

    fun stopOffered(phase: State, processAlive: Boolean): Boolean =
        phase == State.ONLINE || phase == State.STARTING || phase == State.RESTARTING || (phase == State.ERROR && processAlive)
}
