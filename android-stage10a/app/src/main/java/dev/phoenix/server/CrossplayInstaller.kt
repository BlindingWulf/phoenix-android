package dev.phoenix.server

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import org.phoenix.runtime.RuntimeLayout
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Installs only pinned server-side crossplay mods while Minecraft is stopped. */
class CrossplayInstaller(private val app: Context) {
    fun ensure(server: File, cancelled: () -> Boolean, progress: (String) -> Unit) {
        val manifest = JSONObject(app.assets.open("CROSSPLAY_MANIFEST.json").bufferedReader().use { it.readText() })
        check(manifest.getString("minecraftVersion") == "26.2")
        val mods = File(server, "mods").apply { mkdirs() }
        check(!Files.isSymbolicLink(mods.toPath())) { "Crossplay mods directory cannot be a symlink" }
        val staged = mutableListOf<Pair<File, File>>()
        try {
            val rows = manifest.getJSONArray("artifacts")
            for (i in 0 until rows.length()) {
                check(!cancelled()) { "Crossplay installation cancelled" }
                val row = rows.getJSONObject(i)
                val name = row.getString("fileName")
                require(name.matches(Regex("[A-Za-z0-9_.+\\-]+\\.jar"))) { "Invalid crossplay filename" }
                val target = File(mods, name)
                check(!Files.isSymbolicLink(target.toPath())) { "Crossplay artifact cannot be a symlink" }
                val expected = row.getString("sha256")
                if (target.isFile && RuntimeLayout.hash(target.toPath()) == expected) continue
                val uri = URI(row.getString("url"))
                require(uri.scheme == "https" && uri.host in setOf("download.geysermc.org", "cdn.modrinth.com") && uri.userInfo == null && uri.port == -1)
                val size = row.getLong("sizeBytes")
                require(size in 1..(128L * 1048576L))
                check(mods.usableSpace >= size + 32L * 1048576L) { "Insufficient storage for crossplay mods" }
                val part = File.createTempFile(".phoenix-crossplay-", ".part", mods)
                staged += part to target
                val connection = uri.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Phoenix/0.3")
                try {
                    check(connection.responseCode == 200) { "Crossplay download failed (HTTP ${connection.responseCode})" }
                    var received = 0L
                    connection.inputStream.use { input -> part.outputStream().use { output ->
                        val bytes = ByteArray(32768)
                        while (true) {
                            check(!cancelled()) { "Crossplay installation cancelled" }
                            val read = input.read(bytes)
                            if (read < 0) break
                            received += read
                            check(received <= size) { "Crossplay download exceeded its pinned size" }
                            output.write(bytes, 0, read)
                            progress("${row.getString("id")}: ${received / 1048576} / ${size / 1048576} MiB")
                        }
                        output.fd.sync()
                    } }
                    check(received == size && RuntimeLayout.hash(part.toPath()) == expected) { "Crossplay artifact integrity mismatch" }
                } finally { connection.disconnect() }
            }
            check(!cancelled()) { "Crossplay installation cancelled" }
            for ((part, target) in staged) Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.forEach { it.first.delete() } }
        val config = File(server, "config/Geyser-Fabric/config.yml")
        if (!config.exists()) {
            config.parentFile!!.mkdirs()
            // The pinned Configurate loader fills omitted settings from its typed defaults.
            val defaults = """
                config-version: 8
                bedrock:
                  address: 0.0.0.0
                  port: 19132
                  transport: raknet
                  clone-remote-port: false
                java:
                  auth-type: floodgate
                enable-metrics: false
                log-player-ip-addresses: false
            """.trimIndent()+"\n"
            val atomic = AtomicFile(config)
            val output = atomic.startWrite()
            try { output.write(defaults.toByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        }
    }
}
