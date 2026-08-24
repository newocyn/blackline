package online.pcguys.blackline

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class TerminalActivity : AppCompatActivity() {
    private val cyan = Color.rgb(69, 246, 229)
    private val bg = Color.rgb(4, 6, 8)
    private val dim = Color.rgb(138, 153, 163)
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var cwd: File = File("/sdcard")
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        append("BLACKLINE // TERMINAL\n")
        append("ANDROID SHELL // ${Build.MODEL} // API ${Build.VERSION.SDK_INT}\n")
        append("Type 'help' for BLACKLINE commands. Standard Android shell commands also work.\n\n")
        prompt()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
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
                setOnClickListener { startActivity(Intent(this@TerminalActivity, MainActivity::class.java)); finish() }
            })
        }
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this).apply { isFillViewport = true }
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
        listOf("HELP" to "help", "LS" to "ls", "PWD" to "pwd", "DEVICE" to "device", "DF" to "df -h", "CLEAR" to "clear").forEach { pair ->
            shortcuts.addView(shortcut(pair.first) { execute(pair.second) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
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
    }

    private fun execute(raw: String) {
        val cmd = raw.trim()
        if (cmd.isBlank()) { prompt(); return }
        history.add(cmd)
        historyIndex = history.size
        append("$cmd\n")

        when {
            cmd == "clear" -> { output.text = ""; prompt() }
            cmd == "help" -> { append(helpText()); prompt() }
            cmd == "pwd" -> { append(cwd.absolutePath + "\n"); prompt() }
            cmd == "device" -> { append(deviceInfo()); prompt() }
            cmd == "home" -> { startActivity(Intent(this, MainActivity::class.java)); prompt() }
            cmd == "apps" -> { append(appList("") + "\n"); prompt() }
            cmd.startsWith("apps ") -> { append(appList(cmd.removePrefix("apps ").trim()) + "\n"); prompt() }
            cmd.startsWith("open ") -> { openApp(cmd.removePrefix("open ").trim()); prompt() }
            cmd == "kali" -> { append("KALI backend is not bundled in BLACKLINE Home 0.2 yet. The launcher terminal is the native Android shell. PRoot/Kali becomes the next backend so it can live under online.pcguys.blackline without the Termux package collision.\n"); prompt() }
            cmd == "termux" -> {
                val i = packageManager.getLaunchIntentForPackage("com.termux")
                if (i != null) { startActivity(i); append("Opened Termux.\n") } else append("Termux is not installed.\n")
                prompt()
            }
            cmd == "cd" -> { cwd = File("/sdcard"); append(cwd.absolutePath + "\n"); prompt() }
            cmd.startsWith("cd ") -> {
                val targetRaw = cmd.removePrefix("cd ").trim()
                val target = when {
                    targetRaw == "~" -> filesDir
                    targetRaw.startsWith("/") -> File(targetRaw)
                    else -> File(cwd, targetRaw)
                }
                if (target.exists() && target.isDirectory) { cwd = runCatching { target.canonicalFile }.getOrDefault(target); append(cwd.absolutePath + "\n") }
                else append("cd: no such directory: $targetRaw\n")
                prompt()
            }
            else -> runShell(cmd)
        }
    }

    private fun runShell(cmd: String) {
        executor.execute {
            val result = try {
                val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .directory(cwd.takeIf { it.exists() } ?: filesDir)
                    .redirectErrorStream(true)
                    .start()
                val text = p.inputStream.bufferedReader().use { it.readText() }
                val exit = p.waitFor()
                buildString {
                    if (text.isNotBlank()) append(text.trimEnd()).append('\n')
                    if (exit != 0 && text.isBlank()) append("exit $exit\n")
                }
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}\n"
            }
            runOnUiThread { append(result); prompt() }
        }
    }

    private fun helpText(): String = """
BLACKLINE COMMANDS
  help                 show this command index
  clear                clear terminal transcript
  device               Android / kernel / architecture information
  apps [query]          list launchable apps
  open <package|name>   launch an Android app
  home                  return to BLACKLINE Home
  termux                open Termux if installed
  kali                  Kali backend status
  cd / pwd              directory navigation

ANDROID SHELL
  ls, cat, getprop, uname, df, ps, ping, id and other commands exposed by /system/bin/sh work directly.

""".trimStart()

    private fun deviceInfo(): String = buildString {
        append("DEVICE      ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("ANDROID     ${Build.VERSION.RELEASE} // API ${Build.VERSION.SDK_INT}\n")
        append("ARCH        ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("KERNEL      ")
        append(runCatching { ProcessBuilder("uname", "-r").start().inputStream.bufferedReader().readText().trim() }.getOrDefault("unknown"))
        append("\nPACKAGE     $packageName\n")
        append("SHELL       /system/bin/sh\n")
        append("CWD         ${cwd.absolutePath}\n")
    }

    private fun appList(q: String): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) }
            .sortedBy { it.first.lowercase(Locale.US) }
            .take(80)
            .joinToString("\n") { "${it.first.padEnd(24).take(24)} ${it.second}" }
            .ifBlank { "No matching apps." }
    }

    private fun openApp(query: String) {
        var launch = packageManager.getLaunchIntentForPackage(query)
        if (launch == null) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val hit = packageManager.queryIntentActivities(intent, 0).firstOrNull {
                it.loadLabel(packageManager).toString().equals(query, true) || it.loadLabel(packageManager).toString().contains(query, true)
            }
            if (hit != null) launch = packageManager.getLaunchIntentForPackage(hit.activityInfo.packageName)
        }
        if (launch != null) { startActivity(launch); append("Launched.\n") } else append("No matching launchable app.\n")
    }

    private fun prompt() {
        append("blackline:${shortPath(cwd)}$ ")
        input.requestFocus()
    }

    private fun shortPath(file: File): String = when {
        file.absolutePath == "/sdcard" -> "~storage"
        file.absolutePath.startsWith(filesDir.absolutePath) -> "~" + file.absolutePath.removePrefix(filesDir.absolutePath)
        else -> file.absolutePath
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun historyMove(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, history.size)
        input.setText(if (historyIndex == history.size) "" else history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun shortcut(title: String, action: () -> Unit): TextView = TextView(this).apply {
        text = title; gravity = Gravity.CENTER; textSize = 8.5f; typeface = Typeface.MONOSPACE; setTextColor(cyan)
        background = rounded(Color.rgb(13, 17, 20), 10f, Color.rgb(34, 49, 52)); setOnClickListener { action() }
    }

    private fun historyButton(title: String): TextView = TextView(this).apply {
        text = title; gravity = Gravity.CENTER; textSize = 20f; setTextColor(cyan); setOnClickListener { }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(50)).apply { leftMargin = dp(5) }
    }

    private fun historyButton(title: String, action: () -> Unit): TextView = historyButton(title).apply { setOnClickListener { action() } }

    private fun rounded(fill: Int, radius: Float, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); setStroke(dp(1), stroke) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
