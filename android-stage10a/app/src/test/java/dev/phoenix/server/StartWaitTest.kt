package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.RuntimeHost
import org.phoenix.core.ServerController
import org.phoenix.core.ServerController.State
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** What Phoenix does while it waits for a start or restart to come online ([StartWait]). */
class StartWaitTest {
    private fun next(state: State, alive: Boolean = true, mayFinish: Boolean = false, pastDeadline: Boolean = false, detail: String = "") =
        StartWait.next(state, detail, alive, mayFinish, pastDeadline)

    @Test fun onlineEndsTheWait() = assertEquals(StartWait.Online, next(State.ONLINE))

    @Test fun aStartInProgressKeepsWaiting() = assertEquals(StartWait.Waiting, next(State.STARTING))

    @Test fun theUsersStopEndsTheWaitAsStopped() {
        assertEquals(StartWait.Stopped, next(State.STOPPING))
        assertEquals(StartWait.Stopped, next(State.STOPPED, alive = false))
    }

    @Test fun aReadinessTimeoutWithJavaStillRunningKeepsWaitingAsLate() =
        assertEquals(StartWait.Late, next(State.ERROR, mayFinish = true, detail = "Minecraft readiness timed out; stop or inspect console"))

    @Test fun aFailedStartEndsTheWaitWithTheControllersReason() =
        assertEquals(StartWait.Failed("Minecraft failed to start: x. See the console for the cause."),
            next(State.ERROR, detail = "Minecraft failed to start: x. See the console for the cause."))

    @Test fun javaGoneWhileStartingIsAFailure() =
        assertTrue(next(State.STARTING, alive = false) is StartWait.Failed)

    @Test fun noJavaBetweenTheOldAndTheNewProcessOfARestartIsNotAFailure() =
        assertEquals(StartWait.Waiting, next(State.RESTARTING, alive = false))

    @Test fun passingPhoenixsOwnDeadlineWithJavaRunningKeepsWaitingAsLate() =
        assertEquals(StartWait.Late, next(State.STARTING, pastDeadline = true))

    private class FakeHost : RuntimeHost {
        @Volatile var alive = false
        lateinit var listener: RuntimeHost.Listener
        override fun start(listener: RuntimeHost.Listener) { this.listener = listener; alive = true }
        override fun command(command: String) {}
        override fun forceStop() { alive = false }
        override fun isAlive() = alive
        fun line(text: String) = listener.onLine(text)
    }

    /** The same loop as PhoenixBackend.awaitOnline, on the real controller: a start that outlasts the timeout still ends online. */
    @Test fun aStartThatFinishesAfterTheReadinessTimeoutEndsTheWaitOnline() {
        val host = FakeHost()
        ServerController(host, BooleanSupplier { true }, Duration.ofMillis(400), Duration.ofSeconds(5)).use { controller ->
            controller.installed().get(); controller.start().get()
            val seen = mutableListOf<StartWait>()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var doneSent = false
            while (true) {
                val s = controller.snapshot()
                val step = StartWait.next(s.state(), s.detail(), host.isAlive, controller.startMayStillFinish(), false)
                if (seen.lastOrNull() != step) seen += step
                if (step != StartWait.Waiting && step != StartWait.Late) break
                if (step == StartWait.Late && !doneSent) { host.line("[10:06:01] [Server thread/INFO]: Done (361.0s)! For help, type \"help\""); doneSent = true }
                check(System.nanoTime() < end) { "timed out: $seen" }
                Thread.sleep(20)
            }
            assertEquals(listOf(StartWait.Waiting, StartWait.Late, StartWait.Online), seen)
        }
    }
}
