package dev.phoenix.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorldStorageMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun world(): File = tmp.newFolder("internal", "world").apply {
        File(this, "level.dat").writeBytes(ByteArray(300) { it.toByte() })
        File(this, "session.lock").writeText("lock")
        File(this, "region").mkdirs(); File(this, "region/r.0.0.mca").writeBytes(ByteArray(9000) { (it * 7).toByte() })
    }

    private fun card(uuid: String = "A8E9-57CD", state: String = ExternalStorage.MOUNTED, removable: Boolean = true) =
        CardVolume(uuid, "SD card", state, tmp.root.resolve("card-$uuid"), removable, 64L shl 30, 32L shl 30)

    @Test fun copiesVerifiesAndSwitchesKeepingInternalOriginal() {
        val source = world(); val container = tmp.root.resolve("card/worlds")
        val migration = WorldStorageMigration(tmp.root.resolve("journal/move.properties"))
        val lines = mutableListOf<String>()
        val result = migration.run(source, container, "A8E9-57CD", { false }, { Long.MAX_VALUE }, progress = { lines += it })
        assertEquals(WorldStorageMigration.Phase.SWITCHED, result.phase)
        assertEquals(WorldStorageMigration.Phase.SWITCHED, migration.journal().phase)
        assertNull(WorldStorageMigration.verify(WorldStorageMigration.manifest(source), File(container, "world")))
        assertTrue("internal original is kept", File(source, "level.dat").isFile)
        assertFalse(File(container, "world.partial").exists())
        assertTrue(lines.all { it.startsWith("Waiting for: ") })
    }

    @Test fun refusesWhileServerRunsOrCardTooSmall() {
        val source = world(); val container = tmp.root.resolve("card/worlds")
        val migration = WorldStorageMigration(tmp.root.resolve("move.properties"))
        expectRefused { migration.run(source, container, "u", { true }, { Long.MAX_VALUE }) }
        expectRefused { migration.run(source, container, "u", { false }, { 1000L }) }
        assertEquals(WorldStorageMigration.Phase.NONE, migration.journal().phase)
        assertFalse(File(container, "world").exists())
    }

    @Test fun refusesFilesOverFat32Limit() {
        val source = world()
        expectRefused { WorldStorageMigration(tmp.root.resolve("m")).run(source, tmp.root.resolve("c"), "u", { false }, { Long.MAX_VALUE }, maxFileBytes = 5000) }
    }

    @Test fun restartsAfterProcessDeathMidCopy() {
        val source = world(); val container = tmp.root.resolve("card/worlds"); val journal = tmp.root.resolve("move.properties")
        // Simulate a killed run: journal says COPYING and a half-written partial copy is on the card.
        File(container, "world.partial/region").mkdirs()
        File(container, "world.partial/region/r.0.0.mca").writeBytes(ByteArray(10))
        File(container, "world.partial/stray.tmp").writeText("x")
        journal.writeText("phase=COPYING\nsource=${source.absolutePath.replace("\\", "\\\\")}\n")
        val result = WorldStorageMigration(journal).run(source, container, "u", { false }, { Long.MAX_VALUE })
        assertEquals(WorldStorageMigration.Phase.SWITCHED, result.phase)
        assertNull(WorldStorageMigration.verify(WorldStorageMigration.manifest(source), File(container, "world")))
    }

    @Test fun resumesVerifiedCopyWithoutRecopying() {
        val source = world(); val container = tmp.root.resolve("card/worlds"); val journalFile = tmp.root.resolve("move.properties")
        val migration = WorldStorageMigration(journalFile)
        migration.run(source, container, "u", { false }, { Long.MAX_VALUE })
        // Process died between VERIFIED and SWITCHED.
        journalFile.writeText(journalFile.readText().replace("phase=SWITCHED", "phase=VERIFIED"))
        val result = WorldStorageMigration(journalFile).run(source, container, "u", { false }, { 0L })
        assertEquals(WorldStorageMigration.Phase.SWITCHED, result.phase)
    }

    @Test fun verifyCatchesCorruption() {
        val source = world(); val copy = tmp.newFolder("copy")
        source.copyRecursively(copy)
        File(copy, "region/r.0.0.mca").writeBytes(ByteArray(9000))
        assertEquals("region/r.0.0.mca content differs", WorldStorageMigration.verify(WorldStorageMigration.manifest(source), copy))
        File(copy, "extra").writeText("x")
        assertEquals("expected 3 files, found 4", WorldStorageMigration.verify(WorldStorageMigration.manifest(source), copy))
    }

    @Test fun locationNeverFallsBackToInternalOnceCardChosen() {
        val internal = tmp.root.resolve("server")
        assertTrue(ExternalStorage.resolve(null, false, emptyList(), internal) is WorldLocation.Internal)
        // Card chosen but move not yet switched: keep the live internal world.
        assertTrue(ExternalStorage.resolve("A8E9-57CD", false, emptyList(), internal) is WorldLocation.Internal)
        val onCard = ExternalStorage.resolve("A8E9-57CD", true, listOf(card()), internal)
        assertTrue(onCard is WorldLocation.Card)
        assertNull(ExternalStorage.startRefusal(onCard))
        val missing = ExternalStorage.resolve("A8E9-57CD", true, emptyList(), internal)
        assertEquals("microSD removed", (missing as WorldLocation.CardMissing).reason)
        assertTrue(ExternalStorage.startRefusal(missing)!!.startsWith("microSD removed"))
        assertEquals("A different microSD is inserted", (ExternalStorage.resolve("A8E9-57CD", true, listOf(card("1111-2222")), internal) as WorldLocation.CardMissing).reason)
        assertTrue(ExternalStorage.resolve("A8E9-57CD", true, listOf(card(state = "unmounted")), internal) is WorldLocation.CardMissing)
        assertTrue(ExternalStorage.resolve("A8E9-57CD", true, listOf(card(removable = false)), internal) is WorldLocation.CardMissing)
    }

    @Test fun adoptedCardIsNotOfferedAndExplained() {
        val internalVol = card(uuid = "", removable = false)
        val adopted = card(uuid = "adopted", removable = false)
        assertTrue(ExternalStorage.usableCards(listOf(internalVol, adopted)).isEmpty())
        assertTrue(ExternalStorage.noCardReason(listOf(internalVol, adopted))!!.contains("adopted"))
        assertNull(ExternalStorage.noCardReason(listOf(internalVol, card())))
    }

    @Test fun speedGradesAndProbeRuns() {
        assertEquals(WriteSpeed.Grade.SLOW, WriteSpeed.grade(120.0))
        assertEquals(WriteSpeed.Grade.OK, WriteSpeed.grade(800.0))
        assertEquals(WriteSpeed.Grade.FAST, WriteSpeed.grade(2500.0))
        val dir = tmp.newFolder("probe")
        assertTrue(WriteSpeed.probe(dir, writes = 5, fileBytes = 64 * 1024) > 0)
        assertTrue(dir.listFiles()!!.isEmpty())
        assertFalse(WorldStorageMigration.backupFits(5L shl 30))
    }

    private fun expectRefused(block: () -> Unit) {
        try { block(); fail("expected refusal") } catch (expected: WorldStorageMigration.Refused) {}
    }
}
