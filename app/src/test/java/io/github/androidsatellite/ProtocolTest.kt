package io.github.androidsatellite

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ProtocolTest {
    @Test fun imageChunkBoundariesAndRoundTrip() {
        for (size in listOf(1, 1015, 1016, 1017, 2032, 40000)) {
            val original = ByteArray(size) { (it % 251).toByte() }
            val reports = DeckProtocol.imageReports(31, original)
            val recovered = ArrayList<Byte>()
            reports.forEachIndexed { i, p ->
                assertEquals(1024, p.size)
                assertEquals(2, p[0].toInt()); assertEquals(7, p[1].toInt()); assertEquals(31, p[2].toInt())
                assertEquals(if (i == reports.lastIndex) 1 else 0, p[3].toInt())
                assertEquals(i, (p[6].toInt() and 255) or ((p[7].toInt() and 255) shl 8))
                val n = (p[4].toInt() and 255) or ((p[5].toInt() and 255) shl 8)
                assertTrue(n in 1..1016)
                recovered.addAll(p.slice(8 until 8 + n))
                assertTrue(p.drop(8 + n).all { it == 0.toByte() })
            }
            assertArrayEquals(original, recovered.toByteArray())
        }
    }
    @Test fun validatesInputAndAll32Keys() {
        val report = ByteArray(36).apply { this[0] = 1; this[2] = 32; this[4] = 1; this[35] = 1 }
        val keys = DeckProtocol.keys(report)!!
        assertTrue(keys[0]); assertTrue(keys[31]); assertEquals(2, keys.count { it })
        assertNull(DeckProtocol.keys(report.copyOf(35)))
        assertNull(DeckProtocol.keys(report.copyOf().apply { this[2] = 15 }))
        assertNull(DeckProtocol.keys(report.copyOf().apply { this[1] = 3 }))
    }
    @Test fun brightnessFormat() {
        assertArrayEquals(byteArrayOf(3, 8, 100) + ByteArray(29), DeckProtocol.brightness(100))
        assertThrows(IllegalArgumentException::class.java) { DeckProtocol.brightness(101) }
    }
    @Test fun parsesQuotedFieldsAndBase64Padding() {
        val m = SatelliteProtocol.parse("KEY-STATE DEVICEID=abc KEY=3/7 BITMAP=ab== TEXT=\"a b=c\"")
        assertEquals("KEY-STATE", m.command); assertEquals("ab==", m.args["BITMAP"]); assertEquals("a b=c", m.args["TEXT"])
        assertEquals("ERROR", SatelliteProtocol.parse("ADD-DEVICE ERROR MESSAGE=\"bad device\"").status)
    }
    @Test fun keyCoordinatesStayWithinSurface() {
        assertEquals(31, SatelliteProtocol.keyIndex("3/7")); assertEquals(8, SatelliteProtocol.keyIndex("1/0"))
        for (bad in listOf("0/8", "4/0", "-1/0", "32", "1/2/3", "x")) assertNull(SatelliteProtocol.keyIndex(bad))
    }
    @Test fun handlesFragmentedFramesCrLfAndEof() {
        val reader = StringReader("PING a\r\nPONG a\n")
        assertEquals("PING a", SatelliteProtocol.readLine(reader)); assertEquals("PONG a", SatelliteProtocol.readLine(reader)); assertNull(SatelliteProtocol.readLine(reader))
        assertThrows(java.io.EOFException::class.java) { SatelliteProtocol.readLine(StringReader("partial")) }
        assertThrows(IllegalArgumentException::class.java) { SatelliteProtocol.readLine(StringReader("a".repeat(2 * 1024 * 1024 + 1))) }
    }
    @Test fun rejectsOlderBitmapProtocol() {
        assertFalse(SatelliteProtocol.supports96("1.4.0")); assertTrue(SatelliteProtocol.supports96("1.5.0")); assertTrue(SatelliteProtocol.supports96("1.15.0"))
    }
}
