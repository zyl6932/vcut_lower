package com.github.zyl6932.vcutlower.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

/**
 * 常驻的 su 会话。
 *
 * 首页每 3 秒刷新一次状态，每次都 fork 一个 su 太浪费；这里只开一个 su 进程，命令按顺序写进
 * 它的 stdin，每条命令后面跟一个随机标记行，读到标记就算这条命令结束（标记里带退出码）。
 * stderr 并进 stdout（2>&1），所以输出就是用户在终端里会看到的样子。
 *
 * KernelSU 默认不弹授权框：没在管理器「超级用户」里放行时，su 会直接退出，
 * 表现为 [run] 返回 null —— 界面据此提示去管理器授权。
 */
object RootShell {

    class Result(val code: Int, val out: String) {
        val ok get() = code == 0
        val lines get() = out.lines().filter { it.isNotEmpty() }
    }

    private val lock = Mutex()
    private var process: Process? = null
    private var stdin: OutputStreamWriter? = null
    private var stdout: BufferedReader? = null

    /** 最近一次是否拿到了 root；null = 还没试过 */
    @Volatile
    var granted: Boolean? = null
        private set

    private fun open(): Boolean {
        process?.let { if (it.isAlive) return true }
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            process = p
            stdin = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            stdout = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    private fun close() {
        runCatching { process?.destroy() }
        process = null
        stdin = null
        stdout = null
    }

    /** 执行一段 shell；拿不到 root 时返回 null。 */
    suspend fun run(script: String): Result? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!open()) {
                granted = false
                return@withLock null
            }
            val marker = "__VCUT_" + UUID.randomUUID().toString().replace("-", "") + "__"
            try {
                val w = stdin!!
                // 用子 shell 包起来：脚本里的 exit 只结束子 shell，不会把常驻会话关掉
                w.write("( $script\n) 2>&1; echo \"$marker \$?\"\n")
                w.flush()
                val sb = StringBuilder()
                val r = stdout!!
                while (true) {
                    val line = r.readLine() ?: run {
                        // su 被拒绝或会话意外结束
                        close()
                        granted = false
                        return@withLock null
                    }
                    if (line.startsWith(marker)) {
                        granted = true
                        val code = line.substringAfter(' ').trim().toIntOrNull() ?: -1
                        return@withLock Result(code, sb.toString().trimEnd())
                    }
                    sb.append(line).append('\n')
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } catch (_: Exception) {
                close()
                granted = false
                null
            }
        }
    }

    /**
     * 单独开一个 su 进程跑耗时命令（生成诊断包要 1~2 分钟），不占常驻会话，
     * 首页的定时刷新照常进行。onLine 逐行回调输出，用于显示进度。
     */
    suspend fun runDetached(script: String, onLine: (String) -> Unit = {}): Result? =
        withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                val sb = StringBuilder()
                p.inputStream.bufferedReader().forEachLine {
                    sb.append(it).append('\n')
                    onLine(it)
                }
                Result(p.waitFor(), sb.toString().trimEnd())
            } catch (_: Exception) {
                null
            }
        }

    /** 把任意文本安全地写进文件（经 base64，避免引号/换行转义问题）。 */
    suspend fun writeFile(path: String, content: String): Boolean {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        return run("echo '$b64' | base64 -d > '$path'")?.ok == true
    }
}
