package works.earendil.pihost

import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import works.earendil.pihost.core.engine.di.EngineModule
import works.earendil.pihost.core.engine.git.GitInitOutcome
import works.earendil.pihost.core.engine.projects.ProjectOutcome
import works.earendil.pihost.core.engine.projects.ProjectStore
import works.earendil.pihost.core.environment.PathLayout

/**
 * P3C §3.8 的装配冒烟：`ProjectStore` 这条新注入边在**真 Hilt 图**里解析得出来吗。
 *
 * ## 为什么这条要在 `:app` 而不是 `:core:engine`
 * `ProjectStore` 的行为覆盖全在 `:core:engine`（`ProjectStoreTest` 16 条，纯 `TemporaryFolder`）。
 * 这里测的只有一件事：**装配**。`EngineModule` 在本机被 `@UninstallModules` 换掉之后，
 * `AppTestEngineModule.projectStore` 必须补上同一条绑定，否则 Hilt 在建图阶段就
 * `MissingBinding`——而那个失败发生在使用它的 UI 之前，靠 UI 用例是看不出来的
 * （P2G §8 第 4 条 / P2H §3.3 已经为 `ModelCredentialStore` 与 `RelayEndpointStore`
 * 各交过一次这个学费）。
 *
 * ## 为什么落盘断言用沙盒 `filesDir`
 * Robolectric 的 `filesDir` 每个沙箱唯一，所以「建出来的项目目录在沙盒里」这句话
 * 在这里是可证的；而「在**设备**上建目录要过 SELinux」不在这里、也不在这里证明得了
 * ——那是 REPORT-P3C §8 的第一条「只有真机可验」。构建机以 root 跑测试，
 * 若这里去跑真 `git init`，它会成功，然后给出一个设备上未必成立的结论。
 */
@HiltAndroidTest
@UninstallModules(EngineModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = HiltTestApplication::class)
class ProjectStoreWiringTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var store: ProjectStore

    private val app by lazy { ApplicationProvider.getApplicationContext<android.app.Application>() }

    @Test
    fun `the graph resolves a ProjectStore rooted at the sandbox projects dir`() {
        hiltRule.inject()

        val outcome = store.create("wired-proj", initGit = false)

        val created = outcome as ProjectOutcome.Created
        assertEquals("wired-proj", created.name)
        assertEquals("没勾 git 就一次都不该尝试", GitInitOutcome.Skipped, created.git)

        val dir = PathLayout.projectDir(app, "wired-proj")
        assertTrue("目录必须落在本沙箱的 filesDir 下，实为 ${dir.absolutePath}", dir.isDirectory)
        assertTrue(
            "且绝不该跑出沙盒",
            dir.canonicalPath.startsWith(PathLayout.projectsDir(app).canonicalPath + '/'),
        )
        assertEquals("空项目里不该有任何文件", emptyList<String>(), dir.listFiles()?.map { it.name })
    }

    /**
     * 装配里的 git 面必须是**空假体**：`create(initGit = true)` 走完之后
     * [NoopGitInitializer] 记到目录了，但一个子进程都不该起。
     *
     * 这条是上一条的另一半：如果绑的是真 `ProcessGitInitializer`，
     * 这里 `dirs` 依然会加一条，差别只在有没有真进程——所以断的是「沙盒里没有 `.git`」，
     * 那是真 init 唯一会留下的事实。
     */
    @Test
    fun `the wired git face never spawns anything on the test machine`() {
        hiltRule.inject()

        val outcome = store.create("no-spawn", initGit = true)

        assertTrue("该记到目录（说明写面被调到了）", NoopGitInitializer.dirs.any { it.endsWith("/no-spawn") })
        assertFalse(
            "但绝不该真建出仓库：编译机是 root，真 git 会成功并给出设备上不成立的结论",
            java.io.File(PathLayout.projectDir(app, "no-spawn"), ".git").exists(),
        )
        assertTrue(outcome is ProjectOutcome.Created)
    }

    /** 穿越名在装配层也该被拒（第二道门随装配一起装上了，不是只在 core 的单测里存在）。 */
    @Test
    fun `the wired store still refuses traversal names`() {
        hiltRule.inject()

        assertTrue(store.create("../../escape-me", initGit = false) is ProjectOutcome.Invalid)
        assertFalse(
            "沙盒之外不该多出任何东西",
            java.io.File(PathLayout.projectsDir(app).parentFile, "escape-me").exists(),
        )
    }
}
