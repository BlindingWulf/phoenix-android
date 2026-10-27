package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** In-memory stand-in for the app-private preferences file; copying it models what survived process death. */
internal class MemoryStore(initial: Map<String, String> = emptyMap()) : MinecraftHowHandoffJournal.Store {
    private val values = LinkedHashMap(initial)
    var failWrites = false
    override fun read(): Map<String, String> = LinkedHashMap(values)
    override fun replace(values: Map<String, String>): Boolean {
        if (failWrites) return false
        this.values.clear()
        this.values.putAll(values)
        return true
    }
    fun copy() = MemoryStore(values)
}

class MinecraftHowHandoffRestoreTest {
    // Fixture-only contract and values; nothing here is configured in the app.
    private val contract = MinecraftHowBrowserConnection.Contract("fixture-client", "/fixture/authorize", "/fixture/exchange", MinecraftHowBrowserConnection.CALLBACK_URI)
    private val okWithCookie = HttpResponse(200, mapOf("Set-Cookie" to listOf("fixture=fake; Secure; HttpOnly; Path=/")))
    private fun callback(attempt: MinecraftHowBrowserConnection.Attempt) = "${contract.callback}?state=${attempt.state}&code=fixture-code"

    /** A new connection over the same store is what a restarted process sees. */
    private fun process(store: MemoryStore, transport: HttpTransport = HttpTransport { error("must not POST") }, now: () -> Long = { 1_000L }) =
        MinecraftHowBrowserConnection(contract, MinecraftHowHandoffJournal(store), transport, now)

