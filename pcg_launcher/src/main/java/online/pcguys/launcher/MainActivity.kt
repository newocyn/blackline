package online.pcguys.launcher

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val accent = Color.rgb(0, 245, 212)
    private val bg = Color.rgb(5, 5, 5)
    private val panel = Color.rgb(14, 14, 16)
    private val panel2 = Color.rgb(20, 20, 24)
    private val dim = Color.rgb(145, 150, 156)
    private val prefs by lazy { getSharedPreferences("pcg_launcher", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private var currentPage = "home"
    private var apps: List<ResolveInfo> = emptyList()
    private var battery = 0
    private var charging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            battery = if (level >= 0) (level * 100 / scale) else 0
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (currentPage == "home") showHome()
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (currentPage == "home") showHome()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        apps = loadApps()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(clockTick)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        apps = loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(clockTick)
    }

    private fun shell(content: View, page: String) {
        currentPage = page
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = dp(76) })
        root.addView(bottomNav(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM))
        setContentView(root)
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
            background = rounded(panel, 22f, Color.rgb(35,35,40), 1)
        }
        nav.addView(navButton("⌂", "HOME", currentPage == "home") { showHome() }, weight())
        nav.addView(navButton("▦", "APPS", currentPage == "apps") { showApps() }, weight())
        nav.addView(navButton("⌘", "CONTROL", currentPage == "control") { showControl() }, weight())
        return nav
    }

    private fun navButton(icon: String, label: String, active: Boolean, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (active) rounded(Color.rgb(24,30,30), 15f, accent, 1) else rounded(Color.TRANSPARENT, 15f, Color.TRANSPARENT, 0)
            setOnClickListener { action() }
        }
        box.addView(TextView(this).apply { text = icon; textSize = 22f; setTextColor(if (active) accent else Color.WHITE); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply { text = label; textSize = 10f; letterSpacing = .12f; setTextColor(if (active) accent else dim); gravity = Gravity.CENTER })
        return box
    }

    private fun showHome() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }

        val now = Date()
        body.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.US).format(now)
            textSize = 56f
            setTextColor(Color.WHITE)
            letterSpacing = -.03f
        })
        body.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE  //  MMM dd", Locale.US).format(now).uppercase(Locale.US)
            textSize = 13f
            letterSpacing = .14f
            setTextColor(accent)
        })

        body.addView(searchBar(), marginTop(18))
        body.addView(statusStrip(), marginTop(14))

        if (prefs.getBoolean("module_favorites", true)) body.addView(favoritesModule(), marginTop(14))
        if (prefs.getBoolean("module_quick", true)) body.addView(quickModule(), marginTop(14))
        if (prefs.getBoolean("module_system", true)) body.addView(systemModule(), marginTop(14))
        if (prefs.getBoolean("module_tools", true)) body.addView(toolModule(), marginTop(14))

        scroll.addView(body)
        shell(scroll, "home")
    }

    private fun searchBar(): View {
        val input = EditText(this).apply {
            hint = "SEARCH APPS // COMMAND"
            setHintTextColor(Color.rgb(95,100,105))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(panel, 16f, Color.rgb(45,48,52), 1)
            setOnEditorActionListener { _, _, _ ->
                if (text.isNotBlank()) showApps(text.toString())
                true
            }
        }
        return input
    }

    private fun statusStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val network = networkLabel()
        row.addView(metric("BATTERY", "$battery%${if (charging) " +" else ""}"), weight(1f, 5))
        row.addView(metric("NETWORK", network), weight(1f, 5))
        row.addView(metric("APPS", apps.size.toString()), weight(1f, 0))
        return row
    }

    private fun metric(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(panel, 14f, Color.rgb(38,40,44), 1)
            addView(TextView(this@MainActivity).apply { text = label; textSize = 9f; letterSpacing = .12f; setTextColor(dim) })
            addView(TextView(this@MainActivity).apply { text = value; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, 1) })
        }
    }

    private fun favoritesModule(): View {
        val card = moduleCard("FAVORITES", "LONG-PRESS AN APP TO PIN / UNPIN")
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        val selected = apps.filter { favorites.contains(it.activityInfo.packageName) }.take(8)
        if (selected.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "No pinned apps yet. Open APPS and long-press anything to pin it here."
                textSize = 14f; setTextColor(dim); setPadding(0, dp(10), 0, dp(4))
            })
        } else card.addView(appGrid(selected, 4, compact = true), marginTop(10))
        return card
    }

    private fun quickModule(): View {
        val card = moduleCard("QUICK CONTROL", "SYSTEM PANELS")
        val grid = GridLayout(this).apply { columnCount = 2 }
        val items = listOf(
            Triple("WI-FI", "NETWORK CONTROL", Settings.Panel.ACTION_WIFI),
            Triple("BLUETOOTH", "DEVICE CONTROL", Settings.ACTION_BLUETOOTH_SETTINGS),
            Triple("DISPLAY", "BRIGHTNESS + SCREEN", Settings.ACTION_DISPLAY_SETTINGS),
            Triple("SOUND", "VOLUME + AUDIO", Settings.ACTION_SOUND_SETTINGS),
            Triple("BATTERY", "POWER CONTROL", Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Triple("PRIVACY", "PERMISSIONS", Settings.ACTION_PRIVACY_SETTINGS)
        )
        items.forEach { item -> grid.addView(controlTile(item.first, item.second) { openSettings(item.third) }, gridCell()) }
        card.addView(grid, marginTop(8))
        return card
    }

    private fun systemModule(): View {
        val card = moduleCard("SYSTEM", "LIVE DEVICE STATUS")
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val dev = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        card.addView(infoLine("BATTERY", "$battery% ${if (charging) "// CHARGING" else "// DISCHARGING"}"))
        card.addView(infoLine("NETWORK", networkLabel()))
        card.addView(infoLine("VPN", if (vpn) "ACTIVE" else "NOT ACTIVE"))
        card.addView(infoLine("DEV MODE", if (dev) "ENABLED" else "OFF"))
        card.addView(infoLine("ANDROID", Build.VERSION.RELEASE ?: "?"))
        return card
    }

    private fun toolModule(): View {
        val card = moduleCard("TOOLS", "FAST ENTRY POINTS")
        val grid = GridLayout(this).apply { columnCount = 2 }
        grid.addView(controlTile("CAMERA", "CAPTURE") { launchIntent(Intent(MediaStoreIntent.CAMERA)) }, gridCell())
        grid.addView(controlTile("CLOCK", "ALARMS + TIMER") { launchIntent(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }, gridCell())
        grid.addView(controlTile("FILES", "STORAGE") { launchIntent(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }, gridCell())
        grid.addView(controlTile("SETTINGS", "ANDROID CORE") { openSettings(Settings.ACTION_SETTINGS) }, gridCell())
        card.addView(grid, marginTop(8))
        return card
    }

    private object MediaStoreIntent { const val CAMERA = "android.media.action.IMAGE_CAPTURE" }

    private fun showApps(initial: String = "") {
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        main.addView(pageHeader("APPLICATION MATRIX", "${apps.size} LAUNCHABLE APPS"))
        val search = EditText(this).apply {
            hint = "FILTER APPS"
            setHintTextColor(Color.rgb(95,100,105)); setTextColor(Color.WHITE); textSize = 15f; setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0); background = rounded(panel, 16f, Color.rgb(45,48,52), 1)
            setText(initial)
        }
        main.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        val scroll = ScrollView(this)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(holder)
        main.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        fun render(q: String) {
            holder.removeAllViews()
            val filtered = apps.filter { it.loadLabel(packageManager).toString().contains(q, true) }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
            holder.addView(appGrid(filtered, 4, compact = false))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render(initial)
        shell(main, "apps")
    }

    private fun appGrid(list: List<ResolveInfo>, columns: Int, compact: Boolean): View {
        val grid = GridLayout(this).apply { columnCount = columns; alignmentMode = GridLayout.ALIGN_BOUNDS }
        val favorites = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        list.forEach { info ->
            val pkg = info.activityInfo.packageName
            val app = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(8))
                background = rounded(if (favorites.contains(pkg)) Color.rgb(18,26,25) else panel, 15f, if (favorites.contains(pkg)) accent else Color.rgb(33,35,39), 1)
                setOnClickListener { launch(info) }
                setOnLongClickListener {
                    if (favorites.contains(pkg)) favorites.remove(pkg) else favorites.add(pkg)
                    prefs.edit().putStringSet("favorites", favorites).apply()
                    if (currentPage == "apps") showApps() else showHome()
                    true
                }
            }
            app.addView(ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(if (compact) 38 else 46), dp(if (compact) 38 else 46)))
            app.addView(TextView(this).apply {
                text = info.loadLabel(packageManager).toString()
                maxLines = 2; gravity = Gravity.CENTER; textSize = if (compact) 10f else 11f; setTextColor(Color.WHITE)
                setPadding(dp(2), dp(5), dp(2), 0)
            })
            grid.addView(app, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(if (compact) 88 else 105)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        return grid
    }

    private fun showControl() {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(30)) }
        body.addView(pageHeader("CONTROL DECK", "MODULAR LAUNCHER CONFIGURATION"))
        body.addView(primary("SET AS DEFAULT HOME") { requestHomeRole() }, marginTop(16))
        body.addView(controlSection("HOME MODULES",
            settingToggle("Favorites", "module_favorites", true),
            settingToggle("Quick Control", "module_quick", true),
            settingToggle("System Status", "module_system", true),
            settingToggle("Tools", "module_tools", true)
        ), marginTop(14))
        body.addView(controlSection("SYSTEM ENTRY POINTS",
            settingButton("Default apps") { openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) },
            settingButton("Notifications") { openSettings("android.settings.NOTIFICATION_SETTINGS") },
            settingButton("Permissions") { openSettings(Settings.ACTION_APPLICATION_SETTINGS) },
            settingButton("Accessibility") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            settingButton("Developer options") { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        ), marginTop(14))
        body.addView(controlSection("LAUNCHER DATA",
            settingButton("Clear favorites") { prefs.edit().remove("favorites").apply(); showControl() },
            settingButton("Reset modules") {
                prefs.edit().remove("module_favorites").remove("module_quick").remove("module_system").remove("module_tools").apply(); showControl()
            }
        ), marginTop(14))
        body.addView(TextView(this).apply {
            text = "PCG LAUNCHER // v1.0\nAndroid restricts third-party launchers from silently changing protected system settings. Control tiles open the corresponding system panels instead."
            textSize = 11f; setTextColor(Color.rgb(95,100,105)); setPadding(dp(4), dp(20), dp(4), dp(4))
        })
        scroll.addView(body)
        shell(scroll, "control")
    }

    private fun settingToggle(label: String, key: String, default: Boolean): View {
        val row = settingRow(label)
        val value = TextView(this).apply {
            fun refresh() { text = if (prefs.getBoolean(key, default)) "ON" else "OFF"; setTextColor(if (prefs.getBoolean(key, default)) accent else dim) }
            refresh()
            setTypeface(typeface, 1); textSize = 13f
            setOnClickListener { prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply(); refresh() }
        }
        row.addView(value)
        row.setOnClickListener { value.performClick() }
        return row
    }

    private fun settingButton(label: String, action: () -> Unit): View {
        val row = settingRow(label)
        row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(accent) })
        row.setOnClickListener { action() }
        return row
    }

    private fun settingRow(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(13), 0, dp(13))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 15f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun controlSection(title: String, vararg rows: View): View {
        val card = moduleCard(title, "")
        rows.forEach { card.addView(it) }
        return card
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    AlertDialog.Builder(this).setTitle("Already active").setMessage("PCG Launcher is already the default Home app.").setPositiveButton("OK", null).show()
                } else startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 440)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun loadApps(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
    }

    private fun launch(info: ResolveInfo) {
        val intent = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
        if (intent != null) startActivity(intent)
    }

    private fun launchIntent(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun openSettings(action: String) = launchIntent(Intent(action))

    private fun networkLabel(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETH"
                else -> "ONLINE"
            }
        } catch (_: Exception) { "?" }
    }

    private fun moduleCard(title: String, sub: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(panel, 18f, Color.rgb(35,38,42), 1)
            addView(TextView(this@MainActivity).apply { text = title; textSize = 13f; letterSpacing = .13f; setTextColor(accent); setTypeface(typeface, 1) })
            if (sub.isNotBlank()) addView(TextView(this@MainActivity).apply { text = sub; textSize = 9f; letterSpacing = .08f; setTextColor(dim); setPadding(0, dp(2), 0, 0) })
        }
    }

    private fun controlTile(title: String, sub: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13)); background = rounded(panel2, 14f, Color.rgb(44,47,51), 1)
            addView(TextView(this@MainActivity).apply { text = title; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, 1) })
            addView(TextView(this@MainActivity).apply { text = sub; textSize = 9f; setTextColor(dim) })
            setOnClickListener { action() }
        }
    }

    private fun infoLine(k: String, v: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(2))
            addView(TextView(this@MainActivity).apply { text = k; textSize = 11f; setTextColor(dim) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply { text = v; textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, 1) })
        }
    }

    private fun pageHeader(title: String, sub: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = title; textSize = 25f; setTextColor(Color.WHITE); setTypeface(typeface, 1) })
            addView(TextView(this@MainActivity).apply { text = sub; textSize = 10f; letterSpacing = .14f; setTextColor(accent) })
        }
    }

    private fun primary(label: String, action: () -> Unit): View = Button(this).apply {
        text = label; setTextColor(bg); textSize = 13f; setTypeface(typeface, 1); background = rounded(accent, 14f, accent, 0); setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (width > 0) setStroke(dp(width), stroke)
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun weight(w: Float = 1f, marginEnd: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply { if (marginEnd > 0) rightMargin = dp(marginEnd) }
    private fun gridCell() = GridLayout.LayoutParams().apply { width = 0; height = dp(66); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
