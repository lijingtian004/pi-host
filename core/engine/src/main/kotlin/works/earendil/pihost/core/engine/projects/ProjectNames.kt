package works.earendil.pihost.core.engine.projects

import java.util.Locale

/**
 * 项目名的形状规则与反查（P3C §3.1）。纯函数，不碰文件系统、不碰 Android API。
 *
 * ## 为什么非得有一个校验器
 * `PathLayout.projectDir(ctx, name)` 的实现是 `File(projectsDir(ctx), name)`（`PathLayout.kt:38`）——
 * **一个字节的校验都没有**。P3C 之前这不是问题，因为 `name` 只有 `EngineModule.DEFAULT_PROJECT` 一个取值，
 * 是编译期常量；P3C 起 `name` 来自用户在「新建项目」卡里敲的文本，于是
 * `"../../home"`、`"/data/data/com.termux"`、`"a/b"` 这些输入会直接拼进路径并送去 `mkdirs`，
 * 宿主就在沙盒之外建目录。`PathLayout` 在冻结区（§2 红线：`core/environment` 零 diff）不能加校验，
 * 所以闸门只能建在调用侧——就是这里。
 *
 * ## 三道门，不是一道
 * 1. UI：`enabled = draft.isNotBlank()`（`SettingsScreen.kt:302` 的同一条先例）—— 只为手感，不是安全；
 * 2. **[validate]**（本类）：`ProjectStore.create` 的第一件事，非法直接拒绝，**不动磁盘**；
 * 3. **[sanitizeOrFallback]**（本类）：从 DataStore 读盘时兜底 —— 磁盘上的值可能被别的版本写过，
 *    读口不许抛，脏值一律回落 [DEFAULT_NAME]。
 *
 * 第三道门是必须的：`active_project` 落进 `pihost.preferences_pb` 之后就是**外部输入**，
 * 「宿主自己写的所以一定合法」这个前提在降级/手改文件/测试播种下都不成立。
 *
 * ## 字符集为什么是「白名单」而不是「黑名单」
 * 黑名单（只排 `../` 和 `/`）挡不住 `:`（某些文件系统当备用数据流）、换行、前后空格、
 * 以及 Windows 保留名一类；这些在 Android 上多数不致命，但项目名还会进 UI 文案与日志，
 * 一个换行就能伪造一行日志。白名单一次性把面收住：字母数字（`Char.isLetterOrDigit()` 按 Unicode 类别，
 * **中文项目名因此合法**）加 `-_.` 三个分隔符。
 *
 * 前导 `.` 单独立一条：`.git` / `.tmp` / `.installed.json` 这类以点开头的名字会与
 * `AgentConfigFiles.TMP_MARKER`（`.tmp`，`:42`）、`PathLayout.INSTALLED_MARKER_NAME`（`.installed.json`）
 * 的清扫逻辑撞名——项目名允许以点开头，就得让每一处「按后缀/前缀扫目录」的代码多记一条例外。
 *
 * ## 48
 * `ext4` 单段目录名上限 255 字节，一个汉字 3 字节 → 理论上 85 个汉字；但这个名字要在顶栏和会话行里
 * 与分支名、时间、模型 chip 并排显示，48 字符（≈16 个汉字）是「还读得清」的那条线。
 * 超了是拒绝而不是截断：截断会让两个不同的输入落到同一个目录名上。
 *
 * ## 大小写折叠重名也要拒
 * `Foo` 与 `foo` 在 `ext4` 上是两个目录，在用户眼里是同一个项目。宿主一旦允许共存，
 * [projectNameOfCwd] 的反查就会不确定（它得决定归哪一个），而会话列表按项目分组时
 * 会出现「同一个项目两个条目」。手机键盘自动首字母大写正好会造出这种输入，所以这条比文件系统严。
 */
object ProjectNames {

    /** 唯一的项目名真相；`EngineModule.DEFAULT_PROJECT` 与 DataStore 缺省值都指到这里。 */
    const val DEFAULT_NAME: String = "default"

    const val MAX_LENGTH: Int = 48

    /** 白名单里除字母数字之外额外放行的三个分隔符（`.` 允许但不许打头，见 [validate]）。 */
    const val ALLOWED_PUNCTUATION: String = "-_."

    /**
     * 校验一个用户输入的项目名；合法返回 **trim 之后**的名字，非法返回 null。
     *
     * 返回裁剪后的值而不是原样回声：调用方要拿这个值去建目录、落 DataStore、显示给用户，
     * 三处必须是同一个字符串，否则 `" foo "` 建出的目录与之后按 `"foo"` 去找的目录不是同一个。
     *
     * 顺序即优先级，先粗后细：null/空白 → 长度 → 前导点 → 连续点 → 字符集。
     */
    fun validate(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (name.isEmpty()) return null
        if (name.length > MAX_LENGTH) return null
        // `.` 与 `..` 都在这一条里被挡掉：它们的第一个字符就是点。
        if (name.startsWith('.')) return null
        if (name.contains("..")) return null
        if (name.any { !it.isLetterOrDigit() && !ALLOWED_PUNCTUATION.contains(it) }) return null
        return name
    }

