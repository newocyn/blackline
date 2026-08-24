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
    private val cyan = Color.rgb(69, 246, 229)
    private val bg = Color.rgb(4, 6, 8)
    private val dim = Color.rgb(138, 153, 163)

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var engine: BlacklineShellEngine

    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = BlacklineShellEngine(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideStatusBar()
        buildUi()
        append("BLACKLINE // TERMINAL\n")
        append("NATIVE SHELL ENGINE // ${Build.MODEL} // API ${Build.VERSION.SDK_INT}\n")
        append("No Termux dependency. Type 'help' for BLACKLINE commands.\n\n")
        prompt()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun hideStatusBar() {
        runCatching {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = bg
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Keep the terminal controls above the software keyboard on modern edge-to-edge Android.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val bottom = max(ime.bottom, bars.bottom)
            view.setPadding(0, 0, 0, bottom)
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(10), dp(9))
            setBackgroundColor(Color.rgb(9, 12, 15))
            addView(TextView(this@TerminalActivity).apply {
                text = "BLACKLINE // SHELL"
                textSize = 11f
                letterSpacing = .12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(cyan)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@TerminalActivity).apply {
                text = "HOME"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(Color.rgb(18, 23, 27), 12f, Color.rgb(43, 60, 64))
                setOnClickListener {
                    startActivity(Intent(this@TerminalActivity, BlacklineHomeActivity::class.java))
                    finish()
                }
            })
        }
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        output = TextView(this).apply {
            setTextColor(Color.rgb(222, 233, 236))
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(13), dp(14), dp(20))
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setBackgroundColor(Color.rgb(8, 10, 13))
        }
        listOf(
            "HELP" to "help",
            "LS" to "ls",
            "PWD" to "pwd",
            "DEVICE" to "device",
            "DF" to "df -h",
            "CLEAR" to "clear"
        ).forEach { pair ->
            shortcuts.addView(
                shortcut(pair.first) { execute(pair.second) },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
            )
        }
        root.addView(shortcuts)

        val commandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(7), dp(9), dp(9))
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

    private fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isBlank()) {
            prompt()
            return
        }

        history.add(cmd)
        historyIndex = history.size
        append("$cmd\n")
        input.isEnabled = false

        executor.execute {
            val result = engine.execute(cmd)
            runOnUiThread {
                if (result.clearScreen) output.text = ""
                if (result.output.isNotBlank()) append(result.output)
                input.isEnabled = true
                if (result.promptAgain) prompt()
            }
        }
    }

    private fun prompt() {
        append("blackline:${engine.shortPath()}$ ")
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

    private fun shortcut(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 8.5f
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
