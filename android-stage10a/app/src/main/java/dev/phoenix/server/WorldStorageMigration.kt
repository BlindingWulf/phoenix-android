package dev.phoenix.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Copies a world folder to the microSD, verifies every file's size and SHA-256, and only then marks the move switched.
 * Progress lives in a journal file on internal storage, so a run killed at any point resumes or restarts safely.
 * The internal original is never deleted here; that is a separate, user-confirmed step.
 */
class WorldStorageMigration(private val journalFile: File) {
    enum class Phase { NONE, COPYING, VERIFIED, SWITCHED }

    data class Journal(val phase: Phase, val world: String = "", val source: String = "", val target: String = "", val cardUuid: String = "", val files: Int = 0, val bytes: Long = 0)

    class Refused(message: String) : Exception(message)

    fun journal(): Journal {
        if (!journalFile.isFile) return Journal(Phase.NONE)
        val p = Properties().apply { journalFile.inputStream().use { load(it) } }
        return Journal(
            runCatching { Phase.valueOf(p.getProperty("phase", "NONE")) }.getOrDefault(Phase.NONE),
            p.getProperty("world", ""), p.getProperty("source", ""), p.getProperty("target", ""), p.getProperty("card", ""),
            p.getProperty("files", "0").toIntOrNull() ?: 0, p.getProperty("bytes", "0").toLongOrNull() ?: 0,
        )
    }

    private fun write(j: Journal) {
        journalFile.parentFile?.mkdirs()
        val tmp = File(journalFile.path + ".tmp")
        val p = Properties().apply {
            setProperty("phase", j.phase.name); setProperty("world", j.world); setProperty("source", j.source)
            setProperty("target", j.target); setProperty("card", j.cardUuid); setProperty("files", j.files.toString()); setProperty("bytes", j.bytes.toString())
        }
        tmp.outputStream().use { out -> p.store(out, "Phoenix world move"); out.fd.sync() }
        Files.move(tmp.toPath(), journalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Moves [source] (a world folder) into [targetContainer] on the card with UUID [cardUuid].
     * [freeBytes] must measure the card volume, not internal storage. Throws [Refused] with a user-facing reason.
     */
    fun run(
        source: File, targetContainer: File, cardUuid: String, serverRunning: () -> Boolean, freeBytes: (File) -> Long,
        maxFileBytes: Long = ExternalStorage.FAT32_MAX_FILE, progress: (String) -> Unit = {},
    ): Journal {
        val existing = journal()
        if (existing.phase == Phase.SWITCHED && existing.source == source.absolutePath) return existing
        if (serverRunning()) throw Refused("Stop the server before moving the world")
        if (!source.isDirectory || !File(source, "level.dat").isFile) throw Refused("No world at ${source.name}")
        val target = File(targetContainer, source.name)
        val partial = File(targetContainer, source.name + ".partial")

        progress("Waiting for: reading the world's file list")
        val manifest = manifest(source)
        val bytes = manifest.sumOf { it.size }
        manifest.firstOrNull { it.size > maxFileBytes }?.let { throw Refused("${it.path} is over 4 GB, which a FAT32 card can't store") }
        manifest.groupBy { it.path.lowercase() }.values.firstOrNull { it.size > 1 }?.let { throw Refused("${it[0].path} and ${it[1].path} differ only by case; the card can't hold both") }

        if (existing.phase == Phase.VERIFIED && existing.source == source.absolutePath && target.isDirectory) {
            progress("Waiting for: re-checking the copy on the microSD")
            if (verify(manifest, target) == null) return switch(existing)
        }
        if (target.exists()) throw Refused("${target.path} already exists on the microSD")

        targetContainer.mkdirs()
        if (partial.exists()) partial.deleteRecursively() // our own unfinished copy from a killed run
        val needed = bytes + 64L * 1024 * 1024
        val free = freeBytes(targetContainer)
        if (free < needed) throw Refused("The microSD needs ${needed / 1048576} MB free, but has ${free / 1048576} MB")

        val journal = Journal(Phase.COPYING, source.name, source.absolutePath, target.absolutePath, cardUuid, manifest.size, bytes)
        write(journal)
        var copied = 0L
        manifest.forEachIndexed { i, entry ->
            progress("Waiting for: copying ${entry.path} (${i + 1} / ${manifest.size}, ${copied / 1048576} / ${bytes / 1048576} MB)")
            val out = File(partial, entry.path)
            out.parentFile?.mkdirs()
            Files.copy(File(source, entry.path).toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
            copied += entry.size
        }
        progress("Waiting for: verifying ${manifest.size} files on the microSD")
        verify(manifest, partial)?.let { throw Refused("Copy didn't verify: $it. The phone copy is untouched.") }
        if (!partial.renameTo(target)) throw Refused("Couldn't finish the copy on the microSD")
        val verified = journal.copy(phase = Phase.VERIFIED)
        write(verified)
        return switch(verified)
    }

    private fun switch(j: Journal) = j.copy(phase = Phase.SWITCHED).also(::write)

    /** Returns to the internal world (e.g. the user gives up on the card). The card copy is left in place. */
    fun revert() { if (journalFile.isFile) write(journal().copy(phase = Phase.NONE)) }

    data class Entry(val path: String, val size: Long, val sha256: String)

    companion object {
        fun manifest(root: File): List<Entry> {
            val out = mutableListOf<Entry>()
            Files.walk(root.toPath()).use { stream ->
                stream.forEach { path ->
                    if (Files.isSymbolicLink(path)) throw Refused("${root.toPath().relativize(path)} is a symlink; the card's filesystem can't store it")
                    if (Files.isRegularFile(path)) {
                        val rel = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                        out += Entry(rel, Files.size(path), sha256(path.toFile()))
                    }
                }
            }
            return out.sortedBy { it.path }
        }

        /** Null when [dir] holds exactly the manifest's files with matching size and hash, else the first difference. */
        fun verify(manifest: List<Entry>, dir: File): String? {
            val actual = runCatching { manifest(dir) }.getOrElse { return it.message ?: "unreadable" }
            if (actual.size != manifest.size) return "expected ${manifest.size} files, found ${actual.size}"
            manifest.zip(actual).forEach { (want, got) ->
                if (want.path != got.path) return "missing ${want.path}"
                if (want.size != got.size) return "${want.path} size differs"
                if (want.sha256 != got.sha256) return "${want.path} content differs"
            }
            return null
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Backup ZIPs on a FAT32 card must stay under 4 GB; callers split or refuse when this is false. */
        fun backupFits(estimatedBytes: Long, maxFileBytes: Long = ExternalStorage.FAT32_MAX_FILE) = estimatedBytes <= maxFileBytes
    }
}
