package works.earendil.pihost.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import works.earendil.pihost.core.engine.BundledAssets
import works.earendil.pihost.core.engine.InstalledRuntime
import works.earendil.pihost.core.engine.RuntimeManifest

/**
 * 版本卡片要显示的三个号（规格书 §3.4 末条）。三个来源各有各的失败方式，所以是三格而不是一个串：
 *
 * - [appVersion]：APK 的 `versionName`（`PackageManager`，理论上必有，取不到给 [UNKNOWN]）；
 * - [installedRuntimeVersion]：`filesDir/runtime/.installed.json` 里的 marker（[InstalledRuntime.version]），
 *   **未安装 = null**，这是「运行时还没落地」的唯一可信判据（首启用户在设置页也要能看出来）；
 * - [bundledRuntimeVersion]：APK 资产里 `runtime/manifest.json` 的 `runtimeVersion`
 *   （= 这次安装包*打算*装成的版本）。两者不一致就意味着下次进聊天页会触发一次重装。
 *
 * [manifestMissing] 单独成旗：资产缺失/损坏与「版本号为空」在两处来源里长得一样，
 * 但语义完全不同（前者是打包事故，后者不该发生），UI 要能分开说。
 * [manifestPending] = 清单还没读过（初值），UI 显示「读取中」而不是「缺失」——
 * 这一旗存在的全部理由：Robolectric 读不了 assets（见 [AndroidRuntimeInfo] 的 KDoc），
 * 单测里跑到的就是这条 pending 分支，不能把它误判成打包事故。
 */
data class RuntimeInfo(
    val appVersion: String,
    val installedRuntimeVersion: String?,
    val bundledRuntimeVersion: String?,
    val manifestMissing: Boolean = false,
    val manifestPending: Boolean = false,
) {
    /** 已装版本与随包版本是否一致（null 一律算不一致——没装就是没装）。 */
    val matches: Boolean
        get() = installedRuntimeVersion != null && installedRuntimeVersion == bundledRuntimeVersion

    /** 把 [AndroidRuntimeInfo.readManifest] 的结果并进这张快照（唯一一处「补写清单」的地方）。 */
    fun withManifest(read: ManifestRead): RuntimeInfo = when (read) {
        is ManifestRead.Present -> copy(bundledRuntimeVersion = read.runtimeVersion, manifestMissing = false, manifestPending = false)
        ManifestRead.Missing -> copy(bundledRuntimeVersion = null, manifestMissing = true, manifestPending = false)
    }

    companion object {
        const val UNKNOWN: String = "未知"
    }
}

/**
 * 版本信息的读取口：做成接口是给 VM 测试留的假体位（读 assets/PM 在单测里不划算）。
 *
 * **两步分开的理由**是可见性，不是性能：marker 一到就有版本号可看，随包清单随后补写，
 * 于是一次刷新会写出两档快照（UI 侧「读取中 → 具体版本号」是有状态的，不是闪一下）。
 * 实现方**自己负责挪出主线程**（[AndroidRuntimeInfo] 用 `withContext(Dispatchers.IO)`），
 * VM 侧不做调度决定——假体在那里不挂起时，测试就是纯虚拟时钟。
 */
interface RuntimeInfoSource {
    /** marker + versionName。可以在读前后再挂起一次（中间档的测试要靠它，见 RECON-P2E §6）。 */
    suspend fun read(): RuntimeInfo

    /**
     * 随包 `runtime/manifest.json`。默认给 [ManifestRead.Missing]：
     * 假体不必实现它，而真机路径由 [AndroidRuntimeInfo] 覆盖。
     */
    suspend fun readManifest(): ManifestRead = ManifestRead.Missing
}

/**
 * production 实现（`di/SettingsModule` 里 @Singleton 装配）。
 *
 * **两次读取都自己 `withContext(Dispatchers.IO)`**：marker 是 `File.readText`、manifest 是
 * `assets.open`，两者都是磁盘 IO，而调用方（[SettingsViewModel]）跑在 `viewModelScope` = 主线程上。
 * VM 侧因此不需要任何调度决定，假体在测试里也就不用真线程（RECON-P2E §6 的虚拟时钟纪律）。
 *
 * **manifest 这一路在 Robolectric 里读不到**：`AssetManager` shim 走 native 资源表，
 * 单测不可靠——`:core:engine` 的资产用例（`AssetInstallerTest` / `RealRuntimeAssetsInstallTest`）
 * 全部靠假 `AssetSource`，同一课。于是本类的 `blockingReadManifest()` 由编译机的 `:app`
 * 渲染路径与真机覆盖，单测覆盖的是 [Companion.manifestVersionOf] 这个纯函数 + VM 的状态机。
 * 记入收尾报告（§13 之外的实现细节偏差第 9 条）。
 *
 * 为什么版本读取落在 `:feature:settings` 而不是 `:core:engine`：这三格数字只有这张卡片在消费；
 * 安装/升级判定（[InstalledRuntime.version] vs manifest）的**决策**仍在冻结语义的 `AssetInstaller` 里，
 * 这里只读不判，不引入第二条真相源。
 */
@Singleton
class AndroidRuntimeInfo @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : RuntimeInfoSource {

    override suspend fun read(): RuntimeInfo = withContext(Dispatchers.IO) { readMarker() }

    override suspend fun readManifest(): ManifestRead = withContext(Dispatchers.IO) { blockingReadManifest() }

    /** marker + versionName（纯阻塞，可单测：不碰 assets）。 */
    fun readMarker(): RuntimeInfo = RuntimeInfo(
        appVersion = appVersionOf(ctx),
        installedRuntimeVersion = runCatching { InstalledRuntime.version(ctx) }.getOrNull(),
        bundledRuntimeVersion = null,
        manifestPending = true,
    )

    /** 随包清单（纯阻塞）。异常与「真的没有清单」在这里合并成 [ManifestRead.Missing]。 */
    fun blockingReadManifest(): ManifestRead {
        val json = runCatching {
            ctx.assets.open(BundledAssets.MANIFEST_ASSET_PATH).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return ManifestRead.Missing
        val version = manifestVersionOf(json) ?: return ManifestRead.Missing
        return ManifestRead.Present(version)
    }

    companion object {
        /** 与 core/engine 的资产解析配置同一套：清单未来加字段不破坏旧宿主。 */
        val MANIFEST_JSON: Json = Json { ignoreUnknownKeys = true }

        /** 纯函数面：单测直接喂 manifest 文本，不碰 assets。 */
        fun manifestVersionOf(json: String): String? =
            runCatching { MANIFEST_JSON.decodeFromString<RuntimeManifest>(json).runtimeVersion }
                .getOrNull()?.ifBlank { null }

        /**
         * `versionName` 在 minSdk 26 的取法：`getPackageInfo(String, Int)` 这条重载
         * 在 API 33 起被 `getPackageInfo(PackageInfo.RequestFlags)` 取代但仍可用（compileSdk 36 只给
         * deprecation 警告，不给 minSdk 26 埋 API 等级坑）。任何失败 → [RuntimeInfo.UNKNOWN]，
         * 绝不因版本卡片让设置页崩。
         */
        @Suppress("DEPRECATION")
        fun appVersionOf(ctx: Context): String = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()?.ifBlank { null } ?: RuntimeInfo.UNKNOWN
    }
}

/** [AndroidRuntimeInfo.readManifest] 的结果（把「有值/没值」做成两型，省掉调用方的二次判空）。 */
sealed interface ManifestRead {
    data class Present(val runtimeVersion: String) : ManifestRead
    data object Missing : ManifestRead
}
