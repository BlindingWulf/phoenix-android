package dev.phoenix.server

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** World folders in the server directory and their ZIP backups. File work only; the backend decides when it is safe. */
class WorldManager(private val serverDir: File, private val backupsDir: File) {
    /** World sizes take a directory walk, so each is reused for half a minute. */
    private val sizes = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun snapshot(active: String): WorldsSnapshot {
        val dirs = serverDir.listFiles()?.filter { it.isDirectory && isWorldName(it.name) && File(it, "level.dat").isFile }.orEmpty()
        val worlds = dirs.map { dir -> WorldInfo(dir.name, size(dir), File(dir, "level.dat").lastModified(), dir.name == active, true) }.toMutableList()
        // A new world exists only as level-name until the server first starts.
        if (worlds.none { it.active }) worlds += WorldInfo(active, 0, null, true, created = false)
        return WorldsSnapshot(worlds.sortedWith(compareBy({ !it.active }, { it.name.lowercase() })), backups())
    }

    fun backups(): List<BackupInfo> = backupsDir.listFiles().orEmpty().mapNotNull { file ->
        parseBackupName(file.name)?.let { (world, time) -> BackupInfo(file.name, world, time, file.length()) }
    }.sortedByDescending { it.createdMs }

    private fun size(dir: File): Long {
        val now = System.currentTimeMillis()
        sizes[dir.name]?.takeIf { now - it.first < 300_000 }?.let { return it.second } // walking a world competes with chunk I/O
        var total = 0L
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { total += attrs.size(); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        sizes[dir.name] = now to total
        return total
    }

    fun worldDir(world: String): File = File(serverDir, checkName(world))

    /**
     * Writes the world as a ZIP whose root is the world folder's contents. Symbolic links are refused. [level] is the
     * Deflater level: automatic backups use 0 (stored), since region files are already compressed and speed matters there.
     */
    fun zipWorld(world: String, output: OutputStream, level: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION, progress: (Long) -> Unit) {
        val dir = worldDir(world)
        check(File(dir, "level.dat").isFile) { "$world hasn't been saved yet. Start the server once first." }
        val root = dir.canonicalFile
        var bytes = 0L
        ZipOutputStream(output.buffered(65536)).use { zip ->
            zip.setLevel(level)
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(!attrs.isSymbolicLink) { "The world contains a link, so it wasn't backed up" }
                    if (file.fileName.toString() == "session.lock") return FileVisitResult.CONTINUE
                    zip.putNextEntry(ZipEntry(root.toPath().relativize(file).toString().replace(File.separatorChar, '/')))
                    Files.newInputStream(file).use { it.copyTo(zip, 65536) }
                    zip.closeEntry()
                    bytes += attrs.size(); progress(bytes)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        sizes.remove(world)
    }

    fun backup(world: String, level: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION, progress: (Long) -> Unit): BackupInfo {
        backupsDir.mkdirs()
        val now = System.currentTimeMillis()
        val name = backupName(world, now)
        val partial = File(backupsDir, "$name.partial")
        try {
            partial.outputStream().use { zipWorld(world, it, level, progress) }
            val done = File(backupsDir, name)
            check(partial.renameTo(done)) { "Couldn't finish the backup file" }
            return BackupInfo(name, world, now, done.length())
        } finally { partial.delete() }
    }

    fun backupFile(fileName: String): File {
        require(parseBackupName(fileName) != null) { "Unknown backup" }
        return File(backupsDir, fileName).also { check(it.isFile) { "That backup is gone" } }
    }

    fun deleteBackup(fileName: String) = check(backupFile(fileName).delete()) { "Couldn't delete the backup" }

    private val autoIndex get() = File(backupsDir, "automatic.txt")

    /**
     * The automatic backup before a start: a stored (uncompressed) ZIP of [world] listed in automatic.txt, then the
     * oldest automatic ones beyond [keep] are deleted. Backups the owner made are never deleted here.
     * Returns the backup, or null when [AutoBackups.due] says nothing changed since the newest backup.
     */
    fun autoBackup(world: String, keep: Int, now: Long, progress: (Long) -> Unit): BackupInfo? {
        val dir = worldDir(world)
        val levelDat = File(dir, "level.dat").takeIf { it.isFile } ?: return null
        if (!AutoBackups.due(backups().firstOrNull { it.world == world }?.createdMs, levelDat.lastModified(), now)) return null
        val made = backup(world, java.util.zip.Deflater.NO_COMPRESSION, progress)
        val listed = autoIndex.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty() + made.fileName
        val (gone, kept) = AutoBackups.rotate(listed, keep)
        gone.forEach { name -> runCatching { File(backupsDir, name).takeIf { parseBackupName(name) != null }?.delete() } }
        autoIndex.writeText(kept.joinToString("\n", postfix = "\n"))
        return made
    }

    /** Bytes the world folder takes, for the free-space check before a backup. */
    fun worldBytes(world: String): Long = worldDir(world).takeIf { it.isDirectory }?.let(::size) ?: 0L

    /** Deletes a world folder without following links out of it. */
    fun deleteWorld(world: String) {
        val dir = worldDir(world)
        check(dir.isDirectory && !Files.isSymbolicLink(dir.toPath())) { "That world isn't there" }
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(directory: Path, exc: IOException?): FileVisitResult { exc?.let { throw it }; Files.delete(directory); return FileVisitResult.CONTINUE }
        })
        sizes.remove(world)
    }

