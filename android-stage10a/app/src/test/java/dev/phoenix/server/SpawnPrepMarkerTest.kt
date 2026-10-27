package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `<world>/phoenix-spawnprep.json`, as the mod writes it (Gson record, pretty printed). */
class SpawnPrepMarkerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun json(state: String, radius: Int = 256, done: Int = 1089, total: Int = 1089, generated: Int = 1000, existing: Int = 89, failed: Int = 0) = """
        {
          "version": 1,
          "state": "$state",
          "dimension": "minecraft:overworld",
          "centerX": 0,
          "centerZ": 0,
          "radiusBlocks": $radius,
          "done": $done,
          "total": $total,
          "generated": $generated,
          "existing": $existing,
          "failed": $failed,
          "updatedEpochMs": 1792200000000
        }
    """.trimIndent()

    @Test fun readsEveryState() {
        for (state in SpawnPrepMarkerState.values()) {
            val last = SpawnPrepMarker.parse(json(state.name))!!
            assertEquals(state, last.state)
            assertEquals(256, last.radiusBlocks)
            assertEquals(1089, last.done); assertEquals(1089, last.total)
            assertEquals(1000, last.generated); assertEquals(89, last.existing); assertEquals(0, last.failed)
            assertEquals("minecraft:overworld", last.dimension)
            assertEquals(1792200000000L, last.updatedEpochMs)
        }
    }

    @Test fun runningAndPartialResumeTheOthersDoNot() {
        assertTrue(SpawnPrepMarker.parse(json("RUNNING"))!!.willResume)
        assertTrue(SpawnPrepMarker.parse(json("PARTIAL", failed = 3))!!.willResume)
        assertFalse(SpawnPrepMarker.parse(json("COMPLETE"))!!.willResume)
        assertFalse(SpawnPrepMarker.parse(json("CANCELLED"))!!.willResume)
    }

    @Test fun missingFieldsDefaultToZeroButStateIsKept() {
        val last = SpawnPrepMarker.parse("""{"state":"RUNNING","radiusBlocks":512}""")!!
        assertEquals(SpawnPrepMarkerState.RUNNING, last.state)
        assertEquals(512, last.radiusBlocks)
        assertEquals(0, last.total); assertEquals(0, last.done)
        assertNull(last.dimension)
    }

    @Test fun corruptOrUnknownIsNull() {
        for (bad in listOf("", "   ", "{", "not json", "[]", "null", "{}", """{"state":"EXPLODED"}""", """{"state":null}""", """{"state":42}""",
            """{"state":"RUNNING","done":"lots"}""", json("COMPLETE").take(60))) {
            assertNull("accepted: $bad", SpawnPrepMarker.parse(bad))
        }
    }

    @Test fun readFromFile() {
        val file = File(temp.root, "phoenix-spawnprep.json")
        assertNull("missing file", SpawnPrepMarker.read(file))
        file.writeText(json("PARTIAL", failed = 2))
        val last = SpawnPrepMarker.read(file)
        assertNotNull(last); assertEquals(SpawnPrepMarkerState.PARTIAL, last!!.state); assertEquals(2, last.failed)
        file.writeText("{ half a file")
        assertNull("corrupt file", SpawnPrepMarker.read(file))
        assertNull("a directory is not a marker", SpawnPrepMarker.read(temp.newFolder("dir")))
    }

    @Test fun oversizedFileIsRefused() {
        val file = File(temp.root, "big.json")
        file.writeText(json("COMPLETE") + " ".repeat(300_000))
        assertNull(SpawnPrepMarker.read(file))
    }

    @Test fun describeSaysWhatHappened() {
        assertEquals("Last spawn preparation: complete, 1,089 chunks around spawn (radius 256 blocks): 1,000 generated, 89 already there, 0 failed.",
            SpawnPrepMarker.parse(json("COMPLETE"))!!.describe())
        assertEquals("Last spawn preparation: partly done, 1,089 chunks around spawn (radius 256 blocks): 1,000 generated, 86 already there, 3 failed.",
            SpawnPrepMarker.parse(json("PARTIAL", generated = 1000, existing = 86, failed = 3))!!.describe())
        assertEquals("Last spawn preparation: cancelled at 400 of 1,089 chunks (radius 768 blocks): 300 generated, 100 already there, 0 failed.",
            SpawnPrepMarker.parse(json("CANCELLED", radius = 768, done = 400, generated = 300, existing = 100))!!.describe())
        assertEquals("Last spawn preparation: stopped at 400 of 1,089 chunks (radius 256 blocks): 300 generated, 100 already there, 0 failed.",
            SpawnPrepMarker.parse(json("RUNNING", done = 400, generated = 300, existing = 100))!!.describe())
    }
}
