package dev.phoenix.server

/**
 * The owner's own network choices and the cautious rules that follow from them, kept apart from what each provider
 * reports. Pure and unit tested; the backend, the NetBird service and the status reads use these same rules.
 */
object NetworkIntent {
    /**
     * Turning NetBird off drops a pending NetBird request, so a later sign-in or setup never switches to NetBird by
     * itself. Requests for the other routes are kept.
     */
    fun requestAfterNetBirdOff(requested: PublicRoute?): PublicRoute? = requested.takeIf { it != PublicRoute.NETBIRD_EU }

    /**
     * Automatic paths (the name's front door, health checks, Android restarting the NetBird service) may start NetBird
     * only when the owner has not turned it off. Starting it from a button is always the owner's own choice.
     */
    fun netBirdMayAutoStart(ownerTurnedOff: Boolean): Boolean = !ownerTurnedOff

    /**
     * Minecraft.How's signed-in answer after a check. A check that could not reach Minecraft.How (no network yet, the
     * VPN still starting) says nothing new, so the last verified answer stays; only a reachable check changes it.
     */
    fun linkedAfterCheck(previous: Boolean, signedIn: Boolean, reachable: Boolean): Boolean = if (reachable) signedIn else previous

    /** A value read on a cold start: the live read when there is one, else what was saved last, else the default. */
    fun <T> lastKnown(live: T?, remembered: T?, default: T): T = live ?: remembered ?: default
}
