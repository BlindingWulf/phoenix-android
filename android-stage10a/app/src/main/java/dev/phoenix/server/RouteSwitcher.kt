package dev.phoenix.server

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** The server's listener ports, read from its saved configuration. Voice is 0 when Simple Voice Chat is absent. */
data class ServerPorts(val java: Int, val bedrock: Int, val voice: Int)

/**
 * One public route's data plane and setup, behind the one switch path. Implementations never disturb another route
 * from [prepare]; only [stop] ends a route, and it keeps credentials and tunnels.
 */
interface RouteDriver {
    /**
     * Makes this route ready to carry players: credentials present, tunnels or services created for [ports], data
     * plane running and connected. Idempotent. Throws [RouteException] with a message for the owner when it can't.
     */
    fun prepare(ports: ServerPorts, progress: (String) -> Unit): RouteEndpoints
    /** Starts the data plane for hosting with what is already set up; never signs in or creates anything. */
    fun start(ports: ServerPorts)
    /** Stops the data plane. Credentials, tunnels and services stay for next time. */
    fun stop()
    /** True while the data plane runs (connected or still connecting). */
    fun running(): Boolean
}

/** A failure the owner can act on; [message] is shown as is. */
class RouteException(message: String) : Exception(message)

/** Persistence and side effects around a route change, supplied by the backend. */
interface RouteHooks {
    fun selected(): PublicRoute?
    /** Saves the selection durably (commit, not apply); false when storage refused it. */
    fun saveSelected(route: PublicRoute): Boolean
    /**
     * Checks the prepared route can carry players before anything moves: with the server online, a status ping through
     * the public Java address. Returns null when good, otherwise why not. Offline servers are accepted (checked at start).
     */
    fun verify(route: PublicRoute, endpoints: RouteEndpoints): String?
    /** After the selection is saved and the old route stopped: voice address, custom name, cached checks. Never throws. */
    fun committed(previous: PublicRoute?, route: PublicRoute, endpoints: RouteEndpoints?)
    fun progress(change: RouteSwitch?)
}

/**
 * The only way the public route changes. One change at a time; the previous route keeps running until the new one is
 * prepared and verified, then the selection is saved, the previous data plane stops, and dependent settings follow.
 * A failure before the save stops only what this change started, so the previous route is untouched.
 */
class RouteSwitcher(private val drivers: Map<PublicRoute, RouteDriver>, private val hooks: RouteHooks) {
    private val lock = ReentrantLock()
    private val generation = AtomicLong()
    private val current = AtomicReference<RouteSwitch?>(null)

    sealed class Result {
        data class Switched(val route: PublicRoute, val endpoints: RouteEndpoints?) : Result()
        data class Kept(val route: PublicRoute?, val reason: String) : Result()
        data object Busy : Result()
    }

    /** Increases with every committed change; results computed for an older generation are stale. */
    fun generation(): Long = generation.get()
    fun inProgress(): RouteSwitch? = current.get()

    fun switchTo(target: PublicRoute, ports: ServerPorts): Result {
        if (!lock.tryLock()) return Result.Busy
        try {
            val previous = hooks.selected()
            if (previous == target && (target == PublicRoute.LOCAL || drivers[target]?.running() == true)) return Result.Switched(target, null)
            fun step(text: String) = RouteSwitch(previous, target, text).also { current.set(it); hooks.progress(it) }
            step("Preparing ${target.title}")
            if (target == PublicRoute.LOCAL) {
                step("Turning off public access")
                if (!hooks.saveSelected(target)) return Result.Kept(previous, "Phoenix couldn't save the change; nothing was turned off.")
                stopUnless(previous, target)
                generation.incrementAndGet()
                hooks.committed(previous, target, null)
                return Result.Switched(target, null)
            }
            val driver = drivers[target] ?: return Result.Kept(previous, "${target.title} isn't available in this build.")
            val wasRunning = driver.running()
            val endpoints = try {
                driver.prepare(ports) { step(it) }
            } catch (failure: Exception) {
                rollback(driver, wasRunning, previous, target)
                return Result.Kept(previous, failure.message?.takeIf { it.isNotBlank() } ?: "${target.title} couldn't be prepared.")
            }
            step("Checking ${target.title} from the internet")
            hooks.verify(target, endpoints)?.let { reason ->
                rollback(driver, wasRunning, previous, target)
                return Result.Kept(previous, reason)
            }
            step("Switching players to ${target.title}")
            if (!hooks.saveSelected(target)) {
                rollback(driver, wasRunning, previous, target)
                return Result.Kept(previous, "Phoenix couldn't save the change; the previous route was kept.")
            }
            stopUnless(previous, target)
            generation.incrementAndGet()
            hooks.committed(previous, target, endpoints)
            return Result.Switched(target, endpoints)
        } finally {
            current.set(null)
            hooks.progress(null)
            lock.unlock()
        }
    }

    /** Stops the previous route's data plane, except when the new route needs the same one (both NetBird routes). */
    private fun stopUnless(previous: PublicRoute?, target: PublicRoute) {
        if (previous == null || previous == target || previous == PublicRoute.LOCAL) return
        if (previous.usesNetBird && target.usesNetBird) return
        runCatching { drivers[previous]?.stop() }
    }

    /** Undo only what this attempt started; the selected route's data plane is left exactly as it was. */
    private fun rollback(driver: RouteDriver, wasRunning: Boolean, previous: PublicRoute?, target: PublicRoute) {
        if (wasRunning) return
        if (previous != null && previous.usesNetBird && target.usesNetBird) return
        runCatching { driver.stop() }
    }
}
