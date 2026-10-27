package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RouteSwitcherTest {
    private val ports = ServerPorts(25565, 19132, 24454)
    private val endpoints = RouteEndpoints(Endpoint("java.example", null), Endpoint("udp.example", 6695), Endpoint("udp.example", 7000), Endpoint("tcp.example", 25565))

    private class FakeDriver(val name: String, val log: MutableList<String>) : RouteDriver {
        var running = false
        var failPrepare: String? = null
        var prepareGate: CountDownLatch? = null
        var result: RouteEndpoints? = null
        override fun prepare(ports: ServerPorts, progress: (String) -> Unit): RouteEndpoints {
            log += "prepare $name"; progress("preparing $name")
            prepareGate?.await(5, TimeUnit.SECONDS)
            running = true
            failPrepare?.let { throw RouteException(it) }
            return result!!
        }
        override fun start(ports: ServerPorts) { log += "start $name"; running = true }
        override fun stop() { log += "stop $name"; running = false }
        override fun running() = running
    }

    private class FakeHooks(var selected: PublicRoute?, val log: MutableList<String>) : RouteHooks {
        var verifyFailure: String? = null
        var saveFails = false
        val progressSeen = mutableListOf<RouteSwitch?>()
        override fun selected() = selected
        override fun saveSelected(route: PublicRoute): Boolean { if (saveFails) return false; log += "save ${route.key}"; selected = route; return true }
        override fun verify(route: PublicRoute, endpoints: RouteEndpoints): String? { log += "verify ${route.key}"; return verifyFailure }
        override fun committed(previous: PublicRoute?, route: PublicRoute, endpoints: RouteEndpoints?) { log += "committed ${previous?.key}->${route.key}" }
        override fun progress(change: RouteSwitch?) { progressSeen += change }
    }

    private fun setup(selected: PublicRoute?): Triple<RouteSwitcher, Map<PublicRoute, FakeDriver>, FakeHooks> {
        val log = mutableListOf<String>()
        val drivers = mapOf(PublicRoute.PLAYIT to FakeDriver("playit", log), PublicRoute.NETBIRD_EU to FakeDriver("netbird", log),
            PublicRoute.NETBIRD_REGIONAL to FakeDriver("regional", log))
        drivers.values.forEach { it.result = endpoints }
        val hooks = FakeHooks(selected, log)
        return Triple(RouteSwitcher(drivers, hooks), drivers, hooks)
    }

    @Test fun switchingPreparesAndVerifiesTheNewRouteBeforeSavingThenStopsTheOldOne() {
        val (switcher, drivers, hooks) = setup(PublicRoute.NETBIRD_EU)
        drivers.getValue(PublicRoute.NETBIRD_EU).running = true
        val result = switcher.switchTo(PublicRoute.PLAYIT, ports)
        assertEquals(RouteSwitcher.Result.Switched(PublicRoute.PLAYIT, endpoints), result)
        assertEquals(listOf("prepare playit", "verify playit", "save playit", "stop netbird", "committed netbird-eu->playit"), hooks.log)
        assertFalse(drivers.getValue(PublicRoute.NETBIRD_EU).running)
        assertEquals(1L, switcher.generation())
        assertEquals(null, switcher.inProgress())
        assertTrue(hooks.progressSeen.any { it?.to == PublicRoute.PLAYIT })
        assertEquals(null, hooks.progressSeen.last())
    }

    @Test fun aFailedPrepareKeepsTheCurrentRouteAndStopsOnlyWhatItStarted() {
        val (switcher, drivers, hooks) = setup(PublicRoute.NETBIRD_EU)
        drivers.getValue(PublicRoute.NETBIRD_EU).running = true
        drivers.getValue(PublicRoute.PLAYIT).failPrepare = "Playit refused the Java tunnel."
        val result = switcher.switchTo(PublicRoute.PLAYIT, ports)
        assertEquals(RouteSwitcher.Result.Kept(PublicRoute.NETBIRD_EU, "Playit refused the Java tunnel."), result)
        assertEquals(PublicRoute.NETBIRD_EU, hooks.selected)
        assertTrue(drivers.getValue(PublicRoute.NETBIRD_EU).running)
        assertFalse(drivers.getValue(PublicRoute.PLAYIT).running)
        assertFalse(hooks.log.any { it.startsWith("save") || it.startsWith("committed") })
        assertEquals(0L, switcher.generation())
    }

    @Test fun aFailedReachabilityCheckRollsBackWithoutSaving() {
        val (switcher, drivers, hooks) = setup(PublicRoute.PLAYIT)
        drivers.getValue(PublicRoute.PLAYIT).running = true
        hooks.verifyFailure = "Friends couldn't reach the server through NetBird."
        val result = switcher.switchTo(PublicRoute.NETBIRD_EU, ports)
        assertTrue(result is RouteSwitcher.Result.Kept)
        assertEquals(PublicRoute.PLAYIT, hooks.selected)
        assertTrue(drivers.getValue(PublicRoute.PLAYIT).running)
        assertFalse(drivers.getValue(PublicRoute.NETBIRD_EU).running)
    }

    @Test fun aSaveFailureRollsBackToo() {
        val (switcher, drivers, hooks) = setup(null)
        hooks.saveFails = true
        val result = switcher.switchTo(PublicRoute.PLAYIT, ports)
        assertTrue(result is RouteSwitcher.Result.Kept)
        assertFalse(drivers.getValue(PublicRoute.PLAYIT).running)
        assertEquals(null, hooks.selected)
    }

    @Test fun aTargetThatWasAlreadyRunningIsLeftRunningAfterAFailure() {
        val (switcher, drivers, _) = setup(PublicRoute.LOCAL)
        drivers.getValue(PublicRoute.PLAYIT).running = true
        drivers.getValue(PublicRoute.PLAYIT).failPrepare = "no"
        switcher.switchTo(PublicRoute.PLAYIT, ports)
        assertTrue(drivers.getValue(PublicRoute.PLAYIT).running)
    }

    @Test fun movingBetweenTheTwoNetBirdRoutesNeverStopsTheSharedVpn() {
        val (switcher, drivers, hooks) = setup(PublicRoute.NETBIRD_EU)
        drivers.getValue(PublicRoute.NETBIRD_EU).running = true
        switcher.switchTo(PublicRoute.NETBIRD_REGIONAL, ports)
        assertFalse(hooks.log.contains("stop netbird"))
        drivers.getValue(PublicRoute.NETBIRD_EU).failPrepare = "no"
        hooks.log.clear()
        drivers.getValue(PublicRoute.NETBIRD_REGIONAL).failPrepare = "proxy offline"
        switcher.switchTo(PublicRoute.NETBIRD_EU, ports)
        assertFalse("a failed move within NetBird keeps its VPN", hooks.log.any { it.startsWith("stop") })
    }

    @Test fun turningPublicAccessOffStopsTheRouteButSavesFirst() {
        val (switcher, drivers, hooks) = setup(PublicRoute.PLAYIT)
        drivers.getValue(PublicRoute.PLAYIT).running = true
        assertEquals(RouteSwitcher.Result.Switched(PublicRoute.LOCAL, null), switcher.switchTo(PublicRoute.LOCAL, ports))
        assertEquals(listOf("save local", "stop playit", "committed playit->local"), hooks.log)
    }

    @Test fun reselectingARunningRouteDoesNothing() {
        val (switcher, drivers, hooks) = setup(PublicRoute.PLAYIT)
        drivers.getValue(PublicRoute.PLAYIT).running = true
        assertEquals(RouteSwitcher.Result.Switched(PublicRoute.PLAYIT, null), switcher.switchTo(PublicRoute.PLAYIT, ports))
        assertTrue(hooks.log.isEmpty())
    }

    @Test fun rapidTogglesNeverOverlapASecondChangeIsRefusedWhileOneRuns() {
        val (switcher, drivers, hooks) = setup(PublicRoute.NETBIRD_EU)
        val gate = CountDownLatch(1)
        drivers.getValue(PublicRoute.PLAYIT).prepareGate = gate
        var first: RouteSwitcher.Result? = null
        val worker = thread { first = switcher.switchTo(PublicRoute.PLAYIT, ports) }
        while (switcher.inProgress() == null) Thread.sleep(5)
        assertEquals(RouteSwitcher.Result.Busy, switcher.switchTo(PublicRoute.NETBIRD_EU, ports))
        assertEquals(RouteSwitcher.Result.Busy, switcher.switchTo(PublicRoute.LOCAL, ports))
        gate.countDown(); worker.join(5000)
        assertEquals(RouteSwitcher.Result.Switched(PublicRoute.PLAYIT, endpoints), first)
        assertEquals(1, hooks.log.count { it.startsWith("committed") })
        assertEquals(PublicRoute.PLAYIT, hooks.selected)
    }
}
