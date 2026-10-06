package works.earendil.pihost.core.environment

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvFactoryTest {

    private val ctx: Context
        get() = RuntimeEnvironment.getApplication()

    private fun withNativeLibDir(dir: String): Context {
        val app = RuntimeEnvironment.getApplication()
        // Robolectric 的 applicationInfo 是可写字段，用它伪造 APK 解出的 native 目录
        app.applicationInfo.nativeLibraryDir = dir
        return app
    }

    @Test
    fun `build emits exactly the eleven spawn keys with exact values`() {
        val libDir = "/data/app/works.earendil.pihost-ABC123==/lib/arm64"
        val context = withNativeLibDir(libDir)
        val files = context.filesDir.absolutePath
        val env = EnvFactory.build(context)

        val expected = mapOf(
            "PATH" to "$files/runtime/bin:$libDir:/system/bin",
            "HOME" to "$files/home",
            "TMPDIR" to context.cacheDir.absolutePath,
            "NODE_PATH" to "$files/runtime/pi/node_modules",
            "NODE_OPTIONS" to "--max-old-space-size=192",
            "PI_OFFLINE" to "1",
            "PI_SKIP_VERSION_CHECK" to "1",
            "GIT_EXEC_PATH" to "$files/runtime/libexec",
            "SHELL" to "$files/runtime/bin/bash",
            "TERM" to "xterm-256color",
            "SSL_CERT_FILE" to "$files/runtime/ca-bundle.crt",
        )

        assertEquals(11, env.size)
        assertEquals(expected, env)
    }

    @Test
    fun `three mem tiers map to node old-space options`() {
        assertEquals("--max-old-space-size=128", MemTier.LOW.nodeOptions)
        assertEquals("--max-old-space-size=192", MemTier.MID.nodeOptions)
        assertEquals("--max-old-space-size=256", MemTier.HIGH.nodeOptions)
        assertSame(MemTier.MID, MemTier.DEFAULT)

        val context = withNativeLibDir("/lib/arm64")
        assertEquals("--max-old-space-size=128", EnvFactory.build(context, MemTier.LOW)["NODE_OPTIONS"])
        assertEquals("--max-old-space-size=256", EnvFactory.build(context, MemTier.HIGH)["NODE_OPTIONS"])
        // 默认参数即 MID
        assertEquals(
            EnvFactory.build(context, MemTier.MID),
            EnvFactory.build(context),
        )
    }
}
