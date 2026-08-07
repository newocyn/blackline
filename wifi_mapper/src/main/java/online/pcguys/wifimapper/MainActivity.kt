package online.pcguys.wifimapper

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var wifi: WifiManager
    private lateinit var status: TextView
    private lateinit var selectedNetwork: TextView
    private lateinit var networkSpinner: Spinner
    private lateinit var mapView: HeatMapView
    private lateinit var networkList: ListView
    private val latest = mutableListOf<ScanResult>()
    private var selectedSsid: String? = null
    private var pendingPoint: Pair<Float, Float>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasScanPermission()) scan()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        buildUi()
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        restoreProject()
        requestPermissionsAndScan()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "PCG WIFI MAPPER"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, 1)
        })
        header.addView(TextView(this).apply {
            text = "Walk the space. Tap your position. Build a live signal map."
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
        })
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        val mapButton = pill("MAP")
        val networksButton = pill("NETWORKS")
        val infoButton = pill("GUIDE")
        tabs.addView(mapButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        tabs.addView(networksButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        tabs.addView(infoButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        val pages = FrameLayout(this)
        val mapPage = buildMapPage()
        val networkPage = buildNetworkPage()
        val guidePage = buildGuidePage()
        pages.addView(mapPage)
        pages.addView(networkPage)
        pages.addView(guidePage)
        networkPage.visibility = View.GONE
        guidePage.visibility = View.GONE
        root.addView(pages, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        mapButton.setOnClickListener { mapPage.visibility = View.VISIBLE; networkPage.visibility = View.GONE; guidePage.visibility = View.GONE }
        networksButton.setOnClickListener { mapPage.visibility = View.GONE; networkPage.visibility = View.VISIBLE; guidePage.visibility = View.GONE }
        infoButton.setOnClickListener { mapPage.visibility = View.GONE; networkPage.visibility = View.GONE; guidePage.visibility = View.VISIBLE }

        setContentView(root)
    }

    private fun buildMapPage(): View {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(22))
        }

        val card = card()
        selectedNetwork = TextView(this).apply {
            text = "Selected network: none"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, 1)
        }
        status = TextView(this).apply {
            text = "Waiting for Wi-Fi scan…"
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 14f
            setPadding(0, dp(5), 0, dp(12))
        }
        networkSpinner = Spinner(this)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scan = outlined("SCAN")
        val clear = outlined("CLEAR MAP")
        controls.addView(scan, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        controls.addView(clear, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        card.addView(selectedNetwork)
        card.addView(status)
        card.addView(networkSpinner)
        card.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        body.addView(card)

        val hint = TextView(this).apply {
            text = "TAP THE MAP WHERE YOU ARE STANDING"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10))
        }
        body.addView(hint)

        mapView = HeatMapView(this)
        body.addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))

        val legend = TextView(this).apply {
            text = "SIGNAL  Excellent  •  Good  •  Fair  •  Weak\nEach dot is a measured location. More samples create a better map."
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
        }
        body.addView(legend)

        scan.setOnClickListener { scan() }
        clear.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear map?").setMessage("All saved measurement points will be removed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    mapView.samples.clear(); saveProject(); mapView.invalidate(); updateStatus()
                }.show()
        }
        networkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSsid = parent?.getItemAtPosition(position)?.toString()?.substringBefore("  •")
                selectedNetwork.text = "Selected network: ${selectedSsid ?: "none"}"
                mapView.selectedSsid = selectedSsid
                mapView.invalidate()
                saveProject()
            }
        }
        mapView.onPointTapped = { x, y ->
            pendingPoint = x to y
            scanAndRecord()
        }

        scroll.addView(body)
        return scroll
    }

    private fun buildNetworkPage(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        val top = card()
        top.addView(TextView(this).apply {
            text = "VISIBLE NETWORKS"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, 1)
        })
        top.addView(TextView(this).apply {
            text = "Sorted by strongest signal. Tap a network to map it."
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
        })
        body.addView(top)
        networkList = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.BLACK)
        }
        networkList.setOnItemClickListener { _, _, position, _ ->
            val ssid = latest.distinctBy { it.SSID }.sortedByDescending { it.level }.getOrNull(position)?.SSID
            if (!ssid.isNullOrBlank()) {
                selectedSsid = ssid
                refreshSpinner()
                selectedNetwork.text = "Selected network: $ssid"
                mapView.selectedSsid = ssid
                Toast.makeText(this, "$ssid selected", Toast.LENGTH_SHORT).show()
            }
        }
        body.addView(networkList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return body
    }

    private fun buildGuidePage(): View {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(20))
        }
        body.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = "HOW TO MAP A BUILDING"
                setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, 1)
            })
            addView(TextView(this@MainActivity).apply {
                text = "1. Choose the Wi-Fi network.\n\n2. Stand at a known position and tap that location on the map.\n\n3. Wait for the measurement to save.\n\n4. Move several feet and repeat throughout the room or building.\n\n5. Dense samples reveal weak zones and likely access-point coverage boundaries.\n\nAndroid limits how often apps can actively request scans. If a scan is throttled, wait briefly and tap again. Location services must be enabled for Wi-Fi scan results."
                setTextColor(0xFFCCCCCC.toInt()); textSize = 16f; setPadding(0, dp(14), 0, 0)
            })
        })
        val settingsButton = outlined("OPEN LOCATION SETTINGS")
        settingsButton.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        body.addView(settingsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        scroll.addView(body)
        return scroll
    }

    private fun requestPermissionsAndScan() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasScanPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun scan() {
        if (!hasScanPermission()) { requestPermissionsAndScan(); return }
        status.text = "Scanning nearby networks…"
        try {
            val started = wifi.startScan()
            if (!started) {
                loadResults()
                status.text = "Using latest scan results. Android may be throttling active scans."
            }
        } catch (e: SecurityException) {
            status.text = "Wi-Fi scan permission is required."
        }
    }

    private fun scanAndRecord() {
        if (selectedSsid.isNullOrBlank()) {
            Toast.makeText(this, "Select a network first", Toast.LENGTH_SHORT).show(); pendingPoint = null; return
        }
        status.text = "Measuring ${selectedSsid} at this position…"
        try {
            val started = wifi.startScan()
            if (!started) recordPendingFromLatest()
        } catch (_: Exception) { recordPendingFromLatest() }
    }

    private fun loadResults() {
        try {
            latest.clear()
            latest.addAll(wifi.scanResults.filter { it.SSID.isNotBlank() }.sortedByDescending { it.level })
            refreshSpinner()
            refreshNetworkList()
            recordPendingFromLatest()
            updateStatus()
        } catch (e: SecurityException) {
            status.text = "Enable location permission and Location services to scan Wi-Fi."
        }
    }

    private fun recordPendingFromLatest() {
        val point = pendingPoint ?: return
        val ssid = selectedSsid ?: return
        val result = latest.filter { it.SSID == ssid }.maxByOrNull { it.level }
        if (result == null) {
            status.text = "$ssid was not visible in the latest scan."
        } else {
            mapView.samples.add(Sample(point.first, point.second, ssid, result.BSSID, result.level, result.frequency, System.currentTimeMillis()))
            saveProject(); mapView.invalidate()
            status.text = "Saved ${result.level} dBm at this position."
        }
        pendingPoint = null
    }

    private fun refreshSpinner() {
        val ssids = latest.distinctBy { it.SSID }.sortedByDescending { it.level }.map { "${it.SSID}  •  ${it.level} dBm" }
        networkSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ssids)
        selectedSsid?.let { chosen ->
            val index = ssids.indexOfFirst { it.startsWith("$chosen  •") }
            if (index >= 0) networkSpinner.setSelection(index)
        }
    }

    private fun refreshNetworkList() {
        val rows = latest.distinctBy { it.SSID }.sortedByDescending { it.level }.map {
            val band = if (it.frequency >= 5925) "6 GHz" else if (it.frequency >= 4900) "5 GHz" else "2.4 GHz"
            val quality = quality(it.level)
            "${it.SSID}\n${it.level} dBm  •  $quality  •  $band\n${security(it.capabilities)}"
        }
        networkList.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(18), dp(16), dp(18), dp(16)); setBackgroundColor(if (position % 2 == 0) 0xFF111111.toInt() else 0xFF191919.toInt())
                }
            }
        }
    }

    private fun updateStatus() {
        val count = mapView.samples.count { it.ssid == selectedSsid }
        if (latest.isNotEmpty() && pendingPoint == null) status.text = "${latest.size} access points visible • $count mapped samples"
    }

    private fun quality(rssi: Int): String = when { rssi >= -55 -> "Excellent"; rssi >= -67 -> "Good"; rssi >= -75 -> "Fair"; else -> "Weak" }
    private fun security(c: String): String = when { c.contains("WPA3") || c.contains("SAE") -> "WPA3"; c.contains("WPA2") -> "WPA2"; c.contains("WPA") -> "WPA"; c.contains("WEP") -> "WEP"; else -> "Open / unknown" }

    private fun saveProject() {
        val arr = JSONArray()
        mapView.samples.forEach { s -> arr.put(JSONObject().put("x", s.x).put("y", s.y).put("ssid", s.ssid).put("bssid", s.bssid).put("rssi", s.rssi).put("frequency", s.frequency).put("time", s.time)) }
        getSharedPreferences("wifi_mapper", MODE_PRIVATE).edit().putString("samples", arr.toString()).putString("ssid", selectedSsid).apply()
    }

    private fun restoreProject() {
        selectedSsid = getSharedPreferences("wifi_mapper", MODE_PRIVATE).getString("ssid", null)
        val raw = getSharedPreferences("wifi_mapper", MODE_PRIVATE).getString("samples", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                mapView.samples.add(Sample(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getString("ssid"), o.optString("bssid"), o.getInt("rssi"), o.optInt("frequency"), o.optLong("time")))
            }
        } catch (_: Exception) {}
        mapView.selectedSsid = selectedSsid
        selectedNetwork.text = "Selected network: ${selectedSsid ?: "none"}"
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(0xFF141414.toInt())
    }
    private fun pill(label: String) = Button(this).apply { text = label; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); textSize = 13f }
    private fun outlined(label: String) = Button(this).apply { text = label; setTextColor(Color.WHITE); setBackgroundColor(0xFF2A2A2A.toInt()); textSize = 14f }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    data class Sample(val x: Float, val y: Float, val ssid: String, val bssid: String, val rssi: Int, val frequency: Int, val time: Long)

    inner class HeatMapView(context: Context) : View(context) {
        val samples = mutableListOf<Sample>()
        var selectedSsid: String? = null
        var onPointTapped: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); strokeWidth = dp(1).toFloat() }

        init { setBackgroundColor(0xFF0B0B0B.toInt()) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(12).toFloat()
            val area = RectF(pad, pad, width - pad, height - pad)
            for (i in 0..10) {
                val x = area.left + area.width() * i / 10f
                val y = area.top + area.height() * i / 10f
                canvas.drawLine(x, area.top, x, area.bottom, grid)
                canvas.drawLine(area.left, y, area.right, y, grid)
            }
            val filtered = samples.filter { it.ssid == selectedSsid }
            if (filtered.isNotEmpty()) drawHeat(canvas, area, filtered)
            filtered.forEachIndexed { index, s ->
                val px = area.left + s.x * area.width()
                val py = area.top + s.y * area.height()
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                canvas.drawCircle(px, py, dp(7).toFloat(), paint)
                paint.color = Color.BLACK; paint.textSize = dp(9).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText((index + 1).toString(), px, py + dp(3), paint)
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); paint.color = Color.WHITE
            canvas.drawRoundRect(area, dp(16).toFloat(), dp(16).toFloat(), paint)
            if (filtered.isEmpty()) {
                paint.style = Paint.Style.FILL; paint.color = 0xFFAAAAAA.toInt(); paint.textSize = dp(15).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText("Tap your location to add the first sample", width / 2f, height / 2f, paint)
            }
        }

        private fun drawHeat(canvas: Canvas, area: RectF, data: List<Sample>) {
            val cells = 34
            val cw = area.width() / cells
            val ch = area.height() / cells
            for (gx in 0 until cells) for (gy in 0 until cells) {
                val nx = (gx + .5f) / cells
                val ny = (gy + .5f) / cells
                var weighted = 0.0
                var weights = 0.0
                data.forEach { s ->
                    val d = sqrt((nx - s.x).toDouble().pow(2) + (ny - s.y).toDouble().pow(2))
                    val w = 1.0 / max(0.018, d).pow(2)
                    weighted += s.rssi * w; weights += w
                }
                val rssi = (weighted / weights).toFloat()
                paint.style = Paint.Style.FILL
                paint.color = heatColor(rssi)
                canvas.drawRect(area.left + gx * cw, area.top + gy * ch, area.left + (gx + 1) * cw + 1, area.top + (gy + 1) * ch + 1, paint)
            }
        }

        private fun heatColor(rssi: Float): Int {
            val normalized = min(1f, max(0f, (rssi + 90f) / 50f))
            val alpha = 155
            return when {
                normalized > .75f -> Color.argb(alpha, 255, 255, 255)
                normalized > .5f -> Color.argb(alpha, 175, 175, 175)
                normalized > .25f -> Color.argb(alpha, 95, 95, 95)
                else -> Color.argb(alpha, 35, 35, 35)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val pad = dp(12).toFloat()
                val nx = ((event.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                val ny = ((event.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                onPointTapped?.invoke(nx, ny)
                performClick()
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