    @Test fun interruptedBeforeCallbackResumesAndExchangesExactlyOnce() {
        val store = MemoryStore()
        val savedId = process(store).begin()!!.id
        var posts = 0
        var installs = 0
        val restarted = process(store, HttpTransport { posts++; okWithCookie })
        val resume = restarted.resume(savedId)
        assertTrue(resume is MinecraftHowBrowserConnection.Resume.Pending)
        val attempt = (resume as MinecraftHowBrowserConnection.Resume.Pending).attempt
        assertEquals(MinecraftHowBrowserConnection.Outcome.CONNECTED, restarted.complete(attempt, callback(attempt)) { installs++; true })

        // The same callback delivered again, to this or yet another process, is never resent.
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, restarted.complete(attempt, callback(attempt)) { installs++; true })
        val again = process(store)
        assertNull(again.pending())
        assertEquals(MinecraftHowBrowserConnection.Resume.Stale, again.resume(savedId))
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, again.complete(attempt, callback(attempt)) { installs++; true })
        assertEquals(1, posts)
        assertEquals(1, installs)
    }

    @Test fun processDeathDuringExchangeNeverResendsOrConnects() {
        val store = MemoryStore()
        val first = process(store).begin()!!
        var diskAtPost: MemoryStore? = null
        var posts = 0
        // The process dies while the POST is in flight: only what was committed before it survives.
        val dying = process(store, HttpTransport { posts++; diskAtPost = store.copy(); throw IOException("fixture: process killed") })
        dying.complete(dying.pending()!!, callback(first)) { fail("must not install"); false }
        val survived = diskAtPost!!
        assertFalse(survived.read().values.any { it == first.verifier || it == first.state })

        var installs = 0
        val restarted = process(survived)
        assertEquals(MinecraftHowBrowserConnection.Resume.Unresolved, restarted.resume(first.id))
        assertNull(restarted.pending())
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, restarted.complete(first, callback(first)) { installs++; true })
        assertTrue(restarted.acknowledgeUnresolved())
        assertEquals(MinecraftHowBrowserConnection.Resume.Stale, restarted.resume(first.id))
        assertEquals(MinecraftHowBrowserConnection.Resume.None, restarted.resume(null))
        assertEquals(1, posts)
        assertEquals(0, installs)
    }

    @Test fun uncertainExchangeResultIsNeverReplayedOrTreatedAsConnected() {
        for (failure in listOf<() -> HttpResponse>({ throw IOException("fixture timeout") }, { HttpResponse(503) }, { HttpResponse(302, mapOf("Location" to listOf("https://evil.example/"))) })) {
            val store = MemoryStore()
            var posts = 0
            var installs = 0
            val connection = process(store, HttpTransport { posts++; failure() })
            val attempt = connection.begin()!!
            assertEquals(MinecraftHowBrowserConnection.Outcome.EXCHANGE_UNCONFIRMED, connection.complete(attempt, callback(attempt)) { installs++; true })
            assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(attempt, callback(attempt)) { installs++; true })

            val restarted = process(store, HttpTransport { posts++; okWithCookie })
            assertEquals(MinecraftHowBrowserConnection.Resume.Unresolved, restarted.resume(attempt.id))
            assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, restarted.complete(attempt, callback(attempt)) { installs++; true })
            assertEquals(1, posts)
            assertEquals(0, installs)

            // Starting over is a brand-new attempt; the old callback cannot complete it.
            val fresh = restarted.begin()!!
            assertEquals(MinecraftHowBrowserConnection.Outcome.INVALID_CALLBACK, restarted.complete(fresh, callback(attempt)) { installs++; true })
            assertEquals(1, posts)
        }
    }

    @Test fun olderSnapshotsCannotReArmAnAttempt() {
        // Snapshot of an attempt that was later canceled and replaced.
        val store = MemoryStore()
        val old = process(store).begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CANCELED, process(store).complete(old, null) { true })
        val newer = process(store).begin()!!
        assertEquals(MinecraftHowBrowserConnection.Resume.Stale, process(store).resume(old.id))
        // Fail closed: the restored stale screen also abandons the attempt it does not own.
        assertNull(process(store).pending())
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, process(store).complete(newer, callback(newer)) { true })

        // Snapshot of an attempt that already finished.
        val done = MemoryStore()
        val finished = process(done, HttpTransport { okWithCookie }).let { c -> c.begin()!!.also { c.complete(it, callback(it)) { true } } }
        assertEquals(MinecraftHowBrowserConnection.Resume.Stale, process(done).resume(finished.id))
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, process(done).complete(finished, callback(finished)) { fail("must not install"); true })

        // A pre-journal snapshot carried the verifier in a Bundle; it now yields no id and nothing is pending.
        val upgraded = MemoryStore()
        assertEquals(MinecraftHowBrowserConnection.Resume.None, process(upgraded).resume(null))
        assertNull(process(upgraded).pending())

        // A fresh screen without a snapshot abandons any leftover attempt, so a late callback cannot connect.
        val leftover = MemoryStore()
        val abandoned = process(leftover).begin()!!
        assertEquals(MinecraftHowBrowserConnection.Resume.None, process(leftover).resume(null))
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, process(leftover).complete(abandoned, callback(abandoned)) { true })
    }

    @Test fun duplicateAndStaleCallbacksNeverReachTheExchange() {
        val store = MemoryStore()
        var posts = 0
        val connection = process(store, HttpTransport { posts++; okWithCookie })
        val first = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CONNECTED, connection.complete(first, callback(first)) { true })
        // The browser delivers the same return twice (Auth Tab result plus a deep link, or a reload).
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(first, callback(first)) { true })

        // An old return arriving during a newer attempt fails its state check and ends that attempt too.
        val second = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.INVALID_CALLBACK, connection.complete(connection.pending()!!, callback(first)) { true })
        assertNull(connection.pending())
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(second, callback(second)) { true })
        assertEquals(1, posts)
    }

    @Test fun cancellationAndExpiryLeaveAFreshRetryThatWorks() {
        val store = MemoryStore()
        var now = 1_000L
        var posts = 0
        val connection = process(store, HttpTransport { posts++; okWithCookie }) { now }
        val canceled = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CANCELED, connection.complete(canceled, null) { true })
        assertEquals(MinecraftHowBrowserConnection.Resume.Stale, connection.resume(canceled.id))

        val expired = connection.begin()!!
        now += MinecraftHowBrowserConnection.MAX_AGE_MS + 1
        assertEquals(MinecraftHowBrowserConnection.Outcome.EXPIRED, connection.complete(expired, callback(expired)) { true })

        val retry = connection.begin()!!
        assertEquals(MinecraftHowBrowserConnection.Outcome.CONNECTED, connection.complete(retry, callback(retry)) { true })
        // Late returns from the earlier attempts change nothing.
        assertEquals(MinecraftHowBrowserConnection.Outcome.ALREADY_USED, connection.complete(canceled, callback(canceled)) { true })
        assertEquals(1, posts)
    }

    @Test fun restoredAttemptStillExpiresAfterTenMinutes() {
        val store = MemoryStore()
        val attempt = process(store, now = { 1_000L }).begin()!!
        val later = process(store, now = { 1_000L + MinecraftHowBrowserConnection.MAX_AGE_MS + 1 })
        val restored = (later.resume(attempt.id) as MinecraftHowBrowserConnection.Resume.Pending).attempt
        assertEquals(MinecraftHowBrowserConnection.Outcome.EXPIRED, later.complete(restored, callback(restored)) { true })
        assertNull(later.pending())
    }

    @Test fun unconfirmedStorageWritesFailClosed() {
        val unwritable = MemoryStore().apply { failWrites = true }
        assertNull(process(unwritable).begin())

        val store = MemoryStore()
        var posts = 0
        val connection = process(store, HttpTransport { posts++; okWithCookie })
        val attempt = connection.begin()!!
        store.failWrites = true
        assertEquals(MinecraftHowBrowserConnection.Outcome.NOT_RECORDED, connection.complete(attempt, callback(attempt)) { true })
        assertEquals(0, posts)
    }
}
