package online.pcguys.blackline

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors
import kotlin.math.max

class TerminalActivity : AppCompatActivity() {
    private enum class Mode { ANDROID, KALI }

    private val cyan = Color.rgb(69, 246, 229)
    private val bg = Color.rgb(4, 6, 8)
    private val dim = Color.rgb(138, 153, 163)

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var engine: BlacklineShellEngine
    private lateinit var kaliSession: KaliSession
    private lateinit var modeChip: TextView
    private lateinit var stopChip: TextView
    private lateinit var shortcutHolder: LinearLayout

    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var mode = Mode.ANDROID
    @Volatile private var androidBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = BlacklineShellEngine(this)
        kaliSession = KaliSession(engine.kali)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        buildUi()
        append("BLACKLINE // TERMINAL\n")
        append("NATIVE ENGINE // ${Build.MODEL} // API ${Build.VERSION.SDK_INT}\n")
        append("Streaming process output • persistent aliases/env • Android bridge • Kali/PRoot backend\n")
        append("No Termux application/runtime dependency.\n\n")
        promptAndroid()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        engine.cancelActive()
        kaliSession.stop()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun hideStatusBar() {
        runCatching {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = bg
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // IME-aware: command bar + shortcut controls always remain above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, max(ime.bottom, bars.bottom))
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(9, 12, 15))
        }
        top.addView(TextView(this).apply {
            text = "BLACKLINE // SHELL"
            textSize = 10.5f
            letterSpacing = .10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cyan)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        modeChip = chip("ANDROID").apply { setOnClickListener { toggleMode() } }
        top.addView(modeChip, LinearLayout.LayoutParams(dp(74), dp(38)).apply { rightMargin = dp(5) })

        stopChip = chip("STOP").apply {
            alpha = .45f
            setOnClickListener { stopActive() }
        }
        top.addView(stopChip, LinearLayout.LayoutParams(dp(52), dp(38)).apply { rightMargin = dp(5) })

        top.addView(chip("HOME").apply {
            setOnClickListener {
                startActivity(Intent(this@TerminalActivity, BlacklineHomeActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(dp(55), dp(38)))
        root.addView(top)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        output = TextView(this).apply {
            setTextColor(Color.rgb(222, 233, 236))
            textSize = 12.2f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(13), dp(12), dp(13), dp(20))
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val shortcutScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.rgb(8, 10, 13))
        }
        shortcutHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        shortcutScroll.addView(shortcutHolder)
        root.addView(shortcutScroll, LinearLayout.LayoutParams(-1, dp(52)))
        drawShortcuts()

        val commandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(6, 8, 10))
        }
        commandBar.addView(TextView(this).apply {
            text = "$"
            textSize = 18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(cyan)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(50)))

        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(dim)
            hint = "command"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.rgb(11, 14, 17), 14f, Color.rgb(39, 53, 58))
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    val cmd = text.toString()
                    setText("")
                    execute(cmd)
                    true
                } else false
            }
        }
        commandBar.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f))
        commandBar.addView(historyButton("↑") { historyMove(-1) })
        commandBar.addView(historyButton("↓") { historyMove(1) })
        root.addView(commandBar)

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun drawShortcuts() {
        shortcutHolder.removeAllViews()
        val items = if (mode == Mode.ANDROID) {
            listOf(
                "HELP" to { execute("help") },
                "LS" to { execute("ls") },
                "PWD" to { execute("pwd") },
                "DEVICE" to { execute("device") },
                "NET" to { execute("network") },
                "KALI" to { execute("kali status") },
                "CLEAR" to { execute("clear") }
            )
        } else {
            listOf(
                "ID" to { execute("id") },
                "LS" to { execute("ls -la") },
                "PWD" to { execute("pwd") },
                "UNAME" to { execute("uname -a") },
                "APT UPDATE" to { execute("apt update") },
                "TOOLS" to { execute("dpkg -l | tail -25") },
                "EXIT" to { switchToAndroid() }
            )
        }
        items.forEach { (title, action) ->
            shortcutHolder.addView(shortcut(title, action), LinearLayout.LayoutParams(dp(if (title.length > 6) 84 else 62), dp(42)).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }

    private fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isBlank()) {
            input.requestFocus()
            return
        }
        history.add(cmd)
        historyIndex = history.size

        if (mode == Mode.KALI) {
            if (!kaliSession.isRunning()) {
                append("Kali session is not running. Switching to Android mode.\n")
                switchToAndroid()
                return
            }
            append("kali# $cmd\n")
            kaliSession.send(cmd)
            input.requestFocus()
            return
        }

        if (androidBusy) {
            append("A command is still running. Tap STOP first.\n")
            return
        }
        androidBusy = true
        updateBusyState()
        append("blackline:${engine.shortPath()}$ $cmd\n")
        executor.execute {
            val result = engine.execute(cmd) { chunk -> runOnUiThread { append(chunk) } }
            runOnUiThread {
                if (result.clearScreen) output.text = ""
                if (result.output.isNotBlank()) append(result.output)
                androidBusy = false
                updateBusyState()
                if (result.promptAgain) promptAndroid()
            }
        }
    }

    private fun toggleMode() {
        if (mode == Mode.KALI) switchToAndroid() else switchToKali()
    }

    private fun switchToKali() {
        if (androidBusy) {
            append("Stop the active Android command before switching modes.\n")
            return
        }
        if (!engine.kali.isInstalled()) {
            append("Kali is not installed yet.\nRun: kali install minimal\n")
            return
        }
        if (!engine.kali.isProotAvailable()) {
            append("PRoot runtime is missing from this build.\n")
            return
        }
        mode = Mode.KALI
        modeChip.text = "KALI"
        input.hint = "kali command"
        drawShortcuts()
        updateBusyState()
        append("\nBLACKLINE // KALI\nStarting persistent Kali ARM64 userspace…\n")
        try {
            kaliSession.start(
                onOutput = { chunk -> runOnUiThread { append(chunk) } },
                onExit = { code -> runOnUiThread {
                    append("\nKali session exited ($code).\n")
                    if (mode == Mode.KALI) switchToAndroid()
                } }
            )
        } catch (e: Exception) {
            append("Unable to start Kali: ${e.message}\n")
            switchToAndroid()
        }
        input.requestFocus()
    }

    private fun switchToAndroid() {
        if (mode == Mode.KALI) kaliSession.stop()
        mode = Mode.ANDROID
        modeChip.text = "ANDROID"
        input.hint = "command"
        drawShortcuts()
        updateBusyState()
        append("\nBLACKLINE // ANDROID\n")
        promptAndroid()
    }

    private fun stopActive() {
        if (mode == Mode.KALI) {
            if (kaliSession.isRunning()) {
                append("\nStopping Kali session…\n")
                kaliSession.stop()
            }
            switchToAndroid()
        } else if (androidBusy) {
            engine.cancelActive()
            append("^C\n")
            androidBusy = false
            updateBusyState()
            promptAndroid()
        }
    }

    private fun updateBusyState() {
        val active = androidBusy || (mode == Mode.KALI && kaliSession.isRunning())
        stopChip.alpha = if (active) 1f else .45f
        input.isEnabled = mode == Mode.KALI || !androidBusy
    }

    private fun promptAndroid() {
        if (mode != Mode.ANDROID) return
        input.requestFocus()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun append(value: String) {
        output.append(value)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun historyMove(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, history.size)
        input.setText(if (historyIndex == history.size) "" else history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun chip(title: String): TextView = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 8.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(Color.rgb(18, 23, 27), 10f, Color.rgb(43, 60, 64))
    }

    private fun shortcut(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 8.2f
        typeface = Typeface.MONOSPACE
        setTextColor(cyan)
        background = rounded(Color.rgb(13, 17, 20), 10f, Color.rgb(34, 49, 52))
        setOnClickListener { action() }
    }

    private fun historyButton(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(cyan)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(50)).apply { leftMargin = dp(5) }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
