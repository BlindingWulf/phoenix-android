package dev.phoenix.server

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The bundled spawn preparation mod: what `SPAWNPREP_MANIFEST.json` pins. */
data class SpawnPrepArtifact(
    val id: String,
    val version: String,
    /** The jar's name in the server's mods folder. */
    val fileName: String,
    /** Where the jar lies in the app's assets. */
    val assetPath: String,
    /** Lower-case hex SHA-256 of the jar. */
    val sha256: String,
    val sizeBytes: Long,
)

object SpawnPrepManifest {
    const val ASSET = "SPAWNPREP_MANIFEST.json"
    const val MOD_ID = "phoenix-spawnprep"
    private const val MAX_JAR_BYTES = 8L * 1024 * 1024
    private val sha256 = Regex("[0-9a-fA-F]{64}")
    private val jarName = Regex("[A-Za-z0-9_+\\-][A-Za-z0-9._+\\-]{0,118}\\.jar")
    private val assetName = Regex("[A-Za-z0-9_.+\\-]{1,120}(/[A-Za-z0-9_.+\\-]{1,120}){0,3}")

    /** The pinned artifact, or an IllegalArgumentException when the manifest is malformed. */
    fun parse(json: String): SpawnPrepArtifact {
        try {
            val a = JSONObject(json).optJSONObject("artifact") ?: throw IllegalArgumentException("Spawn preparation manifest has no artifact")
            val id = a.getString("id")
            val fileName = a.getString("fileName")
            val asset = a.getString("asset")
            val hash = a.getString("sha256")
            val size = a.getLong("sizeBytes")
            require(id.matches(Regex("[a-z0-9_\\-]{1,64}"))) { "Spawn preparation manifest has a bad id" }
            require(fileName.matches(jarName)) { "Spawn preparation manifest has an unsafe file name" }
            require(asset.matches(assetName) && asset.split('/').none { it == "." || it == ".." }) { "Spawn preparation manifest has an unsafe asset path" }
            require(hash.matches(sha256)) { "Spawn preparation manifest has a bad SHA-256" }
            require(size in 1..MAX_JAR_BYTES) { "Spawn preparation manifest has a bad size" }
            return SpawnPrepArtifact(id, a.optString("version", ""), fileName, asset, hash.lowercase(), size)
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("Spawn preparation manifest is not valid", e)
        }
    }
}

/**
 * Puts the bundled, hash-pinned jar into a server's `mods/` folder, or takes it out again. The jar is checked while it is
 * copied and only then moved into place, so a bad asset never reaches the mods folder. Nothing but this one file (and its
 * `.disabled` twin) is ever touched.
 */
class SpawnPrepInstaller(private val artifact: SpawnPrepArtifact, private val openAsset: () -> InputStream) {
    enum class Outcome { INSTALLED, ALREADY_CURRENT, REMOVED, SET_ASIDE, ABSENT }

    /** Makes the mod present ([enabled]) or absent in [server]'s mods folder. */
    fun apply(server: File, enabled: Boolean): Outcome {
        val mods = File(server, "mods")
        return if (enabled) install(mods) else remove(server, mods)
    }

    private fun install(mods: File): Outcome {
        check(!Files.isSymbolicLink(mods.toPath())) { "The mods folder cannot be a symlink" }
        mods.mkdirs()
        val target = File(mods, artifact.fileName)
        val twin = File(mods, artifact.fileName + ".disabled")
        check(!Files.isSymbolicLink(target.toPath())) { "The spawn preparation mod cannot be a symlink" }
        if (target.isFile && digest(target) == artifact.sha256) { twin.delete(); return Outcome.ALREADY_CURRENT }
        check(mods.usableSpace >= artifact.sizeBytes + SPACE_MARGIN) { "Not enough free storage for the spawn preparation mod" }
        // ".jar.part" is also what the mod manager sweeps if a crash leaves this behind.
        val part = File(mods, ".${artifact.fileName}.part")
        try {
            val sum = MessageDigest.getInstance("SHA-256")
            var received = 0L
            openAsset().use { input -> part.outputStream().use { output ->
                val bytes = ByteArray(16384)
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    received += read
                    check(received <= artifact.sizeBytes) { "The spawn preparation mod failed its integrity check (larger than pinned)" }
                    sum.update(bytes, 0, read)
                    output.write(bytes, 0, read)
                }
                output.fd.sync()
            } }
            check(received == artifact.sizeBytes && hex(sum.digest()) == artifact.sha256) { "The spawn preparation mod failed its integrity check" }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            twin.delete()
        } finally { part.delete() }
        return Outcome.INSTALLED
    }

    private fun remove(server: File, mods: File): Outcome {
        if (!mods.isDirectory) return Outcome.ABSENT
        val present = listOf(File(mods, artifact.fileName), File(mods, artifact.fileName + ".disabled"))
            .filter { Files.isSymbolicLink(it.toPath()) || it.isFile }
        if (present.isEmpty()) return Outcome.ABSENT
        var setAside = false
        for (file in present) {
            if (Files.isSymbolicLink(file.toPath()) || digest(file) == artifact.sha256) { check(file.delete()) { "Couldn't remove ${file.name}" }; continue }
            // Not our jar (edited or replaced by someone): keep it recoverable rather than deleting it.
            val aside = File(server, ".phoenix-mods/set-aside").apply { mkdirs() }
            val moved = File(aside, file.name)
            if (moved.exists()) moved.delete()
            check(file.renameTo(moved)) { "Couldn't set aside ${file.name}" }
            setAside = true
        }
        return if (setAside) Outcome.SET_ASIDE else Outcome.REMOVED
    }

    private fun digest(file: File): String {
        val sum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16384)
            while (true) { val n = input.read(bytes); if (n < 0) break; sum.update(bytes, 0, n) }
        }
        return hex(sum.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        /** Kept free after the add, so the server can still write its world and logs. */
        const val SPACE_MARGIN = 8L * 1024 * 1024
    }
}
