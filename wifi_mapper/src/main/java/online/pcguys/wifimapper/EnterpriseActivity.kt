package online.pcguys.wifimapper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EnterpriseActivity : AppCompatActivity() {
    private val bg = Color.rgb(250,250,250)
    private val surface = Color.WHITE
    private val ink = Color.rgb(18,18,18)
    private val muted = Color.rgb(100,105,112)
    private val line = Color.rgb(228,230,233)
    private val blue = Color.rgb(0,102,204)
    private val green = Color.rgb(20,135,80)
    private val orange = Color.rgb(190,105,0)

    private lateinit var wifi: WifiManager
    private lateinit var tabs: TabLayout
    private lateinit var host: FrameLayout
    private lateinit var proxy: ProxyEngine
    private lateinit var terminal: TerminalEngine
    private val io = Executors.newCachedThreadPool()
    private val scanCancel = AtomicBoolean(false)
    private val deepCancel = AtomicBoolean(false)

    private var devices: List<DeviceRecord> = emptyList()
    private var wifiResults: List<ScanResult> = emptyList()
    private var scanRunning = false

    private var discoveryList: LinearLayout? = null
    private var discoveryProgress: ProgressBar? = null
    private var discoveryStatus: TextView? = null
    private var wifiList: LinearLayout? = null
    private var captureList: LinearLayout? = null
    private var captureStatus: TextView? = null
    private var terminalOutput: TextView? = null
    private var terminalInput: EditText? = null
    private var reportList: LinearLayout? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshWifi()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadWifiResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        terminal = TerminalEngine(this)
        proxy = ProxyEngine {
            runOnUiThread {
                renderCapture()
            }
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        registerWifiReceiver()
        buildShell()
        ensurePermissions()
        refreshWifi()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val titleArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(7))
            addView(text("PCG Network Tool", 27f, ink, true))
            addView(text("Enterprise field diagnostics", 12f, muted), top(2))
        }
        root.addView(titleArea)

        tabs = TabLayout(this).apply {
            setBackgroundColor(bg)
            setSelectedTabIndicatorColor(ink)
            setTabTextColors(muted, ink)
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        listOf("Dashboard","Wi-Fi","Discovery","Capture","Terminal","Reports","Settings").forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        host = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { openPage(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) refreshWifi()
                if (tab.position == 2 && !scanRunning) startDiscovery()
            }
        })
        openPage(0)
    }

    private fun openPage(index: Int) {
        host.removeAllViews()
        host.addView(
            when(index) {
                1 -> wifiPage()
                2 -> discoveryPage()
                3 -> capturePage()
                4 -> terminalPage()
                5 -> reportsPage()
                6 -> settingsPage()
                else -> dashboardPage()
            }
        )
    }

    private fun page(build: LinearLayout.() -> Unit): ScrollView {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(36))
            build()
        }
        scroll.addView(body)
        return scroll
    }

    private fun dashboardPage() = page {
        addView(card().apply {
            addView(text("ACTIVE NETWORK", 11f, muted, true))
            addView(text(currentSsid(), 24f, ink, true), top(7))
            addView(text(networkSummary(), 13f, muted), top(5))
            addView(text(signalSummary(), 14f, ink), top(8))
        })

        addView(primary("Run site discovery").apply {
            setOnClickListener {
                tabs.getTabAt(2)?.select()
                startDiscovery()
            }
        }, top(12))

        val stats = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(stat(devices.size.toString(), "DEVICES"), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(5) })
        stats.addView(stat(wifiResults.size.toString(), "ACCESS POINTS"), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
        stats.addView(stat(proxy.events.size.toString(), "FLOWS"), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(5) })
        addView(stats, top(12))

        addView(section("Field workflow"), top(24))
        addView(card().apply {
            addView(workflowRow("1", "Survey Wi-Fi", "Channels, security, RSSI and congestion"))
            addView(divider(), top(11))
            addView(workflowRow("2", "Discover devices", "Inventory the local IPv4 segment"), top(11))
            addView(divider(), top(11))
            addView(workflowRow("3", "Deep inspect", "Ports, banners, HTTP and TLS details"), top(11))
            addView(divider(), top(11))
            addView(workflowRow("4", "Capture test traffic", "Explicit PCG proxy with flow history"), top(11))
            addView(divider(), top(11))
            addView(workflowRow("5", "Save report", "Retain a site snapshot for the work order"), top(11))
        }, top(8))

        addView(section("Network facts"), top(24))
        addView(card().apply {
            addView(text(networkFacts(), 13f, ink).apply { setLineSpacing(0f, 1.25f) })
        }, top(8))
    }

    private fun wifiPage() = page {
        addView(section("Wi-Fi survey"))
        addView(text("Nearby BSSIDs, security, radio band, channel and congestion.", 12f, muted), top(4))
        addView(secondary("Refresh Wi-Fi scan").apply { setOnClickListener { refreshWifi() } }, top(10))
        wifiList = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(wifiList!!, top(12))
        renderWifi()
    }

    private fun discoveryPage() = page {
        addView(section("Device discovery"))
        discoveryStatus = text(if (scanRunning) "Scanning local segment…" else devices.size.toString() + " devices in current session", 12f, muted)
        addView(discoveryStatus!!, top(4))

        discoveryProgress = ProgressBar(this@EnterpriseActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 254
            progress = if (scanRunning) 1 else 0
            progressTintList = ColorStateList.valueOf(ink)
        }
        addView(discoveryProgress!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })

        val controls = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(primary(if (scanRunning) "Scanning…" else "Scan network").apply {
            isEnabled = !scanRunning
            setOnClickListener { startDiscovery() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        controls.addView(secondary("Cancel").apply {
            isEnabled = scanRunning
            setOnClickListener { scanCancel.set(true) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        addView(controls, top(10))

        addView(text("Tap any device for Deep Scan, HTTP, TLS and quick actions.", 12f, muted), top(10))
        discoveryList = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(discoveryList!!, top(12))
        renderDevices()
    }

    private fun capturePage() = page {
        addView(section("Traffic capture"))
        addView(text("Explicit proxy capture for authorized test traffic. HTTP metadata is inspectable; HTTPS CONNECT destinations are logged while payload remains encrypted.", 12f, muted), top(4))

        val ip = NetworkEngine.localIpv4(this@EnterpriseActivity) ?: "phone-ip"
        captureStatus = text(proxyState(ip), 13f, if (proxy.running) green else muted, true)
        addView(captureStatus!!, top(10))

        addView(card().apply {
            addView(text("Proxy endpoint", 11f, muted, true))
            addView(text(ip + ":" + proxy.listenPort, 20f, ink, true), top(5))
            addView(text("Use this address as the HTTP proxy on the authorized test device. For testing traffic from this phone, use 127.0.0.1:" + proxy.listenPort + " where supported.", 12f, muted), top(5))
        }, top(10))

        val row = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(primary(if (proxy.running) "Stop capture" else "Start capture").apply {
            setOnClickListener {
                if (proxy.running) proxy.stop() else proxy.start(savedProxyPort())
                openPage(3)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        row.addView(secondary("Self-test").apply {
            setOnClickListener {
                io.execute {
                    val result = proxy.selfTest()
                    runOnUiThread { simpleDialog("Capture self-test", result) }
                }
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        addView(row, top(10))

        val row2 = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(secondary("Clear flows").apply {
            setOnClickListener { proxy.clear(); renderCapture() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        row2.addView(secondary("Share flows").apply {
            setOnClickListener { shareText("PCG capture", captureText()) }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        addView(row2, top(10))

        captureList = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(captureList!!, top(14))
        renderCapture()
    }

    private fun terminalPage() = page {
        addView(section("Network terminal"))
        addView(text("PCG commands plus Android shell commands inside the app sandbox. Type help to begin.", 12f, muted), top(4))

        terminalOutput = text("PCG Network Terminal\nType help for available commands.\n", 12f, ink).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(242,243,245), 14)
        }
        addView(terminalOutput!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)).apply { topMargin = dp(10) })

        terminalInput = EditText(this@EnterpriseActivity).apply {
            hint = "command"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            typeface = android.graphics.Typeface.MONOSPACE
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addView(terminalInput!!, top(10))

        val row = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(primary("Run").apply { setOnClickListener { runTerminal() } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        row.addView(secondary("Clear").apply {
            setOnClickListener { terminalOutput?.text = "" }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        addView(row, top(8))

        addView(text("Examples", 14f, ink, true), top(18))
        listOf("status","scan","ports 192.168.1.1 standard","tls example.com","dns example.com","shell ip addr").forEach { command ->
            addView(commandButton(command), top(6))
        }
    }

    private fun reportsPage() = page {
        addView(section("Site reports"))
        addView(text("Save the current network inventory and capture summary on-device, then share it into a work order or customer record.", 12f, muted), top(4))

        val site = EditText(this@EnterpriseActivity).apply {
            hint = "Site / customer name"
            setText(getPreferences(0).getString("site_name", "") ?: "")
            setSingleLine(true)
        }
        addView(site, top(10))

        addView(primary("Save current snapshot").apply {
            setOnClickListener {
                getPreferences(0).edit().putString("site_name", site.text.toString()).apply()
                val file = saveSnapshot(site.text.toString())
                simpleDialog("Snapshot saved", file.name)
                renderReports()
            }
        }, top(10))

        addView(secondary("Share current report").apply {
            setOnClickListener { shareText("PCG Network Report", buildReport(site.text.toString())) }
        }, top(8))

        reportList = LinearLayout(this@EnterpriseActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(reportList!!, top(16))
        renderReports()
    }

    private fun settingsPage() = page {
        addView(section("Settings"))
        addView(text("Field defaults are stored only on this device.", 12f, muted), top(4))

        val proxyPort = EditText(this@EnterpriseActivity).apply {
            hint = "Proxy port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedProxyPort().toString())
        }
        addView(fieldCard("Capture proxy port", proxyPort), top(10))

        val scanTimeout = EditText(this@EnterpriseActivity).apply {
            hint = "Discovery timeout ms"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedScanTimeout().toString())
        }
        addView(fieldCard("Discovery TCP timeout", scanTimeout), top(10))

        addView(primary("Save settings").apply {
            setOnClickListener {
                val p = proxyPort.text.toString().toIntOrNull()?.coerceIn(1024,65535) ?: 8888
                val t = scanTimeout.text.toString().toIntOrNull()?.coerceIn(80,1500) ?: 180
                getPreferences(0).edit().putInt("proxy_port", p).putInt("scan_timeout", t).apply()
                simpleDialog("Settings", "Saved")
            }
        }, top(12))

        addView(card().apply {
            addView(text("Operational boundaries", 15f, ink, true))
            addView(text("The terminal does not grant root. Traffic inspection is opt-in. HTTPS payloads are not silently decrypted. Use scanning and inspection only on networks and devices you are authorized to assess.", 12f, muted), top(6))
        }, top(20))
    }

    private fun startDiscovery() {
        if (scanRunning) return
        if (NetworkEngine.localIpv4(this) == null) {
            simpleDialog("No active IPv4 network", "Connect to the customer Wi-Fi or Ethernet network first.")
            return
        }
        scanRunning = true
        scanCancel.set(false)
        if (tabs.selectedTabPosition != 2) tabs.getTabAt(2)?.select() else openPage(2)

        io.execute {
            val result = NetworkEngine.discoverSubnet(
                this,
                savedScanTimeout(),
                { scanCancel.get() }
            ) { done, total, found ->
                runOnUiThread {
                    discoveryProgress?.max = total
                    discoveryProgress?.progress = done
                    discoveryStatus?.text = if (scanCancel.get()) "Cancelling…" else "Scanning " + done + "/" + total + " • " + devices.size + " found"
                    if (found != null) {
                        val mutable = devices.toMutableList()
                        if (mutable.none { it.ip == found.ip }) mutable.add(found)
                        devices = mutable.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
                        renderDevices()
                    }
                }
            }
            devices = result
            scanRunning = false
            runOnUiThread {
                discoveryStatus?.text = if (scanCancel.get()) "Scan cancelled • " + devices.size + " devices retained" else "Scan complete • " + devices.size + " devices"
                discoveryProgress?.progress = discoveryProgress?.max ?: 254
                renderDevices()
            }
        }
    }

    private fun renderDevices() {
        val box = discoveryList ?: return
        box.removeAllViews()
        if (devices.isEmpty()) {
            box.addView(emptyCard("No inventory yet", "Run Scan network to inventory responding devices on the local segment."))
            return
        }
        devices.forEach { d ->
            box.addView(card().apply {
                addView(LinearLayout(this@EnterpriseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(this@EnterpriseActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(if (d.gateway) "Gateway" else d.role, 16f, ink, true))
                        addView(text(d.hostname ?: d.ip, 12f, muted), top(3))
                        if (d.hostname != null) addView(text(d.ip, 11f, muted), top(1))
                        if (d.openPorts.isNotEmpty()) addView(text("Seen: " + d.openPorts.joinToString(", "), 11f, muted), top(3))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(d.latencyMs?.let { it.toString() + " ms" } ?: "online", 11f, green, true))
                })
                setOnClickListener { deviceActions(d) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
        }
    }

    private fun deviceActions(device: DeviceRecord) {
        val actions = arrayOf("Deep Scan","Quick Scan","HTTP inspect","HTTPS inspect","TLS certificate","DNS / hostname","Open web admin")
        AlertDialog.Builder(this)
            .setTitle(device.hostname ?: device.ip)
            .setMessage(device.role + "\n" + device.ip + "\nCurrent ports: " + if (device.openPorts.isEmpty()) "none observed" else device.openPorts.joinToString(", "))
            .setItems(actions) { _, which ->
                when(which) {
                    0 -> launchDeepScan(device, "deep")
                    1 -> launchDeepScan(device, "quick")
                    2 -> runProbe("HTTP inspection", { NetworkEngine.httpSummary(device.ip, false, null) })
                    3 -> runProbe("HTTPS inspection", { NetworkEngine.httpSummary(device.ip, true, null) })
                    4 -> runProbe("TLS certificate", { NetworkEngine.tlsSummary(device.ip, 443) ?: "No TLS result" })
                    5 -> runProbe("DNS / hostname", {
                        val name = try { java.net.InetAddress.getByName(device.ip).canonicalHostName } catch (_: Exception) { device.ip }
                        "IP: " + device.ip + "\nHostname: " + name
                    })
                    6 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://" + device.ip)))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun launchDeepScan(device: DeviceRecord, initialMode: String) {
        val modes = arrayOf("Quick • key services","Standard • technician profile","Deep • TCP 1-1024","Custom range")
        AlertDialog.Builder(this)
            .setTitle("Scan profile")
            .setItems(modes) { _, choice ->
                when(choice) {
                    0 -> runDeepScan(device, "quick", null, null)
                    1 -> runDeepScan(device, "standard", null, null)
                    2 -> runDeepScan(device, "deep", null, null)
                    else -> customRangeDialog(device)
                }
            }
            .show()
    }

    private fun customRangeDialog(device: DeviceRecord) {
        val input = EditText(this).apply {
            hint = "Example: 1-2048"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Custom TCP range")
            .setView(input)
            .setPositiveButton("Scan") { _, _ ->
                val p = input.text.toString().split("-", limit = 2)
                val a = p.getOrNull(0)?.trim()?.toIntOrNull()
                val b = p.getOrNull(1)?.trim()?.toIntOrNull()
                if (a != null && b != null) runDeepScan(device, "custom", a, b)
                else simpleDialog("Invalid range", "Use start-end, for example 1-2048.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runDeepScan(device: DeviceRecord, mode: String, start: Int?, end: Int?) {
        deepCancel.set(false)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val status = text("Starting scan…", 12f, muted)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ink)
        }
        val output = text("", 12f, ink).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(output) }
        layout.addView(status)
        layout.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(8) })
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(10) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Deep Scan • " + device.ip)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                deepCancel.set(true)
                status.text = "Cancelling…"
            }
        }
        dialog.show()

        io.execute {
            val findings = NetworkEngine.deepScan(
                device.ip,
                mode,
                start,
                end,
                320,
                { deepCancel.get() }
            ) { done, total, found ->
                runOnUiThread {
                    progress.max = total.coerceAtLeast(1)
                    progress.progress = done
                    status.text = "Scanning " + done + "/" + total + " • " + output.text.lines().count { it.isNotBlank() } + " services"
                    if (found != null) {
                        val lineText = found.port.toString() + "/tcp  " + found.service +
                            (found.banner?.let { "\n    " + it } ?: "") + "\n"
                        output.append(lineText)
                    }
                }
            }
            runOnUiThread {
                status.text = if (deepCancel.get()) "Cancelled • " + findings.size + " open ports retained" else "Complete • " + findings.size + " open ports"
                if (findings.isEmpty() && output.text.isBlank()) output.text = "No ports answered in this scan profile."
            }
        }
    }

    private fun runProbe(title: String, action: () -> String) {
        simpleDialog(title, "Working…")
        io.execute {
            val result = try { action() } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            runOnUiThread { simpleDialog(title, result) }
        }
    }

    private fun refreshWifi() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ensurePermissions()
            return
        }
        try {
            wifi.startScan()
            loadWifiResults()
        } catch (_: Exception) {}
    }

    private fun loadWifiResults() {
        try {
            wifiResults = wifi.scanResults.sortedByDescending { it.level }
            renderWifi()
        } catch (_: Exception) {}
    }

    private fun renderWifi() {
        val box = wifiList ?: return
        box.removeAllViews()
        if (wifiResults.isEmpty()) {
            box.addView(emptyCard("No scan results", "Enable Wi-Fi and Location, then refresh."))
            return
        }

        val channelCounts = wifiResults.groupingBy { channel(it.frequency) }.eachCount()
        wifiResults.take(80).forEach { ap ->
            val ch = channel(ap.frequency)
            val congestion = channelCounts[ch] ?: 1
            val score = when {
                congestion <= 2 -> "Low congestion"
                congestion <= 5 -> "Moderate congestion"
                else -> "Busy channel"
            }
            box.addView(card().apply {
                addView(LinearLayout(this@EnterpriseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(LinearLayout(this@EnterpriseActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(ap.SSID.ifBlank { "Hidden network" }, 16f, ink, true))
                        addView(text(band(ap.frequency) + " • Ch " + ch + " • " + security(ap.capabilities), 12f, muted), top(3))
                        addView(text(ap.BSSID + " • " + score, 11f, muted), top(2))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(ap.level.toString() + " dBm", 11f, if (ap.level >= -67) green else orange, true))
                })
                setOnClickListener { showAccessPoint(ap, congestion) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun showAccessPoint(ap: ScanResult, congestion: Int) {
        val width = when(ap.channelWidth) {
            0 -> "20 MHz"; 1 -> "40 MHz"; 2 -> "80 MHz"; 3 -> "160 MHz"; 4 -> "80+80 MHz"; else -> "Unknown"
        }
        simpleDialog(
            ap.SSID.ifBlank { "Hidden network" },
            "BSSID: " + ap.BSSID +
                "\nSignal: " + ap.level + " dBm" +
                "\nBand: " + band(ap.frequency) +
                "\nChannel: " + channel(ap.frequency) +
                "\nFrequency: " + ap.frequency + " MHz" +
                "\nChannel width: " + width +
                "\nSecurity: " + security(ap.capabilities) +
                "\nNearby BSSIDs on channel: " + congestion +
                "\n\nCapabilities: " + ap.capabilities
        )
    }

    private fun renderCapture() {
        val box = captureList ?: return
        box.removeAllViews()
        captureStatus?.text = proxyState(NetworkEngine.localIpv4(this) ?: "phone-ip")
        captureStatus?.setTextColor(if (proxy.running) green else muted)
        if (proxy.events.isEmpty()) {
            box.addView(emptyCard("No flows captured", "Start capture, configure authorized test traffic to use the proxy, then run Self-test to verify the capture path."))
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        proxy.events.take(150).forEach { e ->
            box.addView(card().apply {
                addView(LinearLayout(this@EnterpriseActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text(e.method + "  " + e.protocol, 11f, if (e.protocol == "TLS") blue else green, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(text(fmt.format(Date(e.time)), 10f, muted))
                })
                addView(text(e.host + ":" + e.port + e.path, 13f, ink, true), top(4))
                addView(text(e.detail, 11f, muted), top(3))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun runTerminal() {
        val command = terminalInput?.text?.toString()?.trim().orEmpty()
        if (command.isBlank()) return
        terminalInput?.setText("")
        terminalOutput?.append("\n> " + command + "\n")
        io.execute {
            val result = terminal.run(command)
            runOnUiThread {
                if (result == "__CLEAR__") terminalOutput?.text = ""
                else terminalOutput?.append(result + "\n")
            }
        }
    }

    private fun commandButton(command: String): View {
        return secondary(command).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.MONOSPACE
            setOnClickListener {
                terminalInput?.setText(command)
                terminalInput?.setSelection(command.length)
            }
        }
    }

    private fun saveSnapshot(site: String): File {
        val dir = File(filesDir, "network_reports")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = site.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(40)
        val file = File(dir, (if (safe.isBlank()) "site" else safe) + "-" + stamp + ".txt")
        file.writeText(buildReport(site))
        return file
    }

    private fun buildReport(site: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("PCG NETWORK TOOL ENTERPRISE REPORT\n")
            append("Site: ").append(site.ifBlank { "Unspecified" }).append("\n")
            append("Created: ").append(now).append("\n\n")
            append("NETWORK\n").append(networkFacts()).append("\n\n")
            append("DEVICES (").append(devices.size).append(")\n")
            devices.forEach { d ->
                append(d.ip).append(" | ").append(d.hostname ?: "—").append(" | ").append(d.role)
                    .append(" | ports ").append(d.openPorts.joinToString(",")).append("\n")
            }
            append("\nWI-FI ACCESS POINTS (").append(wifiResults.size).append(")\n")
            wifiResults.take(100).forEach { ap ->
                append(ap.SSID.ifBlank { "Hidden" }).append(" | ").append(ap.BSSID)
                    .append(" | ").append(ap.level).append(" dBm | ").append(band(ap.frequency))
                    .append(" ch ").append(channel(ap.frequency)).append(" | ").append(security(ap.capabilities)).append("\n")
            }
            append("\nCAPTURE FLOWS (").append(proxy.events.size).append(")\n")
            append(captureText())
        }
    }

    private fun captureText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return proxy.events.joinToString("\n") {
            fmt.format(Date(it.time)) + " | " + it.protocol + " " + it.method + " | " +
                it.host + ":" + it.port + it.path + " | " + it.detail
        }
    }

    private fun renderReports() {
        val box = reportList ?: return
        box.removeAllViews()
        val dir = File(filesDir, "network_reports")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            box.addView(emptyCard("No saved snapshots", "Save a snapshot after an on-site assessment."))
            return
        }
        files.take(20).forEach { file ->
            box.addView(card().apply {
                addView(text(file.name, 13f, ink, true))
                addView(text((file.length() / 1024.0).let { String.format(Locale.US, "%.1f KB", it) }, 11f, muted), top(3))
                setOnClickListener {
                    AlertDialog.Builder(this@EnterpriseActivity)
                        .setTitle(file.name)
                        .setItems(arrayOf("Share","View","Delete")) { _, which ->
                            when(which) {
                                0 -> shareText(file.name, file.readText())
                                1 -> simpleDialog(file.name, file.readText().take(20000))
                                2 -> { file.delete(); renderReports() }
                            }
                        }.show()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun shareText(title: String, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun ensurePermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        perms.launch(list.toTypedArray())
    }

    private fun registerWifiReceiver() {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String = try {
        wifi.connectionInfo?.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" } ?: "Not connected"
    } catch (_: Exception) { "Not connected" }

    @Suppress("DEPRECATION")
    private fun signalSummary(): String = try {
        val i = wifi.connectionInfo
        val rssi = i?.rssi ?: -127
        if (rssi <= -126) "Signal unavailable"
        else rssi.toString() + " dBm • " + quality(rssi) + " • " + i.linkSpeed + " Mbps"
    } catch (_: Exception) { "Signal unavailable" }

    @Suppress("DEPRECATION")
    private fun networkSummary(): String = try {
        val i = wifi.connectionInfo
        band(i.frequency) + " • Channel " + channel(i.frequency) + " • " + i.frequency + " MHz"
    } catch (_: Exception) { "Network details unavailable" }

    private fun networkFacts(): String {
        return "Local IP: " + (NetworkEngine.localIpv4(this) ?: "—") +
            "\nGateway: " + (NetworkEngine.gateway(this) ?: "—") +
            "\nDNS: " + NetworkEngine.dnsServers(this).joinToString(", ").ifBlank { "—" } +
            "\nSSID: " + currentSsid() +
            "\nSignal: " + signalSummary()
    }

    private fun proxyState(ip: String): String {
        return if (proxy.running) "Capture active • listening on " + ip + ":" + proxy.listenPort + " • " + proxy.events.size + " flows"
        else "Capture stopped"
    }

    private fun savedProxyPort(): Int = getPreferences(0).getInt("proxy_port", 8888)
    private fun savedScanTimeout(): Int = getPreferences(0).getInt("scan_timeout", 180)

    private fun band(freq: Int): String = when {
        freq >= 5925 -> "6 GHz"
        freq >= 4900 -> "5 GHz"
        freq > 0 -> "2.4 GHz"
        else -> "—"
    }

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

    private fun quality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Very good"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }

    private fun workflowRow(n: String, title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(n, 13f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(ink, 20)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(LinearLayout(this@EnterpriseActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 14f, ink, true))
                addView(text(subtitle, 11f, muted), top(2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        }
    }

    private fun stat(value: String, label: String): View = card().apply {
        gravity = Gravity.CENTER
        addView(text(value, 24f, ink, true).apply { gravity = Gravity.CENTER })
        addView(text(label, 10f, muted, true).apply { gravity = Gravity.CENTER }, top(3))
    }

    private fun fieldCard(label: String, input: EditText): View = card().apply {
        addView(text(label, 12f, muted, true))
        addView(input, top(4))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(surface, 18, line)
        elevation = dp(1).toFloat()
    }

    private fun emptyCard(title: String, subtitle: String): View = card().apply {
        addView(text(title, 15f, ink, true))
        addView(text(subtitle, 12f, muted), top(4))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun section(value: String): TextView = text(value, 18f, ink, true)

    private fun primary(value: String): MaterialButton = MaterialButton(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(ink)
        cornerRadius = dp(14)
        insetTop = 0
        insetBottom = 0
    }

    private fun secondary(value: String): MaterialButton = MaterialButton(this).apply {
        text = value
        textSize = 12f
        setTextColor(ink)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(238,240,242))
        cornerRadius = dp(14)
        insetTop = 0
        insetBottom = 0
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(line) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun top(value: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(value)
        }
    }

    private fun simpleDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        scanCancel.set(true)
        deepCancel.set(true)
        proxy.stop()
        io.shutdownNow()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
