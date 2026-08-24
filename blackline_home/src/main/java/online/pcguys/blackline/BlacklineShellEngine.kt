package online.pcguys.blackline

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * BLACKLINE's native command engine.
 *
 * This does not use Termux. It owns command dispatch, working-directory state,
 * history-facing execution semantics and Android app/device bridges itself,
 * while delegating standard shell syntax to Android's built-in /system/bin/sh.
 */
class BlacklineShellEngine(private val context: Context) {
    data class Result(
        val output: String = "",
        val clearScreen: Boolean = false,
        val promptAgain: Boolean = true
    )

    var cwd: File = File("/sdcard")
        private set

    fun execute(raw: String): Result {
        val cmd = raw.trim()
        if (cmd.isBlank()) return Result()

        return when {
            cmd == "clear" -> Result(clearScreen = true)
            cmd == "help" -> Result(helpText())
            cmd == "pwd" -> Result(cwd.absolutePath + "\n")
            cmd == "device" -> Result(deviceInfo())
            cmd == "apps" -> Result(appList("") + "\n")
            cmd.startsWith("apps ") -> Result(appList(cmd.removePrefix("apps ").trim()) + "\n")
            cmd.startsWith("open ") -> Result(openApp(cmd.removePrefix("open ").trim()))
            cmd == "home" -> {
                context.startActivity(Intent(context, BlacklineHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Result()
            }
            cmd == "cd" -> changeDirectory("/sdcard")
            cmd.startsWith("cd ") -> changeDirectory(cmd.removePrefix("cd ").trim())
            cmd == "kali" -> Result("Kali/PRoot backend is not installed yet. BLACKLINE Shell itself is native and independent of Termux.\n")
            else -> runAndroidShell(cmd)
        }
    }

    private fun changeDirectory(targetRaw: String): Result {
        val target = when {
            targetRaw == "~" -> context.filesDir
            targetRaw.startsWith("/") -> File(targetRaw)
            else -> File(cwd, targetRaw)
        }
        return if (target.exists() && target.isDirectory) {
            cwd = runCatching { target.canonicalFile }.getOrDefault(target)
            Result(cwd.absolutePath + "\n")
        } else {
            Result("cd: no such directory: $targetRaw\n")
        }
    }

    private fun runAndroidShell(command: String): Result {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwd.takeIf { it.exists() } ?: context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["BLACKLINE"] = "1"
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["PWD"] = cwd.absolutePath
                }
                .start()

            val text = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            val out = buildString {
                if (text.isNotBlank()) append(text.trimEnd()).append('\n')
                if (exit != 0 && text.isBlank()) append("exit $exit\n")
            }
            Result(out)
        } catch (e: Exception) {
            Result("${e.javaClass.simpleName}: ${e.message}\n")
        }
    }

    private fun deviceInfo(): String = buildString {
        append("BLACKLINE SHELL ENGINE\n")
        append("DEVICE      ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("ANDROID     ${Build.VERSION.RELEASE} // API ${Build.VERSION.SDK_INT}\n")
        append("ARCH        ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("KERNEL      ")
        append(runCatching { ProcessBuilder("uname", "-r").start().inputStream.bufferedReader().readText().trim() }.getOrDefault("unknown"))
        append("\nPACKAGE     ${context.packageName}\n")
        append("ENGINE      BLACKLINE native\n")
        append("SHELL       /system/bin/sh\n")
        append("CWD         ${cwd.absolutePath}\n")
    }

    private fun appList(query: String): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
            .sortedBy { it.first.lowercase(Locale.US) }
            .take(100)
            .joinToString("\n") { "${it.first.padEnd(24).take(24)} ${it.second}" }
            .ifBlank { "No matching apps." }
    }

    private fun openApp(query: String): String {
        var launch = context.packageManager.getLaunchIntentForPackage(query)
        if (launch == null) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val hit = context.packageManager.queryIntentActivities(intent, 0).firstOrNull {
                val label = it.loadLabel(context.packageManager).toString()
                label.equals(query, true) || label.contains(query, true)
            }
            if (hit != null) launch = context.packageManager.getLaunchIntentForPackage(hit.activityInfo.packageName)
        }
        return if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            "Launched.\n"
        } else {
            "No matching launchable app.\n"
        }
    }

    fun shortPath(): String = when {
        cwd.absolutePath == "/sdcard" -> "~storage"
        cwd.absolutePath.startsWith(context.filesDir.absolutePath) -> "~" + cwd.absolutePath.removePrefix(context.filesDir.absolutePath)
        else -> cwd.absolutePath
    }

    private fun helpText(): String = """
BLACKLINE NATIVE SHELL
  help                 show this command index
  clear                clear terminal transcript
  device               Android / kernel / engine information
  apps [query]          list launchable Android apps
  open <package|name>   launch an Android app
  home                  return to BLACKLINE Home
  cd / pwd              directory navigation
  kali                  PRoot/Kali backend status

ANDROID COMMANDS
  Standard commands exposed by /system/bin/sh work directly: ls, cat,
  getprop, uname, df, ps, ping, id, mkdir, cp, mv, rm, grep and more.

BLACKLINE does not require or invoke Termux.

""".trimStart()
}
