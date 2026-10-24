package io.github.androidsatellite

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.*

class SatelliteClientTest {
    @Test fun registersTransfersImagesAndReportsPressRelease() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val ready = CountDownLatch(1)
            val image = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("BEGIN CompanionVersion=test ApiVersion=1.7.0\r\n"); writer.flush()
                    val registration = reader.readLine()
                    assertTrue(registration.startsWith("ADD-DEVICE DEVICEID=test-xl "))
                    assertTrue(registration.contains("BITMAPS=96"))
                    writer.write("ADD-DEVICE OK DEVICEID=test-xl\nPING check\nKEY-STATE DEVICEID=test-xl KEY=3/7 BITMAP=abc==\n"); writer.flush()
                    val expected = mutableSetOf("PONG check", "KEY-PRESS DEVICEID=test-xl KEY=31 PRESSED=1", "KEY-PRESS DEVICEID=test-xl KEY=31 PRESSED=0")
                    while (expected.isNotEmpty()) {
                        val line = reader.readLine() ?: error("Unexpected EOF")
                        if (line.startsWith("PING")) { writer.write("PONG android\n"); writer.flush() }
                        else assertTrue("Unexpected line: " + line, expected.remove(line))
                    }
                }
            }
            val client = SatelliteClient("127.0.0.1", server.localPort, "test-xl", {},
                { if (it) ready.countDown() },
                { key, data -> assertEquals(31, key); assertEquals("abc==", data); image.countDown() }, {}, {})
            try {
                client.key(0, true) // Inputs before registration must not replay later.
                client.start()
                assertTrue(ready.await(5, TimeUnit.SECONDS)); assertTrue(image.await(5, TimeUnit.SECONDS))
                client.key(31, true); client.key(31, false)
                peer.get(5, TimeUnit.SECONDS)
            } finally { client.close(); executor.shutdownNow() }
        }
    }
    @Test fun fifteenKeyCompanionSessionUsesFiveColumnsAndRoutesBottomRightKey() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val ready = CountDownLatch(1)
            val image = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    writer.write("BEGIN CompanionVersion=test ApiVersion=1.7.0\r\n"); writer.flush()
                    val registration = reader.readLine()
                    assertTrue(registration.startsWith("ADD-DEVICE DEVICEID=test-xl "))
                    assertTrue(registration.contains("BITMAPS=72"))
                    assertTrue(registration.contains("KEYS_TOTAL=15"))
                    assertTrue(registration.contains("KEYS_PER_ROW=5"))
                    writer.write("ADD-DEVICE OK DEVICEID=test-xl\nPING check\nKEY-STATE DEVICEID=test-xl KEY=2/4 BITMAP=abc==\n"); writer.flush()
                    val expected = mutableSetOf("PONG check", "KEY-PRESS DEVICEID=test-xl KEY=14 PRESSED=1", "KEY-PRESS DEVICEID=test-xl KEY=14 PRESSED=0")
                    while (expected.isNotEmpty()) {
                        val line = reader.readLine() ?: error("Unexpected EOF")
                        if (line.startsWith("PING")) { writer.write("PONG android\n"); writer.flush() }
                        else assertTrue("Unexpected line: " + line, expected.remove(line))
                    }
                }
            }
            val client = SatelliteClient("127.0.0.1", server.localPort, "test-xl", {},
                { if (it) ready.countDown() },
                { key, data -> assertEquals(14, key); assertEquals("abc==", data); image.countDown() }, {}, {}, DeckModel.MK2)
            try {
                client.key(0, true) // Inputs before registration must not replay later.
                client.start()
                assertTrue(ready.await(5, TimeUnit.SECONDS)); assertTrue(image.await(5, TimeUnit.SECONDS))
                client.key(14, true); client.key(14, false)
                peer.get(5, TimeUnit.SECONDS)
            } finally { client.close(); executor.shutdownNow() }
        }
    }
    @Test fun reconnectsAndRegistersAgainAfterServerCloses() {
        ServerSocket(0).use { server ->
            server.soTimeout = 6000
            val connections = CountDownLatch(2)
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit<Unit> {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("BEGIN ApiVersion=1.7.0\n"); writer.flush()
                        assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("ADD-DEVICE "))
                        writer.write("ADD-DEVICE OK\n"); writer.flush()
                        // Wait for the client to observe registration before ending this session.
                        Thread.sleep(100)
                    }
                }
            }
            val client = SatelliteClient("127.0.0.1", server.localPort, "test-xl", {}, { if (it) connections.countDown() }, { _, _ -> }, {}, {})
            try {
                client.start(); assertTrue(connections.await(6, TimeUnit.SECONDS)); peer.get(2, TimeUnit.SECONDS)
            } finally { client.close(); executor.shutdownNow() }
        }
    }
}
