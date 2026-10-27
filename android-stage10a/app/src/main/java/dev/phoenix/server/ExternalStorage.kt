package dev.phoenix.server

import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** One storage volume as Android reports it. [root] is the app-specific directory on it (deleted on uninstall). */
data class CardVolume(
    val uuid: String?,
    val label: String,
    val state: String,
    val root: File,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
)

/** Android-free view of the volumes, so selection and migration logic run in JVM tests. */
interface VolumeProvider {
    fun volumes(): List<CardVolume>
}

/** Where the world container is, decided from the remembered card UUID. Never falls back to internal once a card was chosen. */
sealed class WorldLocation {
    /** No card has been chosen (or no verified move finished): keep today's internal world exactly as is. */
    data class Internal(val container: File) : WorldLocation()
    data class Card(val volume: CardVolume, val container: File) : WorldLocation()
    /** The chosen card is absent, a different card, or not mounted read-write. Starting the server must refuse. */
    data class CardMissing(val uuid: String, val reason: String) : WorldLocation()
}

object ExternalStorage {
    const val MOUNTED = "mounted"
    const val WORLDS_DIR = "worlds"
    /** FAT32 cannot hold a file of 4 GiB or more; exFAT can, but the format is not visible through FUSE, so assume the worst. */
    const val FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1

    /** Cards a world can live on: removable, mounted read-write, with a UUID to remember. An adopted card is not removable. */
    fun usableCards(volumes: List<CardVolume>) = volumes.filter { it.removable && it.state == MOUNTED && !it.uuid.isNullOrBlank() }

    /** Explains why no card is offered, for the Settings and Worlds pages. Null when at least one card is usable. */
    fun noCardReason(volumes: List<CardVolume>): String? = when {
        usableCards(volumes).isNotEmpty() -> null
        volumes.any { it.removable && it.state != MOUNTED } -> "microSD is present but ${volumes.first { it.removable && it.state != MOUNTED }.state.replace('_', ' ')}"
        volumes.none { it.removable } && volumes.size > 1 -> "No removable microSD. A card adopted as internal storage can't hold worlds separately; reformat it as portable storage in Android settings."
        else -> "No microSD card found"
    }

    fun resolve(selectedUuid: String?, switched: Boolean, volumes: List<CardVolume>, internalContainer: File): WorldLocation {
        if (selectedUuid.isNullOrBlank() || !switched) return WorldLocation.Internal(internalContainer)
        val match = volumes.firstOrNull { it.uuid == selectedUuid }
            ?: return WorldLocation.CardMissing(selectedUuid, if (usableCards(volumes).isEmpty()) "microSD removed" else "A different microSD is inserted")
        if (!match.removable) return WorldLocation.CardMissing(selectedUuid, "That card is now adopted as internal storage")
        if (match.state != MOUNTED) return WorldLocation.CardMissing(selectedUuid, "microSD is ${match.state.replace('_', ' ')}")
        return WorldLocation.Card(match, File(match.root, WORLDS_DIR))
    }

    /** Refusal message for starting the server, or null when the world location can be used. */
    fun startRefusal(location: WorldLocation): String? = (location as? WorldLocation.CardMissing)?.let { "${it.reason}. The world lives on the microSD; insert the same card to start the server." }

    fun label(location: WorldLocation) = when (location) {
        is WorldLocation.Internal -> "on phone storage"
        is WorldLocation.Card -> "on microSD (${location.volume.label})"
        is WorldLocation.CardMissing -> "microSD missing"
    }
}

/** Random 4 KiB writes, which is what region files do. A1 cards promise 500 write IOPS, A2 cards 2000. */
object WriteSpeed {
    enum class Grade(val text: String) {
        FAST("Fast enough (A2 class)"),
        OK("OK (A1 class)"),
        SLOW("Slow card: chunk saves will stall. Use an A1 or A2 microSD."),
    }

    fun grade(iops: Double) = when {
        iops >= 2000 -> Grade.FAST
        iops >= 500 -> Grade.OK
        else -> Grade.SLOW
    }

    /** Writes [writes] random 4 KiB blocks into a scratch file in [dir], syncing each, and returns write IOPS. Run off the main thread. */
    fun probe(dir: File, writes: Int = 200, fileBytes: Long = 8L * 1024 * 1024, clock: () -> Long = System::nanoTime): Double {
        val file = File(dir, ".phoenix-speed-probe")
        val block = ByteArray(4096).also { Random(7).nextBytes(it) }
        return try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(fileBytes)
                val blocks = (fileBytes / block.size).toInt()
                val random = Random(11)
                val start = clock()
                repeat(writes) {
                    raf.seek(random.nextInt(blocks).toLong() * block.size)
                    raf.write(block)
                    raf.fd.sync()
                }
                val seconds = (clock() - start).coerceAtLeast(1) / 1e9
                writes / seconds
            }
        } finally {
            file.delete()
        }
    }
}
