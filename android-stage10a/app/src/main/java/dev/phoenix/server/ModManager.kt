package dev.phoenix.server

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * The server's mods folder: lists mods from their fabric.mod.json, turns them on and off, adds and removes them.
 *
 * Every change since the last start that reached "online" can be undone: removed or replaced jars are held (moved, not
 * deleted) in `.phoenix-mods/held` next to the mods folder, and the mod set of that last good start is remembered. If the
 * server then fails to start, [rollback] puts that set back. Held files are deleted only after the next good start.
 */
class ModManager(private val modsDir: File, private val requiredFiles: Set<String>) {
    private data class Meta(val id: String?, val name: String?, val version: String?, val description: String?, val environment: String?)
    /** One optional mod file of the last good start: its name without ".disabled", whether it was on, and its size. */
    data class Entry(val base: String, val enabled: Boolean, val size: Long)
    data class RollbackReport(val restored: List<String>, val turnedOff: List<String>, val removedCopies: List<String>, val setAside: List<String>, val missing: List<String>) {
        fun summary(): String = buildList {
            if (restored.isNotEmpty()) add("put back ${restored.size} mod${if (restored.size == 1) "" else "s"}")
            if (removedCopies.isNotEmpty()) add("removed the ${removedCopies.size} pack mod${if (removedCopies.size == 1) "" else "s"} just added")
            if (turnedOff.isNotEmpty()) add("turned off ${turnedOff.joinToString()}")
            if (setAside.isNotEmpty()) add("set aside ${setAside.joinToString()}")
            if (missing.isNotEmpty()) add("couldn't find ${missing.joinToString()}")
        }.joinToString("; ").ifEmpty { "no file changes were needed" }
    }

    private val stateDir = File(modsDir.parentFile, ".phoenix-mods")
    private val heldDir = File(stateDir, "held")
    private val goodFile = File(stateDir, "last-good.tsv")
    private val packCopiesFile = File(stateDir, "pack-copies.txt")
    /** fabric.mod.json is read once per file version; listing large jars repeatedly would be slow. */
    private val metaCache = ConcurrentHashMap<String, Pair<String, Meta>>()
    /** Whether each Phoenium jar carries its tile engine, read once per file version. */
    private val engineCache = ConcurrentHashMap<String, Pair<String, Boolean>>()

    fun list(): List<ModInfo> {
        val files = modFiles()
        metaCache.keys.retainAll(files.map { base(it.name) }.toSet())
        return files.map { file ->
            val meta = meta(file)
            ModInfo(
                fileName = file.name, id = meta.id, name = meta.name ?: base(file.name).removeSuffix(JAR),
                version = meta.version, description = meta.description, environment = meta.environment,
                enabled = file.name.endsWith(JAR), required = base(file.name) in requiredFiles, sizeBytes = file.length(),
            )
        }.sortedWith(compareBy<ModInfo>({ !it.required }, { it.name.lowercase() }))
    }

    fun enabledIds(): Set<String> = list().filter { it.enabled }.mapNotNull { it.id }.toSet()

    /** A pinned Phoenix mod (Geyser, Floodgate, Fabric API) that cannot be turned off, removed or swapped by a pack. */
    fun isRequired(fileName: String): Boolean = base(fileName) in requiredFiles

    /** The jars that load at the next start (pinned Phoenix mods included). */
    fun enabledJars(): List<File> = modFiles().filter { it.name.endsWith(JAR) }

    /** True when the enabled Phoenium jar contains its tile engine; older Phoenium builds do not. Read once per file version. */
    fun enabledPhoeniumEngine(): Boolean {
        val jar = modFiles().firstOrNull { it.name.endsWith(JAR) && meta(it).id == PHOENIUM_ID } ?: return false
        val stamp = "${jar.length()}:${jar.lastModified()}"
        engineCache[jar.name]?.takeIf { it.first == stamp }?.let { return it.second }
        val engine = runCatching {
            ZipFile(jar).use { zip -> PhoeniumTuning.isEngineBuild(java.util.Collections.list(zip.entries()).map { it.name }) }
        }.getOrDefault(false)
        engineCache[jar.name] = stamp to engine
        return engine
    }

