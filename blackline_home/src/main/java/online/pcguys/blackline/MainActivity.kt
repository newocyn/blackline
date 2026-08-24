package online.pcguys.blackline

import android.app.Dialog
import android.app.role.RoleManager
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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
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
    private val cyan = Color.rgb(69, 246, 229)
    private val bg = Color.rgb(5, 6, 8)
    private val panel = Color.rgb(15, 18, 22)
    private val panelSoft = Color.argb(210, 13, 16, 20)
    private val dim = Color.rgb(157, 169, 178)
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()
    private var wallpaperUri: String? = null

    data class AppEntry(val info: ResolveInfo, val label: String, val pkg: String, val icon: Drawable)

    private val clockUpdater = object : Runnable {
        override fun run() {
            renderHome()
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        wallpaperUri = prefs.getString("wallpaper_uri", null)
        loadApps()
        renderHome()
        handler.postDelayed(clockUpdater, 60_000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("openDrawer", false) == true) {
            handler.postDelayed({ showAppDrawer("") }, 150)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            runCatching { startService(Intent(this, EdgeDockService::class.java)) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun loadApps() {
        Thread {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    AppEntry(it, it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.loadIcon(packageManager))
                }
                .sortedBy { it.label.lowercase(Locale.US) }
            runOnUiThread {
                apps = list
                renderHome()
            }
        }.start()
    }

    private fun renderHome() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }

        wallpaperUri?.let { raw ->
            runCatching {
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(raw))
                }
                root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        root.addView(View(this).apply { setBackgroundColor(Color.argb(if (wallpaperUri == null) 10 else 158, 2, 4, 7)) }, FrameLayout.LayoutParams(-1, -1))

        val horizontal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), dp(10), dp(8))
        }
        horizontal.addView(buildDock(), LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(7); rightMargin = dp(10)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(30))
        }
        content.addView(topBar())
        content.addView(hero(), mt(16))
        content.addView(searchBox(), mt(15))
        content.addView(statusRow(), mt(12))
        content.addView(quickActions(), mt(12))
        content.addView(favoritesCard(), mt(12))
        content.addView(footer(), mt(18))
        scroll.addView(content)
        horizontal.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(horizontal, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(8), dp(7), dp(8))
            background = rounded(Color.argb(225, 8, 10, 13), 24f, Color.argb(120, 69, 246, 229), 1)
        }
        dock.addView(dockGlyph("//", "BLACKLINE") { renderHome() })
        dock.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(9) })
        dock.addView(dockGlyph(">_", "TERMINAL") { startActivity(Intent(this, TerminalActivity::class.java)) })
        dock.addView(dockGlyph("▦", "ALL APPS") { showAppDrawer("") }, mt(7))

        val favs = favoriteApps().take(5)
        favs.forEach { entry -> dock.addView(dockApp(entry), mt(7)) }

        val spacer = View(this)
        dock.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        dock.addView(dockGlyph("◁", "EDGE RAIL") { toggleEdgePermission() })
        dock.addView(dockGlyph("▧", "WALLPAPER") { chooseWallpaper() }, mt(7))
        return dock
    }

    private fun dockGlyph(glyph: String, tip: String, action: () -> Unit): View {
        return TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = if (glyph == "//") 18f else 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cyan)
            background = rounded(Color.argb(120, 20, 26, 29), 16f, Color.argb(75, 69, 246, 229), 1)
            setTooltipText(tip)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
    }

    private fun dockApp(entry: AppEntry): View {
        return FrameLayout(this).apply {
            background = rounded(Color.argb(115, 20, 24, 28), 16f, Color.argb(50, 255, 255, 255), 1)
            setTooltipText(entry.label)
            val iv = ImageView(this@MainActivity).apply {
                setImageDrawable(entry.icon)
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }
            addView(iv, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { launch(entry.pkg) }
            setOnLongClickListener { toggleFavorite(entry.pkg); true }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
    }

    private fun topBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.argb(208, 8, 11, 14), 17f, Color.argb(90, 69, 246, 229), 1)
            addView(TextView(this@MainActivity).apply {
                text = "BLACKLINE // HOME"
                textSize = 11f; letterSpacing = .12f; typeface = Typeface.MONOSPACE; setTextColor(cyan)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE)
            })
        }
    }

    private fun hero(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                textSize = 56f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = SimpleDateFormat("EEEE  //  MMMM d", Locale.US).format(Date()).uppercase(Locale.US)
                textSize = 11f; letterSpacing = .16f; typeface = Typeface.MONOSPACE; setTextColor(cyan)
            })
            addView(TextView(this@MainActivity).apply {
                text = "YOUR PHONE // YOUR SHELL // YOUR DECK"
                textSize = 10f; letterSpacing = .11f; typeface = Typeface.MONOSPACE; setTextColor(dim)
                setPadding(0, dp(7), 0, 0)
            })
        }
    }

    private fun searchBox(): View {
        return EditText(this).apply {
            hint = "SEARCH APPS"
            setHintTextColor(Color.rgb(101, 115, 124))
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(Color.argb(220, 10, 13, 16), 19f, Color.argb(120, 69, 246, 229), 1)
            setOnEditorActionListener { _, _, _ ->
                showAppDrawer(text.toString())
                true
            }
            setOnClickListener { if (text.isBlank()) showAppDrawer("") }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
    }

    private fun statusRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        row.addView(metric("BATTERY", "$level%"), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        row.addView(metric("NETWORK", networkLabel()), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(2); rightMargin = dp(4) })
        row.addView(metric("APPS", apps.size.toString()), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        return row
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(panelSoft, 16f, Color.argb(55, 255, 255, 255), 1)
        addView(label(label, 8f, dim))
        addView(label(value, 15f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
    }

    private fun quickActions(): View {
        val card = card("DECK CONTROL", "FAST ENTRY POINTS")
        val grid = GridLayout(this).apply { columnCount = 2 }
        grid.addView(actionTile("TERMINAL", "ANDROID SHELL", ">_") { startActivity(Intent(this, TerminalActivity::class.java)) }, cell())
        grid.addView(actionTile("APPLICATIONS", "FULL APP MATRIX", "▦") { showAppDrawer("") }, cell())
        grid.addView(actionTile("EDGE RAIL", if (Settings.canDrawOverlays(this)) "ACTIVE" else "ENABLE OVERLAY", "◁") { toggleEdgePermission() }, cell())
        grid.addView(actionTile("WALLPAPER", "CHANGE DESKTOP", "▧") { chooseWallpaper() }, cell())
        grid.addView(actionTile("HOME ROLE", "SET BLACKLINE DEFAULT", "⌂") { requestHomeRole() }, cell())
        grid.addView(actionTile("SETTINGS", "ANDROID SYSTEM", "⚙") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, cell())
        card.addView(grid, mt(8))
        return card
    }

    private fun favoritesCard(): View {
        val card = card("PINNED", "LONG-PRESS APPS TO PIN OR UNPIN")
        val favs = favoriteApps().take(8)
        if (favs.isEmpty()) {
            card.addView(label("Open the app matrix and long-press an app to pin it to your BLACKLINE dock.", 12f, dim), mt(10))
        } else {
            val grid = GridLayout(this).apply { columnCount = 4 }
            favs.forEach { grid.addView(appTile(it), appCell()) }
            card.addView(grid, mt(8))
        }
        return card
    }

    private fun showAppDrawer(initial: String) {
        if (apps.isEmpty()) loadApps()
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.argb(245, 5, 6, 8)) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("APPLICATION MATRIX", 14f, cyan).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "×"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        body.addView(header)
        val search = EditText(this).apply {
            hint = "FILTER BY NAME OR PACKAGE"
            setHintTextColor(Color.rgb(100, 112, 121)); setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 13f
            setPadding(dp(16), 0, dp(16), 0); isSingleLine = true
            background = rounded(panel, 17f, Color.argb(100, 69, 246, 229), 1)
            setText(initial); setSelection(text.length)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 4; setPadding(0, dp(8), 0, dp(30)) }
        scroll.addView(grid)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun draw(q: String) {
            grid.removeAllViews()
            apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }.forEach { entry ->
                grid.addView(appTile(entry) { dialog.dismiss(); launch(entry.pkg) }, appCell())
            }
        }
        search.addTextChangedListener(SimpleWatcher { draw(it) })
        draw(initial)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(frame)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun appTile(entry: AppEntry, action: (() -> Unit)? = null): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = rounded(Color.argb(145, 15, 18, 22), 17f, Color.argb(45, 255, 255, 255), 1)
            val icon = ImageView(this@MainActivity).apply { setImageDrawable(entry.icon) }
            addView(icon, LinearLayout.LayoutParams(dp(45), dp(45)))
            addView(label(entry.label, 10f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            setOnClickListener { action?.invoke() ?: launch(entry.pkg) }
            setOnLongClickListener { toggleFavorite(entry.pkg); true }
        }
    }

    private fun toggleFavorite(pkg: String) {
        val current = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (current.contains(pkg)) { current.remove(pkg); false } else { current.add(pkg); true }
        prefs.edit().putStringSet("favorites", current).apply()
        toast(if (added) "Pinned to BLACKLINE" else "Removed from BLACKLINE dock")
        if (Settings.canDrawOverlays(this)) {
            stopService(Intent(this, EdgeDockService::class.java))
            startService(Intent(this, EdgeDockService::class.java))
        }
        renderHome()
    }

    private fun favoriteApps(): List<AppEntry> {
        val ids = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (ids.isNotEmpty()) return apps.filter { ids.contains(it.pkg) }
        val preferred = listOf("chrome", "messages", "phone", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle -> apps.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) } }.distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else apps.take(5)
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } ?: toast("Unable to launch app")
    }

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivityForResult(intent, 501)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                wallpaperUri = uri.toString()
                prefs.edit().putString("wallpaper_uri", wallpaperUri).apply()
                renderHome()
            }
        }
    }

    private fun toggleEdgePermission() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, EdgeDockService::class.java))
            toast("BLACKLINE edge rail active")
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 502)
            else toast("BLACKLINE is already your home app")
        } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun networkLabel(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "ONLINE"
        }
    }

    private fun card(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(15))
        background = rounded(panelSoft, 20f, Color.argb(55, 255, 255, 255), 1)
        addView(label(title, 12f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = .1f })
        addView(label(subtitle, 8f, dim).apply { letterSpacing = .1f }, mt(2))
    }

    private fun actionTile(title: String, subtitle: String, glyph: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = rounded(Color.argb(175, 10, 13, 16), 17f, Color.argb(55, 69, 246, 229), 1)
        addView(label(glyph, 20f, cyan).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) })
        addView(label(title, 11f, Color.WHITE).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }, mt(5))
        addView(label(subtitle, 8f, dim), mt(3))
        setOnClickListener { action() }
    }

    private fun footer(): View = label("BLACKLINE // ANDROID DECK 0.2.0     ${Build.MODEL.uppercase(Locale.US)}", 8f, Color.rgb(94, 109, 117)).apply { gravity = Gravity.CENTER; letterSpacing = .12f }
    private fun divider() = View(this).apply { setBackgroundColor(Color.argb(70, 69, 246, 229)) }
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE }
    private fun mt(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun cell() = GridLayout.LayoutParams().apply { width = 0; height = dp(116); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) }
    private fun appCell() = GridLayout.LayoutParams().apply { width = 0; height = dp(110); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int, strokeWidth: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke) }
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    private class SimpleWatcher(val onChange: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChange(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) {}
    }
}
