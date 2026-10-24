package io.github.androidsatellite

import java.io.Reader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Legacy HID behavior adapted from node-elgato-stream-deck; see THIRD-PARTY-NOTICES.txt. */
object DeckProtocol {
    fun imageReports(key: Int, image: ByteArray, model: DeckModel = DeckModel.XL): List<ByteArray> {
        require(key in 0 until model.keyCount)
        require(image.isNotEmpty() && image.size <= 1024 * 1024)
        if (model.legacy) {
            require(image.size == 54 + 72 * 72 * 3) { "Original Stream Deck needs a 72x72 BMP" }
            val half = image.size / 2
            return (0..1).map { page -> ByteArray(model.reportSize).apply {
                this[0] = 2; this[1] = 1; this[2] = (page + 1).toByte()
                this[4] = page.toByte(); this[5] = (model.hidKey(key) + 1).toByte()
                image.copyInto(this, 16, page * half, (page + 1) * half)
            } }
        }
        return image.asList().chunked(1016).mapIndexed { page, chunk ->
            ByteArray(model.reportSize).apply {
                this[0] = 2; this[1] = 7; this[2] = model.hidKey(key).toByte()
                this[3] = if ((page + 1) * 1016 >= image.size) 1 else 0
                this[4] = chunk.size.toByte(); this[5] = (chunk.size ushr 8).toByte()
                this[6] = page.toByte(); this[7] = (page ushr 8).toByte()
                chunk.forEachIndexed { i, b -> this[i + 8] = b }
            }
        }
    }
    fun brightness(value: Int, model: DeckModel = DeckModel.XL): ByteArray {
        require(value in 0..100)
        return if (model.legacy) ByteArray(17).apply {
            this[0] = 5; this[1] = 0x55; this[2] = 0xaa.toByte(); this[3] = 0xd1.toByte(); this[4] = 1; this[5] = value.toByte()
        } else ByteArray(32).apply { this[0] = 3; this[1] = 8; this[2] = value.toByte() }
    }
    fun keys(report: ByteArray, model: DeckModel = DeckModel.XL): BooleanArray? {
        val offset = if (model.legacy) 1 else 4
        if (report.size < offset + model.keyCount || report[0] != 1.toByte()) return null
        if (!model.legacy && (report[1] != 0.toByte() ||
                (report[2].toInt() and 255) + ((report[3].toInt() and 255) shl 8) != model.keyCount)) return null
        return BooleanArray(model.keyCount) { report[offset + model.hidKey(it)] != 0.toByte() }
    }
    fun serial(report: ByteArray, model: DeckModel): String {
        val offset = if (model.legacy) 5 else 2
        if (report.size <= offset || (report[0].toInt() and 255) != model.serialReportId) return ""
        val limit = if (model.legacy) report.size else minOf(report.size, offset + (report[1].toInt() and 255))
        var end = offset
        while (end < limit && (report[end].toInt() and 255) in 0x20..0x7e) end++
        return String(report, offset, end - offset, Charsets.US_ASCII).trim()
    }
    /** The original firmware expects positive-height BMP rows with horizontally mirrored BGR pixels. */
    fun originalBitmap(argb: IntArray): ByteArray {
        require(argb.size == 72 * 72)
        val data = ByteArray(54 + argb.size * 3)
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        header.put(0, 0x42); header.put(1, 0x4d); header.putInt(2, data.size)
        header.putInt(10, 54); header.putInt(14, 40); header.putInt(18, 72); header.putInt(22, 72)
        header.putShort(26, 1); header.putShort(28, 24); header.putInt(34, argb.size * 3)
        header.putInt(38, 3780); header.putInt(42, 3780)
        for (y in 0 until 72) for (x in 0 until 72) {
            val color = argb[y * 72 + 71 - x]
            val pos = 54 + (y * 72 + x) * 3
            data[pos] = color.toByte(); data[pos + 1] = (color ushr 8).toByte(); data[pos + 2] = (color ushr 16).toByte()
        }
        return data
    }
}

data class Message(val command: String, val args: Map<String, String>, val status: String?)
object SatelliteProtocol {
    fun parse(line: String): Message {
        require(line.length <= 2 * 1024 * 1024) { "Satellite message too large" }
        val tokens = Regex("[^\\s=]+=(?:\"[^\"]*\"|[^\\s]*)|[^\\s]+").findAll(line).map { it.value }.toList()
        require(tokens.isNotEmpty()) { "Empty message" }
        val args = mutableMapOf<String, String>()
        var status: String? = null
        for (token in tokens.drop(1)) {
            val eq = token.indexOf('=')
            if (eq > 0) args[token.substring(0, eq)] = token.substring(eq + 1).removeSurrounding("\"")
            else if (token == "OK" || token == "ERROR") status = token
        }
        return Message(tokens[0], args, status)
    }
    fun keyIndex(value: String, model: DeckModel = DeckModel.XL): Int? {
        val index = if ('/' in value) {
            val parts = value.split('/')
            if (parts.size != 2) return null
            val row = parts[0].toIntOrNull() ?: return null
            val col = parts[1].toIntOrNull() ?: return null
            if (row !in 0 until model.rows || col !in 0 until model.columns) return null
            row * model.columns + col
        } else value.toIntOrNull() ?: return null
        return index.takeIf { it in 0 until model.keyCount }
    }
    fun supports96(version: String): Boolean {
        val p = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return p.getOrElse(0) { 0 } > 1 || (p.getOrElse(0) { 0 } == 1 && p.getOrElse(1) { 0 } >= 5)
    }
    fun registration(id: String, model: DeckModel = DeckModel.XL) = "ADD-DEVICE DEVICEID=" + id +
        " PRODUCT_NAME=\"Android " + model.title + "\" KEYS_TOTAL=" + model.keyCount +
        " KEYS_PER_ROW=" + model.columns + " BITMAPS=" + model.imageSize + " COLORS=0 TEXT=0"
    // Reader.readLine() has no upper bound; a broken peer must not exhaust tablet memory.
    fun readLine(reader: Reader): String? {
        val result = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (result.isEmpty()) null else throw java.io.EOFException("Incomplete line")
            if (c == 10) return result.toString().removeSuffix("\r")
            require(result.length < 2 * 1024 * 1024) { "Satellite line exceeds 2 MB" }
            result.append(c.toChar())
        }
    }
}
