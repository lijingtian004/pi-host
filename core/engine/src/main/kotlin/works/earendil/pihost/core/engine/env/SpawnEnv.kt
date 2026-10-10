package works.earendil.pihost.core.engine.env

import android.content.Context
import java.io.File
import works.earendil.pihost.core.environment.EnvFactory
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.environment.PathLayout

/**
 * 三条 spawn 通路共用的环境表 = [EnvFactory.build] 的 11 键 **+ git 的两条前缀修正**。
 *
 * **为什么要多这一层，而不是直接注 [EnvFactory]**：`EnvFactory` 在冻结区（§2 红线 2），
 * 而它有一个真机才暴露的洞 —— 它只给 node 递了 `SSL_CERT_FILE`，**git 不读这个键**。
 * 打包的 git 是 Termux 二进制搬过来的，ELF 里编进了编译期前缀
 * （我在机上 `strings` 数出 10 条，其中两条正好是要命的）：
 * ```
 * /data/data/com.termux/files/usr/etc/tls/cert.pem   ← 内置 CA 回退路径
 * /data/data/com.termux/files/usr/bin/sh             ← shell-path
 * ```
 * 那份 cert.pem 在机上是 `-rw------- u0_a404`，宿主是 `u0_a401`，**DAC 就过不去**，
 * 于是 pi 一发 https 就死在 TLS 上下文初始化：
 * `fatal: unable to access 'https://github.com/…/': error adding trust anchors from file:
 * /data/data/com.termux/files/usr/etc/tls/cert.pem | exit=128`（REPORT-P3C §9 ③）。
 *
 * **修正手段已被实验钉死**（同机、同一个 `runtime/bin/git`，走 IP 绕开 DNS 抖动）：
 * ```
 * GIT_SSL_CAINFO=/nope/none.crt  → fatal: … error adding trust anchors from file: /nope/none.crt
 * GIT_SSL_CAINFO=<自带 ca-bundle> → fatal: … SSL: no alternative certificate subject name matches …
 * 不设                            → 上面那句变成 termux 路径
 * ```
 * 第一行是判别式：**fatal 里印的是我给的路径**，说明 git 真的读 `GIT_SSL_CAINFO` 并覆盖内置值；
 * 第二行说明它一路走到证书校验，我们那份 188900 B / 121 张的 bundle 被接受。
 * （顺带一条没用但值得记的负结果：`GIT_SSL_CAINFO=/dev/null` 也报 anchors 错 ——
 * 空文件不算信任库，所以这里的闸门是「存在且非空」而不是「存在」。)
 *
 * **为什么放 `core/engine` 而不是给 `EnvFactory` 加键**：冻结区。
 * 这一层是纯增量：三个调用点（会话 spawn / `git init` / `git status` 探针）都从
 * `EngineModule` 拿到它，pi 的 bash 工具 spawn 出来的 git 走的是继承来的同一份环境，
 * 所以宿主侧与 agent 侧一次性同时修好，不必碰 `docs/protocol.md` 与任何 pi 源码。
 *
 * **为什么 `putIfAbsent` 而不是 `put`**：[EnvFactory] 是这份表的权威源，它冻结但将来未必永远冻结。
 * 若哪天它自己开始发 `GIT_SSL_CAINFO`，本层应闭嘴而不是把它盖掉 —— 两条通路抢一个键
 * 比少一个键难查得多。
 *
 * **为什么带 `isFile && length>0` 闸门**：P3C §8.9 那条缺陷（`pm install -r` 后 `runtime/bin`
 * 与 `runtime/libexec` 的符号链接集体悬空、`.installed.json` 又不刷新，于是永远 `UpToDate`、
 * 永不重链）会让 `runtime/bin/sh` 指向一个已删除的旧安装目录。给 git 指一条死路，
 * 不如不给 —— 不给它至少还能用自带的默认，给我们一个不存在的路径则多一句 fatal。
 */
object SpawnEnv {

    /** git 的 CA 信任库键（curl/GIT 上游文档里的 `GIT_SSL_CAINFO`）。 */
    const val KEY_CAINFO: String = "GIT_SSL_CAINFO"

    /** git 内部起 shell 时用的路径（别名、`bisect run`、editor 一类；红线 12 的两条 argv 用不到，防的是将来）。 */
    const val KEY_SHELL_PATH: String = "GIT_SHELL_PATH"

    private const val CA_BUNDLE_NAME: String = "ca-bundle.crt"
    private const val SH_REL_PATH: String = "bin/sh"

    /** 会话 spawn / git 短命令的统一入口：11 键打底，两条修正按需叠加。 */
    fun build(ctx: Context, memTier: MemTier = MemTier.DEFAULT): Map<String, String> =
        augment(EnvFactory.build(ctx, memTier), PathLayout.runtimeDir(ctx))

    /**
     * 纯函数面（[runtimeDir] 由调用方给）：单测因此能只造两个文件就断言全表，
     * 不必伪造整个 `filesDir` 布局。
     */
    fun augment(base: Map<String, String>, runtimeDir: File): Map<String, String> = buildMap {
        putAll(base)
        usablePath(File(runtimeDir, CA_BUNDLE_NAME))?.let { putIfAbsent(KEY_CAINFO, it) }
        usablePath(File(runtimeDir, SH_REL_PATH))?.let { putIfAbsent(KEY_SHELL_PATH, it) }
    }

    /** 存在、是真文件（符号链接要跟到底）、且非空 → 绝对路径；否则 null。 */
    private fun usablePath(file: File): String? =
        if (file.isFile && file.length() > 0L) file.absolutePath else null
}
