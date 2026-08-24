package online.pcguys.blackline

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class DeckModeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var gestures: GestureDetector

    private val ticker = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestures = GestureDetector(this, GestureListener())
        hideSystemUi()
        buildUi()
        update()
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { hideSystemUi() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        time = text("", 52f, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            letterSpacing = -.02f
        }
        center.addView(time)

        date = text("", 9f, Color.rgb(184, 188, 194)).apply {
            letterSpacing = .16f
        }
        center.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })

        center.addView(View(this).apply { setBackgroundColor(Color.rgb(62, 64, 70)) }, LinearLayout.LayoutParams(dp(86), dp(1)).apply { topMargin = dp(22) })

        center.addView(text("BLACKLINE // DECK", 8f, Color.rgb(154, 158, 166)).apply {
            letterSpacing = .17f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(17) })

        status = text("", 9f, Color.WHITE).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        center.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        root.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(38)
            topMargin = dp(-20)
        })

        root.addView(TextView(this).apply {
            text = ">_"
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { startActivity(Intent(this@DeckModeActivity, TerminalActivity::class.java)) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(24)
            bottomMargin = dp(28)
        })

        root.addView(text("SWIPE UP // HOME", 7f, Color.rgb(100, 104, 112)).apply {
            letterSpacing = .14f
        }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(38)
            bottomMargin = dp(32)
        })

        setContentView(root)
    }

    private fun update() {
        val now = Date()
        time.text = SimpleDateFormat("HH:mm", Locale.US).format(now)
        date.text = SimpleDateFormat("EEE • dd MMMM • yyyy", Locale.US).format(now).uppercase(Locale.US)
        status.text = buildString {
            append("MEDIA     ${mediaState()}\n")
            append("NETWORK   ${networkState()}\n")
            append("BATTERY   ${batteryState()}\n")
            append("SYSTEM    READY")
        }
    }

    private fun mediaState(): String = runCatching {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.isMusicActive) "PLAYING" else "IDLE"
    }.getOrDefault("IDLE")

    private fun networkState(): String = runCatching {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return@runCatching "OFFLINE"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "ONLINE"
        }
    }.getOrDefault("OFFLINE")

    private fun batteryState(): String = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        "$level%${if (charging) " CHARGING" else ""}"
    }.getOrDefault("--")

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val dy = e2.y - e1.y
            val dx = e2.x - e1.x
            if (abs(dy) > abs(dx) && dy < -dp(70)) {
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