    /** The enabled mods the user chose (everything except Phoenix's pinned ones), as name -> size. */
    fun optionalEnabled(): Map<String, Long> = enabledJars().filter { it.name !in requiredFiles }.associate { it.name to it.length() }

    fun setEnabled(fileName: String, enabled: Boolean) {
        val file = existing(fileName)
        check(base(file.name) !in requiredFiles) { "Phoenix manages this mod (Bedrock crossplay or voice chat), so it stays on" }
        val target = File(modsDir, if (enabled) base(file.name) else base(file.name) + ".disabled")
        if (target.name == file.name) return
        check(!target.exists()) { "Another copy of this mod is already there" }
        check(file.renameTo(target)) { "Couldn't change the mod file" }
    }

    /** Removes a mod. The file is held until the next good start, so a failed start can bring it back. */
    fun remove(fileName: String) {
        val file = existing(fileName)
        check(base(file.name) !in requiredFiles) { "Phoenix manages this mod (Bedrock crossplay or voice chat), so it stays installed" }
        hold(file)
    }

    /** Checks a picked .jar and moves it into the mods folder. Returns the mod's name. */
    fun install(candidate: File, suggestedName: String?): String {
        val meta = readMeta(candidate) ?: error("This file isn't a Fabric mod (it has no fabric.mod.json)")
        val id = meta.id ?: error("This mod has no id in its fabric.mod.json")
        val name = meta.name ?: id
        check(meta.environment != "client") { "$name only runs in the Minecraft game, not on servers" }
        check(id !in setOf("minecraft", "java", "fabricloader")) { "$name is part of the server itself" }
        list().firstOrNull { it.id == id }?.let { error("${it.name} is already installed. Remove it first to replace it.") }
        val fileName = safeFileName(suggestedName) ?: safeFileName("$id-${meta.version ?: "mod"}.jar") ?: "$id.jar"
        val target = File(modsDir, fileName)
        check(!target.exists() && !File(modsDir, "$fileName.disabled").exists()) { "A mod file named $fileName is already there" }
        copyIn(candidate, target)
        return name
    }

