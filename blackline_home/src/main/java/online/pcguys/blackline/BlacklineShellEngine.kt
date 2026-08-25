package online.pcguys.blackline

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BLACKLINE's native command engine.
 *
 * BLACKLINE owns dispatch, cwd, environment, aliases, Android bridges,
 * process execution and Kali lifecycle. Standard shell syntax is executed by
 * Android's /system/bin/sh; Kali is an independent PRoot backend.
 */
class BlacklineShellEngine(private val context: Context) {
    data class Result(
        val output: String = "",
        val clearScreen: Boolean = false,
        val promptAgain: Boolean = true
    )

    private val prefs = context.getSharedPreferences("blackline_shell", Context.MODE_PRIVATE)
    private val aliases = ConcurrentHashMap<String, String>()
    private val customEnv = ConcurrentHashMap<String, String>()
    val kali = KaliManager(context)

    @Volatile private var activeProcess: Process? = null
    @Volatile private var cancelled = false

    var cwd: File = File("/sdcard")
        private set

    init {
        loadJsonMap("aliases", aliases)
        loadJsonMap("environment", customEnv)
    }

    fun cancelActive() {
        cancelled = true
        kali.cancel()
        runCatching { activeProcess?.destroy() }
        runCatching { activeProcess?.destroyForcibly() }
        activeProcess = null
    }

    fun execute(raw: String): Result = execute(raw) { }