    /**
     * 读盘路径专用：合法就用，非法（含 null / 空白 / 超长 / 穿越）一律回落 [DEFAULT_NAME]。
     *
     * 与 [validate] 的区别只在用途：这条给「已经落盘的值」兜底，绝不能抛，也**不返回 null**——
     * 调用方拿它直接拼 `File`，返回 null 就等于把 NPE 留给 UI 线程。
     */
    fun sanitizeOrFallback(raw: String?): String = validate(raw) ?: DEFAULT_NAME

    /**
     * 从一个会话的 cwd 反查它属于哪个项目；认不出（非本项目树、嵌套过深、输入为空）返回 null。
     *
     * 为什么需要它而不是直接读项目列表：pi 的会话文件头里记的是**绝对 cwd**
     * （`SessionScanner.kt:22-39` 的 `ScannedSession.cwd`），而 P3C 的顶栏分支 chip 与审计 cwd
     * 要跟着**会话自己的**目录走，不是跟着「活动项目」走 —— `switch_session` 只传 `sessionPath`
     * （`rpc-mode.ts:603`），pi 会按会话记录的 cwd 重建服务，所以跨项目续用时两者确实会不一致。
     *
     * 只做一层：宿主从不建嵌套项目（`projects/<name>/` 就是叶子），出现 `projects/a/b`
     * 说明那个会话来自别处，返回 null 让调用方走「未知项目」分支，比猜一个 `a` 更诚实。
     *
     * **两侧都先过 [foldDataAlias]**，原因见那个函数的注释——不加这一步，本函数在真机上
     * 对任何会话都会返回 null（P3C §8.9 附）。
     */
    fun projectNameOfCwd(projectsRootPath: String?, cwd: String?): String? {
        val root = projectsRootPath?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val target = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val foldedRoot = foldDataAlias(root)
        val foldedTarget = foldDataAlias(target)
        if (!foldedTarget.startsWith("$foldedRoot/")) return null
        val rest = foldedTarget.substring(foldedRoot.length + 1)
        if (rest.isEmpty() || rest.contains('/')) return null
        return validate(rest)
    }

    /**
     * 把 Android data 目录的「两张脸」折成同一个键：`/data/user/<n>/<pkg>/…` → `/data/data/<pkg>/…`。
     *
     * 这是真机逼出来的一条，不是理论洁癖。设备上实测（REPORT-P3C §8.9 附）：
     * ```
     * readlink -f /data/data/<pkg>/files/projects/3   → /data/data/<pkg>/files/projects/3
     * readlink -f /data/user/0/<pkg>/files/projects/3 → /data/user/0/<pkg>/files/projects/3
     * 两条路径的 st_dev:st_ino 相同（65097:1388717）
     * ```
     * 也就是说它们是**同一棵树的两张脸（bind），不是符号链接**——所以
     * [java.io.File.getCanonicalPath] **统一不了它们**（canonical 只解符号链接）。
     *
     * 而两侧恰好各用一张脸：
     * - pi 记在会话头里的 cwd 是 `/data/data/…`（node 的 `process.cwd()` 原样）；
     * - 宿主经 `Context.filesDir` 拿到的是 `/data/user/0/…`。
     *
     * 于是「拿宿主的根去前缀匹配 pi 记的 cwd」在真机上**永远不相等**，
     * [projectNameOfCwd] 会对每一条真实会话返回 null —— 单测里两边都写同一串字面量，
     * 所以全绿也照不出来（P3C §8.9 附那条跨项目告知就是这么失效的）。
     */
    fun foldDataAlias(path: String): String {
        val match = USER_DIR_PREFIX.matchAt(path, 0) ?: return path
        val tail = path.substring(match.range.last + 1)
        return if (tail.isEmpty()) path else "$DATA_DATA_PREFIX$tail"
    }

    /**
     * 大小写折叠重名的判据。显式钉 [Locale.ROOT]：土耳其语环境下 `"I".lowercase()` 得到 `ı`（不带点的 i），
     * 于是 `Istanbul` 与 `istanbul` 会被判成不同项目——折叠规则属于格式纪律，不跟设备语言走。
     */
    fun foldKey(name: String): String = name.lowercase(Locale.ROOT)

    // 本对象自己就是一个 object，所以这两个是普通成员而不是 companion。
    // 名字不加前缀：它们只在 foldDataAlias 里用，而 foldDataAlias 是本类唯一的公开出口。

    /** `/data/user/<数字>/` 前缀（Android 的多用户 data 目录写法）。 */
    private val USER_DIR_PREFIX: Regex = Regex("/data/user/\\d+/")

    /** 统一后的目标前缀。选 `/data/data` 而不是 `/data/user/0`：它是 pi 侧那一张脸，也是人类读日志时更眼熟的一张。 */
    private const val DATA_DATA_PREFIX: String = "/data/data/"
}
