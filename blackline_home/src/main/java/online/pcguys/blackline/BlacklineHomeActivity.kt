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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BlacklineHomeActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val dim = Color.rgb(186, 190, 196)
    private val panel = Color.argb(238, 3, 4, 6)

    private var apps: List<AppCache.Entry> = emptyList()
    private var railCollapsed = false
    private var railStatus: TextView? = null
    private var heroTime: TextView? = null
    private var heroDay: TextView? = null
    private var heroDate: TextView? = null
    private lateinit var gestures: GestureDetector

    private val ticker = object : Runnable {
        override fun run() {
            updateClockAndStatus()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        railCollapsed = prefs.getBoolean("taskbar_collapsed", false)
        apps = AppCache.current()
        gestures = GestureDetector(this, GestureListener())
        renderSafe()
        window.decorView.post { enterFullscreen() }
        if (apps.isEmpty()) loadAppsAsync()
        handler.post(ticker)
        ensureCrossAppRailPermissionState()
    }

    override fun onResume() {
        super.onResume()
        runCatching { stopService(Intent(this, EdgeDockService::class.java)) }
        window.decorView.post { enterFullscreen() }
        renderSafe()
    }

    override fun onPause() {
        super.onPause()
        if (Settings.canDrawOverlays(this) && prefs.getBoolean("edge_enabled", false)) {
            handler.postDelayed({ runCatching { startService(Intent(this, EdgeDockService::class.java)) } }, 180)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("openDrawer", false) == true) {
            handler.postDelayed({ showAllApps("") }, 90)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun enterFullscreen() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.BLACK
        }
    }

    private fun loadAppsAsync() {
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
        runCatching { renderHome() }.onFailure { renderRecovery(it.message ?: "home initialization error") }
    }

    private fun renderHome() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isLongClickable = true
            setOnLongClickListener { showDesktopMenu(); true }
        }

        root.addView(heroPanel(), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(if (railCollapsed) 58 else 88)
            topMargin = dp(-20)
        })

        root.addView(commandHint(), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
        })

        root.addView(brandMark(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(16)
            topMargin = dp(18)
        })

        if (railCollapsed) {
            root.addView(collapsedRail(), FrameLayout.LayoutParams(dp(24), dp(104), Gravity.START or Gravity.CENTER_VERTICAL))
        } else {
            root.addView(expandedRail(), FrameLayout.LayoutParams(dp(62), -1, Gravity.START).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            })
        }

        setContentView(root)
        updateClockAndStatus()
    }

    private fun heroPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START

        heroDate = text("", 9f, Color.WHITE).apply {
            letterSpacing = .16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(7f, 0f, 2f, Color.BLACK)
        }
        addView(heroDate)

        heroDay = text("", 62f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -.03f
            setShadowLayer(10f, 0f, 3f, Color.BLACK)
        }
        addView(heroDay, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(-4) })

        heroTime = text("", 15f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            letterSpacing = .08f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        addView(heroTime, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(-2) })

        addView(text("BLACKLINE // MOBILE DECK", 8f, dim).apply {
            letterSpacing = .15f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
    }

    private fun commandHint(): View = TextView(this).apply {
        text = "↑ APPS     RIGHT EDGE → QUICK POD     ↓ DECK"
        gravity = Gravity.CENTER
        textSize = 7.3f
        letterSpacing = .08f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.argb(205, 255, 255, 255))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(Color.argb(82, 0, 0, 0), 18f, Color.argb(50, 255, 255, 255))
        setOnClickListener { showAllApps("") }
    }

    private fun brandMark(): View = TextView(this).apply {
        text = "// BLACKLINE"
        textSize = 7.5f
        letterSpacing = .14f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setShadowLayer(7f, 0f, 2f, Color.BLACK)
    }

    private fun expandedRail(): View {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(7), dp(5), dp(7))
            background = leftRailBackground()
        }

        rail.addView(railButton("//", "ALL APPS") { showAllApps("") })
        rail.addView(railButton(">_", "TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) }, vMargin(5))
        rail.addView(railButton("◉", "QUICK POD") { showQuickPod() }, vMargin(5))

        rail.addView(View(this).apply { setBackgroundColor(Color.argb(45, 255, 255, 255)) }, LinearLayout.LayoutParams(dp(42), dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        favoriteApps().take(7).forEach { holder.addView(railApp(it), vMargin(5)) }
        scroll.addView(holder)
        rail.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        railStatus = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 6.9f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, .91f)
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(100, 20, 21, 24), 10f, Color.argb(34, 255, 255, 255))
            setOnClickListener { showSystemPanel() }
        }
        rail.addView(railStatus, LinearLayout.LayoutParams(dp(48), dp(72)).apply { topMargin = dp(5) })

        rail.addView(TextView(this).apply {
            text = "‹"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            setOnClickListener { setRailCollapsed(true) }
        }, LinearLayout.LayoutParams(dp(48), dp(36)).apply { topMargin = dp(3) })

        return rail
    }

    private fun collapsedRail(): View = TextView(this).apply {
        text = "//"
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = edgeHandleBackground()
        setOnClickListener { setRailCollapsed(false) }
    }

    private fun railButton(glyph: String, tip: String, action: () -> Unit): View = TextView(this).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setTooltipText(tip)
        background = rounded(Color.argb(94, 18, 19, 22), 12f, Color.argb(45, 255, 255, 255))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(47))
    }

    private fun railApp(entry: AppCache.Entry): View = FrameLayout(this).apply {
        background = rounded(Color.argb(74, 18, 19, 22), 12f, Color.argb(34, 255, 255, 255))
        setTooltipText(entry.label)
        addView(ImageView(this@BlacklineHomeActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { launch(entry.pkg) }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(47))
    }

    private fun updateClockAndStatus() {
        val now = Date()
        heroDay?.text = SimpleDateFormat("EEE", Locale.US).format(now).uppercase(Locale.US)
        heroDate?.text = SimpleDateFormat("MMMM d • yyyy", Locale.US).format(now).uppercase(Locale.US)
        heroTime?.text = SimpleDateFormat("h:mm a", Locale.US).format(now).uppercase(Locale.US)
        railStatus?.text = buildString {
            append(SimpleDateFormat("h:mm", Locale.US).format(now))
            append("\n")
            append(batteryShort())
            append("\n")
            append(networkShort())
        }
    }

    private fun showAllApps(initial: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(18))
            background = topRounded(Color.argb(248, 4, 5, 7), Color.argb(72, 255, 255, 255))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("BLACKLINE // ALL APPS", 11f, Color.WHITE).apply {
                letterSpacing = .12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("⌃", 20f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        body.addView(head)

        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(Color.rgb(110, 116, 124))
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(13, 14, 17), 14f, Color.argb(55, 255, 255, 255))
            setText(initial)
            setSelection(text.length)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(10), 0, dp(20))
        }
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun redraw(query: String) {
            grid.removeAllViews()
            val matches = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            if (matches.isEmpty()) {
                grid.addView(text("NO MATCHING APPLICATIONS", 9f, dim), GridLayout.LayoutParams().apply {
                    width = GridLayout.LayoutParams.MATCH_PARENT
                    height = dp(90)
                    columnSpec = GridLayout.spec(0, 4)
                })
            } else {
                matches.forEach { entry -> grid.addView(appTile(entry) { dialog.dismiss(); launch(entry.pkg) }, appGridCell()) }
            }
        }
        search.addTextChangedListener(SimpleWatcher { redraw(it) })
        redraw(initial)

        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.BOTTOM }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .80f).toInt())
            decorView.translationY = resources.displayMetrics.heightPixels.toFloat()
            decorView.animate().translationY(0f).setDuration(210).start()
        }
    }

    private fun appTile(entry: AppCache.Entry, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(3), dp(8), dp(3), dp(8))
        addView(ImageView(this@BlacklineHomeActivity).apply { setImageDrawable(entry.icon) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(text(entry.label, 8.6f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setOnClickListener { action() }
        setOnLongClickListener { toggleFavorite(entry.pkg); true }
    }

    private fun showQuickPod() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rightPodBackground()
        }
        shell.addView(text("QUICK // POD", 8f, dim).apply {
            letterSpacing = .14f
            gravity = Gravity.CENTER
        })

        val podSize = dp(260)
        val pod = FrameLayout(this)
        shell.addView(pod, LinearLayout.LayoutParams(podSize, podSize).apply { topMargin = dp(4) })

        val quick = quickApps().take(8)
        val iconSize = dp(50)
        val center = dp(130)
        val radius = dp(82)

        quick.forEachIndexed { index, entry ->
            val angle = (2.0 * PI * index / maxOf(quick.size, 1)) - PI / 2.0
            val x = center + (radius * cos(angle)).toInt() - iconSize / 2
            val y = center + (radius * sin(angle)).toInt() - iconSize / 2
            pod.addView(podApp(entry) { dialog.dismiss(); launch(entry.pkg) }, FrameLayout.LayoutParams(iconSize, iconSize).apply {
                leftMargin = x
                topMargin = y
            })
        }

        pod.addView(TextView(this).apply {
            text = ">_"
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(230, 7, 8, 10), 25f, Color.argb(110, 255, 255, 255))
            setOnClickListener { dialog.dismiss(); startActivity(Intent(this@BlacklineHomeActivity, TerminalActivity::class.java)) }
        }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))

        shell.addView(text("MOST USED + RECENT", 7f, dim).apply {
            gravity = Gravity.CENTER
            letterSpacing = .11f
        })

        dialog.setContentView(shell)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            setLayout(dp(292), ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.translationX = dp(300).toFloat()
            decorView.animate().translationX(0f).setDuration(180).start()
        }
    }

    private fun podApp(entry: AppCache.Entry, action: () -> Unit): View = FrameLayout(this).apply {
        background = rounded(Color.argb(225, 7, 8, 10), 24f, Color.argb(78, 255, 255, 255))
        addView(ImageView(this@BlacklineHomeActivity).apply {
            setImageDrawable(entry.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { action() }
        setTooltipText(entry.label)
    }

    private fun openDeckMode() {
        startActivity(Intent(this, DeckModeActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showSystemPanel() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val now = Date()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(18))
            background = rounded(Color.argb(250, 5, 6, 8), 22f, Color.argb(85, 255, 255, 255))
        }
        body.addView(text(SimpleDateFormat("h:mm a", Locale.US).format(now), 30f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        })
        body.addView(text(SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now), 10f, dim), top(2))
        body.addView(infoLine("BATTERY", batteryLong()), top(14))
        body.addView(infoLine("NETWORK", networkLong()), top(5))
        body.addView(infoLine("MEDIA", mediaState()), top(5))
        body.addView(infoLine("ANDROID", Build.VERSION.RELEASE ?: "?"), top(5))

        val quick = GridLayout(this).apply { columnCount = 2 }
        quick.addView(panelAction("TERMINAL") { dialog.dismiss(); startActivity(Intent(this@BlacklineHomeActivity, TerminalActivity::class.java)) }, panelCell())
        quick.addView(panelAction("DECK MODE") { dialog.dismiss(); openDeckMode() }, panelCell())
        quick.addView(panelAction("WALLPAPER") { dialog.dismiss(); wallpaperPicker() }, panelCell())
        quick.addView(panelAction(if (Settings.canDrawOverlays(this)) "EDGE READY" else "EDGE SETUP") { dialog.dismiss(); overlaySetup() }, panelCell())
        quick.addView(panelAction("SETTINGS") { dialog.dismiss(); startActivity(Intent(Settings.ACTION_SETTINGS)) }, panelCell())
        quick.addView(panelAction("HOME APP") { dialog.dismiss(); requestHomeRole() }, panelCell())
        body.addView(quick, top(12))

        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply { gravity = Gravity.START or Gravity.BOTTOM }
            setLayout((resources.displayMetrics.widthPixels * .86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun infoLine(name: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.argb(100, 20, 22, 25), 12f, Color.argb(32, 255, 255, 255))
        addView(text(name, 8f, dim), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 9f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
    }

    private fun panelAction(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 8.8f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(17, 18, 21), 12f, Color.argb(42, 255, 255, 255))
        setOnClickListener { action() }
    }

    private fun showDesktopMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("BLACKLINE")
            .setItems(arrayOf("All apps", "Quick Pod", "Terminal", "Deck mode", "System wallpaper", "Cross-app rail", "Set as default Home")) { _, which ->
                when (which) {
                    0 -> showAllApps("")
                    1 -> showQuickPod()
                    2 -> startActivity(Intent(this, TerminalActivity::class.java))
                    3 -> openDeckMode()
                    4 -> wallpaperPicker()
                    5 -> overlaySetup()
                    6 -> requestHomeRole()
                }
            }.show()
    }

    private fun favoriteApps(): List<AppCache.Entry> {
        val ids = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (ids.isNotEmpty()) return apps.filter { ids.contains(it.pkg) }
        val preferred = listOf("phone", "messages", "chrome", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle -> apps.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) } }
            .distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else apps.take(6)
    }

    private fun quickApps(): List<AppCache.Entry> {
        val favorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        return apps.sortedWith(compareByDescending<AppCache.Entry> {
            val count = prefs.getInt("launch_count_${it.pkg}", 0)
            count + if (favorites.contains(it.pkg)) 1000 else 0
        }.thenByDescending {
            prefs.getLong("last_launch_${it.pkg}", 0L)
        }.thenBy { it.label.lowercase(Locale.US) })
    }

    private fun toggleFavorite(pkg: String) {
        val set = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (set.remove(pkg)) false else { set.add(pkg); true }
        prefs.edit().putStringSet("favorites", set).apply()
        toast(if (added) "Pinned to BLACKLINE rail" else "Removed from BLACKLINE rail")
        renderSafe()
    }

    private fun launch(pkg: String) {
        prefs.edit()
            .putInt("launch_count_$pkg", prefs.getInt("launch_count_$pkg", 0) + 1)
            .putLong("last_launch_$pkg", System.currentTimeMillis())
            .apply()
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } ?: toast("Unable to launch app")
    }

    private fun setRailCollapsed(value: Boolean) {
        railCollapsed = value
        prefs.edit().putBoolean("taskbar_collapsed", value).apply()
        renderSafe()
    }

    private fun wallpaperPicker() {
        val candidates = listOf(
            Intent(Intent.ACTION_SET_WALLPAPER),
            Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        val chosen = candidates.firstOrNull { it.resolveActivity(packageManager) != null }
        if (chosen != null) startActivity(chosen) else toast("Wallpaper picker unavailable")
    }

    private fun overlaySetup() {
        prefs.edit().putBoolean("edge_enabled", true).apply()
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            toast("Cross-app BLACKLINE rail enabled")
        }
    }

    private fun ensureCrossAppRailPermissionState() {
        if (Settings.canDrawOverlays(this) && !prefs.contains("edge_enabled")) {
            prefs.edit().putBoolean("edge_enabled", true).apply()
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 502)
            } else toast("BLACKLINE is already your Home app")
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun batteryShort(): String = batteryData().first
    private fun batteryLong(): String = batteryData().second

    private fun batteryData(): Pair<String, String> = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        Pair("$level%${if (charging) "+" else ""}", "$level% ${if (charging) "CHARGING" else "BATTERY"}")
    }.getOrDefault(Pair("--", "UNKNOWN"))

    private fun networkShort(): String = when (networkLong()) {
        "WI-FI" -> "WIFI"
        "CELLULAR" -> "CELL"
        "VPN" -> "VPN"
        else -> "OFF"
    }

    private fun networkLong(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "OFFLINE"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "ONLINE"
        }
    }.getOrDefault("OFFLINE")

    private fun mediaState(): String = runCatching {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.isMusicActive) "PLAYING" else "IDLE"
    }.getOrDefault("IDLE")

    private fun renderRecovery(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(text("BLACKLINE", 30f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        root.addView(text("RECOVERY MODE", 9f, dim), top(8))
        root.addView(text(message.take(180), 9f, Color.GRAY), top(12))
        root.addView(panelAction("TERMINAL") { startActivity(Intent(this@BlacklineHomeActivity, TerminalActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(18) })
        root.addView(panelAction("RELOAD HOME") { renderSafe() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            val threshold = dp(70).toFloat()

            if (abs(dy) > abs(dx) && dy < -threshold) {
                showAllApps("")
                return true
            }
            if (abs(dy) > abs(dx) && dy > threshold) {
                openDeckMode()
                return true
            }
            if (e1.x > resources.displayMetrics.widthPixels * .68f && dx < -threshold) {
                showQuickPod()
                return true
            }
            return false
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun appGridCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(96)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(2), dp(3), dp(2), dp(3))
    }

    private fun panelCell() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(52)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun vMargin(top: Int) = LinearLayout.LayoutParams(dp(48), dp(47)).apply { topMargin = dp(top) }
    private fun top(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }

    private fun leftRailBackground() = GradientDrawable().apply {
        setColor(panel)
        cornerRadii = floatArrayOf(0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f)
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun edgeHandleBackground() = GradientDrawable().apply {
        setColor(Color.argb(232, 4, 5, 7))
        cornerRadii = floatArrayOf(0f, 0f, dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f)
        setStroke(dp(1), Color.argb(130, 255, 255, 255))
    }

    private fun rightPodBackground() = GradientDrawable().apply {
        setColor(Color.argb(240, 4, 5, 7))
        cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f, dp(24).toFloat(), dp(24).toFloat())
        setStroke(dp(1), Color.argb(78, 255, 255, 255))
    }

    private fun topRounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
        setStroke(dp(1), stroke)
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun toast(value: String) = android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class SimpleWatcher(val change: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}
