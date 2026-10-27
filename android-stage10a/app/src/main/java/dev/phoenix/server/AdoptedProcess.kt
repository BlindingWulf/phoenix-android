package dev.phoenix.server

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A Minecraft Java process that an earlier run of this app started and that outlived that app process. Java gives no
 * pipes or exit code for a process that is not a child, so this stands in for [Process] inside ProcessRuntimeHost: the
 * output is the server's own log (its last [TAIL_BYTES] first, then what is appended), liveness and signals are checked
 * against the recorded start time (a reused pid is never signalled), and console input is refused except "stop", which
 * becomes SIGTERM so that Minecraft's shutdown hook saves the world.
 */
class AdoptedProcess(
    private val pid: Int,
    private val recordedStartTicks: Long?,
    private val serverJar: String,
    private val log: File,
    /** A stop was already logged when this process was found: it is ending by itself, so its end is expected. */
    private val stopping: Boolean,
) : Process() {
    /** The last signal Phoenix sent (15 or 9), or null. */
    @Volatile private var signalled: Int? = null

    /** Whether the process is still this server: alive, this launcher and jar, and the recorded start time. */
    private fun ours(): Boolean {
        val facts = LeftoverServer.readProcess(pid)
        return facts.alive && LeftoverServer.isOurServer(facts.argv, serverJar) &&
            (recordedStartTicks == null || facts.startTicks == recordedStartTicks)
    }

    override fun isAlive(): Boolean = ours()
    override fun waitFor(): Int {
        while (ours()) Thread.sleep(250)
        return exitCode()
    }
    override fun exitValue(): Int {
        if (ours()) throw IllegalThreadStateException("Minecraft is still running")
        return exitCode()
    }
    /** SIGTERM: Minecraft's shutdown hook saves the worlds before the process ends. */
    override fun destroy() = signal(15)
    override fun destroyForcibly(): Process {
        signal(9)
        return this
    }
    override fun getOutputStream(): OutputStream = ConsoleGate()
    override fun getInputStream(): InputStream = LogTail()
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0)) // the server's errors are merged into its log

    /** 137 after a SIGKILL from Phoenix; 0 when Phoenix asked it to stop or it was already stopping; -1 when not known. */
    private fun exitCode(): Int = when {
        signalled == 9 -> 137
        signalled == 15 || stopping -> 0
        else -> -1
    }

    private fun signal(sig: Int) {
        if (!ours()) return
        signalled = sig
        android.os.Process.sendSignal(pid, sig)
    }

    /** The server's log as it is written: it starts at the last [TAIL_BYTES], blocks while the server runs, then ends. */
    private inner class LogTail : InputStream() {
        private var position = -1L
        /** Started mid-file, the first line is cut: it is dropped. */
        private var skipToLineEnd = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                // Read the liveness first: whatever the server wrote before it ended is still read before the end.
                val running = ours()
                val size = log.length()
                if (position < 0) {
                    position = (size - TAIL_BYTES).coerceAtLeast(0L)
                    skipToLineEnd = position > 0
                }
                // A log that got shorter was started again: read the new file from its beginning.
                if (size < position) position = 0L
                if (size > position) {
                    val want = minOf(len.toLong(), size - position).toInt()
                    val n = RandomAccessFile(log, "r").use { file -> file.seek(position); file.read(buffer, off, want) }
                    if (n > 0) {
                        position += n
                        val kept = if (skipToLineEnd) dropCutLine(buffer, off, n) else n
                        if (kept > 0) return kept
                        continue
                    }
                }
                if (!running) return -1
                Thread.sleep(200)
            }
        }

        /** Drops the cut first line; returns how many bytes are left at [off] (0 while the line is still cut). */
        private fun dropCutLine(buffer: ByteArray, off: Int, n: Int): Int {
            var i = 0
            while (i < n && buffer[off + i] != NEWLINE) i++
            if (i == n) return 0
            skipToLineEnd = false
            val rest = n - (i + 1)
            System.arraycopy(buffer, off + i + 1, buffer, off, rest)
            return rest
        }
    }

    /** The server's console input. A reconnected server takes only "stop" (as SIGTERM); any other command is refused. */
    private inner class ConsoleGate : OutputStream() {
        private val line = StringBuilder()

        override fun write(b: Int) {
            if (b.toByte() == NEWLINE) {
                val text = line.toString().trim()
                line.setLength(0)
                if (text == "stop") signal(15) else throw IOException(INPUT_NOTE)
            } else line.append((b and 0xff).toChar())
        }

        override fun flush() {}
    }

    companion object {
        /** How much of the log is read at first: the most recent output, which is what the console shows. */
        const val TAIL_BYTES = 256L * 1024
        /** Shown in place of the console input after a reconnect. "stop" is still accepted. */
        const val INPUT_NOTE = "Console input is off after Phoenix reconnected to this server. Stop works; start it again to type commands."
        private const val NEWLINE: Byte = 10
    }
}
