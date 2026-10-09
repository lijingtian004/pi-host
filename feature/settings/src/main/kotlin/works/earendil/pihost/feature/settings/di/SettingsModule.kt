package works.earendil.pihost.feature.settings.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import works.earendil.pihost.feature.settings.AndroidRuntimeInfo
import works.earendil.pihost.feature.settings.DiagnosticsSource
import works.earendil.pihost.feature.settings.FileDiagnosticsSource
import works.earendil.pihost.feature.settings.FileModelCredentialSource
import works.earendil.pihost.feature.settings.FileRelayEndpointSource
import works.earendil.pihost.feature.settings.ModelCredentialSource
import works.earendil.pihost.feature.settings.RelayEndpointSource
import works.earendil.pihost.feature.settings.RuntimeInfoSource

/**
 * 设置屏的装配（规格书 §4.2）。
 *
 * 只有版本读取口需要绑定：[AndroidRuntimeInfo] 自己带 `@Inject`/`@Singleton`，
 * 但 `SettingsViewModel` 依赖的是 [RuntimeInfoSource] 这个接口，故需要一条接口→实现的边。
 * 取 `object` + `@Provides` 而不是 `abstract class` + `@Binds`：与 `EngineModule`
 * 同一决定（RECON-P2D §4.4 原文允许自选，纯转发的 @Binds 在此不多给任何约束力）。
 *
 * 凭据面（P2G §4.2）同理：[FileModelCredentialSource] 带 `@Inject`，VM 依赖 [ModelCredentialSource] 接口，
 * 这里加一条同形状的转发边。被包的 `ModelCredentialStore` 本体**不在这里提供**——
 * 它由 `:core:engine` 的 `EngineModule` 提供（规格书 §4.1 点名，且 `:app`/`:core:engine` 的
 * Hilt 测试一条 `@UninstallModules(EngineModule::class)` 就能同时换掉引擎面、设置面与凭据面）。
 *
 * `SettingsRepository` **不在这里**：它由 `:core:engine` 的 `EngineModule` 提供
 * （规格书 §4.2 把三键实现放在 core 侧，`envFor` 要同步读 memTier）。
 * 于是测试侧一条 `@UninstallModules(EngineModule::class)` 就能同时换掉引擎面与设置面，
 * 这正是当初把 repository 挂进 EngineModule 的动机之一。
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun runtimeInfoSource(impl: AndroidRuntimeInfo): RuntimeInfoSource = impl

    @Provides
    @Singleton
    fun modelCredentialSource(impl: FileModelCredentialSource): ModelCredentialSource = impl

    /**
     * 中转面（P2H §3.2）：与 [modelCredentialSource] 同一条形状的转发边。
     *
     * 被包的 `RelayEndpointStore` 本体**不在这里提供**——它由 `:core:engine` 的 `EngineModule` 提供
     * （§3.1 把它放在凭据 store 旁边，两张表同一个 `homeDir` 通路），
     * 于是 `:app`/`:core:engine` 的测试一条 `@UninstallModules(EngineModule::class)` 仍然同时换掉
     * 引擎面、设置面、凭据面与中转面；卸载侧要补的 store 边见 `AppTestDoubles`。
     */
    @Provides
    @Singleton
    fun relayEndpointSource(impl: FileRelayEndpointSource): RelayEndpointSource = impl

    /**
     * 诊断面（P3A §3.5）：与 [modelCredentialSource] / [relayEndpointSource] 同一条形状的转发边。
     *
     * 被包的 `CrashReporter` 本体**不在这里提供**——它绑在 `:app` 的 `CrashReportModule`
     * （版本号只有 app 的 `BuildConfig` 才是真相，§3.3）。于是本模块**不会**被
     * `@UninstallModules(EngineModule::class)` 连带卸掉，`:app` 的测试图拿到的是
     * 「真 CrashReporter（真目录 = Robolectric 沙箱 filesDir/crash）+ 本条转发边」。
     */
    @Provides
    @Singleton
    fun diagnosticsSource(impl: FileDiagnosticsSource): DiagnosticsSource = impl
}
