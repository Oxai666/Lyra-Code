package com.yukisoffd.lyracode.termux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Termux 里的持久 shell 会话。
 *
 * 为什么需要这个：现有的 run_command 每次都是一次独立的 RUN_COMMAND，
 * Termux 起一个新 bash、跑完、退出。`cd /project` 之后的目录、`export`
 * 的环境变量、`source venv/bin/activate` 的虚拟环境，下一次调用全没了。
 * 模型被迫把每条命令写成 `cd /project && source venv/bin/activate && ...`，
 * 又长又容易错。
 *
 * 这里用 tmux 起一个后台会话，命令通过 `send-keys` 打进去，输出用
 * `capture-pane` 捞出来。同一个会话里 cd / export / 别名 / 后台进程
 * 全都保留，跟用户自己在 Termux 里敲一样。
 *
 * 会话名的前缀是 `ly_`，跟 Ultra 的 `rk_` 区分开，两个 App 同时装在
 * 一台机器上也不会互相 kill 对方的会话。
 */
class TmuxSessionManager(
    private val executor: TermuxExecutor,
) {

    data class Outcome(
        val ok: Boolean,
        val text: String,
    )

    /**
     * 确保 tmux 可用。返回 null 表示可用，否则是一条给模型看的说明。
     *
     * 自动装：第一次用的时候 `pkg install -y tmux`。装在 Termux 里是
     * 几秒钟的事，比让用户自己去敲一遍友好。装不上就如实说，别假装能用。
     */
    private suspend fun ensureTmux(): String? = withContext(Dispatchers.IO) {
        val check = runCatching { executor.runRaw("command -v tmux", timeoutSeconds = 20) }
            .getOrNull()
        if (check != null && check.exitCode == 0 && check.stdout.isNotBlank()) return@withContext null

        val install = runCatching {
            executor.runRaw("pkg install -y tmux", timeoutSeconds = 180)
        }.getOrNull()
        if (install == null) {
            return@withContext "tmux 不可用，自动安装也没有响应。请在 Termux 里手动执行 pkg install tmux。"
        }
        if (install.exitCode != 0) {
            val reason = install.stderr.trim().ifBlank { install.stdout.trim() }.ifBlank { "无输出" }
            return@withContext "tmux 安装失败（exit ${install.exitCode}）：$reason\n" +
                "请在 Termux 里手动执行 pkg install tmux。"
        }
        val recheck = runCatching { executor.runRaw("command -v tmux", timeoutSeconds = 20) }
            .getOrNull()
        if (recheck == null || recheck.exitCode != 0 || recheck.stdout.isBlank()) {
            return@withContext "tmux 安装后仍然找不到命令，可能是 Termux 的 PATH 有问题。"
        }
        null
    }

    /** 起一个新会话。 */
    suspend fun start(nameHint: String?, cols: Int = 120, rows: Int = 40): Outcome =
        withContext(Dispatchers.IO) {
            ensureTmux()?.let { return@withContext Outcome(false, it) }
            val session = buildSessionName(nameHint)
            val stdout = runTmux(
                "new-session -d -s ${q(session)} -x ${cols.coerceIn(40, 300)} -y ${rows.coerceIn(10, 200)}"
            )
            if (stdout == null) {
                Outcome(false, "无法创建 tmux 会话 $session。")
            } else {
                Outcome(
                    true,
                    "session: $session\n" +
                        "说明：这个会话会一直活着。用 termux_session action=send 往里打命令，" +
                        "用 action=capture 看屏幕。cd / export / 别名 / 后台进程都会保留。",
                )
            }
        }

    /**
     * 往会话里打一条命令。
     *
     * 默认补一个回车（[pressEnter]），因为绝大多数场景是"执行这条命令"。
     * 要发控制键（Ctrl-C、方向键）就用 [keys]。
     */
    suspend fun send(
        session: String,
        text: String?,
        keys: List<String>? = null,
        pressEnter: Boolean = true,
    ): Outcome = withContext(Dispatchers.IO) {
        ensureTmux()?.let { return@withContext Outcome(false, it) }
        if (!sessionExists(session)) {
            return@withContext Outcome(false, sessionMissing(session))
        }
        // keys 优先：那是显式控制键，不做字面发送
        if (!keys.isNullOrEmpty()) {
            val argv = buildString {
                append("send-keys -t ").append(q(session))
                keys.forEach { append(' ').append(q(it)) }
            }
            val out = runTmux(argv) ?: return@withContext Outcome(false, "send-keys 失败。")
            return@withContext Outcome(true, "已发送按键：${keys.joinToString(" ")}\n$out".trim())
        }

        if (text.isNullOrEmpty()) {
            return@withContext Outcome(false, "text 和 keys 至少给一个。")
        }

        // -l 是字面发送：不把 "+" "Enter" 之类当键名解释。
        // -- 结束选项解析，防止命令内容以 - 开头时被当成参数。
        val sent = runTmux("send-keys -t ${q(session)} -l -- ${q(text)}")
            ?: return@withContext Outcome(false, "send-keys 失败。")

        if (pressEnter) {
            runTmux("send-keys -t ${q(session)} Enter")
                ?: return@withContext Outcome(false, "回车发送失败。")
        }
        Outcome(true, "已发送。${if (pressEnter) "已回车。" else ""}\n$sent".trim())
    }

    /**
     * 读屏幕内容。
     *
     * -S -N 表示从 N 行之前的滚动缓冲开始，最后一行是最新的。
     * 注意 tmux 的 -S 是"从历史里往前取"，所以给负数。
     */
    suspend fun capture(session: String, lines: Int = 200): Outcome =
        withContext(Dispatchers.IO) {
            ensureTmux()?.let { return@withContext Outcome(false, it) }
            if (!sessionExists(session)) {
                return@withContext Outcome(false, sessionMissing(session))
            }
            val out = runTmux("capture-pane -t ${q(session)} -p -S -${lines.coerceIn(1, 5000)}")
                ?: return@withContext Outcome(false, "capture-pane 失败。")
            Outcome(true, out.ifBlank { "(屏幕是空的)" })
        }

    /** 杀掉会话。 */
    suspend fun kill(session: String): Outcome = withContext(Dispatchers.IO) {
        ensureTmux()?.let { return@withContext Outcome(false, it) }
        if (!sessionExists(session)) {
            return@withContext Outcome(false, sessionMissing(session))
        }
        val out = runTmux("kill-session -t ${q(session)}")
        if (out == null) Outcome(false, "kill-session 失败。") else Outcome(true, "已关闭 $session。")
    }

    /** 列出本 App 创建的会话。 */
    suspend fun list(): Outcome = withContext(Dispatchers.IO) {
        ensureTmux()?.let { return@withContext Outcome(false, it) }
        val out = runTmux(
            "list-sessions -F '#{session_name}\\t#{session_created}\\t#{session_activity}'"
        ) ?: return@withContext Outcome(false, "list-sessions 失败。")
        val mine = out.lineSequence()
            .filter { it.isNotBlank() }
            .filter { it.startsWith("ly_") }
            .toList()
        if (mine.isEmpty()) {
            Outcome(true, "当前没有 Lyra 创建的会话。用 action=start 起一个。")
        } else {
            Outcome(
                true,
                buildString {
                    appendLine("会话列表（名字 / 创建时间戳 / 最后活动时间戳）：")
                    mine.forEach { appendLine("  $it") }
                }.trim(),
            )
        }
    }

    // ---- 内部 ----

    /**
     * 跑一条 tmux 命令，返回 stdout；失败返回 null。
     *
     * 外层套 `bash -lc`：tmux 在 Termux 的 usr/bin 下，非登录 shell 的
     * PATH 不一定有它。同时把 stderr 合进来——tmux 的报错（no such
     * session 之类）走 stderr，丢了会让上层无法判断失败原因。
     */
    private suspend fun runTmux(args: String): String? {
        val result = runCatching { executor.runRaw("tmux $args 2>&1", timeoutSeconds = 30) }
            .getOrNull() ?: return null
        return if (result.exitCode == 0) result.stdout else {
            // 非 0 退出：把 stdout（已经合了 stderr）返回给上层，让它自己判断。
            // 但 list/capture 这类，exit != 0 通常是真失败，返回 null 更清楚。
            null
        }
    }

    private suspend fun sessionExists(session: String): Boolean {
        val result = runCatching {
            executor.runRaw("tmux has-session -t ${q(session)} 2>/dev/null", timeoutSeconds = 15)
        }.getOrNull() ?: return false
        return result.exitCode == 0
    }

    private fun sessionMissing(session: String) =
        "会话 $session 不存在。先用 termux_session action=list 看看有哪些，" +
            "或者 action=start 新起一个。"

    /** `ly_<hint>_<8位>`，hint 只保留字母数字下划线。 */
    private fun buildSessionName(hint: String?): String {
        val cleaned = hint?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (cleaned.isNullOrBlank()) "ly_$id" else "ly_${cleaned}_$id"
    }

    /** POSIX 单引号转义。 */
    private fun q(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
