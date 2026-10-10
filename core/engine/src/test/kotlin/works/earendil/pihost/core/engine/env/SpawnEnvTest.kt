package works.earendil.pihost.core.engine.env

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.environment.PathLayout

/**
 * [SpawnEnv]（P3C §3.10）12 条。
 *
 * `augment` 那 9 条是纯 JVM + [TemporaryFolder]；只有 `build` 的两条要 [Context]
 * （它要 `PathLayout.runtimeDir` 与 `applicationInfo.nativeLibraryDir`）。
 *
 * **为什么这一层的测试值比它自己那 6 行代码重**：它挡住的不是逻辑分支，而是一次
 * 真机上线才暴露的环境缺陷 —— pi 一发 https 就 `error adding trust anchors from file:
 * /data/data/com.termux/files/usr/etc/tls/cert.pem | exit=128`（REPORT-P3C §9 ③）。
 * 所以断言的重点是**路径归属**（落在我们自己的 runtime 里，绝不落在 `com.termux`），
 * 以及 §8.9 那条集体悬空符号链接的降级形状（宁可不给键，也不给一条死路）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpawnEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    /** 打底表：只放两个键就够，本层不该关心 EnvFactory 那 11 个键的内容（那边有自己的测试）。 */
    private val base = mapOf("PATH" to "/runtime/bin:/lib:/system/bin", "SSL_CERT_FILE" to "/runtime/ca-bundle.crt")

    private fun runtimeWith(bundleBytes: Long?, makeShDir: Boolean = false): File {
        val runtime = tmp.newFolder("runtime")
        if (bundleBytes != null) {
            val bundle = File(runtime, "ca-bundle.crt")
            bundle.writeBytes(ByteArray(bundleBytes.toInt()))
        }
        val bin = File(runtime, "bin").apply { mkdirs() }
        if (makeShDir) File(bin, "sh").mkdir() else File(bin, "sh").writeBytes(ByteArray(32))
        return runtime
    }

    // ── augment：加什么 ────────────────────────────────────────────────────────

    @Test
    fun `augment adds exactly the two git keys when both paths are usable`() {
        val runtime = runtimeWith(bundleBytes = 128L)
        val env = SpawnEnv.augment(base, runtime)

        assertEquals(4, env.size)
        assertEquals(File(runtime, "ca-bundle.crt").absolutePath, env[SpawnEnv.KEY_CAINFO])
        assertEquals(File(runtime, "bin/sh").absolutePath, env[SpawnEnv.KEY_SHELL_PATH])
    }

    @Test
    fun `the git paths live under our own runtime dir, never under the termux prefix`() {
        val runtime = runtimeWith(bundleBytes = 128L)
        val env = SpawnEnv.augment(base, runtime)

        listOf(SpawnEnv.KEY_CAINFO, SpawnEnv.KEY_SHELL_PATH).forEach { key ->
            val value = env.getValue(key)
            assertTrue("$value 应在 $runtime 下", value.startsWith(runtime.absolutePath))
            assertFalse("$value 泄漏了 Termux 前缀", value.contains("com.termux"))
        }
    }

    @Test
    fun `base map is never mutated`() {
        val runtime = runtimeWith(bundleBytes = 128L)
        SpawnEnv.augment(base, runtime)

        assertEquals(2, base.size)
        assertFalse(base.containsKey(SpawnEnv.KEY_CAINFO))
    }

    // ── augment：什么时候不加 ──────────────────────────────────────────────────

    @Test
    fun `absent bundle omits cainfo`() {
        val runtime = runtimeWith(bundleBytes = null)
        val env = SpawnEnv.augment(base, runtime)

        assertEquals(setOf("PATH", "SSL_CERT_FILE", SpawnEnv.KEY_SHELL_PATH), env.keys)
        assertFalse(env.containsKey(SpawnEnv.KEY_CAINFO))
    }

    @Test
    fun `empty bundle omits cainfo because git rejects an anchor-less file anyway`() {
        // 机上实测：GIT_SSL_CAINFO=/dev/null 同样报 `error adding trust anchors from file:`，
        // 所以「存在」不等于「可用」，空文件指过去只是把一种死法换成另一种。
        val runtime = runtimeWith(bundleBytes = 0L)
        val env = SpawnEnv.augment(base, runtime)

        assertFalse(env.containsKey(SpawnEnv.KEY_CAINFO))
    }

    @Test
    fun `directory masquerading as the bundle is not a usable path`() {
        val runtime = tmp.newFolder("runtime")
        File(runtime, "ca-bundle.crt").mkdir()
        runtimeWithShOnly(File(runtime, "bin"))

        val env = SpawnEnv.augment(base, runtime)

        assertFalse(env.containsKey(SpawnEnv.KEY_CAINFO))
    }

    @Test
    fun `dangling sh symlink drops only shell path and keeps cainfo`() {
        // P3C §8.9：`pm install -r` 后 runtime/bin 的链接集体指向已删除的旧安装目录。
        val runtime = tmp.newFolder("runtime")
        File(runtime, "ca-bundle.crt").writeBytes(ByteArray(64))
        val bin = File(runtime, "bin").apply { mkdirs() }
        Files.createSymbolicLink(
            bin.toPath().resolve("sh"),
            Paths.get("/data/app/~~gone==/works.earendil.pihost-xyz==/lib/arm64/libpi_bash.so"),
        )

        val env = SpawnEnv.augment(base, runtime)

        assertEquals(3, env.size)
        assertEquals(File(runtime, "ca-bundle.crt").absolutePath, env[SpawnEnv.KEY_CAINFO])
        assertFalse(env.containsKey(SpawnEnv.KEY_SHELL_PATH))
    }

    @Test
    fun `a bundle that is only a readable directory tree still yields nothing`() {
        val missing = File(tmp.root, "no-runtime-at-all")
        val env = SpawnEnv.augment(base, missing)

        assertEquals(base, env)
    }

    // ── augment：谁说话算数 ────────────────────────────────────────────────────

    @Test
    fun `an existing key wins: this layer only fills holes, never overrides`() {
        // EnvFactory 是这张表的权威源（且在本期属冻结区）。若它哪天自己开始发这两个键，
        // 本层必须闭嘴 —— 两条通路抢一个键比少一个键难查得多。
        val runtime = runtimeWith(bundleBytes = 128L)
        val already = base + mapOf(
            SpawnEnv.KEY_CAINFO to "/authority/bundle.crt",
            SpawnEnv.KEY_SHELL_PATH to "/authority/sh",
        )

        val env = SpawnEnv.augment(already, runtime)

        assertEquals(4, env.size)
        assertEquals("/authority/bundle.crt", env[SpawnEnv.KEY_CAINFO])
        assertEquals("/authority/sh", env[SpawnEnv.KEY_SHELL_PATH])
    }

    // ── build：接上真实的 EnvFactory 与 PathLayout ─────────────────────────────

    @Test
    fun `build keeps all eleven factory keys and appends the two git ones`() {
        val app = ctx
        val libDir = "/data/app/works.earendil.pihost-ABC==/lib/arm64"
        app.applicationInfo.nativeLibraryDir = libDir
        val runtime = PathLayout.runtimeDir(app)
        assertTrue(runtime.absolutePath, runtime.deleteRecursively() || !runtime.exists())
        File(runtime, "bin").mkdirs()
        File(runtime, "ca-bundle.crt").writeBytes(ByteArray(96))
        File(runtime, "bin/sh").writeBytes(ByteArray(16))

        val env = SpawnEnv.build(app)

        assertEquals(13, env.size)
        // 打底那 11 键的形状不许被这层改动（抽查三条最容易碰的）。
        val files = app.filesDir.absolutePath
        assertEquals("$files/runtime/bin:$libDir:/system/bin", env["PATH"])
        assertEquals("$files/runtime/ca-bundle.crt", env["SSL_CERT_FILE"])
        assertEquals("$files/runtime/libexec", env["GIT_EXEC_PATH"])
        // 新增两条：都指到自己的 runtime 下。
        assertEquals(File(runtime, "ca-bundle.crt").absolutePath, env[SpawnEnv.KEY_CAINFO])
        assertEquals(File(runtime, "bin/sh").absolutePath, env[SpawnEnv.KEY_SHELL_PATH])
    }

    @Test
    fun `build before the runtime is unpacked yields exactly the eleven keys`() {
        val app = ctx
        app.applicationInfo.nativeLibraryDir = "/lib/arm64"
        val runtime = PathLayout.runtimeDir(app)
        assertTrue(runtime.absolutePath, runtime.deleteRecursively() || !runtime.exists())

        val env = SpawnEnv.build(app)

        assertEquals(11, env.size)
        assertFalse(env.containsKey(SpawnEnv.KEY_CAINFO))
        assertFalse(env.containsKey(SpawnEnv.KEY_SHELL_PATH))
    }

    private fun runtimeWithShOnly(bin: File) {
        bin.mkdirs()
        File(bin, "sh").writeBytes(ByteArray(8))
    }
}
