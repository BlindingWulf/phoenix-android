package dev.phoenix.server

/** What the sign-in screen does with a native account read. Pure so every branch is unit-tested. */
internal object MinecraftHowAccountCheck {
    enum class Next {
        /** A sign-in the user just finished verified: return to Phoenix automatically. */
        RETURN,
        /** A saved session is still valid: offer "Continue as …" or "Use another account", never auto-close. */
        OFFER_CONTINUE,
        /** An attempt failed or is unknown, but an earlier account is still signed in. It is not the new account. */
        KEPT_EARLIER_ACCOUNT,
        /** An attempt failed or is unknown and nothing is signed in. */
        NOT_CONNECTED,
        /** Minecraft.How could not be reached or answered unexpectedly: not proof of sign-out. */
        UNREACHABLE,
        /** Phoenix verified an account before, and Minecraft.How now says it is signed out. */
        EXPIRED,
        NOT_SIGNED_IN,
    }

    /**
     * [verification] is null when Phoenix holds no Minecraft.How cookie at all. [afterAttempt] is true
     * when this read follows a failed, canceled or unresolved connection attempt, so whatever session it
     * finds predates that attempt.
     */
    fun decide(
        verification: MinecraftHowClient.SessionVerification?,
        afterAttempt: Boolean,
        autoReturn: Boolean,
        verifiedBefore: Boolean,
    ): Next = when {
        verification != null && !verification.reachable -> Next.UNREACHABLE
        verification?.signedIn == true -> when {
            afterAttempt -> Next.KEPT_EARLIER_ACCOUNT
            autoReturn -> Next.RETURN
            else -> Next.OFFER_CONTINUE
        }
        afterAttempt -> Next.NOT_CONNECTED
        verifiedBefore -> Next.EXPIRED
        else -> Next.NOT_SIGNED_IN
    }
}
