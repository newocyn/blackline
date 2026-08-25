package online.pcguys.blackline

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLACKLINE-owned Kali runtime.
 *
 * No Termux app/runtime is required. Kali's official NetHunter rootfs is
 * downloaded directly and executed under a bundled PRoot binary.
 */
class KaliManager(private val context: Context) {
    private val baseDir = File(context.noBackupFilesDir, "blackline-kali")
    val rootfsDir = File(baseDir, "rootfs")
    private val workDir = File(baseDir, "work")
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var activeProcess: Process? = null

    companion object {
        private const val BASE_URL = "https://kali.download/nethunter-images/current/rootfs"
        private val VALID_VARIANTS = setOf("nano", "minimal", "full")
    }

    fun isSupportedArchitecture(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    fun isInstalled(): Boolean = File(rootfsDir, "bin/bash").exists() && File(baseDir, ".installed").exists()
    fun prootFile(): File = File(context.applicationInfo.nativeLibraryDir, "libblackline_proot.so")
    fun isProotAvailable(): Boolean = prootFile().exists()

    fun status(): String = buildString {
        append("BLACKLINE KALI RUNTIME\n")
        append("ARCH        ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("PROOT       ${if (isProotAvailable()) "READY" else "MISSING"}\n")
        append("ROOTFS      ${if (isInstalled()) "INSTALLED" else "NOT INSTALLED"}\n")
        if (isInstalled()) {
            append("PATH        ${rootfsDir.absolutePath}\n")
            append("SIZE        ${formatBytes(directorySize(rootfsDir))}\n")
            val marker = File(baseDir, ".installed").takeIf { it.exists() }?.readText()?.trim().orEmpty()
            if (marker.isNotBlank()) append("SOURCE      $marker\n")
        }
        append("ROOT MODE   PRoot fake-root (Android itself remains unrooted)\n")
    }

    fun cancel() {
        cancelRequested.set(true)
        runCatching { activeConnection?.disconnect() }
        runCatching { activeProcess?.destroy() }
    }

    fun install(requestedVariant: String = "minimal", emit: (String) -> Unit): Boolean {
        cancelRequested.set(false)
        val variant = requestedVariant.lowercase().takeIf { it in VALID_VARIANTS } ?: "minimal"
        if (!isSupportedArchitecture()) {
            emit("Kali runtime currently supports ARM64 devices only.\n")
            return false
        }
        if (!isProotAvailable()) {
            emit("Bundled PRoot runtime is missing from this BLACKLINE build.\n")
            return false
        }

        baseDir.mkdirs()
        workDir.mkdirs()
        val imageName = "kali-nethunter-rootfs-$variant-arm64.tar.xz"
        val image = File(workDir, imageName)
        val sumFile = File(workDir, "$imageName.sha512sum")
        val imageUrl = "$BASE_URL/$imageName"
        val sumUrl = "$BASE_URL/$imageName.sha512sum"

        return try {
            emit("BLACKLINE // KALI INSTALL\n")
            emit("SOURCE      official Kali NetHunter rootfs\n")
            emit("VARIANT     ${variant.uppercase()}\n")
            emit("Downloading checksum…\n")
            download(sumUrl, sumFile, emit, quiet = true)
            ensureNotCancelled()
            emit("Downloading Kali rootfs…\n")
            download(imageUrl, image, emit, quiet = false)
            ensureNotCancelled()

            val expected = sumFile.readText().trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
            val actual = sha512(image)
            emit("SHA-512     ${if (actual == expected) "VERIFIED" else "FAILED"}\n")
            if (expected.isBlank() || actual != expected) throw IllegalStateException("Kali rootfs checksum verification failed")

            val tempRoot = File(baseDir, "rootfs.new")
            tempRoot.deleteRecursively()
            tempRoot.mkdirs()
            emit("Extracting root filesystem… this can take several minutes.\n")
            extractTarXz(image, tempRoot, emit)
            ensureNotCancelled()
            configureRootfs(tempRoot)

            rootfsDir.deleteRecursively()
            if (!tempRoot.renameTo(rootfsDir)) throw IllegalStateException("Could not activate extracted Kali rootfs")
            File(baseDir, ".installed").writeText("$imageName @ $BASE_URL")
            image.delete()
            sumFile.delete()

            emit("KALI INSTALLED\n")
            emit("Run 'kali doctor' to verify the runtime or tap ANDROID at the top to switch into KALI mode.\n")
            true
        } catch (e: InterruptedException) {
            emit("Kali installation cancelled.\n")
            false
        } catch (e: Exception) {
            emit("Kali install failed: ${e.message ?: e.javaClass.simpleName}\n")
            false
        } finally {
            activeConnection = null
        }
    }

    fun remove(emit: (String) -> Unit): Boolean = try {
        cancel()
        rootfsDir.deleteRecursively()
        File(baseDir, ".installed").delete()
        workDir.deleteRecursively()
        emit("Kali rootfs removed.\n")
        true
    } catch (e: Exception) {
        emit("Unable to remove Kali: ${e.message}\n")
        false
    }

    fun doctor(): String {
        if (!isInstalled()) return "Kali is not installed. Run: kali install minimal\n"
        if (!isProotAvailable()) return "PRoot runtime missing.\n"
        return try {
            val command = baseProotCommand("/root") + listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "TERM=dumb",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/bin/bash",
                "-lc",
                "printf 'KALI_OK\\n'; id; uname -m; grep -E '^(PRETTY_NAME|VERSION)=' /etc/os-release 2>/dev/null; command -v apt; printf 'DNS '; getent hosts kali.org 2>/dev/null | head -1 || true"
            )
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            activeProcess = p
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            activeProcess = null
            buildString {
                append(out)
                append("DOCTOR EXIT $code\n")
                append(if (out.contains("KALI_OK")) "BLACKLINE/KALI bridge is executing the Kali userspace.\n" else "Kali shell did not initialize correctly.\n")
            }
        } catch (e: Exception) {
            "Kali doctor failed: ${e.message}\n"
        }
    }

    fun baseProotCommand(guestCwd: String = "/root"): List<String> {
        val root = rootfsDir.absolutePath
        val command = mutableListOf(
            prootFile().absolutePath,
            "-0",
            "-r", root,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "/sdcard:/sdcard",
            "-b", "/storage:/storage",
            "-w", guestCwd
        )
        return command
    }

    fun newInteractiveProcess(): Process {
        check(isInstalled()) { "Kali is not installed" }
        check(isProotAvailable()) { "PRoot runtime missing" }
        val command = baseProotCommand("/root") + listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/bin/bash",
            "TERM=dumb",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash",
            "--noprofile",
            "--norc",
            "-i"
        )
        return ProcessBuilder(command).redirectErrorStream(true).start().also { activeProcess = it }
    }

