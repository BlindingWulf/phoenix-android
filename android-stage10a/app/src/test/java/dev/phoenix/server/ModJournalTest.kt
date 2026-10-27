package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The mods folder's rollback journal and mod-pack store, on real files. */
class ModJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: File
    private lateinit var mods: File
    private lateinit var manager: ModManager

    @Before fun setUp() {
        server = temp.newFolder("server"); mods = File(server, "mods").apply { mkdirs() }
        manager = ModManager(mods, setOf("Geyser-Fabric.jar"))
        jar(File(mods, "Geyser-Fabric.jar"), "geyser-fabric")
    }

    private fun jar(file: File, id: String, extra: String = "", env: String = "*", version: String = "1.0.0"): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"schemaVersion":1,"id":"$id","name":"$id","version":"$version","environment":"$env"$extra}""".toByteArray())
        }
        return file
    }

    private fun names() = mods.list()!!.filter { !it.startsWith(".") }.sorted()

    @Test fun anOptionalCopyOfAManagedModIsSetAsideNotDeleted() {
        jar(File(mods, "geyser-old.jar"), "geyser-fabric", version = "0.9")
        jar(File(mods, "other.jar.disabled"), "geyser-fabric", version = "0.8")
        jar(File(mods, "a.jar"), "a")
        assertEquals(listOf("geyser-old.jar", "other.jar.disabled"), manager.setAsideManagedDuplicates().sorted())
        assertEquals(listOf("Geyser-Fabric.jar", "a.jar"), names())
        assertTrue(File(server, ".phoenix-mods/set-aside/geyser-old.jar").isFile)
        assertEquals(emptyList<String>(), manager.setAsideManagedDuplicates())
    }

    @Test fun nothingToRollBackBeforeTheFirstGoodStart() {
        jar(File(mods, "a.jar"), "a")
        assertFalse(manager.changedSinceGood())
        assertNull(manager.rollback())
    }

    @Test fun rollbackUndoesAddRemoveAndToggles() {
        jar(File(mods, "keep.jar"), "keep"); jar(File(mods, "gone.jar"), "gone"); jar(File(mods, "off.jar.disabled"), "off")
        manager.markGood()
        assertFalse(manager.changedSinceGood())
        // after the good start: remove one, add one, turn one on and one off
        manager.remove("gone.jar")
        jar(File(mods, "new.jar"), "new")
        manager.setEnabled("off.jar.disabled", true)
        manager.setEnabled("keep.jar", false)
        assertTrue(manager.changedSinceGood())
        val report = manager.rollback()
        assertNotNull(report)
        assertEquals(listOf("gone.jar"), report!!.restored)
        assertEquals(listOf("new.jar"), report.turnedOff)
        assertEquals(listOf("Geyser-Fabric.jar", "gone.jar", "keep.jar", "new.jar.disabled", "off.jar.disabled"), names())
        // the set-aside new mod is off but still listed; the optional set equals the good one again apart from it
        assertEquals(manager.lastGood()!!.toSet(), manager.current().filter { it.base != "new.jar" }.toSet())
    }

    @Test fun aGoodStartPurgesHeldFiles() {
        jar(File(mods, "gone.jar"), "gone")
        manager.markGood()
        manager.remove("gone.jar")
        val held = File(server, ".phoenix-mods/held/gone.jar")
        assertTrue(held.isFile)
        manager.markGood()
        assertFalse(held.exists())
        assertEquals(emptyList<ModManager.Entry>(), manager.lastGood())
    }

    @Test fun packSwitchRollsBackToThePreviousMods() {
        jar(File(mods, "old.jar"), "old"); jar(File(mods, "spare.jar.disabled"), "spare")
        manager.markGood()
        val packs = ModPackStore(File(server, "modpacks"), setOf("geyser-fabric", "floodgate", "fabric-api"))
        val id = packs.create("Test pack")
        packs.addJar(id, jar(temp.newFile("p1.jar"), "p1"), "p1.jar")
        packs.addJar(id, jar(temp.newFile("p2.jar"), "p2"), "p2.jar")
        manager.replaceEnabled(packs.jars(id))
        assertEquals(listOf("Geyser-Fabric.jar", "p1.jar", "p2.jar", "spare.jar.disabled"), names())
        assertEquals(mapOf("p1.jar" to File(mods, "p1.jar").length(), "p2.jar" to File(mods, "p2.jar").length()), manager.optionalEnabled())
        // the server fails to start with the pack: pack copies are deleted, the old mod comes back, the pack keeps its jars
        val report = manager.rollback()!!
        assertEquals(listOf("p1.jar", "p2.jar"), report.removedCopies.sorted())
        assertEquals(listOf("old.jar"), report.restored)
        assertEquals(listOf("Geyser-Fabric.jar", "old.jar", "spare.jar.disabled"), names())
        assertEquals(2, packs.jars(id).size)
    }

    @Test fun packImportSkipsClientPinnedAndDuplicateJars() {
        val zip = temp.newFile("Cool Pack.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun add(path: String, file: File) { out.putNextEntry(ZipEntry(path)); out.write(file.readBytes()) }
            add("mods/lithium.jar", jar(temp.newFile("l.jar"), "lithium", ""","depends":{"minecraft":"~1.21.4"}"""))
            add("mods/sodium.jar", jar(temp.newFile("s.jar"), "sodium", env = "client"))
            add("mods/geyser.jar", jar(temp.newFile("g.jar"), "geyser-fabric"))
            add("other/lithium-copy.jar", jar(temp.newFile("l2.jar"), "lithium"))
            add("readme.txt", temp.newFile("readme.txt"))
        }
        val packs = ModPackStore(File(server, "modpacks"), setOf("geyser-fabric", "floodgate", "fabric-api"))
        val result = packs.importZip(zip, "Cool Pack.zip")
        assertEquals("Cool Pack", result.name)
        assertEquals(1, result.added)
        assertEquals(3, result.skipped.size)
        val info = packs.list(emptyMap(), emptyList(), ServerTarget("26.2", "0.19.5", 25)).single()
        assertTrue(info.blocked)
        assertTrue(info.issues.single().message.contains("made for Minecraft ~1.21.4"))
        assertFalse(info.inUse)
        // edit: replace the outdated jar with a compatible one; the pack becomes usable
        packs.removeJar(result.id, "lithium.jar")
        packs.addJar(result.id, jar(temp.newFile("l3.jar"), "lithium", ""","depends":{"minecraft":"26.2"}"""), "lithium-26.2.jar")
        val fixed = packs.list(emptyMap(), emptyList(), ServerTarget("26.2", "0.19.5", 25)).single()
        assertFalse(fixed.blocked)
        packs.rename(result.id, "Renamed")
        assertEquals("Renamed", packs.name(result.id))
        packs.delete(result.id)
        assertEquals(emptyList<String>(), packs.ids())
    }

    @Test fun installSweepsPartialCopiesLeftByAnInterruptedAdd() {
        File(mods, ".old.jar.part").writeText("half a jar")
        File(mods, ".phoenix-crossplay-1.part").writeText("crossplay download")
        manager.install(jar(temp.newFile("fresh.jar"), "fresh"), "fresh.jar")
        assertFalse(File(mods, ".old.jar.part").exists())
        // CrossplayInstaller's staging files are not the mod manager's to delete
        assertTrue(File(mods, ".phoenix-crossplay-1.part").exists())
        assertEquals(listOf("Geyser-Fabric.jar", "fresh.jar"), names().filter { it.endsWith(".jar") })
    }

    @Test fun phoeniumWithoutThePatchedC2meIsReportedBeforeAStart() {
        assertTrue(manager.stackWarnings().isEmpty())
        jar(File(mods, "phoenium.jar"), "phoenium")
        assertTrue(manager.stackWarnings().single().contains("won't start without it"))
        jar(File(mods, "c2me.jar"), "c2me", version = "0.4.2-alpha.0.43+26.2")
        assertTrue(manager.stackWarnings().single().contains("not C2ME 0.4.2-alpha.0.43+26.2"))
        jar(File(mods, "c2me.jar"), "c2me", version = "0.4.2-alpha.0.43-phoenium-settlement2+26.2")
        assertTrue(manager.stackWarnings().isEmpty())
        manager.setEnabled("phoenium.jar", false)
        jar(File(mods, "c2me.jar"), "c2me", version = "0.4.2-alpha.0.43+26.2")
        assertTrue(manager.stackWarnings().isEmpty())
    }

    @Test fun stackIdentityIsBaselineWithOnlyPinnedModsAndTracksOptionalJars() {
        assertEquals("baseline", manager.stackIdentity())
        jar(File(mods, "off.jar.disabled"), "off")
        assertEquals("baseline", manager.stackIdentity())
        jar(File(mods, "lithium.jar"), "lithium")
        val first = manager.stackIdentity()
        assertTrue(first.matches(Regex("mods-[0-9a-f]{16}")))
        assertEquals(first, manager.stackIdentity())
        jar(File(mods, "lithium.jar"), "lithium", extra = ""","depends":{"minecraft":"26.2"}""")
        assertFalse(first == manager.stackIdentity())
        manager.setEnabled("lithium.jar", false)
        assertEquals("baseline", manager.stackIdentity())
    }
}
