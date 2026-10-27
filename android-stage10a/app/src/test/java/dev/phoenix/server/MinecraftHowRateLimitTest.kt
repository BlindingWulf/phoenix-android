package dev.phoenix.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stage 9 regression: Minecraft.How HTTP 429 must stop requests, honour Retry-After and survive restarts. */
class MinecraftHowRateLimitTest {
    private val cookies = "laravel_session=fake; XSRF-TOKEN=csrf%2Bvalue"
    private val hostname = "fixture0test.minecraft.co.com"
    private var now = 1_700_000_000_000L
    private val persisted = mutableListOf<Long>()

    @Before
    fun reset() {
        MinecraftHowClient.restoreCooldown(0L)
        MinecraftHowClient.clock = { now }
        MinecraftHowClient.cooldownSink = { persisted += it }
    }

    @After
    fun restore() {
        MinecraftHowClient.restoreCooldown(0L)
        MinecraftHowClient.clock = System::currentTimeMillis
        MinecraftHowClient.cooldownSink = null
    }

    @Test
    fun retryAfterBlocksEveryRequestUntilTheDeadlineAndIsPersisted() {
        var calls = 0
        val client = MinecraftHowClient(HttpTransport {
            calls++
            HttpResponse(429, mapOf("Retry-After" to listOf("120")), "slow down")
        })

        val first = client.checkHostname(cookies, hostname)
        assertEquals(MinecraftHowClient.EnsureState.RATE_LIMITED, first.state)
        assertEquals(1, calls)
        assertEquals(now + 120_000L, MinecraftHowClient.cooldownUntilEpochMillis)
        assertEquals(listOf(now + 120_000L), persisted)
        assertTrue(first.detail.contains("Try again after"))

        now += 60_000L
        val blocked = client.checkHostname(cookies, hostname)
        assertEquals(MinecraftHowClient.EnsureState.RATE_LIMITED, blocked.state)
        assertEquals("no request may be sent during the cooldown", 1, calls)

        now += 60_001L
        client.checkHostname(cookies, hostname)
        assertEquals("one request after the deadline", 2, calls)
    }

    @Test
    fun missingRetryAfterUsesBoundedDoublingLocalBackoff() {
        val client = MinecraftHowClient(HttpTransport { HttpResponse(429) })
        val expected = listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)
        for (delay in expected) {
            assertEquals(MinecraftHowClient.EnsureState.RATE_LIMITED, client.checkHostname(cookies, hostname).state)
            assertEquals(now + delay, MinecraftHowClient.cooldownUntilEpochMillis)
            now += delay + 1
        }
    }

    @Test
    fun restoredCooldownFromAPreviousProcessBlocksWithoutTouchingTheNetwork() {
        MinecraftHowClient.restoreCooldown(now + 30_000L)
        val client = MinecraftHowClient(HttpTransport { error("must not reach the network during a restored cooldown") })
        assertEquals(MinecraftHowClient.EnsureState.RATE_LIMITED, client.checkHostname(cookies, hostname).state)
    }
}
