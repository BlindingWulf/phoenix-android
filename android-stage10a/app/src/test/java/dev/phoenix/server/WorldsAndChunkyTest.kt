package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorldsAndChunkyTest {
    @Test
    fun worldNamesAreSafeFolderNames() {
        assertEquals("survival-main", WorldManager.checkName(" survival main "))
        assertEquals("Creative_2", WorldManager.checkName("Creative_2"))
        for (bad in listOf("", "../world", "a/b", "mods", "config", ".hidden", "x".repeat(33))) {
            try { WorldManager.checkName(bad); fail("accepted $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun backupNamesRoundTripIncludingHyphenatedWorlds() {
        val name = WorldManager.backupName("my-world", 1791543525764)
        assertEquals("my-world-1791543525764.zip", name)
        assertEquals("my-world" to 1791543525764L, WorldManager.parseBackupName(name))
        // Backups made before worlds had names use the same pattern.
        assertEquals("world" to 1791543525764L, WorldManager.parseBackupName("world-1791543525764.zip"))
        assertNull(WorldManager.parseBackupName("world-1791543525764.zip.partial"))
        assertNull(WorldManager.parseBackupName("../x-1791543525764.zip"))
    }

    @Test
    fun chunkImportReadsTheNewDimensionsLayout() {
        val names = listOf(
            "world/level.dat",
            "world/dimensions/minecraft/overworld/region/r.0.0.mca",
            "world/dimensions/minecraft/overworld/entities/r.0.0.mca",
            "world/dimensions/minecraft/the_nether/region/r.-1.0.mca",
            "world/dimensions/minecraft/the_end/poi/r.0.-1.mca",
        )
        assertEquals(listOf(
            "world/dimensions/minecraft/overworld/region/r.0.0.mca" to "region/r.0.0.mca",
            "world/dimensions/minecraft/overworld/entities/r.0.0.mca" to "entities/r.0.0.mca",
        ), ChunkImport.select(names, "overworld"))
        assertEquals(listOf("world/dimensions/minecraft/the_nether/region/r.-1.0.mca" to "region/r.-1.0.mca"), ChunkImport.select(names, "the_nether"))
        assertEquals(listOf("world/dimensions/minecraft/the_end/poi/r.0.-1.mca" to "poi/r.0.-1.mca"), ChunkImport.select(names, "the_end"))
    }

    @Test
    fun chunkImportReadsTheOldLayoutAndRegionOnlyZips() {
        val old = listOf("New World/region/r.0.0.mca", "New World/DIM-1/region/r.0.0.mca", "New World/DIM1/region/r.1.1.mca")
        assertEquals(listOf("New World/region/r.0.0.mca" to "region/r.0.0.mca"), ChunkImport.select(old, "overworld"))
        assertEquals(listOf("New World/DIM-1/region/r.0.0.mca" to "region/r.0.0.mca"), ChunkImport.select(old, "the_nether"))
        assertEquals(listOf("New World/DIM1/region/r.1.1.mca" to "region/r.1.1.mca"), ChunkImport.select(old, "the_end"))
        // A ZIP of just the folders goes to whichever dimension the user picked.
        val bare = listOf("region/r.0.0.mca", "region/r.0.1.mca")
        assertEquals(2, ChunkImport.select(bare, "the_nether").size)
    }

    @Test
    fun chunkImportRefusesUnsafeOrMissingFiles() {
        assertFails { ChunkImport.select(listOf("../region/r.0.0.mca"), "overworld") }
        assertFails { ChunkImport.select(listOf("world/level.dat"), "overworld") }
        // An old-style world without a Nether folder has no Nether chunks to give.
        assertFails { ChunkImport.select(listOf("w/region/r.0.0.mca", "w/DIM1/region/r.0.0.mca"), "the_nether") }
        assertFails { ChunkImport.select(listOf("a/region/r.0.0.mca", "b/region/r.0.0.mca"), "overworld") }
    }

    @Test
    fun chunkyMessagesBecomeProgress() {
        var status = ChunkyStatus(state = "STARTING")
        status = ChunkyLog.update(status, "[10:00:00] [Server thread/INFO]: [Chunky] Task started for minecraft:overworld at 0, 0 with radius 1000.")!!
        assertEquals("RUNNING", status.state)
        assertEquals("minecraft:overworld", status.world)
        val progress = "[10:00:05] [Chunky/INFO]: [Chunky] Task running for minecraft:overworld. Processed: 1234 chunks (12.34%), ETA: 0:01:23, Rate: 45.6 cps, Current: 12, -7"
        assertTrue(ChunkyLog.isProgress(progress))
        status = ChunkyLog.update(status, progress)!!
        assertEquals(12.34, status.percent!!, 0.001)
        assertEquals(1234L, status.processed)
        assertEquals("0:01:23", status.eta)
        assertEquals(45.6, status.rate!!, 0.001)
        status = ChunkyLog.update(status, "[10:03:00] [Server thread/INFO]: [Chunky] Task finished for minecraft:overworld. Processed: 15876 chunks (100.00%), Total time: 0:02:03")!!
        assertEquals("DONE", status.state)
        assertNull(status.eta)
        assertNull(ChunkyLog.update(status, "[10:03:01] [Server thread/INFO]: <Steve> chunky is cool"))
        assertFalse(ChunkyLog.isProgress("[10:03:01] [Server thread/INFO]: [Chunky] Task paused for minecraft:overworld."))
    }

    @Test
    fun statusWordingPicksTheRightColor() {
        assertEquals(Tone.ERROR, toneOf("Minecraft.How couldn't save the name."))
        assertEquals(Tone.ERROR, toneOf("That name is not available."))
        assertEquals(Tone.WARN, toneOf("Sign in to Minecraft.How to continue."))
        assertEquals(Tone.WORKING, toneOf("Checking the name and domain with Minecraft.How…"))
        assertEquals(Tone.OK, toneOf("DNS records point to your server. Verified."))
        assertEquals(Tone.IDLE, toneOf("Choose a hostname."))
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("expected a failure") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }
}
