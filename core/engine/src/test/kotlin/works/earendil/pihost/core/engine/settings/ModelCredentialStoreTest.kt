package works.earendil.pihost.core.engine.settings

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.Companion.AGENT_DIR_NAME
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.Companion.AUTH_FILE_NAME
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.Companion.KNOWN_PROVIDERS
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.Companion.PI_DIR_NAME
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.Companion.TMP_PREFIX
import works.earendil.pihost.core.engine.settings.ModelCredentialStore.ProviderEntry

/**
 * `ModelCredentialStore`：规格书 §4.1 的 ①–⑧ 逐条覆盖，另加 ⑨–⑮ 把红线「保留未知字段」、
 * 两条失败路径与 10 项清单钉住（门槛 ≥8，实测 15 条）。
 *
 * 纯 JVM：所有读写都落在 [TemporaryFolder] 里，**绝不碰设备路径常量**
 * （P2F §7.1 的教训：`/data/user/0/...` 会被 root 身份的编译机真建出来，还会让测试类互相踩）。
 * 断言一律对**文件原文**或**解析后的结构**下手，不用 store 自己的读函数当唯一真相（01 就是逐字比形状）。
 */
class ModelCredentialStoreTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var homeDir: File
    private lateinit var store: ModelCredentialStore

    private fun newStore(): ModelCredentialStore = newStore(File(tmpFolder.root, "home-${System.nanoTime()}"))

    private fun newStore(home: File): ModelCredentialStore {
        homeDir = home
        store = ModelCredentialStore(home)
        return store
    }

    /** 解析文件原文（损坏时抛——正好充当「写出去的是不是合法 JSON」的门）。 */
    private fun readTree(): JsonObject = Json.parseToJsonElement(store.authFile.readText()).jsonObject

    private fun agentDir(): File = File(File(homeDir, PI_DIR_NAME), AGENT_DIR_NAME)

    /** 直接喂一份手写 auth.json（测试侧唯一一处拼 JSON 的地方，用写文件绕开 store 的写路径）。 */
    private fun seedAgentFile(content: String) {
        agentDir().mkdirs()
        store.authFile.writeText(content)
    }

    // ── ① save 后文件形状精确，且 tmp 不残留 ──────────────────────────────────────

    @Test
    fun `01 save writes exactly the ApiKeyCredential shape and leaves no tmp behind`() {
        newStore()

        store.save("deepseek", "sk-x")

        assertEquals(
            "§4.1 第 ① 条：形状必须逐字等于 spec 里那个对象",
            """{"deepseek":{"type":"api_key","key":"sk-x"}}""",
            store.authFile.readText(),
        )
        val names = agentDir().listFiles()!!.map { it.name }
        assertEquals("agent 目录里只该有 auth.json（tmp 必须被清掉）", listOf(AUTH_FILE_NAME), names)
        assertEquals(emptyList<File>(), names.filter { it.startsWith(TMP_PREFIX) })
    }

    /** authFile 的三段拼法必须与 pi 的 `join(homedir(), ".pi", "agent", "auth.json")` 落在同一路径。 */
    @Test
    fun `02 authFile resolves to homeDir dot-pi agent auth-json`() {
        newStore()

        val expected = File(File(File(homeDir, ".pi"), "agent"), "auth.json")
        assertEquals(expected, store.authFile)
        assertEquals(expected.absolutePath, store.authFile.absolutePath)
        assertEquals(PI_DIR_NAME, ".pi")
        assertEquals(AGENT_DIR_NAME, "agent")
    }

    // ── ② 两个 provider 共存 ──────────────────────────────────────────────────────

    @Test
    fun `03 two providers coexist after two saves`() {
        newStore()

        store.save("deepseek", "sk-deep")
        store.save("anthropic", "sk-ant")

        val tree = readTree()
        assertEquals(setOf("deepseek", "anthropic"), tree.keys)
        assertEquals("sk-deep", tree.getValue("deepseek").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("sk-ant", tree.getValue("anthropic").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(
            "读表顺序 = 文件里的插入序（UI 列表要稳定）",
            listOf(ProviderEntry("deepseek", true), ProviderEntry("anthropic", true)),
            store.readConfigured(),
        )
    }

    // ── ③ 同 provider 再 save 只更新 key，不多出一条 ──────────────────────────────

    @Test
    fun `04 re-saving the same provider replaces the key and adds no extra entry`() {
        newStore()

        store.save("openai", "sk-old")
        store.save("openai", "sk-new")

        val tree = readTree()
        assertEquals(setOf("openai"), tree.keys)
        assertEquals("sk-new", tree.getValue("openai").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(1, store.readConfigured().size)
        assertEquals(
            "更新只换 key：条目数仍是 1，字段仍是 type/key 两个",
            """{"openai":{"type":"api_key","key":"sk-new"}}""",
            store.authFile.readText(),
        )
    }

    // ── ④ clear 只删目标、保留他人 ────────────────────────────────────────────────

    @Test
    fun `05 clear removes only the target provider`() {
        newStore()
        store.save("deepseek", "sk-deep")
        store.save("groq", "sk-groq")
        store.save("xai", "sk-xai")

        store.clear("groq")

        val tree = readTree()
        assertFalse("目标必须消失", tree.containsKey("groq"))
        assertEquals(setOf("deepseek", "xai"), tree.keys)
        assertEquals("sk-deep", tree.getValue("deepseek").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("sk-xai", tree.getValue("xai").jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals(
            listOf(ProviderEntry("deepseek", true), ProviderEntry("xai", true)),
            store.readConfigured(),
        )
        assertEquals(0, agentDir().listFiles()!!.count { it.name.startsWith(TMP_PREFIX) })
    }

    /** clear 幂等：文件不存在 / 条目不存在都不该凭空写出一个 `{}`。 */
    @Test
    fun `06 clear is a no-op when the file or the entry is absent`() {
        newStore()

        store.clear("deepseek")
        assertFalse("没有 auth.json 时 clear 不许创建它", store.authFile.exists())

        store.save("deepseek", "sk-x")
        store.clear("anthropic")
        assertEquals("""{"deepseek":{"type":"api_key","key":"sk-x"}}""", store.authFile.readText())
    }

    // ── ⑤ 损坏 JSON 上 save → 覆盖成合法对象 ──────────────────────────────────────

    @Test
    fun `07 saving over a corrupted file replaces it with a valid object`() {
        newStore()
        seedAgentFile("{ this is not json ]")

        store.save("deepseek", "sk-x")

        assertEquals("""{"deepseek":{"type":"api_key","key":"sk-x"}}""", store.authFile.readText())
        assertEquals(listOf(ProviderEntry("deepseek", true)), store.readConfigured())
    }

    /** 顶层不是对象（数组）同样算损坏，别把它当 provider 表读。 */
    @Test
    fun `08 a non-object top level reads as empty and is replaceable`() {
        newStore()
        seedAgentFile("[1,2,3]")

        assertEquals(emptyList<ProviderEntry>(), store.readConfigured())
        store.save("zai", "sk-z")
        assertEquals(setOf("zai"), readTree().keys)
    }

    /** 空文件（pi 首次 spawn 前科，或写坏了）→ 空表；save 直接盖成合法对象。 */
    @Test
    fun `09 an empty file reads as empty and saves cleanly`() {
        newStore()
        seedAgentFile("")

        assertEquals(emptyList<ProviderEntry>(), store.readConfigured())
        store.save("groq", "sk-g")
        assertEquals("""{"groq":{"type":"api_key","key":"sk-g"}}""", store.authFile.readText())
    }

    // ── ⑥ save 自动建父目录 ───────────────────────────────────────────────────────

    @Test
    fun `10 save creates the whole dot-pi agent directory chain`() {
        val home = File(tmpFolder.root, "fresh-home")
        assertFalse(home.exists())

        val s = ModelCredentialStore(home)
        s.save("deepseek", "sk-x")

        assertTrue("home 目录必须被建出来", home.isDirectory)
        assertTrue(File(home, ".pi").isDirectory)
        assertTrue(File(File(home, ".pi"), "agent").isDirectory)
        assertTrue(s.authFile.isFile)
        assertEquals(listOf(ProviderEntry("deepseek", true)), s.readConfigured())
    }

    /** 目录链被一个**普通文件**挡住 → 抛 IOException 且不产生 auth.json / tmp（UI 失败提示的根因）。 */
    @Test
    fun `11 save fails loudly when the directory chain is blocked by a regular file`() {
        val blockedHome = File(tmpFolder.root, "blocked-home")
        blockedHome.mkdirs()
        File(blockedHome, ".pi").writeText("我不是目录")

        val s = ModelCredentialStore(blockedHome)
        val boom = runCatching { s.save("deepseek", "sk-x") }.exceptionOrNull()
        assertTrue("期望 IOException，实际 $boom", boom is IOException)
        assertTrue("消息要点出建不成的目录", boom!!.message!!.contains(File(blockedHome, ".pi").path))
        assertFalse("失败路径不许留 auth.json", s.authFile.exists())
        assertEquals("失败路径不许留 tmp", emptyList<File>(), blockedHome.listFiles()!!.filter { it.name.startsWith(TMP_PREFIX) })
    }

    // ── ⑦ blank key / blank providerId 抛 IAE，且不写盘 ───────────────────────────

    @Test
    fun `12 blank arguments are rejected without touching the file`() {
        newStore()

        for (key in listOf("", " ", "\t\n")) {
            assertIsIAE("blank apiKey（<$key>）") { store.save("deepseek", key) }
        }
        for (provider in listOf("", "  ")) {
            assertIsIAE("blank providerId（<$provider>）") { store.save(provider, "sk-x") }
            assertIsIAE("blank providerId（<$provider>）的 clear") { store.clear(provider) }
        }
        assertFalse("任何一次拒绝都不该留下 auth.json", store.authFile.exists())
    }

    private fun assertIsIAE(what: String, block: () -> Unit) {
        val boom = runCatching(block).exceptionOrNull()
        assertTrue("$what 应抛 IllegalArgumentException，实际 $boom", boom is IllegalArgumentException)
    }

    // ── ⑧ readConfigured 不回显 key 明文 + 「已配置」判据 ──────────────────────────

    @Test
    fun `13 readConfigured never leaks the key and requires a non-blank api_key`() {
        newStore()
        val secret = "sk-super-secret-value"
        seedAgentFile(
            """{"deepseek":{"type":"api_key","key":"$secret"},"anthropic":{"type":"oauth","access":"a-token","refresh":"r-token","expires":1700000000000},"openai":{"type":"api_key"},"groq":{"type":"api_key","key":"   "},"note":"顶层不是对象的垃圾"}""",
        )

        val rows = store.readConfigured()
        val dump = rows.toString()
        assertFalse("结果里不许出现 api_key 明文：$dump", dump.contains(secret))
        assertFalse("也不许出现 oauth 的 access/refresh：$dump", dump.contains("a-token") || dump.contains("r-token"))
        assertEquals(
            "对象条目按文件顺序进表；type 不是 api_key / key 缺失 / key 空白 都算未配置；垃圾键不进表",
            listOf(
                ProviderEntry("deepseek", true),
                ProviderEntry("anthropic", false),
                ProviderEntry("openai", false),
                ProviderEntry("groq", false),
            ),
            rows,
        )
        assertEquals("ProviderEntry 只有两个字段，没地方藏密钥", 2, ProviderEntry::class.java.declaredFields.size)
    }

    // ── ⑨ 读-改-写保留未知字段（§2 红线「保留其他 provider 条目与未知字段」）────────

    @Test
    fun `14 read-modify-write preserves other providers and unknown fields`() {
        newStore()
        seedAgentFile(
            """{"anthropic":{"type":"oauth","access":"a-token","expires":1700000000000,"nested":{"a":[1,{"b":2}]}},"openai":{"type":"api_key","key":"sk-keep","extra":{"future":"field"}},"note":"顶层垃圾也留着","count":7,"flag":true,"nil":null}""",
        )

        store.save("deepseek", "sk-new")
        store.clear("openai")

        val expected =
            """{"anthropic":{"type":"oauth","access":"a-token","expires":1700000000000,"nested":{"a":[1,{"b":2}]}},"note":"顶层垃圾也留着","count":7,"flag":true,"nil":null,"deepseek":{"type":"api_key","key":"sk-new"}}"""
        assertEquals(
            "除目标条目外，其余键与字段必须逐字等价（比结构，不依赖键序）",
            Json.parseToJsonElement(expected).jsonObject,
            readTree(),
        )
        assertFalse(readTree().containsKey("openai"))
    }

    /** 未知字段在 clear 路径上同样要留住（红线对 save/clear 都成立）。 */
    @Test
    fun `15 clear keeps unknown top level keys`() {
        newStore()
        seedAgentFile("""{"deepseek":{"type":"api_key","key":"sk-x"},"modelsCache":{"x":1},"comment":"留我"}""")

        store.clear("deepseek")

        assertEquals("""{"modelsCache":{"x":1},"comment":"留我"}""", store.authFile.readText())
    }

    // ── 10 项固定清单（§3 定案）──────────────────────────────────────────────────

    @Test
    fun `16 the provider list is exactly the ten ids and display names from section 3`() {
        assertEquals(10, KNOWN_PROVIDERS.size)
        assertEquals(
            listOf(
                "deepseek" to "DeepSeek",
                "anthropic" to "Anthropic",
                "openai" to "OpenAI",
                "openrouter" to "OpenRouter",
                "zai" to "Z.AI",
                "groq" to "Groq",
                "xai" to "xAI",
                "moonshotai-cn" to "Moonshot（国内）",
                "minimax-cn" to "MiniMax（国内）",
                "kimi-coding" to "Kimi",
            ),
            KNOWN_PROVIDERS.map { it.providerId to it.displayName },
        )
        assertEquals("DeepSeek", ModelCredentialStore.displayNameOf("deepseek"))
        assertEquals("清单外的 id 原样回显，不编造名字", "somegate", ModelCredentialStore.displayNameOf("somegate"))
        assertEquals("id 不许重复", KNOWN_PROVIDERS.size, KNOWN_PROVIDERS.map { it.providerId }.distinct().size)
    }

    /** 空表起点：文件不存在时 readConfigured 给空表而不是抛（进屏首帧的分支）。 */
    @Test
    fun `17 readConfigured on a missing file is empty`() {
        newStore()

        assertFalse(store.authFile.exists())
        assertEquals(emptyList<ProviderEntry>(), store.readConfigured())
        assertNull("未写入前不该有文件", store.authFile.takeIf { it.exists() })
    }
}
