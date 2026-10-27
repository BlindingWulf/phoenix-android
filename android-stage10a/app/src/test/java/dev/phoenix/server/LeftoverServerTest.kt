package dev.phoenix.server

import dev.phoenix.server.LeftoverServer.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.ServerController.State

class LeftoverServerTest {
    private val jar = "/data/user/0/dev.phoenix/files/servers/baseline/server.jar"
    private val otherJar = "/data/user/0/dev.phoenix/files/servers/vanilla/server.jar"
    private val argv = listOf(
        "/data/app/lib/arm64/libphoenixjvm.so", "native", "/home", "-Xmx2048m", "--", "org.phoenix.runtime.JarMain", jar, "nogui",
    )
    private val done = LeftoverServer.LogFacts(done = true, stopping = false)

    private fun classify(
        candidate: LeftoverServer.Candidate? = LeftoverServer.Candidate(pid = 11016, recordedStartTicks = 4242L),
        process: LeftoverServer.ProcessFacts? = LeftoverServer.ProcessFacts(alive = true, argv = argv, startTicks = 4242L),
        log: LeftoverServer.LogFacts = done,
        answering: Boolean = true,
    ): Outcome = LeftoverServer.classify(candidate, process, log, answering, jar)

    @Test fun noRecordedOrScannedProcessMeansNothingToAdopt() {
        assertEquals(Outcome.NONE, classify(candidate = null))
    }

    @Test fun aDeadProcessIsNotAServer() {
        assertEquals(Outcome.NONE, classify(process = LeftoverServer.ProcessFacts(alive = false, argv = argv, startTicks = 4242L)))
        assertEquals(Outcome.NONE, classify(process = null))
    }

    @Test fun doneAndAnsweringIsRunning() {
        assertEquals(Outcome.RUNNING, classify())
    }

    @Test fun notYetDoneIsStarting() {
        assertEquals(Outcome.STARTING, classify(log = LeftoverServer.LogFacts(done = false, stopping = false)))
    }

    @Test fun doneButNotAnsweringYetIsStarting() {
        assertEquals(Outcome.STARTING, classify(answering = false))
    }

    @Test fun aStopLoggedAfterDoneIsStopping() {
        assertEquals(Outcome.STOPPING, classify(log = LeftoverServer.LogFacts(done = true, stopping = true)))
    }

    @Test fun aReusedPidIsStaleWhateverTheLogSays() {
        val reused = LeftoverServer.ProcessFacts(alive = true, argv = listOf("/system/bin/sh", "-c", "sleep 99"), startTicks = 4242L)
        assertEquals(Outcome.STALE, classify(process = reused, log = LeftoverServer.LogFacts(done = true, stopping = true)))
    }

    @Test fun aRestartedStartTimeIsStale() {
        assertEquals(Outcome.STALE, classify(process = LeftoverServer.ProcessFacts(alive = true, argv = argv, startTicks = 9999L)))
    }

    @Test fun aServerOfAnotherServerDirectoryIsStale() {
        val other = argv.map { if (it == jar) otherJar else it }
        assertEquals(Outcome.STALE, classify(process = LeftoverServer.ProcessFacts(alive = true, argv = other, startTicks = 4242L)))
    }

    @Test fun aJavaProcessWithOurJarButNotOurLauncherIsStale() {
        val plainJava = listOf("/system/bin/java") + argv.drop(1)
        assertEquals(Outcome.STALE, classify(process = LeftoverServer.ProcessFacts(alive = true, argv = plainJava, startTicks = 4242L)))
    }

    @Test fun aScannedProcessWithoutARecordedStartTimeIsCheckedByCommandLineOnly() {
        assertEquals(Outcome.RUNNING, classify(
            candidate = LeftoverServer.Candidate(pid = 11016, recordedStartTicks = null),
            process = LeftoverServer.ProcessFacts(alive = true, argv = argv, startTicks = 9999L),
        ))
    }

    @Test fun ourServerIsRecognisedByLauncherAndJarOnly() {
        assertTrue(LeftoverServer.isOurServer(argv, jar))
        assertFalse(LeftoverServer.isOurServer(argv, otherJar))
        assertFalse(LeftoverServer.isOurServer(listOf("/system/bin/sh", jar), jar))
        assertFalse(LeftoverServer.isOurServer(emptyList(), jar))
    }