    /**
     * Makes [jars] the enabled optional mods: the enabled optional jars now in the folder are held, and copies of [jars]
     * are added. Disabled mods stay where they are (unless a pack jar takes their name). Pinned Phoenix mods are untouched.
     */
    fun replaceEnabled(jars: List<File>) {
        val names = jars.map { it.name }
        require(names.all { safeFileName(it) == it && it !in requiredFiles } && names.toSet().size == names.size) { "The pack has an unexpected file name" }
        modFiles().filter { base(it.name) !in requiredFiles && (it.name.endsWith(JAR) || base(it.name) in names) }.forEach(::hold)
        val copied = mutableListOf<String>()
        try {
            for (jar in jars) { copyIn(jar, File(modsDir, jar.name)); copied += jar.name }
        } finally {
            stateDir.mkdirs()
            packCopiesFile.writeText((packCopies() + copied).joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Sets aside other copies of a mod Phoenix manages (same mod id, a different file, enabled or not), so Fabric never
     * loads two versions of one mod. They stay recoverable in the set-aside folder. Returns the file names moved.
     */
    fun setAsideManagedDuplicates(): List<String> {
        val managedIds = modFiles().filter { base(it.name) in requiredFiles }.mapNotNull { meta(it).id }.toSet()
        return modFiles().filter { base(it.name) !in requiredFiles && meta(it).id?.let { id -> id in managedIds } == true }
            .map { file -> hold(file, aside = true); file.name }
    }

    /** Called when the server reached "online": remembers this mod set and deletes what was held for it. */
    fun markGood() {
        stateDir.mkdirs()
        val lines = modFiles().filter { base(it.name) !in requiredFiles }
            .joinToString("") { "${if (it.name.endsWith(JAR)) 1 else 0}\t${it.length()}\t${base(it.name)}\n" }
        val part = File(stateDir, "last-good.tsv.part")
        part.writeText("phoenix-mods-v1\n$lines")
        check(part.renameTo(goodFile) || (goodFile.delete() && part.renameTo(goodFile))) { "Couldn't save the mod journal" }
        heldDir.listFiles()?.forEach { it.delete() }
        packCopiesFile.delete()
    }

    /** The optional mods of the last good start, or null before the first one. */
    fun lastGood(): List<Entry>? {
        val lines = goodFile.takeIf { it.isFile }?.readLines() ?: return null
        if (lines.firstOrNull() != "phoenix-mods-v1") return null
        return lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val (on, size, base) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
            Entry(base, on == "1", size.toLongOrNull() ?: return@mapNotNull null)
        }
    }

    fun current(): List<Entry> = modFiles().filter { base(it.name) !in requiredFiles }
        .map { Entry(base(it.name), it.name.endsWith(JAR), it.length()) }

    /** True when the optional mods differ from the last good start (false before the first good start). */
    fun changedSinceGood(): Boolean = lastGood()?.let { it.toSet() != current().toSet() } ?: false

    /**
     * Puts back the optional mods of the last good start. Pack copies added since are deleted (the pack keeps them), other
     * new mods are turned off so they stay visible, held files come back, and on/off states are restored.
     */
    fun rollback(): RollbackReport? {
        val good = lastGood() ?: return null
        val byBase = good.associateBy { it.base }
        val copies = packCopies()
        val restored = mutableListOf<String>(); val turnedOff = mutableListOf<String>(); val removed = mutableListOf<String>()
        val setAside = mutableListOf<String>(); val missing = mutableListOf<String>()
        for (file in modFiles().filter { base(it.name) !in requiredFiles }) {
            val entry = byBase[base(file.name)]
            if (entry != null && entry.size == file.length()) continue
            when {
                base(file.name) in copies && entry == null -> { file.delete(); removed += base(file.name) }
                entry == null && file.name.endsWith(JAR) -> { check(file.renameTo(File(modsDir, file.name + ".disabled"))) { "Couldn't turn off ${file.name}" }; turnedOff += file.name }
                entry == null -> Unit
                else -> { hold(file, aside = true); setAside += file.name }
            }
        }
        for (entry in good) {
            val on = File(modsDir, entry.base); val off = File(modsDir, entry.base + ".disabled")
            if (!on.isFile && !off.isFile) {
                val held = listOf(File(heldDir, entry.base), File(heldDir, entry.base + ".disabled")).firstOrNull { it.isFile && it.length() == entry.size }
                if (held == null) { missing += entry.base; continue }
                check(held.renameTo(if (entry.enabled) on else off)) { "Couldn't put back ${entry.base}" }
                restored += entry.base
            } else if (entry.enabled && off.isFile && !on.isFile) {
                check(off.renameTo(on)) { "Couldn't turn on ${entry.base}" }
            } else if (!entry.enabled && on.isFile && !off.isFile) {
                check(on.renameTo(off)) { "Couldn't turn off ${entry.base}" }
            }
        }
        packCopiesFile.delete()
        return RollbackReport(restored, turnedOff, removed, setAside, missing)
    }

    private fun packCopies(): Set<String> = packCopiesFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** Moves [file] into the held folder. The first held copy of a name is the one the last good start used, so it wins. */
    private fun hold(file: File, aside: Boolean = false) {
        val dir = if (aside) File(stateDir, "set-aside") else heldDir
        dir.mkdirs()
        val target = File(dir, file.name)
        if (target.exists() && !aside) { check(file.delete()) { "Couldn't remove the mod" }; return }
        if (target.exists()) target.delete()
        check(file.renameTo(target)) { "Couldn't remove the mod" }
    }

    private fun copyIn(source: File, target: File) {
        modsDir.mkdirs()
        sweepParts()
        // Free space is checked on the mods folder's own volume (mods stay internal even if world data moves).
        check(modsDir.usableSpace >= source.length() + SPACE_MARGIN) { "Not enough free storage to add ${target.name}" }
        val part = File(modsDir, ".${target.name}.part")
        try {
            source.copyTo(part, overwrite = true)
            check(part.length() == source.length()) { "Couldn't copy ${target.name} completely" }
            check(!target.exists() && part.renameTo(target)) { "Couldn't add the mod file" }
        } finally { part.delete() }
    }

    /**
     * Deletes `.<name>.jar.part` copies left in the mods folder by an add that was interrupted (crash, power loss).
     * CrossplayInstaller's `.phoenix-crossplay-*.part` downloads are not ours and are left alone.
     */
    fun sweepParts(): List<String> =
        modsDir.listFiles()?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".jar.part") }
            ?.filter { it.delete() }?.map { it.name }.orEmpty()

