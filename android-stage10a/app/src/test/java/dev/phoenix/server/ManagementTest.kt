package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Random

class ManagementTest {
    @Test fun maskedTextFramesOfEverySizeRoundTrip() {
        val random = Random(7)
        for (size in listOf(0, 5, 125, 126, 300, 70_000)) {
            val text = "x".repeat(size)
            val bytes = WebSocketFrames.text(text, random)
            assertEquals(0x81, bytes[0].toInt() and 0xFF)
            assertEquals("client frames are masked", 0x80, bytes[1].toInt() and 0x80)
            assertEquals(text, WebSocketFrames.read(DataInputStream(ByteArrayInputStream(bytes))))
        }
    }

    @Test fun pingsAreSkippedAndACloseEndsTheStream() {
        val ping = byteArrayOf(0x89.toByte(), 0x02, 'h'.code.toByte(), 'i'.code.toByte())
        val text = byteArrayOf(0x81.toByte(), 0x02, '{'.code.toByte(), '}'.code.toByte())
        assertEquals("{}", WebSocketFrames.read(DataInputStream(ByteArrayInputStream(ping + text))))
        assertNull(WebSocketFrames.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0x88.toByte(), 0x00)))))
    }

    @Test fun easesOffWhenHotAndRestoresAfterTwoCoolMinutes() {
        assertEquals(ThermalPolicy.Action.EASE_OFF, ThermalPolicy.next(0, 0.9f, eased = false, coolSince = null, now = 0))
        assertEquals("moderate thermal status even without a headroom forecast", ThermalPolicy.Action.EASE_OFF,
            ThermalPolicy.next(2, Float.NaN, eased = false, coolSince = null, now = 0))
        assertEquals(ThermalPolicy.Action.NONE, ThermalPolicy.next(0, 0.7f, eased = false, coolSince = null, now = 0))
        assertEquals("already eased off", ThermalPolicy.Action.NONE, ThermalPolicy.next(2, 0.95f, eased = true, coolSince = null, now = 0))
        assertEquals("cool, but not for long enough", ThermalPolicy.Action.NONE, ThermalPolicy.next(0, 0.4f, eased = true, coolSince = 0, now = 60_000))
        assertEquals(ThermalPolicy.Action.RESTORE, ThermalPolicy.next(0, 0.4f, eased = true, coolSince = 0, now = 120_000))
        assertEquals("warm but below the hot mark keeps the lower distance", ThermalPolicy.Action.NONE,
            ThermalPolicy.next(1, 0.7f, eased = true, coolSince = 0, now = 600_000))
    }
}
