package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mod's "[PhoenixSpawnPrep]" console lines (docs/PHOENIX_SPAWN_PREP.md) read into [SpawnPrepStatus]. */
class SpawnPrepLogTest {
    private fun line(text: String) = "[10:00:00] [Server thread/INFO]: [PhoenixSpawnPrep] $text"

    private val start = line("START reason=new-world dimension=minecraft:overworld centerChunk=0,0 radiusBlocks=256 chunks=1089 maxInFlight=16")
    // Field order exactly as in the contract.
    private val progress = line("PROGRESS done=544 total=1089 pct=49.9 generated=500 existing=44 failed=1 cps=37.2 etaS=15 inFlight=16 heapMB=412/1536")

    @Test fun startReadsTheRunShape() {
        val s = SpawnPrepLog.update(SpawnPrepStatus(), start)!!
        assertEquals(SpawnPrepState.STARTING, s.state)
        assertEquals("new-world", s.reason)
        assertEquals("minecraft:overworld", s.dimension)
        assertEquals("0,0", s.centerChunk)
        assertEquals(256, s.radiusBlocks)
        assertEquals(1089, s.total)
        assertEquals(0, s.done)
        assertEquals(0.0, s.percent, 0.0)
        assertFalse(s.cancelling)
    }

    @Test fun progressReadsEveryField() {
        val s = SpawnPrepLog.update(SpawnPrepLog.update(SpawnPrepStatus(), start)!!, progress)!!
        assertEquals(SpawnPrepState.RUNNING, s.state)
        assertEquals(544, s.done)
        assertEquals(1089, s.total)
        assertEquals(49.9, s.percent, 0.0001)
        assertEquals(500, s.generated)
        assertEquals(44, s.existing)
        assertEquals(1, s.failed)
        assertEquals(37.2, s.chunksPerSecond!!, 0.0001)
        assertEquals(15, s.etaSeconds)
        assertEquals(16, s.inFlight)
        assertEquals(412, s.heapUsedMb)
        assertEquals(1536, s.heapMaxMb)
        // The run shape from START is kept.
        assertEquals("minecraft:overworld", s.dimension)
        assertEquals(256, s.radiusBlocks)
    }

    @Test fun progressWorksWithoutAStartLineAndInAnyFieldOrder() {
        val s = SpawnPrepLog.update(SpawnPrepStatus(), line("PROGRESS etaS=9 pct=10.0 total=100 done=10"))!!
        assertEquals(SpawnPrepState.RUNNING, s.state)
        assertEquals(10, s.done); assertEquals(100, s.total); assertEquals(10.0, s.percent, 0.0); assertEquals(9, s.etaSeconds)
        assertNull(s.inFlight); assertNull(s.heapUsedMb)
    }

    @Test fun progressIsHiddenFromTheConsoleButTheOtherLinesAreNot() {
        assertTrue(SpawnPrepLog.isProgress(progress))
        assertFalse(SpawnPrepLog.isProgress(start))
        assertFalse(SpawnPrepLog.isProgress(line("DONE state=COMPLETE done=1 total=1 generated=1 existing=0 failed=0 seconds=1.0")))
        assertFalse(SpawnPrepLog.isProgress("[10:00:00] [Server thread/INFO]: <Steve> PROGRESS done=1"))
    }

    @Test fun doneKeepsTheResult() {
        val running = SpawnPrepLog.update(SpawnPrepStatus(cancelling = true), progress)!!
        val done = SpawnPrepLog.update(running, line("DONE state=CANCELLED done=544 total=1089 generated=500 existing=44 failed=1 seconds=14.6"))!!
        assertEquals(SpawnPrepState.DONE, done.state)
        assertEquals("CANCELLED", done.result)
        assertEquals(544, done.done); assertEquals(1089, done.total)
        assertEquals(500, done.generated); assertEquals(44, done.existing); assertEquals(1, done.failed)
        assertEquals(14.6, done.seconds!!, 0.0001)
        assertFalse("Cancelling ends when DONE arrives", done.cancelling)
        assertNull(done.etaSeconds)
        for (result in listOf("COMPLETE", "PARTIAL")) {
            assertEquals(result, SpawnPrepLog.update(running, line("DONE state=$result done=1 total=1 generated=1 existing=0 failed=0 seconds=1.0"))!!.result)
        }
    }

