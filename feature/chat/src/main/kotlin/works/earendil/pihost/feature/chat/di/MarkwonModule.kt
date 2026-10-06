package works.earendil.pihost.feature.chat.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.noties.markwon.Markwon
import javax.inject.Singleton
import works.earendil.pihost.feature.chat.ui.buildMarkwon

/**
 * Markwon 的单例装配（规格书 §9.5：「markwon 单例 @Singleton：Prism4j 语法注册一次」）。
 *
 * 为什么挂 SingletonComponent 而不是 ViewModel 自建：
 * - `Markwon.builder(...).usePlugin(SyntaxHighlightPlugin.create(Prism4j(GrammarLocator)…))`
 *   的装配成本（语法表、span factory、主题）是**每实例一次**的；每条 assistant 气泡各自 build
 *   会把这笔成本摊到列表滚动上，而 transcript 每 80ms 就可能换一版快照（§3.3 debounce）。
 * - 用 `@ApplicationContext` 建实例 = 生命周期与进程同长，不持有 Activity，
 *   ViewModel 被回收也不会把 Markwon 拖进泄漏链。
 *
 * 线程 confinement（§9.5 的后半句）不靠在这里加锁：`Markwon` 本身只做「解析 + 排版」，
 * 本仓唯一的调用点是 [works.earendil.pihost.feature.chat.ui.MarkdownBody] 的
 * `AndroidView` factory/update，两者都由 Compose 在主线程执行；
 * 实例被 `ChatViewModel` 持有也只是**转交**，VM 从不调用它（见该类 KDoc）。
 */
@Module
@InstallIn(SingletonComponent::class)
object MarkwonModule {

    @Provides
    @Singleton
    fun markwon(@ApplicationContext context: Context): Markwon = buildMarkwon(context)
}
