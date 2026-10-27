package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test

/** Fixture accounts and cookies only. "A" is the account Phoenix already holds; "B" is the one chosen in a new attempt. */
class MinecraftHowAccountSwitchTest {
    private class FakeJar(initial: Map<String, String>) : MinecraftHowSessionSwitch.Jar {
        val cookies = LinkedHashMap(initial)
        /** Cookies the jar will not change, e.g. one stored under another domain or path. */
        val stuck = mutableSetOf<String>()
        var flushes = 0
        override fun header() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
        override fun set(setCookie: String) {
            val name = setCookie.substringBefore('=').trim()
            if (name in stuck) return
            if (setCookie.contains("Max-Age=0")) cookies.remove(name)
            else cookies[name] = setCookie.substringAfter('=').substringBefore(';')
        }
        override fun flush() { flushes++ }
    }

    private val accountA = mapOf("laravel_session" to "sessA", "XSRF-TOKEN" to "xsrfA", "remember_web" to "rememberA")
    private val receiptB = listOf("laravel_session=sessB; Secure; HttpOnly; Path=/", "XSRF-TOKEN=xsrfB; Secure; Path=/")
    private var reads = 0

    /** The fixture server: a remember cookie for A wins over a session for B, the way a merged jar could. */
    private fun server(header: String): MinecraftHowClient.SessionVerification {
        reads++
        val cookies = header.split(';').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val id = when {
            cookies["remember_web"] == "rememberA" || cookies["laravel_session"] == "sessA" -> 1L
            cookies["laravel_session"] == "sessB" -> 2L
            else -> null
        }
        return if (id == null) MinecraftHowClient.SessionVerification(false, "signed out", 0)
        else MinecraftHowClient.SessionVerification(true, "signed in", 1, accountLabel = "fixture$id", accountId = id)
    }

    @Test fun newAccountReplacesTheEarlierOneInsteadOfMerging() {
        val jar = FakeJar(accountA)
        assertTrue(MinecraftHowSessionSwitch.install(receiptB, jar, ::server))
        assertEquals(setOf("laravel_session", "XSRF-TOKEN"), jar.cookies.keys)
        assertEquals(2L, server(jar.header()!!).accountId)
    }

    @Test fun failedNewLoginLeavesTheEarlierAccountUntouched() {
        for (verify in listOf<(String) -> MinecraftHowClient.SessionVerification>(
            { MinecraftHowClient.SessionVerification(false, "signed out", 0) },
            { MinecraftHowClient.SessionVerification(false, "offline", 0, reachable = false) },
            // Signed in but without a numeric account id cannot be compared, so it is not accepted.
            { MinecraftHowClient.SessionVerification(true, "signed in", 0, accountLabel = "fixture") },
        )) {
            val jar = FakeJar(accountA)
            assertFalse(MinecraftHowSessionSwitch.install(receiptB, jar, verify))
            assertEquals(accountA, jar.cookies)
        }
        val jar = FakeJar(accountA)
        assertFalse(MinecraftHowSessionSwitch.install(listOf("laravel_session=sessB; Path=/"), jar, ::server))
        assertEquals(accountA, jar.cookies)
    }

    @Test fun earlierAccountThatSurvivesTheSwitchIsNeverReportedAsTheNewOne() {
        // A leftover remember cookie for A would make the merged jar read back as A.
        val leftover = FakeJar(accountA).apply { stuck += "remember_web" }
        assertFalse(MinecraftHowSessionSwitch.install(receiptB, leftover, ::server))
        assertFalse(leftover.cookies.containsKey("laravel_session"))

        // A jar that ignores the new session keeps A with matching cookie names; the account id check catches it.
        val ignoring = FakeJar(mapOf("laravel_session" to "sessA", "XSRF-TOKEN" to "xsrfA")).apply { stuck += "laravel_session" }
        assertFalse(MinecraftHowSessionSwitch.install(receiptB, ignoring, ::server))
    }

    @Test fun accountCheckNeverPresentsAnEarlierSessionAsANewConnection() {
        val signedIn = MinecraftHowClient.SessionVerification(true, "signed in", 2, accountLabel = "fixture1", accountId = 1)
        val signedOut = MinecraftHowClient.SessionVerification(false, "signed out", 0)
        val offline = MinecraftHowClient.SessionVerification(false, "offline", 0, reachable = false)
        val check = MinecraftHowAccountCheck::decide
        // After a canceled, failed or unresolved attempt an existing session is the earlier account.
        assertEquals(MinecraftHowAccountCheck.Next.KEPT_EARLIER_ACCOUNT, check(signedIn, true, true, true))
        assertEquals(MinecraftHowAccountCheck.Next.NOT_CONNECTED, check(signedOut, true, false, true))
        assertEquals(MinecraftHowAccountCheck.Next.NOT_CONNECTED, check(null, true, false, false))
        // Only a sign-in the user just finished closes the screen; opening it offers the saved account.
        assertEquals(MinecraftHowAccountCheck.Next.RETURN, check(signedIn, false, true, false))
        assertEquals(MinecraftHowAccountCheck.Next.OFFER_CONTINUE, check(signedIn, false, false, true))
        // Network failure is not a sign-out; expired differs from never signed in.
        assertEquals(MinecraftHowAccountCheck.Next.UNREACHABLE, check(offline, false, false, true))
        assertEquals(MinecraftHowAccountCheck.Next.UNREACHABLE, check(offline, true, false, true))
        assertEquals(MinecraftHowAccountCheck.Next.EXPIRED, check(signedOut, false, false, true))
        assertEquals(MinecraftHowAccountCheck.Next.EXPIRED, check(null, false, false, true))
        assertEquals(MinecraftHowAccountCheck.Next.NOT_SIGNED_IN, check(signedOut, false, false, false))
        assertEquals(MinecraftHowAccountCheck.Next.NOT_SIGNED_IN, check(null, false, true, false))
    }
}
