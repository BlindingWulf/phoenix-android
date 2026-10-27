package dev.phoenix.server

/**
 * Finishes a local sign-out after the cookie jar was cleared. `removeAllCookies` reports on the main
 * thread, and a cookie flush may block on disk I/O, so the flush and read-back run on [offMain].
 */
internal object MinecraftHowSignOutCompletion {
    fun complete(offMain: (Runnable) -> Unit, flush: () -> Unit, header: () -> String?, done: (Boolean) -> Unit) {
        val task = Runnable {
            val cleared = try {
                flush()
                header() == null
            } catch (_: RuntimeException) {
                false
            }
            done(cleared)
        }
        try {
            offMain(task)
        } catch (_: RuntimeException) {
            // No worker could start: report "not cleared" rather than flushing on the main thread.
            done(false)
        }
    }

    /** A short-lived daemon thread; sign-out is rare and must not hold the screen's executor. */
    val daemonThread: (Runnable) -> Unit = { task -> Thread(task, "minecraft-how-sign-out").apply { isDaemon = true }.start() }
}
