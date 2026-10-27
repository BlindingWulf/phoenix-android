package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetBirdStatusPollTest {
    @Test
    fun connectedPollsRelaxed() {
        assertEquals(5000L, NetBirdVpnService.statusPollDelayMs("Connected"))
    }

    @Test
    fun transitionalStatesPollQuickly() {
        listOf("", "Idle", "Connecting", "SomethingNew").forEach {
            assertEquals(it, 1500L, NetBirdVpnService.statusPollDelayMs(it))
        }
    }

    @Test
    fun statesWaitingOnTheUserDoNotPollEveryFewSeconds() {
        listOf("NeedsLogin", "SessionExpired", "LoginFailed").forEach {
            assertTrue(it, NetBirdVpnService.statusPollDelayMs(it) >= 15000L)
        }
    }

    @Test
    fun waitingStatesStillDescribeTheSignInAction() {
        listOf("NeedsLogin", "SessionExpired", "LoginFailed").forEach {
            assertTrue(it, NetBirdVpnService.describeGoStatus(it).contains("Authorize"))
        }
    }
}
