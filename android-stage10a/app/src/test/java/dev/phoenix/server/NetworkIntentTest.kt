package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIntentTest {
    @Test
    fun turningNetBirdOffDropsOnlyANetBirdRequest() {
        assertNull(NetworkIntent.requestAfterNetBirdOff(PublicRoute.NETBIRD_EU))
        assertNull(NetworkIntent.requestAfterNetBirdOff(null))
        assertEquals(PublicRoute.PLAYIT, NetworkIntent.requestAfterNetBirdOff(PublicRoute.PLAYIT))
        assertEquals(PublicRoute.NETBIRD_REGIONAL, NetworkIntent.requestAfterNetBirdOff(PublicRoute.NETBIRD_REGIONAL))
    }

    @Test
    fun anOwnerChoiceToTurnNetBirdOffStopsAutomaticStarts() {
        assertTrue(NetworkIntent.netBirdMayAutoStart(ownerTurnedOff = false))
        assertFalse(NetworkIntent.netBirdMayAutoStart(ownerTurnedOff = true))
    }

    @Test
    fun aCheckThatCannotReachMinecraftHowKeepsTheLastVerifiedAnswer() {
        assertTrue(NetworkIntent.linkedAfterCheck(previous = true, signedIn = false, reachable = false))
        assertFalse(NetworkIntent.linkedAfterCheck(previous = false, signedIn = true, reachable = false))
        assertTrue(NetworkIntent.linkedAfterCheck(previous = false, signedIn = true, reachable = true))
        assertFalse(NetworkIntent.linkedAfterCheck(previous = true, signedIn = false, reachable = true))
    }

    @Test
    fun aColdStartReadUsesTheLiveValueThenTheRememberedOneThenTheDefault() {
        assertEquals(false, NetworkIntent.lastKnown(live = false, remembered = true, default = true))
        assertEquals(true, NetworkIntent.lastKnown(live = null, remembered = true, default = false))
        assertEquals(false, NetworkIntent.lastKnown(live = null, remembered = null, default = false))
    }
}
