package online.pcguys.blackline

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class EdgeDockService : Service() {
    private lateinit var wm: WindowManager
    private var view: View? = null
    private val prefs by lazy { getSharedPreferences("blackline_home", MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Settings.canDrawOverlays(this)) safeShowCollapsed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) safeShowCollapsed()
        return START_STICKY
    }

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    private fun params(width: Int, height: Int, yPos: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        x = 0
        y = yPos
    }

    private fun safeShowCollapsed() {
        runCatching { showCollapsed() }.onFailure { removeView(); stopSelf() }
    }

    private fun safeShowExpanded() {
        runCatching { showExpanded() }.onFailure { removeView(); safeShowCollapsed() }
    }

    private fun showCollapsed() {
        removeView()
        val handle = TextView(this).apply {
            text = "//"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = edgeRounded(Color.argb(238, 4, 5, 7), Color.argb(155, 255, 255, 255))
        }
        val p = params(dp(28), dp(82), prefs.getInt("edge_y", dp(250)))
        var downY = 0f
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - downY).toInt()
                    if (abs(delta) > dp(6)) moved = true
                    p.y = (startY + delta).coerceIn(dp(30), resources.displayMetrics.heightPixels - dp(120))
                    runCatching { wm.updateViewLayout(handle, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("edge_y", p.y).apply()
                    if (!moved) safeShowExpanded()
                    true
                }
                else -> false
            }
        }
        view = handle
        wm.addView(handle, p)
    }

    private fun showExpanded() {
        removeView()
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = rightRounded(Color.argb(248, 4, 5, 7), Color.argb(145, 255, 255, 255))
        }

        rail.addView(glyph("‹", "COLLAPSE") { safeShowCollapsed() })
        rail.addView(glyph("//", "START") {
            launchIntent(Intent(this, BlacklineHomeActivity::class.java).putExtra("openDrawer", true))
            safeShowCollapsed()
        }, mt(6))
        rail.addView(glyph(">_", "TERMINAL") {
            launchIntent(Intent(this, TerminalActivity::class.java))
            safeShowCollapsed()
        }, mt(6))
        rail.addView(glyph("⌂", "HOME") {
            launchIntent(Intent(this, BlacklineHomeActivity::class.java))
            safeShowCollapsed()
        }, mt(6))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val apps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        favoriteApps().take(8).forEach { apps.addView(appButton(it), mt(5)) }
        scroll.addView(apps)
        rail.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(5) })

        rail.addView(TextView(this).apply {
            text = statusText()
            gravity = Gravity.CENTER
            textSize = 7.5f
            setLineSpacing(0f, .94f)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(130, 20, 21, 24), 11f, Color.argb(45, 255, 255, 255))
        }, LinearLayout.LayoutParams(dp(54), dp(76)).apply { topMargin = dp(6) })

        val topInset = dp(26)
        val h = (resources.displayMetrics.heightPixels - dp(54)).coerceAtLeast(dp(320))
        val p = params(dp(70), h, topInset)
        view = rail
        wm.addView(rail, p)
    }

    private fun favoriteApps(): List<AppCache.Entry> {
        var all = AppCache.current()
        if (all.isEmpty()) all = runCatching { AppCache.load(packageManager, packageName) }.getOrDefault(emptyList())
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        if (favs.isNotEmpty()) return all.filter { favs.contains(it.pkg) }
        val preferred = listOf("phone", "messages", "chrome", "camera", "gmail", "maps")
        val picked = preferred.mapNotNull { needle -> all.firstOrNull { it.label.contains(needle, true) || it.pkg.contains(needle, true) } }
            .distinctBy { it.pkg }
        return if (picked.isNotEmpty()) picked else all.take(6)
    }

    private fun appButton(app: AppCache.Entry): View = FrameLayout(this).apply {
        background = rounded(Color.argb(110, 18, 19, 22), 12f, Color.argb(45, 255, 255, 255))
        setTooltipText(app.label)
        addView(ImageView(this@EdgeDockService).apply {
            setImageDrawable(app.icon)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener {
            packageManager.getLaunchIntentForPackage(app.pkg)?.let { launchIntent(it) }
            safeShowCollapsed()
        }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(50))
    }

    private fun glyph(value: String, tip: String, action: () -> Unit): View = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setTooltipText(tip)
        background = rounded(Color.argb(135, 18, 19, 22), 12f, Color.argb(65, 255, 255, 255))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(54), dp(50))
    }

    private fun statusText(): String {
        val time = SimpleDateFormat("h:mm", Locale.US).format(Date())
        val battery = runCatching {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            "${i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0}%"
        }.getOrDefault("--")
        return "$time\n$battery"
    }

    private fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun removeView() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun mt(v: Int) = LinearLayout.LayoutParams(dp(54), dp(50)).apply { topMargin = dp(v) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        cornerRadii = floatArrayOf(0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f)
        setStroke(dp(1), stroke)
    }
}