    @Test fun readyAndSkipAndFailed() {
        val ready = SpawnPrepLog.update(SpawnPrepStatus(), line("READY state=COMPLETE (the server finishes starting now)"))!!
        assertEquals(SpawnPrepState.READY, ready.state)
        assertEquals("COMPLETE", ready.result)

        val skip = SpawnPrepLog.update(SpawnPrepStatus(), line("SKIP reason=already-prepared radiusBlocks=256"))!!
        assertEquals(SpawnPrepState.SKIPPED, skip.state)
        assertEquals("already-prepared", skip.reason)
        assertEquals(256, skip.radiusBlocks)
        for (reason in listOf("cancelled-by-user", "existing-world", "disabled")) {
            assertEquals(reason, SpawnPrepLog.update(SpawnPrepStatus(), line("SKIP reason=$reason radiusBlocks=0"))!!.reason)
        }

        val failed = SpawnPrepLog.update(SpawnPrepStatus(), line("FAILED: spawn preparation stopped with an error; the server starts anyway"))!!
        assertEquals(SpawnPrepState.FAILED, failed.state)
        assertEquals("spawn preparation stopped with an error; the server starts anyway", failed.message)
    }

    @Test fun otherLinesAreIgnored() {
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), "[10:00:00] [Server thread/INFO]: Done (3.2s)! For help, type \"help\""))
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), "[10:00:00] [Server thread/INFO]: <Steve> [PhoenixSpawnPrep] is a cool mod"))
        // The mod's other messages (config warnings, marker-write problems) are not status changes.
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), line("config: radiusBlocks=9999 is too large; using 4096")))
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), line("removed a cancel file left from an earlier run")))
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), line("")))
        assertNull(SpawnPrepLog.update(SpawnPrepStatus(), "[PhoenixSpawnPrep]"))
    }

    @Test fun aPlayersChatOrSayCannotFakeAStatusLine() {
        val done = "[PhoenixSpawnPrep] DONE state=COMPLETE done=1 total=1 generated=1 existing=0 failed=0 seconds=1.0"
        for (spoof in listOf(
            "[10:00:00] [Server thread/INFO]: <Steve> $done",
            "[10:00:00] [Server thread/INFO]: [Not Secure] <Steve> $done",
            "[10:00:00] [Server thread/INFO]: [Server] $done",
            "[10:00:00] [Server thread/INFO]: Steve issued server command: /say $done",
            "[10:00:00] [Server thread/INFO]: hello ]: $done",
        )) {
            assertNull(spoof, SpawnPrepLog.update(SpawnPrepStatus(state = SpawnPrepState.RUNNING), spoof))
            assertFalse(spoof, SpawnPrepLog.isProgress(spoof))
        }
        // The two log layouts a mod's own logger can produce are accepted.
        assertEquals(SpawnPrepState.DONE, SpawnPrepLog.update(SpawnPrepStatus(), "[10:00:00] [Server thread/INFO] (PhoenixSpawnPrep) $done")!!.state)
        assertEquals(SpawnPrepState.DONE, SpawnPrepLog.update(SpawnPrepStatus(), "[10:00:00] [Server thread/INFO]: $done")!!.state)
    }

    @Test fun malformedLinesNeverThrowOrCorruptTheStatus() {
        val before = SpawnPrepLog.update(SpawnPrepLog.update(SpawnPrepStatus(), start)!!, progress)!!
        val broken = listOf(
            "PROGRESS", "PROGRESS done= total=", "PROGRESS done=abc total=xyz pct=n/a etaS=soon heapMB=lots",
            "PROGRESS done=-5 total=10 pct=NaN", "PROGRESS done=99999999999999999999 total=1", "PROGRESS heapMB=1/", "PROGRESS =3 == junk",
            "PROGRESS pct=250 total=10 done=10", "PROGRESS etaS=-4",
            "START", "START chunks=many radiusBlocks=big", "DONE", "DONE state=EXPLODED", "READY", "SKIP", "SKIP reason=", "FAILED", "FAILED:",
        )
        for (text in broken) {
            val result = SpawnPrepLog.update(before, line(text))
            if (result != null) {
                assertTrue("$text: counters stay sane", result.done >= 0 && result.total >= 0 && result.percent in 0.0..100.0)
                assertTrue("$text: eta never negative", (result.etaSeconds ?: 0) >= 0)
            }
        }
        // A garbled number keeps the earlier value instead of resetting it.
        val kept = SpawnPrepLog.update(before, line("PROGRESS done=abc total=1089 pct=oops"))!!
        assertEquals(544, kept.done)
        assertEquals(49.9, kept.percent, 0.0001)
        // An unknown DONE state is not shown as a result.
        assertNull(SpawnPrepLog.update(before, line("DONE state=EXPLODED done=1 total=1 generated=1 existing=0 failed=0 seconds=1.0"))?.result)
    }

    @Test fun aNewStartForTheNextDimensionResetsTheCounters() {
        val first = SpawnPrepLog.update(SpawnPrepLog.update(SpawnPrepStatus(), start)!!, progress)!!
        val second = SpawnPrepLog.update(first, line("START reason=resume dimension=minecraft:the_nether centerChunk=0,0 radiusBlocks=128 chunks=289 maxInFlight=16"))!!
        assertEquals("minecraft:the_nether", second.dimension)
        assertEquals(0, second.done); assertEquals(289, second.total); assertEquals(0, second.generated)
        assertNull(second.etaSeconds)
    }

    @Test fun lineWithoutLogPrefixStillParses() {
        assertEquals(SpawnPrepState.STARTING,
            SpawnPrepLog.update(SpawnPrepStatus(), "[PhoenixSpawnPrep] START reason=resume dimension=minecraft:overworld centerChunk=1,-2 radiusBlocks=64 chunks=81 maxInFlight=8")!!.state)
    }

    // ---- wording

    @Test fun headlineMatchesTheContractWording() {
        val s = SpawnPrepLog.update(SpawnPrepLog.update(SpawnPrepStatus(), start)!!, progress)!!
        assertEquals("Preparing the area around spawn: 50 % (about 15 s left)", s.headline())
        assertEquals("544 / 1,089 chunks", s.chunkLine())
    }

    @Test fun headlineLeavesOutAnUnknownEtaAndUsesMinutesForLongOnes() {
        val running = SpawnPrepStatus(state = SpawnPrepState.RUNNING, done = 10, total = 100, percent = 10.0)
        assertEquals("Preparing the area around spawn: 10 %", running.headline())
        assertEquals("Preparing the area around spawn: 10 % (about 119 s left)", running.copy(etaSeconds = 119).headline())
        assertEquals("Preparing the area around spawn: 10 % (about 2 min left)", running.copy(etaSeconds = 120).headline())
        assertEquals("Preparing the area around spawn: 10 % (about 26 min left)", running.copy(etaSeconds = 1530).headline())
        assertEquals("Preparing the area around spawn: 0 %", SpawnPrepStatus(state = SpawnPrepState.STARTING, total = 100).headline())
    }

    @Test fun cancellingShowsUntilDoneArrives() {
        val running = SpawnPrepStatus(state = SpawnPrepState.RUNNING, done = 10, total = 100, percent = 10.0, cancelling = true)
        assertEquals("Cancelling…", running.headline())
        assertTrue(running.active)
        val done = SpawnPrepLog.update(running, line("DONE state=CANCELLED done=10 total=100 generated=10 existing=0 failed=0 seconds=3.0"))!!
        assertFalse(done.active)
        assertNull(done.headline())
    }

    @Test fun onlyStartingAndRunningCountAsActive() {
        assertTrue(SpawnPrepStatus(state = SpawnPrepState.STARTING).active)
        assertTrue(SpawnPrepStatus(state = SpawnPrepState.RUNNING).active)
        for (state in listOf(SpawnPrepState.IDLE, SpawnPrepState.DONE, SpawnPrepState.READY, SpawnPrepState.SKIPPED, SpawnPrepState.FAILED))
            assertFalse("$state", SpawnPrepStatus(state = state).active)
        assertNull(SpawnPrepStatus().headline())
    }
}
