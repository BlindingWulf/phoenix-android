package dev.phoenix.server

import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

data class ModPackMod(val fileName: String, val id: String?, val name: String, val version: String?, val sizeBytes: Long)

/** A saved set of mods. [inUse]: exactly these jars are the server's enabled optional mods right now. */
data class ModPackInfo(
    val id: String, val name: String, val createdMs: Long, val mods: List<ModPackMod>, val sizeBytes: Long,
    val inUse: Boolean, val issues: List<ModIssue>,
) {
    val blocked get() = issues.any { it.blocking }
}

/** The Mods tab's pack list plus the newest import result and mod rollback, each shown once. */
data class ModPacksSnapshot(val packs: List<ModPackInfo> = emptyList(), val lastImport: PackImport? = null, val rollback: String? = null)

/** What an import added and what it left out, and why. */
data class PackImport(val id: String, val name: String, val added: Int, val skipped: List<String>)

/**
 * Mod packs, each a folder `modpacks/<id>/` with its jars and a `pack.properties` (name, creation time). Packs are
 * separate from the mods folder: editing a pack never changes the running server; "Use" copies a pack in.
 */
class ModPackStore(private val packsDir: File, private val pinnedIds: Set<String>) {
    private val metaCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ModMeta?>>()

    fun ids(): List<String> = packsDir.listFiles()?.filter { File(it, PROPS).isFile }?.map { it.name }?.sorted().orEmpty()

    fun name(id: String): String = props(dir(id)).getProperty("name") ?: id

