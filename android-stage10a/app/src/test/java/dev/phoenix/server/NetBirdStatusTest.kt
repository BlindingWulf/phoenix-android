package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phoenix.core.ServerController

class NetBirdStatusTest {
    private val online = Dashboard(phase = ServerController.State.ONLINE, networkHasIdentity = true)

    @Test fun aClientThatEndedOnItsOwnIsAnErrorNotOff() {
        val stopped = online.copy(netBirdLink = "NetBird stopped. Tap Set up online play to reconnect.")
        assertTrue(netBirdStoppedUnexpectedly(stopped))
        assertEquals("Stopped unexpectedly" to Tone.ERROR, netBirdStatus(stopped))
        // Join status reads the shared route state, where the backend reports the same stop as a NetBird error.
        val netBird = ProviderState(PublicRoute.NETBIRD_EU, selected = true, configured = true, health = LinkHealth.ERROR, status = "Stopped unexpectedly")
        val withRoute = stopped.copy(net = NetworkView(PublicRoute.NETBIRD_EU, PublicRoute.entries.associateWith { if (it == PublicRoute.NETBIRD_EU) netBird else ProviderState(it) }))
        assertEquals("NetBird: Stopped unexpectedly" to Tone.ERROR, joinStatus(withRoute))
    }

    @Test fun theUsersOwnTurnOffAndALiveClientAreNotCrashes() {
        val turnedOff = online.copy(netBirdLink = "Disconnected. Phone identity and permanent services are saved.")
        assertFalse(netBirdStoppedUnexpectedly(turnedOff))
        assertEquals("Not connected" to Tone.WARN, netBirdStatus(turnedOff))
        assertEquals("Idle" to Tone.OK, netBirdStatus(turnedOff.copy(phase = ServerController.State.STOPPED)))
        val connecting = online.copy(netBirdLive = true, networkBusy = true, netBirdLink = "Connecting this phone to NetBird… 12s")
        assertFalse(netBirdStoppedUnexpectedly(connecting))
        val connected = online.copy(netBirdLive = true, netBirdLinkUp = true, netBirdLink = "Phone connected to NetBird")
        assertEquals("Connected" to Tone.OK, netBirdStatus(connected))
    }
}
