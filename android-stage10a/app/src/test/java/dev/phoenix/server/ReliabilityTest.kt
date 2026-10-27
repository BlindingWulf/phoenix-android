package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    @Test fun atMostThreeAutomaticRestartsInFifteenMinutes() {
        val loop = CrashLoop(3, 15 * 60_000L)
        assertEquals(1, loop.allow(0))
        assertEquals(2, loop.allow(60_000))
        assertEquals(3, loop.allow(120_000))
        assertNull("a fourth crash within 15 minutes is left to the owner", loop.allow(180_000))
        // The first exit leaves the window after 15 minutes, so one restart is allowed again.
        assertEquals(3, loop.allow(15 * 60_000L + 1))
    }

    @Test fun aKillByAndroidIsToldApartFromACrash() {
        assertTrue(ServerExit.killed(137))
        assertFalse(ServerExit.killed(1))
        assertEquals("Android ended it", ServerExit.describe(137))
        assertEquals("it crashed with exit code 1", ServerExit.describe(1))
    }

    @Test fun chunkAndRegionErrorsAreFoundButOrdinaryLinesAreNot() {
        val lines = listOf(
            "[12:04:56] [Server thread/INFO]: Preparing level \"world\"",
            "[12:05:01] [Worker-Main-3/ERROR]: Failed to read chunk [3, -7]",
            "[12:05:01] [IO-Worker-1/WARN]: Region file world/region/r.0.0.mca has truncated header: 4096",
            "[12:05:02] [Server thread/WARN]: Chunk file at [12, 4] is in the wrong location; relocating. (Expected [1, 2], got [12, 4])",
            "\tat net.minecraft.world.level.chunk.storage.RegionFile.getChunkDataInputStream(RegionFile.java:120)",
            "[12:05:03] [Server thread/INFO]: Failed to read chunk text from a chat message",
            "[12:05:04] [Server thread/WARN]: Can't keep up! Is the server overloaded?",
            "[12:05:05] [Server thread/ERROR]: Failed to save chunk 5,6",
        )
        val found = ChunkErrors.find(lines)
        assertEquals(4, found.size)
        assertTrue(found.first().contains("Failed to read chunk [3, -7]"))
        assertTrue(ChunkErrors.find(listOf("[x] [Server thread/INFO]: Saving chunks for level 'ServerLevel[world]'/minecraft:overworld")).isEmpty())
    }

    /** Phone B 2026-10-20 (abridged, exact lines): a Phoenium crash, then chunk loads the stop cancelled, and no final save. */
    private val phoneBCrash = listOf(
        "[04:39:50] [Server thread/INFO]: PHOENIUM_TICK_READINESS budgetMs=0 level=minecraft:overworld ticksPastBudget=3003 pendingNow=1 maxPending=85 candidates=1",
        "[04:39:50] [Server thread/ERROR]: Encountered an unexpected exception",
        "net.minecraft.ReportedException: Exception ticking world",
        "\tat knot//net.minecraft.server.MinecraftServer.tickChildren(MinecraftServer.java:1140)",
        "Caused by: dev.phoenium.chunk.ChunkProtocolException: unfinished synchronous chunk dependency inside an owner transaction",
        "\tat knot//dev.phoenium.chunk.ChunkSynchronousAccessGuard.beforeWait(ChunkSynchronousAccessGuard.java:14)",
        "[04:39:50] [Server thread/ERROR]: This crash report has been saved to: /data/data/dev.phoenix.server.rccheck/files/servers/baseline/crash-reports/crash-2026-10-20_04.39.50-server.txt",
        "[04:39:50] [Server thread/ERROR]: Error upgrading chunk [1, -1] to \"minecraft:structure_starts\"",
        "java.util.concurrent.CompletionException: dev.phoenium.chunk.C2meGenerationControlException: C2ME generation branch ended with SHUTDOWN",
        "[04:39:50] [Server thread/ERROR]: Failed to load chunk 1,-1",
        "java.util.concurrent.CompletionException: dev.phoenium.chunk.C2meGenerationControlException: C2ME generation branch ended with SHUTDOWN",
        "[04:39:52] [Server thread/INFO]: Stopping server",
        "[04:39:52] [Server thread/INFO]: Saving players",
        "[04:39:52] [Server thread/INFO]: Saving worlds",
        "[04:39:52] [Server thread/ERROR]: Exception stopping the server",
        "dev.phoenium.chunk.ChunkProtocolException: unfinished synchronous chunk dependency inside an owner transaction",
    )

    @Test fun chunkLoadsCancelledByACrashOrStopAreNotWorldDamage() {
        assertEquals(emptyList<String>(), ChunkErrors.find(phoneBCrash))
        assertEquals("a save failing while it stops still counts", 1,
            ChunkErrors.find(phoneBCrash + "[04:39:52] [Server thread/ERROR]: Failed to save chunk 5,6").size)
        assertEquals("a load failing before the crash still counts", 1,
            ChunkErrors.find(listOf("[04:39:49] [Server thread/ERROR]: Failed to load chunk [3, -7]") + phoneBCrash).size)
    }

    @Test fun aMinecraftCrashIsFoundWithItsCauseReportAndFinalSave() {
        val crash = ServerCrashes.find(phoneBCrash)!!
        assertEquals("Exception ticking world (ChunkProtocolException: unfinished synchronous chunk dependency inside an owner transaction)", crash.summary)
        assertEquals("crash-2026-10-20_04.39.50-server.txt", crash.report)
        assertFalse("the stop after this crash saved nothing", crash.savedAtStop)
        assertTrue(ServerCrashes.find(phoneBCrash + "[04:39:53] [Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved")!!.savedAtStop)
        assertNull("a clean stop is no crash", ServerCrashes.find(listOf("[x] [Server thread/INFO]: Stopping server",
            "[x] [Server thread/INFO]: ThreadedAnvilChunkStorage: All dimensions are saved")))
    }

    @Test fun runningOutOfMemoryAndFatalJavaErrorsAreCrashesToo() {
        val oom = ServerCrashes.find(listOf("[10:00:00] [Server thread/ERROR]: Encountered an unexpected exception", "java.lang.OutOfMemoryError: Java heap space"))!!
        assertEquals("OutOfMemoryError: Java heap space", oom.summary)
        assertNull(oom.report)
        assertEquals("OutOfMemoryError: Java heap space",
            ServerCrashes.find(listOf("Exception in thread \"C2ME Storage #1\" java.lang.OutOfMemoryError: Java heap space"))!!.summary)
        val fatal = ServerCrashes.find(listOf("#", "# A fatal error has been detected by the Java Runtime Environment:", "#",
            "#  SIGSEGV (0xb) at pc=0x0000007f8a1c2d40, pid=4242, tid=4300", "# An error report file with more information is saved as:",
            "# /data/data/dev.phoenix.server/files/servers/baseline/hs_err_pid4242.log"))!!
        assertEquals("Java itself crashed (SIGSEGV)", fatal.summary)
        assertEquals("hs_err_pid4242.log", fatal.report)
    }

    @Test fun aBackupIsMadeWhenTheWorldChangedOrTheNewestIsADayOld() {
        val day = 24 * 3_600_000L
        assertTrue("no backup yet", AutoBackups.due(null, 1_000, 2_000))
        assertFalse("nothing saved since an hour-old backup", AutoBackups.due(10_000, 9_000, 10_000 + 3_600_000))
        assertTrue("the world was saved after the backup", AutoBackups.due(10_000, 11_000, 20_000))
        assertTrue("the newest backup is a day old", AutoBackups.due(10_000, 9_000, 10_000 + day))
    }

    @Test fun onlyTheOldestAutomaticBackupsBeyondTheLimitAreDeleted() {
        val (gone, kept) = AutoBackups.rotate(listOf("world-1.zip", "world-2.zip", "world-3.zip", "world-4.zip"), 3)
        assertEquals(listOf("world-1.zip"), gone)
        assertEquals(listOf("world-2.zip", "world-3.zip", "world-4.zip"), kept)
        assertEquals(emptyList<String>(), AutoBackups.rotate(listOf("world-1.zip"), 3).first)
        assertEquals(listOf("world-1.zip"), AutoBackups.rotate(listOf("world-1.zip", "world-2.zip"), 0).first)
        assertFalse(AutoBackups.enoughSpace(400L * 1_048_576, 800L * 1_048_576))
        assertTrue(AutoBackups.enoughSpace(140L * 1_048_576, 40_000L * 1_048_576))
    }

    @Test fun onlyPhoenixExecutablesNotStartedByTheRunningAppAreLeftovers() {
        val native = "/data/app/~~x==/dev.phoenix.server.stage10a-y==/lib/arm64/"
        assertTrue(Leftovers.isLeftover(native + "libphoenixjvm.so", 1, 4242))
        assertTrue(Leftovers.isLeftover(native + "libplayitagent.so", 1, 4242))
        assertFalse("the running app's own agent", Leftovers.isLeftover(native + "libplayitagent.so", 4242, 4242))
        assertFalse(Leftovers.isLeftover("/system/bin/sh", 1, 4242))
        assertFalse(Leftovers.isLeftover("dev.phoenix.server.stage10a", 1, 4242))
        assertTrue(Leftovers.isServer(native + "libphoenixjvm.so"))
    }
}
