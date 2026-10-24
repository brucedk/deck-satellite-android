package io.github.androidsatellite

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.net.wifi.WifiManager
import android.os.*
import java.text.SimpleDateFormat
import java.util.*

object AppState {
    var running = false
    var usb = "Not connected"
    var network = "Stopped"
    var serial = ""
    var model = DeckModel.STANDARD
    var pressed = BooleanArray(model.keyCount)
    val lines = ArrayDeque<String>()
    val listeners = mutableSetOf<() -> Unit>()
    fun changed() { listeners.toList().forEach { it() } }
    fun log(text: String) {
        lines.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + text)
        while (lines.size > 120) lines.removeFirst()
        changed()
    }
}

class BridgeService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@BridgeService }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private var deck: UsbDeck? = null
    private var client: SatelliteClient? = null
    private var bridgeWanted = false
    private var active = false
    private var host = ""
    private var port = 16622
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val permissionAction get() = packageName + ".USB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION") val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                permissionAction -> if (active && device != null) {
                    if (usb.hasPermission(device)) open(device)
                    else { AppState.usb = "USB permission denied — tap Start USB to retry"; log(AppState.usb) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (active) scan(false)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (device?.deviceName == deck?.device?.deviceName) {
                    closeDeck(); AppState.usb = "Stream Deck unplugged — waiting"; log(AppState.usb)
                }
            }
        }
    }
    private val rescan = object : Runnable {
        override fun run() {
            if (!active) return
            if (deck == null) scan(false)
            handler.postDelayed(this, 2000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(UsbManager::class.java)
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("bridge", "Satellite connection", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stop(); return START_NOT_STICKY }
        if (!active) {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, "bridge")
                .setSmallIcon(R.drawable.ic_satellite).setContentTitle("Deck Satellite is running")
                .setContentText("USB and Companion connection active; tap to view status")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            active = true; AppState.running = true
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeckSatellite:Bridge").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DeckSatellite:WiFi").apply { acquire() }
            handler.post(rescan)
        }
        scan(true)
        return START_NOT_STICKY
    }
    private fun log(text: String) { AppState.log(text) }
    private fun postLog(text: String) { handler.post { log(text) } }
    private fun scan(requestPermission: Boolean) {
        if (deck != null) return
        val devices = usb.deviceList.values.filter { DeckModel.fromUsb(it.vendorId, it.productId) != null }
        if (requestPermission) {
            val inventory = usb.deviceList.values.joinToString { "VID=" + it.vendorId.toString(16) + " PID=" + it.productId.toString(16) }
            log("USB devices: " + inventory.ifBlank { "none detected" })
        }
        val device = devices.firstOrNull()
        if (device == null) {
            if (AppState.usb != "Waiting for a supported Stream Deck") { AppState.usb = "Waiting for a supported Stream Deck"; log(AppState.usb) }
            return
        }
        if (usb.hasPermission(device)) open(device)
        else if (requestPermission) {
            AppState.usb = "Allow USB access in the Android dialog"; log(AppState.usb)
            val pending = PendingIntent.getBroadcast(this, 2, Intent(permissionAction).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            usb.requestPermission(device, pending)
        } else if (!AppState.usb.startsWith("USB permission") && !AppState.usb.startsWith("Allow USB")) {
            AppState.usb = "USB permission needed — tap Start USB"; log(AppState.usb)
        }
    }
    private fun open(device: UsbDevice) {
        if (!active || deck != null) return
        AppState.usb = "Opening Stream Deck…"; AppState.changed()
        lateinit var created: UsbDeck
        created = UsbDeck(usb, device, ::postLog,
            { serial -> handler.post {
                if (deck !== created) return@post
                AppState.serial = serial; AppState.usb = created.model.title + " connected"
                log(created.model.title + " serial: " + serial); created.brightness(50)
                if (bridgeWanted) connectClient() else pattern()
            } },
            { key, down -> handler.post {
                if (deck !== created) return@post
                AppState.pressed[key] = down
                log("Key " + (key + 1) + if (down) " down" else " up")
                if (bridgeWanted) client?.key(key, down) else created.image(key, DeckImages.test(key, down, created.model))
            } },
            { handler.post {
                if (deck !== created) return@post
                closeDeck(); AppState.usb = "USB disconnected; retrying"; log(AppState.usb)
            } })
        AppState.model = created.model; AppState.pressed = BooleanArray(created.model.keyCount)
        deck = created; AppState.changed(); created.start()
    }
    fun connect(hostText: String, portNumber: Int) {
        check(active) { "Start USB first" }
        require(hostText.isNotBlank() && !hostText.any { it.isWhitespace() } && portNumber in 1..65535) { "Enter a host/IP and a valid port" }
        host = hostText; port = portNumber; bridgeWanted = true
        if (deck != null && AppState.serial.isNotBlank()) connectClient()
        else { AppState.network = "Waiting for USB"; AppState.changed() }
    }
    private fun connectClient() {
        disconnectClient()
        val currentDeck = deck ?: return
        val id = (if (currentDeck.model.keyCount == 32) "android-xl-" else "android-deck-") + AppState.serial.replace(Regex("[^A-Za-z0-9_-]"), "-")
        lateinit var created: SatelliteClient
        created = SatelliteClient(host, port, id, ::postLog,
            { connected -> handler.post {
                if (client !== created) return@post
                AppState.network = if (connected) "Connected to " + host else "Disconnected — retrying"
                if (!connected) clearDeck()
                AppState.changed()
            } },
            { key, encoded ->
                // USB mailbox keeps only the newest pending image per key.
                currentDeck.image(key, DeckImages.fromRgb(encoded, currentDeck.model))
            },
            { for (i in 0 until currentDeck.model.keyCount) currentDeck.image(i, DeckImages.blank(currentDeck.model)) },
            { value -> currentDeck.brightness(value) }, currentDeck.model)
        clearDeck()
        client = created; AppState.network = "Connecting to " + host; AppState.changed(); created.start()
    }
    fun testMode() {
        bridgeWanted = false; disconnectClient(); AppState.network = "USB test mode"; pattern()
    }
    fun pattern() {
        if (bridgeWanted) return
        val current = deck ?: return
        for (key in 0 until current.model.keyCount) current.image(key, DeckImages.test(key, AppState.pressed[key], current.model))
        log("Numbered test pattern queued; cyan marker belongs at top left")
    }
    fun brightness(value: Int) { deck?.brightness(value) }
    private fun clearDeck() {
        val current = deck ?: return
        for (i in 0 until current.model.keyCount) current.image(i, DeckImages.blank(current.model))
    }
    private fun disconnectClient() { val old = client; client = null; old?.close() }
    private fun closeDeck() {
        disconnectClient(); val old = deck; deck = null; old?.close()
        AppState.serial = ""; AppState.pressed.fill(false)
        AppState.network = if (bridgeWanted) "Waiting for USB" else "USB test mode"
    }
    fun stop() {
        active = false; bridgeWanted = false; handler.removeCallbacks(rescan)
        closeDeck(); wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
        AppState.running = false; AppState.usb = "Stopped"; AppState.network = "Stopped"; AppState.changed()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { stop(); unregisterReceiver(receiver); super.onDestroy() }
}
