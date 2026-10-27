package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JvmTuningTest {
    private val cache = File("cache")

    @Test
    fun offByDefaultLeavesThePinnedLaunchUnchanged() {
        assertEquals(emptyList<String>(), JvmTuning.flags(false, 2048, cache))
    }

    @Test
    fun smallHeapsUseSerialAndLargerOnesUseG1() {
        val small = JvmTuning.flags(true, 768, cache)
        assertTrue("-XX:+UseSerialGC" in small)
        assertFalse(small.any { it.contains("G1") })
        val large = JvmTuning.flags(true, 1024, cache)
        assertTrue("-XX:+UseG1GC" in large && "-XX:MaxGCPauseMillis=100" in large)
        assertFalse("-XX:+UseSerialGC" in large)
    }

    @Test
    fun neverPreTouchesAndKeepsTheCdsArchiveInTheCache() {
        val flags = JvmTuning.flags(true, 1536, cache)
        assertTrue("-XX:-AlwaysPreTouch" in flags)
        assertFalse("-XX:+AlwaysPreTouch" in flags)
        assertTrue("-Xshare:auto" in flags)
        assertEquals("-XX:SharedArchiveFile=${File(cache, JvmTuning.CDS_ARCHIVE).absolutePath}", flags.last())
    }

    @Test
    fun theMetricsAgentReturnsIdleHeapOnlyAfterTheStart() {
        assertEquals("idleHeap=120", JvmTuning.agentArguments())
        assertTrue("well after a typical 60 s start", JvmTuning.IDLE_HEAP_AFTER_S >= 90)
    }

    @Test
    fun experimentFlagsTakeOnlyJvmOptionsOnePerLine() {
        val text = listOf("# Netty", "-Dio.netty.allocator.numDirectArenas=2", "", "-XX:ParallelGCThreads=2  # big cores",
            "rm -rf /", "-jar x.jar", "-XX:Foo bar").joinToString("\n")
        assertEquals(listOf("-Dio.netty.allocator.numDirectArenas=2", "-XX:ParallelGCThreads=2"), JvmTuning.experimentFlags(text))
        assertEquals(emptyList<String>(), JvmTuning.experimentFlags(null))
    }

    @Test
    fun aotCacheIsUsedOnlyForTheSameKeyAndLearnsAgainOtherwise() {
        val free = 4L * 1024 * 1_048_576
        assertEquals(AotCache.Mode.USE, AotCache.mode(true, cacheExists = true, savedKey = "k", key = "k", freeBytes = free))
        assertEquals(AotCache.Mode.RECORD, AotCache.mode(true, cacheExists = true, savedKey = "old", key = "k", freeBytes = free))
        assertEquals(AotCache.Mode.RECORD, AotCache.mode(true, cacheExists = false, savedKey = null, key = "k", freeBytes = free))
        assertEquals("not enough storage to learn", AotCache.Mode.NONE, AotCache.mode(true, false, null, "k", 200L * 1_048_576))
        assertEquals(AotCache.Mode.NONE, AotCache.mode(false, true, "k", "k", free))
        assertEquals(listOf("-XX:AOTCache=${File(cache, AotCache.CACHE).absolutePath}"), AotCache.flags(AotCache.Mode.USE, cache))
        assertEquals("-XX:AOTMode=record", AotCache.flags(AotCache.Mode.RECORD, cache).first())
        assertEquals(emptyList<String>(), AotCache.flags(AotCache.Mode.NONE, cache))
        val a = AotCache.key(5, 100, "{runtime}", emptyList())
        assertFalse("an update or reinstall learns again", a == AotCache.key(5, 101, "{runtime}", emptyList()))
        assertFalse("a new runtime learns again", a == AotCache.key(5, 100, "{runtime2}", emptyList()))
    }

    @Test
    fun fasterStartsIsOffUntilTheOwnerTurnsItOn() {
        // A fresh install must launch without any -XX:AOT flag: AOT crashed the C1 compiler on the certified stack.
        assertFalse(AotCache.DEFAULT_ENABLED)
        assertFalse(Dashboard().fasterStarts)
        val mode = AotCache.mode(AotCache.DEFAULT_ENABLED, cacheExists = true, savedKey = "k", key = "k", freeBytes = Long.MAX_VALUE)
        assertEquals(emptyList<String>(), AotCache.flags(mode, File("cache")))
    }
}

