package works.earendil.pihost.core.environment

import android.content.Context

/**
 * spawn node 子进程时的 env 表（方案 §4.4，逐键照设计文档 §6 `buildEnv`）。
 *
 * 11 个键，一个不多一个不少：runtime 一切路径以 `filesDir/runtime` 为根，
 * 原生可执行体（node/bash/git 及 libexec）都在 `applicationInfo.nativeLibraryDir`。
 */
object EnvFactory {

    fun build(ctx: Context, memTier: MemTier = MemTier.DEFAULT): Map<String, String> {
        val libDir = ctx.applicationInfo.nativeLibraryDir!!
        val runtime = PathLayout.runtimeDir(ctx).absolutePath
        val home = PathLayout.homeDir(ctx).absolutePath

        return buildMap {
            put("PATH", "$runtime/bin:$libDir:/system/bin")
            put("HOME", home)
            put("TMPDIR", ctx.cacheDir.absolutePath)
            put("NODE_PATH", "$runtime/pi/node_modules")
            put("NODE_OPTIONS", memTier.nodeOptions)
            put("PI_OFFLINE", "1")
            put("PI_SKIP_VERSION_CHECK", "1")
            put("GIT_EXEC_PATH", "$runtime/libexec")
            put("SHELL", "$runtime/bin/bash")
            put("TERM", "xterm-256color")
            put("SSL_CERT_FILE", "$runtime/ca-bundle.crt")
        }
    }
}
