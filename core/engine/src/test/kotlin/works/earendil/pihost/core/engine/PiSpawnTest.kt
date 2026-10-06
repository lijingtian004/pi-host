package works.earendil.pihost.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PiSpawn argv 工厂（规格书 §4）。
 *
 * 形状纪律：各 flag 是**独立 argv 元素**（不拼字符串），`--session-id` 带 host 给的 UUID，
 * 且**绝不出现** `--resume`/`--no-session`（§12.1 定案）。
 */
class PiSpawnTest {

    private val libDir = "/data/user/0/works.earendil.pihost/lib"
    private val runtimeDir = "/data/user/0/works.earendil.pihost/files/runtime"
    private val sessionId = "01a10e68-5c3c-72b4-98ac-70e0ce9a55d2"

    private fun command() = PiSpawn(libDir, runtimeDir).command(sessionId)

    @Test
    fun `argv is node plus cli js with rpc mode session id and no extensions`() {
        assertEquals(
            listOf(
                "$libDir/libpi_node.so",
                "$runtimeDir/pi/dist/bundle/cli.js",
                "--mode", "rpc",
                "--session-id", sessionId,
                "--no-extensions",
            ),
            command(),
        )
    }

    @Test
    fun `flags stay separate argv elements in declared order`() {
        val argv = command()
        val modeIndex = argv.indexOf("--mode")
        val sessionIndex = argv.indexOf("--session-id")
        assertTrue("各 flag 独立成元素", modeIndex >= 0 && argv[modeIndex + 1] == "rpc")
        assertTrue("session-id 紧跟其值", argv[sessionIndex + 1] == sessionId)
        assertTrue("flag 顺序 = --mode → --session-id → --no-extensions",
            modeIndex < sessionIndex && sessionIndex < argv.indexOf("--no-extensions"))
        assertFalse("没有元素把 flag 和值拼在一起", argv.any { it.contains('=') || it.contains(" --") })
    }

    @Test
    fun `session id is injected verbatim on every spawn`() {
        val other = "b1b1b1b1-0000-1111-2222-333344445555"
        val argv = PiSpawn(libDir, runtimeDir).command(other)
        assertEquals(other, argv[argv.indexOf("--session-id") + 1])
        assertTrue("恢复靠 --session-id，不用交互选择器", "--resume" !in argv)
        assertTrue("会话要落盘，不能 --no-session", "--no-session" !in argv)
    }

    @Test
    fun `paths come from injected dirs not hardcoded sandbox`() {
        val spawn = PiSpawn("/prefix/lib", "/prefix/files/runtime")
        assertEquals("/prefix/lib/libpi_node.so", spawn.nodeExecutable)
        assertEquals("/prefix/files/runtime/pi/dist/bundle/cli.js", spawn.cliScript)
        assertEquals(spawn.nodeExecutable, spawn.command("x")[0])
        assertEquals(spawn.cliScript, spawn.command("x")[1])
    }
}
