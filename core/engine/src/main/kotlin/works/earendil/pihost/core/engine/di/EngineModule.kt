package works.earendil.pihost.core.engine.di

import android.content.Context
import android.os.Build
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
import works.earendil.pihost.core.engine.ExecFallbackStarter
import works.earendil.pihost.core.engine.PiSpawn
import works.earendil.pihost.core.engine.RuntimeInstall
import works.earendil.pihost.core.engine.RuntimeInstallImpl
import works.earendil.pihost.core.engine.SessionSupervisor
import works.earendil.pihost.core.engine.SpawnDiagnosticsSink
import works.earendil.pihost.core.engine.audit.AuditPaths
import works.earendil.pihost.core.engine.audit.AuditScope
import works.earendil.pihost.core.engine.audit.AuditSink
import works.earendil.pihost.core.engine.audit.FileAuditSink
import works.earendil.pihost.core.engine.settings.DataStoreSettingsRepository
import works.earendil.pihost.core.engine.settings.ModelCredentialStore
import works.earendil.pihost.core.engine.settings.RelayEndpointStore
import works.earendil.pihost.core.engine.settings.SettingsRepository
import works.earendil.pihost.core.environment.EnvFactory
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.core.process.JvmProcessFactory
import works.earendil.pihost.core.process.ProcessStarter
import java.io.File
import javax.inject.Singleton

