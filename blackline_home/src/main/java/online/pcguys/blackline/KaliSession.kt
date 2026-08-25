package online.pcguys.blackline

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/** A persistent stdin/stdout Kali shell session launched through BLACKLINE PRoot. */
class KaliSession(private val manager: KaliManager) {
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null
    private val open = AtomicBoolean(false)

    fun isRunning(): Boolean = open.get() && process?.isAlive == true

    fun start(onOutput: (String) -> Unit, onExit: (Int) -> Unit) {
        if (isRunning()) return
        val p = manager.newInteractiveProcess()
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream))
        open.set(true)
        readerThread = Thread {
            val code = try {
                p.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(2048)
                    while (open.get()) {
                        val n = reader.read(chars)
                        if (n < 0) break
                        if (n > 0) onOutput(cleanTerminalText(String(chars, 0, n)))
                    }
                }
                p.waitFor()
            } catch (_: Exception) {
                -1
            } finally {
                open.set(false)
                writer = null
                process = null
            }
            onExit(code)
        }.apply {
            name = "BLACKLINE-Kali-reader"
            isDaemon = true
            start()
        }
        send("export PS1='kali@blackline:\\w# '")
        send("export PROMPT_COMMAND=''")
        send("cd /root")
        send("printf 'BLACKLINE KALI SESSION READY\\n'")
    }

    fun send(line: String) {
        if (!isRunning()) return
        runCatching {
            writer?.apply {
                write(line)
                write("\n")
                flush()
            }
        }
    }

    fun stop() {
        open.set(false)
        runCatching { writer?.apply { write("exit\n"); flush() } }
        runCatching { process?.destroy() }
        runCatching { readerThread?.interrupt() }
        writer = null
        process = null
    }

    private fun cleanTerminalText(value: String): String {
        return value
            .replace(Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)"), "")
            .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace("\r", "")
    }
}
