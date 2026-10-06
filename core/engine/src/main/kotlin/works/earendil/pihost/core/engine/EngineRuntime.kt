package works.earendil.pihost.core.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import works.earendil.pihost.core.environment.EnvFactory
import works.earendil.pihost.core.environment.MemTier
import works.earendil.pihost.core.environment.PathLayout
import works.earendil.pihost.core.process.JvmProcessFactory

/**
 * 进程级单例桥（P2C 规格书 §6.1）。
 *
 * **P2D 引入 Hilt 后本对象整体删除**（静态可变状态是显式债，不是架构）：
 * 现在 [PiEngineService] 与 MainActivity 分属两个组件、没有 DI 容器，
 * 又必须共享同一个 [SessionSupervisor]（否则「启动引擎」和「测试 RPC」各说各话），
 * 所以先落一个最小的静态桥。
 *
 * 生命周期：[initialize] 幂等（重复调用返回同一实例）；[scope] 与进程同寿——
 * 会话协程**不挂在 Service 上**，Service 被系统重建（START_NOT_STICKY 下不会重建，
 * 但 onDestroy 会取消自建 scope）时正在跑的 pi 进程不受影响，
 * 只有 [reset]/[shutdownAll] 才真正收摊。
 */
object EngineRuntime {

    /** 全部会话协程的宿主 scope：SupervisorJob 保证一条会话失败不牵连其它会话。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 当前 supervisor；未初始化 = null。
     *
     * 规格书 §6.1 把它定成**可写的 public var**（无 DI 期唯一注入口）：
     * 生产代码只经 [initialize] 赋值，测试直接塞 stub 监督者（本工程不引 Mockito）。
     * P2D Hilt 化后此字段与整个 object 一起消失。
     */
    @Volatile
    var supervisor: SessionSupervisor? = null

    /** 默认项目目录名（`filesDir/projects/default`）——P3 才有项目切换。 */
    const val DEFAULT_PROJECT: String = "default"

    /**
     * 构造并返回唯一的 [SessionSupervisor]；已初始化则直接返回旧实例（幂等）。
     *
     * 组装件全部来自冻结区的公开工厂（规格书 §6.1）：
     * argv = [PiSpawn]（libDir = `applicationInfo.nativeLibraryDir`、runtimeDir = [PathLayout.runtimeDir]），
     * env = [EnvFactory.build]，工作目录 = [PathLayout.projectDir] 下 [DEFAULT_PROJECT]。
     */
    fun initialize(ctx: Context, tier: MemTier = MemTier.DEFAULT): SessionSupervisor {
        supervisor?.let { return it }
        synchronized(this) {
            supervisor?.let { return it }
            val app = ctx.applicationContext
            val libDir = app.applicationInfo.nativeLibraryDir
                ?: error("applicationInfo.nativeLibraryDir 为 null：取不到 libpi_node.so 所在目录，无法 spawn")
            val spawn = PiSpawn(libDir, PathLayout.runtimeDir(app).absolutePath)
            val created = SessionSupervisor(
                scope = scope,
                starter = JvmProcessFactory,
                commandFor = spawn::command,
                envFor = { EnvFactory.build(app, tier) },
                workingDir = PathLayout.projectDir(app, DEFAULT_PROJECT).absolutePath,
            )
            supervisor = created
            return created
        }
    }

    /**
     * 仅供测试：丢弃 supervisor（不碰 [scope]，也不动子进程）。
     * 生产代码不许调用——真停机走 [PiEngineService] 的 ACTION_STOP。
     */
    fun reset() {
        synchronized(this) { supervisor = null }
    }
}
