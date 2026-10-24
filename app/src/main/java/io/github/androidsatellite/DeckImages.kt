package io.github.androidsatellite

import android.graphics.*
import android.util.Base64
import java.io.ByteArrayOutputStream

object DeckImages {
    fun encode(bitmap: Bitmap, model: DeckModel): ByteArray {
        require(bitmap.width == model.imageSize && bitmap.height == model.imageSize)
        if (model.legacy) {
            val pixels = IntArray(72 * 72)
            bitmap.getPixels(pixels, 0, 72, 0, 0, 72, 72)
            return DeckProtocol.originalBitmap(pixels)
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, model.imageSize, model.imageSize, Matrix().apply { postRotate(180f) }, false)
        return ByteArrayOutputStream().use { out ->
            try { check(rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.toByteArray() }
            finally { if (rotated !== bitmap) rotated.recycle() }
        }
    }
    fun fromRgb(encoded: String, model: DeckModel): Bitmap {
        val size = model.imageSize
        val raw = Base64.decode(encoded, Base64.DEFAULT)
        require(raw.size == size * size * 3) { "Expected " + size + " x " + size + " RGB bitmap; got " + raw.size + " bytes" }
        val colors = IntArray(size * size) { i ->
            Color.rgb(raw[i * 3].toInt() and 255, raw[i * 3 + 1].toInt() and 255, raw[i * 3 + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
    }
    fun test(key: Int, down: Boolean = false, model: DeckModel = DeckModel.XL): Bitmap {
        val bmp = Bitmap.createBitmap(model.imageSize, model.imageSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { scale(model.imageSize / 96f, model.imageSize / 96f) }
        c.drawColor(if (down) Color.rgb(0, 150, 100) else Color.rgb(18, 35 + key / model.columns * 14, 60))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 38f; typeface = Typeface.DEFAULT_BOLD }
        c.drawText((key + 1).toString(), 48f, 55f, p)
        p.textSize = 12f
        c.drawText(if (down) "PRESSED" else "R" + (key / model.columns + 1) + "  C" + (key % model.columns + 1), 48f, 78f, p)
        p.color = Color.CYAN
        c.drawRect(5f, 5f, 16f, 10f, p) // top-left marker confirms rotation
        return bmp
    }
    fun blank(model: DeckModel = DeckModel.XL): Bitmap = Bitmap.createBitmap(model.imageSize, model.imageSize, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
}
