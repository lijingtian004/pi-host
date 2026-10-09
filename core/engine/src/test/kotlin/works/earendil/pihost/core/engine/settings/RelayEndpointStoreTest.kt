package works.earendil.pihost.core.engine.settings

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.AGENT_DIR_NAME
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.DEFAULT_API
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.MODELS_FILE_NAME
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.MODELS_TMP_PREFIX
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.RELAY_APIS
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.SETTINGS_FILE_NAME
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.Companion.SETTINGS_TMP_PREFIX
import works.earendil.pihost.core.engine.settings.RelayEndpointStore.RelayEntry
import works.earendil.pihost.core.engine.settings.RelayModel

/**
 * `RelayEndpointStore`：规格书 §3.1 点名的 ①–⑫ 逐条覆盖（①→01、②→03、③→04、④→05/06、⑤→07、⑥→10、
 * ⑦→12/13/31、⑧→14、⑨→15/16、⑩→18/19、⑪→20/26、⑫→21/22），另加 02/08/09/11/17/23/24/25/27/28/29/30
 * 把两条失败路径、`$ENV_NAME` 原样落盘、默认钉选读口与五项 api 清单钉住（门槛 ≥12）。
 *
 * **P3B-FIX1 §3.1 第 6 条新增 32–37**（思考旗那一面，六条）：32 落盘形状（true 写、false 省略）、
 * 33 write→read 往返 + 手写 `reasoning:true` 读回、34 只认真 `true`（false/字符串/数字/null/对象全 false）、
 * 35 清洗的同 id **取或**、36 全空白 id 清洗后抛且不动文件、37 clear/setDefault 与新字段互不影响。
 * 30 与 ⑥ 两支旧用例按新签名适配后**语义未变**（trim/去空/去重、空清单非法）。
 *
 * 纯 JVM：所有读写都落在 [TemporaryFolder] 派生目录里，**绝不碰设备路径常量**（P2F §7.1 的同一课）。
 * 断言一律对**文件原文**或**解析后的结构**下手，不用 store 自己的读函数当唯一真相
 * （01 逐字比形状，21/18 比的是磁盘上的结构等价）。
 */
class RelayEndpointStoreTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var homeDir: File
    private lateinit var store: RelayEndpointStore

    private fun newStore(): RelayEndpointStore = newStore(File(tmpFolder.root, "home-${System.nanoTime()}"))

    private fun newStore(home: File): RelayEndpointStore {
        homeDir = home
        store = RelayEndpointStore(home)
        return store
    }

    private fun agentDir(): File = File(File(homeDir, ".pi"), "agent")

    /** 解析 models.json 原文（损坏时抛——正好充当「写出去的是不是合法 JSON」的门）。 */
    private fun readModels(): JsonObject = Json.parseToJsonElement(store.modelsFile.readText()).jsonObject

    private fun readSettings(): JsonObject = Json.parseToJsonElement(store.settingsFile.readText()).jsonObject

    /** providers 段（比 [RelayEndpointStore.readRelays] 更靠近磁盘：测试不许用被测读口当唯一真相）。 */
    private fun providersTree(): JsonObject = readModels().getValue("providers").jsonObject

    private fun entryTree(providerId: String): JsonObject = providersTree().getValue(providerId).jsonObject

    private fun seedModels(content: String) {
        agentDir().mkdirs()
        store.modelsFile.writeText(content)
    }

    private fun seedSettings(content: String) {
        agentDir().mkdirs()
        store.settingsFile.writeText(content)
    }

    /** 中转至少要有一个模型 id；测试里最常见的合法入参。 */
    private fun saveRelay(
        providerId: String = "my-relay",
        baseUrl: String = "http://127.0.0.1:18080/v1",
        api: String = "openai-completions",
        models: List<RelayModel> = listOf(RelayModel("test-relay-model")),
        apiKey: String? = "sk-relay-test-123",
    ) = store.save(providerId, baseUrl, api, models, apiKey)

    /** agent 目录里的文件名（排序后），用来断言「没有 tmp 残骸」「只写了该写的那张表」。 */
    private fun agentFileNames(): List<String> = agentDir().listFiles()!!.map { it.name }.sorted()

    // ── ① save 后 models.json 精确形状（含 apiKey、无 tmp 残留）─────────────────────

    @Test
    fun `01 save writes exactly the spec shape and leaves no tmp behind`() {
        newStore()

        saveRelay()

        assertEquals(
            "§3.1 原文形状：providers.<id> = baseUrl/api/apiKey/models[{id}]",
            """{"providers":{"my-relay":{"baseUrl":"http://127.0.0.1:18080/v1","api":"openai-completions","apiKey":"sk-relay-test-123","models":[{"id":"test-relay-model"}]}}}""",
            store.modelsFile.readText(),
        )
        assertEquals("agent 目录里只该有 models.json（tmp 必须被清掉）", listOf(MODELS_FILE_NAME), agentFileNames())
        assertEquals(emptyList<File>(), agentFileNames().filter { it.contains(MODELS_TMP_PREFIX) })
    }

    /** 两张表的路径拼法必须与 pi 的 `join(agentDir,…)` 落在同一目录（§1：settings-manager.ts:294-301）。 */
    @Test
    fun `02 modelsFile and settingsFile resolve inside homeDir dot-pi agent`() {
        newStore()

        assertEquals(File(File(homeDir, ".pi"), "agent"), agentDir())
        assertEquals(File(File(File(homeDir, ".pi"), "agent"), "models.json"), store.modelsFile)
        assertEquals(File(File(File(homeDir, ".pi"), "agent"), "settings.json"), store.settingsFile)
        assertEquals(store.modelsFile.parentFile, store.settingsFile.parentFile)
        assertEquals(AGENT_DIR_NAME, "agent")
        assertEquals(MODELS_FILE_NAME, "models.json")
        assertEquals(SETTINGS_FILE_NAME, "settings.json")
    }

    // ── ② 两个中转共存 ────────────────────────────────────────────────────────────

    @Test
    fun `03 two relays coexist after two saves`() {
        newStore()

        saveRelay(providerId = "relay-a", baseUrl = "https://a.example.com/v1", models = listOf(RelayModel("m-a")))
        saveRelay(
            providerId = "relay-b",
            baseUrl = "https://b.example.com/v1",
            api = "anthropic-messages",
            models = listOf(RelayModel("m-b1"), RelayModel("m-b2")),
        )

        assertEquals(setOf("relay-a", "relay-b"), providersTree().keys)
        assertEquals(
            "读表顺序 = 文件里的插入序（UI 列表要稳定）",
            listOf(
                RelayEntry("relay-a", "https://a.example.com/v1", "openai-completions", listOf(RelayModel("m-a")), true),
                RelayEntry("relay-b", "https://b.example.com/v1", "anthropic-messages", listOf(RelayModel("m-b1"), RelayModel("m-b2")), true),
            ),
            store.readRelays(),
        )
    }

    // ── ③ 同 id 再 save 是更新，不是多出一条 ───────────────────────────────────────

    @Test
    fun `04 re-saving the same id updates the entry and adds no second one`() {
        newStore()
        saveRelay(baseUrl = "https://old.example.com/v1", models = listOf(RelayModel("old-model")))

        saveRelay(baseUrl = "https://new.example.com/v1", models = listOf(RelayModel("new-model"), RelayModel("second")))

        assertEquals(setOf("my-relay"), providersTree().keys)
        assertEquals(1, store.readRelays().size)
        assertEquals(
            "更新只换内容：条目仍是那一个，字段仍是那四个",
            """{"providers":{"my-relay":{"baseUrl":"https://new.example.com/v1","api":"openai-completions","apiKey":"sk-relay-test-123","models":[{"id":"new-model"},{"id":"second"}]}}}""",
            store.modelsFile.readText(),
        )
    }

    // ── ④ apiKey=null 保留旧 key ──────────────────────────────────────────────────

    @Test
    fun `05 null apiKey keeps the key already on disk`() {
        newStore()
        saveRelay(apiKey = "sk-keep-me")

        saveRelay(baseUrl = "https://moved.example.com/v1", models = listOf(RelayModel("other-model")), apiKey = null)

        assertEquals("sk-keep-me", entryTree("my-relay").getValue("apiKey").jsonPrimitive.content)
        assertEquals(
            listOf(RelayEntry("my-relay", "https://moved.example.com/v1", DEFAULT_API, listOf(RelayModel("other-model")), keyConfigured = true)),
            store.readRelays(),
        )
    }

    /** 空白 apiKey 与 null 同义（§3.1「null/blank → 保留已有」），不是「覆盖成空串」。 */
    @Test
    fun `06 blank apiKey keeps the key already on disk too`() {
        newStore()
        saveRelay(apiKey = "sk-keep-me")

        saveRelay(apiKey = "   ")

        assertEquals("sk-keep-me", entryTree("my-relay").getValue("apiKey").jsonPrimitive.content)
    }

    // ── ⑤ apiKey 非空覆盖 ─────────────────────────────────────────────────────────

    @Test
    fun `07 non-blank apiKey overwrites the stored one`() {
        newStore()
        saveRelay(apiKey = "sk-old")

        saveRelay(apiKey = "sk-new")

        assertEquals(setOf("my-relay"), providersTree().keys)
        assertEquals("sk-new", entryTree("my-relay").getValue("apiKey").jsonPrimitive.content)
        assertTrue(store.readRelays().single().keyConfigured)
    }

    /** 原来就没有 key 且这次也没给 → 不写 apiKey 字段（pi 判未配置，比写空串诚实）。 */
    @Test
    fun `08 omitting the key on a fresh entry writes no apiKey field at all`() {
        newStore()

        saveRelay(apiKey = null)

        assertFalse("条目里不该出现 apiKey 这个键：${entryTree("my-relay")}", entryTree("my-relay").containsKey("apiKey"))
        assertEquals(
            """{"providers":{"my-relay":{"baseUrl":"http://127.0.0.1:18080/v1","api":"openai-completions","models":[{"id":"test-relay-model"}]}}}""",
            store.modelsFile.readText(),
        )
        assertFalse(store.readRelays().single().keyConfigured)
    }

    /** `$ENV_NAME` 形态原样落盘：pi 自己解析，app 不展开（§3.1 原文）。 */
    @Test
    fun `09 a dollar-ENV key is stored verbatim and never expanded`() {
        newStore()

        saveRelay(apiKey = "\$RELAY_TOKEN")

        assertEquals("\$RELAY_TOKEN", entryTree("my-relay").getValue("apiKey").jsonPrimitive.content)
        // 读口只报状态：引用串本身也不作为返回值的一部分
        assertEquals(
            listOf(RelayEntry("my-relay", "http://127.0.0.1:18080/v1", DEFAULT_API, listOf(RelayModel("test-relay-model")), true)),
            store.readRelays(),
        )
    }

    // ── ⑥ models 空列表非法 ───────────────────────────────────────────────────────

    @Test
    fun `10 empty model list is rejected without touching the file`() {
        newStore()

        assertIsIAE("空列表") { store.save("my-relay", "https://x.example.com/v1", DEFAULT_API, emptyList(), "sk-x") }
        assertIsIAE("全是空白项") { store.save("my-relay", "https://x.example.com/v1", DEFAULT_API, listOf(RelayModel(""), RelayModel("  ")), "sk-x") }
        assertFalse("任何一次拒绝都不该留下 models.json", store.modelsFile.exists())
    }

    /** providerId / baseUrl / api 空白同抛（§3.1「providerId/baseUrl blank 同抛」，api 一并守住）。 */
    @Test
    fun `11 blank providerId baseUrl or api are rejected without touching the file`() {
        newStore()

        for (id in listOf("", "  ")) {
            assertIsIAE("blank providerId（<$id>）") { store.save(id, "https://x/v1", DEFAULT_API, listOf(RelayModel("m")), "sk-x") }
            assertIsIAE("blank providerId（<$id>）的 clear") { store.clear(id) }
            assertIsIAE("blank providerId（<$id>）的 setDefault") { store.setDefault(id, "m") }
        }
        assertIsIAE("blank baseUrl") { store.save("my-relay", " ", DEFAULT_API, listOf(RelayModel("m")), "sk-x") }
        assertIsIAE("blank api") { store.save("my-relay", "https://x/v1", "", listOf(RelayModel("m")), "sk-x") }
        assertIsIAE("blank modelId 的 setDefault") { store.setDefault("my-relay", " ") }
        assertFalse(store.modelsFile.exists())
        assertFalse(store.settingsFile.exists())
    }

    private fun assertIsIAE(what: String, block: () -> Unit) {
        val boom = runCatching(block).exceptionOrNull()
        assertTrue("$what 应抛 IllegalArgumentException，实际 $boom", boom is IllegalArgumentException)
    }

    // ── ⑦ 损坏 JSON 上 save 覆盖为合法对象 ─────────────────────────────────────────

    @Test
    fun `12 saving over a corrupted models json replaces it with a valid object`() {
        newStore()
        seedModels("{ this is not json ]")

        saveRelay()

        assertEquals(
            """{"providers":{"my-relay":{"baseUrl":"http://127.0.0.1:18080/v1","api":"openai-completions","apiKey":"sk-relay-test-123","models":[{"id":"test-relay-model"}]}}}""",
            store.modelsFile.readText(),
        )
        assertEquals(1, store.readRelays().size)
    }

    /** `providers` 段本身是垃圾（数组）→ 读为空、写覆盖成合法对象，顶层其余键仍保留。 */
    @Test
    fun `13 a non-object providers section reads as empty and is replaceable`() {
        newStore()
        seedModels("""{"providers":[1,2,3],"note":"顶层键要留住"}""")

        assertEquals(emptyList<RelayEntry>(), store.readRelays())
        saveRelay(providerId = "relay-x")

        assertEquals(setOf("relay-x"), providersTree().keys)
        assertEquals("顶层键留住", "顶层键要留住", readModels().getValue("note").jsonPrimitive.content)
    }

    // ── ⑧ readRelays 是无密钥视图 ─────────────────────────────────────────────────

    @Test
    fun `14 readRelays never returns the key and marks keyConfigured per entry`() {
        newStore()
        val secret = "sk-super-secret-relay-key"
        seedModels("""{"providers":{"good":{"baseUrl":"https://g.example.com/v1","api":"openai-completions","apiKey":"$secret","models":[{"id":"g1"},{"id":"g2"}]},"nokey":{"baseUrl":"https://n.example.com/v1","models":[{"id":"n1"}]},"blankkey":{"baseUrl":"https://b.example.com/v1","apiKey":"   ","models":[{"id":"b1"}]},"note":"不是对象，不进表"}}""")

        val rows = store.readRelays()
        val dump = rows.toString()
        assertFalse("返回值里不许出现 apiKey 明文：$dump", dump.contains(secret))
        assertEquals(
            "对象条目按文件顺序进表；缺 api 补默认、缺 key 与空白 key 都算未配置；非对象值不进表",
            listOf(
                RelayEntry("good", "https://g.example.com/v1", "openai-completions", listOf(RelayModel("g1"), RelayModel("g2")), true),
                RelayEntry("nokey", "https://n.example.com/v1", DEFAULT_API, listOf(RelayModel("n1")), false),
                RelayEntry("blankkey", "https://b.example.com/v1", DEFAULT_API, listOf(RelayModel("b1")), false),
            ),
            rows,
        )
        assertEquals("RelayEntry 只有五个字段，没地方藏密钥", 5, RelayEntry::class.java.declaredFields.size)
    }

    // ── ⑨ setDefault 写 settings.json 两字段并保留其他字段 ─────────────────────────

    @Test
    fun `15 setDefault writes both fields and keeps unrelated settings fields`() {
        newStore()
        seedSettings("""{"theme":"dark","locale":"zh-CN","nested":{"a":[1,{"b":2}]}}""")

        store.setDefault("my-relay", "deepseek-chat")

        assertEquals(
            "除两个默认字段外，其余键与字段必须逐字等价（比结构，不依赖键序）",
            Json.parseToJsonElement(
                """{"theme":"dark","locale":"zh-CN","nested":{"a":[1,{"b":2}]},"defaultProvider":"my-relay","defaultModel":"deepseek-chat"}""",
            ).jsonObject,
            readSettings(),
        )
        assertEquals("my-relay", store.readDefaultProvider())
        assertEquals(listOf(SETTINGS_FILE_NAME), agentFileNames())
    }

    /** 干净文件上 setDefault 的形状逐字确定（两字段、插入序固定）。 */
    @Test
    fun `16 setDefault on a fresh file writes exactly the two fields`() {
        newStore()

        store.setDefault("relay-a", "gpt-4o")

        assertEquals("""{"defaultProvider":"relay-a","defaultModel":"gpt-4o"}""", store.settingsFile.readText())
    }

    /** setDefault 覆盖既有钉选：同字段就地替换，不多出一份、顺序不乱。 */
    @Test
    fun `17 setDefault replaces a previously pinned default`() {
        newStore()
        store.setDefault("relay-a", "gpt-4o")

        store.setDefault("relay-b", "deepseek-chat")

        assertEquals("""{"defaultProvider":"relay-b","defaultModel":"deepseek-chat"}""", store.settingsFile.readText())
        assertEquals("relay-b", store.readDefaultProvider())
    }

    // ── ⑩ clear 同步清悬空 default ────────────────────────────────────────────────

    @Test
    fun `18 clearing the pinned relay also clears the dangling default fields`() {
        newStore()
        saveRelay(providerId = "relay-a")
        saveRelay(providerId = "relay-b", baseUrl = "https://b.example.com/v1")
        // settings.json：钉着 relay-a，另有一个 app 不管的 theme 字段
        seedSettings("""{"theme":"dark","defaultProvider":"relay-a","defaultModel":"test-relay-model"}""")

        store.clear("relay-a")

        assertEquals(setOf("relay-b"), providersTree().keys)
        assertNull("默认指针必须一起清掉，否则 pi 找不到模型回退内置默认", store.readDefaultProvider())
        val settings = readSettings()
        assertFalse(settings.containsKey("defaultProvider"))
        assertFalse(settings.containsKey("defaultModel"))
        assertEquals("无关字段照旧保留", "dark", settings.getValue("theme").jsonPrimitive.content)
        assertEquals("""{"theme":"dark"}""", store.settingsFile.readText())
    }

    /** 条目本来不在 models.json 里、但 defaultProvider 指着它（手改文件的形状）→ 同样清悬空，且不碰 models.json。 */
    @Test
    fun `19 clear removes a default that points at a relay missing from models json`() {
        newStore()
        seedModels("""{"providers":{"relay-b":{"baseUrl":"https://b.example.com/v1","api":"openai-completions","models":[]}}}""")
        seedSettings("""{"defaultProvider":"gone","theme":"dark"}""")
        val modelsBefore = store.modelsFile.readText()

        store.clear("gone")

        assertNull(store.readDefaultProvider())
        assertEquals("models.json 里没有这个条目 → 一个字节都不动", modelsBefore, store.modelsFile.readText())
        assertEquals("""{"theme":"dark"}""", store.settingsFile.readText())
    }

    // ── ⑪ clear 幂等 ──────────────────────────────────────────────────────────────

    @Test
    fun `20 clear is a no-op when the file or the entry is absent`() {
        newStore()

        store.clear("my-relay")
        assertFalse("没有 models.json 时 clear 不许创建它", store.modelsFile.exists())
        assertFalse("也没有 settings.json", store.settingsFile.exists())

        saveRelay(providerId = "relay-a")
        val before = store.modelsFile.readText()
        store.clear("relay-b")
        assertEquals("清一条本来不存在的中转：文件一个字节都不动", before, store.modelsFile.readText())

        store.clear("relay-a")
        assertEquals(emptyList<String>(), providersTree().keys.toList())
        val after = store.modelsFile.readText()
        store.clear("relay-a")
        assertEquals("第二次 clear 完全幂等", after, store.modelsFile.readText())
    }

    // ── ⑫ models.json 的非 provider 顶层键不丢 ────────────────────────────────────

    @Test
    fun `21 save and clear keep top level keys that are not providers`() {
        newStore()
        seedModels(
            """{"note":"未来 pi 可能加的顶层字段","count":7,"flag":true,"nil":null,"providers":{"relay-old":{"baseUrl":"https://o.example.com/v1","api":"openai-completions","apiKey":"sk-old","models":[{"id":"o1"}]}}}""",
        )

        saveRelay(providerId = "relay-new", apiKey = "sk-new")
        store.clear("relay-old")

        val tree = readModels()
        assertEquals("顶层非 providers 的键一个都不能少", setOf("note", "count", "flag", "nil", "providers"), tree.keys)
        assertEquals("未来 pi 可能加的顶层字段", tree.getValue("note").jsonPrimitive.content)
        assertEquals(setOf("relay-new"), tree.getValue("providers").jsonObject.keys)
    }

    /** provider 条目内部 app 不管的字段（headers 等）也要留住（红线对条目级同样成立）。 */
    @Test
    fun `22 save keeps provider level fields the app does not manage`() {
        newStore()
        seedModels(
            """{"providers":{"my-relay":{"headers":{"X-Via":"relay"},"apiKey":"sk-old","baseUrl":"https://old.example.com/v1","api":"openai-completions","models":[{"id":"old"}]}}}""",
        )

        saveRelay(baseUrl = "https://new.example.com/v1", models = listOf(RelayModel("new")), apiKey = null)

        val entry = entryTree("my-relay")
        assertEquals("unmanaged 字段留住，managed 四项被重写", setOf("headers", "baseUrl", "api", "apiKey", "models"), entry.keys)
        assertEquals("relay", entry.getValue("headers").jsonObject.getValue("X-Via").jsonPrimitive.content)
        assertEquals("sk-old", entry.getValue("apiKey").jsonPrimitive.content)
        assertEquals("https://new.example.com/v1", entry.getValue("baseUrl").jsonPrimitive.content)
    }

    // ── 目录与失败路径（与 ModelCredentialStore 同一套纪律）────────────────────────

    @Test
    fun `23 save and setDefault create the whole dot-pi agent chain`() {
        val home = File(tmpFolder.root, "fresh-home")
        assertFalse(home.exists())

        val s = RelayEndpointStore(home)
        s.save("my-relay", "https://r.example.com/v1", DEFAULT_API, listOf(RelayModel("m")), "sk-x")
        s.setDefault("my-relay", "m")

        assertTrue(home.isDirectory)
        assertTrue(File(home, ".pi").isDirectory)
        assertTrue(s.modelsFile.isFile)
        assertTrue(s.settingsFile.isFile)
        assertEquals(
            listOf(MODELS_FILE_NAME, SETTINGS_FILE_NAME),
            requireNotNull(s.modelsFile.parentFile).listFiles()!!.map { it.name }.sorted(),
        )
    }

    /** 目录链被一个**普通文件**挡住 → 抛 IOException，且 models.json / tmp 一个都不落（UI 失败提示的根因）。 */
    @Test
    fun `24 saving fails loudly when the directory chain is blocked by a regular file`() {
        val blockedHome = File(tmpFolder.root, "blocked-home")
        blockedHome.mkdirs()
        File(blockedHome, ".pi").writeText("我不是目录")

        val s = RelayEndpointStore(blockedHome)
        val boom = runCatching { s.save("my-relay", "https://r.example.com/v1", DEFAULT_API, listOf(RelayModel("m")), "sk-x") }.exceptionOrNull()
        assertTrue("期望 IOException，实际 $boom", boom is IOException)
        assertTrue("消息要点出建不成的目录", requireNotNull(boom).message!!.contains(File(blockedHome, ".pi").path))
        assertFalse(s.modelsFile.exists())
        assertFalse("失败路径不许留 tmp", blockedHome.listFiles()!!.any { it.name.contains(MODELS_TMP_PREFIX) })
    }

    // ── 默认钉选读口 + clearDefault ────────────────────────────────────────────────

    /** 缺文件 / 损坏 / 非字符串 / 空白 → 一律 null（进屏首帧不许崩，也不许亮徽标）。 */
    @Test
    fun `25 readDefaultProvider is null for missing corrupted or blank settings`() {
        newStore()
        assertNull(store.readDefaultProvider())

        seedSettings("[1,2,3]")
        assertNull(store.readDefaultProvider())

        seedSettings("""{"defaultProvider":"   ","defaultModel":"m"}""")
        assertNull(store.readDefaultProvider())

        seedSettings("""{"defaultProvider":{"nested":true}}""")
        assertNull(store.readDefaultProvider())
    }

    @Test
    fun `26 clearDefault drops only the two default fields and is idempotent`() {
        newStore()
        seedSettings("""{"theme":"dark","defaultProvider":"relay-a","defaultModel":"m1"}""")

        store.clearDefault()

        assertEquals("""{"theme":"dark"}""", store.settingsFile.readText())
        val before = store.settingsFile.readText()
        store.clearDefault()
        assertEquals("第二次完全幂等：不动文件", before, store.settingsFile.readText())

        newStore(File(tmpFolder.root, "home-no-settings"))
        store.clearDefault()
        assertFalse("没有 settings.json 时 clearDefault 不许创建它", store.settingsFile.exists())
    }

    // ── 五项 api 清单（§3.2）──────────────────────────────────────────────────────

    @Test
    fun `27 the api picker is exactly the five options from section 3_2 in order`() {
        assertEquals(5, RELAY_APIS.size)
        assertEquals(
            listOf("openai-completions", "anthropic-messages", "openai-responses", "google-generative-ai", "pi-messages"),
            RELAY_APIS.map { it.api },
        )
        assertEquals("默认项必须是 openai-completions（中转场景的绝大多数）", DEFAULT_API, RELAY_APIS.first().api)
        assertEquals("适用绝大多数中转/one-api/new-api", RELAY_APIS.first().note)
        assertEquals("其余四项不带说明", listOf(null, null, null, null), RELAY_APIS.drop(1).map { it.note })
        assertEquals("OpenAI 兼容（Chat Completions）", RelayEndpointStore.apiDisplayName("openai-completions"))
        assertEquals("清单外的值原样回显，不编造名字", "bedrock-converse-stream", RelayEndpointStore.apiDisplayName("bedrock-converse-stream"))
        assertEquals("id 不许重复", RELAY_APIS.size, RELAY_APIS.map { it.api }.distinct().size)
    }

    /** 清单外的 api 值 store 照收（app 不比 pi 严）：写得进、读得出。 */
    @Test
    fun `28 an api value outside the picker list still round-trips`() {
        newStore()

        saveRelay(api = "bedrock-converse-stream")

        assertEquals(listOf("bedrock-converse-stream"), store.readRelays().map { it.api })
        assertEquals("bedrock-converse-stream", entryTree("my-relay").getValue("api").jsonPrimitive.content)
    }

    /** 两张表的 tmp 前缀互不相同，且两次写过后目录里没有任何残骸。 */
    @Test
    fun `29 each file uses its own tmp prefix and neither leaves debris`() {
        newStore()
        saveRelay()
        store.setDefault("my-relay", "test-relay-model")

        assertEquals(listOf(MODELS_FILE_NAME, SETTINGS_FILE_NAME), agentFileNames())
        assertEquals("models.json.tmp", MODELS_TMP_PREFIX)
        assertEquals("settings.json.tmp", SETTINGS_TMP_PREFIX)
        assertEquals(emptyList<File>(), agentFileNames().filter { it.contains(".tmp") })
    }

    /** 模型清单里的空白项被清掉、多余空格被 trim、重复 id 折叠（逗号分隔的多值来自 UI）。 */
    @Test
    fun `30 model ids are trimmed, blanks dropped and duplicates collapsed`() {
        newStore()

        saveRelay(
            models = listOf(
                RelayModel(" gpt-4o "),
                RelayModel(""),
                RelayModel("  "),
                RelayModel("deepseek-chat"),
                RelayModel("gpt-4o"),
            ),
        )

        val written = entryTree("my-relay").getValue("models") as JsonArray
        assertEquals(
            listOf("gpt-4o", "deepseek-chat"),
            written.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals("没标思考的项一个 reasoning 字段都不写", listOf(false, false), written.map { (it.jsonObject).containsKey("reasoning") })
        assertEquals(listOf(RelayModel("gpt-4o"), RelayModel("deepseek-chat")), store.readRelays().single().models)
    }

    /** 空文件 / 顶层是数组的 settings.json：setDefault 覆盖成合法对象（⑦ 对两张表都成立）。 */
    @Test
    fun `31 setDefault over an empty or corrupt settings file replaces it with a valid object`() {
        newStore()
        seedSettings("")
        store.setDefault("relay-a", "m")
        assertEquals("""{"defaultProvider":"relay-a","defaultModel":"m"}""", store.settingsFile.readText())

        seedSettings("[1,2,3]")
        store.setDefault("relay-b", "m2")
        assertEquals("""{"defaultProvider":"relay-b","defaultModel":"m2"}""", store.settingsFile.readText())
    }

    // ── P3B-FIX1 §3.1 第 6 条：reasoning 面（本轮的唯一新增语义）────────────────────

    /**
     * 落盘形状：true 的项写 `"reasoning":true`，false 的项**一个字节都不多**。
     *
     * 这是本轮的**主用例**：用户报的「思考无法调整」就是这里少写那个字段造成的
     * （pi `provider-composer.ts:231` 的 `?? false` → `getSupportedThinkingLevels` 只剩 `["off"]`）。
     * 逐字比原文，不比解析结果——「字段有没有写出去」这件事只有磁盘上的字节能作证。
     */
    @Test
    fun `32 save writes reasoning true only on the flagged models`() {
        newStore()

        saveRelay(
            models = listOf(
                RelayModel("deepseek-chat", false),
                RelayModel("deepseek-reasoner", true),
                RelayModel("gpt-4o"),
            ),
        )

        assertEquals(
            "§3.1 形状：true 项带字段、false 项省略（顺序按传入顺序）",
            """{"providers":{"my-relay":{"baseUrl":"http://127.0.0.1:18080/v1","api":"openai-completions","apiKey":"sk-relay-test-123","models":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner","reasoning":true},{"id":"gpt-4o"}]}}}""",
            store.modelsFile.readText(),
        )
        val written = entryTree("my-relay").getValue("models") as JsonArray
        assertEquals(
            "只有第二项有 reasoning 这个键",
            listOf(false, true, false),
            written.map { (it as JsonObject).containsKey("reasoning") },
        )
    }

    /**
     * write → read 往返：app 自己写的读得回来（含 true），**手写的** `reasoning:true` 同样读得回来。
     *
     * 后半段才是重点：用户可以直接编辑 models.json（§3.1「app 不比 pi 严」那条纪律），
     * 读口认不出手写的 true，就等于「文件里明明有、UI 里调不动」——同一个 bug 换一层皮。
     */
    @Test
    fun `33 reasoning round-trips through save and reads a hand written true`() {
        newStore()
        saveRelay(models = listOf(RelayModel("plain"), RelayModel("thinker", true)))

        assertEquals(
            "写出去的思考旗原样读回",
            listOf(RelayModel("plain", false), RelayModel("thinker", true)),
            store.readRelays().single().models,
        )

        newStore(File(tmpFolder.root, "home-handwritten"))
        seedModels(
            """{"providers":{"hand":{"baseUrl":"https://h.example.com/v1","api":"openai-completions","models":[{"id":"a"},{"id":"b","reasoning":true}]}}}""",
        )

        assertEquals(
            "手写的 reasoning:true 也认（读口不比 pi 严，也不许丢字段）",
            listOf(RelayModel("a", false), RelayModel("b", true)),
            store.readRelays().single().models,
        )
    }

    /**
     * **只认真 `true`**：缺字段、写 false、写 `"true"` 字符串、写 1、写 null、写对象 → 一律 false。
     *
     * 从严的理由见 `modelEntries` 的 KDoc：true 是「往上游请求里加 reasoning 参数」那一侧，
     * 把非布尔值猜成 true 会让 app 发出 pi 自己都不会发的请求。
     */
    @Test
    fun `34 only a real json true counts as reasoning`() {
        newStore()
        seedModels(
            """{"providers":{"odd":{"baseUrl":"https://o.example.com/v1","models":["bare",""" +
                """{"id":"f","reasoning":false},{"id":"s","reasoning":"true"},{"id":"n","reasoning":1},""" +
                """{"id":"z","reasoning":null},{"id":"j","reasoning":{"deep":true}},{"id":"t","reasoning":true}]}}}""",
        )

        assertEquals(
            listOf(
                RelayModel("bare", false),
                RelayModel("f", false),
                RelayModel("s", false),
                RelayModel("n", false),
                RelayModel("z", false),
                RelayModel("j", false),
                RelayModel("t", true),
            ),
            store.readRelays().single().models,
        )
    }

    /** 清洗的第二道门（§3.1 第 5 条）：同 id 混 reasoning → **取或**；trim、去空、按 id 去重照旧。 */
    @Test
    fun `35 cleaning merges the same id with OR and keeps first-seen order`() {
        newStore()

        saveRelay(
            models = listOf(
                RelayModel(" gpt-4o ", false),
                RelayModel("deepseek-chat", false),
                RelayModel("gpt-4o", true),
                RelayModel("  ", false),
                RelayModel("deepseek-chat", false),
            ),
        )

        val written = entryTree("my-relay").getValue("models") as JsonArray
        assertEquals(
            "gpt-4o 因任一条 true 而 true、deepseek-chat 两条都 false 保持 false、空白项没了（顺序 = 首次出现）",
            listOf("gpt-4o", "deepseek-chat"),
            written.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals(
            "只有该写的才写得出这个键",
            listOf(true, false),
            written.map { it.jsonObject.containsKey("reasoning") },
        )
        assertEquals(
            "写出去的值是真布尔 true（不是字符串、不是 1）",
            listOf("true"),
            written.mapNotNull { it.jsonObject["reasoning"]?.jsonPrimitive?.content },
        )
        assertEquals(
            listOf(RelayModel("gpt-4o", true), RelayModel("deepseek-chat", false)),
            store.readRelays().single().models,
        )
    }

    /**
     * 空白 id 清洗后为空 → 抛（这条从 ⑥ 迁到带旗的形状上）：**先判空表再清洗**的顺序不变，
     * 而且抛之前一个字节都不写。
     */
    @Test
    fun `36 a list of blank ids is rejected after cleaning without touching the file`() {
        newStore()

        assertIsIAE("全空白 id") {
            store.save("my-relay", "https://x.example.com/v1", DEFAULT_API, listOf(RelayModel("   "), RelayModel("")), "sk-x")
        }
        assertFalse(store.modelsFile.exists())
    }

    /** delete/setDefault 不受本轮签名变更影响（§3.1 第 6 条要求「旧用例原样绿」，这里再钉一次形状）。 */
    @Test
    fun `37 clear and setDefault are untouched by the reasoning field`() {
        newStore()
        saveRelay(models = listOf(RelayModel("thinker", true), RelayModel("plain")))
        store.setDefault("my-relay", "thinker")
        assertEquals(
            """{"defaultProvider":"my-relay","defaultModel":"thinker"}""",
            store.settingsFile.readText(),
        )

        store.clear("my-relay")

        assertEquals("""{"providers":{}}""", store.modelsFile.readText())
        assertNull("默认指针跟着清掉（⑩ 条语义与 reasoning 无关）", store.readDefaultProvider())
        assertEquals("""{}""", store.settingsFile.readText())
    }
}
