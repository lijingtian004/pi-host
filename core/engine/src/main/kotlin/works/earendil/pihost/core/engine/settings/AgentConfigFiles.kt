package works.earendil.pihost.core.engine.settings

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * `<agent-dir>` 下三张 json 表的共享底座（P2H §3.1 点名：把 `ModelCredentialStore` 的三个 private 工具
 * 提取成同包共享类，[ModelCredentialStore] 与 [RelayEndpointStore] 共用一份，**不许复制粘贴两份**）。
 *
 * ## 为什么值得单开一个类
 * P2G 把「读-改-写 + 原子替换 + 目录自建」写在 [ModelCredentialStore] 的 private 段里，那时宿主侧只有一张表。
 * P2H 起变成三张：`auth.json`（凭据）、`models.json`（中转端点）、`settings.json`（默认模型钉选），
 * 而 §2 红线要求三条写入通路**全部**原子、全部读-改-写。工具留在其中一个 store 里让另一个去借，
 * 等于让「models.json 的原子性」取决于 auth 类的成员可见性；抽出来之后纪律只有一份实现，
 * 两个 store 各自只负责「决定形状」。
 *
 * ## 三条纪律（逐字继承 P2G §4.1）
 * 1. **读-改-写**：[readObject] 交回的是 `JsonObject` 本身（`Map<String, JsonElement>`），不做类型化 decode，
 *    所以别人的条目与未知字段天然留住；
 * 2. **原子替换**：[writeTextAtomic] 是同一条 tmp+`renameTo` 纪律的唯一实现，
 *    [writeAtomic] 只是它的 JSON 薄封装；任何异常路径都清 tmp；
 * 3. **失败要响**：[ensureDirectory] 建不成目录就抛 [IOException]，宁可一个字节都不写。
 *
 * 本类**不含任何字段名语义**（`providers` / `apiKey` / `defaultProvider` 都留在各自 store 的 companion 里），
 * 所以它不持有任何凭据概念，也不打日志（§2「凭据不进日志」）。
 */
object AgentConfigFiles {

    /** pi 的 agent 目录第一级（`config.ts:605-611` 的 `join(homedir(), ".pi", "agent")`）。 */
    const val PI_DIR_NAME: String = ".pi"

    /** pi 的 agent 目录第二级。 */
    const val AGENT_DIR_NAME: String = "agent"

    const val AUTH_FILE_NAME: String = "auth.json"
    const val MODELS_FILE_NAME: String = "models.json"
    const val SETTINGS_FILE_NAME: String = "settings.json"

    /** tmp 名的构成标记：最终形态是 `<配置文件名>.tmp<nanoTime>`（见 [tmpPrefix] / [writeAtomic]）。 */
    const val TMP_MARKER: String = ".tmp"

    /** 宽松解析：未来 pi 加字段不破旧宿主（与 `core/rpc`、`AndroidRuntimeInfo` 同一套配置）。 */
    val JSON: Json = Json { ignoreUnknownKeys = true }

    /** 空对象单例：`readObject` 的四条回落路径共用，不反复 new。 */
    val EMPTY_OBJECT: JsonObject = buildJsonObject { }

    /** `<homeDir>/.pi/agent`（三段拼法与 pi 的 `join()` 逐段对齐）。 */
    fun agentDir(homeDir: File): File = File(File(homeDir, PI_DIR_NAME), AGENT_DIR_NAME)

    /** `<homeDir>/.pi/agent/<fileName>`。 */
    fun agentFile(homeDir: File, fileName: String): File = File(agentDir(homeDir), fileName)

    /**
     * 某张表自己的 tmp 前缀：三张表同在 `.pi/agent/` 下，前缀各带自己的文件名，
     * 于是单测能断言「本文件的写入没留残骸」而不把邻居文件的 tmp 误算进来。
     */
    fun tmpPrefix(fileName: String): String = fileName + TMP_MARKER

    /** 目录不存在就建；建不成且不是目录 → 抛（宁可不写，也不让内容落到错误位置）。 */
    fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("无法创建目录：${dir.path}")
    }

    /**
     * 读成 [JsonObject]；文件缺失 / 空白 / 损坏 / 顶层不是对象 → 一律给空对象。
     *
     * 「损坏也当空读」是「在坏文件上写 → 覆盖成合法对象」这条语义的基础
     * （P2G §4.1 第 ⑤ 条 / P2H §3.1 第 ⑦ 条）。读失败不抛：设置页进屏就要各列一次，
     * 磁盘抖动时页面该显示「还没配置」而不是崩。
     */
    fun readObject(file: File): JsonObject {
        val text = runCatching { file.readText() }.getOrNull() ?: return EMPTY_OBJECT
        if (text.isBlank()) return EMPTY_OBJECT
        return runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: EMPTY_OBJECT
    }

    /**
     * 同目录 tmp + `renameTo` 原子替换（§2 红线）。
     *
     * `rename(2)` 在同一文件系统上覆盖目标且原子，所以正常路径一步到位；
     * 少数失败（SELinux/跨目录）才退化成「删旧 → 再 rename」两步，两步之间 pi 若 spawn 会读到「无文件」，
     * 那正是 pi 自己 `ensureFileExists` 创建 `{}` 的分支，不是崩溃路径。
     * `finally` 里必清 tmp：[File.renameTo] 成功后 tmp 已不存在，`exists()` 判一下免得对已消失文件刷 false。
     *
     * tmp 名由 [target] 自己的文件名派生（[tmpPrefix]），调用方不需要再传一遍——
     * 少一个参数就等于少一处「前缀与目标文件不匹配」的可能。
     *
     * P3C §3.4 起本方法是 [writeTextAtomic] 的一层薄封装：JSON 表与非 JSON 文件走**同一条**
     * tmp→rename→清残骸 纪律。原来这段 body 直接写在下面，抽出来时逐字未改
     * （`:104-105`/`:489`/`:562-565` 那三条「无 tmp 残留」断言仍归 [writeAtomic] 管）。
     */
    fun writeAtomic(dir: File, target: File, content: JsonObject) =
        writeTextAtomic(dir, target, content.toString())

    /**
     * 纯文本的原子替换（P3C §3.4 为 `$HOME/.gitconfig` 加）。
     *
     * 为什么不再开一个 helper：本类的立身之本就是「原子写纪律只许有一份实现」（见类头）。
     * `.gitconfig` 不是 JSON，但不是**另一种原子性**——tmp 名、rename 覆盖语义、失败路径清残骸
     * 全部与 json 三张表一致，所以它是同一条纪律的第二个载荷类型。
     *
     * tmp 前缀同样按 [target] 的文件名派生：`.gitconfig.tmp<nanoTime>`，
     * 于是 `homeDir` 的单测可以断言「本文件的写入没留残骸」而不把邻居的算进来。
     */
    fun writeTextAtomic(dir: File, target: File, content: String) {
        val tmp = File(dir, tmpPrefix(target.name) + System.nanoTime())
        try {
            tmp.outputStream().use { out -> out.write(content.toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(target)) {
                if (target.exists() && !target.delete()) throw IOException("无法替换 ${target.path}：旧文件删不掉")
                if (!tmp.renameTo(target)) throw IOException("无法原子替换 ${target.path}")
            }
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}
