package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `config/phoenix-spawnprep.properties`: Phoenix owns `enabled` and `radiusBlocks` and keeps every other line. */
class SpawnPrepConfigTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun defaultsAndChoices() {
        assertEquals(256, SpawnPrepConfig.DEFAULT_RADIUS)
        assertEquals(listOf(256, 512, 768, 1024, 1280), SpawnPrepConfig.RADIUS_CHOICES)
        assertEquals(4096, SpawnPrepConfig.MAX_RADIUS)
    }

    @Test fun radiusIsClampedToTheModsRange() {
        assertEquals(0, SpawnPrepConfig.clampRadius(-5))
        assertEquals(0, SpawnPrepConfig.clampRadius(0))
        assertEquals(256, SpawnPrepConfig.clampRadius(256))
        assertEquals(4096, SpawnPrepConfig.clampRadius(4096))
        assertEquals(4096, SpawnPrepConfig.clampRadius(1_000_000))
    }

    @Test fun typedRadiusIsValidatedNotClamped() {
        assertEquals(0, SpawnPrepConfig.parseRadius("0"))
        assertEquals(300, SpawnPrepConfig.parseRadius(" 300 "))
        assertEquals(4096, SpawnPrepConfig.parseRadius("4096"))
        for (bad in listOf("", " ", "-1", "4097", "99999999999", "12.5", "abc", "1e3", "0x10", "256 blocks"))
            assertNull("accepted '$bad'", SpawnPrepConfig.parseRadius(bad))
    }

    /** UI check MAINPHONE-SPAWNPREP-UI-1: Custom 5000 greyed Save with no word why. */
    @Test fun rejectedRadiusSaysWhy() {
        assertNull(SpawnPrepConfig.radiusProblem("300"))
        assertNull(SpawnPrepConfig.radiusProblem(" 4096 "))
        assertEquals("The largest radius is 4096 blocks.", SpawnPrepConfig.radiusProblem("5000"))
        assertEquals("The largest radius is 4096 blocks.", SpawnPrepConfig.radiusProblem("99999999999"))
        assertEquals("The radius cannot be negative.", SpawnPrepConfig.radiusProblem("-1"))
        assertEquals("Use whole blocks only (digits).", SpawnPrepConfig.radiusProblem("12.5"))
        assertEquals("Enter a radius in blocks.", SpawnPrepConfig.radiusProblem(" "))
    }

    @Test fun newFileHasOnlyPhoenixsKeys() {
        assertEquals("enabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge(null, true, 256))
        assertEquals("enabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge("", true, 256))
        assertEquals("enabled=false\nradiusBlocks=1280\n", SpawnPrepConfig.merge("  \n", false, 1280))
    }

    @Test fun mergeReplacesOurKeysAndKeepsEverythingElseInPlace() {
        val existing = "# Phoenix spawn preparation\nenabled=false\n\nmaxInFlight=32\nradiusBlocks=1280\n! another comment\nexistingWorlds=true\n"
        assertEquals("# Phoenix spawn preparation\nenabled=true\n\nmaxInFlight=32\nradiusBlocks=512\n! another comment\nexistingWorlds=true\n",
            SpawnPrepConfig.merge(existing, true, 512))
    }

    @Test fun mergeAppendsMissingKeysAndHandlesSpacingAndColons() {
        assertEquals("maxInFlight=8\nenabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge("maxInFlight=8", true, 256))
        assertEquals("maxInFlight=8\nenabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge("maxInFlight=8\n", true, 256))
        assertEquals("enabled=true\nradiusBlocks=64\nx=1\n", SpawnPrepConfig.merge("  enabled : false\nradiusBlocks   =   9\nx=1\n", true, 64))
    }

    @Test fun mergeKeepsCommentedOutKeysAndKeyLookalikes() {
        val existing = "#enabled=false\n#radiusBlocks=1\nenabledX=1\nradiusBlocksExtra=2\n"
        assertEquals("#enabled=false\n#radiusBlocks=1\nenabledX=1\nradiusBlocksExtra=2\nenabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge(existing, true, 256))
    }

    @Test fun mergeCollapsesDuplicateKeysToOneLine() {
        val merged = SpawnPrepConfig.merge("radiusBlocks=1\nenabled=false\nradiusBlocks=2\n", true, 300)
        assertEquals(1, merged.lines().count { it.startsWith("radiusBlocks=") })
        assertEquals(1, merged.lines().count { it.startsWith("enabled=") })
        assertEquals(setOf("enabled=true", "radiusBlocks=300"), merged.lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test fun mergeAcceptsWindowsLineEndings() {
        assertEquals("a=1\nenabled=true\nradiusBlocks=256\n", SpawnPrepConfig.merge("a=1\r\nenabled=false\r\n", true, 256))
    }

    @Test fun mergeClampsAnOutOfRangeRadius() {
        assertTrue(SpawnPrepConfig.merge(null, true, 99_999).contains("radiusBlocks=4096"))
        assertTrue(SpawnPrepConfig.merge(null, true, -1).contains("radiusBlocks=0"))
    }

    @Test fun mergeIsStable() {
        val once = SpawnPrepConfig.merge("# hi\nmaxInFlight=8\n", true, 768)
        assertEquals(once, SpawnPrepConfig.merge(once, true, 768))
    }

    @Test fun writeCreatesTheConfigFolderAndPreservesOtherKeys() {
        val file = File(temp.root, "config/phoenix-spawnprep.properties")
        SpawnPrepConfig.write(file, true, 256)
        assertEquals("enabled=true\nradiusBlocks=256\n", file.readText())
        file.writeText("# mine\nmaxInFlight=4\nenabled=true\nradiusBlocks=256\nexistingWorlds=true\n")
        SpawnPrepConfig.write(file, true, 1024)
        assertEquals("# mine\nmaxInFlight=4\nenabled=true\nradiusBlocks=1024\nexistingWorlds=true\n", file.readText())
        assertEquals(listOf("phoenix-spawnprep.properties"), file.parentFile.list()!!.toList())
    }

    @Test fun writeIfPresentLeavesAMissingFileAlone() {
        val file = File(temp.root, "config/phoenix-spawnprep.properties")
        assertFalse(SpawnPrepConfig.writeIfPresent(file, false, 256))
        assertFalse(file.exists())
        assertFalse(file.parentFile.exists())
        file.parentFile.mkdirs(); file.writeText("enabled=true\nradiusBlocks=256\nmaxInFlight=8\n")
        assertTrue(SpawnPrepConfig.writeIfPresent(file, false, 256))
        assertEquals("enabled=false\nradiusBlocks=256\nmaxInFlight=8\n", file.readText())
    }
}
