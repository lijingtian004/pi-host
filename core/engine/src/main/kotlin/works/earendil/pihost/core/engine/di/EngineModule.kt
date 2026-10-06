package works.earendil.pihost.core.engine.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import works.earendil.pihost.core.engine.AssetInstaller
import works.earendil.pihost.core.engine.BundledAssets
import works.earendil.pihost.core.engine.ChatEngine
import works.earendil.pihost.core.engine.EngineScope
import works.earendil.pihost.core.engine.PiSpawn
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.RuntimeInstallImpl
import works.earendil.pihost.core.engine.SessionSupervisor
import works.earendil.pihost.core.environment.EnvFactory
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.core.process.JvmProcessFactory
import works.earendil.pihost.core.process.ProcessStarter
import javax.inject.Singleton

/**
 * 引擎装配（规格书 §4.2.2）：P2C 那段 `EngineRuntime.initialize()` 的手工组装，
 * 原样搬进 Hilt 的 `@Singleton` 组件，静态可变桥随之删除（§4.2.4）。
 *
 * 组装件全部来自冻结区的公开工厂：
 * - argv = [PiSpawn]（libDir = `applicationInfo.nativeLibraryDir`、runtimeDir = [PathLayout.runtimeDir]）；
 * - env = `EnvFactory.build(ctx, MemTier.MID)`（规格书写死 MID，与 P2C 实际用的
 *   `MemTier.DEFAULT` 同值 192MB，这里按 §4.2.2 原文取 MID）；
 * - 工作目录 = [PathLayout.projectDir]`("default")`（P3 才有项目切换）；
 * - starter = [JvmProcessFactory]。
 *
 * **为什么是 `object` + 全 `@Provides`，而不是 `abstract class` + `@Binds`**：
 * 规格书 §4.4 本来就给了两种写法并让实现方自选（「`@Binds` 或直接
 * `SessionSupervisor : ChatEngine` + EngineModule `@Provides fun chatEngine(s) = s`」）。
 * 取 @Provides：接口绑定是纯转发，`@Binds` 在此没有多任何约束力，而 `object` 模块
 * 少一个 Dagger 实例化形态的坑。两个实现（[SessionSupervisor]、[RuntimeInstallImpl]）
 * 本身仍是 `ChatEngine`/`RuntimeInstall` 的唯一 production 实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    /** 会话/安装协程的宿主 scope：一条会话失败不牵连其它（SupervisorJob）。 */
    @Provides
    @Singleton
    @EngineScope
    fun engineScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun processStarter(): ProcessStarter = JvmProcessFactory

    /** 唯一项目目录名（P2C 的 `EngineRuntime.DEFAULT_PROJECT` 随静态桥一起迁到这里）。 */
    const val DEFAULT_PROJECT: String = "default"

    /**
     * `nativeLibraryDir` 为 null 时**不再抛**（P2C 的 `initialize()` 用 `error()`，
     * 那是 UI 线程上的崩溃）。空串会让 argv[0] 变成一个不存在的可执行体，
     * 由 [SessionSupervisor] 按退避表判成 [works.earendil.pihost.core.engine.SessionState.Failed]，
     * 首启 UI 走 `ChatUiState.Failed` 分支——同样的事实，更好的可见性。
     */
    @Provides
    @Singleton
    fun piSpawn(@ApplicationContext ctx: Context): PiSpawn = PiSpawn(
        libDir = ctx.applicationInfo.nativeLibraryDir.orEmpty(),
        runtimeDir = PathLayout.runtimeDir(ctx).absolutePath,
    )

    @Provides
    @Singleton
    fun assetInstaller(@ApplicationContext ctx: Context): AssetInstaller =
        AssetInstaller(BundledAssets(ctx))

    @Provides
    @Singleton
    fun sessionSupervisor(
        @EngineScope scope: CoroutineScope,
        starter: ProcessStarter,
        spawn: PiSpawn,
        @ApplicationContext ctx: Context,
    ): SessionSupervisor = SessionSupervisor(
        scope = scope,
        starter = starter,
        commandFor = spawn::command,
        envFor = { EnvFactory.build(ctx, MemTier.MID) },
        workingDir = PathLayout.projectDir(ctx, DEFAULT_PROJECT).absolutePath,
    )

    /** §4.4：聊天核心链/服务拿到的引擎面（同一个 [SessionSupervisor] 实例）。 */
    @Provides
    @Singleton
    fun chatEngine(supervisor: SessionSupervisor): ChatEngine = supervisor

    /** §4.4：首启安装面（包 [AssetInstaller] + applicationContext）。 */
    @Provides
    @Singleton
    fun runtimeInstall(impl: RuntimeInstallImpl): RuntimeInstall = impl
}