    /**
     * Copies pre-generated region files for one dimension out of [zip] into [world]. Existing files
     * with the same name are replaced; the caller backs the world up first. Returns the file count.
     */
    fun importChunks(zip: File, world: String, dimension: String, freeBytes: Long, progress: (Int, Int) -> Unit): Int {
        val target = File(worldDir(world), "dimensions/minecraft/$dimension")
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().filter { !it.isDirectory }.toList()
            val picked = ChunkImport.select(entries.map { it.name }, dimension)
            val byName = entries.associateBy { it.name }
            val total = picked.sumOf { (name, _) -> byName.getValue(name).size.coerceAtLeast(0) }
            check(total < freeBytes - 256L * 1048576) { "Not enough free storage for these chunks" }
            picked.forEachIndexed { index, (name, relative) ->
                val out = File(target, relative)
                out.parentFile!!.mkdirs()
                val part = File(out.parentFile, ".${out.name}.part")
                try {
                    archive.getInputStream(byName.getValue(name)).use { input -> part.outputStream().use { input.copyTo(it, 65536) } }
                    check(part.renameTo(out)) { "Couldn't write ${out.name}" }
                } finally { part.delete() }
                progress(index + 1, picked.size)
            }
            sizes.remove(world)
            return picked.size
        }
    }

    companion object {
        private val reserved = setOf("config", "mods", "logs", "libraries", "versions", "crash-reports", "backups", "cache", "defaultconfigs", "geyser", "floodgate")
        private val backupPattern = Regex("""^([A-Za-z0-9][A-Za-z0-9_-]{0,31})-(\d{10,15})\.zip$""")

        fun isWorldName(name: String) = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) && name.lowercase() !in reserved

        /** World folder names: letters, numbers, "-" and "_", up to 32 characters. */
        fun checkName(name: String): String {
            val clean = name.trim().replace(' ', '-')
            require(isWorldName(clean)) { "Use 1–32 letters, numbers, - or _ for the world name" }
            return clean
        }

        fun backupName(world: String, epochMs: Long) = "${checkName(world)}-$epochMs.zip"

        fun parseBackupName(fileName: String): Pair<String, Long>? =
            backupPattern.find(fileName)?.let { it.groupValues[1] to it.groupValues[2].toLong() }
    }
}

/** Picks a dimension's region files out of a ZIP made on a computer, whatever folder layout it uses. */
object ChunkImport {
    val dimensions = listOf("overworld", "the_nether", "the_end")
    private val regionFile = Regex("""^(.*?)(region|entities|poi)/(r\.-?\d+\.-?\d+\.mca)$""")

    /** Returns (ZIP entry, path below the dimension folder) pairs, or fails with a message for the user. */
    fun select(names: List<String>, dimension: String): List<Pair<String, String>> {
        require(dimension in dimensions) { "Unknown dimension" }
        val matches = names.mapNotNull { name ->
            if (name.contains("..") || name.startsWith("/") || name.contains('\\')) return@mapNotNull null
            regionFile.find(name)?.let { Triple(name, it.groupValues[1], "${it.groupValues[2]}/${it.groupValues[3]}") }
        }
        check(matches.isNotEmpty()) { "This ZIP has no region files (region/r.X.Z.mca)" }
        fun dimensionOf(prefix: String): String? = when {
            prefix.endsWith("dimensions/minecraft/overworld/") -> "overworld"
            prefix.endsWith("dimensions/minecraft/the_nether/") || prefix.endsWith("DIM-1/") -> "the_nether"
            prefix.endsWith("dimensions/minecraft/the_end/") || prefix.endsWith("DIM1/") -> "the_end"
            prefix.contains("dimensions/") -> "other"
            else -> null
        }
        val explicit = matches.filter { dimensionOf(it.second) == dimension }
        val generic = matches.filter { dimensionOf(it.second) == null }
        val chosen = when {
            explicit.isNotEmpty() -> explicit
            // A whole old-style world keeps the Overworld at its root; a dimension-only ZIP has just the folders.
            generic.isNotEmpty() && (dimension == "overworld" || matches.all { dimensionOf(it.second) == null }) -> generic
            else -> error("This ZIP has no ${label(dimension)} region files")
        }
        check(chosen.map { it.second }.distinct().size == 1) { "This ZIP holds more than one set of region folders; ZIP one world at a time" }
        return chosen.map { it.first to it.third }
    }

    fun label(dimension: String) = when (dimension) { "the_nether" -> "Nether"; "the_end" -> "End"; else -> "Overworld" }
}
