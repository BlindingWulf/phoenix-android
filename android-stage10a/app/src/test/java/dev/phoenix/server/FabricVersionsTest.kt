package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FabricVersionsTest {
    private fun m(predicate: String, version: String) = FabricVersions.matches(predicate, version)

    @Test fun exactAndWildcards() {
        assertEquals(true, m("26.2", "26.2"))
        assertEquals(true, m("=26.2", "26.2.0"))
        assertEquals(false, m("26.2", "26.2.1"))
        assertEquals(true, m("26.2.x", "26.2.4"))
        assertEquals(true, m("26.x", "26.2"))
        assertEquals(false, m("26.1.x", "26.2"))
        assertEquals(true, m("*", "anything"))
        assertEquals(true, m("", "26.2"))
    }

    @Test fun comparisonsAndRanges() {
        assertEquals(true, m(">=26.2", "26.2"))
        assertEquals(false, m(">26.2", "26.2"))
        assertEquals(true, m(">=26.1 <26.3", "26.2"))
        assertEquals(false, m(">=26.1 <26.2", "26.2"))
        assertEquals(true, m(">=0.16.0", "0.19.5"))
        assertEquals(false, m(">=0.20.0", "0.19.5"))
        assertEquals(true, m(">=21", "25"))
    }

    @Test fun tildeAndCaret() {
        assertEquals(true, m("~26.2", "26.2.9"))
        assertEquals(false, m("~26.2", "26.3"))
        assertEquals(false, m("~26.2", "26.3-alpha.1"))
        assertEquals(true, m("^0.19.0", "0.25.1"))
        assertEquals(false, m("^0.19.0", "1.0.0"))
        assertEquals(false, m("~1.21", "26.2"))
    }

    @Test fun preReleasesSortBeforeTheirRelease() {
        assertEquals(false, m(">=26.2", "26.2-rc.1"))
        assertEquals(true, m(">=26.2-alpha.1", "26.2-rc.1"))
        assertEquals(true, m("<26.2", "26.2-beta.3"))
        assertEquals(true, m(">=0.4.2-alpha.0.43", "0.4.2-alpha.0.56"))
    }

    @Test fun buildMetadataIsIgnored() {
        assertEquals(true, m(">=0.159.0", "0.161.0+26.2"))
        assertEquals(true, m("0.161.0", "0.161.0+26.2"))
    }

    @Test fun nonSemanticVersionsCompareOnlyAsText() {
        assertEquals(true, m("2.11.3-SNAPSHOT", "2.11.3-SNAPSHOT"))
        assertEquals(true, m("beta-2", "beta-2"))
        assertEquals(false, m("beta-2", "beta-3"))
        assertNull(m(">=1.0", "beta-2"))
    }

    @Test fun listsMatchWhenAnyAlternativeDoes() {
        assertEquals(true, FabricVersions.matchesAny(listOf("1.21.x", "26.2"), "26.2"))
        assertEquals(false, FabricVersions.matchesAny(listOf("1.21.x", "1.20.x"), "26.2"))
        assertNull(FabricVersions.matchesAny(listOf("1.21.x", ">=x"), "26.2"))
        assertEquals(true, FabricVersions.matchesAny(emptyList(), "26.2"))
    }
}
