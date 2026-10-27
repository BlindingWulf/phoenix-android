package dev.phoenix.server

import org.junit.Assert.*
import org.junit.Test

class MinecraftHowBrowserCookiesTest {
    private val session = "fixture_session=fake; Secure; HttpOnly; Path=/"
    private val xsrf = "XSRF-TOKEN=fake; Secure; Path=/"

    @Test fun secureSameOriginReceiptsRequireCsrfAndDoNotExposeValuesInToString() {
        val receipt = MinecraftHowBrowserCookies.validate(listOf(session, xsrf))!!
        assertEquals("fixture_session=fake; XSRF-TOKEN=fake", receipt.cookieHeader)
        assertFalse(receipt.toString().contains("fake"))
        assertNotNull(MinecraftHowBrowserCookies.validate(listOf(session + "; Domain=.minecraft.how", xsrf)))
        assertNull(MinecraftHowBrowserCookies.validate(listOf(session)))
    }

    @Test fun insecureOffsiteRestrictedExpiredAndInjectedCookiesAreRejected() {
        val invalid = listOf(session.replace(" Secure;", ""), session + "; Domain=evil.example",
            session + "; Domain=..minecraft.how", session.replace("Path=/", "Path=/auth"),
            session + "; Max-Age=0", session + "\r\nOther: injected")
        for (header in invalid) assertNull(header, MinecraftHowBrowserCookies.validate(listOf(header, xsrf)))
    }

    @Test fun duplicatesAndOversizedReceiptsAreRejected() {
        assertNull(MinecraftHowBrowserCookies.validate(listOf(session, session, xsrf)))
        assertNull(MinecraftHowBrowserCookies.validate(listOf("x=" + "a".repeat(8200), xsrf)))
        assertNull(MinecraftHowBrowserCookies.validate(emptyList()))
    }
}
