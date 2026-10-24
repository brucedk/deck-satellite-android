package io.github.androidsatellite

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeckModelTest {
    @Test fun detectsAllSupportedModelsAndRejectsOtherDevices() {
        for (model in DeckModel.entries) assertEquals(model, DeckModel.fromUsb(0x0fd9, model.productId))
        assertNull(DeckModel.fromUsb(0x1234, 0x0080))
        assertNull(DeckModel.fromUsb(0x0fd9, 0x0084)) // Stream Deck + has a different layout.
        for (pid in listOf(0x0060, 0x006d, 0x0080, 0x00a5)) {
            val model = DeckModel.fromUsb(0x0fd9, pid)!!
            assertEquals(15, model.keyCount); assertEquals(5, model.columns); assertEquals(72, model.imageSize)
        }
    }
    @Test fun modernFifteenKeyInputAndOutputStayInRange() {
        for (model in listOf(DeckModel.STANDARD, DeckModel.MK2, DeckModel.MK2_SCISSOR)) {
            val report = ByteArray(19).apply { this[0] = 1; this[2] = 15; this[4] = 1; this[18] = 1 }
            val keys = DeckProtocol.keys(report, model)!!
            assertEquals(15, keys.size); assertTrue(keys[0]); assertTrue(keys[14]); assertEquals(2, keys.count { it })
            assertNull(DeckProtocol.keys(report.copyOf(18), model))
            assertNull(DeckProtocol.keys(report.copyOf().apply { this[2] = 32 }, model))
            val chunks = DeckProtocol.imageReports(14, ByteArray(1500), model)
            assertEquals(2, chunks.size); assertEquals(14, chunks[0][2].toInt())
            assertEquals(1, chunks[1][3].toInt())
            assertThrows(IllegalArgumentException::class.java) { DeckProtocol.imageReports(15, byteArrayOf(1), model) }
        }
    }
    @Test fun originalInputMirrorsEachRowAndPreservesRowOrder() {
        val model = DeckModel.ORIGINAL
        assertEquals(listOf(4,3,2,1,0,9,8,7,6,5,14,13,12,11,10), (0..14).map { model.hidKey(it) })
        for (logical in 0..14) {
            val report = ByteArray(16).apply { this[0] = 1; this[1 + model.hidKey(logical)] = 1 }
            val keys = DeckProtocol.keys(report, model)!!
            assertTrue(keys[logical]); assertEquals(1, keys.count { it })
        }
        assertNull(DeckProtocol.keys(ByteArray(15), model))
    }
    @Test fun originalBmpHasExpectedHeaderAndPixelOrientation() {
        val pixels = IntArray(72 * 72)
        pixels[0] = 0xff112233.toInt(); pixels[71] = 0xffaabbcc.toInt(); pixels[71 * 72] = 0xff445566.toInt()
        val bmp = DeckProtocol.originalBitmap(pixels)
        val h = ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(15606, bmp.size); assertEquals(15606, h.getInt(2)); assertEquals(54, h.getInt(10))
        assertEquals(72, h.getInt(18)); assertEquals(72, h.getInt(22)); assertEquals(24, h.getShort(28).toInt())
        assertArrayEquals(byteArrayOf(0xcc.toByte(),0xbb.toByte(),0xaa.toByte()), bmp.copyOfRange(54,57))
        assertArrayEquals(byteArrayOf(0x33,0x22,0x11), bmp.copyOfRange(54+71*3,57+71*3))
        assertArrayEquals(byteArrayOf(0x66,0x55,0x44), bmp.takeLast(3).toByteArray())
    }
    @Test fun originalTwoPacketsContainFullBmpAndCorrectOneBasedKey() {
        val bmp = DeckProtocol.originalBitmap(IntArray(72 * 72) { it })
        for (key in listOf(0,4,5,14)) {
            val packets = DeckProtocol.imageReports(key,bmp,DeckModel.ORIGINAL)
            assertEquals(2, packets.size)
            packets.forEachIndexed { i, packet ->
                assertEquals(8191, packet.size); assertEquals(2, packet[0].toInt()); assertEquals(1, packet[1].toInt())
                assertEquals(i+1,packet[2].toInt()); assertEquals(i,packet[4].toInt())
                assertEquals(DeckModel.ORIGINAL.hidKey(key)+1,packet[5].toInt())
                assertTrue(packet.drop(16+7803).all { it == 0.toByte() })
            }
            assertArrayEquals(bmp,packets[0].copyOfRange(16,7819)+packets[1].copyOfRange(16,7819))
        }
    }
    @Test fun legacyBrightnessAndSerialReportsDifferFromModern() {
        assertArrayEquals(byteArrayOf(5,0x55,0xaa.toByte(),0xd1.toByte(),1,75)+ByteArray(11),DeckProtocol.brightness(75,DeckModel.ORIGINAL))
        val legacy = ByteArray(32).apply { this[0]=3; "AB1234567890".toByteArray().copyInto(this,5) }
        val modern = ByteArray(32).apply { this[0]=6; this[1]=12; "AB1234567890".toByteArray().copyInto(this,2) }
        assertEquals("AB1234567890",DeckProtocol.serial(legacy,DeckModel.ORIGINAL))
        assertEquals("AB1234567890",DeckProtocol.serial(legacy.copyOf(17),DeckModel.ORIGINAL))
        assertEquals("AB1234567890",DeckProtocol.serial(modern,DeckModel.MK2))
        assertEquals("",DeckProtocol.serial(legacy,DeckModel.MK2))
    }
    @Test fun companionUsesFiveColumnsAnd72PixelImages() {
        for (model in DeckModel.entries) {
            val msg=SatelliteProtocol.parse(SatelliteProtocol.registration("test",model))
            assertEquals(model.keyCount.toString(),msg.args["KEYS_TOTAL"])
            assertEquals(model.columns.toString(),msg.args["KEYS_PER_ROW"])
            assertEquals(model.imageSize.toString(),msg.args["BITMAPS"])
            assertEquals(model.keyCount-1,SatelliteProtocol.keyIndex((model.rows-1).toString()+"/"+(model.columns-1),model))
        }
        assertEquals(14,SatelliteProtocol.keyIndex("2/4",DeckModel.MK2))
        assertEquals(5,SatelliteProtocol.keyIndex("1/0",DeckModel.MK2))
        for (bad in listOf("0/5","3/0","15","-1")) assertNull(SatelliteProtocol.keyIndex(bad,DeckModel.MK2))
    }
}
