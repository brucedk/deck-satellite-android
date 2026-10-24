package io.github.androidsatellite

/** Layout and HID protocol selected from the actual USB product ID. */
enum class DeckModel(val productId: Int, val title: String, val columns: Int, val rows: Int, val imageSize: Int, val legacy: Boolean = false) {
    ORIGINAL(0x0060, "Stream Deck Original", 5, 3, 72, true),
    STANDARD(0x006d, "Stream Deck 2019", 5, 3, 72),
    MK2(0x0080, "Stream Deck MK.2", 5, 3, 72),
    MK2_SCISSOR(0x00a5, "Stream Deck MK.2 Scissor", 5, 3, 72),
    XL(0x006c, "Stream Deck XL", 8, 4, 96),
    XL_2022(0x008f, "Stream Deck XL 2022", 8, 4, 96);

    val keyCount get() = columns * rows
    val reportSize get() = if (legacy) 8191 else 1024
    val serialReportId get() = if (legacy) 3 else 6
    fun hidKey(logicalKey: Int): Int {
        require(logicalKey in 0 until keyCount)
        return if (legacy) logicalKey / columns * columns + columns - 1 - logicalKey % columns else logicalKey
    }
    companion object {
        const val VENDOR = 0x0fd9
        fun fromUsb(vendor: Int, product: Int): DeckModel? =
            if (vendor == VENDOR) entries.firstOrNull { it.productId == product } else null
    }
}
