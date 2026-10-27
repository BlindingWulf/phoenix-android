package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoeniumTuningTest {
    private val engineEntries = listOf("META-INF/MANIFEST.MF", "fabric.mod.json", "dev/phoenium/engine/EngineConfig.class")

    @Test
    fun accelerationIsOffByDefault() {
        assertFalse(PhoeniumTuning.DEFAULT_ACCELERATION)
    }

    @Test
    fun standardModeLeavesTheLaunchUnchanged() {
        assertEquals(emptyList<String>(), PhoeniumTuning.flags(false, true, 2))
    }

    @Test
    fun accelerationEmitsExactlyTheVerifiedEngineFlags() {
        assertEquals(
            listOf("-Dphoenium.engine.tiles=true", "-Dphoenium.engine.workers=2", "-Dphoenium.engine.schedule=dag"),
            PhoeniumTuning.flags(true, true, 2),
        )
    }

    @Test
    fun theWorkerCountIsTheOnlyNumberThatChanges() {
        assertEquals("-Dphoenium.engine.workers=3", PhoeniumTuning.flags(true, true, 3)[1])
    }

    @Test
    fun absentVanillaOrEnginelessPhoeniumEmitsNothing() {
        assertEquals(emptyList<String>(), PhoeniumTuning.flags(true, false, 2))
        assertTrue(PhoeniumTuning.applies(phoeniumEnabled = true, engineBuild = true, fabric = true))
        assertFalse(PhoeniumTuning.applies(phoeniumEnabled = false, engineBuild = true, fabric = true))
        assertFalse(PhoeniumTuning.applies(phoeniumEnabled = true, engineBuild = false, fabric = true))
        assertFalse(PhoeniumTuning.applies(phoeniumEnabled = true, engineBuild = true, fabric = false))
    }

    @Test
    fun anEngineBuildIsRecognisedByItsEngineClass() {
        assertTrue(PhoeniumTuning.isEngineBuild(engineEntries))
        assertFalse(PhoeniumTuning.isEngineBuild(listOf("fabric.mod.json", "dev/phoenium/live/LiveDiagnostics.class")))
    }

    @Test
    fun workersStayWithinWhatTheCoresAllow() {
        assertEquals(1, PhoeniumTuning.maxWorkers(1))
        assertEquals(1, PhoeniumTuning.maxWorkers(2))
        assertEquals(2, PhoeniumTuning.maxWorkers(4))
        assertEquals(4, PhoeniumTuning.maxWorkers(8))
        assertEquals(4, PhoeniumTuning.maxWorkers(16))
        assertEquals(1, PhoeniumTuning.clampWorkers(0, 8))
        assertEquals(4, PhoeniumTuning.clampWorkers(99, 8))
        assertEquals(2, PhoeniumTuning.clampWorkers(null, 8))
        assertEquals(1, PhoeniumTuning.clampWorkers(null, 2))
    }

    @Test
    fun neverEmitsBigCoreAotOrDiagnosticOrOverrideFlags() {
        for (workers in 1..4) {
            val flags = PhoeniumTuning.flags(true, true, workers)
            assertTrue(flags.all { it.startsWith("-Dphoenium.engine.") })
            assertFalse(flags.any {
                it.contains("bigcore") || it.contains("test.diagnostics") || it.contains("foreignHooks") ||
                    it.contains("allowUnsupportedStack") || it.contains("gameplay") || it.startsWith("-XX:")
            })
        }
    }
}
