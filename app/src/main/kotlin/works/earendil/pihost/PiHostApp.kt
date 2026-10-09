package works.earendil.pihost

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import works.earendil.pihost.core.engine.crash.CrashReporter

/**
 * Hilt 图宿主（规格书 §4.2.1）。
 *
 * P2C 靠 `core/engine` 里那个叫 `EngineRuntime` 的静态 object 在 Activity 与 Service 之间
 * 共享 supervisor；P2D 引入 Hilt 后由 `@Singleton` 组件承担同一职责（该 object 已删除），
 * 静态可变状态随之删除（规格书 §4.2.4）。
 *
 * **P3A §3.3 多了一件事：崩溃收集**。`onCreate()` 里做两步——
 * 1. [CrashReporter.install]：把默认 `UncaughtExceptionHandler` 换成「先同步落盘、再委托回原 handler」
 *    的包装层。放在**第一行**（`super.onCreate()` 之后立即）：Hilt 图已可用，
 *    而此刻之后发生的任何一次启动崩溃都值得留下一份证据；
 * 2. [CrashReporter.cleanup]：把上一次运行留下的报告裁到最新 10 份。
 *
 * 两步都是同步的，这是刻意的（§2 红线「崩溃文件必须同步写」的同一条理由）：
 * 启动路径上起协程去删文件，删到一半进程被系统掐掉等于什么都没做。
 * 代价是主线程上多一次 `filesDir/crash` 的 `listFiles` + 若干 `delete`——
 * 上限 10 份、每份几十 KB 的目录扫描，比同一条 `onCreate` 里 Hilt 自身要做的反射装配便宜得多。
 *
 * 注入用字段注入而不是构造：`Application` 的构造由系统调，没有可传参的口子。
 * 崩溃报告里**不含凭据**：正文只有时间/版本/设备/线程名 + 异常与栈（§2 红线「apiKey 永不回显」的延伸）。
 */
@HiltAndroidApp
class PiHostApp : Application() {

    @Inject
    lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        crashReporter.cleanup()
    }
}
