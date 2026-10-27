package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerMemoryTest {
    private val mb = 1_048_576L

    @Test
    fun fixedSizesStayWithinSixtyPercentOfThePhone() {
        // A 3.6 GB phone (the test phone) offers up to 2 GB.
        assertEquals(listOf(512, 768, 1024, 1536, 2048), ServerMemory.choices(3643 * mb))
        assertEquals(listOf(512, 768, 1024, 1536), ServerMemory.choices(3 * 1024 * mb))
        assertEquals(emptyList<Int>(), ServerMemory.choices(512 * mb))
    }

    @Test
    fun customSizesReachThreeQuartersOfBigPhones() {
        assertEquals(12288, ServerMemory.maxCustomMb(16L * 1024 * mb))
        assertEquals(true, ServerMemory.allowed(8192, 16L * 1024 * mb))
        assertEquals(true, ServerMemory.allowed(3000, 16L * 1024 * mb))
        assertEquals(false, ServerMemory.allowed(13000, 16L * 1024 * mb))
        assertEquals(false, ServerMemory.allowed(100, 16L * 1024 * mb))
        assertEquals(false, ServerMemory.allowed(3000, 3643 * mb))
        assertEquals(true, ServerMemory.allowed(0, 3643 * mb))
    }

    @Test
    fun dynamicMatchesTheOldAutomaticSize() {
        assertEquals(633L, ServerMemory.dynamicMb(1408 * mb))
        assertEquals(256L, ServerMemory.dynamicMb(300 * mb))
        assertEquals(1536L, ServerMemory.dynamicMb(6000 * mb))
    }

    @Test
    fun phoneTuningOnlyReplacesVanillaDefaults() {
        assertEquals(mapOf("view-distance" to "8", "simulation-distance" to "6", "sync-chunk-writes" to "false"), PhoneTuning.changes("10", "10", "true"))
        // Someone already picked their own distances: keep them, still skip the per-chunk fsync.
        assertEquals(mapOf("sync-chunk-writes" to "false"), PhoneTuning.changes("12", "4", null))
        assertEquals(emptyMap<String, String>(), PhoneTuning.changes("7", "5", "false"))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals("Dynamic", ServerMemory.label(0))
        assertEquals("768 MB", ServerMemory.label(768))
        assertEquals("1.5 GB", ServerMemory.label(1536))
        assertEquals("2 GB", ServerMemory.label(2048))
        assertEquals("2.5 GB", ServerMemory.label(2560))
        assertEquals("3000 MB", ServerMemory.label(3000))
    }
}