    /**
     * Identifies the mod stack that loads at the next start, for telemetry and benchmark runs: "baseline" when only the
     * pinned Phoenix mods are enabled, otherwise "mods-" plus a SHA-256 over the sorted optional jars' names and contents.
     */
    fun stackIdentity(): String {
        val optional = enabledJars().filter { base(it.name) !in requiredFiles }.sortedBy { it.name }
        if (optional.isEmpty()) return "baseline"
        val digest = MessageDigest.getInstance("SHA-256")
        for (jar in optional) {
            digest.update("${jar.name}\t${jar.length()}\n".toByteArray(Charsets.UTF_8))
            jar.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
        }
        return "mods-" + digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Problems with the mods that load at the next start that stop the server, as sentences for the user (empty when
     * none). Phoenium runs only with the patched C2ME it is certified with (the "phoenium-settlement2" build): without it
     * Phoenium refuses to start the server (Phoenium Stage 9 S9-E16), so Phoenix says why before the start.
     */
    fun stackWarnings(): List<String> {
        val enabled = list().filter { it.enabled }
        if (enabled.none { it.id == PHOENIUM_ID }) return emptyList()
        val c2me = enabled.firstOrNull { it.id == C2ME_ID }
        return when {
            c2me == null -> listOf("Phoenium needs the patched C2ME (the \"$C2ME_BUILD\" build) and won't start without it. Add that C2ME or turn Phoenium off.")
            c2me.version?.contains(C2ME_BUILD) != true ->
                listOf("Phoenium needs the patched C2ME (the \"$C2ME_BUILD\" build), not C2ME ${c2me.version ?: "of an unknown version"}. Replace it or turn Phoenium off.")
            else -> emptyList()
        }
    }

    private fun modFiles(): List<File> =
        modsDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && (it.name.endsWith(JAR) || it.name.endsWith(DISABLED)) }.orEmpty()

    private fun existing(fileName: String): File {
        require(fileName == File(fileName).name && (fileName.endsWith(JAR) || fileName.endsWith(DISABLED))) { "Unknown mod file" }
        return File(modsDir, fileName).also { check(it.isFile) { "That mod is gone" } }
    }

    private fun meta(file: File): Meta {
        val stamp = "${file.length()}:${file.lastModified()}"
        metaCache[base(file.name)]?.takeIf { it.first == stamp }?.let { return it.second }
        return (readMeta(file) ?: Meta(null, null, null, null, null)).also { metaCache[base(file.name)] = stamp to it }
    }

    companion object {
        private const val PHOENIUM_ID = "phoenium"
        private const val C2ME_ID = "c2me"
        private const val C2ME_BUILD = "phoenium-settlement2"
        private const val JAR = ".jar"
        private const val DISABLED = ".jar.disabled"
        /** Kept free on the mods volume after an add, so the server can still write its world and logs. */
        private const val SPACE_MARGIN = 32L * 1024 * 1024
        private fun base(name: String) = name.removeSuffix(".disabled")

        fun safeFileName(name: String?): String? =
            name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.endsWith(JAR) && !it.startsWith(".") && it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }

        private fun readMeta(file: File): Meta? = runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("fabric.mod.json") ?: return@runCatching null
                if (entry.size > 512 * 1024) return@runCatching null
                val json = zip.getInputStream(entry).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                fun text(key: String) = json.optString(key).trim().takeIf { it.isNotEmpty() }
                Meta(text("id"), text("name"), text("version"), text("description"), text("environment"))
            }
        }.getOrNull()
    }
}
