package online.pcguys.wifimapper

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

data class DeviceRecord(
    val ip: String,
    val hostname: String?,
    val latencyMs: Long?,
    val openPorts: List<Int>,
    val role: String,
    val gateway: Boolean,
    val notes: String = ""
)

data class PortFinding(
    val port: Int,
    val service: String,
    val latencyMs: Long?,
    val banner: String?
)

object NetworkEngine {
    private val quickPorts = listOf(22,53,80,139,443,445,554,631,8080,8443,9100)
    private val standardPorts = listOf(
        20,21,22,23,25,53,67,68,80,110,123,135,137,138,139,143,161,389,443,445,
        465,500,514,515,554,587,631,636,993,995,1433,1521,1723,1883,2049,2375,
        3000,3306,3389,5000,5060,5432,5672,5900,5985,6379,8000,8080,8081,8443,
        8883,8888,9000,9090,9100,9200,27017
    )

    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }?.hostAddress
    }

    @Suppress("DEPRECATION")
    fun gateway(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val value = wifi.dhcpInfo?.gateway ?: return null
        if (value == 0) return null
        return listOf(
            value and 255,
            value shr 8 and 255,
            value shr 16 and 255,
            value shr 24 and 255
        ).joinToString(".")
    }

    fun dnsServers(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return emptyList()
        return cm.getLinkProperties(n)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    }

    fun ping(host: String, timeoutSeconds: Int = 1): Long? {
        return try {
            val start = System.nanoTime()
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSeconds.toString(), host)
                .redirectErrorStream(true).start()
            val done = p.waitFor((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
            if (done && p.exitValue() == 0) (System.nanoTime() - start) / 1_000_000 else null
        } catch (_: Exception) { null }
    }

    fun tcp(host: String, port: Int, timeoutMs: Int = 350): Long? {
        return try {
            val start = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) { null }
    }

    fun discoverSubnet(
        context: Context,
        timeoutMs: Int = 180,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int, DeviceRecord?) -> Unit = { _, _, _ -> }
    ): List<DeviceRecord> {
        val local = localIpv4(context) ?: return emptyList()
        val prefix = local.substringBeforeLast(".")
        val gw = gateway(context)
        val out = ConcurrentLinkedQueue<DeviceRecord>()
        val pool = Executors.newFixedThreadPool(36)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        for (i in 1..254) {
            if (cancelled()) break
            val ip = prefix + "." + i
            if (ip == local) {
                counter.incrementAndGet()
                continue
            }
            pool.submit {
                if (cancelled()) return@submit
                val open = mutableListOf<Int>()
                var best: Long? = null
                for (port in quickPorts) {
                    val m = tcp(ip, port, timeoutMs)
                    if (m != null) {
                        open.add(port)
                        if (best == null || m < best!!) best = m
                    }
                }
                val pingMs = if (open.isEmpty()) ping(ip, 1) else best
                val reachable = pingMs != null || open.isNotEmpty() || ip == gw
                var found: DeviceRecord? = null
                if (reachable) {
                    val hostname = try {
                        InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip }
                    } catch (_: Exception) { null }
                    found = DeviceRecord(
                        ip = ip,
                        hostname = hostname,
                        latencyMs = pingMs,
                        openPorts = open.sorted(),
                        role = role(open, ip == gw),
                        gateway = ip == gw
                    )
                    out.add(found)
                }
                val done = counter.incrementAndGet()
                progress(done, 254, found)
            }
        }
        pool.shutdown()
        pool.awaitTermination(45, TimeUnit.SECONDS)
        return out.distinctBy { it.ip }.sortedWith(
            compareByDescending<DeviceRecord> { it.gateway }
                .thenBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 0 }
        )
    }

    fun deepScan(
        host: String,
        mode: String,
        customStart: Int? = null,
        customEnd: Int? = null,
        timeoutMs: Int = 300,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int, PortFinding?) -> Unit = { _, _, _ -> }
    ): List<PortFinding> {
        val ports = when (mode.lowercase()) {
            "quick" -> quickPorts
            "deep" -> (1..1024).toList()
            "custom" -> {
                val a = (customStart ?: 1).coerceIn(1, 65535)
                val b = (customEnd ?: a).coerceIn(a, 65535)
                (a..b).take(4096)
            }
            else -> standardPorts
        }
        val found = ConcurrentLinkedQueue<PortFinding>()
        val pool = Executors.newFixedThreadPool(32)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        ports.forEach { port ->
            if (cancelled()) return@forEach
            pool.submit {
                if (cancelled()) return@submit
                val ms = tcp(host, port, timeoutMs)
                var item: PortFinding? = null
                if (ms != null) {
                    val banner = safeBanner(host, port)
                    item = PortFinding(port, serviceName(port), ms, banner)
                    found.add(item)
                }
                val done = counter.incrementAndGet()
                progress(done, ports.size, item)
            }
        }
        pool.shutdown()
        pool.awaitTermination(90, TimeUnit.SECONDS)
        return found.sortedBy { it.port }
    }

    fun safeBanner(host: String, port: Int): String? {
        if (port == 443 || port == 8443) return tlsSummary(host, port)
        if (port in listOf(80,8080,8000,8081,8888,9000,9090)) {
            return try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 700)
                socket.soTimeout = 900
                val out = socket.getOutputStream()
                out.write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val lines = mutableListOf<String>()
                repeat(8) {
                    val line = reader.readLine() ?: return@repeat
                    if (line.isBlank()) return@repeat
                    if (line.startsWith("HTTP/") || line.startsWith("Server:", true) ||
                        line.startsWith("Location:", true) || line.startsWith("Content-Type:", true)) {
                        lines.add(line.take(180))
                    }
                }
                socket.close()
                lines.joinToString(" | ").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
        }
        return null
    }

    fun tlsSummary(host: String, port: Int = 443): String? {
        return try {
            val socket = SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket
            socket.connect(InetSocketAddress(host, port), 1200)
            socket.soTimeout = 1500
            socket.startHandshake()
            val session = socket.session
            val cert = session.peerCertificates.firstOrNull() as? X509Certificate
            val result = buildString {
                append(session.protocol)
                append(" / ")
                append(session.cipherSuite)
                if (cert != null) {
                    append(" | ")
                    append(cert.subjectX500Principal.name.take(160))
                    append(" | expires ")
                    append(cert.notAfter.toString())
                }
            }
            socket.close()
            result
        } catch (e: Exception) { "TLS probe failed: " + (e.message ?: e.javaClass.simpleName) }
    }

    fun httpSummary(host: String, https: Boolean = false, port: Int? = null): String {
        return try {
            val scheme = if (https) "https" else "http"
            val defaultPort = if (https) 443 else 80
            val p = port ?: defaultPort
            val suffix = if (p == defaultPort) "" else ":" + p
            val conn = URL(scheme + "://" + host + suffix + "/").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connect()
            val lines = mutableListOf<String>()
            lines.add("HTTP " + conn.responseCode + " " + conn.responseMessage)
            listOf("Server","Location","Content-Type","Content-Length").forEach { key ->
                conn.getHeaderField(key)?.let { lines.add(key + ": " + it.take(200)) }
            }
            conn.disconnect()
            lines.joinToString("\n")
        } catch (e: Exception) { "HTTP probe failed: " + (e.message ?: e.javaClass.simpleName) }
    }

    fun resolve(host: String): List<String> {
        return try { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } } catch (_: Exception) { emptyList() }
    }


    fun ssdpDiscover(timeoutMs: Int = 2200): List<String> {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 500
            val payload = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: ssdp:all\r\n\r\n"
            ).toByteArray()
            val target = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(payload, payload.size, target, 1900))
            val end = System.currentTimeMillis() + timeoutMs
            val found = linkedSetOf<String>()
            while (System.currentTimeMillis() < end) {
                try {
                    val buf = ByteArray(4096)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val body = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val server = body.lines().firstOrNull { it.startsWith("SERVER:", true) }?.substringAfter(":")?.trim()
                    val location = body.lines().firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(":")?.trim()
                    val st = body.lines().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(":")?.trim()
                    found.add(packet.address.hostAddress + " | " + (server ?: st ?: "UPnP device") + (location?.let { " | " + it } ?: ""))
                } catch (_: java.net.SocketTimeoutException) {}
            }
            socket.close()
            found.toList()
        } catch (e: Exception) {
            listOf("SSDP error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun riskSummary(ports: List<Int>): String {
        val flags = mutableListOf<String>()
        if (23 in ports) flags.add("Telnet exposed")
        if (21 in ports || 20 in ports) flags.add("FTP exposed")
        if (80 in ports && 443 !in ports) flags.add("HTTP-only management")
        if (445 in ports) flags.add("SMB exposed")
        if (3389 in ports) flags.add("RDP exposed")
        if (5900 in ports) flags.add("VNC exposed")
        if (6379 in ports) flags.add("Redis exposed")
        if (27017 in ports) flags.add("MongoDB exposed")
        if (2375 in ports) flags.add("Docker API exposed")
        return if (flags.isEmpty()) "No obvious legacy/admin exposure in scanned ports" else flags.joinToString(" • ")
    }

    fun role(ports: List<Int>, gateway: Boolean): String = when {
        gateway -> "Router / Gateway"
        9100 in ports || 631 in ports || 515 in ports -> "Printer"
        554 in ports -> "Camera / Media"
        445 in ports || 2049 in ports -> "Server / NAS"
        3389 in ports -> "Windows workstation/server"
        1883 in ports || 8883 in ports -> "IoT / MQTT"
        22 in ports -> "Server / Appliance"
        ports.any { it in listOf(80,443,8080,8443) } -> "Web-managed device"
        else -> "Network device"
    }

    fun serviceName(port: Int): String {
        return mapOf(
            20 to "FTP data",21 to "FTP",22 to "SSH",23 to "Telnet",25 to "SMTP",53 to "DNS",
            67 to "DHCP",68 to "DHCP",80 to "HTTP",110 to "POP3",123 to "NTP",135 to "RPC",
            137 to "NetBIOS",138 to "NetBIOS",139 to "NetBIOS",143 to "IMAP",161 to "SNMP",
            389 to "LDAP",443 to "HTTPS",445 to "SMB",465 to "SMTPS",500 to "IKE",514 to "Syslog",
            515 to "LPD",554 to "RTSP",587 to "SMTP submission",631 to "IPP",636 to "LDAPS",
            993 to "IMAPS",995 to "POP3S",1433 to "MSSQL",1521 to "Oracle",1723 to "PPTP",
            1883 to "MQTT",2049 to "NFS",2375 to "Docker",3000 to "Web dev",3306 to "MySQL",
            3389 to "RDP",5000 to "Web/API",5060 to "SIP",5432 to "PostgreSQL",5672 to "AMQP",
            5900 to "VNC",5985 to "WinRM",6379 to "Redis",8000 to "HTTP alt",8080 to "HTTP alt",
            8081 to "HTTP alt",8443 to "HTTPS alt",8883 to "MQTTS",8888 to "Proxy / HTTP alt",
            9000 to "App service",9090 to "Web service",9100 to "JetDirect",9200 to "Elasticsearch",
            27017 to "MongoDB"
        )[port] ?: "TCP"
    }
}
