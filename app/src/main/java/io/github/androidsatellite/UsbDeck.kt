package io.github.androidsatellite

import android.hardware.usb.*
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

/** A single thread owns requestWait: input and output completions cannot steal each other. */
class UsbDeck(
    private val manager: UsbManager,
    val device: UsbDevice,
    private val log: (String) -> Unit,
    private val onReady: (String) -> Unit,
    private val onKey: (Int, Boolean) -> Unit,
    private val onLost: () -> Unit
) : AutoCloseable {
    val model = requireNotNull(DeckModel.fromUsb(device.vendorId, device.productId)) { "Unsupported Stream Deck" }
    private val active = AtomicBoolean(true)
    private val lock = Any()
    private val images = arrayOfNulls<Bitmap>(model.keyCount)
    private var desiredBrightness: Int? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    private var worker: Thread? = null
    fun start() { worker = Thread({ run() }, "deck-usb").apply { start() } }
    fun image(key: Int, bitmap: Bitmap) = synchronized(lock) {
        if (!active.get()) { bitmap.recycle(); return@synchronized }
        require(key in 0 until model.keyCount)
        images[key]?.recycle(); images[key] = bitmap
    }
    fun brightness(value: Int) = synchronized(lock) { desiredBrightness = value.coerceIn(0, 100) }
    private fun run() {
        var input: UsbRequest? = null
        var output: UsbRequest? = null
        var iface: UsbInterface? = null
        try {
            val candidate = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { intf ->
                intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    (0 until intf.endpointCount).any { intf.getEndpoint(it).direction == UsbConstants.USB_DIR_IN }
            } ?: error("No HID input interface found")
            iface = candidate
            val endpoints = (0 until candidate.endpointCount).map { candidate.getEndpoint(it) }
            val inEp = endpoints.first { it.direction == UsbConstants.USB_DIR_IN }
            val outEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            val conn = manager.openDevice(device) ?: error("Cannot open USB device; grant USB access")
            connection = conn
            check(conn.claimInterface(candidate, true)) { "Cannot claim USB interface" }
            log("USB " + device.productId.toString(16) + "; IN " + inEp.address + "; OUT " + (outEp?.address ?: "control"))
            var identity = ""
            for (length in if (model.legacy) listOf(32, 17) else listOf(32)) {
                val serialData = ByteArray(length).apply { this[0] = model.serialReportId.toByte() }
                val received = conn.controlTransfer(0xa1, 1, 0x0300 or model.serialReportId, candidate.id, serialData, serialData.size, 1000)
                if (received > 0) identity = DeckProtocol.serial(serialData.copyOf(received), model)
                if (identity.isNotBlank()) break
            }
            if (identity.isBlank()) identity = runCatching { device.serialNumber }.getOrNull().orEmpty()
            check(identity.isNotBlank()) { "Cannot read a stable Stream Deck serial number" }
            input = UsbRequest().also { check(it.initialize(conn, inEp)) }
            output = outEp?.let { ep -> UsbRequest().also { check(it.initialize(conn, ep)) } }
            val inBuffer = ByteBuffer.allocateDirect(maxOf(512, inEp.maxPacketSize))
            check(input.queue(inBuffer)) { "Cannot queue USB input" }
            val states = BooleanArray(model.keyCount)
            var reports: Iterator<ByteArray>? = null
            var outBuffer: ByteBuffer? = null
            var outputStarted = 0L
            var nextKey = 0
            onReady(identity)
            while (active.get()) {
                val brightness = synchronized(lock) { desiredBrightness.also { desiredBrightness = null } }
                if (brightness != null) {
                    val data = DeckProtocol.brightness(brightness, model)
                    check(conn.controlTransfer(0x21, 9, 0x0300 or (data[0].toInt() and 255), candidate.id, data, data.size, 1000) == data.size) { "Brightness USB transfer failed" }
                }
                if (outBuffer == null) {
                    if (reports?.hasNext() != true) {
                        var selected: Pair<Int, Bitmap>? = null
                        synchronized(lock) {
                            for (offset in 0 until model.keyCount) {
                                val key = (nextKey + offset) % model.keyCount
                                val bitmap = images[key] ?: continue
                                images[key] = null; selected = key to bitmap; nextKey = (key + 1) % model.keyCount; break
                            }
                        }
                        selected?.let { (key, bmp) ->
                            try { reports = DeckProtocol.imageReports(key, DeckImages.encode(bmp, model), model).iterator() }
                            finally { bmp.recycle() }
                        }
                    }
                    if (reports?.hasNext() == true) {
                        val packet = reports!!.next()
                        if (output != null) {
                            outBuffer = ByteBuffer.allocateDirect(packet.size).apply { put(packet); flip() }
                            check(output.queue(outBuffer)) { "Cannot queue USB image" }
                            outputStarted = System.nanoTime()
                        } else {
                            check(conn.controlTransfer(0x21, 9, 0x0202, candidate.id, packet, packet.size, 1000) == packet.size) { "Image USB transfer failed" }
                        }
                    }
                }
                val complete = try { conn.requestWait(25) } catch (_: TimeoutException) { null }
                if (complete === input) {
                    val bytes = ByteArray(inBuffer.position())
                    inBuffer.flip(); inBuffer.get(bytes)
                    DeckProtocol.keys(bytes, model)?.forEachIndexed { key, down ->
                        if (states[key] != down) { states[key] = down; onKey(key, down) }
                    }
                    inBuffer.clear()
                    if (active.get()) check(input.queue(inBuffer)) { "USB input stopped" }
                } else if (output != null && complete === output) {
                    check(outBuffer?.position() == model.reportSize) { "Incomplete USB image transfer" }
                    outBuffer = null
                }
                if (outBuffer != null && System.nanoTime() - outputStarted > 2_000_000_000L) error("USB image transfer timed out")
            }
        } catch (e: Exception) {
            if (active.get()) log("USB: " + (e.message ?: e.javaClass.simpleName))
        } finally {
            active.set(false)
            runCatching { input?.cancel() }; runCatching { output?.cancel() }
            runCatching { input?.close() }; runCatching { output?.close() }
            runCatching { iface?.let { connection?.releaseInterface(it) } }
            runCatching { connection?.close() }; connection = null
            synchronized(lock) { images.forEach { it?.recycle() }; images.fill(null) }
            onLost()
        }
    }
    override fun close() { active.set(false); connection?.close() }
}
