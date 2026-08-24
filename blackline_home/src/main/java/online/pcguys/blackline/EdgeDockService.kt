package online.pcguys.blackline

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

class EdgeDockService : Service() {
    private lateinit var wm: WindowManager
    private var view: View? = null
    private var expanded = false
    private val cyan = Color.rgb(69, 246, 229)
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Settings.canDrawOverlays(this)) showCollapsed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view == null) showCollapsed()
        return START_STICKY
    }

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    private fun params(width: Int, height: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        x = 0
        y = prefs.getInt("edge_y", 280)
    }

    private fun showCollapsed() {
        expanded = false
        removeView()
        val handle = TextView(this).apply {
            text = "//"
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cyan)
            background = rounded(Color.argb(235, 6, 8, 10), 0f, 18f, 18f, 0f, Color.argb(150, 69, 246, 229))
        }
        val p = params(dp(30), dp(78))
        var downY = 0f
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - downY).toInt()
                    if (abs(delta) > dp(6)) moved = true
                    p.y = (startY + delta).coerceAtLeast(30)
                    runCatching { wm.updateViewLayout(handle, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("edge_y", p.y).apply()
                    if (!moved) showExpanded()
                    true
                }
                else -> false
            }
        }
        view = handle
        wm.addView(handle, p)
    }

    private fun showExpanded() {
        expanded = true
        removeView()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(10), dp(7), dp(10))
            background = rounded(Color.argb(245, 5, 7, 9), 0f, 22f, 22f, 0f, Color.argb(150, 69, 246, 229))
        }
        root.addView(glyph("‹", "COLLAPSE") { showCollapsed() })
        root.addView(glyph(">_", "TERMINAL") {
            launchIntent(Intent(this, TerminalActivity::class.java))
            showCollapsed()
        }, mt(8))
        root.addView(glyph("⌂", "HOME") {
            launchIntent(Intent(this, MainActivity::class.java))
            showCollapsed()
        }, mt(8))
        root.addView(glyph("▦", "APPS") {
            launchIntent(Intent(this, MainActivity::class.java).putExtra("openDrawer", true))
            showCollapsed()
        }, mt(8))

        val scroll = ScrollView(this)
        val appsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        favoriteApps().take(7).forEach { app -> appsHolder.addView(appButton(app), mt(8)) }
        scroll.addView(appsHolder)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        val p = params(dp(76), WindowManager.LayoutParams.MATCH_PARENT).apply { y = 0 }
        view = root
        wm.addView(root, p)
    }

    private data class EdgeApp(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable)

    private fun favoriteApps(): List<EdgeApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = packageManager.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { EdgeApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager)) }
            .sortedBy { it.label.lowercase(Locale.US) }
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (favs.isNotEmpty()) return all.filter { favs.contains(it.pkg) }
        val preferred = listOf("chrome", "messages", "phone", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle -> all.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) } }.distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else all.take(6)
    }

    private fun appButton(app: EdgeApp): View = FrameLayout(this).apply {
        background = rounded(Color.argb(120, 18, 22, 25), 14f, 14f, 14f, 14f, Color.argb(60, 255, 255, 255))
        setTooltipText(app.label)
        addView(ImageView(this@EdgeDockService).apply { setImageDrawable(app.icon); setPadding(dp(9), dp(9), dp(9), dp(9)) }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener {
            packageManager.getLaunchIntentForPackage(app.pkg)?.let { launchIntent(it) }
            showCollapsed()
        }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
    }

    private fun glyph(textValue: String, tip: String, action: () -> Unit): View = TextView(this).apply {
        text = textValue
        gravity = Gravity.CENTER
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(cyan)
        setTooltipText(tip)
        background = rounded(Color.argb(130, 18, 22, 25), 14f, 14f, 14f, 14f, Color.argb(80, 69, 246, 229))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
    }

    private fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun removeView() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun mt(v: Int) = LinearLayout.LayoutParams(dp(54), dp(54)).apply { topMargin = dp(v) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, tl: Float, tr: Float, br: Float, bl: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadii = floatArrayOf(dp(tl.toInt()).toFloat(), dp(tl.toInt()).toFloat(), dp(tr.toInt()).toFloat(), dp(tr.toInt()).toFloat(), dp(br.toInt()).toFloat(), dp(br.toInt()).toFloat(), dp(bl.toInt()).toFloat(), dp(bl.toInt()).toFloat())
        setStroke(dp(1), stroke)
    }
}
