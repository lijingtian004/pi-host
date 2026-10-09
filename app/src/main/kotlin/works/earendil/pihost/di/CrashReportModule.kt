package works.earendil.pihost.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import works.earendil.pihost.BuildConfig
import works.earendil.pihost.core.engine.crash.CrashPaths
import works.earendil.pihost.core.engine.crash.CrashReporter

/**
 * 崩溃收集的装配（规格书 P3A §3.3）。
 *
 * **为什么单独一个模块，而不是塞进 `EngineModule`**：
 * 1. 版本号只有 `:app` 的 `BuildConfig` 才是真相（§3.3 原文「DI @Provides 提供真 dir + BuildConfig.VERSION_NAME」）；
 *    `:core:engine` 是 library 模块，那里没有 app 的 `BuildConfig`，硬往 core 里塞一个版本常量就是第二份真相。
 * 2. `:app`/`:core:engine` 的 Hilt 测试一律 `@UninstallModules(EngineModule::class)` 整体换假体，
 *    而崩溃面**要的就是真装配**：§3.3 的两条 app 用例断言的是「`PiHostApp.onCreate()` 之后默认 handler 被包过」
 *    「`pendingReports()` 反映磁盘上的文件」，假体顶掉就没东西可测了。
 *    Robolectric 沙箱的 `filesDir` 每沙箱唯一，写进去的是临时目录，不碰设备路径（P2F §7.1 的越界教训）。
 *
 * 目录 = [CrashPaths.crashDir]（`filesDir/crash`）：§1.3 已决——`core/environment` 冻结，
 * 新目录不进 `PathLayout`。
 */
@Module
@InstallIn(SingletonComponent::class)
object CrashReportModule {

    @Provides
    @Singleton
    fun crashReporter(@ApplicationContext ctx: Context): CrashReporter =
        CrashReporter(dir = CrashPaths.crashDir(ctx), appVersion = BuildConfig.VERSION_NAME)
}
