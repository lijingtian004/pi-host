package works.earendil.pihost

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 图宿主（规格书 §4.2.1）。
 *
 * P2C 靠 [works.earendil.pihost.core.engine.EngineRuntime] 这个静态 object 在
 * Activity 与 Service 之间共享 supervisor；P2D 引入 Hilt 后由 `@Singleton` 组件承担同一职责，
 * 静态可变状态随之删除（规格书 §4.2.4）。
 */
@HiltAndroidApp
class PiHostApp : Application()
