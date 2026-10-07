package works.earendil.pihost.feature.settings

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.engine.settings.ModelCredentialStore
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry

/**
 * 设置页消费的凭据面（规格书 §4.2「注入 fake store」的注入点）。
 *
 * **为什么不直接把 `ModelCredentialStore` 交给 VM**：§4.1 那三个方法是**阻塞**文件 IO，
 * 而 `SettingsViewModel` 跑在 `viewModelScope`（主线程）。本仓对这条已有定案——
 * [RuntimeInfoSource] 的 KDoc 原文「实现方**自己负责挪出主线程**（`withContext(Dispatchers.IO)`），
 * VM 侧不做调度决定——假体在那里不挂起时，测试就是纯虚拟时钟」（实现见 [AndroidRuntimeInfo] `:92`/`:94`）。
 * 于是这里挂起、那里 hop，VM 与两条测试路径（`SettingsViewModelTest` 的 `runTest` +
 * `SettingsScreenTest` 的 `pump()`）都不需要真线程（RECON-P2G §4.1）。
 *
 * 顺带把 §4.2 要的假体位做出来：测试注入 `Fakes.kt` 里的 `FakeModelCredentialSource`，
 * 既能演「保存后列表刷新」，也能演「写盘失败进 notice」——后者用真文件在 root 身份的编译机上很难造
 * （P2F §7.1 的同一课）。
 */
interface ModelCredentialSource {

    /** 当前已配置的 provider 行（**不含 key 明文**，§2 红线）。 */
    suspend fun snapshot(): List<ProviderEntry>

    /** 写入/更新一个 provider 的 api_key；失败上抛，由调用方 `runCatching` 转成 notice。 */
    suspend fun save(providerId: String, apiKey: String)

    /** 删掉一个 provider 条目（幂等）。 */
    suspend fun clear(providerId: String)
}

/**
 * production 实现：包 [ModelCredentialStore]（`filesDir/home/.pi/agent/auth.json`），三个方法各自 hop 到
 * [Dispatchers.IO]。
 *
 * 凭据通路的全部纪律（原子替换、读-改-写、不回显明文）都在 core 侧那个类里，这里**只做调度**，
 * 不建第二份真相、不打任何日志（§2「凭据不进日志」）。
 */
@Singleton
class FileModelCredentialSource @Inject constructor(
    private val store: ModelCredentialStore,
) : ModelCredentialSource {

    override suspend fun snapshot(): List<ProviderEntry> = withContext(Dispatchers.IO) { store.readConfigured() }

    override suspend fun save(providerId: String, apiKey: String) {
        withContext(Dispatchers.IO) { store.save(providerId, apiKey) }
    }

    override suspend fun clear(providerId: String) {
        withContext(Dispatchers.IO) { store.clear(providerId) }
    }
}