    fun execute(raw: String, emit: (String) -> Unit): Result {
        cancelled = false
        val entered = raw.trim()
        if (entered.isBlank()) return Result()
        val cmd = expandAlias(entered)

        return when {
            cmd == "clear" -> Result(clearScreen = true)
            cmd == "help" -> Result(helpText())
            cmd == "pwd" -> Result(cwd.absolutePath + "\n")
            cmd == "device" -> Result(deviceInfo())
            cmd == "battery" -> Result(batteryInfo())
            cmd == "network" -> Result(networkInfo())
            cmd == "storage" -> Result(storageInfo())
            cmd == "env" -> Result(environmentText())
            cmd.startsWith("export ") -> setEnvironment(cmd.removePrefix("export ").trim())
            cmd.startsWith("unset ") -> unsetEnvironment(cmd.removePrefix("unset ").trim())
            cmd == "alias" -> Result(aliasText())
            cmd.startsWith("alias ") -> setAlias(cmd.removePrefix("alias ").trim())
            cmd.startsWith("unalias ") -> unsetAlias(cmd.removePrefix("unalias ").trim())
            cmd == "apps" -> Result(appList("") + "\n")
            cmd.startsWith("apps ") -> Result(appList(cmd.removePrefix("apps ").trim()) + "\n")
            cmd.startsWith("open ") -> Result(openApp(cmd.removePrefix("open ").trim()))
            cmd.startsWith("url ") -> Result(openUrl(cmd.removePrefix("url ").trim()))
            cmd == "clipboard" || cmd == "clipboard get" -> Result(getClipboard())
            cmd.startsWith("clipboard set ") -> Result(setClipboard(cmd.removePrefix("clipboard set ")))
            cmd.startsWith("share ") -> Result(shareText(cmd.removePrefix("share ")))
            cmd == "props" -> runAndroidShell("getprop", emit)
            cmd.startsWith("props ") -> {
                val q = shellQuote(cmd.removePrefix("props ").trim())
                runAndroidShell("getprop | grep -i -- $q", emit)
            }
            cmd.startsWith("http get ") -> httpGet(cmd.removePrefix("http get ").trim(), emit)
            cmd == "home" -> {
                context.startActivity(Intent(context, BlacklineHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Result()
            }
            cmd == "cd" -> changeDirectory("/sdcard")
            cmd.startsWith("cd ") -> changeDirectory(cmd.removePrefix("cd ").trim())
            cmd == "kali" || cmd == "kali status" -> Result(kali.status())
            cmd.startsWith("kali install") -> {
                val variant = cmd.removePrefix("kali install").trim().ifBlank { "minimal" }
                kali.install(variant, emit)
                Result()
            }
            cmd == "kali doctor" -> Result(kali.doctor())
            cmd == "kali remove" -> {
                kali.remove(emit)
                Result()
            }
            else -> runAndroidShell(cmd, emit)
        }
    }

    private fun changeDirectory(targetRaw: String): Result {
        val expanded = if (targetRaw.startsWith("~/")) File(context.filesDir, targetRaw.removePrefix("~/")).absolutePath else targetRaw
        val target = when {
            expanded == "~" -> context.filesDir
            expanded.startsWith("/") -> File(expanded)
            else -> File(cwd, expanded)
        }
        return if (target.exists() && target.isDirectory) {
            cwd = runCatching { target.canonicalFile }.getOrDefault(target)
            Result()
        } else {
            Result("cd: no such directory: $targetRaw\n")
        }
    }

    private fun runAndroidShell(command: String, emit: (String) -> Unit): Result {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(cwd.takeIf { it.exists() } ?: context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["BLACKLINE"] = "1"
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["PWD"] = cwd.absolutePath
                    customEnv.forEach { (key, value) -> environment()[key] = value }
                }
                .start()
            activeProcess = process
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (cancelled) return@forEach
                    emit(line + "\n")
                }
            }
            val exit = process.waitFor()
            activeProcess = null
            if (cancelled) Result("^C\n") else if (exit != 0) Result("exit $exit\n") else Result()
        } catch (e: Exception) {
            activeProcess = null
            Result("${e.javaClass.simpleName}: ${e.message}\n")
        }
    }

    private fun httpGet(value: String, emit: (String) -> Unit): Result {
        val rawUrl = value.trim()
        if (rawUrl.isBlank()) return Result("usage: http get <https://url>\n")
        val url = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "BLACKLINE/0.7")
            }
            connection.connect()
            emit("HTTP ${connection.responseCode} ${connection.responseMessage}\n")
            connection.headerFields.filterKeys { it != null }.forEach { (k, values) -> emit("$k: ${values.joinToString()}\n") }
            emit("\n")
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            stream?.bufferedReader()?.useLines { lines -> lines.take(400).forEach { emit(it + "\n") } }
            connection.disconnect()
            Result()
        } catch (e: Exception) {
            Result("HTTP error: ${e.message}\n")
        }
    }

    private fun setEnvironment(value: String): Result {
        val idx = value.indexOf('=')
        if (idx <= 0) return Result("usage: export NAME=value\n")
        val key = value.substring(0, idx).trim()
        val data = stripQuotes(value.substring(idx + 1))
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return Result("invalid environment variable name\n")
        customEnv[key] = data
        persistJsonMap("environment", customEnv)
        return Result()
    }

    private fun unsetEnvironment(keyRaw: String): Result {
        customEnv.remove(keyRaw.trim())
        persistJsonMap("environment", customEnv)
        return Result()
    }

    private fun environmentText(): String = buildString {
        append("BLACKLINE=1\nHOME=${context.filesDir.absolutePath}\nPWD=${cwd.absolutePath}\n")
        customEnv.toSortedMap().forEach { (k, v) -> append("$k=$v\n") }
    }

    private fun setAlias(value: String): Result {
        val idx = value.indexOf('=')
        if (idx <= 0) return Result("usage: alias name='command'\n")
        val name = value.substring(0, idx).trim()
        if (!name.matches(Regex("[A-Za-z0-9_.-]+"))) return Result("invalid alias name\n")
        aliases[name] = stripQuotes(value.substring(idx + 1).trim())
        persistJsonMap("aliases", aliases)
        return Result()
    }

    private fun unsetAlias(name: String): Result {
        aliases.remove(name.trim())
        persistJsonMap("aliases", aliases)
        return Result()
    }

    private fun aliasText(): String = if (aliases.isEmpty()) "No aliases.\n" else aliases.toSortedMap().entries.joinToString("\n", postfix = "\n") { "alias ${it.key}='${it.value}'" }

    private fun expandAlias(command: String): String {
        val first = command.substringBefore(' ')
        val replacement = aliases[first] ?: return command
        val rest = command.removePrefix(first).trimStart()
        return if (rest.isBlank()) replacement else "$replacement $rest"
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
        append("KALI        ${if (kali.isInstalled()) "READY" else "NOT INSTALLED"}\n")
    }

    private fun batteryInfo(): String = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            else -> "UNKNOWN"
        }
        "LEVEL       $level%\nSTATE       $state\nTEMP        %.1f C\nVOLTAGE     %d mV\n".format(temp, voltage)
    }.getOrElse { "Battery information unavailable.\n" }

    private fun networkInfo(): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "ONLINE"
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        "TYPE        $type\nINTERNET    ${if (validated) "VALIDATED" else "UNVERIFIED"}\n"
    }.getOrElse { "Network information unavailable.\n" }

    private fun storageInfo(): String = buildString {
        listOf("APP" to context.filesDir, "INTERNAL" to File("/sdcard")).forEach { (label, file) ->
            runCatching {
                val s = StatFs(file.absolutePath)
                append("${label.padEnd(10)} ${formatBytes(s.availableBytes)} free / ${formatBytes(s.totalBytes)} total  ${file.absolutePath}\n")
            }
        }
    }

    private fun appList(query: String): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0)
            .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }
            .sortedBy { it.first.lowercase(Locale.US) }
            .take(150)
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
        } else "No matching launchable app.\n"
    }

    private fun openUrl(value: String): String = try {
        val target = if (value.contains("://")) value else "https://$value"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Opened $target\n"
    } catch (e: Exception) { "Unable to open URL: ${e.message}\n" }

    private fun getClipboard(): String = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (value.isBlank()) "Clipboard is empty or unavailable.\n" else value + "\n"
    }.getOrElse { "Clipboard unavailable.\n" }

    private fun setClipboard(value: String): String = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BLACKLINE", value))
        "Copied to clipboard.\n"
    }.getOrElse { "Clipboard unavailable.\n" }

    private fun shareText(value: String): String = try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share from BLACKLINE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "Share sheet opened.\n"
    } catch (e: Exception) { "Unable to share: ${e.message}\n" }

    fun shortPath(): String = when {
        cwd.absolutePath == "/sdcard" -> "~storage"
        cwd.absolutePath.startsWith(context.filesDir.absolutePath) -> "~" + cwd.absolutePath.removePrefix(context.filesDir.absolutePath)
        else -> cwd.absolutePath
    }

    private fun loadJsonMap(key: String, target: ConcurrentHashMap<String, String>) {
        runCatching {
            val obj = JSONObject(prefs.getString(key, "{}") ?: "{}")
            obj.keys().forEach { name -> target[name] = obj.optString(name) }
        }
    }

    private fun persistJsonMap(key: String, source: Map<String, String>) {
        val obj = JSONObject()
        source.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun stripQuotes(value: String): String {
        if (value.length >= 2 && ((value.first() == '\'' && value.last() == '\'') || (value.first() == '"' && value.last() == '"'))) return value.substring(1, value.length - 1)
        return value
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var n = value.toDouble() / 1024.0
        var i = 0
        while (n >= 1024 && i < units.lastIndex) { n /= 1024.0; i++ }
        return "%.1f %s".format(n, units[i])
    }

    private fun helpText(): String = """
BLACKLINE NATIVE SHELL
  help                         command index
  clear                        clear terminal transcript
  cd <path> / pwd              directory navigation
  env / export NAME=value      persistent BLACKLINE environment
  unset NAME                   remove environment value
  alias name='command'         persistent command alias
  alias / unalias name         list or remove aliases

ANDROID BRIDGE
  device                       device/kernel/runtime information
  battery                      battery state, temperature, voltage
  network                      active Android network state
  storage                      storage capacity/free space
  props [query]                Android system properties
  apps [query]                 launchable applications
  open <package|name>          launch Android application
  url <address>                open web/deep link
  clipboard [get]              read clipboard when Android permits
  clipboard set <text>         copy text
  share <text>                 Android share sheet
  http get <url>               simple HTTPS/HTTP request
  home                         return to BLACKLINE Home

KALI / PROOT
  kali status                  runtime/rootfs status
  kali install [minimal|full]  download official verified Kali rootfs
  kali doctor                  execute Kali self-test
  kali remove                  remove Kali rootfs
  After install, tap the ANDROID/KALI mode control in the terminal header.

ANDROID SHELL
  Anything else is streamed through /system/bin/sh. Pipes, redirects,
  variables and command chaining are supported by the platform shell.

BLACKLINE does not require or invoke the Termux application/runtime.

""".trimStart()
}