    private fun download(urlString: String, destination: File, emit: (String) -> Unit, quiet: Boolean) {
        destination.parentFile?.mkdirs()
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BLACKLINE/0.7 Android")
        }
        activeConnection = connection
        connection.connect()
        if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode} for $urlString")
        val total = connection.contentLengthLong
        var done = 0L
        var nextReport = 4L * 1024L * 1024L
        BufferedInputStream(connection.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    ensureNotCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (!quiet && done >= nextReport) {
                        val pct = if (total > 0) ((done * 100) / total).coerceIn(0, 100) else -1
                        emit(if (pct >= 0) "DOWNLOAD    $pct%  ${formatBytes(done)} / ${formatBytes(total)}\n" else "DOWNLOAD    ${formatBytes(done)}\n")
                        nextReport = done + 4L * 1024L * 1024L
                    }
                }
            }
        }
        connection.disconnect()
        activeConnection = null
        if (!quiet) emit("DOWNLOAD    COMPLETE  ${formatBytes(done)}\n")
    }

    private fun extractTarXz(archive: File, destination: File, emit: (String) -> Unit) {
        val hardLinks = mutableListOf<Pair<File, String>>()
        var entries = 0
        FileInputStream(archive).use { fileInput ->
            BufferedInputStream(fileInput, 256 * 1024).use { buffered ->
                XZCompressorInputStream(buffered).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            ensureNotCancelled()
                            val entry = tar.nextTarEntry ?: break
                            val out = safeOutputFile(destination, entry)
                            when {
                                entry.isDirectory -> out.mkdirs()
                                entry.isSymbolicLink -> {
                                    out.parentFile?.mkdirs()
                                    runCatching { out.delete() }
                                    Os.symlink(entry.linkName, out.absolutePath)
                                }
                                entry.isLink -> {
                                    out.parentFile?.mkdirs()
                                    hardLinks += out to entry.linkName
                                }
                                entry.isFile -> {
                                    out.parentFile?.mkdirs()
                                    BufferedOutputStream(FileOutputStream(out), 128 * 1024).use { output ->
                                        tar.copyTo(output, 128 * 1024)
                                    }
                                    runCatching { Os.chmod(out.absolutePath, entry.mode and 0x1ff) }
                                }
                            }
                            entries++
                            if (entries % 750 == 0) emit("EXTRACT     $entries filesystem entries\n")
                        }
                    }
                }
            }
        }
        hardLinks.forEach { (link, targetName) ->
            val target = safeOutputFile(destination, TarArchiveEntry(targetName))
            if (target.exists()) runCatching { Os.link(target.absolutePath, link.absolutePath) }
        }
        emit("EXTRACT     COMPLETE  $entries entries\n")
    }

    private fun safeOutputFile(root: File, entry: TarArchiveEntry): File {
        val clean = entry.name.removePrefix("/").removePrefix("./")
        val out = File(root, clean)
        val rootPath = root.canonicalPath + File.separator
        val outPath = out.canonicalPath
        if (outPath != root.canonicalPath && !outPath.startsWith(rootPath)) {
            throw SecurityException("Blocked rootfs path traversal: ${entry.name}")
        }
        return out
    }

    private fun configureRootfs(root: File) {
        File(root, "root").mkdirs()
        val etc = File(root, "etc").apply { mkdirs() }
        val resolv = File(etc, "resolv.conf")
        runCatching { if (resolv.exists() || resolv.isFile) resolv.delete() }
        runCatching { resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        val hosts = File(etc, "hosts")
        runCatching { if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n127.0.1.1 blackline\n") }
        File(root, "root/.blackline").writeText("BLACKLINE Kali runtime\n")
    }

    private fun sha512(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureNotCancelled() {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
    }

    private fun directorySize(file: File): Long = if (!file.exists()) 0L else if (file.isFile) file.length() else file.listFiles()?.sumOf { directorySize(it) } ?: 0L

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024.0; i++ }
        return "%.1f %s".format(v, units[i])
    }
}
