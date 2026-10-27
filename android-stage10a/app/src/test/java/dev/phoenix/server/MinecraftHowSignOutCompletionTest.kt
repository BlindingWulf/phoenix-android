package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test

class MinecraftHowSignOutCompletionTest {
    private class QueuedWorker : (Runnable) -> Unit {
        val queued = mutableListOf<Runnable>()
        override fun invoke(task: Runnable) { queued += task }
        fun runAll() { queued.toList().forEach { it.run() }; queued.clear() }
    }

    @Test fun flushAndReadBackRunOnlyOnTheWorkerNotTheCallingThread() {
        val worker = QueuedWorker()
        var flushed = 0
        var result: Boolean? = null
        MinecraftHowSignOutCompletion.complete(worker, { flushed++ }, { null }, { result = it })
        assertEquals(0, flushed)
        assertNull(result)
        worker.runAll()
        assertEquals(1, flushed)
        assertEquals(true, result)
    }

    @Test fun aRemainingCookieIsReportedAsNotCleared() {
        val worker = QueuedWorker()
        var result: Boolean? = null
        MinecraftHowSignOutCompletion.complete(worker, {}, { "fixture_session=fake" }, { result = it })
        worker.runAll()
        assertEquals(false, result)
    }

    @Test fun aFailingFlushIsReportedAsNotClearedWithoutThrowing() {
        val worker = QueuedWorker()
        var result: Boolean? = null
        var headerRead = false
        MinecraftHowSignOutCompletion.complete(worker, { throw IllegalStateException("storage") }, { headerRead = true; null }, { result = it })
        worker.runAll()
        assertEquals(false, result)
        assertFalse(headerRead)
    }

    @Test fun aWorkerThatCannotStartReportsNotClearedAndNeverFlushesInline() {
        var flushed = false
        var result: Boolean? = null
        MinecraftHowSignOutCompletion.complete({ throw IllegalStateException("no thread") }, { flushed = true }, { null }, { result = it })
        assertFalse(flushed)
        assertEquals(false, result)
    }

    @Test fun daemonThreadDeliversTheResult() {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Boolean? = null
        MinecraftHowSignOutCompletion.complete(MinecraftHowSignOutCompletion.daemonThread, {}, { null }, { result = it; latch.countDown() })
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(true, result)
    }
}
