package works.earendil.pihost.core.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * JvmProcessFactory 冒烟：真实子进程的 stdio 三通路 + 退出码 + destroy 语义。
 * 用本机 sh/node 均可；选 POSIX sh 保证桌面环境可用。
 */
class JvmProcessTest {

    private val sh = if (File("/bin/sh").exists()) "/bin/sh" else "sh"

    @Test
    fun `stdin to stdout roundtrip and exit code`() {
        val tmp = Files.createTempDirectory("pihost-proc").toFile()
        try {
            val p = JvmProcessFactory.start(
                command = listOf(sh, "-c", "cat; echo err 1>&2; exit 7"),
                env = mapOf("PIHOST_TEST_VAR" to "yes"),
                workingDir = tmp.absolutePath,
            )
            assertTrue(p.isAlive())

            p.stdin.write("hello\n".toByteArray())
            p.stdin.flush()
            p.stdin.close() // cat 收到 EOF 才结束

            assertEquals(7, p.waitFor())
            assertFalse(p.isAlive())

            val out = p.stdout.readBytes().decodeToString()
            assertEquals("hello\n", out)
            val err = p.stderr.readBytes().decodeToString()
            assertEquals("err\n", err)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `env vars reach child process`() {
        val p = JvmProcessFactory.start(
            command = listOf(sh, "-c", "printf %s \"\$PIHOST_TEST_VAR\""),
            env = mapOf("PIHOST_TEST_VAR" to "yes"),
            workingDir = null,
        )
        p.stdin.close()
        assertEquals(0, p.waitFor())
        assertEquals("yes", p.stdout.readBytes().decodeToString())
    }

    @Test
    fun `destroy kills long-running child and is idempotent`() {
        val p = JvmProcessFactory.start(
            command = listOf(sh, "-c", "sleep 60"),
            env = emptyMap(),
            workingDir = null,
        )
        assertTrue(p.isAlive())
        p.destroy()
        p.waitFor()
        assertFalse(p.isAlive())
        // 幂等：已退出进程再 destroy 无副作用、不抛
        p.destroy()
    }
}
