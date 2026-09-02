package online.pcguys.wifimapper

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class TerminalEngine(private val context: Context) {
    fun run(commandLine: String): String {
        val line = commandLine.trim()
        if (line.isBlank()) return ""
        val parts = line.split(Regex("\\s+"))
        val cmd = parts.first().lowercase()
        val args = parts.drop(1)
        return when (cmd) {
            "help","?" -> help()
            "status" -> status()
            "ip" -> "Local IP: " + (NetworkEngine.localIpv4(context) ?: "unavailable")
            "gateway","gw" -> "Gateway: " + (NetworkEngine.gateway(context) ?: "unavailable")
            "dns" -> {
                if (args.isEmpty()) "DNS: " + NetworkEngine.dnsServers(context).joinToString(", ")
                else {
                    val r = NetworkEngine.resolve(args[0])
                    if (r.isEmpty()) "No records found" else r.joinToString("\n")
                }
            }
            "ping" -> {
                if (args.isEmpty()) "Usage: ping <host>"
                else {
                    val ms = NetworkEngine.ping(args[0], 2)
                    if (ms == null) "No ICMP response from " + args[0] else args[0] + " replied in " + ms + " ms"
                }
            }
            "tcp" -> {
                if (args.size < 2) "Usage: tcp <host> <port>"
                else {
                    val port = args[1].toIntOrNull() ?: return "Invalid port"
                    val ms = NetworkEngine.tcp(args[0], port, 1200)
                    if (ms == null) args[0] + ":" + port + " did not answer" else args[0] + ":" + port + " open / " + ms + " ms"
                }
            }
            "ports" -> {
                if (args.isEmpty()) return "Usage: ports <host> [quick|standard|deep] [start-end]"
                val host = args[0]
                var mode = args.getOrNull(1)?.lowercase() ?: "standard"
                var start: Int? = null
                var end: Int? = null
                val range = args.getOrNull(2) ?: args.getOrNull(1)?.takeIf { it.contains("-") }
                if (range != null && range.contains("-")) {
                    val p = range.split("-", limit = 2)
                    start = p.getOrNull(0)?.toIntOrNull()
                    end = p.getOrNull(1)?.toIntOrNull()
                    mode = "custom"
                }
                val found = NetworkEngine.deepScan(host, mode, start, end, 350)
                if (found.isEmpty()) "No open ports found"
                else found.joinToString("\n") { item ->
                    item.port.toString() + "/tcp  " + item.service +
                        (item.banner?.let { "  " + it } ?: "")
                }
            }
            "http" -> {
                if (args.isEmpty()) "Usage: http <host> [port]"
                else NetworkEngine.httpSummary(args[0], false, args.getOrNull(1)?.toIntOrNull())
            }
            "https" -> {
                if (args.isEmpty()) "Usage: https <host> [port]"
                else NetworkEngine.httpSummary(args[0], true, args.getOrNull(1)?.toIntOrNull())
            }
            "tls" -> {
                if (args.isEmpty()) "Usage: tls <host> [port]"
                else NetworkEngine.tlsSummary(args[0], args.getOrNull(1)?.toIntOrNull() ?: 443) ?: "No TLS details"
            }
            "ssdp","upnp" -> {
                val found = NetworkEngine.ssdpDiscover()
                if (found.isEmpty()) "No SSDP/UPnP responses" else found.joinToString("\\n")
            }
            "scan" -> {
                val found = NetworkEngine.discoverSubnet(context, 180)
                if (found.isEmpty()) "No responding devices found"
                else found.joinToString("\n") {
                    it.ip + "  " + it.role + "  " + it.openPorts.joinToString(",")
                }
            }
            "shell" -> {
                if (args.isEmpty()) "Usage: shell <android-shell-command>"
                else runShell(args.joinToString(" "))
            }
            "clear" -> "__CLEAR__"
            "about" -> "PCG Network Tool Enterprise\nAuthorized network diagnostics and traffic inspection."
            else -> "Unknown command: " + cmd + "\nType help for commands."
        }
    }

    private fun status(): String {
        return buildString {
            append("Local IP: ").append(NetworkEngine.localIpv4(context) ?: "unavailable").append("\n")
            append("Gateway: ").append(NetworkEngine.gateway(context) ?: "unavailable").append("\n")
            append("DNS: ").append(NetworkEngine.dnsServers(context).joinToString(", ")).append("\n")
            append("Android shell: app sandbox / non-root")
        }
    }

    private fun runShell(command: String): String {
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val done = p.waitFor(12, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return "Command timed out after 12 seconds"
            }
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val out = reader.readText().take(30000)
            if (out.isBlank()) "(exit " + p.exitValue() + ")" else out
        } catch (e: Exception) {
            "Shell error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun help(): String {
        return """
PCG NETWORK TERMINAL

status
ip
gateway
dns [host]
ping <host>
tcp <host> <port>
scan
ports <host> [quick|standard|deep]
ports <host> custom <start-end>
http <host> [port]
https <host> [port]
tls <host> [port]
shell <command>
clear
about

shell runs inside the Android app sandbox and does not grant root.
""".trimIndent()
    }
}
