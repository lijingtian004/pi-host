package works.earendil.pihost

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 清单权限与版本体检（P2F §4.4 的 app 侧）。
 *
 * Robolectric 读的是 **AGP 合并后的 manifest**（`isIncludeAndroidResources = true`，
 * `app/build.gradle.kts:43-47`），所以这里验的就是最终进 APK 的那份，不是源文件。
 *
 * `@Config(application = Application::class)` 是**故意的降级**：本文件只查清单，
 * 不碰 DI。留空的话 Robolectric 会照 `android:name=".PiHostApp"` 实例化真实的 @HiltAndroidApp
 * 应用类，那条路要求 `@HiltAndroidTest` + `HiltTestApplication` + `HiltAndroidRule`
 * （见 `PiEngineServiceWiringTest.kt:40-44` 的配方）——对权限断言纯属白付装配成本。
 *
 * 关于 `checkSelfPermission` 的一条如实说明（不是循环论证）：
 * Robolectric **不模拟安装期授权**——真机上 normal 权限（INTERNET / FOREGROUND_SERVICE）
 * 由系统在 install 时自动授予，而 shadow 的授权集合默认是空的。
 * 所以本文件先断言「合并清单声明了 INTERNET」，再把**从清单里读出来的**
 * `requestedPermissions` 整组喂给 `grantPermissions`（等价于 install 期授予），
 * 之后 `checkSelfPermission` 才必须返回 `PERMISSION_GRANTED`。
 * 授权集合来源于清单而非硬编码：**清单少一条，第一步的断言先红，第二步的等价授予也没有 INTERNET**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManifestPermissionTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun requestedPermissions(): Array<String> {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions ?: emptyArray()
    }

    // ── ① INTERNET 已在合并清单里，且按安装期语义 checkSelfPermission 通过 ──────────

    @Test
    fun `01 internet permission is declared and granted at install time`() {
        assertTrue(
            "P2F §4.3(a)：没有 INTERNET，pi 调模型 API 的 socket 直接被拒。实际声明=${requestedPermissions().toList()}",
            requestedPermissions().contains(Manifest.permission.INTERNET),
        )

        shadowOf(app).grantPermissions(*requestedPermissions())

        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(Manifest.permission.INTERNET),
        )
    }

    // ── ② 旧三条权限不许被覆盖掉（P2C 的 FGS 面 + 通知面） ─────────────────────────

    @Test
    fun `02 the pre-p2f permission set survives the manifest edit`() {
        val declared = requestedPermissions().toSet()
        assertTrue("FOREGROUND_SERVICE 丢了 → 引擎服务起不来：$declared",
            declared.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue("SPECIAL_USE 子类丢了 → API 34 直接崩：$declared",
            declared.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue("POST_NOTIFICATIONS 丢了 → 通知门失效：$declared",
            declared.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    // ── ③ 版本必须升到 versionCode=2 / versionName=0.1.1（§2 红线「版本必须升」） ────

    @Test
    fun `03 the apk identity is bumped to versionCode 2 and versionName 0 point 1 point 1`() {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // 用 2L：longVersionCode 是 Long，写字面量 2 会落到 assertEquals(String, Object, Object)
        // 那条重载上，Integer(2) != Long(2) 会假红——这是 JUnit 装箱陷阱，不是版本没升。
        assertEquals("versionCode 不升，真机会装成同版本、覆盖不掉带 bug 的旧包", 2L, info.longVersionCode)
        assertEquals("0.1.1", info.versionName)
        assertEquals("works.earendil.pihost", info.packageName)
    }
}
