package online.pcguys.blackline

import android.app.Dialog
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
    private val accent = Color.WHITE
    private val cyan = Color.rgb(69, 246, 229)
    private val panel = Color.argb(232, 8, 9, 12)
    private val dim = Color.rgb(181, 188, 194)
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var apps: List<AppCache.Entry> = emptyList()
    private var wallpaperUri: String? = null
    private var taskbarCollapsed = false

    private var taskTime: TextView? = null
    private var taskDate: TextView? = null
    private var taskBattery: TextView? = null
    private var taskNetwork: TextView? = null

    private val statusUpdater = object : Runnable {
        override fun run() {
            runCatching { updateTaskStatus() }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wallpaperUri = prefs.getString("wallpaper_uri", null)
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

        if (prefs.getBoolean("edge_enabled", false) && Settings.canDrawOverlays(this)) {
            handler.postDelayed({
                runCatching { startService(Intent(this, EdgeDockService::class.java)) }
            }, 450)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("openDrawer", false) == true) {
            handler.postDelayed({ runCatching { showAppDrawer("") } }, 120)
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
            val loaded = runCatching {
                AppCache.load(packageManager, packageName, force = true)
            }.getOrDefault(emptyList())

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
            setBackgroundColor(Color.rgb(12, 13, 16))
            setOnLongClickListener {
                showDesktopMenu()
                true
            }
        }

        wallpaperUri?.let { raw ->
            runCatching {
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(raw))
                }
                root.addView(image, FrameLayout.LayoutParams(-1, -1))
            }
        }

        root.addView(desktopShortcuts(), FrameLayout.LayoutParams(dp(182), -2, Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(14)
            topMargin = dp(18)
        })

        root.addView(desktopBadge(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(14)
            topMargin = dp(16)
        })

        if (taskbarCollapsed) {
            root.addView(collapsedTaskbar(), FrameLayout.LayoutParams(dp(92), dp(25), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(4)
            })
        } else {
            root.addView(expandedTaskbar(), FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM).apply {
                leftMargin = dp(7)
                rightMargin = dp(7)
                bottomMargin = dp(6)
            })
        }

        setContentView(root)
        updateTaskStatus()
    }

    private fun renderRecoveryDesktop(message: String) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        body.addView(text("BLACKLINE", 28f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        body.addView(text("DESKTOP RECOVERY MODE", 11f, Color.LTGRAY).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
        body.addView(text(message.take(180), 10f, Color.GRAY))
        body.addView(recoveryButton("OPEN TERMINAL") { startActivity(Intent(this@DesktopActivity, TerminalActivity::class.java)) }, mt(18))
        body.addView(recoveryButton("ANDROID SETTINGS") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, mt(8))
        body.addView(recoveryButton("RELOAD DESKTOP") { safeRenderDesktop() }, mt(8))
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

    private fun desktopBadge(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = rounded(Color.argb(105, 0, 0, 0), 12f, Color.argb(70, 255, 255, 255), 1)
        addView(text("BLACKLINE // DESKTOP", 8.5f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        addView(text(Build.MODEL.uppercase(Locale.US), 7.5f, Color.LTGRAY).apply {
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun desktopShortcuts(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(desktopAction(">_", "Terminal") { startActivity(Intent(this, TerminalActivity::class.java)) })
        column.addView(desktopAction("▦", "Applications") { showAppDrawer("") }, mt(6))
        column.addView(desktopAction("▧", "Wallpaper") { chooseWallpaper() }, mt(6))
        column.addView(desktopAction("◁", "Edge bar") { toggleEdgeBar() }, mt(6))
        favoriteApps().take(3).forEach { entry -> column.addView(desktopApp(entry), mt(6)) }
        return column
    }

    private fun desktopAction(glyph: String, label: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(3), dp(8), dp(3))
        background = rounded(Color.argb(75, 0, 0, 0), 11f, Color.argb(40, 255, 255, 255), 1)
        addView(TextView(this@DesktopActivity).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(text(label, 11f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        setOnClickListener { action() }
    }

    private fun desktopApp(entry: AppCache.Entry): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(3), dp(8), dp(3))
        background = rounded(Color.argb(75, 0, 0, 0), 11f, Color.argb(40, 255, 255, 255), 1)
        addView(ImageView(this@DesktopActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(text(entry.label, 11f, Color.WHITE).apply {
            maxLines = 1
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun expandedTaskbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = rounded(Color.argb(242, 4, 5, 7), 15f, Color.argb(82, 255, 255, 255), 1)
        }

        bar.addView(taskButton("//", "BLACKLINE") { showAppDrawer("") })
        bar.addView(taskButton(">_", "TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) }, lpTask(5))

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pinned = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        favoriteApps().take(9).forEach { entry -> pinned.addView(taskApp(entry), lpTask(4)) }
        scroller.addView(pinned)
        bar.addView(scroller, LinearLayout.LayoutParams(0, -1, 1f).apply {
            leftMargin = dp(5)
            rightMargin = dp(5)
        })

        val system = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        taskNetwork = taskStatus("NET", networkLabel())
        taskBattery = taskStatus("BAT", batteryText())
        taskDate = taskStatus("DATE", SimpleDateFormat("MMM d", Locale.US).format(Date()))
        taskTime = taskStatus("TIME", SimpleDateFormat("h:mm a", Locale.US).format(Date()))

        taskNetwork?.let { system.addView(it) }
        taskBattery?.let { system.addView(it, lpStatus(4)) }
        taskDate?.let { system.addView(it, lpStatus(4)) }
        taskTime?.let { system.addView(it, lpStatus(4)) }
        system.addView(taskButton("⌄", "COLLAPSE") { setTaskbarCollapsed(true) }, lpTask(5))

        bar.addView(system)
        return bar
    }

    private fun collapsedTaskbar(): View = TextView(this).apply {
        text = "▲  TASKBAR"
        gravity = Gravity.CENTER
        textSize = 8.5f
        letterSpacing = .08f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(225, 3, 4, 6), 11f, Color.argb(95, 255, 255, 255), 1)
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
        setTextColor(accent)
        setTooltipText(tip)
        background = rounded(Color.argb(135, 21, 22, 25), 11f, Color.argb(55, 255, 255, 255), 1)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(47), dp(47))
    }

    private fun taskApp(entry: AppCache.Entry): View = FrameLayout(this).apply {
        background = rounded(Color.argb(95, 21, 22, 25), 11f, Color.argb(40, 255, 255, 255), 1)
        setTooltipText(entry.label)
        addView(ImageView(this@DesktopActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
        layoutParams = LinearLayout.LayoutParams(dp(47), dp(47))
    }

    private fun taskStatus(label: String, value: String): TextView = TextView(this).apply {
        text = "$label\n$value"
        gravity = Gravity.CENTER
        textSize = 8.2f
        setLineSpacing(0f, .93f)
        typeface = Typeface.MONOSPACE
        setTextColor(Color.WHITE)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun updateTaskStatus() {
        val now = Date()
        taskTime?.text = "TIME\n${SimpleDateFormat("h:mm a", Locale.US).format(now)}"
        taskDate?.text = "DATE\n${SimpleDateFormat("MMM d", Locale.US).format(now)}"
        taskBattery?.text = "BAT\n${batteryText()}"
        taskNetwork?.text = "NET\n${networkLabel()}"
    }

    private fun showAppDrawer(initial: String) {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.argb(238, 3, 4, 6)) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("BLACKLINE // APPLICATIONS", 14f, Color.WHITE).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@DesktopActivity).apply {
                text = "×"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        body.addView(header)

        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(118, 127, 135))
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(16), 0, dp(16), 0)
            isSingleLine = true
            background = rounded(Color.rgb(13, 14, 17), 14f, Color.argb(75, 255, 255, 255), 1)
            setText(initial)
            setSelection(text.length)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })

        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(8), 0, dp(28))
        }
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun draw(q: String) {
            grid.removeAllViews()
            if (apps.isEmpty()) {
                grid.addView(text("INDEXING APPLICATIONS…", 11f, dim), GridLayout.LayoutParams().apply {
                    width = GridLayout.LayoutParams.MATCH_PARENT
                    height = dp(80)
                    columnSpec = GridLayout.spec(0, 4)
                })
                return
            }
            apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }
                .forEach { entry -> grid.addView(appTile(entry) { dialog.dismiss(); launch(entry.pkg) }, appCell()) }
        }

        search.addTextChangedListener(SimpleWatcher { draw(it) })
        draw(initial)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(frame)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun appTile(entry: AppCache.Entry, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(9), dp(5), dp(9))
        background = rounded(Color.argb(160, 15, 17, 20), 14f, Color.argb(42, 255, 255, 255), 1)
        addView(ImageView(this@DesktopActivity).apply { setImageDrawable(entry.icon) }, LinearLayout.LayoutParams(dp(46), dp(46)))
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
            .setItems(arrayOf("Applications", "Terminal", "Change wallpaper", "Toggle edge bar", "Set as default Home", "Android settings")) { _, which ->
                when (which) {
                    0 -> showAppDrawer("")
                    1 -> startActivity(Intent(this, TerminalActivity::class.java))
                    2 -> chooseWallpaper()
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
        val preferred = listOf("chrome", "messages", "phone", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle ->
            apps.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) }
        }.distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else apps.take(6)
    }

    private fun toggleFavorite(pkg: String) {
        val current = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (current.remove(pkg)) false else {
            current.add(pkg)
            true
        }
        prefs.edit().putStringSet("favorites", current).apply()
        toast(if (added) "Pinned to taskbar" else "Removed from taskbar")
        AppCache.invalidate()
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

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 501)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                wallpaperUri = uri.toString()
                prefs.edit().putString("wallpaper_uri", wallpaperUri).apply()
                safeRenderDesktop()
            }
        }
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
        } else {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    private fun batteryText(): String {
        return runCatching {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (charging) "$level% +" else "$level%"
        }.getOrDefault("--")
    }

    private fun networkLabel(): String {
        return runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "OFF"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
                else -> "NET"
            }
        }.getOrDefault("--")
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun mt(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun lpTask(left: Int) = LinearLayout.LayoutParams(dp(47), dp(47)).apply { leftMargin = dp(left) }
    private fun lpStatus(left: Int) = LinearLayout.LayoutParams(-2, dp(47)).apply { leftMargin = dp(left) }
    private fun appCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(106)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }
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
