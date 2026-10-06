package works.earendil.pihost.feature.sessions.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import works.earendil.pihost.feature.sessions.PiSessionScanner
import works.earendil.pihost.feature.sessions.SessionScanner

/**
 * 会话扫描面的装配点（规格书 §4.1）。
 *
 * 单例的理由：[PiSessionScanner] 无状态、只有根目录一个字段，单例纯粹是为了
 * 让「sessions 屏多次进出」不重复构造，也方便未来 P3 换实现时只改这一处。
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionsModule {

    /** sessions 根 = `$HOME/.pi/agent/sessions` = `filesDir/home/.pi/agent/sessions`（RECON-P2E §5）。 */
    @Provides
    @Singleton
    fun sessionScanner(@ApplicationContext ctx: Context): SessionScanner =
        PiSessionScanner(SessionScanner.sessionsRoot(ctx))
}
