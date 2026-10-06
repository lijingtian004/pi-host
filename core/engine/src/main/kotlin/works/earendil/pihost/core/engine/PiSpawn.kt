package works.earendil.pihost.core.engine

/**
 * pi rpc 子进程的 argv 工厂（方案 §4.4 + P2C 规格书 §4/§12.1 定案）。
 *
 * 落地形状（各 flag 独立 argv 元素，不拼接字符串，避免空格/引号歧义）：
 * ```
 * $libDir/libpi_node.so $runtimeDir/pi/dist/bundle/cli.js \
 *     --mode rpc --session-id <uuid> --no-extensions
 * ```
 *
 * 为什么是 `--session-id` 而不是 `--resume`（实测 pi 源码
 * `packages/coding-agent/src/cli/args.ts:113-141`）：
 * - `--resume, -r` 是**交互式选择器**（不接受值），RPC 模式下无人应答 → 永久挂起，作废；
 * - `--session-id <id>`（args.ts:136）= "Use exact project session ID, creating it if missing"，
 *   是非交互恢复的唯一正解。id 由 **host 生成 UUID**，首次 spawn 也带上（不依赖 get_state 回读），
 *   所以崩溃重启后同 id 续用同一会话文件。
 * - `--no-session` 是「不落盘」，与恢复互斥 → 不加。
 * - `--no-extensions` 关掉 MCP/扩展加载，避免再起一层子进程（§12.1）。
 *
 * 纯 Kotlin、无 Android API：`libDir` 由调用方取 `ctx.applicationInfo.nativeLibraryDir!!`、
 * `runtimeDir` 取 `PathLayout.runtimeDir(ctx).absolutePath` 注入（见 `EngineRuntime`）。
 */
class PiSpawn(private val libDir: String, private val runtimeDir: String) {

    /** node 可执行体：P2B 把 Termux 的 `bin/node` 提升成 `libpi_node.so` 落进 nativeLibraryDir。 */
    val nodeExecutable: String = "$libDir/$NODE_SO"

    /** pi 的 RPC 入口（runtime zip 内路径 `pi/dist/bundle/cli.js`）。 */
    val cliScript: String = "$runtimeDir/$CLI_JS_PATH"

    fun command(sessionId: String): List<String> = listOf(
        nodeExecutable,
        cliScript,
        "--mode", MODE_VALUE,
        "--session-id", sessionId,
        NO_EXTENSIONS_FLAG,
    )

    companion object {
        const val NODE_SO: String = "libpi_node.so"
        const val CLI_JS_PATH: String = "pi/dist/bundle/cli.js"
        const val MODE_FLAG: String = "--mode"
        const val MODE_VALUE: String = "rpc"
        const val SESSION_ID_FLAG: String = "--session-id"
        const val NO_EXTENSIONS_FLAG: String = "--no-extensions"
    }
}
