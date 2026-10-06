package works.earendil.pihost.core.engine

import android.content.Context
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssetInstallerTest {

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

    /** 从临时目录读资产，顺便计数 openRuntime——版本命中时必须是 0。 */
    private class FakeAssetSource(
        private val manifestFile: File,
        private val zipFile: File,
    ) : AssetSource {
        var manifestOpens = 0
            private set
        var runtimeOpens = 0
            private set

        override fun openManifest(): InputStream {
            manifestOpens++
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

    private fun makeManifest(
        version: String,
        files: Map<String, String>,
        shaOverrides: Map<String, String> = emptyMap(),
        symlinks: Map<String, String> = emptyMap(),
    ): File {
        val fileJson = files.entries.joinToString(",") { (path, content) ->
            """{"path":"$path","sha256":"${shaOverrides[path] ?: sha256Hex(content)}"}"""
        }
        val linkJson = symlinks.entries.joinToString(",") { (path, target) ->
            """{"path":"$path","target":"$target"}"""
        }
        val manifest = File(folder.root, "manifest-$version-${System.nanoTime()}.json")
        // generator 字段是清单里的未知键，用来验证 ignoreUnknownKeys
        manifest.writeText(
            """{"runtimeVersion":"$version","files":[$fileJson],"symlinks":[$linkJson],"generator":"ci-job-b"}""",
        )
        return manifest
    }

    @Test
    fun `same installed version short-circuits without opening the archive`() {
        InstalledRuntime.write(ctx, "1.0.0")
        val files = mapOf("bin/node" to "ELF-node")
        val source = FakeAssetSource(makeManifest("1.0.0", files), makeZip(files))

        val result = runBlocking { AssetInstaller(source).ensureInstalled(ctx) }

        assertEquals(InstallResult.UpToDate("1.0.0"), result)
        assertEquals("manifest 必读", 1, source.manifestOpens)
        assertEquals("版本命中时绝不能打开 zip", 0, source.runtimeOpens)
    }

    @Test
    fun `fresh install lays out runtime plus marker and leaves no staging dir`() {
        val files = linkedMapOf(
            "pi/dist/bundle/cli.js" to "// pi rpc bundle\n",
            "pi/node_modules/index.js" to "modules\n",
            "bin/node" to "ELF-node",
            "ca-bundle.crt" to "PEM-BLOB",
        )
        val source = FakeAssetSource(makeManifest("1.0.0", files), makeZip(files))

        val result = runBlocking { AssetInstaller(source).ensureInstalled(ctx) }

        assertEquals(InstallResult.Installed("1.0.0"), result)
        files.forEach { (path, content) ->
            assertEquals(content, File(PathLayout.runtimeDir(ctx), path).readText())
        }
        assertEquals("1.0.0", InstalledRuntime.version(ctx))
        assertEquals(
            "1.0.0",
            PathLayout.installedMarker(ctx).readText().let {
                PiAssetJson.decodeFromString<InstalledMarker>(it).runtimeVersion
            },
        )
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
        assertFalse(PathLayout.runtimeOldDir(ctx).exists())
        assertEquals(1, source.runtimeOpens)
    }

    @Test
    fun `sha256 mismatch fails and keeps the previous runtime intact`() {
        val runtime = PathLayout.runtimeDir(ctx)
        File(runtime, "bin").mkdirs()
        File(runtime, "bin/node").writeText("OLD-NODE")
        InstalledRuntime.write(ctx, "1.0.0")

        val files = mapOf("bin/node" to "NEW-NODE")
        val source = FakeAssetSource(
            makeManifest("2.0.0", files, shaOverrides = mapOf("bin/node" to "0".repeat(64))),
            makeZip(files),
        )

        val result = runBlocking { AssetInstaller(source).ensureInstalled(ctx) }

        assertTrue("期望 Failed，实际 $result", result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).message.contains("sha256 mismatch"))
        assertEquals("OLD-NODE", File(runtime, "bin/node").readText())
        assertEquals("1.0.0", InstalledRuntime.version(ctx))
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
    }

    @Test
    fun `ensureInstalled is idempotent`() {
        val files = mapOf("pi/dist/bundle/cli.js" to "bundle")
        val source = FakeAssetSource(makeManifest("1.0.0", files), makeZip(files))
        val installer = AssetInstaller(source)

        assertEquals(InstallResult.Installed("1.0.0"), runBlocking { installer.ensureInstalled(ctx) })
        assertEquals(InstallResult.UpToDate("1.0.0"), runBlocking { installer.ensureInstalled(ctx) })

        assertEquals(1, source.runtimeOpens)
        assertEquals(2, source.manifestOpens)
        assertEquals("bundle", File(PathLayout.runtimeDir(ctx), "pi/dist/bundle/cli.js").readText())
        assertFalse(PathLayout.runtimeTmpDir(ctx).exists())
    }

    @Test
    fun `symlink farm links runtime bin bash into nativeLibraryDir`() {
        val libDir = folder.newFolder("native-libs")
        File(libDir, "libpi_bash.so").writeText("ELF-bash")
        ctx.applicationInfo.nativeLibraryDir = libDir.absolutePath

        val files = mapOf("pi/dist/bundle/cli.js" to "bundle")
        val manifest = makeManifest("1.0.0", files, symlinks = mapOf("bin/bash" to "libpi_bash.so"))
        val source = FakeAssetSource(manifest, makeZip(files))

        assertEquals(InstallResult.Installed("1.0.0"), runBlocking { AssetInstaller(source).ensureInstalled(ctx) })

        val bash = File(PathLayout.runtimeDir(ctx), "bin/bash").toPath()
        assertTrue("runtime/bin/bash 应为 symlink", Files.isSymbolicLink(bash))
        assertEquals(File(libDir, "libpi_bash.so").toPath(), Files.readSymbolicLink(bash))
        assertEquals("ELF-bash", File(PathLayout.runtimeDir(ctx), "bin/bash").readText())
    }
}
