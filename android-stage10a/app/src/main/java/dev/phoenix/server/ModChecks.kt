package dev.phoenix.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** What Phoenix needs from a mod's fabric.mod.json to check it before the server starts. */
data class ModMeta(
    val fileName: String,
    val id: String,
    val name: String,
    val version: String?,
    val environment: String?,
    /** Other ids this jar answers to: its `provides` plus every mod nested inside it (Fabric API's modules, bundled libraries). */
    val provides: Map<String, String?> = emptyMap(),
    val depends: Map<String, List<String>> = emptyMap(),
    val breaks: Map<String, List<String>> = emptyMap(),
)

/** The server a mod set is checked against. */
data class ServerTarget(val minecraft: String, val loader: String, val java: Int)

data class ModIssue(val fileName: String?, val blocking: Boolean, val message: String)

object ModChecks {
    private const val MAX_META_BYTES = 512 * 1024
    private const val MAX_NESTED = 400

    /** Reads a jar's fabric.mod.json and those of the jars nested in it; null when it isn't a Fabric mod. */
    fun read(file: File, fileName: String = file.name): ModMeta? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("fabric.mod.json") ?: return@runCatching null
            if (entry.size > MAX_META_BYTES) return@runCatching null
            val json = JSONObject(zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8))
            val nested = mutableMapOf<String, String?>()
            for (path in nestedJars(json)) {
                if (nested.size >= MAX_NESTED) break
                val inner = zip.getEntry(path) ?: continue
                zip.getInputStream(inner).use { collectNested(it, nested, depth = 1) }
            }
            parse(json, fileName, nested)
        }
    }.getOrNull()

    /** [nested] collects the ids (and versions) of jar-in-jar mods, up to two levels deep. */
    private fun collectNested(input: InputStream, nested: MutableMap<String, String?>, depth: Int) {
        val jars = mutableMapOf<String, ByteArray>()
        var meta: JSONObject? = null
        ZipInputStream(input).let { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "fabric.mod.json" -> meta = JSONObject(zip.readBounded(MAX_META_BYTES).toString(Charsets.UTF_8))
                    depth < 2 && entry.name.startsWith("META-INF/jars/") && entry.name.endsWith(".jar") -> jars[entry.name] = zip.readBounded(64 * 1024 * 1024)
                }
            }
        }
        val json = meta ?: return
        val id = json.optString("id").trim().ifEmpty { return }
        val version = json.optString("version").trim().ifEmpty { null }
        nested[id] = version
        provides(json).forEach { nested.putIfAbsent(it, version) }
        for (path in nestedJars(json)) jars[path]?.let { if (nested.size < MAX_NESTED) collectNested(it.inputStream(), nested, depth + 1) }
    }

    private fun ZipInputStream.readBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val n = read(buffer); if (n < 0) break
            out.write(buffer, 0, n); check(out.size() <= limit) { "nested entry too large" }
        }
        return out.toByteArray()
    }

    private fun nestedJars(json: JSONObject): List<String> {
        val jars = json.optJSONArray("jars") ?: return emptyList()
        return (0 until jars.length()).mapNotNull { jars.optJSONObject(it)?.optString("file")?.takeIf { f -> f.endsWith(".jar") } }
    }

    private fun provides(json: JSONObject): List<String> {
        val array = json.optJSONArray("provides") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).trim().ifEmpty { null } }
    }

    /** Fabric allows a predicate as a string or as a list of alternatives. */
    private fun predicates(json: JSONObject?): Map<String, List<String>> {
        json ?: return emptyMap()
        return json.keys().asSequence().associateWith { key ->
            when (val value = json.opt(key)) {
                is JSONArray -> (0 until value.length()).map { value.optString(it) }
                null -> listOf("*")
                else -> listOf(value.toString())
            }
        }
    }

    fun parse(json: JSONObject, fileName: String, nested: Map<String, String?> = emptyMap()): ModMeta? {
        val id = json.optString("id").trim().ifEmpty { return null }
        fun text(key: String) = json.optString(key).trim().takeIf { it.isNotEmpty() }
        val version = text("version")
        val provided = LinkedHashMap<String, String?>()
        provides(json).forEach { provided[it] = version }
        nested.forEach { (k, v) -> provided.putIfAbsent(k, v) }
        return ModMeta(fileName, id, text("name") ?: id, version, text("environment"), provided,
            predicates(json.optJSONObject("depends")), predicates(json.optJSONObject("breaks")))
    }

    /**
     * Checks a whole mod set as Fabric would at startup. Blocking issues stop the server from starting (or are certain to
     * break it): duplicates, client-only mods, missing or wrong-version dependencies, declared incompatibilities.
     */
    fun check(mods: List<ModMeta>, target: ServerTarget): List<ModIssue> {
        val issues = mutableListOf<ModIssue>()
        mods.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (_, copies) ->
            issues += ModIssue(copies.last().fileName, true,
                "${copies.first().name} is in the set ${copies.size} times (${copies.joinToString { it.fileName }}). Keep one.")
        }
        mods.filter { it.environment == "client" }.forEach {
            issues += ModIssue(it.fileName, true, "${it.name} only runs in the Minecraft game, not on servers.")
        }
        val available = HashMap<String, String?>()
        mods.forEach { mod -> mod.provides.forEach { (k, v) -> available.putIfAbsent(k, v) } }
        mods.forEach { mod -> available[mod.id] = mod.version }
        available["minecraft"] = target.minecraft
        available["fabricloader"] = target.loader
        available["fabric-loader"] = target.loader
        available["java"] = target.java.toString()
        for (mod in mods) {
            for ((dep, wanted) in mod.depends) {
                if (!available.containsKey(dep)) {
                    issues += ModIssue(mod.fileName, true, "${mod.name} needs $dep, which isn't in the set.")
                    continue
                }
                val have = available[dep] ?: continue
                when (FabricVersions.matchesAny(wanted, have)) {
                    false -> issues += ModIssue(mod.fileName, true, when (dep) {
                        "minecraft" -> "${mod.name} is made for Minecraft ${wanted.joinToString(" or ")}; this server runs ${target.minecraft}."
                        "fabricloader" -> "${mod.name} needs Fabric Loader ${wanted.joinToString(" or ")}; this server has ${target.loader}."
                        "java" -> "${mod.name} needs Java ${wanted.joinToString(" or ")}; this server runs Java ${target.java}."
                        else -> "${mod.name} needs $dep ${wanted.joinToString(" or ")}, but the set has $have."
                    })
                    null -> issues += ModIssue(mod.fileName, false, "Couldn't check ${mod.name}'s need for $dep ${wanted.joinToString(" or ")} (has $have).")
                    true -> Unit
                }
            }
            for ((other, versions) in mod.breaks) {
                if (other == mod.id) continue
                val have = available[other] ?: continue
                if (FabricVersions.matchesAny(versions, have) == true) {
                    issues += ModIssue(mod.fileName, true, "${mod.name} doesn't work with $other $have.")
                }
            }
        }
        return issues.distinct()
    }
}
