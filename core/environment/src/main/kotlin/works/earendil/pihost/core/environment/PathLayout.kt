package works.earendil.pihost.core.environment

import android.content.Context
import java.io.File

/**
 * 沙盒目录布局集中定义（方案 §4 / §4.4）。所有模块必须经此处取路径，禁止散落硬编码。
 *
 * ```
 * filesDir/
 * ├── runtime/            # 已安装的 pi 运行时（bin/ pi/ libexec/ ca-bundle.crt ...）
 * │   └── .installed.json # 安装标记（runtimeVersion）
 * ├── runtime.tmp/        # 解包暂存区，校验全绿后原子切换成 runtime/
 * ├── runtime.old/        # 原子切换期间旧 runtime 的临时藏身处
 * ├── home/               # $HOME，pi 的 ~/.pi/agent 落在这里
 * └── projects/<name>/    # 会话工作目录
 * ```
 */
object PathLayout {
    const val RUNTIME_DIR_NAME: String = "runtime"
    const val RUNTIME_TMP_DIR_NAME: String = "runtime.tmp"
    const val RUNTIME_OLD_DIR_NAME: String = "runtime.old"
    const val HOME_DIR_NAME: String = "home"
    const val PROJECTS_DIR_NAME: String = "projects"
    const val INSTALLED_MARKER_NAME: String = ".installed.json"

    fun runtimeDir(ctx: Context): File = File(ctx.filesDir, RUNTIME_DIR_NAME)

    fun runtimeTmpDir(ctx: Context): File = File(ctx.filesDir, RUNTIME_TMP_DIR_NAME)

    /** 原子切换时旧 runtime 的中转目录（方案 §5.1 f）。 */
    fun runtimeOldDir(ctx: Context): File = File(ctx.filesDir, RUNTIME_OLD_DIR_NAME)

    fun homeDir(ctx: Context): File = File(ctx.filesDir, HOME_DIR_NAME)

    fun projectsDir(ctx: Context): File = File(ctx.filesDir, PROJECTS_DIR_NAME)

    fun projectDir(ctx: Context, name: String): File = File(projectsDir(ctx), name)

    fun installedMarker(ctx: Context): File = File(runtimeDir(ctx), INSTALLED_MARKER_NAME)
}
