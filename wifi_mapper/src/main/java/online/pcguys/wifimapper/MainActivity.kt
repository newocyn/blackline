package online.pcguys.wifimapper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var wifi: WifiManager
    private lateinit var connectionName: TextView
    private lateinit var signalValue: TextView
    private lateinit var signalQuality: TextView
    private lateinit var gatewayPing: TextView
    private lateinit var internetPing: TextView
    private lateinit var channelInfo: TextView
    private lateinit var statusText: TextView
    private lateinit var nearbyContainer: LinearLayout
    private lateinit var graph: SignalGraphView
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var scans = emptyList<ScanResult>()
    private var monitoring = true
    private var tickCount = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestScan(); updateNow()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadScanResults()
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (monitoring) {
                updateNow(); tickCount++
                if (tickCount % 8 == 0) requestScan()
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        buildUi()
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        requestPermissions()
        handler.post(ticker)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(30)) }
        body.addView(label("PCG WIFI ANALYZER", 26f, Color.WHITE).apply { setTypeface(typeface, 1) })
        body.addView(label("Live signal, latency, channel and nearby access-point analysis", 14f, 0xFF9A9A9A.toInt()).apply { setPadding(0, dp(4), 0, dp(18)) })

        val hero = card()
        connectionName = label("Connected network", 14f, 0xFFAAAAAA.toInt())
        signalValue = label("-- dBm", 38f, Color.WHITE).apply { setTypeface(typeface, 1) }
        signalQuality = label("Waiting for Wi-Fi data", 16f, Color.WHITE)
        hero.addView(connectionName); hero.addView(signalValue); hero.addView(signalQuality)
        body.addView(hero)

        graph = SignalGraphView(this)
        body.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(12) })

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gatewayPing = metricCard("ROUTER", "-- ms")
        internetPing = metricCard("INTERNET", "-- ms")
        metrics.addView(gatewayPing.parent as View, LinearLayout.LayoutParams(0, dp(110), 1f).apply { marginEnd = dp(6) })
        metrics.addView(internetPing.parent as View, LinearLayout.LayoutParams(0, dp(110), 1f).apply { marginStart = dp(6) })
        body.addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val info = card()
        channelInfo = label("Channel information unavailable", 16f, Color.WHITE)
        statusText = label("Starting live monitor…", 13f, 0xFFAAAAAA.toInt())
        info.addView(channelInfo)
        info.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        body.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = button("REFRESH NOW")
        val toggle = button("PAUSE")
        controls.addView(refresh, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        controls.addView(toggle, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        body.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        body.addView(label("NEARBY ACCESS POINTS", 15f, Color.WHITE).apply { setTypeface(typeface, 1); setPadding(0, dp(24), 0, dp(10)) })
        nearbyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(nearbyContainer)
        refresh.setOnClickListener { requestScan(); updateNow() }
        toggle.setOnClickListener {
            monitoring = !monitoring
            toggle.text = if (monitoring) "PAUSE" else "RESUME"
            statusText.text = if (monitoring) "Live monitoring active" else "Live monitoring paused"
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun metricCard(title: String, initial: String): TextView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(12), dp(14), dp(12), dp(14)); setBackgroundColor(0xFF141414.toInt()) }
        box.addView(label(title, 12f, 0xFF999999.toInt()))
        val value = label(initial, 25f, Color.WHITE).apply { setTypeface(typeface, 1); gravity = Gravity.CENTER }
        box.addView(value)
        return value
    }

    private fun updateNow() {
        val info = try { wifi.connectionInfo } catch (_: SecurityException) { null }
        val rssi = info?.rssi ?: -127
        val ssid = info?.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" } ?: "Not connected"
        val freq = info?.frequency ?: 0
        connectionName.text = ssid
        signalValue.text = if (rssi <= -126) "-- dBm" else "$rssi dBm"
        signalQuality.text = qualityText(rssi)
        if (rssi > -126) graph.add(rssi)
        channelInfo.text = if (freq > 0) "${band(freq)} • Channel ${channel(freq)} • ${freq} MHz • Link ${info?.linkSpeed ?: 0} Mbps" else "No active Wi-Fi connection"
        statusText.text = "Live monitor: 2-second signal updates • periodic nearby scans"
        runPings()
    }

    private fun runPings() {
        worker.execute {
            val gateway = gatewayAddress()
            val routerMs = gateway?.let { tcpPing(it, 53, 900) ?: tcpPing(it, 80, 900) }
            val internetMs = tcpPing("1.1.1.1", 443, 1200)
            runOnUiThread {
                gatewayPing.text = routerMs?.let { "$it ms" } ?: "Offline"
                internetPing.text = internetMs?.let { "$it ms" } ?: "Offline"
            }
        }
    }

    private fun tcpPing(host: String, port: Int, timeout: Int): Long? = try {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        (System.nanoTime() - start) / 1_000_000
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun gatewayAddress(): String? {
        val value = wifi.dhcpInfo?.gateway ?: return null
        if (value == 0) return null
        return listOf(value and 0xff, value shr 8 and 0xff, value shr 16 and 0xff, value shr 24 and 0xff).joinToString(".")
    }

    private fun requestPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionLauncher.launch(p.toTypedArray())
    }
    private fun requestScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try { if (!wifi.startScan()) loadScanResults() } catch (_: SecurityException) { }
    }
    private fun loadScanResults() {
        try {
            scans = wifi.scanResults.filter { it.SSID.isNotBlank() }.sortedByDescending { it.level }
            renderNearby()
        } catch (_: SecurityException) { }
    }
    private fun renderNearby() {
        nearbyContainer.removeAllViews()
        if (scans.isEmpty()) {
            nearbyContainer.addView(label("No results yet. Ensure Wi-Fi and Location are enabled.", 14f, 0xFFAAAAAA.toInt()))
            return
        }
        scans.take(25).forEach { ap ->
            val row = card()
            row.addView(label(ap.SSID, 17f, Color.WHITE).apply { setTypeface(typeface, 1) })
            row.addView(label("${ap.level} dBm • ${qualityText(ap.level)}", 14f, 0xFFDDDDDD.toInt()))
            row.addView(label("${band(ap.frequency)} • Channel ${channel(ap.frequency)} • ${security(ap.capabilities)}\nBSSID ${ap.BSSID}", 12f, 0xFF8E8E8E.toInt()))
            nearbyContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun qualityText(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Very good"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        rssi > -126 -> "Weak"
        else -> "Unavailable"
    }
    private fun band(freq: Int): String = when { freq >= 5925 -> "6 GHz"; freq >= 4900 -> "5 GHz"; else -> "2.4 GHz" }
    private fun channel(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5000..5895 -> (freq - 5000) / 5
        freq >= 5955 -> (freq - 5950) / 5
        else -> 0
    }
    private fun security(c: String): String = when {
        c.contains("SAE") || c.contains("WPA3") -> "WPA3"
        c.contains("WPA2") -> "WPA2"
        c.contains("WPA") -> "WPA"
        c.contains("WEP") -> "WEP"
        else -> "Open"
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundColor(0xFF141414.toInt()) }
    private fun label(t: String, s: Float, c: Int) = TextView(this).apply { text = t; textSize = s; setTextColor(c) }
    private fun button(t: String) = Button(this).apply { text = t; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class SignalGraphView(context: Context) : View(context) {
        private val values = ArrayDeque<Int>()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); strokeWidth = dp(1).toFloat() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF999999.toInt(); textSize = dp(11).toFloat() }
        init { setBackgroundColor(0xFF101010.toInt()) }
        fun add(rssi: Int) { values.addLast(rssi.coerceIn(-100, -30)); while (values.size > 60) values.removeFirst(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(38).toFloat(); val right = width - dp(12).toFloat(); val top = dp(14).toFloat(); val bottom = height - dp(24).toFloat()
            listOf(-30, -50, -70, -90).forEach { db ->
                val y = top + ((-30 - db) / 70f) * (bottom - top)
                canvas.drawLine(left, y, right, y, grid); canvas.drawText("$db", dp(6).toFloat(), y + dp(4), textPaint)
            }
            if (values.size < 2) { canvas.drawText("Live signal history", left, height / 2f, textPaint); return }
            val list = values.toList(); val step = (right - left) / max(1, list.size - 1)
            for (i in 1 until list.size) {
                val x1 = left + step * (i - 1); val x2 = left + step * i
                val y1 = top + ((-30 - list[i - 1]) / 70f) * (bottom - top)
                val y2 = top + ((-30 - list[i]) / 70f) * (bottom - top)
                canvas.drawLine(x1, y1, x2, y2, line)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy(); handler.removeCallbacks(ticker)
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        worker.shutdownNow()
    }
}
