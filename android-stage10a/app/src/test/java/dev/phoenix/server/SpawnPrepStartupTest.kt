package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.RuntimeHost
import org.phoenix.core.ServerController
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/**
 * "Joinable" is Minecraft's own "Done (…)!" line and nothing the mod prints. These run the real [ServerController] that
 * decides ONLINE, fed with the mod's real lines.
 */
class SpawnPrepStartupTest {
    private class FakeHost : RuntimeHost {
        @Volatile var alive = false
        lateinit var listener: RuntimeHost.Listener
        override fun start(listener: RuntimeHost.Listener) { this.listener = listener; alive = true }
        override fun command(command: String) {}
        override fun forceStop() { alive = false }
        override fun isAlive() = alive
        fun line(text: String) = listener.onLine(text)
    }

    private fun until(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) { check(System.nanoTime() < end) { "timed out" }; Thread.sleep(10) }
    }

    private val contractLines = listOf(
        "[10:00:01] [Server thread/INFO]: [PhoenixSpawnPrep] START reason=new-world dimension=minecraft:overworld centerChunk=0,0 radiusBlocks=256 chunks=1089 maxInFlight=16",
        "[10:00:03] [PhoenixSpawnPrep/INFO]: [PhoenixSpawnPrep] PROGRESS done=544 total=1089 pct=49.9 generated=500 existing=44 failed=1 cps=37.2 etaS=15 inFlight=16 heapMB=412/1536",
        "[10:00:20] [Server thread/INFO]: [PhoenixSpawnPrep] DONE state=COMPLETE done=1089 total=1089 generated=1089 existing=0 failed=0 seconds=29.0",
        "[10:00:20] [Server thread/INFO]: [PhoenixSpawnPrep] READY state=COMPLETE (the server finishes starting now)",
        "[10:00:20] [Server thread/INFO]: [PhoenixSpawnPrep] SKIP reason=already-prepared radiusBlocks=256",
        "[10:00:20] [Server thread/ERROR]: [PhoenixSpawnPrep] FAILED: spawn preparation stopped with an error; the server starts anyway",
    )

    @Test fun preparationLinesNeverMakeTheServerOnlineOnlyMinecraftsDoneLineDoes() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofSeconds(30), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            var status = SpawnPrepStatus()
            for (text in contractLines) {
                host.line(text)
                SpawnPrepLog.update(status, text)?.let { status = it }
            }
            Thread.sleep(900) // the controller polls readiness every 250 ms
            assertEquals(ServerController.State.STARTING, controller.snapshot().state())
            host.line("[10:00:21] [Server thread/INFO]: Done (21.5s)! For help, type \"help\"")
            until { controller.snapshot().state() == ServerController.State.ONLINE }
        }
    }

    @Test fun progressKeepsALongPreparationFromTimingOutButSilenceStillDoes() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(600), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            // 2 s of "progress every 100 ms" is more than three startup timeouts.
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < end) { controller.noteStartupProgress(); Thread.sleep(100) }
            assertEquals(ServerController.State.STARTING, controller.snapshot().state())
            // Then the server goes quiet: the normal timeout applies from the last progress.
            until(4000) { controller.snapshot().state() == ServerController.State.ERROR }
            assertTrue(controller.snapshot().detail().contains("timed out"))
        }
    }

    @Test fun aStartThatFinishesAfterTheReadinessTimeoutStillGoesOnline() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            // A preparation that logs nothing (or a slow world load) outlasts the startup timeout ...
            until(3000) { controller.snapshot().state() == ServerController.State.ERROR }
            assertTrue(controller.snapshot().detail().contains("timed out"))
            // ... and Minecraft then finishes starting: the server is online after all.
            host.line("[10:06:01] [Server thread/INFO]: Done (361.0s)! For help, type \"help\"")
            until { controller.snapshot().state() == ServerController.State.ONLINE }
            assertTrue(controller.isActive)
        }
    }

    @Test fun aTimedOutStartWhoseJavaEndedDoesNotComeBackOnline() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            until(3000) { controller.snapshot().state() == ServerController.State.ERROR }
            host.alive = false
            host.line("[10:06:01] [Server thread/INFO]: Done (361.0s)! For help, type \"help\"")
            Thread.sleep(900) // the controller polls readiness every 250 ms
            assertEquals(ServerController.State.ERROR, controller.snapshot().state())
        }
    }

    @Test fun aTimedOutStartWithJavaRunningMayStillFinish() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            assertTrue(!controller.startMayStillFinish())
            until(3000) { controller.snapshot().state() == ServerController.State.ERROR }
            assertTrue(controller.startMayStillFinish())
            host.alive = false
            assertTrue(!controller.startMayStillFinish())
        }
    }

    @Test fun aFailedStartLineAfterTheReadinessTimeoutIsStillAFailedStart() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            until(3000) { controller.snapshot().state() == ServerController.State.ERROR }
            host.line("[10:06:01] [Server thread/ERROR]: Failed to load world data. World files may be corrupted")
            until { controller.snapshot().detail().startsWith("Minecraft failed to start") }
            assertTrue(!controller.startMayStillFinish())
            // A failed start never comes back online.
            host.line("[10:06:02] [Server thread/INFO]: Done (361.0s)! For help, type \"help\"")
            Thread.sleep(900) // the controller polls readiness every 250 ms
            assertEquals(ServerController.State.ERROR, controller.snapshot().state())
        }
    }

    @Test fun withoutProgressTheStartupTimeoutIsUnchanged() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            until(3000) { controller.snapshot().state() == ServerController.State.ERROR }
        }
    }

    @Test fun progressAfterTheServerIsOnlineChangesNothing() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofSeconds(30), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            host.line("[10:00:21] [Server thread/INFO]: Done (1.0s)! For help, type \"help\"")
            until { controller.snapshot().state() == ServerController.State.ONLINE }
            controller.noteStartupProgress()
            assertEquals(ServerController.State.ONLINE, controller.snapshot().state())
            assertTrue(controller.isActive)
        }
    }
}
