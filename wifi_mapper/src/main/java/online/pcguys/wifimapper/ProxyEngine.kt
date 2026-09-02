package online.pcguys.wifimapper

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class CaptureEvent(
    val id: Long,
    val time: Long,
    val protocol: String,
    val method: String,
    val host: String,
    val port: Int,
    val path: String,
    val detail: String
)

class ProxyEngine(private val listener: (CaptureEvent) -> Unit) {
    private val pool = Executors.newCachedThreadPool()
    private val ids = AtomicLong(1)
    private var server: ServerSocket? = null
    @Volatile var running = false
        private set
    @Volatile var listenPort = 8888
        private set
    val events = CopyOnWriteArrayList<CaptureEvent>()

    fun start(port: Int = 8888): Result<Unit> {
        if (running) return Result.success(Unit)
        return try {
            listenPort = port.coerceIn(1024, 65535)
            server = ServerSocket(listenPort, 50)
            running = true
            pool.execute {
                while (running) {
                    try {
                        val client = server?.accept() ?: break
                        pool.execute { handle(client) }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    fun clear() { events.clear() }

    fun selfTest(): String {
        if (!running) return "Proxy is not running"
        return try {
            val s = Socket("127.0.0.1", listenPort)
            s.soTimeout = 4000
            val out = BufferedOutputStream(s.getOutputStream())
            out.write(("HEAD http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val input = BufferedInputStream(s.getInputStream())
            val line = readLine(input) ?: "No response"
            s.close()
            "Self-test response: " + line
        } catch (e: Exception) {
            "Self-test failed: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun emit(protocol: String, method: String, host: String, port: Int, path: String, detail: String) {
        val item = CaptureEvent(
            ids.getAndIncrement(),
            System.currentTimeMillis(),
            protocol,
            method,
            host,
            port,
            path,
            detail
        )
        events.add(0, item)
        while (events.size > 500) events.removeAt(events.lastIndex)
        listener(item)
    }

    private fun handle(client: Socket) {
        client.soTimeout = 25000
        try {
            val input = BufferedInputStream(client.getInputStream())
            val first = readLine(input) ?: return
            val parts = first.split(" ", limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val version = parts.getOrElse(2) { "HTTP/1.1" }
            val headers = linkedMapOf<String,String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }

            if (method == "CONNECT") {
                val hp = target.split(":", limit = 2)
                val host = hp[0]
                val port = hp.getOrNull(1)?.toIntOrNull() ?: 443
                emit("TLS", "CONNECT", host, port, "", "Encrypted HTTPS tunnel")
                val remote = Socket()
                remote.connect(java.net.InetSocketAddress(host, port), 5000)
                val clientOut = BufferedOutputStream(client.getOutputStream())
                clientOut.write(("HTTP/1.1 200 Connection Established\r\nProxy-Agent: PCG-Network-Tool\r\n\r\n").toByteArray())
                clientOut.flush()
                tunnel(client, remote, input)
                return
            }

            val uri = try { URI(target) } catch (_: Exception) { null }
            val hostHeader = headers.entries.firstOrNull { it.key.equals("Host", true) }?.value
                ?: uri?.host ?: return
            val hp = hostHeader.split(":", limit = 2)
            val host = hp[0]
            val port = hp.getOrNull(1)?.toIntOrNull() ?: (uri?.port?.takeIf { it > 0 } ?: 80)
            val path = if (uri != null && uri.isAbsolute) {
                buildString {
                    append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
                    if (!uri.rawQuery.isNullOrBlank()) append("?").append(uri.rawQuery)
                }
            } else target

            val visible = headers.entries.filterNot {
                it.key.equals("Authorization", true) ||
                it.key.equals("Proxy-Authorization", true) ||
                it.key.equals("Cookie", true)
            }.take(8).joinToString(" • ") { it.key + ": " + it.value.take(80) }

            emit("HTTP", method, host, port, path, visible.ifBlank { "HTTP request" })

            val remote = Socket()
            remote.connect(java.net.InetSocketAddress(host, port), 5000)
            remote.soTimeout = 25000
            val remoteOut = BufferedOutputStream(remote.getOutputStream())
            remoteOut.write((method + " " + path + " " + version + "\r\n").toByteArray())
            headers.forEach { (k,v) ->
                if (!k.equals("Proxy-Authorization", true) && !k.equals("Proxy-Connection", true)) {
                    remoteOut.write((k + ": " + v + "\r\n").toByteArray())
                }
            }
            remoteOut.write("Connection: close\r\n\r\n".toByteArray())
            remoteOut.flush()
            tunnel(client, remote, input)
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun tunnel(client: Socket, remote: Socket, clientInput: BufferedInputStream) {
        val a = pool.submit {
            try {
                val out = BufferedOutputStream(remote.getOutputStream())
                clientInput.copyTo(out, 8192)
                out.flush()
            } catch (_: Exception) {}
            try { remote.shutdownOutput() } catch (_: Exception) {}
        }
        val b = pool.submit {
            try {
                val input = BufferedInputStream(remote.getInputStream())
                val out = BufferedOutputStream(client.getOutputStream())
                input.copyTo(out, 8192)
                out.flush()
            } catch (_: Exception) {}
            try { client.shutdownOutput() } catch (_: Exception) {}
        }
        try { a.get() } catch (_: Exception) {}
        try { b.get() } catch (_: Exception) {}
        try { remote.close() } catch (_: Exception) {}
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16384) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.ISO_8859_1)
            if (b == 10) break
            if (b != 13) bytes.add(b.toByte())
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1)
    }
}
