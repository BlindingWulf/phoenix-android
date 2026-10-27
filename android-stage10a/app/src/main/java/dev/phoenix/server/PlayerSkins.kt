package dev.phoenix.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Faces from the skins players actually wear. Java skins come from Mojang's session server by UUID;
 * Bedrock skins come from the GeyserMC Global API, where Geyser already publishes each Floodgate
 * player's converted skin by XUID. Each face is fetched at most once every few hours and kept as an
 * 8×8 PNG in the app cache; anything that fails falls back to the generated PixelAvatar.
 */
object PlayerSkins {
    sealed interface Source {
        data class Java(val id: String) : Source
        data class Bedrock(val xuid: Long) : Source
    }

    /** Floodgate gives Bedrock players UUID(0, xuid); Mojang accounts are version 4. Offline-mode UUIDs (version 3) have no skin. */
    fun sourceOf(uuid: String?): Source? {
        val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return null
        return when {
            id.mostSignificantBits == 0L && id.leastSignificantBits > 0 -> Source.Bedrock(id.leastSignificantBits)
            id.version() == 4 -> Source.Java(id.toString().replace("-", ""))
            else -> null
        }
    }

    private val texturePath = Regex("""^/texture/[0-9a-f]{16,128}$""")

    /** Only Mojang's texture server is ever downloaded from, always over HTTPS. */
    fun safeTextureUrl(url: String?): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if (parsed.host != "textures.minecraft.net" || parsed.protocol !in setOf("http", "https") || !texturePath.matches(parsed.path)) return null
        return "https://textures.minecraft.net${parsed.path}"
    }

    /**
     * The front of the head with the hat layer on top, as 8×8 ARGB pixels. Works for 64×64, legacy 64×32
     * and HD skins (any multiple of 64 wide); returns null for anything else.
     */
    fun faceFrom(pixels: IntArray, width: Int, height: Int): IntArray? {
        if (width < 64 || width % 64 != 0 || (height != width && height != width / 2) || pixels.size != width * height) return null
        val scale = width / 64
        fun at(x: Int, y: Int) = pixels[y * scale * width + x * scale]
        val hat = IntArray(64) { at(40 + it % 8, 8 + it / 8) }
        // Old 64×32 skins often fill the hat with solid color; Minecraft treats a fully opaque hat there as no hat.
        val useHat = height == width || hat.any { (it ushr 24) < 0xFF }
        return IntArray(64) {
            val top = hat[it]
            if (useHat && (top ushr 24) >= 0x80) top or OPAQUE else at(8 + it % 8, 8 + it / 8) or OPAQUE
        }
    }

    private const val FRESH_MS = 6 * 3_600_000L
    private const val RETRY_MS = 3 * 60_000L
    private const val OPAQUE = 0xFF000000.toInt()
    private val memory by lazy { LruCache<String, Bitmap>(64) }
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** A face already in memory, for drawing the first frame without waiting. */
    fun cached(uuid: String?): Bitmap? = uuid?.let(memory::get)
    /** Drops the decoded faces kept in memory (files in the cache stay); used before a server start and when memory is low. */
    fun trim() = memory.evictAll()

    /** Blocking; call off the main thread. */
    fun face(context: Context, uuid: String): Bitmap? {
        memory.get(uuid)?.let { return it }
        val source = sourceOf(uuid) ?: return null
        synchronized(locks.getOrPut(uuid) { Any() }) {
            memory.get(uuid)?.let { return it }
            val file = File(context.cacheDir, "skins/$uuid.png")
            val stored = file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
            if (stored != null && System.currentTimeMillis() - file.lastModified() < FRESH_MS) return stored.also { memory.put(uuid, it) }
            failedAt[uuid]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_MS) return stored }
            val fresh = runCatching { download(source) }.getOrNull()
            if (fresh == null) { failedAt[uuid] = SystemClock.elapsedRealtime(); return stored?.also { memory.put(uuid, it) } }
            failedAt.remove(uuid)
            runCatching {
                file.parentFile?.mkdirs()
                val bytes = ByteArrayOutputStream().also { fresh.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                File(file.path + ".part").apply { writeBytes(bytes); renameTo(file) }
            }
            memory.put(uuid, fresh)
            return fresh
        }
    }

    private fun download(source: Source): Bitmap? {
        val texture = when (source) {
            is Source.Java -> {
                val profile = JSONObject(get("https://sessionserver.mojang.com/session/minecraft/profile/${source.id}", 64 * 1024).decodeToString())
                val properties = profile.optJSONArray("properties") ?: return null
                val textures = (0 until properties.length()).map(properties::getJSONObject).firstOrNull { it.optString("name") == "textures" } ?: return null
                val decoded = JSONObject(Base64.getDecoder().decode(textures.getString("value")).decodeToString())
                decoded.optJSONObject("textures")?.optJSONObject("SKIN")?.optString("url")
            }
            is Source.Bedrock -> {
                val skin = JSONObject(get("https://api.geysermc.org/v2/skin/${source.xuid}", 64 * 1024).decodeToString())
                skin.optString("texture_id").takeIf { it.isNotBlank() }?.let { "https://textures.minecraft.net/texture/$it" }
            }
        }
        val url = safeTextureUrl(texture) ?: return null
        val png = get(url, 512 * 1024)
        val skin = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val pixels = IntArray(skin.width * skin.height).also { skin.getPixels(it, 0, skin.width, 0, 0, skin.width, skin.height) }
        val face = faceFrom(pixels, skin.width, skin.height) ?: return null
        return Bitmap.createBitmap(face, 8, 8, Bitmap.Config.ARGB_8888)
    }

    private fun get(url: String, limit: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, image/png")
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    check(out.size() <= limit) { "Response too large" }
                }
                return out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
