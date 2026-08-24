package online.pcguys.blackline

import android.app.Dialog
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlacklineHomeActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val dim = Color.rgb(185, 191, 197)
    private val panel = Color.argb(244, 4, 5, 7)

    private var apps: List<AppCache.Entry> = emptyList()
    private var collapsed = false
    private var statusView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        collapsed = prefs.getBoolean("taskbar_collapsed", false)
        apps = AppCache.current()
        renderSafe()
        window.decorView.post { enterDesktopFullscreen() }
        if (apps.isEmpty()) loadApps()
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // Home owns the rail itself; prevent a duplicate floating copy here.
        runCatching { stopService(Intent(this, EdgeDockService::class.java)) }
        window.decorView.post { enterDesktopFullscreen() }
        renderSafe()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("openDrawer", false) == true) {
            handler.postDelayed({ showStartMenu("") }, 100)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterDesktopFullscreen()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun enterDesktopFullscreen() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.BLACK
        }
    }

    private fun loadApps() {
        Thread {
            val loaded = runCatching { AppCache.load(packageManager, packageName, force = true) }
                .getOrDefault(emptyList())
            runOnUiThread {
                apps = loaded
                renderSafe()
            }
        }.start()
    }

    private fun renderSafe() {
        runCatching { render() }.onFailure { renderRecovery(it.message ?: "desktop error") }
    }

    private fun render() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isLongClickable = true
            setOnLongClickListener { showDesktopMenu(); true }
        }

        root.addView(desktopGrid(), FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(if (collapsed) 44 else 82)
            rightMargin = dp(10)
            topMargin = dp(16)
        })

        root.addView(TextView(this).apply {
            text = "BLACKLINE"
            textSize = 8f
            letterSpacing = .13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(Color.argb(90, 0, 0, 0), 11f, Color.argb(55, 255, 255, 255))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(12)
            topMargin = dp(12)
        })

        if (collapsed) {
            root.addView(collapsedRail(), FrameLayout.LayoutParams(dp(30), dp(92), Gravity.START or Gravity.CENTER_VERTICAL))
        } else {
            root.addView(expandedRail(), FrameLayout.LayoutParams(dp(70), -1, Gravity.START).apply {
                leftMargin = dp(5)
                topMargin = dp(8)
                bottomMargin = dp(8)
            })
        }

        setContentView(root)
        updateStatus()
    }

    private fun desktopGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        favoriteApps().take(9).forEach { grid.addView(desktopIcon(it), gridCell(92)) }
        return grid
    }

    private fun desktopIcon(entry: AppCache.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(FrameLayout(this@BlacklineHomeActivity).apply {
            background = rounded(Color.argb(100, 0, 0, 0), 15f, Color.argb(55, 255, 255, 255))
            addView(ImageView(this@BlacklineHomeActivity).apply {
                setImageDrawable(entry.icon)
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(label(entry.label), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun expandedRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rounded(panel, 18f, Color.argb(90, 255, 255, 255))
        }

        rail.addView(railButton("//", "START") { showStartMenu("") })
        rail.addView(railButton(">_", "TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) }, railMargin(6))
        rail.addView(View(this).apply { setBackgroundColor(Color.argb(58, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(7)
            bottomMargin = dp(6)
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pins = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        favoriteApps().take(8).forEach { pins.addView(railApp(it), railMargin(5)) }
        scroll.addView(pins)
        rail.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        statusView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 7.5f
            setLineSpacing(0f, .94f)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(130, 20, 21, 24), 11f, Color.argb(48, 255, 255, 255))
            setOnClickListener { showSystemTray() }
        }
        rail.addView(statusView, LinearLayout.LayoutParams(dp(54), dp(80)).apply { topMargin = dp(6) })

        rail.addView(TextView(this).apply {
            text = "‹"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.WHITE)
            setOnClickListener { setCollapsed(true) }
        }, LinearLayout.LayoutParams(dp(54), dp(42)).apply { topMargin = dp(5) })

        return rail
    }

    private fun collapsedRail(): View = TextView(this).apply {
        text = "//"
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = edgeRounded(Color.argb(236, 4, 5, 7), Color.argb(150, 255, 255, 255))
        setOnClickListener { setCollapsed(false) }
    }

    private fun railButton(glyph: String, tip: String, action: () -> Unit): View = TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setTooltipText(tip)
        background = rounded(Color.argb(138, 20, 21, 24), 12f, Color.argb(58, 255, 255, 255))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(50))
    }

    private fun railApp(entry: AppCache.Entry): View = FrameLayout(this).apply {
        background = rounded(Color.argb(100, 20, 21, 24), 12f, Color.argb(42, 255, 255, 255))
        setTooltipText(entry.label)
        addView(ImageView(this@BlacklineHomeActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(50))
    }

    private fun updateStatus() {
        val now = Date()
        val time = SimpleDateFormat("h:mm", Locale.US).format(now)
        val date = SimpleDateFormat("MMM d", Locale.US).format(now).uppercase(Locale.US)
        statusView?.text = "$time\n$date\n${batteryText()}\n${networkShort()}"
    }

    private fun showStartMenu(initial: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rightRounded(Color.argb(250, 5, 6, 8), Color.argb(95, 255, 255, 255))
        }
        body.addView(TextView(this).apply {
            text = "BLACKLINE // START"
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val search = EditText(this).apply {
            hint = "Search applications"
            setHintTextColor(Color.rgb(119, 126, 132))
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(Color.rgb(14, 16, 19), 14f, Color.argb(58, 255, 255, 255))
            setText(initial)
            setSelection(text.length)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })

        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(0, dp(8), 0, dp(16))
        }
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun redraw(q: String) {
            grid.removeAllViews()
            apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                .forEach { entry ->
                    grid.addView(startTile(entry) { dialog.dismiss(); launch(entry.pkg) }, gridCell(104))
                }
        }
        search.addTextChangedListener(SimpleWatcher { redraw(it) })
        redraw(initial)

        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            setLayout((resources.displayMetrics.widthPixels * .88f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.translationX = -resources.displayMetrics.widthPixels.toFloat()
            decorView.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun startTile(entry: AppCache.Entry, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(ImageView(this@BlacklineHomeActivity).apply { setImageDrawable(entry.icon) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(entry.label), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setOnClickListener { action() }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun showSystemTray() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val now = Date()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = rounded(Color.argb(250, 5, 6, 8), 22f, Color.argb(95, 255, 255, 255))
        }
        body.addView(TextView(this).apply {
            text = SimpleDateFormat("h:mm a", Locale.US).format(now)
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        body.addView(text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), 11f, dim), top(2))
        body.addView(trayLine("BATTERY", batteryText()), top(14))
        body.addView(trayLine("NETWORK", networkLabel()), top(6))
        body.addView(trayLine("ANDROID", Build.VERSION.RELEASE ?: "?"), top(6))

        val quick = GridLayout(this).apply { columnCount = 2 }
        quick.addView(trayAction("SETTINGS") { dialog.dismiss(); startActivity(Intent(Settings.ACTION_SETTINGS)) }, gridCell(54))
        quick.addView(trayAction("WALLPAPER") { dialog.dismiss(); wallpaperPicker() }, gridCell(54))
        quick.addView(trayAction(if (Settings.canDrawOverlays(this)) "CROSS-APP READY" else "CROSS-APP SETUP") {
            dialog.dismiss(); overlaySetup()
        }, gridCell(54))
        quick.addView(trayAction("HOME APP") { dialog.dismiss(); requestHomeRole() }, gridCell(54))
        body.addView(quick, top(12))

        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.START or Gravity.BOTTOM }
            setLayout((resources.displayMetrics.widthPixels * .84f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun trayLine(name: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(11), dp(12), dp(11))
        background = rounded(Color.argb(130, 20, 22, 25), 12f, Color.argb(38, 255, 255, 255))
        addView(text(name, 9f, dim), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 11f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
    }

    private fun trayAction(name: String, action: () -> Unit): View = TextView(this).apply {
        text = name
        gravity = Gravity.CENTER
        textSize = 9.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(18, 19, 22), 12f, Color.argb(48, 255, 255, 255))
        setOnClickListener { action() }
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            if (Settings.canDrawOverlays(this)) runCatching { startService(Intent(this, EdgeDockService::class.java)) }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } ?: toast("Unable to launch app")
    }

    private fun favoriteApps(): List<AppCache.Entry> {
        val ids = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (ids.isNotEmpty()) return apps.filter { ids.contains(it.pkg) }
        val preferred = listOf("phone", "messages", "chrome", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle -> apps.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) } }
            .distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else apps.take(6)
    }

    private fun toggleFavorite(pkg: String) {
        val current = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (current.remove(pkg)) false else { current.add(pkg); true }
        prefs.edit().putStringSet("favorites", current).apply()
        toast(if (added) "Pinned" else "Unpinned")
        renderSafe()
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        prefs.edit().putBoolean("taskbar_collapsed", value).apply()
        renderSafe()
    }

    private fun overlaySetup() {
        if (Settings.canDrawOverlays(this)) toast("Cross-app taskbar is ready")
        else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun wallpaperPicker() {
        val choices = listOf(
            Intent(Intent.ACTION_SET_WALLPAPER),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        choices.firstOrNull { it.resolveActivity(packageManager) != null }?.let { startActivity(it) }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME))
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 502)
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun showDesktopMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("BLACKLINE")
            .setItems(arrayOf("Applications", "Terminal", "Wallpaper", "Cross-app taskbar setup", "Set as Home", "Android settings")) { _, i ->
                when (i) {
                    0 -> showStartMenu("")
                    1 -> startActivity(Intent(this, TerminalActivity::class.java))
                    2 -> wallpaperPicker()
                    3 -> overlaySetup()
                    4 -> requestHomeRole()
                    5 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }.show()
    }

    private fun batteryText(): String = runCatching {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) "$level%+" else "$level%"
    }.getOrDefault("--")

    private fun networkShort(): String = when (networkLabel()) {
        "WI-FI" -> "WIFI"
        "CELLULAR" -> "CELL"
        "OFFLINE" -> "OFF"
        else -> networkLabel()
    }

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

    private fun renderRecovery(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(text("BLACKLINE // RECOVERY", 18f, Color.WHITE))
        root.addView(text(message.take(180), 10f, Color.GRAY), top(12))
        root.addView(trayAction("TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        setContentView(root)
    }

    private fun label(value: String) = text(value, 9.5f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun railMargin(top: Int) = LinearLayout.LayoutParams(dp(54), dp(50)).apply { topMargin = dp(top) }
    private fun top(v: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(v) }
    private fun gridCell(height: Int) = GridLayout.LayoutParams().apply {
        width = 0
        this.height = dp(height)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun edgeRounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(0f, 0f, dp(15).toFloat(), dp(15).toFloat(), dp(15).toFloat(), dp(15).toFloat(), 0f, 0f)
        setStroke(dp(1), stroke)
    }

    private fun rightRounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(0f, 0f, dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f)
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(v: String) = android.widget.Toast.makeText(this, v, android.widget.Toast.LENGTH_SHORT).show()

    private class SimpleWatcher(private val f: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = f(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}
