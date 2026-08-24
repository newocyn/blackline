package online.pcguys.blackline

import android.app.Dialog
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DesktopActivity : AppCompatActivity() {
    private val white = Color.WHITE
    private val dim = Color.rgb(184, 190, 196)
    private val panel = Color.argb(242, 5, 6, 8)
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var apps: List<AppCache.Entry> = emptyList()
    private var taskbarCollapsed = false
    private var trayChip: TextView? = null

    private val statusUpdater = object : Runnable {
        override fun run() {
            runCatching { updateTrayChip() }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskbarCollapsed = prefs.getBoolean("taskbar_collapsed", false)
        apps = AppCache.current()
        safeRenderDesktop()
        window.decorView.post { hideAndroidStatusBarSafe() }
        if (apps.isEmpty()) loadAppsAsync()
        handler.post(statusUpdater)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { hideAndroidStatusBarSafe() }
        safeRenderDesktop()
        if (prefs.getBoolean("edge_enabled", false) && Settings.canDrawOverlays(this)) {
            handler.postDelayed({ runCatching { startService(Intent(this, EdgeDockService::class.java)) } }, 450)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("openDrawer", false) == true) {
            handler.postDelayed({ runCatching { showStartMenu("") } }, 100)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideAndroidStatusBarSafe()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hideAndroidStatusBarSafe() {
        runCatching {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.navigationBarColor = Color.BLACK
        }
    }

    private fun loadAppsAsync() {
        Thread {
            val loaded = runCatching { AppCache.load(packageManager, packageName, force = true) }
                .getOrDefault(emptyList())
            runOnUiThread {
                apps = loaded
                safeRenderDesktop()
            }
        }.start()
    }

    private fun safeRenderDesktop() {
        runCatching { renderDesktop() }
            .onFailure { renderRecoveryDesktop(it.message ?: "desktop initialization error") }
    }

    private fun renderDesktop() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(11, 12, 14))
            isLongClickable = true
            setOnLongClickListener {
                showDesktopMenu()
                true
            }
        }

        runCatching {
            val wallpaper = WallpaperManager.getInstance(this).drawable
            root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(wallpaper)
            }, FrameLayout.LayoutParams(-1, -1))
        }

        root.addView(desktopIconGrid(), FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            topMargin = dp(24)
        })

        root.addView(brandBadge(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(14)
            topMargin = dp(16)
        })

        if (taskbarCollapsed) {
            root.addView(collapsedTaskbar(), FrameLayout.LayoutParams(dp(94), dp(26), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(5)
            })
        } else {
            root.addView(expandedTaskbar(), FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM).apply {
                leftMargin = dp(7)
                rightMargin = dp(7)
                bottomMargin = dp(6)
            })
        }

        setContentView(root)
        updateTrayChip()
    }

    private fun desktopIconGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        val entries = mutableListOf<View>()
        entries += desktopAction(">_", "Terminal") { startActivity(Intent(this, TerminalActivity::class.java)) }
        entries += desktopAction("▦", "Apps") { showStartMenu("") }
        entries += desktopAction("◁", "Edge bar") { toggleEdgeBar() }
        favoriteApps().take(6).forEach { entries += desktopApp(it) }

        entries.forEach { grid.addView(it, desktopCell()) }
        return grid
    }

    private fun desktopAction(glyph: String, label: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(5), dp(4), dp(5))
        addView(TextView(this@DesktopActivity).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(118, 0, 0, 0), 15f, Color.argb(72, 255, 255, 255), 1)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(text(label, 10f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setOnClickListener { action() }
    }

    private fun desktopApp(entry: AppCache.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(5), dp(4), dp(5))
        addView(FrameLayout(this@DesktopActivity).apply {
            background = rounded(Color.argb(105, 0, 0, 0), 15f, Color.argb(58, 255, 255, 255), 1)
            addView(ImageView(this@DesktopActivity).apply {
                setImageDrawable(entry.icon)
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(text(entry.label, 10f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun brandBadge(): View = TextView(this).apply {
        text = "BLACKLINE"
        textSize = 8f
        letterSpacing = .13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = rounded(Color.argb(94, 0, 0, 0), 11f, Color.argb(55, 255, 255, 255), 1)
    }

    private fun expandedTaskbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(panel, 16f, Color.argb(90, 255, 255, 255), 1)
        }

        bar.addView(taskButton("//", "START") { showStartMenu("") })
        bar.addView(taskButton(">_", "TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) }, taskMargin(4))

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pinned = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        favoriteApps().take(6).forEach { pinned.addView(taskApp(it), taskMargin(3)) }
        scroller.addView(pinned)
        bar.addView(scroller, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })

        trayChip = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(135, 21, 22, 25), 11f, Color.argb(52, 255, 255, 255), 1)
            setPadding(dp(7), 0, dp(7), 0)
            setOnClickListener { showSystemTray() }
        }
        bar.addView(trayChip, LinearLayout.LayoutParams(dp(76), dp(48)).apply { leftMargin = dp(3) })

        bar.addView(TextView(this).apply {
            text = "⌄"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            setOnClickListener { setTaskbarCollapsed(true) }
        }, LinearLayout.LayoutParams(dp(28), dp(48)).apply { leftMargin = dp(2) })

        return bar
    }

    private fun collapsedTaskbar(): View = TextView(this).apply {
        text = "▲  BLACKLINE"
        gravity = Gravity.CENTER
        textSize = 8.5f
        letterSpacing = .08f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(230, 3, 4, 6), 12f, Color.argb(96, 255, 255, 255), 1)
        setOnClickListener { setTaskbarCollapsed(false) }
    }

    private fun setTaskbarCollapsed(value: Boolean) {
        taskbarCollapsed = value
        prefs.edit().putBoolean("taskbar_collapsed", value).apply()
        safeRenderDesktop()
    }

    private fun taskButton(glyph: String, tip: String, action: () -> Unit): View = TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setTooltipText(tip)
        background = rounded(Color.argb(140, 21, 22, 25), 11f, Color.argb(58, 255, 255, 255), 1)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(48))
    }

    private fun taskApp(entry: AppCache.Entry): View = FrameLayout(this).apply {
        background = rounded(Color.argb(98, 21, 22, 25), 11f, Color.argb(42, 255, 255, 255), 1)
        setTooltipText(entry.label)
        addView(ImageView(this@DesktopActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(48))
    }

    private fun updateTrayChip() {
        val now = Date()
        trayChip?.text = "${SimpleDateFormat("h:mm", Locale.US).format(now)}\n${batteryText()}"
    }

    private fun showSystemTray() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(Color.argb(250, 5, 6, 8), 22f, Color.argb(95, 255, 255, 255), 1)
        }

        val now = Date()
        body.addView(text(SimpleDateFormat("h:mm a", Locale.US).format(now), 30f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
        body.addView(text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), 11f, dim), top(2))
        body.addView(trayLine("BATTERY", batteryText()), top(15))
        body.addView(trayLine("NETWORK", networkLabel()), top(6))
        body.addView(trayLine("ANDROID", Build.VERSION.RELEASE ?: "?"), top(6))

        val quick = GridLayout(this).apply { columnCount = 2 }
        quick.addView(trayAction("SETTINGS") { dialog.dismiss(); startActivity(Intent(Settings.ACTION_SETTINGS)) }, trayCell())
        quick.addView(trayAction("WALLPAPER") { dialog.dismiss(); openSystemWallpaperPicker() }, trayCell())
        quick.addView(trayAction("EDGE BAR") { dialog.dismiss(); toggleEdgeBar() }, trayCell())
        quick.addView(trayAction("HOME APP") { dialog.dismiss(); requestHomeRole() }, trayCell())
        body.addView(quick, top(12))

        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        dialog.window?.apply {
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun trayLine(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = rounded(Color.argb(130, 20, 22, 25), 12f, Color.argb(38, 255, 255, 255), 1)
        addView(text(label, 9f, dim), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 11f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
    }

    private fun trayAction(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(18, 19, 22), 12f, Color.argb(48, 255, 255, 255), 1)
        setOnClickListener { action() }
    }

    private fun showStartMenu(initial: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(Color.argb(250, 5, 6, 8), 24f, Color.argb(95, 255, 255, 255), 1)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("BLACKLINE // START", 13f, Color.WHITE).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@DesktopActivity).apply {
                text = "×"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        body.addView(header)

        val search = EditText(this).apply {
            hint = "Search applications"
            setHintTextColor(Color.rgb(119, 126, 132))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(Color.rgb(14, 16, 19), 14f, Color.argb(58, 255, 255, 255), 1)
            setText(initial)
            setSelection(text.length)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })

        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(0, dp(8), 0, dp(16))
        }
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun draw(query: String) {
            grid.removeAllViews()
            if (apps.isEmpty()) {
                grid.addView(text("INDEXING APPLICATIONS…", 10f, dim), GridLayout.LayoutParams().apply {
                    width = GridLayout.LayoutParams.MATCH_PARENT
                    height = dp(70)
                    columnSpec = GridLayout.spec(0, 3)
                })
                return
            }
            apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                .forEach { entry -> grid.addView(startAppTile(entry) { dialog.dismiss(); launch(entry.pkg) }, startCell()) }
        }

        search.addTextChangedListener(SimpleWatcher { draw(it) })
        draw(initial)

        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
        }
        dialog.show()
        dialog.window?.apply {
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72f).toInt())
        }
    }

    private fun startAppTile(entry: AppCache.Entry, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(ImageView(this@DesktopActivity).apply { setImageDrawable(entry.icon) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(text(entry.label, 9.5f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setOnClickListener { action() }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun showDesktopMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("BLACKLINE Desktop")
            .setItems(arrayOf("Applications", "Terminal", "System wallpaper", "Toggle edge bar", "Set as default Home", "Android settings")) { _, which ->
                when (which) {
                    0 -> showStartMenu("")
                    1 -> startActivity(Intent(this, TerminalActivity::class.java))
                    2 -> openSystemWallpaperPicker()
                    3 -> toggleEdgeBar()
                    4 -> requestHomeRole()
                    5 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun favoriteApps(): List<AppCache.Entry> {
        val ids = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (ids.isNotEmpty()) return apps.filter { ids.contains(it.pkg) }
        val preferred = listOf("phone", "messages", "chrome", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle ->
            apps.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) }
        }.distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else apps.take(6)
    }

    private fun toggleFavorite(pkg: String) {
        val current = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (current.remove(pkg)) false else { current.add(pkg); true }
        prefs.edit().putStringSet("favorites", current).apply()
        toast(if (added) "Pinned to taskbar" else "Removed from taskbar")
        if (prefs.getBoolean("edge_enabled", false) && Settings.canDrawOverlays(this)) {
            runCatching { stopService(Intent(this, EdgeDockService::class.java)) }
            handler.postDelayed({ runCatching { startService(Intent(this, EdgeDockService::class.java)) } }, 150)
        }
        safeRenderDesktop()
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } ?: toast("Unable to launch app")
    }

    private fun openSystemWallpaperPicker() {
        val candidates = listOf(
            Intent(Intent.ACTION_SET_WALLPAPER),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        val selected = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (selected != null) startActivity(selected) else toast("Wallpaper picker unavailable")
    }

    private fun toggleEdgeBar() {
        if (!Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("edge_enabled", true).apply()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val enabled = !prefs.getBoolean("edge_enabled", false)
        prefs.edit().putBoolean("edge_enabled", enabled).apply()
        if (enabled) {
            runCatching { startService(Intent(this, EdgeDockService::class.java)) }
            toast("Edge bar enabled")
        } else {
            runCatching { stopService(Intent(this, EdgeDockService::class.java)) }
            toast("Edge bar disabled")
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 502)
            } else toast("BLACKLINE is already your home app")
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun batteryText(): String = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (charging) "$level%+" else "$level%"
    }.getOrDefault("--")

    private fun networkLabel(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "OFFLINE"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "ONLINE"
        }
    }.getOrDefault("--")

    private fun renderRecoveryDesktop(message: String) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        body.addView(text("BLACKLINE", 28f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        body.addView(text("DESKTOP RECOVERY MODE", 11f, Color.LTGRAY), top(8))
        body.addView(text(message.take(180), 10f, Color.GRAY), top(14))
        body.addView(recoveryButton("OPEN TERMINAL") { startActivity(Intent(this@DesktopActivity, TerminalActivity::class.java)) }, top(18))
        body.addView(recoveryButton("ANDROID SETTINGS") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, top(8))
        body.addView(recoveryButton("RELOAD DESKTOP") { safeRenderDesktop() }, top(8))
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun recoveryButton(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(18, 19, 22), 10f, Color.DKGRAY, 1)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun desktopCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(91)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun startCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(103)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun trayCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(54)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun taskMargin(left: Int) = LinearLayout.LayoutParams(dp(46), dp(48)).apply { leftMargin = dp(left) }
    private fun top(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int, strokeWidth: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }

    private fun toast(value: String) = android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_SHORT).show()

    private class SimpleWatcher(val onChange: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}
