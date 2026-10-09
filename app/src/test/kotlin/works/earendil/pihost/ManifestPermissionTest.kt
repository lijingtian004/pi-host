package works.earendil.pihost

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

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
 *
 * **P3A §3.4 起本文件还管「备份面」**（④⑤）：备份排除清单是指向 `res/xml` 的资源引用，
 * 只有 aapt 编译进 APK 之后才解得出引用对不对，所以放在这里用合并资源读，而不是读仓库里的源文件。
 *
 * **P3B §3.5 起还管「权限清单一字不改」**（⑥）：①② 是「不能丢」的下界，⑥ 补上「不能多」的上界——
 * P3B 的三张脸（模型切换 / 统计条 / 压缩）都走既有 RPC 通道，没有任何一条需要新的系统能力，
 * 所以合并清单必须还是那四条。同一条里 ③ 把 versionCode/versionName 升到 7 / 0.1.6
 * （P3B-FIX1 §3.3：本轮唯一允许的 `app/build.gradle.kts` 改动，其余字段一字未动）。
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

    // ── ③ 版本必须升到 versionCode=7 / versionName=0.1.6（§2 红线「版本必须升」） ────

    @Test
    fun `03 the apk identity is bumped to versionCode 7 and versionName 0 point 1 point 6`() {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // 用 7L：longVersionCode 是 Long，写字面量 7 会落到 assertEquals(String, Object, Object)
        // 那条重载上，Integer(7) != Long(7) 会假红——这是 JUnit 装箱陷阱，不是版本没升。
        // 7 是 P3B-FIX1 的修复包：真机若还装着 6，覆盖安装才有门。
        assertEquals("versionCode 不升，真机会装成同版本、覆盖不掉带 bug 的旧包", 7L, info.longVersionCode)
        assertEquals("0.1.6", info.versionName)
        assertEquals("works.earendil.pihost", info.packageName)
        // 单调性单列一条：P2F 发的是 2、P2G 是 3、P2H 是 4、P3A 是 5、P3B 是 6、P3B-FIX1 是 7。
        // 写死 7L 已经隐含这件事，
        // 但这条的失败信息会直接说出「必须严格大于上一版」，排查 adb install -r 拒装时少绕一步。
        assertTrue("必须严格高于 P3B 的 6，否则带 bug 的旧包覆盖不掉", info.longVersionCode > 6L)
    }

    // ── ④ 备份排除清单（P3A §3.4）：API ≤ 30 的那份 ────────────────────────────

    /**
     * `fullBackupContent` 指向的那份资源必须**真的在 APK 里**且排除四个敏感目录。
     *
     * 读的是 `app.resources`（= 合并 + aapt2 编译后的那份），所以这里验的是最终进 APK 的形态，
     * 不是仓库里的源文件——与 `MainActivityTest` 用合并清单同一套理由。
     */
    @Test
    fun `04 backup rules exclude the four sensitive directories`() {
        val sections = excludesBySection(R.xml.backup_rules)

        assertEquals("只该有一个区块（根）：$sections", setOf("full-backup-content"), sections.keys)
        assertEquals(SUCCESS_EXCLUDES, sections.getValue("full-backup-content"))
    }

    // ── ⑤ 备份排除清单（P3A §3.4）：API 31+ 的那两块 + allowBackup 仍是 false ───

    @Test
    fun `05 data extraction rules cover cloud backup and device transfer while allowBackup stays false`() {
        val sections = excludesBySection(R.xml.data_extraction_rules)

        assertEquals(
            "§3.4 点名两块都要有（根节点只当容器，不带 exclude）：$sections.keys",
            setOf("cloud-backup", "device-transfer"),
            sections.keys,
        )
        assertEquals(SUCCESS_EXCLUDES, sections.getValue("cloud-backup"))
        assertEquals(SUCCESS_EXCLUDES, sections.getValue("device-transfer"))

        // 纵深防御的前提：真正生效的开关没被顺手改成 true（§2 红线「凭据永不离开本机」）
        val flags = app.applicationInfo.flags
        assertEquals(
            "allowBackup 必须仍是 false（FLAG_ALLOW_BACKUP 不许置位），flags=0x${Integer.toHexString(flags)}",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    // ── ⑥ P3B §3.5：权限清单**一字不改**（双向：既不许丢，也不许多） ────────────────

    /**
     * ①② 只保证「这四条都在」，加第五条照样绿——而 P3B 全程不该新增任何权限
     * （§3.3 模型切换、§3.4 统计条都走既有 RPC 通道，没有新的系统能力面）。
     * 这条把集合钉成**恰好四个**：manifest 一旦多一条 `uses-permission` 立刻红。
     *
     * 断的是合并清单（与 ①② 同一份真相），所以库模块的 `uses-permission` 也会被算进来——
     * 这正是「依赖偷偷要权限」的检出方式。而它**确实**检出到一条：
     * `androidx.core:core`（本轮解析到的是 1.18.0）在自己的 manifest 里写了
     * ```
     * <permission android:name="${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
     *             android:protectionLevel="signature" />
     * <uses-permission android:name="${'$'}{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
     * ```
     * （实测位置：编译机 `~/.gradle/caches/8.13/transforms/…/core-1.18.0/AndroidManifest.xml:22-26`；
     * 合并产物 `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
     * 里 `uses-permission` 共 5 条 = 我们这 4 条 + 它这 1 条）。
     * **这不是能力申请**：名字带 `applicationId` 前缀、`protectionLevel=signature`、由 App 自己声明自己使用，
     * 是 AndroidX 为 `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` 造的内部闸门，
     * 从 P2A 起每一份 APK 都有它，P3B 一条没加。所以断言分成两半：
     * 系统命名空间（`android.permission.*`）必须**恰好**是 [P3B_SYSTEM_PERMISSIONS]，
     * 剩下的杂项必须**只有**这条库自带的 [selfDefinedReceiverGate]。
     */
    @Test
    fun `06 the permission list is exactly the four and nothing was added in p3b`() {
        val declared = requestedPermissions().toSet()
        val system = declared.filterTo(linkedSetOf()) { it.startsWith("android.permission.") }

        assertEquals(
            "P3B §3.5 红线「不新增权限」：合并清单里 android.permission.* 必须恰好是这四条" +
                "（丢→①② 已各自报警，多→这条报警）。实际=$system",
            P3B_SYSTEM_PERMISSIONS,
            system,
        )

        assertEquals(
            "除上面四条外，合并清单只允许 androidx.core 自带的那条自定义 signature 权限" +
                "（不是系统能力，见 KDoc）。实际多余项=${declared - P3B_SYSTEM_PERMISSIONS}",
            setOf(selfDefinedReceiverGate()),
            declared - P3B_SYSTEM_PERMISSIONS,
        )
    }

    /** `androidx.core` 注入的那条：<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION。 */
    private fun selfDefinedReceiverGate(): String =
        "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /**
     * 把一份备份规则资源读成 `区块名 → exclude 集合（domain:path）`。
     *
     * 用 `resources.getXml`（二分 XML 的 pull 解析）而不是解析源文件：源文件不在测试运行时里，
     * 而「清单指向的资源丢了 / 名字打错」正是 aapt 会静默放过的形态（属性只在运行时才解引用）。
     */
    private fun excludesBySection(resId: Int): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        val parser = app.resources.getXml(resId)
        var rootTag: String? = null
        var section: String? = null
        var depth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (rootTag == null) rootTag = parser.name
                    val name = parser.name
                    if (name == "exclude") {
                        val key = section ?: rootTag.orEmpty()
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        out.getOrPut(key) { linkedSetOf() }.add("$domain:$path")
                    } else if (depth > 1) {
                        section = name
                    }
                }

                XmlPullParser.END_TAG -> depth--
            }
            parser.next()
        }
        return out
    }

    companion object {
        /** §3.4 点名的四个目录 × `domain="file"`（= `<包>/files/`）。 */
        private val SUCCESS_EXCLUDES: Set<String> =
            setOf("home", "audit", "crash", "runtime").map { "file:$it" }.toSet()

        /**
         * P3B §3.5 的系统权限基线：**恰好**这四条 `android.permission.*`，一条不多一条不少。
         *
         * 逐条来历（与 ①② 的分工一致）：
         * - `INTERNET`（P2F §4.3(a)）：pi 调模型 API 的 socket，缺了直接 `ERR_CONNECTION_FAILURE`；
         * - `FOREGROUND_SERVICE`（P2C）：引擎服务必须常驻，否则后台被杀；
         * - `FOREGROUND_SERVICE_SPECIAL_USE`（P2C，API 34 起强制）：少了它 `startForeground` 抛异常；
         * - `POST_NOTIFICATIONS`（P2C）：FGS 通知门，Android 13+ 是 runtime 权限。
         *
         * 字符串与 `Manifest.permission.*` 常量同值，但按「清单里的原始串」写：断言对象是合并清单的
         * 字面量，保持①② 同一套写法，免得读者以为换了来源。
         */
        private val P3B_SYSTEM_PERMISSIONS: Set<String> = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE,
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
