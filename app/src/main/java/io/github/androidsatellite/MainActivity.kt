package io.github.androidsatellite

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private var service: BridgeService? = null
    private var bound = false
    private lateinit var usbStatus: TextView
    private lateinit var netStatus: TextView
    private lateinit var logView: TextView
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var grid: GridLayout
    private val keys = mutableListOf<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private var updatePending = false
    private val listener: () -> Unit = {
        if (!updatePending) {
            updatePending = true
            handler.postDelayed({ updatePending = false; render() }, 80)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as BridgeService.LocalBinder).service; render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(225, 233, 242)); setPadding(0, dp(6), 0, dp(6))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 19, 29)
        window.navigationBarColor = Color.rgb(13, 19, 29)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(13, 19, 29)); isFillViewport = true }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(32)) }
        scroll.addView(layout); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            layout.setPadding(dp(24) + insets.systemWindowInsetLeft, dp(24) + insets.systemWindowInsetTop,
                dp(24) + insets.systemWindowInsetRight, dp(32) + insets.systemWindowInsetBottom)
            insets
        }
        layout.addView(label("Deck Satellite", 30f))
        layout.addView(label("15-key Stream Deck + XL · Android USB bridge", 16f))
        layout.addView(label("1  Connect your Stream Deck", 21f))
        usbStatus = label(""); layout.addView(usbStatus)
        layout.addView(label("Connect the Stream Deck through a USB data cable or powered hub, then allow USB access."))
        val usbButtons = LinearLayout(this)
        button(usbButtons, "Start USB") {
            startForegroundService(Intent(this, BridgeService::class.java))
        }
        button(usbButtons, "USB test mode") { service?.testMode() }
        layout.addView(usbButtons)
        grid = GridLayout(this)
        layout.addView(grid)
        layout.addView(label("The grid follows the connected deck. USB test mode numbers every key from left to right."))
        layout.addView(label("Deck brightness"))
        layout.addView(SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) service?.brightness(value) }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        })
        layout.addView(label("2  Connect to Companion", 21f))
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        host = EditText(this).apply {
            hint = "Companion computer IP or hostname"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("host", ""))
        }
        port = EditText(this).apply {
            hint = "Satellite TCP port"; setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", 16622).toString())
        }
        layout.addView(host); layout.addView(port)
        val networkButtons = LinearLayout(this)
        button(networkButtons, "Connect") {
            val address = host.text.toString().trim()
            val portNumber = port.text.toString().toIntOrNull() ?: 0
            try {
                val current = service ?: error("Service is starting; try again")
                current.connect(address, portNumber)
                prefs.edit().putString("host", address).putInt("port", portNumber).apply()
            } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
        }
        button(networkButtons, "Stop everything") { service?.stop() }
        layout.addView(networkButtons)
        netStatus = label(""); layout.addView(netStatus)
        layout.addView(label("Enable Satellite API in Companion and use its TCP port (normally 16622). Both devices must be on a reachable network."))
        layout.addView(label("Diagnostics", 21f))
        val logButtons = LinearLayout(this)
        button(logButtons, "Share log") {
            val text = "Deck Satellite 0.2.0\nAndroid " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + " " + Build.MODEL +
                "\nUSB: " + AppState.usb + "\nNetwork: " + AppState.network + "\n\n" + AppState.lines.joinToString("\n")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostic log"))
        }
        layout.addView(logButtons)
        logView = label("", 12f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        layout.addView(logView)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    override fun onStart() {
        super.onStart(); AppState.listeners.add(listener)
        bound = bindService(Intent(this, BridgeService::class.java), connection, BIND_AUTO_CREATE)
        render()
    }
    override fun onStop() {
        AppState.listeners.remove(listener); handler.removeCallbacksAndMessages(null); updatePending = false
        if (bound) unbindService(connection)
        bound = false; service = null; super.onStop()
    }
    private fun render() {
        val model = AppState.model
        if (keys.size != model.keyCount || grid.columnCount != model.columns) {
            grid.removeAllViews(); keys.clear()
            grid.columnCount = model.columns; grid.rowCount = model.rows
            for (i in 0 until model.keyCount) {
                val cell = label((i + 1).toString(), 17f).apply {
                    gravity = Gravity.CENTER; contentDescription = "Physical key " + (i + 1)
                }
                grid.addView(cell, GridLayout.LayoutParams().apply {
                    width = 0; height = dp(46)
                    rowSpec = GridLayout.spec(i / model.columns)
                    columnSpec = GridLayout.spec(i % model.columns, 1f)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }); keys.add(cell)
            }
        }
        usbStatus.text = AppState.usb + if (AppState.serial.isNotBlank()) " · " + AppState.serial else ""
        netStatus.text = AppState.network
        keys.forEachIndexed { i, cell -> cell.setBackgroundColor(if (AppState.pressed[i]) Color.rgb(0, 145, 100) else Color.rgb(28, 43, 61)) }
        logView.text = AppState.lines.toList().takeLast(45).reversed().joinToString("\n")
    }
}
