package works.earendil.pihost.core.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.environment.PathLayout
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [RuntimeInstallImpl] 的适配回归（规格书 §7：「ChatEngine/RuntimeInstall 接口由
 * SessionSupervisor 与 AssetInstaller 适配的回归」）。
 *
 * 只验**适配层**该验的三件事，不重复 [AssetInstallerTest] 那 5 条解包语义：
 * 1. `InstallResult` 压成 Boolean 的映射（UpToDate/Installed → true，Failed → false）；
 * 2. 进度**镜像**确实跟着 AssetInstaller 走（首启 UI 只读这一条流）；
 * 3. 失败原因必须原样可见（VM 把它填进 `ChatUiState.Failed.cause`，看不见就没法排查）。
 *
 * scope 一律用 `Dispatchers.Unconfined`：镜像的收集协程立刻同步跑，
 * 断言就与 `runBlocking { ensureInstalled() }` 的返回顺序无关（不需要 pump）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuntimeInstallTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun cleanSandbox() {
        PathLayout.runtimeDir(ctx).deleteRecursively()
        PathLayout.runtimeTmpDir(ctx).deleteRecursively()
        PathLayout.runtimeOldDir(ctx).deleteRecursively()
    }

    private val unconfined: CoroutineScope
        get() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // ── 资产夹具（与 AssetInstallerTest 同形，独立一份以免跨文件耦合）──────────

    private class FakeAssetSource(
        private val manifestFile: File,
        private val zipFile: File,
        private val manifestFailure: String? = null,
    ) : AssetSource {
        var manifestOpens = 0
            private set
        var runtimeOpens = 0
            private set

        override fun openManifest(): InputStream {
            manifestOpens++
            manifestFailure?.let { throw IOException(it) }
            return FileInputStream(manifestFile)
        }

        override fun openRuntime(): InputStream {
            runtimeOpens++
            return FileInputStream(zipFile)
        }
    }

    private fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun makeZip(files: Map<String, String>): File {
        val zip = File(folder.root, "pi-runtime-${System.nanoTime()}.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            files.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return zip
    }

    private fun makeManifest(version: String, files: Map<String, String>, shaOverrides: Map<String, String> = emptyMap()): File {
        val fileJson = files.entries.joinToString(",") { (path, content) ->
            """{"path":"$path","sha256":"${shaOverrides[path] ?: sha256Hex(content)}"}"""
        }
        val manifest = File(folder.root, "manifest-$version-${System.nanoTime()}.json")
        manifest.writeText("""{"runtimeVersion":"$version","files":[$fileJson],"symlinks":[]}""")
        return manifest
    }

    private fun installWith(source: AssetSource, scope: CoroutineScope = unconfined): RuntimeInstallImpl =
        RuntimeInstallImpl(ctx, AssetInstaller(source), scope)

    // ── 用例 1：全新安装 → true，镜像进 Done，运行期目录与标记就位 ───────────────

    @Test
    fun `ensureInstalled returns true after a fresh install and the mirror reaches Done`() {
        val files = mapOf("pi/dist/bundle/cli.js" to "// bundle\n", "bin/node" to "ELF-node")
        val install = installWith(FakeAssetSource(makeManifest("9.0.0", files), makeZip(files)))

        assertEquals(InstallProgress.Idle, install.progress.value)
        assertTrue(install.run { runBlocking { ensureInstalled() } })

        assertEquals(InstallProgress.Done, install.progress.value)
        assertEquals("9.0.0", InstalledRuntime.version(ctx))
        assertEquals(
            "// bundle\n",
            File(PathLayout.runtimeDir(ctx), "pi/dist/bundle/cli.js").readText(),
        )
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
    }

    // ── 用例 2：版本命中 → true，且连 zip 都不打开（UpToDate 也映射成 true）──────

    @Test
    fun `a matching installed version returns true without opening the archive`() {
        InstalledRuntime.write(ctx, "9.0.0")
        val files = mapOf("bin/node" to "ELF-node")
        val source = FakeAssetSource(makeManifest("9.0.0", files), makeZip(files))
        val install = installWith(source)

        assertTrue(runBlocking { install.ensureInstalled() })
        assertEquals(1, source.manifestOpens)
        assertEquals("版本命中时绝不能打开 zip", 0, source.runtimeOpens)
        assertEquals(InstallProgress.Done, install.progress.value)
    }

    // ── 用例 3：sha 不符 → false + 镜像 Failed（原因可见）+ 旧 runtime 原样留着 ──

    @Test
    fun `a sha mismatch returns false and mirrors the failure cause`() {
        val runtime = PathLayout.runtimeDir(ctx)
        File(runtime, "bin").mkdirs()
        File(runtime, "bin/node").writeText("OLD-NODE")
        InstalledRuntime.write(ctx, "9.0.0")

        val files = mapOf("bin/node" to "NEW-NODE")
        val source = FakeAssetSource(
            makeManifest("9.0.1", files, shaOverrides = mapOf("bin/node" to "0".repeat(64))),
            makeZip(files),
        )
        val install = installWith(source)

        assertFalse(runBlocking { install.ensureInstalled() })
        val failed = install.progress.value
        assertTrue("镜像必须是 Failed，实为 $failed", failed is InstallProgress.Failed)
        val cause = (failed as InstallProgress.Failed).message
        assertTrue("cause 必须带 sha256 mismatch 与原文件名：$cause", cause.contains("sha256 mismatch"))
        assertTrue(cause.contains("bin/node"))
        assertEquals("OLD-NODE", File(runtime, "bin/node").readText())
        assertEquals("9.0.0", InstalledRuntime.version(ctx))
    }

    // ── 用例 4：清单读不出来 → false，且原因不是空串（UI 要显因）────────────────

    @Test
    fun `an unreadable manifest fails with the real cause rather than an empty string`() {
        val files = mapOf("bin/node" to "ELF-node")
        val boom = "资产缺失：runtime/manifest.json"
        val install = installWith(FakeAssetSource(makeManifest("9.0.0", files), makeZip(files), manifestFailure = boom))

        assertFalse(runBlocking { install.ensureInstalled() })
        val failed = install.progress.value as InstallProgress.Failed
        assertEquals(boom, failed.message)
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
    }

    // ── 用例 5：镜像逐档跟随 AssetInstaller（首启进度 UI 只读这一条流）───────────

    @Test
    fun `the mirrored progress follows every step of the underlying installer`() {
        val files = linkedMapOf(
            "pi/dist/bundle/cli.js" to "bundle",
            "pi/node_modules/index.js" to "modules",
        )
        val installer = AssetInstaller(FakeAssetSource(makeManifest("9.0.0", files), makeZip(files)))
        // Unconfined 的镜像收集协程是「就地续跑」：内层每写一档，外层立刻看见一档
        val install = RuntimeInstallImpl(ctx, installer, unconfined)

        val seen = ArrayList<InstallProgress>()
        // 采集协程挂在自己的 SupervisorJob 上：**不能**当 runBlocking 的孩子——
        // job.cancel() 会让父 scope 一起进 CancellationException（经典坑）
        val recorder = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = recorder.launch { install.progress.collect(seen::add) }
        val ok = runBlocking { install.ensureInstalled() }
        job.cancel()
        assertTrue(ok)
        assertTrue("必须见过解包档：$seen", seen.filterIsInstance<InstallProgress.Extracting>().isNotEmpty())
        assertTrue("必须见过校验档：$seen", seen.filterIsInstance<InstallProgress.Verifying>().isNotEmpty())
        assertTrue("必须见过 Finalizing：$seen", seen.contains(InstallProgress.Finalizing))
        assertEquals("终值必须是 Done，实为 $seen", InstallProgress.Done, seen.last())
        assertEquals("镜像终值必须与内层一致", installer.progress.value, install.progress.value)
        val extracting = seen.filterIsInstance<InstallProgress.Extracting>()
        assertEquals("total 取清单文件数", listOf(2, 2, 2), extracting.map { it.total })
        assertEquals("done 逐条递增", listOf(0, 1, 2), extracting.map { it.done })
    }
}
