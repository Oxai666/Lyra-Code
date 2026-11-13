package com.yukisoffd.lyracode.system

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.yukisoffd.lyracode.data.AppSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SystemCommandResult(
    val ok: Boolean,
    val mode: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val message: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("schema", "lyra_system_command_v1")
        .put("ok", ok)
        .put("mode", mode)
        .put("exit_code", exitCode)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("message", message)
        .toString()

    companion object {
        fun fromJson(value: String): SystemCommandResult {
            val json = JSONObject(value)
            return SystemCommandResult(
                ok = json.optBoolean("ok"),
                mode = json.optString("mode", "shell"),
                exitCode = json.optInt("exit_code", -1),
                stdout = json.optString("stdout"),
                stderr = json.optString("stderr"),
                message = json.optString("message"),
            )
        }
    }
}

class SystemCommandExecutor(
    context: Context,
    private val settings: AppSettings,
) {
    private val appContext = context.applicationContext
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext, ShizukuShellUserService::class.java),
    )
        .daemon(false)
        .processNameSuffix("lyra_shell")
        .version(1)
    private val serviceLock = Any()
    @Volatile
    private var shellService: ISystemShellService? = null
    private var pendingService: CompletableDeferred<ISystemShellService>? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ISystemShellService.Stub.asInterface(binder)
            shellService = service
            synchronized(serviceLock) {
                pendingService?.complete(service)
                pendingService = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShellPermission(): Boolean {
        return isShizukuRunning() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }

    fun shouldShowShellTool(): Boolean = settings.requestShellAccess

    fun shouldShowRootTool(): Boolean = settings.requestRootAccess

    suspend fun probeRoot(): SystemCommandResult = withContext(Dispatchers.IO) {
        runRootProcess("id -u", 12)
    }

    suspend fun executeShell(command: String, timeoutSeconds: Int, beforeDispatch: suspend () -> Unit = {}): SystemCommandResult = withContext(Dispatchers.IO) {
        if (!settings.requestShellAccess) {
            return@withContext unavailable("shell", "Shell 权限开关已关闭。")
        }
        if (!isShizukuRunning()) {
            return@withContext unavailable(
                "shell",
                "Shizuku 服务未运行。请先在 Shizuku 中通过无线调试或电脑 ADB 启动服务。",
            )
        }
        if (!hasShellPermission()) {
            return@withContext unavailable("shell", "尚未授予 Lyra Code 的 Shizuku 权限。")
        }
        runCatching {
            val service = requireShellService()
            currentCoroutineContext().ensureActive()
            beforeDispatch()
            SystemCommandResult.fromJson(service.execute(command, timeoutSeconds.coerceIn(3, 600)))
        }.getOrElse {
            shellService = null
            unavailable("shell", "Shell 命令启动失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    suspend fun executeRoot(
        command: String,
        timeoutSeconds: Int,
        allowShellFallback: Boolean = true,
        beforeDispatch: suspend () -> Unit = {},
    ): SystemCommandResult = withContext(Dispatchers.IO) {
        if (!settings.requestRootAccess) {
            return@withContext unavailable("root", "Root 权限开关已关闭。")
        }
        val probe = runRootProcess("id -u", 12)
        if (probe.ok && probe.stdout.trim().lineSequence().lastOrNull() == "0") {
            currentCoroutineContext().ensureActive()
            try { beforeDispatch() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { return@withContext unavailable("root", "操作取消：${error.message.orEmpty()}") }
            currentCoroutineContext().ensureActive()
            return@withContext runRootProcess(command, timeoutSeconds)
        }
        if (allowShellFallback && settings.requestShellAccess) {
            val fallback = executeShell(command, timeoutSeconds, beforeDispatch)
            return@withContext fallback.copy(
                message = "Root 不可用，已按设置回退到 Shizuku Shell。${fallback.message}",
            )
        }
        unavailable(
            "root",
            "未获得 Root 权限，且没有可用的 Shell 回退通道。${probe.stderr.ifBlank { probe.message }}",
        )
    }

    private fun runRootProcess(command: String, timeoutSeconds: Int): SystemCommandResult {
        val quoted = shellQuote(command)
        // ⚠ 把配置里的 su 前缀解析成真实绝对路径再拼。
        //
        // 只靠 PATH 的话，KernelSU 的 /data/adb/ksu/bin/su 和 Magisk 的
        // /data/adb/magisk/su 在很多 ROM 上都不在里面——结果是用户明明装了
        // root，App 却一直报「未获得 Root 权限」。
        val template = resolveSuBinary(settings.customSuCommand)
        val invocation = if ("{command}" in template) {
            template.replace("{command}", quoted)
        } else {
            "$template $quoted"
        }
        return runCatching {
            collectProcess(
                ProcessBuilder("/system/bin/sh", "-c", invocation).start(),
                "root",
                timeoutSeconds,
            )
        }.getOrElse {
            unavailable("root", "Root 命令启动失败: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun collectProcess(process: Process, mode: String, timeoutSeconds: Int): SystemCommandResult {
        val readers = Executors.newFixedThreadPool(2)
        // 用 readCapped 而不是 readText：后者没有上限，
        // 一条 `cat /dev/urandom` 就够把 App 撑死（系统静默 kill，无报错）。
        val stdoutFuture = readers.submit(Callable { readCapped(process.inputStream) })
        val stderrFuture = readers.submit(Callable { readCapped(process.errorStream) })
        val timeout = timeoutSeconds.coerceIn(3, 600)
        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val stdout = runCatching { stdoutFuture.get(3, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(3, TimeUnit.SECONDS) }.getOrDefault("")
        readers.shutdownNow()
        if (!finished) {
            return SystemCommandResult(
                ok = false,
                mode = mode,
                exitCode = -1,
                stdout = stdout,
                stderr = stderr,
                message = "命令执行超过 ${timeout} 秒，已终止。",
            )
        }
        val exitCode = process.exitValue()
        return SystemCommandResult(
            ok = exitCode == 0,
            mode = mode,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            message = if (exitCode == 0) "" else "命令退出码为 $exitCode。",
        )
    }

    private fun unavailable(mode: String, message: String): SystemCommandResult {
        return SystemCommandResult(false, mode, -1, "", "", message)
    }

    private suspend fun requireShellService(): ISystemShellService {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val deferred = synchronized(serviceLock) {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let {
                return it
            }
            pendingService ?: CompletableDeferred<ISystemShellService>().also {
                pendingService = it
                Shizuku.bindUserService(serviceArgs, serviceConnection)
            }
        }
        return withTimeout(10_000L) { deferred.await() }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    /**
     * su 的常见落点。
     *
     * 注意第一个是裸名 `su`，走 PATH 查找；后面几个是绝对路径兜底，
     * 覆盖 KernelSU / Magisk / 老 ROM 的 /system/xbin。
     */
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/debug_ramdisk/su",
    )

    /**
     * 把配置里的 su 前缀（如 `su -c`）换成一个真实存在的绝对路径。
     *
     * 找不到就原样返回配置——让 su 自己去报错，比这里编一个不存在
     * 的路径让它报 "No such file" 更有用。
     */
    private fun resolveSuBinary(configured: String): String {
        val parts = configured.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return "su"
        val head = parts[0]
        val rest = parts.drop(1)
        val resolved = when {
            head.contains('/') ->
                File(head).takeIf { it.exists() && it.canExecute() }?.absolutePath
            head == "su" ->
                suCandidates.firstNotNullOfOrNull { candidate ->
                    if (candidate.contains('/')) {
                        File(candidate).takeIf { it.exists() && it.canExecute() }?.absolutePath
                    } else {
                        whichOnPath(candidate)
                    }
                }
            else -> whichOnPath(head)
        } ?: head
        return (listOf(resolved) + rest).joinToString(" ")
    }

    private fun whichOnPath(name: String): String? {
        val path = System.getenv("PATH").orEmpty()
        if (path.isBlank()) return null
        return path.split(':').asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    }

    /**
     * 带上限地读完一个流。
     *
     * 原来直接 readText()：`cat /dev/urandom`、巨型 dmesg、或者一个写不完的
     * 构建日志都会把内存吃光，表现是 App 被系统杀掉、没有任何报错。
     * 上限之外的内容丢弃，只保留前 [limit] 个字符。
     */
    private fun readCapped(stream: java.io.InputStream, limit: Int = MAX_OUTPUT_CHARS): String {
        val sb = StringBuilder()
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (sb.length >= limit) continue
                    sb.append(buffer, 0, minOf(read, limit - sb.length))
                }
            }
        } catch (_: Throwable) {
            // 进程被强杀时读端会抛，能拿到多少算多少
        }
        return sb.toString()
    }

    private companion object {
        /** 单次命令输出上限 256K 字符。 */
        const val MAX_OUTPUT_CHARS = 256 * 1024
    }
}
