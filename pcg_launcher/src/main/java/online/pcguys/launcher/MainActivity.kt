package online.pcguys.launcher

import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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
    private val hot = Color.rgb(255, 45, 149)
    private val bg = Color.rgb(3, 4, 6)
    private val panel = Color.rgb(10, 12, 16)
    private val panel2 = Color.rgb(16, 19, 25)
    private val dim = Color.rgb(139, 150, 160)
    private val prefs by lazy { getSharedPreferences("pcg_launcher", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private var currentPage = "home"
    private var battery = 0
    private var charging = false
    private var appsReady = false
    private var appEntries: List<AppEntry> = emptyList()
    private var lastSearch = ""

    data class AppEntry(
        val info: ResolveInfo,
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    data class Command(
        val title: String,
        val subtitle: String,
        val keywords: String,
        val action: () -> Unit
    )

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
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            window.attributes = attrs
        }
        showHome()
        refreshAppsAsync()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        refreshAppsAsync()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(clockTick)
    }

    private fun refreshAppsAsync() {
        Thread {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val entries = packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map {
                        AppEntry(
                            info = it,
                            label = it.loadLabel(packageManager).toString(),
                            packageName = it.activityInfo.packageName,
                            icon = it.loadIcon(packageManager)
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.US) }
                runOnUiThread {
                    appEntries = entries
                    appsReady = true
                    if (currentPage == "apps") showApps(lastSearch)
                    else if (currentPage == "home") showHome()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun shell(content: View, page: String) {
        currentPage = page
        root = FrameLayout(this).apply {
            setBackgroundColor(bg)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            bottomMargin = dp(78)
        })
        root.addView(bottomNav(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76), Gravity.BOTTOM).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(6)
        })
        setContentView(root)
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = gradientPanel()
        }
        nav.addView(navButton("⌂", "HOME", currentPage == "home") { showHome() }, weight())
        nav.addView(navButton("⌕", "SEARCH", currentPage == "search") { showUniversalSearch("") }, weight())
        nav.addView(navButton("▦", "APPS", currentPage == "apps") { showApps() }, weight())
        nav.addView(navButton("⌘", "CONTROL", currentPage == "control") { showControl() }, weight())
        return nav
    }

    private fun navButton(icon: String, label: String, active: Boolean, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (active) rounded(Color.rgb(18, 30, 31), 14f, accent, 1) else rounded(Color.TRANSPARENT, 14f, Color.TRANSPARENT, 0)
            setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(if (active) accent else Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 8.5f; letterSpacing = .12f; gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                setTextColor(if (active) accent else dim)
            })
        }
    }

    private fun showHome() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(30))
        }

        body.addView(systemBanner())

        val now = Date()
        body.addView(TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.US).format(now)
            textSize = 54f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = -.03f
            setPadding(0, dp(8), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = SimpleDateFormat("EEEE  //  MMM dd", Locale.US).format(now).uppercase(Locale.US)
            textSize = 12f
            letterSpacing = .15f
            typeface = Typeface.MONOSPACE
            setTextColor(accent)
        })

        body.addView(searchBar(), marginTop(16))
        body.addView(statusStrip(), marginTop(12))

        if (prefs.getBoolean("module_favorites", true)) body.addView(favoritesModule(), marginTop(12))
        if (prefs.getBoolean("module_quick", true)) body.addView(quickModule(), marginTop(12))
        if (prefs.getBoolean("module_system", true)) body.addView(systemModule(), marginTop(12))
        if (prefs.getBoolean("module_tools", true)) body.addView(toolModule(), marginTop(12))

        scroll.addView(body)
        shell(scroll, "home")
    }

    private fun systemBanner(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(8, 17, 19), 12f, accent, 1)
            addView(TextView(this@MainActivity).apply {
                text = "PCG // NEURAL HOME"
                textSize = 10f; letterSpacing = .15f; typeface = Typeface.MONOSPACE; setTextColor(accent)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (appsReady) "ONLINE" else "INDEXING"
                textSize = 9f; letterSpacing = .12f; typeface = Typeface.MONOSPACE; setTextColor(if (appsReady) accent else hot)
            })
        }
    }

    private fun searchBar(): View {
        return EditText(this).apply {
            hint = "SEARCH ANYTHING // APPS // SETTINGS // WEB"
            setHintTextColor(Color.rgb(90, 103, 114))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(7, 11, 15), 16f, accent, 1)
            setOnEditorActionListener { _, _, _ ->
                val q = text.toString().trim()
                if (q.isNotBlank()) showUniversalSearch(q)
                true
            }
        }
    }

    private fun statusStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(metric("BAT", "$battery%${if (charging) "+" else ""}"), weight(1f, 5))
        row.addView(metric("LINK", networkLabel()), weight(1f, 5))
        row.addView(metric("APPS", if (appsReady) appEntries.size.toString() else "…"), weight(1f, 0))
        return row
    }

    private fun metric(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(panel, 13f, Color.rgb(35, 43, 50), 1)
            addView(TextView(this@MainActivity).apply {
                text = label; textSize = 8f; letterSpacing = .13f; typeface = Typeface.MONOSPACE; setTextColor(dim)
            })
            addView(TextView(this@MainActivity).apply {
                text = value; textSize = 15f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
            })
        }
    }

    private fun favoritesModule(): View {
        val card = moduleCard("FAVORITES", "LONG PRESS IN APP MATRIX TO PIN")
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        val selected = appEntries.filter { favorites.contains(it.packageName) }.take(8)
        if (!appsReady) card.addView(note("Indexing application matrix…"), marginTop(8))
        else if (selected.isEmpty()) card.addView(note("No pinned apps. Open APPS and long-press anything to pin it here."), marginTop(8))
        else card.addView(appGrid(selected, 4, compact = true), marginTop(8))
        return card
    }

    private fun quickModule(): View {
        val card = moduleCard("QUICK CONTROL", "ANDROID SYSTEM ENTRY POINTS")
        val grid = GridLayout(this).apply { columnCount = 2 }
        quickCommands().take(6).forEach { cmd ->
            grid.addView(controlTile(cmd.title, cmd.subtitle) { cmd.action() }, gridCell())
        }
        card.addView(grid, marginTop(7))
        return card
    }

    private fun systemModule(): View {
        val card = moduleCard("SYSTEM", "LIVE DEVICE TELEMETRY")
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
        toolCommands().forEach { cmd -> grid.addView(controlTile(cmd.title, cmd.subtitle) { cmd.action() }, gridCell()) }
        card.addView(grid, marginTop(7))
        return card
    }

    private fun showApps(initial: String = "") {
        lastSearch = initial
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        main.addView(pageHeader("APPLICATION MATRIX", if (appsReady) "${appEntries.size} LAUNCHABLE NODES" else "INDEXING APPLICATIONS…"))

        val search = EditText(this).apply {
            hint = "FILTER APPS"
            setHintTextColor(Color.rgb(90, 103, 114)); setTextColor(Color.WHITE); textSize = 13f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(7, 11, 15), 15f, accent, 1)
            setText(initial)
        }
        main.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        val scroll = ScrollView(this)
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(holder)
        main.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        fun render(q: String) {
            lastSearch = q
            holder.removeAllViews()
            if (!appsReady) {
                holder.addView(note("Application index is loading in the background. This page will refresh automatically."), marginTop(12))
                return
            }
            val filtered = if (q.isBlank()) appEntries else appEntries.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
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

    private fun showUniversalSearch(initial: String) {
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        main.addView(pageHeader("OMNISEARCH", "APPS // SYSTEM // ACTIONS // WEB"))
        val search = EditText(this).apply {
            hint = "TYPE ANY KEYWORD"
            setHintTextColor(Color.rgb(90, 103, 114)); setTextColor(Color.WHITE); textSize = 14f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.rgb(7, 11, 15), 15f, accent, 1)
            setText(initial)
            setSelection(text.length)
        }
        main.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })

        val scroll = ScrollView(this)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        main.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        fun render(qRaw: String) {
            val q = qRaw.trim()
            results.removeAllViews()
            if (q.isBlank()) {
                results.addView(note("Search app names, package names, Wi-Fi, Bluetooth, display, battery, privacy, camera, files, phone, maps, browser, clock, notifications, developer options—or any web keyword."), marginTop(12))
                return
            }

            val apps = appEntries.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }.take(8)
            if (apps.isNotEmpty()) {
                results.addView(searchSectionLabel("APPLICATIONS"), marginTop(10))
                apps.forEach { results.addView(searchRow(it.label, it.packageName, "APP") { launch(it) }) }
            }

            val commands = allCommands().filter {
                it.title.contains(q, true) || it.subtitle.contains(q, true) || it.keywords.contains(q, true)
            }.take(10)
            if (commands.isNotEmpty()) {
                results.addView(searchSectionLabel("SYSTEM + ACTIONS"), marginTop(10))
                commands.forEach { cmd -> results.addView(searchRow(cmd.title, cmd.subtitle, "CMD") { cmd.action() }) }
            }

            if (looksLikeUrl(q)) {
                results.addView(searchSectionLabel("DIRECT"), marginTop(10))
                results.addView(searchRow("OPEN URL", q, "NET") { openUrl(q) })
            }

            results.addView(searchSectionLabel("WEB"), marginTop(10))
            results.addView(searchRow("SEARCH WEB", q, "WEB") { webSearch(q) })
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        search.setOnEditorActionListener { _, _, _ ->
            val q = search.text.toString().trim()
            val app = appEntries.firstOrNull { it.label.equals(q, true) }
            if (app != null) launch(app) else webSearch(q)
            true
        }
        render(initial)
        shell(main, "search")
    }

    private fun searchRow(title: String, sub: String, tag: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(panel, 13f, Color.rgb(31, 41, 49), 1)
            setOnClickListener { action() }
            val textBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(TextView(this@MainActivity).apply {
                text = title; textSize = 14f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            textBox.addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(dim); maxLines = 1
            })
            addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = tag; textSize = 9f; letterSpacing = .12f; typeface = Typeface.MONOSPACE
                setTextColor(accent); setPadding(dp(8), dp(4), dp(8), dp(4)); background = rounded(Color.rgb(12, 28, 29), 8f, accent, 1)
            })
        }.also { it.layoutParams = marginTop(5) }
    }

    private fun searchSectionLabel(textValue: String): View = TextView(this).apply {
        text = textValue
        textSize = 9f; letterSpacing = .15f; typeface = Typeface.MONOSPACE; setTextColor(hot)
        setPadding(dp(2), dp(4), 0, dp(4))
    }

    private fun appGrid(list: List<AppEntry>, columns: Int, compact: Boolean): View {
        val grid = GridLayout(this).apply { columnCount = columns; alignmentMode = GridLayout.ALIGN_BOUNDS }
        val favorites = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        list.forEach { entry ->
            val app = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(9), dp(4), dp(7))
                background = rounded(
                    if (favorites.contains(entry.packageName)) Color.rgb(13, 27, 28) else panel,
                    14f,
                    if (favorites.contains(entry.packageName)) accent else Color.rgb(31, 38, 45),
                    1
                )
                setOnClickListener { launch(entry) }
                setOnLongClickListener {
                    if (favorites.contains(entry.packageName)) favorites.remove(entry.packageName) else favorites.add(entry.packageName)
                    prefs.edit().putStringSet("favorites", favorites).apply()
                    if (currentPage == "apps") showApps(lastSearch) else showHome()
                    true
                }
            }
            app.addView(ImageView(this).apply {
                setImageDrawable(entry.icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(if (compact) 36 else 44), dp(if (compact) 36 else 44)))
            app.addView(TextView(this).apply {
                text = entry.label
                maxLines = 2; gravity = Gravity.CENTER; textSize = if (compact) 9.5f else 10.5f
                setTextColor(Color.WHITE); setPadding(dp(2), dp(5), dp(2), 0)
            })
            grid.addView(app, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(if (compact) 82 else 99)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        return grid
    }

    private fun showControl() {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(30))
        }
        body.addView(pageHeader("CONTROL DECK", "MODULAR LAUNCHER CONFIGURATION"))
        body.addView(primary("SET AS DEFAULT HOME") { requestHomeRole() }, marginTop(14))
        body.addView(controlSection("HOME MODULES",
            settingToggle("Favorites", "module_favorites", true),
            settingToggle("Quick Control", "module_quick", true),
            settingToggle("System Status", "module_system", true),
            settingToggle("Tools", "module_tools", true)
        ), marginTop(12))
        body.addView(controlSection("SYSTEM ENTRY POINTS",
            settingButton("Default apps") { openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) },
            settingButton("Notifications") { openSettings("android.settings.NOTIFICATION_SETTINGS") },
            settingButton("Permissions") { openSettings(Settings.ACTION_APPLICATION_SETTINGS) },
            settingButton("Accessibility") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            settingButton("Developer options") { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        ), marginTop(12))
        body.addView(controlSection("LAUNCHER DATA",
            settingButton("Re-index applications") { appsReady = false; refreshAppsAsync(); showControl() },
            settingButton("Clear favorites") { prefs.edit().remove("favorites").apply(); showControl() },
            settingButton("Reset modules") {
                prefs.edit().remove("module_favorites").remove("module_quick").remove("module_system").remove("module_tools").apply(); showControl()
            }
        ), marginTop(12))
        body.addView(TextView(this).apply {
            text = "PCG LAUNCHER // v1.1\nOMNISEARCH + CACHED APP MATRIX + CUTOUT-SAFE LAYOUT"
            textSize = 9.5f; letterSpacing = .1f; typeface = Typeface.MONOSPACE; setTextColor(Color.rgb(87, 101, 112))
            setPadding(dp(4), dp(18), dp(4), dp(4))
        })
        scroll.addView(body)
        shell(scroll, "control")
    }

    private fun quickCommands(): List<Command> = listOf(
        Command("WI-FI", "NETWORK CONTROL", "wifi wireless network internet") { openSettings(Settings.Panel.ACTION_WIFI) },
        Command("BLUETOOTH", "DEVICE CONTROL", "bluetooth bt nearby devices") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
        Command("DISPLAY", "BRIGHTNESS + SCREEN", "display brightness screen wallpaper") { openSettings(Settings.ACTION_DISPLAY_SETTINGS) },
        Command("SOUND", "VOLUME + AUDIO", "sound volume audio ringtone") { openSettings(Settings.ACTION_SOUND_SETTINGS) },
        Command("BATTERY", "POWER CONTROL", "battery power saver charging") { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) },
        Command("PRIVACY", "PERMISSIONS", "privacy permission security") { openSettings(Settings.ACTION_PRIVACY_SETTINGS) }
    )

    private fun toolCommands(): List<Command> = listOf(
        Command("CAMERA", "CAPTURE", "camera photo picture") { launchIntent(Intent("android.media.action.IMAGE_CAPTURE")) },
        Command("PHONE", "DIALER", "phone call dialer") { launchIntent(Intent(Intent.ACTION_DIAL)) },
        Command("MESSAGES", "SMS", "message sms text") { launchIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) },
        Command("BROWSER", "WEB", "browser internet web chrome") { launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))) },
        Command("MAPS", "NAVIGATION", "maps navigation gps directions") { launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))) },
        Command("CLOCK", "ALARMS + TIMER", "clock alarm timer") { launchIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)) },
        Command("FILES", "STORAGE", "files storage downloads documents") { openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
        Command("SETTINGS", "ANDROID CORE", "settings system android control") { openSettings(Settings.ACTION_SETTINGS) }
    )

    private fun allCommands(): List<Command> = quickCommands() + toolCommands() + listOf(
        Command("NOTIFICATIONS", "NOTIFICATION SETTINGS", "notifications alerts banners") { openSettings("android.settings.NOTIFICATION_SETTINGS") },
        Command("DEFAULT APPS", "APP DEFAULTS", "default apps browser phone sms home") { openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) },
        Command("ACCESSIBILITY", "ACCESSIBILITY SERVICES", "accessibility services assist") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        Command("DEVELOPER OPTIONS", "ADVANCED ANDROID", "developer usb debugging adb") { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        Command("HOME", "RETURN TO HOME", "home launcher desktop") { showHome() },
        Command("APP MATRIX", "ALL INSTALLED APPS", "apps applications installed matrix") { showApps() }
    )

    private fun settingToggle(label: String, key: String, default: Boolean): View {
        val row = settingRow(label)
        val value = TextView(this).apply {
            fun refresh() {
                text = if (prefs.getBoolean(key, default)) "ON" else "OFF"
                setTextColor(if (prefs.getBoolean(key, default)) accent else dim)
            }
            refresh()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 12f
            setOnClickListener { prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply(); refresh() }
        }
        row.addView(value)
        row.setOnClickListener { value.performClick() }
        return row
    }

    private fun settingButton(label: String, action: () -> Unit): View {
        val row = settingRow(label)
        row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(accent) })
        row.setOnClickListener { action() }
        return row
    }

    private fun settingRow(label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(TextView(this@MainActivity).apply { text = label; textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun launch(entry: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
        if (intent != null) startActivity(intent)
    }

    private fun launchIntent(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun openSettings(action: String) = launchIntent(Intent(action))

    private fun webSearch(query: String) {
        if (query.isBlank()) return
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")))
    }

    private fun openUrl(value: String) {
        val normalized = if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "https://$value"
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
    }

    private fun looksLikeUrl(value: String): Boolean {
        return value.startsWith("http://", true) || value.startsWith("https://", true) || (value.contains('.') && !value.contains(' '))
    }

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
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(panel, 17f, Color.rgb(35, 43, 50), 1)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(View(this@MainActivity).apply { setBackgroundColor(hot) }, LinearLayout.LayoutParams(dp(3), dp(16)).apply { rightMargin = dp(8) })
                addView(TextView(this@MainActivity).apply {
                    text = title; textSize = 11.5f; letterSpacing = .13f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(accent)
                })
            })
            if (sub.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 8.5f; letterSpacing = .08f; typeface = Typeface.MONOSPACE; setTextColor(dim); setPadding(dp(11), dp(3), 0, 0)
            })
        }
    }

    private fun controlTile(title: String, sub: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(panel2, 13f, Color.rgb(42, 50, 58), 1)
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 12.5f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 8f; typeface = Typeface.MONOSPACE; setTextColor(dim)
            })
            setOnClickListener { action() }
        }
    }

    private fun infoLine(k: String, v: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(1))
            addView(TextView(this@MainActivity).apply {
                text = k; textSize = 9.5f; typeface = Typeface.MONOSPACE; setTextColor(dim)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = v; textSize = 10.5f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
            })
        }
    }

    private fun pageHeader(title: String, sub: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 23f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = sub; textSize = 9f; letterSpacing = .14f; typeface = Typeface.MONOSPACE; setTextColor(accent)
            })
        }
    }

    private fun note(textValue: String): View = TextView(this).apply {
        text = textValue; textSize = 12.5f; setTextColor(dim); typeface = Typeface.MONOSPACE
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    private fun primary(label: String, action: () -> Unit): View = Button(this).apply {
        text = label; setTextColor(bg); textSize = 12f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        background = rounded(accent, 13f, accent, 0); setOnClickListener { action() }
    }

    private fun gradientPanel(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.rgb(10, 16, 20), Color.rgb(18, 12, 22), Color.rgb(10, 16, 20))
    ).apply {
        cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(45, 58, 66))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (width > 0) setStroke(dp(width), stroke)
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(v) }
    private fun weight(w: Float = 1f, marginEnd: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply { if (marginEnd > 0) rightMargin = dp(marginEnd) }
    private fun gridCell() = GridLayout.LayoutParams().apply {
        width = 0; height = dp(62); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(3), dp(3), dp(3), dp(3))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