    fun jars(id: String): List<File> = dir(id).listFiles()?.filter { it.isFile && it.name.endsWith(".jar") && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun metas(id: String): List<ModMeta?> = jars(id).map(::meta)

    /** Every pack, checked together with Phoenix's pinned mods ([pinned]) against [target]. [current] = optionalEnabled(). */
    fun list(current: Map<String, Long>, pinned: List<ModMeta>, target: ServerTarget): List<ModPackInfo> = ids().map { id ->
        val dir = dir(id)
        val jars = jars(id)
        val metas = jars.map(::meta)
        val mods = jars.zip(metas).map { (jar, meta) -> ModPackMod(jar.name, meta?.id, meta?.name ?: jar.name.removeSuffix(".jar"), meta?.version, jar.length()) }
        val unreadable = jars.zip(metas).filter { it.second == null }.map { ModIssue(it.first.name, true, "${it.first.name} isn't a Fabric mod.") }
        val issues = unreadable + ModChecks.check(pinned + metas.filterNotNull(), target).filter { issue -> pinned.none { it.fileName == issue.fileName } || issue.fileName == null }
        ModPackInfo(id, name(id), props(dir).getProperty("created")?.toLongOrNull() ?: dir.lastModified(), mods, jars.sumOf { it.length() },
            inUse = jars.isNotEmpty() && jars.associate { it.name to it.length() } == current, issues = issues)
    }.sortedWith(compareBy<ModPackInfo>({ !it.inUse }, { it.name.lowercase() }))

    /** Finds a pack whose jars are exactly [files] (same names and sizes). */
    fun matching(files: Map<String, Long>): String? = ids().firstOrNull { id -> jars(id).associate { it.name to it.length() } == files }

    fun create(name: String): String {
        val clean = checkName(name)
        packsDir.mkdirs()
        var id = "pack-" + System.currentTimeMillis().toString(36)
        while (File(packsDir, id).exists()) id += "x"
        val dir = File(packsDir, ".$id.part").apply { deleteRecursively(); mkdirs() }
        writeProps(dir, Properties().apply { setProperty("name", clean); setProperty("created", System.currentTimeMillis().toString()) })
        check(dir.renameTo(File(packsDir, id))) { "Couldn't create the pack" }
        return id
    }

    /** A new pack holding copies of [files] (the mods folder is not changed). */
    fun saveFrom(files: List<File>, name: String): String {
        val id = create(name)
        try { files.forEach { copyInto(it, File(dir(id), it.name)) } } catch (e: Exception) { delete(id); throw e }
        return id
    }

    /**
     * Imports a .zip of server mod jars, or the jars inside a Modrinth .mrpack (its `overrides`). Client-only mods, files
     * that aren't Fabric mods, duplicates and Phoenix's own pinned mods are left out and reported.
     */
    fun importZip(zip: File, displayName: String?, cancelled: () -> Boolean = { false }): PackImport {
        val name = displayName?.substringBeforeLast('.')?.replace(Regex("[\\r\\n\\u0000]"), " ")?.trim()?.take(40)?.ifEmpty { null } ?: "Imported pack"
        val id = create(name)
        val dir = dir(id)
        val skipped = mutableListOf<String>()
        var added = 0
        try {
            ZipFile(zip).use { archive ->
                val entries = archive.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".jar") && !it.name.startsWith("__MACOSX/") }.toList()
                check(entries.size <= MAX_JARS) { "Packs with more than $MAX_JARS mods aren't supported" }
                var total = 0L
                val ids = mutableSetOf<String>()
                for (entry in entries) {
                    check(!cancelled()) { "The import was cancelled" }
                    val fileName = ModManager.safeFileName(entry.name)
                    if (fileName == null || File(dir, fileName).exists()) { skipped += "${entry.name.substringAfterLast('/')}: unusable or repeated file name"; continue }
                    val part = File(dir, ".$fileName.part")
                    try {
                        archive.getInputStream(entry).use { input -> part.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var size = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                size += n; total += n
                                check(size <= MAX_JAR_BYTES) { "$fileName is over 256 MB" }
                                check(total <= MAX_PACK_BYTES) { "Packs over 1 GB aren't supported" }
                                output.write(buffer, 0, n)
                            }
                        } }
                        val meta = ModChecks.read(part, fileName)
                        val reason = when {
                            meta == null -> "not a Fabric mod"
                            meta.environment == "client" -> "only runs in the game, not on servers"
                            meta.id in pinnedIds -> "Phoenix installs its own pinned copy"
                            meta.id in setOf("minecraft", "java", "fabricloader") -> "part of the server itself"
                            !ids.add(meta.id) -> "already in the pack"
                            else -> null
                        }
                        if (reason != null) { skipped += "${meta?.name ?: fileName}: $reason"; continue }
                        check(part.renameTo(File(dir, fileName))) { "Couldn't save $fileName" }
                        added++
                    } finally { part.delete() }
                }
                archive.getEntry("modrinth.index.json")?.let { index ->
                    val files = JSONObject(archive.getInputStream(index).use { it.readBytes() }.toString(Charsets.UTF_8)).optJSONArray("files")
                    val toDownload = (0 until (files?.length() ?: 0)).mapNotNull { files!!.optJSONObject(it) }
                        .filter { it.optJSONObject("env")?.optString("server") != "unsupported" }
                        .map { it.optString("path").substringAfterLast('/') }
                    if (toDownload.isNotEmpty()) skipped += "${toDownload.size} mod${if (toDownload.size == 1) "" else "s"} listed for download from Modrinth " +
                        "(${toDownload.take(5).joinToString()}${if (toDownload.size > 5) ", …" else ""}): Phoenix doesn't download them; add the .jar files with Edit"
                }
            }
            check(added > 0 || skipped.isNotEmpty()) { "There are no mod .jar files in that file" }
        } catch (e: Exception) { delete(id); throw e }
        return PackImport(id, name, added, skipped)
    }

    fun rename(id: String, name: String) {
        val dir = dir(id)
        writeProps(dir, props(dir).apply { setProperty("name", checkName(name)) })
    }

    /** Adds a jar to a pack (the pack is checked as a whole when it is listed). Returns the mod's name. */
    fun addJar(id: String, candidate: File, suggestedName: String?): String {
        val meta = ModChecks.read(candidate) ?: error("This file isn't a Fabric mod (it has no fabric.mod.json)")
        check(meta.environment != "client") { "${meta.name} only runs in the Minecraft game, not on servers" }
        check(meta.id !in pinnedIds) { "Phoenix installs its own copy of ${meta.name}" }
        metas(id).firstOrNull { it?.id == meta.id }?.let { error("${it.name} is already in this pack. Remove it first to replace it.") }
        val fileName = ModManager.safeFileName(suggestedName) ?: ModManager.safeFileName("${meta.id}-${meta.version ?: "mod"}.jar") ?: "${meta.id}.jar"
        val target = File(dir(id), fileName)
        check(!target.exists()) { "A file named $fileName is already in this pack" }
        copyInto(candidate, target)
        return meta.name
    }

    fun removeJar(id: String, fileName: String) {
        require(ModManager.safeFileName(fileName) == fileName) { "Unknown mod file" }
        val file = File(dir(id), fileName)
        check(file.isFile && file.delete()) { "That mod is no longer in the pack" }
    }

    fun delete(id: String) {
        val dir = dir(id)
        val trash = File(packsDir, ".$id.deleting")
        check(dir.renameTo(trash)) { "Couldn't delete the pack" }
        trash.deleteRecursively()
    }

    private fun dir(id: String): File {
        require(id.matches(Regex("pack-[a-z0-9]{1,20}x*"))) { "Unknown pack" }
        return File(packsDir, id).also { check(File(it, PROPS).isFile) { "That pack is gone" } }
    }

    private fun meta(jar: File): ModMeta? {
        val stamp = "${jar.length()}:${jar.lastModified()}"
        metaCache[jar.path]?.takeIf { it.first == stamp }?.let { return it.second }
        return ModChecks.read(jar).also { metaCache[jar.path] = stamp to it }
    }

    private fun copyInto(source: File, target: File) {
        val part = File(target.parentFile, ".${target.name}.part")
        try {
            source.copyTo(part, overwrite = true)
            check(!target.exists() && part.renameTo(target)) { "Couldn't copy ${target.name}" }
        } finally { part.delete() }
    }

    private fun props(dir: File) = Properties().apply { File(dir, PROPS).takeIf { it.isFile }?.inputStream()?.use(::load) }

    private fun writeProps(dir: File, props: Properties) {
        val part = File(dir, "$PROPS.part")
        part.outputStream().use { props.store(it, "Phoenix mod pack") }
        val target = File(dir, PROPS)
        check(part.renameTo(target) || (target.delete() && part.renameTo(target))) { "Couldn't save the pack" }
    }

    companion object {
        private const val PROPS = "pack.properties"
        const val MAX_JARS = 300
        private const val MAX_JAR_BYTES = 256L * 1024 * 1024
        private const val MAX_PACK_BYTES = 1024L * 1024 * 1024

        fun checkName(name: String): String {
            val clean = name.trim()
            require(clean.length in 1..40 && clean.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Use a pack name of 1–40 characters" }
            return clean
        }
    }
}
