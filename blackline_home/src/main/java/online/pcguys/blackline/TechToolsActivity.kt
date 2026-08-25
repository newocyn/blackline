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
import java.net.InetSocketAddress
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
    private val panel2 = Color.rgb(15, 18, 22)
    private val dim = Color.rgb(138, 153, 163)
    private val accent = Color.rgb(69, 246, 229)
    private lateinit var output: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusChip: TextView
    private lateinit var networkSummary: TextView
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val WIFI_PERMISSIONS = 701
        private val COMMON_SERVICES = linkedMapOf(
            22 to "SSH",
            53 to "DNS",
            80 to "WEB",
            139 to "SMB",
            443 to "HTTPS",
            445 to "SMB",
            631 to "IPP PRINTER",
            3389 to "RDP",
            8080 to "WEB ALT",
            9100 to "PRINTER"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        buildUi()
        refreshNetworkSummary()
        showWelcome()
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
        statusChip = chipView("READY") { }
        statusChip.setTextColor(accent)
        top.addView(statusChip, LinearLayout.LayoutParams(dp(58), dp(38)).apply { rightMargin = dp(5) })
        top.addView(chipView("SHELL") {
            startActivity(Intent(this, TerminalActivity::class.java))
        }, LinearLayout.LayoutParams(dp(58), dp(38)).apply { rightMargin = dp(5) })
        top.addView(chipView("HOME") { finish() }, LinearLayout.LayoutParams(dp(55), dp(38)))
        root.addView(top)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        body.addView(text("FIELD NETWORK STATUS", 8f, dim).apply { letterSpacing = .13f })
        networkSummary = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel2, 14f, Color.rgb(35, 46, 51))
            setOnClickListener { refreshNetworkSummary(); showNetworkInfo() }
        }
        body.addView(networkSummary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        body.addView(text("START HERE", 8f, dim).apply { letterSpacing = .13f }, top(14))
        body.addView(workflowCard(
            "DISCOVER NETWORK",
            "Find PCs, printers, servers and other reachable devices on this Wi-Fi/LAN. Identifies common services automatically.",
            "SCAN LAN"
        ) { startLanDiscovery() }, top(6))
        body.addView(workflowCard(
            "WI-FI SURVEY",
            "See nearby access points, signal strength, security, channel congestion and practical channel guidance.",
            "SURVEY"
        ) { startWifiSurvey() }, top(7))
        body.addView(workflowCard(
            "INTERNET CHECK",
            "Verify gateway, DNS resolution, outside connectivity and latency in one technician-friendly test.",
            "RUN CHECK"
        ) { runInternetCheck() }, top(7))
        body.addView(workflowCard(
            "THIS DEVICE",
            "Show Android, kernel, battery, storage, network interface, addresses, DNS and routes.",
            "DIAGNOSE"
        ) { showDeviceDiagnostics() }, top(7))

        body.addView(text("TARGET TOOL", 8f, dim).apply { letterSpacing = .13f }, top(16))
        body.addView(text("Enter a device, hostname or website when you want to inspect one specific target.", 9f, dim), top(4))

        val hostRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostInput = EditText(this).apply {
            hint = "192.168.1.25 / printer.local / website.com"
            setHintTextColor(Color.rgb(104, 113, 120))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(panel, 12f, Color.rgb(39, 53, 58))
        }
        hostRow.addView(hostInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        portInput = EditText(this).apply {
            hint = "port"
            setHintTextColor(Color.rgb(104, 113, 120))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(panel, 12f, Color.rgb(39, 53, 58))
        }
        hostRow.addView(portInput, LinearLayout.LayoutParams(dp(70), dp(48)).apply { leftMargin = dp(6) })
        body.addView(hostRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        val actions = GridLayout(this).apply { columnCount = 3 }
        actions.addView(actionButton("IDENTIFY") { identifyTarget() }, actionCell())
        actions.addView(actionButton("PING") { runPing() }, actionCell())
        actions.addView(actionButton("SERVICES") { scanTargetServices() }, actionCell())
        actions.addView(actionButton("DNS") { runDns() }, actionCell())
        actions.addView(actionButton("PORT") { runPortTest() }, actionCell())
        actions.addView(actionButton("HTTP") { runHttpCheck() }, actionCell())
        body.addView(actions, top(6))

        body.addView(text("RESULTS", 8f, dim).apply { letterSpacing = .13f }, top(16))
        output = TextView(this).apply {
            setTextColor(Color.rgb(219, 229, 233))
            textSize = 11.3f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(13), dp(14), dp(18))
            background = rounded(panel, 14f, Color.rgb(30, 41, 45))
        }
        body.addView(output, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val utilities = GridLayout(this).apply { columnCount = 3 }
        utilities.addView(actionButton("NET DETAILS") { showNetworkInfo() }, actionCell())
        utilities.addView(actionButton("SETTINGS") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, actionCell())
        utilities.addView(actionButton("CLEAR") { output.text = "" }, actionCell())
        body.addView(utilities, top(8))

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showWelcome() {
        replaceOutput(
            "TECH DECK READY\n\n" +
                "Recommended workflow:\n" +
                "1. DISCOVER NETWORK to inventory the local LAN.\n" +
                "2. WI-FI SURVEY for signal/channel problems.\n" +
                "3. INTERNET CHECK when the complaint is ‘the internet is slow/down.’\n" +
                "4. Tap a discovered IP into TARGET TOOL for deeper inspection.\n\n" +
                "BLACKLINE only scans the local network you are currently connected to.\n"
        )
    }

    private fun refreshNetworkSummary() {
        executor.execute {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val lp = network?.let { cm.getLinkProperties(it) }
            val type = networkType(caps)
            val ip = localIpv4()?.hostAddress ?: "No IPv4"
            val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "--"
            val dns = lp?.dnsServers?.firstOrNull()?.hostAddress ?: "--"
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val summary = "$type  •  ${if (validated) "INTERNET OK" else "CHECK INTERNET"}\nIP $ip   GW $gateway\nDNS $dns"
            runOnUiThread { networkSummary.text = summary }
        }
    }

    private fun startWifiSurvey() {
        if (!hasWifiPermissions()) {
            val needed = mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), WIFI_PERMISSIONS)
            return
        }
        runTask("SURVEY") { performWifiSurvey() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WIFI_PERMISSIONS) {
            if (hasWifiPermissions()) runTask("SURVEY") { performWifiSurvey() }
            else replaceOutput("WI-FI SURVEY\n\nPermission denied. Nearby Wi-Fi scanning requires Android Wi-Fi/location permission.\n")
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
        Thread.sleep(1200)
        val results = runCatching { wm.scanResults }.getOrDefault(emptyList()).sortedByDescending { it.level }
        val current = runCatching { wm.connectionInfo }.getOrNull()
        val counts = results.groupingBy { channelFor(it.frequency) }.eachCount()
        val recommended24 = listOf(1, 6, 11).minByOrNull { counts[it] ?: 0 }
        val strongest5 = results.filter { bandFor(it.frequency) == "5G" }.groupBy { channelFor(it.frequency) }
            .minByOrNull { (_, aps) -> aps.sumOf { max(1, 100 + it.level) } }?.key

        val out = buildString {
            append("WI-FI SURVEY // ${stamp()}\n\n")
            if (current != null) {
                val ssid = current.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "current network"
                append("CONNECTED\n")
                append("  $ssid  ${current.rssi} dBm  ${signalWord(current.rssi)}\n")
                append("  ${current.frequency} MHz  ch ${channelFor(current.frequency)}\n\n")
            }
            append("FOUND ${results.size} ACCESS POINTS\n")
            append("Best 2.4 GHz starting point: channel ${recommended24 ?: "--"}\n")
            if (strongest5 != null) append("Least-loaded observed 5 GHz channel: $strongest5\n")
            append("\nCHANNEL LOAD\n")
            counts.entries.sortedBy { it.key }.forEach { (ch, count) ->
                if (ch > 0) append("  CH ${ch.toString().padStart(3)}  ${bar(count.coerceAtMost(10), 10)}  $count AP${if (count == 1) "" else "s"}\n")
            }
            append("\nNEARBY NETWORKS — strongest first\n")
            results.take(60).forEachIndexed { index, r ->
                val ssid = r.SSID.takeIf { it.isNotBlank() } ?: "<hidden>"
                append(String.format(Locale.US, "%02d  %-20s %4d dBm  ch %-3d  %-4s  %s\n", index + 1, ssid.take(20), r.level, channelFor(r.frequency), bandFor(r.frequency), securityLabel(r.capabilities)))
                append("    ${signalWord(r.level)}  ${r.BSSID}\n")
            }
            append("\nTECH NOTE\n")
            append("This is an Android Wi-Fi survey. Raw 802.11 monitor-mode packet capture requires compatible root/kernel or external Wi-Fi hardware.\n")
        }
        runOnUiThread { replaceOutput(out) }
    }

    private fun startLanDiscovery() {
        runTask("SCANNING") {
            val local = localIpv4()
            val base = local?.hostAddress?.substringBeforeLast('.')
            if (base == null) {
                runOnUiThread { replaceOutput("DISCOVER NETWORK\n\nNo local IPv4 address found. Connect to Wi-Fi/Ethernet first.\n") }
                return@runTask
            }
            runOnUiThread { replaceOutput("DISCOVER NETWORK // $base.0/24\n\nLooking for devices and common services…\n") }

            data class Host(val ip: String, val name: String, val services: List<String>, val ping: Boolean)
            val pool = Executors.newFixedThreadPool(40)
            val found = java.util.Collections.synchronizedList(mutableListOf<Host>())
            val tasks = (1..254).map { n ->
                pool.submit {
                    val ip = "$base.$n"
                    val ping = pingOnce(ip, 550)
                    val ports = if (ping) scanCommonPorts(ip, 130) else scanCommonPorts(ip, 85, listOf(80, 443, 445, 22, 3389, 9100, 631))
                    if (ping || ports.isNotEmpty()) {
                        val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip)
                        val services = ports.mapNotNull { COMMON_SERVICES[it] }.distinct()
                        found += Host(ip, name, services, ping)
                    }
                }
            }
            tasks.forEach { runCatching { it.get(12, TimeUnit.SECONDS) } }
            pool.shutdownNow()

            val sorted = found.distinctBy { it.ip }.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
            val gateway = activeGateway()
            val me = local.hostAddress
            val out = buildString {
                append("NETWORK DISCOVERY COMPLETE\n")
                append("Subnet      $base.0/24\n")
                append("Devices     ${sorted.size}\n")
                append("This phone  $me\n")
                append("Gateway     ${gateway ?: "--"}\n\n")
                if (sorted.isEmpty()) {
                    append("No responsive devices were found. Some networks isolate clients or block probes.\n")
                } else {
                    sorted.forEachIndexed { index, h ->
                        val tags = mutableListOf<String>()
                        if (h.ip == gateway) tags += "GATEWAY"
                        if (h.ip == me) tags += "THIS PHONE"
                        tags += h.services
                        append("${index + 1}. ${h.ip}")
                        if (h.name != h.ip) append("  ${h.name}")
                        append("\n")
                        append("   ${if (h.ping) "reachable" else "service detected"}")
                        if (tags.isNotEmpty()) append("  •  ${tags.joinToString("  •  ")}")
                        append("\n")
                    }
                    append("\nTIP: enter any IP above in TARGET TOOL and tap IDENTIFY or SERVICES for a closer look.\n")
                }
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runInternetCheck() {
        runTask("TESTING") {
            val gateway = activeGateway()
            val gwOk = gateway?.let { pingOnce(it, 900) } ?: false
            val dnsStart = System.nanoTime()
            val dnsResult = runCatching { InetAddress.getAllByName("example.com") }.getOrNull()
            val dnsMs = (System.nanoTime() - dnsStart) / 1_000_000
            val ping1 = pingLatency("1.1.1.1")
            val ping2 = pingLatency("8.8.8.8")
            val http = httpHead("https://example.com")
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            val overall = validated && dnsResult != null && http.first in 200..399
            val out = buildString {
                append("INTERNET CHECK // ${if (overall) "PASS" else "ATTENTION"}\n\n")
                append("LOCAL LINK\n")
                append("  Gateway ${gateway ?: "--"}   ${if (gwOk) "REACHABLE" else "NO PING / BLOCKED"}\n")
                append("\nDNS\n")
                append("  ${if (dnsResult != null) "PASS" else "FAIL"}   ${dnsMs} ms")
                if (dnsResult != null) append("   ${dnsResult.firstOrNull()?.hostAddress ?: ""}")
                append("\n\nLATENCY\n")
                append("  1.1.1.1   ${ping1 ?: "no reply"}\n")
                append("  8.8.8.8   ${ping2 ?: "no reply"}\n")
                append("\nWEB\n")
                append("  example.com   HTTP ${http.first}   ${http.second} ms\n")
                append("\nANDROID VALIDATED INTERNET   ${if (validated) "YES" else "NO"}\n\n")
                append(when {
                    overall -> "RESULT: Internet path looks healthy.\n"
                    dnsResult == null -> "RESULT: Link may be up, but DNS resolution is failing. Check DNS/router settings.\n"
                    !validated -> "RESULT: Android does not consider this connection fully online. Check gateway/ISP/captive portal.\n"
                    else -> "RESULT: Connectivity is partial. Review failed tests above.\n"
                })
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun identifyTarget() {
        val host = cleanHost(hostInput.text.toString())
        if (host.isBlank()) return replaceOutput("IDENTIFY TARGET\n\nEnter an IP or hostname first.\n")
        runTask("IDENTIFY") {
            val resolved = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            val ip = resolved.firstOrNull()?.hostAddress ?: host
            val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip)
            val latency = pingLatency(ip)
            val ports = scanCommonPorts(ip, 300)
            val out = buildString {
                append("TARGET IDENTIFICATION\n\n")
                append("Input       $host\n")
                append("Address     $ip\n")
                append("Hostname    $name\n")
                append("Ping        ${latency ?: "no reply"}\n")
                append("\nDETECTED SERVICES\n")
                if (ports.isEmpty()) append("  No common TCP services detected.\n")
                ports.forEach { p -> append("  $p   ${COMMON_SERVICES[p] ?: "OPEN"}\n") }
                append("\n")
                append(serviceAdvice(ports))
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun scanTargetServices() {
        val host = cleanHost(hostInput.text.toString())
        if (host.isBlank()) return replaceOutput("SERVICE CHECK\n\nEnter an IP or hostname first.\n")
        runTask("SERVICES") {
            val ports = scanCommonPorts(host, 450)
            val out = buildString {
                append("COMMON SERVICE CHECK // $host\n\n")
                if (ports.isEmpty()) append("No common TCP services responded.\n")
                else ports.forEach { p -> append("OPEN  ${p.toString().padEnd(5)} ${COMMON_SERVICES[p] ?: "TCP SERVICE"}\n") }
                append("\n")
                append(serviceAdvice(ports))
                append("\nThis is a focused technician service check, not an exhaustive port scan.\n")
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun showNetworkInfo() {
        runTask("NETWORK") {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val lp = network?.let { cm.getLinkProperties(it) }
            val out = buildString {
                append("NETWORK DETAILS // ${stamp()}\n\n")
                append("Type        ${networkType(caps)}\n")
                append("Validated   ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}\n")
                append("Interface   ${lp?.interfaceName ?: "--"}\n")
                append("Gateway     ${lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "--"}\n")
                append("Downstream  ${caps?.linkDownstreamBandwidthKbps ?: 0} Kbps estimate\n")
                append("Upstream    ${caps?.linkUpstreamBandwidthKbps ?: 0} Kbps estimate\n")
                append("\nADDRESSES\n")
                lp?.linkAddresses?.forEach { append("  $it\n") }
                append("\nDNS\n")
                lp?.dnsServers?.forEach { append("  ${it.hostAddress}\n") }
                append("\nROUTES\n")
                lp?.routes?.take(20)?.forEach { append("  $it\n") }
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun showDeviceDiagnostics() {
        runTask("DEVICE") {
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val stat = StatFs(File("/sdcard").absolutePath)
            val kernel = runCatching { ProcessBuilder("uname", "-a").start().inputStream.bufferedReader().readText().trim() }.getOrDefault("unknown")
            val out = buildString {
                append("THIS DEVICE // ${stamp()}\n\n")
                append("Device      ${Build.MANUFACTURER} ${Build.MODEL}\n")
                append("Android     ${Build.VERSION.RELEASE} // API ${Build.VERSION.SDK_INT}\n")
                append("Arch        ${Build.SUPPORTED_ABIS.joinToString()}\n")
                append("Kernel      $kernel\n")
                append("Battery     $level%   %.1f C   %d mV\n".format(temp, voltage))
                append("Storage     ${formatBytes(stat.availableBytes)} free / ${formatBytes(stat.totalBytes)}\n")
                append("Uptime      ${formatDuration(android.os.SystemClock.elapsedRealtime())}\n\n")
                append("NETWORK\n")
                append("  IPv4      ${localIpv4()?.hostAddress ?: "--"}\n")
                append("  Gateway   ${activeGateway() ?: "--"}\n")
            }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runPing() {
        val host = cleanHost(hostInput.text.toString())
        if (host.isBlank()) return replaceOutput("PING\n\nEnter a host or IP first.\n")
        runTask("PING") {
            val out = runCatching {
                val p = ProcessBuilder("/system/bin/ping", "-c", "4", "-W", "2", host).redirectErrorStream(true).start()
                val data = p.inputStream.bufferedReader().readText()
                p.waitFor(12, TimeUnit.SECONDS)
                "PING // $host\n\n$data"
            }.getOrElse { "PING // $host\n\n${it.message}\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runDns() {
        val host = cleanHost(hostInput.text.toString())
        if (host.isBlank()) return replaceOutput("DNS LOOKUP\n\nEnter a hostname first.\n")
        runTask("DNS") {
            val start = System.nanoTime()
            val out = runCatching {
                val results = InetAddress.getAllByName(host)
                val ms = (System.nanoTime() - start) / 1_000_000
                buildString {
                    append("DNS LOOKUP // $host\n\n")
                    append("Resolved in $ms ms\n\n")
                    results.distinctBy { it.hostAddress }.forEach { append("${it.hostAddress}\n") }
                }
            }.getOrElse { "DNS LOOKUP // $host\n\n${it.message}\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun runPortTest() {
        val host = cleanHost(hostInput.text.toString())
        val port = portInput.text.toString().trim().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) return replaceOutput("TCP PORT TEST\n\nEnter a host and valid port.\n")
        runTask("PORT") {
            val start = System.nanoTime()
            val open = tcpOpen(host, port, 2500)
            val ms = (System.nanoTime() - start) / 1_000_000
            val known = COMMON_SERVICES[port]?.let { " ($it)" }.orEmpty()
            runOnUiThread { replaceOutput("TCP PORT TEST\n\n$host:$port$known\n${if (open) "OPEN" else "CLOSED / FILTERED / UNREACHABLE"}\n${ms} ms\n") }
        }
    }

    private fun runHttpCheck() {
        val raw = hostInput.text.toString().trim()
        if (raw.isBlank()) return replaceOutput("HTTP CHECK\n\nEnter a hostname or URL.\n")
        runTask("HTTP") {
            val target = if (raw.contains("://")) raw else "https://$raw"
            val out = try {
                val c = (URL(target).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BLACKLINE-Tech/0.9")
                }
                val start = System.nanoTime()
                c.connect()
                val ms = (System.nanoTime() - start) / 1_000_000
                buildString {
                    append("HTTP CHECK\n\n$target\n")
                    append("Status      ${c.responseCode} ${c.responseMessage}\n")
                    append("Time        $ms ms\n")
                    append("Server      ${c.getHeaderField("Server") ?: "--"}\n")
                    append("Type        ${c.contentType ?: "--"}\n")
                    append("Redirect    ${c.getHeaderField("Location") ?: "--"}\n")
                }.also { c.disconnect() }
            } catch (e: Exception) { "HTTP CHECK\n\n$target\n${e.message}\n" }
            runOnUiThread { replaceOutput(out) }
        }
    }

    private fun scanCommonPorts(host: String, timeoutMs: Int, subset: List<Int> = COMMON_SERVICES.keys.toList()): List<Int> =
        subset.filter { tcpOpen(host, it, timeoutMs) }

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) { false }

    private fun pingOnce(host: String, timeoutMs: Int): Boolean = try {
        val seconds = max(1, timeoutMs / 1000)
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", seconds.toString(), host).redirectErrorStream(true).start()
        p.waitFor(timeoutMs.toLong() + 350, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }

    private fun pingLatency(host: String): String? = runCatching {
        val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(3, TimeUnit.SECONDS)
        Regex("time[=<]([0-9.]+)\\s*ms").find(out)?.groupValues?.getOrNull(1)?.let { "$it ms" }
    }.getOrNull()

    private fun httpHead(target: String): Pair<Int, Long> = try {
        val c = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BLACKLINE-Tech/0.9")
        }
        val start = System.nanoTime()
        c.connect()
        val code = c.responseCode
        val ms = (System.nanoTime() - start) / 1_000_000
        c.disconnect()
        code to ms
    } catch (_: Exception) { 0 to -1L }

    private fun serviceAdvice(ports: List<Int>): String = buildString {
        when {
            9100 in ports || 631 in ports -> append("Likely printer/print server. Try its IP in a browser and verify printer queues/settings.\n")
            445 in ports || 139 in ports -> append("SMB/file sharing detected. This may be a PC, NAS or file server.\n")
            3389 in ports -> append("RDP detected. Likely Windows PC/server with Remote Desktop enabled.\n")
            22 in ports -> append("SSH detected. Likely Linux/macOS/network appliance/server.\n")
            80 in ports || 443 in ports || 8080 in ports -> append("Web interface detected. Try HTTP/HTTPS to inspect its management page.\n")
            else -> append("No obvious device role from common TCP services.\n")
        }
    }

    private fun cleanHost(raw: String): String = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')
        .trim()

    private fun localIpv4(): Inet4Address? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    private fun activeGateway(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
    }

    private fun networkType(caps: NetworkCapabilities?): String = when {
        caps == null -> "OFFLINE"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> "ONLINE"
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

    private fun runTask(label: String, block: () -> Unit) {
        statusChip.text = label
        statusChip.setTextColor(Color.WHITE)
        executor.execute {
            try { block() }
            finally { runOnUiThread { statusChip.text = "READY"; statusChip.setTextColor(accent); refreshNetworkSummary() } }
        }
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

    private fun workflowCard(title: String, description: String, button: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(panel2, 14f, Color.rgb(35, 46, 51))
        addView(text(title, 11f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        addView(text(description, 9.4f, dim).apply { setLineSpacing(dp(1).toFloat(), 1f) }, top(4))
        addView(TextView(this@TechToolsActivity).apply {
            text = button
            gravity = Gravity.CENTER
            textSize = 8.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = rounded(accent, 11f, accent)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(dp(92), dp(36)).apply { topMargin = dp(9) })
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 8.3f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(accent)
        background = rounded(panel, 10f, Color.rgb(34, 49, 52))
        setOnClickListener { action() }
    }

    private fun chipView(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 8.2f
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

    private fun actionCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(44)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun top(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
