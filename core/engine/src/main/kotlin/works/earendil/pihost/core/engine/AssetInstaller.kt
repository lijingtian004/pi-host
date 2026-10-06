package works.earendil.pihost.core.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import works.earendil.pihost.core.environment.PathLayout
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** 首启进度 UI 的状态（方案 §5.1 / §3.3）。 */
sealed interface InstallProgress {
    data object Idle : InstallProgress

    /** [total] 取 manifest.files 数；[done] 是实际解出的文件条目数。 */
    data class Extracting(val done: Int, val total: Int) : InstallProgress
    data class Verifying(val done: Int, val total: Int) : InstallProgress
    data object Finalizing : InstallProgress
    data object Done : InstallProgress
    data class Failed(val message: String) : InstallProgress
}

/** [AssetInstaller.ensureInstalled] 的结果。 */
sealed interface InstallResult {
    /** 版本一致，未打开 zip。 */
    data class UpToDate(val version: String) : InstallResult

    /** 全绿并完成原子切换。 */
    data class Installed(val version: String) : InstallResult

    /** 失败：tmp 已清理，旧 runtime 原样保留。 */
    data class Failed(val message: String) : InstallResult
}

/**
 * 运行时资产安装器（方案 §5.1）：解包 → sha256 校验 → 原子切换 → 写标记 → symlink 农场。
 * 任一环节失败都不允许半成品成为运行时。
 */
class AssetInstaller(private val source: AssetSource) {

    private val state = MutableStateFlow<InstallProgress>(InstallProgress.Idle)

    /** 解包/校验进度，驱动首启进度 UI。 */
    val progress: StateFlow<InstallProgress> = state.asStateFlow()

    suspend fun ensureInstalled(ctx: Context): InstallResult = withContext(Dispatchers.IO) {
        val runtime = PathLayout.runtimeDir(ctx)
        val tmp = PathLayout.runtimeTmpDir(ctx)
        try {
            val manifest = readManifest()

            // b) 版本一致 → UpToDate，连 zip 都不打开
            if (InstalledRuntime.version(ctx) == manifest.runtimeVersion) {
                state.value = InstallProgress.Done
                return@withContext InstallResult.UpToDate(manifest.runtimeVersion)
            }

            val total = manifest.files.size

            // c) 清空暂存区
            tmp.deleteRecursively()
            if (!tmp.mkdirs() && !tmp.isDirectory) {
                throw IOException("cannot create staging dir ${tmp.absolutePath}")
            }

            // d) 流式解包（含 zip-slip 防护）
            state.value = InstallProgress.Extracting(0, total)
            var extracted = 0
            val buf = ByteArray(BUFFER_SIZE)
            source.openRuntime().use { raw ->
                ZipInputStream(BufferedInputStream(raw, BUFFER_SIZE)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val target = safeResolve(tmp, entry.name)
                        if (entry.isDirectory) {
                            if (!target.mkdirs() && !target.isDirectory) {
                                throw IOException("cannot create dir ${target.absolutePath}")
                            }
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                var read = zip.read(buf)
                                while (read >= 0) {
                                    out.write(buf, 0, read)
                                    read = zip.read(buf)
                                }
                            }
                            extracted++
                            state.value = InstallProgress.Extracting(extracted, total)
                        }
                        zip.closeEntry()
                    }
                }
            }

            // e) 逐文件 sha256 对表
            state.value = InstallProgress.Verifying(0, total)
            manifest.files.forEachIndexed { index, file ->
                val staged = safeResolve(tmp, file.path)
                if (!staged.isFile) {
                    throw IOException("runtime file missing from archive: ${file.path}")
                }
                val actual = sha256HexOf(staged)
                if (!actual.equals(file.sha256.lowercase(), ignoreCase = true)) {
                    throw IOException(
                        "sha256 mismatch for ${file.path}: expected ${file.sha256}, actual $actual",
                    )
                }
                state.value = InstallProgress.Verifying(index + 1, total)
            }

            // f) 原子切换：runtime → runtime.old，runtime.tmp → runtime，删 runtime.old
            state.value = InstallProgress.Finalizing
            val stale = PathLayout.runtimeOldDir(ctx)
            stale.deleteRecursively()
            val hadRuntime = runtime.exists()
            if (hadRuntime && !runtime.renameTo(stale)) {
                throw IOException("cannot park old runtime ${runtime.absolutePath} into ${stale.absolutePath}")
            }
            if (!tmp.renameTo(runtime)) {
                if (hadRuntime) stale.renameTo(runtime)
                throw IOException("cannot promote ${tmp.absolutePath} to ${runtime.absolutePath}")
            }
            stale.deleteRecursively()

            // g) 安装标记
            InstalledRuntime.write(ctx, manifest.runtimeVersion)

            // h) symlink 农场
            createSymlinkFarm(ctx, runtime, manifest.symlinks)

            state.value = InstallProgress.Done
            InstallResult.Installed(manifest.runtimeVersion)
        } catch (t: Throwable) {
            // i) 失败：只清暂存区，旧 runtime 原样保留
            val message = t.message ?: t.javaClass.name
            runCatching { tmp.deleteRecursively() }
            state.value = InstallProgress.Failed(message)
            InstallResult.Failed(message)
        }
    }

    private fun readManifest(): RuntimeManifest =
        source.openManifest().use { input ->
            PiAssetJson.decodeFromString(input.readBytes().toString(Charsets.UTF_8))
        }

    /** zip-slip 防护：entry 的 canonical 路径必须落在 [root] 之内。 */
    private fun safeResolve(root: File, name: String): File {
        val candidate = File(root, name)
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
            throw IOException("archive entry escapes runtime dir: $name")
        }
        return candidate
    }

    private fun createSymlinkFarm(ctx: Context, runtime: File, symlinks: List<RuntimeSymlink>) {
        if (symlinks.isEmpty()) return
        val libDir = ctx.applicationInfo.nativeLibraryDir
            ?: throw IOException("applicationInfo.nativeLibraryDir is unavailable")
        for (link in symlinks) {
            val linkFile = safeResolve(runtime, link.path)
            linkFile.parentFile?.mkdirs()
            val linkPath = linkFile.toPath()
            val targetPath = File(libDir, link.target).toPath()
            try {
                // 先删同名链接/残留文件，否则 createSymbolicLink 抛 FileAlreadyExistsException
                Files.deleteIfExists(linkPath)
            } catch (_: NoSuchFileException) {
                // 本来就不存在
            }
            Files.createSymbolicLink(linkPath, targetPath)
        }
    }

    private fun sha256HexOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        /** 方案 §5.1：8MB 缓冲流式读写。 */
        const val BUFFER_SIZE = 8 * 1024 * 1024

        /** 校验阶段读本地已解包文件，64KB 足够，避免为每个文件再分配 8MB。 */
        const val HASH_BUFFER_SIZE = 64 * 1024
    }
}
