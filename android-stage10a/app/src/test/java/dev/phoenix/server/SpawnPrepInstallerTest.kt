package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Installing and removing the bundled, hash-pinned spawn preparation mod. */
class SpawnPrepInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: File
    private lateinit var mods: File

    private val good = "pretend this is the pinned phoenix-spawnprep jar".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun artifact(bytes: ByteArray = good, name: String = "phoenix-spawnprep.jar") =
        SpawnPrepArtifact("phoenix-spawnprep", "0.1.0", name, "spawnprep/phoenix-spawnprep.jar", sha(bytes), bytes.size.toLong())

    private var opened = 0
    private fun installer(source: ByteArray = good, expect: ByteArray = good) =
        SpawnPrepInstaller(artifact(expect)) { opened++; ByteArrayInputStream(source) }

    @Before fun setUp() { server = temp.newFolder("server"); mods = File(server, "mods") }

    private fun leftovers() = mods.list()!!.filter { it.endsWith(".part") }

    @Test fun installsTheVerifiedJarIntoMods() {
        assertEquals(SpawnPrepInstaller.Outcome.INSTALLED, installer().apply(server, true))
        assertEquals(sha(good), sha(File(mods, "phoenix-spawnprep.jar").readBytes()))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun anInstalledCurrentJarIsLeftAloneAndTheAssetIsNotReadAgain() {
        installer().apply(server, true)
        val file = File(mods, "phoenix-spawnprep.jar")
        file.setLastModified(1_000_000_000L)
        opened = 0
        assertEquals(SpawnPrepInstaller.Outcome.ALREADY_CURRENT, installer().apply(server, true))
        assertEquals(0, opened)
        assertEquals(1_000_000_000L, file.lastModified())
    }

    @Test fun aStaleOrTamperedJarAtOurNameIsReplaced() {
        mods.mkdirs()
        File(mods, "phoenix-spawnprep.jar").writeBytes("older or edited copy".toByteArray())
        assertEquals(SpawnPrepInstaller.Outcome.INSTALLED, installer().apply(server, true))
        assertEquals(sha(good), sha(File(mods, "phoenix-spawnprep.jar").readBytes()))
    }

    @Test fun aDisabledCopyIsPutBackAsTheEnabledJar() {
        mods.mkdirs()
        File(mods, "phoenix-spawnprep.jar.disabled").writeBytes(good)
        assertEquals(SpawnPrepInstaller.Outcome.INSTALLED, installer().apply(server, true))
        assertTrue(File(mods, "phoenix-spawnprep.jar").isFile)
        assertFalse(File(mods, "phoenix-spawnprep.jar.disabled").exists())
    }

    @Test fun anAssetThatFailsItsHashIsRefusedAndNothingIsInstalled() {
        val tampered = good.copyOf().also { it[0] = (it[0] + 1).toByte() }
        try { installer(source = tampered).apply(server, true); fail("installed a jar that does not match the pinned hash") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!, expected.message!!.contains("integrity")) }
        assertFalse(File(mods, "phoenix-spawnprep.jar").exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aFailedInstallKeepsAnExistingJarAndLeavesNoPartFile() {
        mods.mkdirs()
        val existing = "the jar that works today".toByteArray()
        File(mods, "phoenix-spawnprep.jar").writeBytes(existing)
        val tampered = good + byteArrayOf(1)
        try { installer(source = tampered).apply(server, true); fail("accepted a jar of the wrong size") } catch (_: IllegalStateException) { }
        assertEquals(sha(existing), sha(File(mods, "phoenix-spawnprep.jar").readBytes()))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aTruncatedAssetIsRefused() {
        try { installer(source = good.copyOf(5)).apply(server, true); fail("accepted a truncated jar") } catch (_: IllegalStateException) { }
        assertFalse(File(mods, "phoenix-spawnprep.jar").exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun anAssetThatCannotBeReadLeavesNothingBehind() {
        val broken = SpawnPrepInstaller(artifact()) { object : InputStream() { override fun read(): Int = throw IOException("disk gone") } }
        try { broken.apply(server, true); fail("expected an error") } catch (_: IOException) { }
        assertFalse(File(mods, "phoenix-spawnprep.jar").exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun disablingRemovesTheJar() {
        installer().apply(server, true)
        assertEquals(SpawnPrepInstaller.Outcome.REMOVED, installer().apply(server, false))
        assertFalse(File(mods, "phoenix-spawnprep.jar").exists())
        assertEquals(emptyList<String>(), mods.list()!!.toList())
    }

    @Test fun disablingRemovesTheDisabledCopyToo() {
        mods.mkdirs()
        File(mods, "phoenix-spawnprep.jar.disabled").writeBytes(good)
        assertEquals(SpawnPrepInstaller.Outcome.REMOVED, installer().apply(server, false))
        assertEquals(emptyList<String>(), mods.list()!!.toList())
    }

    @Test fun disablingSetsAsideAFileThatIsNotOurJarInsteadOfDeletingIt() {
        mods.mkdirs()
        val foreign = "someone's own edit".toByteArray()
        File(mods, "phoenix-spawnprep.jar").writeBytes(foreign)
        assertEquals(SpawnPrepInstaller.Outcome.SET_ASIDE, installer().apply(server, false))
        assertFalse(File(mods, "phoenix-spawnprep.jar").exists())
        assertEquals(sha(foreign), sha(File(server, ".phoenix-mods/set-aside/phoenix-spawnprep.jar").readBytes()))
    }

    @Test fun disablingWithNothingInstalledTouchesNothing() {
        assertEquals(SpawnPrepInstaller.Outcome.ABSENT, installer().apply(server, false))
        assertFalse("no mods folder is created just to remove nothing", mods.exists())
        assertEquals(0, opened)
        mods.mkdirs(); File(mods, "other.jar").writeBytes(byteArrayOf(1))
        assertEquals(SpawnPrepInstaller.Outcome.ABSENT, installer().apply(server, false))
        assertEquals(listOf("other.jar"), mods.list()!!.toList())
    }

    @Test fun otherModsAreNeverTouched() {
        mods.mkdirs()
        File(mods, "chunky.jar").writeBytes(byteArrayOf(1, 2, 3)); File(mods, "lithium.jar.disabled").writeBytes(byteArrayOf(4))
        installer().apply(server, true)
        installer().apply(server, false)
        assertEquals(listOf("chunky.jar", "lithium.jar.disabled"), mods.list()!!.sorted())
        assertEquals(listOf<Byte>(1, 2, 3), File(mods, "chunky.jar").readBytes().toList())
    }

    @Test fun applyIsRepeatable() {
        repeat(3) { assertTrue(installer().apply(server, true) in setOf(SpawnPrepInstaller.Outcome.INSTALLED, SpawnPrepInstaller.Outcome.ALREADY_CURRENT)) }
        repeat(2) { installer().apply(server, false) }
        installer().apply(server, true)
        assertTrue(File(mods, "phoenix-spawnprep.jar").isFile)
    }

    // ---- manifest

    private fun manifest(sha: String = "a".repeat(64), name: String = "phoenix-spawnprep.jar", id: String = "phoenix-spawnprep", size: Long = 27778, asset: String = "spawnprep/phoenix-spawnprep.jar") =
        """{"schemaVersion":1,"minecraftVersion":"26.2","artifact":{"id":"$id","version":"0.1.0","fileName":"$name","asset":"$asset","sha256":"$sha","sizeBytes":$size}}"""

    @Test fun manifestIsParsed() {
        val a = SpawnPrepManifest.parse(manifest())
        assertEquals("phoenix-spawnprep", a.id); assertEquals("0.1.0", a.version); assertEquals("phoenix-spawnprep.jar", a.fileName)
        assertEquals("spawnprep/phoenix-spawnprep.jar", a.assetPath); assertEquals("a".repeat(64), a.sha256); assertEquals(27778L, a.sizeBytes)
    }

    @Test fun aSha256InUpperCaseIsNormalised() {
        assertEquals("ab".repeat(32), SpawnPrepManifest.parse(manifest(sha = "AB".repeat(32))).sha256)
    }

    @Test fun badManifestsAreRefused() {
        val bad = listOf(
            manifest(sha = "abc"), manifest(sha = "g".repeat(64)), manifest(sha = ""),
            manifest(name = "../evil.jar"), manifest(name = "a/b.jar"), manifest(name = "x.zip"), manifest(name = ".hidden.jar"), manifest(name = "has space.jar"),
            manifest(id = ""), manifest(size = 0), manifest(size = -1), manifest(size = 999_999_999), manifest(asset = "../x.jar"), manifest(asset = "/abs.jar"),
            "{}", "not json", "",
        )
        for (text in bad) {
            try { SpawnPrepManifest.parse(text); fail("accepted: $text") } catch (_: IllegalArgumentException) { }
        }
    }

    // ---- the shipped files

    private val assets = File("src/main/assets")

    @Test fun theShippedJarMatchesItsPinnedHash() {
        val artifact = SpawnPrepManifest.parse(File(assets, "SPAWNPREP_MANIFEST.json").readText())
        // Pinned from phoenix-spawnprep-693D1C066C6F.jar (0.1.1, 2026-10-18): the 1CFB0EDFC505 build validated on Phone A
        // (LAPTOP-SPAWNPREP-NWKILL-1) and Phone B with only its declared version changed (every class file identical). It
        // saves the new world's settings before preparing, so a first start killed during preparation resumes. The earlier
        // AECD0C457460 build left such a world unable to start ("Overworld settings missing") and must not ship.
        assertEquals("693d1c066c6ffd85adc38872af404f49556da85eecb769ff72df9e71d2f6e324", artifact.sha256)
        assertEquals(28057L, artifact.sizeBytes)
        val jar = File(assets, artifact.assetPath)
        assertTrue("${jar.absolutePath} is missing", jar.isFile)
        assertEquals(artifact.sizeBytes, jar.length())
        assertEquals(artifact.sha256, sha(jar.readBytes()))
        ZipFile(jar).use { zip ->
            val meta = zip.getInputStream(zip.getEntry("fabric.mod.json")).readBytes().toString(Charsets.UTF_8)
            assertTrue(meta, meta.contains("\"id\": \"phoenix-spawnprep\"") && meta.contains("\"environment\": \"server\""))
            // The Mods tab shows the jar's own version: it must name this build, not the earlier jar's 0.1.0.
            assertTrue(meta, meta.contains("\"version\": \"${artifact.version}\""))
        }
        assertEquals("0.1.1", artifact.version)
    }

    @Test fun theShippedInstallerWorksOnTheShippedJar() {
        val artifact = SpawnPrepManifest.parse(File(assets, "SPAWNPREP_MANIFEST.json").readText())
        val jar = File(assets, artifact.assetPath)
        assertEquals(SpawnPrepInstaller.Outcome.INSTALLED, SpawnPrepInstaller(artifact) { jar.inputStream() }.apply(server, true))
        assertEquals(artifact.sha256, sha(File(mods, artifact.fileName).readBytes()))
    }

    // ---- with the mod manager

    private fun modJar(file: File, id: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"schemaVersion":1,"id":"$id","name":"$id","version":"1.0.0","environment":"*"}""".toByteArray())
        }
    }

    @Test fun theManagedJarIsRequiredAndKeepsTheModStackBaseline() {
        mods.mkdirs()
        modJar(File(mods, "Geyser-Fabric.jar"), "geyser-fabric")
        val manager = ModManager(mods, setOf("Geyser-Fabric.jar", "phoenix-spawnprep.jar"))
        val baseline = manager.stackIdentity()
        assertEquals("baseline", baseline)
        modJar(File(mods, "phoenix-spawnprep.jar"), "phoenix-spawnprep")
        assertEquals("baseline", manager.stackIdentity())
        val row = manager.list().first { it.id == "phoenix-spawnprep" }
        assertTrue(row.required)
        assertTrue(manager.optionalEnabled().isEmpty())
        try { manager.remove("phoenix-spawnprep.jar"); fail("a managed jar must not be removable here") } catch (_: IllegalStateException) { }
    }

    @Test fun anotherCopyOfTheModIsSetAsideSoFabricNeverLoadsTwo() {
        mods.mkdirs()
        modJar(File(mods, "phoenix-spawnprep.jar"), "phoenix-spawnprep")
        modJar(File(mods, "phoenix-spawnprep-0.0.9.jar"), "phoenix-spawnprep")
        val manager = ModManager(mods, setOf("phoenix-spawnprep.jar"))
        assertEquals(listOf("phoenix-spawnprep-0.0.9.jar"), manager.setAsideManagedDuplicates())
    }
}