    @Test fun logCountsOnlyTheLatestStart() {
        val lines = listOf(
            "[00:00:01] [Server thread/INFO]: Starting minecraft server version 26.2",
            "[00:00:09] [Server thread/INFO]: Done (8.0s)! For help, type \"help\"",
            "[00:00:10] [Server thread/INFO]: Stopping server",
            "[01:00:00] [Server thread/INFO]: Starting minecraft server version 26.2",
            "[01:00:05] [Server thread/INFO]: Done (8.18s)! Run /geyser help for help!",
        )
        assertEquals(LeftoverServer.LogFacts(done = false, stopping = false), LeftoverServer.scanLog(lines))
    }

    @Test fun logReadsDoneThenStopping() {
        val lines = listOf("Starting minecraft server version 26.2", "Done (8.0s)! For help, type \"help\"", "Stopping server")
        assertEquals(LeftoverServer.LogFacts(done = true, stopping = true), LeftoverServer.scanLog(lines))
    }

    @Test fun aStopBeforeDoneDoesNotCount() {
        val lines = listOf("Starting minecraft server version 26.2", "Stopping server", "Done (8.0s)! For help, type \"help\"")
        assertEquals(LeftoverServer.LogFacts(done = true, stopping = false), LeftoverServer.scanLog(lines))
    }

    @Test fun statReadsStateParentAndStartTimeAfterTheLastParenthesis() {
        // The command name may contain spaces and parentheses; fields start after the last ')'. Field 22 is the start time.
        val stat = "11016 (libphoenixjvm.so (x)) S 1 11016 11016 0 -1 4194304 1000 0 0 0 10 5 0 0 20 0 60 0 4242 123456 789"
        val fields = LeftoverServer.parseStat(stat)!!
        assertEquals('S', fields.state)
        assertEquals(1, fields.ppid)
        assertEquals(4242L, fields.startTicks)
        assertNull(LeftoverServer.parseStat("garbage"))
    }

    @Test fun commandLineIsReadArgumentByArgument() {
        assertEquals(listOf("libphoenixjvm.so", "native"), LeftoverServer.parseCmdline("libphoenixjvm.so\u0000native\u0000"))
    }

    @Test fun uptimeComesFromTheProcessStartTimeAndSystemUptime() {
        // 100 clock ticks per second: started at tick 4242 (42.42 s), system up for 100.0 s, so 57.58 s of uptime.
        assertEquals(57_580L, LeftoverServer.uptimeMs(startTicks = 4242L, systemUptimeSeconds = 100.0))
    }

    @Test fun startIsNotOfferedWhileAJavaProcessIsAlive() {
        assertFalse(ServerGate.startOffered(State.ONLINE, processAlive = true, busy = false))
        assertFalse(ServerGate.startOffered(State.STARTING, processAlive = true, busy = false))
        assertFalse(ServerGate.startOffered(State.STOPPING, processAlive = true, busy = false))
        assertFalse(ServerGate.startOffered(State.READY, processAlive = true, busy = false))
        assertFalse(ServerGate.startOffered(State.ERROR, processAlive = true, busy = false))
    }

    @Test fun startIsOfferedOnlyWhenStoppedAndIdle() {
        assertTrue(ServerGate.startOffered(State.READY, processAlive = false, busy = false))
        assertTrue(ServerGate.startOffered(State.STOPPED, processAlive = false, busy = false))
        assertTrue(ServerGate.startOffered(State.ERROR, processAlive = false, busy = false))
        assertFalse(ServerGate.startOffered(State.READY, processAlive = false, busy = true))
        assertFalse(ServerGate.startOffered(State.ONLINE, processAlive = false, busy = false))
    }

    @Test fun stopIsOfferedWhileOnlineStartingOrAliveAfterError() {
        assertTrue(ServerGate.stopOffered(State.ONLINE, processAlive = true))
        assertTrue(ServerGate.stopOffered(State.STARTING, processAlive = true))
        assertTrue(ServerGate.stopOffered(State.ERROR, processAlive = true))
        assertFalse(ServerGate.stopOffered(State.ERROR, processAlive = false))
        assertFalse(ServerGate.stopOffered(State.STOPPED, processAlive = false))
        assertTrue(ServerGate.stopOffered(State.RESTARTING, processAlive = true))
    }

    @Test fun unknownExitCodeIsSaidPlainly() {
        assertEquals("its exit code is not known", ServerExit.describe(-1))
    }
}
