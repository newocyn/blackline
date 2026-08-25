package online.pcguys.blackline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TechToolsActivity : AppCompatActivity() {
    private val bg = Color.rgb(4, 6, 8)
    private val panel = Color.rgb(11, 14, 17)
    private val dim = Color.rgb(138, 153, 163)
    private val accent = Color.rgb(69, 246, 229)
    private lateinit var output: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val WIFI_PERMISSIONS = 701
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        buildUi()
        showNetworkInfo()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        runCatching {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = bg
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, max(ime.bottom, nav.bottom))
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(9), dp(8), dp(9))
            setBackgroundColor(Color.rgb(9, 12, 15))
        }
        top.addView(text("BLACKLINE // TECH DECK", 11f, accent).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = .08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(chip("SHELL") {
            startActivity(Intent(this, TerminalActivity::class.java))
            finish()
        }, LinearLayout.LayoutParams(dp(58), dp(38)).apply { rightMargin = dp(5) })
        top.addView(chip("HOME") {
            startActivity(Intent(this, BlacklineHomeActivity::class.java))
            finish()
        }, LinearLayout.LayoutParams(dp(55), dp(38)))
        root.addView(top)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            setBackgroundColor(Color.rgb(7, 9, 11))
        }
        controls.addView(text("FIELD DIAGNOSTICS", 8.5f, dim).apply { letterSpacing = .12f })

        val grid = GridLayout(this).apply { columnCount = 3 }
        listOf(
            Triple("WI-FI", "SURVEY") { startWifiSurvey() },
            Triple("LAN", "DISCOVERY") { startLanDiscovery() },
            Triple("NET", "DETAILS") { showNetworkInfo() },
            Triple("DEVICE", "DIAG") { showDeviceDiagnostics() },
            Triple("ANDROID", "SETTINGS") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            Triple("CLEAR", "OUTPUT") { output.text = "" }
        ).forEach { (a, b, action) -> grid.addView(toolButton(a, b, action), cell()) }
        controls.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        val hostRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostInput = EditText(this).apply {
            hint = "host / IP / URL"
            setHintTextColor(Color.rgb(104, 113, 120))
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(panel, 12f, Color.rgb(39, 53, 58))
        }
        hostRow.addView(hostInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        portInput = EditText(this).apply {
            hint = "port"
            setHintTextColor(Color.rgb(104, 113, 120))
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setPadding(dp(9), 0, dp(9), 0)
            background = rounded(panel, 12f, Color.rgb(39, 53, 58))
        }
        hostRow.addView(portInput, LinearLayout.LayoutParams(dp(72), dp(46)).apply { leftMargin = dp(6) })
        controls.addView(hostRow, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })

        val actionRow = GridLayout(this).apply { columnCount = 4 }
        actionRow.addView(actionButton("PING") { runPing() }, actionCell())
        actionRow.addView(actionButton("DNS") { runDns() }, actionCell())
        actionRow.addView(actionButton("PORT") { runPortTest() }, actionCell())
        actionRow.addView(actionButton("HTTP") { runHttpCheck() }, actionCell())
        controls.addView(actionRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        root.addView(controls)

        val outputScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        output = TextView(this).apply {
            setTextColor(Color.rgb(219, 229, 233))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(13), dp(12), dp(13), dp(22))
        }
        outputScroll.addView(output)
        root.addView(outputScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun startWifiSurvey() {
        if (!hasWifiPermissions()) {
            val needed = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), WIFI_PERMISSIONS)
            return
        }
        executor.execute { performWifiSurvey() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WIFI_PERMISSIONS) {
            if (hasWifiPermissions()) executor.execute { performWifiSurvey() }
            else append("WI-FI SURVEY\nPermission denied. Nearby Wi-Fi scanning requires Android Wi-Fi/location permission.\n\n")
        }
    }

    private fun hasWifiPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        return fine && nearby
    }

    @Suppress("DEPRECATION")
    private fun performWifiSurvey() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        runCatching { wm.startScan() }
        Thread.sleep(1100)
        val results = runCatching { wm.scanResults }.getOrDefault(emptyList()).sortedByDescending { it.level }
        val out = buildString {
            append("WI-FI SURVEY // ${stamp()}\n")
            append("MODE        Android survey (not raw monitor-mode capture)\n")
            append("NETWORKS    ${results.size}\n\n")
            if (results.isEmpty()) {
                append("No scan results. Ensure Wi-Fi and Location services are enabled; Android may also throttle repeated scans.\n")
            } else {
                val channels = results.groupingBy { channelFor(it.frequency) }.eachCount().entries.sortedByDescending { it.value }.take(12)
                append("CHANNEL LOAD\n")
                channels.forEach { append("CH ${it.key.toString().padStart(3)}  ${bar(it.value.coerceAtMost(8), 8)}  ${it.value} APs\n") }
                append("\nNEARBY NETWORKS\n")
                results.take(80).forEachIndexed { index, r ->
                    val ssid = r.SSID.takeIf { it.isNotBlank() } ?: "<hidden>"
                    val sec = securityLabel(r.capabilities)
                    append(String.format(Locale.US, "%02d  %-22s %4d dBm  ch %-3d  %-5s  %s\n", index + 1, ssid.take(22), r.level, channelFor(r.frequency), bandFor(r.frequency), sec))
                    append("    ${r.BSSID}  ${r.frequency} MHz  ${signalWord(r.level)}\n")
                }
            }
            append("\nTrue 802.11 monitor-mode sniffing/packet injection requires compatible root/kernel or an external Wi-Fi adapter.\n\n")
        }
        runOnUiThread { replaceOutput(out) }
    }

    private fun showNetworkInfo() {
        executor.execute {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val lp = network?.let { cm.getLinkProperties(it) }
            val type = when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "ONLINE"
            }
            val out = buildString {
                append("NETWORK DETAILS // ${stamp()}\n")
                append("TYPE        $type\n")
                append("VALIDATED   ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}\n")
                append("INTERFACE   ${lp?.interfaceName ?: "--"}\n")
                append("\nADDRESSES\n")
                lp?.linkAddresses?.forEach { append("  $it\n") }
                append("\nDNS\n")
                lp?.dnsServers?.forEach { append("  ${it.hostAddress}\n") }
                append("\nROUTES\n")
                lp?.routes?.take(20)?.forEach { append("  $it\n") }
                append("\n")
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun showDeviceDiagnostics() {
        executor.execute {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val stat = StatFs(File("/sdcard").absolutePath)
            val kernel = runCatching { ProcessBuilder("uname", "-a").start().inputStream.bufferedReader().readText().trim() }.getOrDefault("unknown")
            val out = buildString {
                append("DEVICE DIAGNOSTICS // ${stamp()}\n")
                append("DEVICE      ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("ANDROID     ${Build.VERSION.RELEASE} // API ${Build.VERSION.SDK_INT}\n")
                append("ARCH        ${Build.SUPPORTED_ABIS.joinToString()}\n")
                append("KERNEL      $kernel\n")
                append("BATTERY     $level%  %.1f C  %d mV\n".format(temp, voltage))
                append("STORAGE     ${formatBytes(stat.availableBytes)} free / ${formatBytes(stat.totalBytes)}\n")
                append("UPTIME      ${formatDuration(android.os.SystemClock.elapsedRealtime())}\n")
                append("\n")
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runPing() {
        val host = hostInput.text.toString().trim().substringBefore("/")
        if (host.isBlank()) return append("PING\nEnter a host or IP first.\n\n")
        executor.execute {
            val out = runCatching {
                val p = ProcessBuilder("/system/bin/ping", "-c", "4", "-W", "2", host).redirectErrorStream(true).start()
                val data = p.inputStream.bufferedReader().readText()
                p.waitFor(12, TimeUnit.SECONDS)
                "PING $host\n$data\n"
            }.getOrElse { "PING $host\n${it.message}\n\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runDns() {
        val host = hostInput.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (host.isBlank()) return append("DNS\nEnter a hostname first.\n\n")
        executor.execute {
            val out = runCatching {
                val results = InetAddress.getAllByName(host)
                buildString {
                    append("DNS LOOKUP // $host\n")
                    results.distinctBy { it.hostAddress }.forEach { append("${it.hostAddress}\n") }
                    append("\n")
                }
            }.getOrElse { "DNS LOOKUP // $host\n${it.message}\n\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runPortTest() {
        val host = hostInput.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) return append("PORT TEST\nEnter host and valid port.\n\n")
        executor.execute {
            val start = System.nanoTime()
            val result = try {
                Socket().use { socket -> socket.connect(java.net.InetSocketAddress(host, port), 2500) }
                val ms = (System.nanoTime() - start) / 1_000_000
                "OPEN ($ms ms)"
            } catch (e: Exception) { "CLOSED / UNREACHABLE (${e.javaClass.simpleName})" }
            runOnUiThread { replaceOutput("TCP PORT TEST\n$host:$port  $result\n\n") }
        }
    }

    private fun runHttpCheck() {
        val raw = hostInput.text.toString().trim()
        if (raw.isBlank()) return append("HTTP CHECK\nEnter a hostname or URL.\n\n")
        executor.execute {
            val target = if (raw.contains("://")) raw else "https://$raw"
            val out = try {
                val c = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BLACKLINE-Tech/0.8")
                }
                val start = System.nanoTime()
                c.connect()
                val ms = (System.nanoTime() - start) / 1_000_000
                buildString {
                    append("HTTP CHECK\n$target\n")
                    append("STATUS      ${c.responseCode} ${c.responseMessage}\n")
                    append("TIME        ${ms} ms\n")
                    append("SERVER      ${c.getHeaderField("Server") ?: "--"}\n")
                    append("TYPE        ${c.contentType ?: "--"}\n\n")
                }.also { c.disconnect() }
            } catch (e: Exception) { "HTTP CHECK\n$target\n${e.message}\n\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun startLanDiscovery() {
        executor.execute {
            val base = localIpv4()?.hostAddress?.substringBeforeLast('.')
            if (base == null) {
                runOnUiThread { replaceOutput("LAN DISCOVERY\nNo local IPv4 address found. Connect to a LAN/Wi-Fi network first.\n\n") }
                return@execute
            }
            runOnUiThread { replaceOutput("LAN DISCOVERY // $base.0/24\nScanning local subnet for reachable hosts…\n") }
            val pool = Executors.newFixedThreadPool(32)
            val found = java.util.Collections.synchronizedList(mutableListOf<String>())
            val tasks = (1..254).map { n ->
                pool.submit {
                    val ip = "$base.$n"
                    try {
                        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
                        if (p.waitFor(1400, TimeUnit.MILLISECONDS) && p.exitValue() == 0) found += ip else p.destroyForcibly()
                    } catch (_: Exception) { }
                }
            }
            tasks.forEach { runCatching { it.get(4, TimeUnit.SECONDS) } }
            pool.shutdownNow()
            val sorted = found.distinct().sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
            val out = buildString {
                append("LAN DISCOVERY // $base.0/24\n")
                append("REACHABLE    ${sorted.size}\n\n")
                sorted.forEach { ip ->
                    val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip)
                    append("$ip${if (name != ip) "  $name" else ""}\n")
                }
                append("\nDiscovery is limited to the phone's current local /24 and performs reachability checks only.\n\n")
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun localIpv4(): Inet4Address? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    private fun channelFor(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5000..5895 -> (freq - 5000) / 5
        freq in 5955..7115 -> (freq - 5950) / 5
        else -> 0
    }

    private fun bandFor(freq: Int): String = when (freq) {
        in 2400..2500 -> "2.4G"
        in 4900..5900 -> "5G"
        in 5925..7125 -> "6G"
        else -> "?"
    }

    private fun securityLabel(caps: String): String = when {
        caps.contains("WPA3", true) || caps.contains("SAE", true) -> "WPA3"
        caps.contains("WPA2", true) || caps.contains("RSN", true) -> "WPA2"
        caps.contains("WPA", true) -> "WPA"
        caps.contains("WEP", true) -> "WEP"
        else -> "OPEN"
    }

    private fun signalWord(dbm: Int): String = when {
        dbm >= -50 -> "EXCELLENT"
        dbm >= -60 -> "GOOD"
        dbm >= -70 -> "FAIR"
        dbm >= -80 -> "WEAK"
        else -> "VERY WEAK"
    }

    private fun bar(value: Int, max: Int): String = buildString {
        repeat(max) { append(if (it < value) '█' else '·') }
    }

    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024.0; i++ }
        return "%.1f %s".format(Locale.US, v, units[i])
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        return "${days}d ${hours}h ${minutes}m"
    }

    private fun replaceOutput(value: String) { output.text = value }
    private fun append(value: String) { output.append(value) }

    private fun toolButton(a: String, b: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(panel, 12f, Color.rgb(35, 46, 51))
        addView(text(a, 10.5f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        addView(text(b, 7.5f, dim).apply { letterSpacing = .05f })
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 8.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(accent)
        background = rounded(panel, 10f, Color.rgb(34, 49, 52))
        setOnClickListener { action() }
    }

    private fun chip(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 8.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(18, 23, 27), 10f, Color.rgb(43, 60, 64))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun cell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(58)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun actionCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(42)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
