package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModChecksTest {
    @get:Rule val temp = TemporaryFolder()
    private val target = ServerTarget("26.2", "0.19.5", 25)
    private val fabricApi = ModMeta("fabric-api.jar", "fabric-api", "Fabric API", "0.161.0+26.2", null,
        provides = mapOf("fabric" to "0.161.0+26.2", "fabric-networking-api-v1" to "5.0.1", "fabric-api-base" to "1.0.3"))

    private fun mod(id: String, depends: Map<String, List<String>> = emptyMap(), version: String = "1.0.0", env: String? = null,
                    breaks: Map<String, List<String>> = emptyMap()) =
        ModMeta("$id.jar", id, id.replaceFirstChar { it.uppercase() }, version, env, depends = depends, breaks = breaks)

    @Test fun aConsistentSetHasNoIssues() {
        val lithium = mod("lithium", mapOf("minecraft" to listOf("26.2"), "fabricloader" to listOf(">=0.16.0"), "fabric-networking-api-v1" to listOf("*")))
        assertEquals(emptyList<ModIssue>(), ModChecks.check(listOf(fabricApi, lithium), target))
    }

    @Test fun wrongMinecraftVersionBlocks() {
        val old = mod("oldmod", mapOf("minecraft" to listOf("~1.21.4")))
        val issues = ModChecks.check(listOf(fabricApi, old), target)
        assertEquals(1, issues.size)
        assertTrue(issues[0].blocking)
        assertTrue(issues[0].message, issues[0].message.contains("made for Minecraft ~1.21.4; this server runs 26.2"))
    }

    @Test fun missingDependencyAndOldLoaderBlock() {
        val needsCloth = mod("needs", mapOf("cloth-config" to listOf(">=20"), "fabricloader" to listOf(">=0.20.0")))
        val issues = ModChecks.check(listOf(needsCloth), target)
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.blocking })
        assertTrue(issues.any { it.message.contains("needs cloth-config, which isn't in the set") })
        assertTrue(issues.any { it.message.contains("needs Fabric Loader >=0.20.0; this server has 0.19.5") })
    }

    @Test fun nestedAndProvidedIdsSatisfyDependencies() {
        val user = mod("user", mapOf("fabric" to listOf("*"), "fabric-api-base" to listOf(">=1.0")))
        assertEquals(emptyList<ModIssue>(), ModChecks.check(listOf(fabricApi, user), target))
    }

    @Test fun duplicatesClientOnlyAndBreaksBlock() {
        val a = mod("dup"); val b = a.copy(fileName = "dup-2.jar")
        val client = mod("shader", env = "client")
        val breaker = mod("breaker", breaks = mapOf("lithium" to listOf("*")))
        val issues = ModChecks.check(listOf(a, b, client, breaker, mod("lithium")), target)
        assertTrue(issues.any { it.blocking && it.message.contains("is in the set 2 times") })
        assertTrue(issues.any { it.blocking && it.message.contains("only runs in the Minecraft game") })
        assertTrue(issues.any { it.blocking && it.message.contains("doesn't work with lithium") })
    }

    @Test fun undecidablePredicateIsOnlyAWarning() {
        val odd = mod("odd", mapOf("minecraft" to listOf(">=x")))
        val issues = ModChecks.check(listOf(odd), target)
        assertEquals(1, issues.size)
        assertFalse(issues[0].blocking)
    }

    @Test fun readsMetadataAndNestedJarsFromARealJar() {
        val inner = jarBytes("""{"schemaVersion":1,"id":"cloth-config","version":"20.0.1","provides":["cloth"]}""")
        val jar = temp.newFile("bundle.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"schemaVersion":1,"id":"bundle","name":"Bundle","version":"2.0.0","environment":"*",
                "jars":[{"file":"META-INF/jars/cloth.jar"}],"depends":{"minecraft":["26.2","26.1"],"cloth-config":">=20"}}""".toByteArray())
            zip.putNextEntry(ZipEntry("META-INF/jars/cloth.jar")); zip.write(inner)
        }
        val meta = ModChecks.read(jar)
        assertNotNull(meta)
        assertEquals("bundle", meta!!.id)
        assertEquals("20.0.1", meta.provides["cloth-config"])
        assertEquals("20.0.1", meta.provides["cloth"])
        assertEquals(listOf("26.2", "26.1"), meta.depends["minecraft"])
        assertEquals(emptyList<ModIssue>(), ModChecks.check(listOf(meta), target))
        assertEquals(null, ModChecks.read(temp.newFile("not-a-mod.jar").also { File(it.path).writeText("x") }))
    }

    private fun jarBytes(json: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("fabric.mod.json")); zip.write(json.toByteArray()) }
        return out.toByteArray()
    }
}
