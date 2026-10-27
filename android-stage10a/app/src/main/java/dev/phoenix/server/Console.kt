package dev.phoenix.server

/** Console input and output rules, kept free of Android types so they are unit tested. */
object Console {
    /** Minecraft chat's own rule: "/command" runs a command and anything else is said to everyone as [Server]. */
    fun toServerLine(text: String): String? {
        val line = text.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("/")) return line.drop(1).trim().ifEmpty { null }
        return "say $line"
    }

    /** Replies to Phoenix's own background status queries (tick query, list, seed), hidden unless the user asked. */
    private val statusPollReply = Regex(
        """]: (The game is |Target tick rate: |Percentiles: P50: |There are \d+ of a max of \d+ players online|Seed: \[)|^Average time per tick: """
    )
    fun isStatusPollReply(line: String) = statusPollReply.containsMatchIn(line)

    enum class Kind { ERROR, WARN, JOIN, LEAVE, CHAT, SENT, PHOENIX, INFO, CRASH, SERVER, SAVE, DIAGNOSTIC }

    data class Line(
        val time: String?, val text: String, val kind: Kind,
        val trace: List<String> = emptyList(), val traceDropped: Int = 0, val note: String? = null,
    )

    /** Most characters of Java stack trace kept under one entry; the rest is counted and is in the full log. */
    const val TRACE_CHARS = 12_000

    /** Java's stack trace lines: indented "at ...", "... N more", and "Caused by:" or "Suppressed:" lines. */
    private val traceLine = Regex("""^\s+at \S|^\s*\.\.\. \d+ more\s*$|^\s*(Caused by|Suppressed): """)
    private val causeLine = Regex("""^\s*Caused by: (.+)$""")
    private const val CRASH_REPORT = "This crash report has been saved to:"
    /** The longest trace line kept; a longer one is cut and marked, and the full log has all of it. */
    private const val TRACE_LINE_CHARS = 500

    /**
     * Folds one raw line into the entry above it when it belongs there: a stack trace line, a "Caused by" cause, a Java
     * exception header that opens a trace, or a crash's report path. Returns the updated entry, or null when the line
     * starts a new entry. Only the entry directly above is looked at, so each line costs the same however long the log.
     */
    fun foldInto(previous: Line?, raw: String): Line? {
        if (previous == null) return null
        if (previous.kind == Kind.CRASH && raw.contains(CRASH_REPORT)) {
            val report = raw.substringAfter(CRASH_REPORT).trim()
            return addTrace(previous, parse(raw).text).copy(note = report.substringAfterLast('/'))
        }
        val opensTrace = !logPrefix.containsMatchIn(raw) && exceptionHeader.containsMatchIn(raw)
        val canTakeTrace = previous.kind == Kind.ERROR || previous.kind == Kind.WARN || previous.kind == Kind.CRASH || previous.trace.isNotEmpty()
        if (!canTakeTrace || !(opensTrace || traceLine.containsMatchIn(raw))) return null
        var updated: Line = previous
        if (previous.kind == Kind.CRASH) {
            if (opensTrace) updated = updated.copy(text = crashHeadline(raw))
            causeLine.find(raw)?.let { updated = updated.copy(text = crashHeadline(it.groupValues[1])) }
        }
        return addTrace(updated, raw)
    }

    /** "Server crashed: ChunkProtocolException: unfinished ...", from a Java exception line with its class name shortened. */
    private fun crashHeadline(exception: String): String {
        val name = exception.substringBefore(": ").substringAfterLast('.')
        val message = exception.substringAfter(": ", "").take(200)
        return "Server crashed: " + (if (message.isEmpty()) name else "$name: $message")
    }

    /** Adds one line to an entry's trace while it is under [TRACE_CHARS]; past that the line is only counted. */
    private fun addTrace(line: Line, text: String): Line {
        val kept = if (text.length > TRACE_LINE_CHARS) text.take(TRACE_LINE_CHARS) + " …" else text
        val used = line.trace.sumOf { it.length }
        return if (used + kept.length <= TRACE_CHARS) line.copy(trace = line.trace + kept)
        else line.copy(traceDropped = line.traceDropped + 1)
    }

    /** The entry as copied text: its time and headline, the crash report's name, and its kept stack trace. */
    fun plainText(line: Line, showAddresses: Boolean): String {
        fun shown(text: String) = if (showAddresses) text else hideAddresses(text)
        val lines = mutableListOf((line.time?.let { "[$it] " } ?: "") + shown(line.text))
        line.note?.let { lines += "Crash report: $it" }
        lines += line.trace.map { shown(it) }
        if (line.traceDropped > 0) lines += "… ${line.traceDropped} more lines are in the full log"
        return lines.joinToString("\n")
    }

    private val logPrefix = Regex("""^\[(\d{2}:\d{2}:\d{2})] \[[^\]]*/(INFO|WARN|ERROR|FATAL)]: (.*)$""")
    private val commandError = Regex("""Unknown or incomplete command|<--\[HERE]|Incorrect argument for command|Unknown command|Expected whitespace|Invalid or unknown|No player was found""")
    private val chat = Regex("""^(\[Not Secure] )?(<[^>]+>|\[[^\]]+]) """)
    private val crashStart = Regex("""Encountered an unexpected exception""")
    /** Server start and stop milestones: shown as events, never as diagnostics. */
    private val serverEvent = Regex("""^(Starting minecraft server version |Done \(|Stopping (the )?server\b)""")
    private val saveEvent = Regex("""^(Saved the game|Saving the game|Saving chunks)""")
    /** Phoenix's own internal lines and the replies to its status queries: hidden by the diagnostic filter, never warnings. */
    private val diagnosticText = Regex("""^(PHOENIUM_|PHOENIX_|Average time per tick: |Target tick rate: |Percentiles: P50: |The game is running normally|Seed: \[)|@@PHOENIX_""")
    /** A Java exception or error class at the start of a line, such as "net.minecraft.ReportedException: ...". */
    private val exceptionHeader = Regex("""^(Exception in thread "[^"]*" )?(?:[A-Za-z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable)(?::|$)""")
    private val playitLevel = Regex("""\s(ERROR|WARN|INFO|DEBUG|TRACE)\s""")

    /** Splits Minecraft's log prefix off and picks a kind (colour, grouping, filter) for one console line. */
    fun parse(raw: String): Line {
        if (raw.startsWith(SENT_PREFIX)) return Line(null, raw.removePrefix(SENT_PREFIX), Kind.SENT)
        if (raw.startsWith(PHOENIX_PREFIX)) {
            val note = raw.removePrefix(PHOENIX_PREFIX)
            // The Playit agent's own lines keep their level: its warnings and errors stay visible, its routine output is diagnostic.
            return Line(null, note, if (note.startsWith("Playit agent: ")) playitKind(note) else Kind.PHOENIX)
        }
        val match = logPrefix.find(raw)
        val time = match?.groupValues?.get(1)
        val level = match?.groupValues?.get(2)
        val text = match?.groupValues?.get(3) ?: raw
        val kind = when {
            level == "ERROR" || level == "FATAL" || commandError.containsMatchIn(text) ->
                if (crashStart.containsMatchIn(text)) Kind.CRASH else Kind.ERROR
            level == "WARN" -> Kind.WARN
            match == null && exceptionHeader.containsMatchIn(text) -> Kind.ERROR
            match == null && text.startsWith("WARNING: ") -> Kind.WARN
            text.endsWith(" joined the game") -> Kind.JOIN
            text.endsWith(" left the game") || text.contains(" lost connection: ") -> Kind.LEAVE
            serverEvent.containsMatchIn(text) -> Kind.SERVER
            saveEvent.containsMatchIn(text) -> Kind.SAVE
            chat.containsMatchIn(text) -> Kind.CHAT
            diagnosticText.containsMatchIn(text) -> Kind.DIAGNOSTIC
            else -> Kind.INFO
        }
        // A crash starts one entry; its headline names the cause as the trace reveals it (see foldInto).
        if (kind == Kind.CRASH) return Line(time, "Server crashed: unexpected exception", kind, trace = listOf(text))
        return Line(time, text, kind)
    }

    private fun playitKind(note: String): Kind = when (playitLevel.find(note)?.groupValues?.get(1)) {
        "ERROR" -> Kind.ERROR
        "WARN" -> Kind.WARN
        else -> Kind.DIAGNOSTIC
    }

    // IPv4 with an optional port ("/203.0.113.7:51234"), and bracketed or slash-prefixed IPv6 ("/[2001:db8::1]:51234").
    private val ipv4 = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?(?![\w.])""")
    // Java 14+ prints IPv6 socket addresses in brackets. "::" or five colons, so "[12:34:56]" times are left alone.
    private val ipv6 = Regex("""\[(?=[0-9A-Fa-f:.]*::|(?:[0-9A-Fa-f.]*:){5})[0-9A-Fa-f:.]+(?:%\w+)?](?::\d{1,5})?""")

    /**
     * What the console shows instead of player IP addresses, for example "Steve[/203.0.113.7:51234] logged in" becomes
     * "Steve[/•••] logged in". Display only: the server's own log and Phoenix's copy keep the original line.
     */
    fun hideAddresses(text: String): String {
        if (text.none { it == '.' || it == ':' }) return text
        return ipv6.replace(ipv4.replace(text, ADDRESS_MASK), ADDRESS_MASK)
    }
    const val ADDRESS_MASK = "•••"

    /** Marks what the user typed, so it can be shown in the console exactly once. */
    const val SENT_PREFIX = "\u0001> "
    /** Marks Phoenix's own messages in the console. */
    const val PHOENIX_PREFIX = "\u0001Phoenix: "
}

/** One console line, parsed once when it arrives; [id] keeps each row's identity in the list. */
data class ConsoleEntry(val id: Long, val line: Console.Line)

/** Reads Chunky's "[Chunky] Task …" console messages into pre-generation progress. */
object ChunkyLog {
    private val world = Regex("""for ([a-z0-9_.-]+:[a-z0-9_./-]+)""")
    private val processed = Regex("""Processed: (\d+) chunks \(([0-9.]+)%\)""")
    private val eta = Regex("""ETA: ([0-9:]+)""")
    private val rate = Regex("""Rate: ([0-9.]+) cps""")

    /** The periodic "Task running … Processed: …" update, which is shown as progress rather than in the console. */
    fun isProgress(line: String) = line.contains("[Chunky]") && line.contains("Task running") && line.contains("Processed:")

    fun update(status: ChunkyStatus, line: String): ChunkyStatus? {
        val at = line.indexOf("[Chunky]")
        if (at < 0) return null
        val text = line.substring(at + "[Chunky]".length).trim()
        val lower = text.lowercase()
        val state = when {
            "task finished" in lower || "task complete" in lower -> "DONE"
            "task paused" in lower -> "PAUSED"
            "task cancelled" in lower || "task canceled" in lower || "task stopped" in lower -> "CANCELLED"
            "task running" in lower || "task started" in lower || "task continu" in lower || "task resum" in lower -> "RUNNING"
            else -> status.state
        }
        val progress = processed.find(text)
        val running = state == "RUNNING"
        return status.copy(
            state = state,
            world = world.find(text)?.groupValues?.get(1) ?: status.world,
            percent = progress?.groupValues?.get(2)?.toDoubleOrNull() ?: if (state == "DONE") 100.0 else status.percent,
            processed = progress?.groupValues?.get(1)?.toLongOrNull() ?: status.processed,
            rate = rate.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: status.rate.takeIf { running },
            eta = eta.find(text)?.groupValues?.get(1) ?: status.eta.takeIf { running },
            lastMessage = if (isProgress(line)) status.lastMessage else text,
            needsConfirm = "/chunky confirm" in lower || ("confirm" in lower && "confirmed" !in lower),
        )
    }
}