/**
 * 引擎装配（规格书 §4.2.2）：P2C 那段 `EngineRuntime.initialize()` 的手工组装，
 * 原样搬进 Hilt 的 `@Singleton` 组件，静态可变桥随之删除（§4.2.4）。
 *
 * 组装件来自冻结区的公开工厂（P2F 起 starter 外面多一层 core/engine 自己的包装，冻结区未动）：
 * - argv = [PiSpawn]（libDir = `applicationInfo.nativeLibraryDir`、runtimeDir = [PathLayout.runtimeDir]）；
 * - env = `EnvFactory.build(ctx, settings.memTier)`（P2E §3.4 起改动态取；P2C/P2D 写死
 *   `MemTier.MID`，与 `MemTier.DEFAULT` 同值 192MB，**默认行为一字未变**，
 *   只是磁盘上真写了 LOW/HIGH 时下次 spawn 跟着变，§13 偏差 4）；
 * - 工作目录 = [PathLayout.projectDir]`("default")`（P3 才有项目切换）。**P2F 修正**：`PathLayout` 只构造路径
 *   不建目录，全仓此前无一处 mkdir `projects/`（RECON-P2F §2.1）→ 该目录在真机上不存在，
 *   而子进程 chdir 先于 execve，于是 spawn 直接 `error=2`；现由 [SessionSupervisor] spawn 前 mkdirs 兜底；
 * - starter = [ExecFallbackStarter]([JvmProcessFactory], [SpawnDiagnosticsSink])（P2F §4.1(b)(c)：
 *   诊断上屏 + 落盘，`error=13` 时走 linker64 装载降级）。
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

    /**
     * spawn 通路（P2F §4.1(c)）：`ExecFallbackStarter(JvmProcessFactory, sink)`。
     *
     * - **为什么这里要 ctx**：诊断落盘路径 = `File(PathLayout.homeDir(ctx), spawn-diagnostics.log)`。
     *   选 homeDir 不选 runtimeDir（P2F §9）：runtimeDir 会被安装器原子切换清掉，homeDir 稳定。
     *   参数只在本模块内部动过（红线只锁 `SessionSupervisor`/`ChatEngine`/`RuntimeInstall` 的公共 API）；
     *   `:app`/`:core:engine` 的 Hilt 测试一律 `@UninstallModules(EngineModule::class)` 整体换假体，
     *   没有任何用例直接绑这个提供者（RECON-P2F §3 实测），所以改签名零破坏。
     * - **sink 的 runCatching 全包 + >256KB 截断重写**都在 [SpawnDiagnosticsSink] 里，
     *   这里只负责喂 Android 侧的头部（`Build.SUPPORTED_ABIS` 等），使两个核心类保持纯 JVM 可测。
     */
    @Provides
    @Singleton
    fun processStarter(@ApplicationContext ctx: Context): ProcessStarter {
        val sink = SpawnDiagnosticsSink(
            file = File(PathLayout.homeDir(ctx), SpawnDiagnosticsSink.FILE_NAME),
            banner = {
                listOf(
                    "abis=${Build.SUPPORTED_ABIS.joinToString(",")}",
                    "nativeLibraryDir=${ctx.applicationInfo.nativeLibraryDir}",
                    "filesDir=${ctx.filesDir}",
                )
            },
        )
        return ExecFallbackStarter(delegate = JvmProcessFactory, diagnostics = sink)
    }

    /**
     * 设置仓库（P2E §4.2）。**必须 @Singleton**：DataStore 按文件路径持锁，
     * 第二条实例直接抛 `IllegalStateException: There are multiple DataStores active for the same file`
     * （RECON-P2E §3 结论 2 的实测栈顶是 `FileStorage.createConnection(FileStorage.kt:52)`）。
     *
     * 绑定挂在 [EngineModule] 而不是单开一个 `SettingsModule`：`:app`/`:core:engine` 的
     * Hilt 测试一律靠 `@UninstallModules(EngineModule::class)` 换假体，
     * 同一条 uninstall 就能把引擎面与设置面一起换掉，不用每个测试记两个模块名。
     */
    @Provides
    @Singleton
    fun settingsRepository(@ApplicationContext ctx: Context): SettingsRepository =
        DataStoreSettingsRepository.create(ctx)

    /**
     * 模型凭据仓库（P2G §4.1）：`homeDir` 取冻结区现成的 [PathLayout.homeDir]（RECON-P2G §1 实测 `:34`），
     * 于是落点 = `filesDir/home/.pi/agent/auth.json`，与 pi 的 `getAgentDir()`（`config.ts:605-611`）
     * + `getAuthPath()`（`:623-625`）逐段对齐；同一条 `homeDir` 通路 P2F 已在生产里写过诊断文件
     * （见上面 [processStarter] 的 `:79`）。
     *
     * 凭据**不进 DataStore**（§2 红线：明文 XML 落盘 + 备份面），也不进 env（§9 定案：`EnvFactory`
     * 的 11 个键不动）。绑在 [EngineModule] 而不是 `SettingsModule`：与 [settingsRepository] 同一条理由——
     * `:app`/`:core:engine` 的 Hilt 测试靠一条 `@UninstallModules(EngineModule::class)` 整体换假体，
     * 新增一条 binding 就得同步补进 `AppTestEngineModule`（P2E 的 `ScriptedSettingsRepository` 是同一先例）。
     */
    @Provides
    @Singleton
    fun modelCredentialStore(@ApplicationContext ctx: Context): ModelCredentialStore =
        ModelCredentialStore(PathLayout.homeDir(ctx))

    /**
     * 第三方中转站端点仓库（P2H §3.1）：`models.json` + `settings.json`，与 [modelCredentialStore]
     * 同一个 `homeDir` 通路（`PathLayout.homeDir(ctx)`），所以三张表必然同目录 = `filesDir/home/.pi/agent/`。
     *
     * 同样绑在 [EngineModule] 而不是单开模块：`:app`/`:core:engine` 的 Hilt 测试靠一条
     * `@UninstallModules(EngineModule::class)` 整体换假体，新增一条 binding 就得同步补进
     * `AppTestEngineModule`（P2G §8 第 4 条同一课）。
     */
    @Provides
    @Singleton
    fun relayEndpointStore(@ApplicationContext ctx: Context): RelayEndpointStore =
        RelayEndpointStore(PathLayout.homeDir(ctx))

    /** 唯一项目目录名（P2C 的 `EngineRuntime.DEFAULT_PROJECT` 随静态桥一起迁到这里）。 */
    const val DEFAULT_PROJECT: String = "default"

    /**
     * 审计写入侧的 scope（P3A §3.1）：`SupervisorJob + Dispatchers.IO`，与进程同寿。
     *
     * 与 [engineScope] 分成两条：那条跑 spawn/JSON 轮转（Default），这条只有追加写文件（IO）。
     * `SupervisorJob` 让「一条写协程炸了」不牵连兄弟——虽然 [FileAuditSink] 内部已经全量吞异常，
     * 装配面也不该留一条「一个子失败取消整个 scope」的路径。
     */
    @Provides
    @Singleton
    @AuditScope
    fun auditScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 审计落盘（P3A §3.1/§3.2）：目录 = [AuditPaths.auditDir]（`filesDir/audit`）。
     *
     * **为什么绑在 [EngineModule]**（与 [settingsRepository] / [modelCredentialStore] / [relayEndpointStore]
     * 同一条理由，P2G §8 第 4 条的同一课）：`:app`/`:core:engine` 的 Hilt 测试一律靠一条
     * `@UninstallModules(EngineModule::class)` 整体换假体，新增 binding 就得同步补进
     * `AppTestEngineModule`——单开一个不卸载的模块会让测试图拿到**真**审计 sink，
     * 往 Robolectric 沙箱里写文件，反而更难断言。
     */
    @Provides
    @Singleton
    fun fileAuditSink(
        @ApplicationContext ctx: Context,
        @AuditScope scope: CoroutineScope,
    ): FileAuditSink = FileAuditSink(AuditPaths.auditDir(ctx), scope)

    /**
     * 接口 → 实现转发边（P3A §3.1）：注入点一律要 [AuditSink]，不是 [FileAuditSink]。
     *
     * 照 [relayEndpointStore] / [chatEngine] 的先例：调用方依赖抽象，测试才能拿假体顶掉它
     * （`ChatViewModelTest` 的 FakeAuditSink 就是这么进来的）。
     */
    @Provides
    @Singleton
    fun auditSink(sink: FileAuditSink): AuditSink = sink

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
        settings: SettingsRepository,
        @ApplicationContext ctx: Context,
    ): SessionSupervisor = SessionSupervisor(
        scope = scope,
        starter = starter,
        commandFor = spawn::command,
        // P2E §3.4 + §13 偏差 4：memTier 热更新只影响**后续** spawn（EnvFactory 冻结、
        // 每次 spawn 重新 build env），活动会话不受影响。lambda 同步读 SettingsRepository 的
        // 内存快照 —— 快照由 DataStore 收集器维护，setter 落盘后立刻乐观改写，
        // 所以这里既不挂起也不碰磁盘（RECON-P2E §3 / §7.6：repository 必须在 core 侧）。
        envFor = { EnvFactory.build(ctx, settings.memTier) },
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
