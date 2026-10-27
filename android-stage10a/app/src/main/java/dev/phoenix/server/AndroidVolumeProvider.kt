package dev.phoenix.server

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager

/** App-specific directories on every mounted volume. Needs no storage permission; the card directory is deleted on uninstall. */
class AndroidVolumeProvider(private val context: Context) : VolumeProvider {
    override fun volumes(): List<CardVolume> {
        val storage = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull().map { dir ->
            val volume = storage.getStorageVolume(dir)
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            CardVolume(
                uuid = volume?.uuid,
                label = volume?.getDescription(context) ?: "Storage",
                state = volume?.state ?: Environment.getExternalStorageState(dir),
                root = dir,
                removable = runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false),
                totalBytes = stat?.totalBytes ?: 0,
                freeBytes = stat?.availableBytes ?: 0,
            )
        }
    }
}
