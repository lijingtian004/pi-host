package works.earendil.pihost.core.engine

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import works.earendil.pihost.core.environment.PathLayout
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * P2B 出口判据 3：用 job B 的**真实**产物在编译机模拟沙盒跑一遍 AssetInstaller。
 *
 * fixture 不进 git（25M bundle / 107M so 只入脚本），所以这里按环境门控：
 * 找不到 `tools/runtime-packaging/out/assets/runtime/` 就 skip，CI 与干净工作树不受影响。
 * 找得到时验的是真东西：1294 个真实文件的 sha256 gate、原子切换、259 条 symlink 农场、
 * 二次调用命中版本短路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealRuntimeAssetsInstallTest {

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

    /** 从 user.dir 逐级上溯找 job B 产物；也可用 PI_RUNTIME_ASSETS 显式指定。 */
    private fun realAssets(): Pair<File, File>? {
        val dirs = buildList {
            System.getenv("PI_RUNTIME_ASSETS")?.let { add(File(it)) }
            var cwd: File? = File(System.getProperty("user.dir") ?: ".")
            while (cwd != null) {
                add(File(cwd, "tools/runtime-packaging/out/assets/runtime"))
                cwd = cwd.parentFile
            }
        }
        val hit = dirs.firstOrNull { File(it, MANIFEST).isFile && File(it, ZIP).isFile } ?: return null
        return File(hit, MANIFEST) to File(hit, ZIP)
    }

    private fun assumeRealAssets(): Pair<File, File> {
        val pair = realAssets()
        assumeTrue(
            "跳过：未找到 job B 真实产物（跑 tools/runtime-packaging/build-pi-runtime.sh 生成）",
            pair != null,
        )
        return pair!!
    }

    private class FileAssets(
        private val manifestFile: File,
        private val zipFile: File,
    ) : AssetSource {
        var runtimeOpens = 0
            private set

        override fun openManifest(): InputStream = FileInputStream(manifestFile)

        override fun openRuntime(): InputStream {
            runtimeOpens++
            return FileInputStream(zipFile)
        }
    }

    @Test
    fun `real bundle installs, verifies every sha256 and links the whole ELF farm`() {
        val (manifestFile, zipFile) = assumeRealAssets()
        val manifest = PiAssetJson.decodeFromString<RuntimeManifest>(manifestFile.readText())
        assertTrue("真实清单文件数应超过 1000", manifest.files.size > 1_000)
        assertTrue("真实清单必须声明 symlink 农场", manifest.symlinks.isNotEmpty())

        // nativeLibraryDir 替身：job A 的每个 libpi_*.so 在这里以同名桩文件出现
        val libDir = folder.newFolder("nativeLibraryDir")
        manifest.symlinks.forEach { File(libDir, it.target).writeText("ELF-stub:${it.target}") }
        ctx.applicationInfo.nativeLibraryDir = libDir.absolutePath

        val source = FileAssets(manifestFile, zipFile)
        val result = runBlocking { AssetInstaller(source).ensureInstalled(ctx) }
        assertEquals(InstallResult.Installed(manifest.runtimeVersion), result)
        assertEquals(1, source.runtimeOpens)

        val runtime = PathLayout.runtimeDir(ctx)

        // 真实 pi bundle 落地（spawn 链的 argv[1]）
        val cli = File(runtime, "pi/dist/bundle/cli.js")
        assertTrue("缺 pi/dist/bundle/cli.js", cli.isFile)
        assertTrue("cli.js 应是 node 入口", cli.readText().startsWith("#!/usr/bin/env node"))
        assertTrue(File(runtime, "pi/dist/bundle/rpc-entry.js").isFile)
        assertTrue(File(runtime, "ca-bundle.crt").readText().contains("BEGIN CERTIFICATE"))

        // 清单里每个 path 的 sha256 都已由安装器逐文件核对过，这里再抽查体积
        val chunk = manifest.files.first { it.path.startsWith("pi/dist/bundle/chunks/") }
        assertTrue(File(runtime, chunk.path).isFile)

        // symlink 农场：每条都真是指向 nativeLibraryDir/<target> 的软链，且可穿透读到桩内容
        manifest.symlinks.forEach { link ->
            val path = File(runtime, link.path).toPath()
            assertTrue("${link.path} 应为 symlink", Files.isSymbolicLink(path))
            assertEquals(File(libDir, link.target).toPath(), Files.readSymbolicLink(path))
            assertEquals("ELF-stub:${link.target}", File(runtime, link.path).readText())
        }
        // spawn 链用到的三条关键链接（方案 §4.4）
        assertEquals("ELF-stub:libpi_bash.so", File(runtime, "bin/bash").readText())
        assertEquals("ELF-stub:libpi_git.so", File(runtime, "bin/git").readText())
        assertEquals("ELF-stub:libpi_git.so", File(runtime, "libexec/git-add").readText())

        // 目录残留：tmp/old 都不能存在
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
        assertFalse(PathLayout.runtimeOldDir(ctx).exists())
        assertEquals(manifest.runtimeVersion, InstalledRuntime.version(ctx))

        // 二次调用：版本命中，连 zip 都不打开
        val again = runBlocking { AssetInstaller(source).ensureInstalled(ctx) }
        assertEquals(InstallResult.UpToDate(manifest.runtimeVersion), again)
        assertEquals(1, source.runtimeOpens)
    }

    @Test
    fun `real archive carries only regular files and matches the manifest exactly`() {
        val (manifestFile, zipFile) = assumeRealAssets()
        val manifest = PiAssetJson.decodeFromString<RuntimeManifest>(manifestFile.readText())
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            assertEquals("zip 条目数应与 manifest.files 一致", manifest.files.size, entries.size)
            val declared = manifest.files.map { it.path }.toSet()
            val times = mutableSetOf<Long>()
            entries.forEach { entry ->
                assertTrue("zip 里有多余条目 ${entry.name}", declared.contains(entry.name))
                assertFalse("runtime 路径不得是绝对路径", entry.name.startsWith("/"))
                assertFalse("runtime 路径不得含 ..", entry.name.split('/').contains(".."))
                assertFalse("zip 内不放目录条目以外的东西：${entry.name}", entry.name.endsWith("/"))
                times += entry.time
            }
            // 打包脚本约定：mtime 钉死（确定性构建）；zip 里不写 symlink 条目，
            // Java ZipInputStream 本来也不还原软链，所以软链一律由 manifest.symlinks 声明。
            assertEquals("zip 条目的 mtime 必须唯一（固定时间戳）", 1, times.size)
        }
        manifest.symlinks.forEach {
            assertTrue("${it.target} 必须是 jniLibs 文件名", it.target.startsWith("libpi_") && it.target.endsWith(".so"))
        }
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val ZIP = "pi-runtime.zip"
    }
}
