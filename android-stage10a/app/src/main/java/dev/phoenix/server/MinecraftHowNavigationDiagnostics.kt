package dev.phoenix.server

import android.content.Context
import android.net.Uri
import android.system.Os
import org.json.JSONObject
import java.io.File

/** Debug-only bounded routing evidence; excludes query, fragment, cookies, bodies and dynamic path segments. */
internal object MinecraftHowNavigationDiagnostics {
    private val lock = Any()
    private val fixedSegments = setOf("api", "oauth2", "authorize", "auth", "discord", "facebook", "google", "callback",
        "login", "login.php", "device-based", "regular", "dialog", "oauth", "checkpoint", "identify", "recover",
        "dashboard", "dns", "privacy", "terms", "connect", "cancel", "success", "blank", "error",
        "unified", "login_via", "app")

    fun record(context: Context, event: String, uri: Uri, status: Int? = null) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val path = uri.path.orEmpty().split('/').take(8).joinToString("/") {
                if (it.isEmpty() || it in fixedSegments || it.matches(Regex("v[0-9]{1,2}\\.[0-9]{1,2}"))) it else "<other>"
            }.take(180)
            val entry = JSONObject().put("event", event).put("scheme", uri.scheme.orEmpty().take(32))
                .put("host", uri.host.orEmpty().take(253)).put("path", path)
                .put("status", status ?: JSONObject.NULL).put("queryRetained", false)
            synchronized(lock) {
                val file = File(context.noBackupFilesDir, "minecraft-how-navigation.jsonl")
                if (file.exists() && file.length() > 64 * 1024) file.writeText("")
                file.appendText(entry.toString() + "\n")
                Os.chmod(file.absolutePath, 0b110000000)
            }
        }
    }
}
