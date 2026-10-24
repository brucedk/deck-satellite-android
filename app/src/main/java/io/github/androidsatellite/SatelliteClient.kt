package io.github.androidsatellite

import java.net.InetSocketAddress
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SatelliteClient(
    private val host: String,
    private val port: Int,
    private val id: String,
    private val log: (String) -> Unit,
    private val state: (Boolean) -> Unit,
    private val image: (Int, String) -> Unit,
    private val clear: () -> Unit,
    private val brightness: (Int) -> Unit,
    private val model: DeckModel = DeckModel.XL
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val gate = Any()
    private val sentKeys = BooleanArray(model.keyCount)
    private val outgoing = LinkedBlockingQueue<String>(256)
    @Volatile private var socket: Socket? = null
    @Volatile private var registered = false
    private val worker = Thread({ run() }, "satellite-reader")
    fun start() = worker.start()
    fun key(index: Int, down: Boolean) = synchronized(gate) {
        if (!registered || index !in 0 until model.keyCount) return@synchronized
        if (down || sentKeys[index]) {
            enqueue("KEY-PRESS DEVICEID=" + id + " KEY=" + index + " PRESSED=" + if (down) "1" else "0")
            sentKeys[index] = down
        }
    }
    private fun enqueue(line: String) {
        if (!outgoing.offer(line)) { log("Network output stalled; reconnecting"); socket?.close() }
    }
    private fun run() {
        var delay = 1000L
        while (active.get()) {
            var writerThread: Thread? = null
            var thisSocket: Socket? = null
            try {
                log("Connecting to " + host + ":" + port)
                val conn = Socket()
                thisSocket = conn; socket = conn
                if (!active.get()) break
                conn.connect(InetSocketAddress(host, port), 5000)
                conn.tcpNoDelay = true; conn.soTimeout = 10000
                val session = AtomicBoolean(true)
                writerThread = Thread({
                    try {
                        val writer = conn.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        while (active.get() && session.get() && !conn.isClosed) {
                            val line = outgoing.poll(2, TimeUnit.SECONDS) ?: "PING android"
                            writer.write(line); writer.write("\n"); writer.flush()
                        }
                    } catch (_: Exception) { runCatching { conn.close() } }
                }, "satellite-writer").apply { start() }
                val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
                var greeted = false
                try {
                    while (active.get()) {
                        val line = SatelliteProtocol.readLine(reader) ?: break
                        if (line.isBlank()) continue
                        if (line.startsWith("PING ") || line == "PING") { enqueue("PONG" + line.removePrefix("PING")); continue }
                        val message = SatelliteProtocol.parse(line)
                        if (message.command == "BEGIN") {
                            check(!greeted) { "Duplicate Companion greeting" }
                            val version = message.args["ApiVersion"].orEmpty()
                            check(SatelliteProtocol.supports96(version)) { "Companion Satellite API 1.5 or newer required" }
                            greeted = true
                            enqueue(SatelliteProtocol.registration(id, model))
                        } else if (message.status == "ERROR" || message.command == "ERROR") {
                            error(message.args["MESSAGE"] ?: "Companion rejected " + message.command)
                        } else if (message.command == "ADD-DEVICE" && message.status == "OK") {
                            check(greeted) { "Registration before greeting" }
                            synchronized(gate) { sentKeys.fill(false); registered = true }
                            delay = 1000; state(true); log("Companion connected: " + model.keyCount + "-key " + model.title + " surface registered")
                        } else if (registered && message.args["DEVICEID"] == id) {
                            when (message.command) {
                                "KEY-STATE" -> {
                                    val key = SatelliteProtocol.keyIndex(message.args["KEY"].orEmpty(), model)
                                    val bitmap = message.args["BITMAP"]
                                    if (key != null && bitmap != null) image(key, bitmap)
                                }
                                "KEYS-CLEAR" -> clear()
                                "BRIGHTNESS" -> message.args["VALUE"]?.toIntOrNull()?.let { brightness(it.coerceIn(0, 100)) }
                            }
                        }
                    }
                } finally { session.set(false) }
            } catch (e: Exception) {
                if (active.get()) log("Companion: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                synchronized(gate) { registered = false; sentKeys.fill(false) }
                runCatching { thisSocket?.close() }; writerThread?.interrupt()
                runCatching { writerThread?.join(1000) }
                outgoing.clear(); socket = null
                state(false)
            }
            if (active.get()) {
                log("Retrying Companion in " + delay / 1000 + " s")
                try { Thread.sleep(delay) } catch (_: InterruptedException) { }
                delay = minOf(delay * 2, 10000)
            }
        }
    }
    override fun close() {
        active.set(false); registered = false
        runCatching { socket?.close() }; worker.interrupt()
    }
}
