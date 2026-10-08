# REPORT-P2H — 第三方中转站接入（models.json/settings.json 原子读写 + 设置页「自定义中转」+ 0.1.3）

基线：`1c4b4e7`（P2G 报告收尾）。本轮五个提交 + 本报告：
`3aed524`(AgentConfigFiles 抽取) → `3f1d10a`(RelayEndpointStore + 31 条单测 + EngineModule 边) →
`cc6de20`(feature/settings 中转面：Source + VM + Section + 22 条用例) →
`f2f3e7b`(补 `relayEndpointSource` 绑定，`cc6de20` 漏的一条转发边) →
`a3afd67`(app 版本 4/0.1.3 + AppTestDoubles 注入边) → 本报告。

## 1. 结论摘要

- 交付物 §3.1 / §3.2 / §3.3 / §3.4 **全部落地，无降级项**。规格书 §3.2 那句「api 用 RadioButton 的
  Dialog 选择器」按 **Dialog** 落地（`RelayApiPickerDialog`，`SettingsScreen.kt:534`），
  沿用 P2G 在 `AlertDialog` 上已经用 Robolectric 实证过的那条路，不引入 `ExposedDropdownMenuBox` 的签名漂移面。
- 用例总数 **304**（编译机 267 + 本机 37），**0 红 0 跳过**；本轮新增 **53** 条
  （`:core:engine` 31 + `:feature:settings` 22 = VM t18–t26 九条 + UI h01–h13 十三条）。
  P2G 基线 251 + 53 = 304，逐模块相加也对得上（§6）。
- §2 三条红线在 P2H 窗口内**逐条有实测支撑**（§4）：`models.json`/`settings.json` 只经
  `AgentConfigFiles.writeAtomic`（同目录 tmp + `renameTo`）落盘；写路径全部是「读-改-写」，
  未知字段与别人的 provider 一个不丢；中转 `apiKey` 不进 DataStore、不进日志、不进 UI 回显。
- APK：**63,826,901 B**，sha256 `0944f6f3e16124c5b0802e9d60ac7e3e7429149e9eb363e09f9d1752c8907f31`，
  双端（编译机 `/root/apk-serve/0944f6f3e161/` + 本机 `/root/pi/dist/`）**逐字相等**；
  badging 实测 `versionCode='4' versionName='0.1.3'`（§7）。
- 冻结区复核口径见 §5：规格书 §4 原文写的是 `git diff 1f77127..HEAD`，那个基线早于 P2F/P2G，
  diff 必然带上**前几轮**对 `feature/chat` 的合法改动；P2H 自己的窗口是 `1c4b4e7..HEAD`，
  窗口内七条冻结路径的 diff **为空**（实测输出见 §5）。

## 2. 交付物落点（要求 → 代码 → 证据）

### 2.1 §3.1 `core:engine`：AgentConfigFiles 抽取 + RelayEndpointStore

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| 「把 `ModelCredentialStore` 私有的 `readObject`/`writeAtomic`/`ensureDirectory` 抽成共享工具，不许复制粘贴」 | `core/engine/.../settings/AgentConfigFiles.kt:63`（`ensureDirectory`）、`:75`（`readObject`）、`:92`（`writeAtomic`）；`ModelCredentialStore.kt:84/:86/:89/:105/:110` 全部改为**委托调用**，那三个私有函数从该类里删除（本轮 `ModelCredentialStore.kt` 净 `+21/−57`，删的就是这三段） | `ModelCredentialStoreTest` 17 条**一条没改、一条没红**（§6），抽取是行为不变的 |
| 路径与 tmp 前缀单一真相 | `AgentConfigFiles.kt:32-45`（`.pi`/`agent`/三个文件名/`.tmp`/`JSON{ignoreUnknownKeys}`）；`ModelCredentialStore.kt:118-123` 与 `RelayEndpointStore.kt:269-275` 的同名公开常量都是**委托**（`const val X = AgentConfigFiles.X`），旧测试引用点不破 | 单测按 `MODELS_TMP_PREFIX`/`SETTINGS_TMP_PREFIX` 断「无残留」，值与写入侧派生同一个 |
| 类形状：`class RelayEndpointStore(private val homeDir: File)` | `RelayEndpointStore.kt:52` | 31 条全部 `RelayEndpointStore(tmp.newFolder())` 直构 |
| `modelsFile` / `settingsFile` | `:55` / `:59`（都走 `AgentConfigFiles.agentFile`，即 `home/.pi/agent/<file>`） | 02 / 23 |
| `readRelays()` | `:81`（非对象垃圾条目跳过、`apiKey` 非空白才算 `keyConfigured`） | 13 / 14 / 31 |
| `save(providerId, baseUrl, api, modelIds, apiKey)` | `:107-132`：四道 `require` → `readObject` 拿旧根 → `providersOf` → 旧条目里 **app 不管的字段先原样铺一遍**（`:125`）→ 重写 `MANAGED_PROVIDER_FIELDS` 四项 → `writeAtomic`（`:131`） | 01（精确形状 + 目录里只许两个文件）/ 03 / 04 / 05 / 12 / 15 / 16 / 21 / 22 |
| 「`apiKey` 传 null/空白 → 沿用已存 key」 | `:121-122` `resolvedKey = apiKey?.trim()?.takeIf{…} ?: 旧条目的 apiKey?.takeIf{非空白}`；原来没有且这次没给 → **不写该字段**（`:128` 只在非 null 时 `put`） | 07 / 08 / 09 |
| `clear(providerId)` + 同步清悬空默认 | `:143-158`（条目存在才重写 `models.json`；`:158` 无条件比对 `readDefaultProvider()`，指着它就 `clearDefault()`） | 10 / 11 / **⑩ → 18 / 19**（逐字断言见 §9） |
| `setDefault(providerId, modelId)` / `clearDefault()` | `:170-181` / `:187-196`（只碰 `defaultProvider`/`defaultModel` 两字段，其余原样保留；两者都缺 → 不动文件） | 17 / 18 / 20 |
| 原子性：同目录 tmp + `renameTo`，`finally` 清残骸 | 唯一出口 `AgentConfigFiles.writeAtomic`（`:92`）；`renameTo` 跨设备失败时先删目标再 rename | 01 / 06 / 24（失败路径也不落半个文件）/ 30 |
| 坏 JSON / 缺文件 / 顶层非对象 → 空对象继续写 | `AgentConfigFiles.readObject`（`:75`）+ `providersOf`（`:200`，`providers` 缺失或不是对象 → `EMPTY_OBJECT`） | 02 / 03 / 04 / 13 |
| 自动建父目录、失败抛 `IOException` | `AgentConfigFiles.ensureDirectory`（`:63`） | 23（全新 home 全链建成）/ 24（`.pi` 被普通文件挡住 → `IOException`） |
| EngineModule 提供 store，`homeDir` 走 `PathLayout` | `core/engine/.../di/EngineModule.kt:133-134`（`@Provides @Singleton` = `RelayEndpointStore(PathLayout.homeDir(ctx))`），与 `modelCredentialStore` 同一 `homeDir` 通路 | `:app` 17 / `:core:engine` 116 全绿即建图通过 |
| 门槛「`RelayEndpointStoreTest` ≥12 条，①–⑫ 逐条覆盖」 | `RelayEndpointStoreTest.kt:27-29` 类 KDoc 里写了映射表：①→01、②→03、③→04、④→05/06、⑤→07、⑥→10、⑦→12/13/31、⑧→14、⑨→15/16、⑩→18/19、⑪→20/26、⑫→21/22 | 实到 **31** 条（另加 02/08/09/11/17/23/24/25/27/28/29/30） |

`save` 的 `MANAGED_PROVIDER_FIELDS = {baseUrl, api, apiKey, models}`（`:280`）是「重写这四项、其余原样」这条红线的
唯一判据来源——用例 22 直接钉 `entry.keys == {headers, baseUrl, api, apiKey, models}`。

### 2.2 §3.2 `feature:settings`：自定义中转 Section

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| 「自定义中转（OpenAI 兼容）」Section，在「模型接入」之后，`HorizontalDivider` 分隔 | `ui/SettingsScreen.kt:122-123`（`HorizontalDivider()` 紧跟 `CredentialSection`，再挂 `RelaySection(...)`） | h01 |
| 四个输入框：providerId / baseUrl / 模型清单（逗号分隔）/ API key | `ui/SettingsScreen.kt:396`（id，tag `:402`）、`:404`（url，tag `:411`）、`:413`（models，tag `:420`）、`:422`（key，tag `:429`），全部 `singleLine` + `supportingText` 说明 | h01（四框 + 选择器 + 空表提示全在树上） |
| key 框 `PasswordVisualTransformation` | `SettingsScreen.kt:428` | h04（`•`.repeat(n) 显示态 + 明文只在输入框一个节点上）/ h13（写失败后草稿仍在，`assertRelayKeyValue`） |
| 「空白 key = 沿用已存密钥」 | UI：文案 `ui/SettingsTags.kt:209`；转换发生在 VM `SettingsViewModel.kt:258` `key = apiKey?.trim()?.takeIf { it.isNotEmpty() }`，UI 不做判断 | h03（UI 面：填空白 → fake 收到 `apiKey = null`）/ t20（VM 面同一条）/ 07（store 面：null 不动已存 key） |
| api 用 RadioButton 的 Dialog，固定五项，`openai-completions` 标注「适用绝大多数中转/one-api/new-api」 | 清单在 `RelayEndpointStore.kt:292-298`（`RELAY_APIS`，第一项带 `note`）；Dialog `SettingsScreen.kt:534`，五个 `RadioButton` 的 tag 由 `SettingsTags.relayApiItem(api)` 生成；选中项的 `note` 在选择按钮下方直接渲染（`:440` 起） | h10（五项逐个 `assertIsDisplayed` + `assertIsSelected` 只认当前值 + 长度钉死 5）/ t19（选了 `pi-messages` 就按它写） |
| 「保存中转」按钮 + notice「已保存，新会话生效」 | 按钮 `SettingsScreen.kt:445-449`，`enabled = id/baseUrl/parseModelIds(models)` 三者非空（key 可为空，见 `:446` 那行的条件里**没有** apiKey）；「重读列表」`TextButton` `:450-453`；文案 `SettingsTags.kt:226` `RELAY_SAVED` | h02（逐字断言同一条 notice）/ t19 / t21（三道空白门一次都不碰 source） |
| 每行「设为默认」「清除」 | `relaySetDefault(providerId)` / `relayClear(providerId)` tag（`SettingsTags.kt:75/:78`）；「设为默认」在 `entry.modelIds.isEmpty()` 时禁用 | h05（钉选后徽标亮）/ h06（清除后行与徽标一起消失）/ h12（没有模型 id 的行钉不了默认，VM 侧还有 t22 的空白拦） |
| 行格式：`providerId · baseUrl（截断显示）· N 个模型` | `relayRowText(entry)` 起于 `SettingsScreen.kt:523`，截断阈值 `RELAY_URL_DISPLAY_LIMIT = 36`（`:364`），超长加 `…`；行体渲染在 `:479` | h11（长 url 断言被截 + 计数 `%d 个模型` + `assertFalse(rowText.contains("sk-"))`） |
| 默认徽标由 `settings.json` 驱动 | VM `defaultRelay`（`SettingsViewModel.kt:131-134`）来自 `relayStore.defaultProvider()`，并在 `:223-225` 用「行里真的有它」过滤（悬空指针不显示徽标） | t18 / t24（悬空默认被滤掉）/ h06 |
| VM：`relays`/`defaultRelay`/`saveRelay`/`setRelayDefault`/`clearRelay`/`refreshRelays` | `:126-139` 三条 flow、`:219` `refreshRelays`、`:245` `saveRelay`、`:283` `setRelayDefault`、`:308` `clearRelay`；`:144` 进屏即读 | t18–t26 九条 |
| `RelayEndpointSource` + `FileRelayEndpointSource`（`Dispatchers.IO` hop）+ `SettingsModule` 装配 | `RelayEndpoints.kt:24-43` 接口、`:53-72` 实现（五个方法**逐个** `withContext(Dispatchers.IO)`）、`:81` `RelayDraft`、`:99` `parseModelIds`；绑定 `di/SettingsModule.kt:55` | `f2f3e7b` 就是这条绑定漏写导致 `:app` Hilt 建图失败（§8 第 9 条） |
| 新 tag 系列 `SCREEN + "-relay"` + `init{}` 自检 | `SettingsTags.kt:56-66` 常量、`:69-81` 派生函数、`:92-101` 十条 `check` | 自检不过就直接 `ExceptionInInitializerError`，该模块任何用例都跑不起来 |
| 新中文 strings 进 `SettingsStrings` | `SettingsTags.kt:188-236`（`SECTION_RELAY` 到 `relayCleared()`，实测 **27** 条 `const val`/`fun`） | h01–h13 全部按常量引用，没有一处硬编码文案 |
| 「≥8 条 Robolectric UI 用例，注入 fake source」 | `src/test/.../Fakes.kt` 的 `FakeRelayEndpointSource`（`saves`/`clears`/`pins` 调用记录 + `boom` 失败开关 + 空白 key 沿用已存 key 的语义） | 实到 **13** 条（h01–h13）+ VM 面 9 条（t18–t26） |

### 2.3 §3.3 `app`：版本 + 测试图注入边

| 要求 | 落点 | 证据 |
|---|---|---|
| `versionCode = 4` / `versionName = "0.1.3"` | `app/build.gradle.kts:19-20` | `ManifestPermissionTest.kt:82-87` 用例 03：`4L` / `"0.1.3"` / `> 3L` 单调性 |
| 「`AppTestDoubles` 补 `RelayEndpointStore`/`RelayEndpointSource` 注入边（真类 + 假目录，照 `modelCredentialStore` 先例）」 | `AppTestDoubles.kt:134-135` **只补了 store 这一条**，理由与实测见 §8 第 4 条 | `:app` 17 条全绿（MainActivity 11 / ManifestPermission 3 / PiEngineServiceWiring 3） |

## 3. 改动清单（P2H 独占，`git diff --name-status 1c4b4e7..HEAD` = **16 个路径：4 新增 / 12 修改**）

```
A  core/engine/src/main/kotlin/.../settings/AgentConfigFiles.kt              +104
M  core/engine/src/main/kotlin/.../settings/ModelCredentialStore.kt          +21 −57
A  core/engine/src/main/kotlin/.../settings/RelayEndpointStore.kt            +306
A  core/engine/src/test/kotlin/.../settings/RelayEndpointStoreTest.kt        +589
M  core/engine/src/main/kotlin/.../di/EngineModule.kt                        +14
A  feature/settings/src/main/kotlin/.../RelayEndpoints.kt                    +100
M  feature/settings/src/main/kotlin/.../SettingsViewModel.kt                 +140
M  feature/settings/src/main/kotlin/.../di/SettingsModule.kt                 +14
M  feature/settings/src/main/kotlin/.../ui/SettingsScreen.kt                 +241
M  feature/settings/src/main/kotlin/.../ui/SettingsTags.kt                   +98
M  feature/settings/src/test/kotlin/.../Fakes.kt                             +89
M  feature/settings/src/test/kotlin/.../SettingsViewModelTest.kt             +168 −3
M  feature/settings/src/test/kotlin/.../ui/SettingsScreenTest.kt             +385 −10
M  app/build.gradle.kts                                                      +2 −2
M  app/src/test/kotlin/.../AppTestDoubles.kt                                 +24
M  app/src/test/kotlin/.../ManifestPermissionTest.kt                         +8 −8
```

合计 `+2303 / −80`（`git diff --shortstat 1c4b4e7..HEAD` 实测）。
新增主源码 `104 + 306 + 100 + 140 + 241 + 98 + 14 + 14 = 1017` 行，测试侧 `589 + 89 + 168 + 385 + 24 + 8 = 1263` 行。
`−80` 行的全部去向：`ModelCredentialStore.kt` 的 57 行（三个私有工具搬去 `AgentConfigFiles`，改成委托调用）、
`SettingsViewModelTest.kt` 的 3 行与 `SettingsScreenTest.kt` 的 10 行（rig 构造加第五个参数、`qualifiers` 抬高度、
`assertKeyValue` 家族改成按持有者清单断言，见 §8 第 7/8 条）、`ManifestPermissionTest.kt` 的 8 行 + `app/build.gradle.kts` 的 2 行（版本期望值）。
**没有删除任何既有公共函数或公共签名**：`ModelCredentialStore` 的三个私有 `fun` 不算公共面，公开 API 与 17 条旧测试一字未动。

## 4. 红线复核（§2 逐条，全部实测）

1. **`models.json`/`settings.json` 必须原子写（同目录 tmp + rename）**：
   `RelayEndpointStore` 的**唯一**写盘出口是 `AgentConfigFiles.writeAtomic(dir, target, content)`
   （`RelayEndpointStore.kt:131/:150/:179/:193`，共四处调用，分别是 `save`/`clear`/`setDefault`/`clearDefault`）。tmp 名 = `<目标文件名>.tmp<nanoTime>`，
   与目标**同目录**（`AgentConfigFiles.kt:92` 起），`renameTo` 失败先删目标再 rename，`finally` 清残骸。
   实测：用例 01 断「写完目录里只剩 `models.json`/`settings.json`」，24 断「失败路径一个文件都不落」，
   25/26 断「tmp 前缀 = `MODELS_TMP_PREFIX`/`SETTINGS_TMP_PREFIX` 的文件为零」。
2. **读-改-写，保留其它 provider 与未知字段（用 `JsonObject`，不许 typed decode）**：
   `save` 从 `readObject(target)` 起步，`providers` 段按 `Map<String, JsonElement>` 增删条目，
   再 `root.with(FIELD_PROVIDERS, …)` 写回（`:116-132`）；全类**没有任何 `@Serializable` data class 参与解析**，
   `RelayEntry` 只在读取末端手工组装。实测：21（顶层 `note/count/flag/nil` 四个非 providers 键全留）、
   22（provider 内 `headers` 留住、managed 四项被重写）、15/16（两个 provider 共存，动一个不碰另一个）、
   18（`settings.json` 的 `theme` 留住）。逐字断言见 §9。
3. **中转 `apiKey` 是凭据**：
   - **不进 DataStore**：`core/engine/.../settings/SettingsRepository.kt` 里 `relay|Relay|apiKey|baseUrl` 五个词
     实测**零命中**；DataStore 键仍是 P2E 的三个（`:89-91` `mem_tier`/`max_parallel_sessions`/`default_model`），
     没有扩成四个。
   - **不进日志**：中转面三个新文件（`AgentConfigFiles.kt`/`RelayEndpointStore.kt`/`RelayEndpoints.kt`）
     实测 `Log.`/`println` **零命中**；`feature/settings` 与 `core/engine/.../settings` 两个 main 目录里
     只有 `SettingsRepository.kt:139/:143` 两处日志，打的是异常对象（P2E 既有代码），不含任何中转字段。
   - **不进测试快照 / 不进提交**：`grep -rn "sk-" --include="*.kt"`（排除 `/build/`）实测 **90 处命中、4 个文件，
     全部在 `src/test`**（`RelayEndpointStoreTest` / `ModelCredentialStoreTest` / `SettingsViewModelTest` /
     `SettingsScreenTest`），`src/main` 命中文件数为 **0**——命中的都是本轮/前轮的假常量，见 §5 第 3 条。
   - **UI 永不回显已存 apiKey**：`RelayEntry` 只有 `providerId/baseUrl/api/modelIds/keyConfigured` 五个字段
     （`RelayEndpointStore.kt:68-74`，**没有** `apiKey`），行内只渲染 `已配置/无可用密钥`
     （`relayRowKey` tag，`SettingsScreen.kt` 行体）；实测 h04 断明文只住在输入框那一个节点、
     h11 断整行文本 `!contains("sk-")`。
   - **baseUrl 不是凭据，可以回显**：`relayRowText`（`:523`）把 baseUrl 直接排进行里（超 36 字符截断加 `…`），
     h11 逐字断言截断结果。
4. **单次 gradle 只打一个模块**：本轮所有远端调用都是**单模块任务串**（`:core:engine:testDebugUnitTest` /
   `:feature:settings:testDebugUnitTest` / `:app:testDebugUnitTest` / `:app:assembleDebug` 各一次），
   没有出现一条命令跨两个模块的写法。
5. **`feature/chat` 不改 + 六条冻结路径不动**：P2H 窗口 diff 为空，实测见 §5。
6. **`EnvFactory` 十一键不动**：`grep -c 'put(' core/environment/.../EnvFactory.kt` 实测 **11**
   （`:19-29`，PATH/HOME/TMPDIR/NODE_PATH/NODE_OPTIONS/PI_OFFLINE/PI_SKIP_VERSION_CHECK/GIT_EXEC_PATH/SHELL/TERM/SSL_CERT_FILE），
   本轮没有为中转加任何键——中转靠磁盘上的 `models.json` 生效，走的是 pi 自己的读取通路。
7. **服务器磁盘 ≥4G**：交付时实测 `/dev/vda1` 可用 **5.0G**（§7）。

## 5. 冻结区复核与基线口径（§4 第一条验收）

```
$ git diff --stat 1c4b4e7..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md \
      tools/capture tools/runtime-packaging feature/chat
（无输出 → 七条冻结路径在 P2H 窗口内一字未动）
```

- **`1f77127` 与 `1c4b4e7` 两个基线不是一回事（规格书 §4 的原文口径要这样读）**：
  照规格书原文跑 `git diff --stat 1f77127..HEAD -- <冻结路径>`，输出**非空**——18 个 `feature/chat` 文件、
  `+1617/−109`。这不是本轮违规：`1f77127` 早于 P2E/P2F/P2G 三个里程碑，`feature/chat` 的那些改动
  是**那几轮的正当交付**（P2F/P2G 报告各自都复核过自己窗口内的冻结区 diff 为空）。
  本轮把口径钉在 **P2H 自己的窗口**（基线 `1c4b4e7` = REPORT-P2G 收尾），结果为一个字节都没有（上面那条命令）。
  同一窗口的全量文件清单就是 §3 那 16 条路径，全部落在 `core/engine`/`feature/settings`/`app`/`docs` 四个允许区。
- `core/rpc`、`core/process/src/main`、`core/environment`、`docs/protocol.md`、`tools/capture`、
  `tools/runtime-packaging` 六条在**两个口径下都是空**（`1f77127..HEAD` 的输出里只出现 `feature/chat` 前缀）。
- **pi 源码零改动**（§0 的「零修改」约束）：
  `diff -rq /tmp/pi-verify/pi-1.0.4 /root/pi/.tools/pi-1.0.4` → **`rc=0` 且无输出**（本轮实测，解包自
  `/root/pi/.tools/pi-v1.0.4.tar.gz`）。中转的两个文件名与字段名全部是**对照 pi 1.0.4 源码抄出来的常量**
  （`AgentConfigFiles.kt:38-39`、`RelayEndpointStore.kt:260-261` 两个文件名与 `:267-274` 八个字段名），没有改动上游一个字节。

## 6. 测试矩阵（规格书 §3.4：一次 gradle 一个模块）

| 模块 | 测试类（tests） | 合计 fail/err/skip | 门槛 | 判定 |
|---|---|---|---|---|
| `:core:environment` | EnvFactoryTest 2 / PathLayoutTest 3 | **5** / 0 / 0 / 0 | 5 | ✓ |
| `:core:engine` | AssetInstallerTest 5 / ChatEngineAdapterTest 6 / DataStoreProbeTest 4 / ExecFallbackStarterTest 9 / PiEngineServiceTest 7 / PiSpawnTest 4 / RealRuntimeAssetsInstallTest 2 / RuntimeInstallTest 5 / SessionSupervisorTest 14 / SpawnDiagnosticsSinkTest 6 / **RelayEndpointStoreTest 31** / ModelCredentialStoreTest 17 / SettingsRepositoryTest 6 | **116** / 0 / 0 / 0 | ≥57（store ≥12） | ✓（85 基线 + 新增 31） |
| `:feature:chat` | ChatViewModelTest 23 / ChatScreenUiTest 6 / ComposeProbeTest 3 / HiltComposeProbeTest 1 / HiltProbeTest 1 / InstallGateFailedTest 5 / MarkwonRenderTest 4 / MarkwonSmokeTest 4 | **47** / 0 / 0 / 0 | 47 | ✓（一条没动，§2 红线） |
| `:feature:sessions` | SessionScannerTest 10 / SessionsViewModelTest 12 / SessionsScreenTest 3 | **25** / 0 / 0 / 0 | 25 | ✓ |
| `:feature:settings` | SettingsViewModelTest 30 / SettingsScreenTest 27 | **57** / 0 / 0 / 0 | ≥28（UI ≥8） | ✓（35 基线 + 新增 22） |
| `:app` | MainActivityTest 11 / ManifestPermissionTest 3 / PiEngineServiceWiringTest 3 | **17** / 0 / 0 / 0 | 17 | ✓（版本期望值随 §2.3 改） |
| **编译机合计** | | **267**，fail 0 / err 0 / skip 0 | | 全绿零红 |
| `:core:rpc`（本机） | GoldenReplayTest / RpcModelTest / PiSessionTest / PiDesktopE2eTest / LineFramerTest = 34 | **34** / 0 / 0 / 0 | 34 | ✓ |
| `:core:process`（本机） | JvmProcessTest 3 | **3** / 0 / 0 / 0 | 3 | ✓ |
| **总计** | | **304**（267 + 37） | | **0 红 0 跳过** |

**同步点（哪个数字对应哪个 HEAD，实测依据）**：
`engine 116` / `settings 57` 是在 `f2f3e7b` 上实测的；随后 `a3afd67` 只动 `app/build.gradle.kts` 与
`app/src/test/**` 三个文件（`git diff --numstat f2f3e7b..a3afd67` 输出就这三行），
且 `git diff f2f3e7b..a3afd67 -- core/engine/src/main feature/settings/src/main` **为空**
→ 那两个模块的 116/57 与最终 HEAD 对得上，无需重跑。`app 17` 与 `:app:assembleDebug` 都在 `a3afd67` 上实测。
P2G 基线 85/35 的两个数字取自 `docs/REPORT-P2G.md` §7（同机同脚本），本轮逐模块重跑后仍然一致。

## 7. assembleDebug + APK 双端落盘

`aapt2 dump badging`（编译机 build-tools 36.0.0）实测：

```
package: name='works.earendil.pihost' versionCode='4' versionName='0.1.3' compileSdkVersion='36'
minSdkVersion:'26'  targetSdkVersion:'35'  native-code: 'arm64-v8a'
uses-permission: FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / INTERNET
```

对照 P2G 包：`versionCode 3 / 0.1.2` → **4 / 0.1.3**；权限集合不变（本轮没加任何权限，
`ManifestPermissionTest` 用例 01/02 仍绿即证明四条声明没被覆盖掉）。

| 落点 | 尺寸 | sha256 |
|---|---|---|
| 编译机 `/root/apk-serve/0944f6f3e161/pi-host-full-debug.apk` | 63,826,901 B | `0944f6f3e16124c5b0802e9d60ac7e3e7429149e9eb363e09f9d1752c8907f31` |
| 本机 `/root/pi/dist/pi-host-full-debug.apk` | 63,826,901 B | `0944f6f3e16124c5b0802e9d60ac7e3e7429149e9eb363e09f9d1752c8907f31` |

- 两端 sha256 **逐字相等**（本机这条值由本轮 `sha256sum /root/pi/dist/pi-host-full-debug.apk` 重测确认，
  尺寸 `63826901`）。
- 对比 P2G 包：63,787,581 B → 63,826,901 B = **+39,320 B**
  （`AgentConfigFiles` 104 + `RelayEndpointStore` 306 + `RelayEndpoints` 100 + VM 140 + Section/Dialog/文案 339 行，
  加一次 dex 增长抖动）。
- 编译机 `/root/apk-serve/index.html` 已更新（源文件 `/root/pi/.tools/p2h-index.html`），
  P2H 与 P2G 两行并列表；P2H 的目录名 `0944f6f3e161` = 自身 sha256 前 12 位，P2G 的 `2dce9c4ca97c`
  早于这条命名规则（§8 第 16 条），两个 URL 都保持可下载。
- 磁盘：交付时编译机 `/dev/vda1` 可用 **5.0G**（≥4G 门槛），整轮没有 OOM kill。

## 8. 偏差、遗留与未执行

1. **点名偏差 ①：api 下拉为什么是 `BUILTIN_APIS` 十项里的五项**（规格书 §3.4 要求显式记录）。
   `RelayEndpointStore.kt:292-298` 的 `RELAY_APIS` = `openai-completions` / `anthropic-messages` /
   `openai-responses` / `google-generative-ai` / `pi-messages`。
   其余五项（`openai-codex-responses` / `azure-openai-responses` / `google-vertex` / `mistral-conversations` /
   `bedrock-converse-stream`）**需要各自的基础设施凭据**：Azure 要资源名与AAD/entrypoint、
   Vertex 要 GCP ADC、Bedrock 要 SigV4 身份与 region、Mistral 是另一套密钥体系，
   `openai-codex-responses` 走 Codex 的 OAuth 会话——**第三方中转站给不出这些东西**，
   放进下拉只会制造「选了就能用」的假选项。所以清单里不收；
   同时 **store 侧不做白名单校验**（`save` 只 `require(api.isNotBlank())`，`:110`），
   手改 `models.json` 写第六个值照样被 `readRelays` 读出来、`apiDisplayName`（`:304`）原样回显不编造名字。
   UI 侧的钉死证据是 h10：五个 item 逐个 `assertIsDisplayed` 且清单长度 == 5。
2. **点名偏差 ②：「baseUrl 要带路径前缀（例 `/v1`）」这句提示住在哪**（规格书 §3.4 要求显式记录）。
   文案常量 `feature/settings/.../ui/SettingsTags.kt:202`
   `RELAY_URL_HINT = "要带路径前缀，例：https://relay.example.com/v1（pi 只补 /chat/completions）"`，
   渲染位置 = `SettingsScreen.kt:409`，作为**接口地址输入框的 `supportingText`**（不是 Toast、不是 hint 弹层，
   所以截图与无障碍语义树里都在）。同一框还有 `placeholder = "https://relay.example.com/v1"`（`:408`）。
   为什么必须有这句：pi 的 openai-completions 面只在 baseUrl 后面补 `/chat/completions`，
   填 `https://relay.example.com` 会打到中转站的站点根，报错信息还挂在请求路径上，很难猜。
3. **多了一条 `readDefaultProvider()`（超出 §3.1 方法清单）**：`RelayEndpointStore.kt:94`。
   §3.1 点名的清单是 `modelsFile/settingsFile/RelayEntry/readRelays/save/clear/setDefault/clearDefault`。
   加这条有两个硬需要：(a) `clear` 要判断「默认是不是正指着被删的这条」（`:157`），
   (b) VM 的徽标必须来自 `settings.json` 而不是 UI 内存态（§3.2 明文要求），而 `readRelays()` 的返回里
   不带默认指针。它是**纯读、无副作用**，且被 18/19/17 三条用例直接覆盖。
4. **§3.3 说的「补 RelayEndpointStore/RelayEndpointSource 注入边」实测只需一条**（`AppTestDoubles.kt:134`）：
   接口→实现那条转发边住在 `SettingsModule`，而 app 测试只 `@UninstallModules(EngineModule::class)`，
   `SettingsModule` 没有被卸载 → 再补一条就是 **DuplicateBindings**，Hilt 直接建图失败。
   这条判断是**跑出来的**，不是推的：`f2f3e7b` 之前 `:app` 报 `Dagger/MissingBinding: RelayEndpointSource`，
   补上 `SettingsModule.kt:55` 才绿（§8 第 9 条）。
5. **`FileRelayEndpointSource` 的 `@Singleton` + 五个方法逐个 `withContext(Dispatchers.IO)`**：
   照 `FileModelCredentialSource` 的先例（P2G §8 第 1 条同因）——§3.1 的 store 方法是阻塞的，
   §3.2 的 VM 跑在 `viewModelScope`，调度决定必须关在实现里，VM 不碰。
6. **`clear` 的一个非显然语义**：条目本来不在 `models.json` 里（手改文件、或并发删除）时，
   `models.json` **一个字节都不重写**，但悬空默认**照样清**（`:158` 在 `if (target.isFile)` 之外）。
   §3.1 的 ⑩ 只说「同步清悬空 default」，这条拆分是补的，钉在用例 19（`assertEquals(modelsBefore, …)`）。
7. **`:feature:settings` UI 测试的 `qualifiers` 从 `h2400dp` 抬到 `h6400dp`**（`SettingsScreenTest.kt:71`，改前实测 10 条红）：
   notice 挂在整条竖直 `verticalScroll` 列的**最末**，新增的中转 Section 把它推出视口，
   `assertIsDisplayed()` 要求节点在视口内 → g02/g09/g11/g12 四条**P2G 既有用例**一并转红。
   这里刻意选择「抬高假屏幕」而不是把 `assertIsDisplayed` 松成 `assertExists`：
   松断言等于把「用户真的看得见这句话」这条验收悄悄降级。理由写在 `SettingsScreenTest.kt:62-70` 的注释里。
8. **`assertKeyValue` 家族重写为「持有者清单」而非「列表长度」**（`SettingsScreenTest.kt:101` `assertKeyValue` / `:116` `holdersOf` / `:141` `assertRelayKeyValue`，`h13`）：
   P2G 那句「全树只有输入框这一个明文持有者」在树上只有两个框时靠长度成立；
   P2H 之后全树有**五个**输入框，四个空框的 `InputText` 是空串，
   「合法保留的 id/url/models 草稿」也不再是明文——所以按 `SemanticsProperties.InputText` 的
   **非空且等于该明文**来筛持有者 tag。h13 第一版就是踩在这个坑上的假红（§8 第 8 条即为此留痕）。
   顺带把两个密钥框各自钉了一条：`assertKeyValue`（凭据框）/ `assertRelayKeyValue`（中转框），
   回显风险的护栏数量与输入框数量同步增长。
9. **`:feature:settings` 的 57 条绿**没有**发现漏绑定（如实记录的观测盲区）**：
   `cc6de20` 给 `SettingsModule` 只加了 import、没加 `@Provides` 方法，
   该模块 57 条（Robolectric + 手构 VM）全程绿；直到 `:app:testDebugUnitTest` 才炸出
   `Dagger/MissingBinding: works.earendil.pihost.feature.settings.RelayEndpointSource cannot be provided`。
   根因：feature 模块的测试不建 Hilt 图，只有 app 的全图测试会建。
   已由 `f2f3e7b` 补绑定（`+12` 行），修完把 `:feature:settings` 与 `:app` 各重跑一遍，57/17 全绿。
10. **本轮自己踩到的两处编译期坑（已修，留痕）**：
    (a) `RelayEndpointStoreTest` 用例 14 最初写成四引号的多行 raw string 拼接，Kotlin 报
    `Literals must be surrounded by whitespace` / `Unresolved reference 'nokey'` → 改成**一整行** raw string（现 `:297-298`）；
    (b) `SettingsViewModelTest.kt:231` 因 VM 多了第五个依赖报 `No value passed for parameter 'relayStore'`
    → t07b/t17 两处直构各补一个假体（那 3 行 `−` 就是这两处）。
11. **变异检查：本轮未执行**。P2G §4 那套「删掉 `visualTransformation` 一行看用例咬不咬」在 P2H 没有重做，
    所以 h04/t20 这类护栏目前只有「代码在→用例绿」的正证，没有「代码没→用例红」的反证。
    复现命令（编译机，一次一模块，跑完 `git restore`）：
    ```bash
    # 删掉 feature/settings/.../ui/SettingsScreen.kt:428 的 visualTransformation 一行
    bash /root/pi/.tools/bbuild.sh gradle ':feature:settings:cleanTestDebugUnitTest :feature:settings:testDebugUnitTest'
    # 期望：h04（SettingsScreenTest.kt:623）红，expected:<•••••> but was:<明文>
    ```
    本轮已知的**间接**反证：`h11`（`SettingsScreenTest.kt:796`）断整行 `!contains("sk-")`，
    一旦有人把 `apiKey` 塞进 `RelayEntry` 或行文本，这条立刻红。
12. **真机验收：未执行**（本机与编译机都没有设备；编译机 `adb devices` 列表为空，本机无 `adb`）。
    有设备时的路径：`adb install -r pi-host-full-debug.apk`（versionCode 4 > 3，允许覆盖）→
    设置页「自定义中转（OpenAI 兼容）」填 id/baseUrl（带 `/v1`）/模型清单/key → 「保存中转」→
    notice「已保存，新会话生效」→ **开新会话**选到该 provider 的模型发一句话 → assistant 有回复。
    预期链路（方向层在 P2G RECON 里已实证，本轮不重做）：pi spawn 时读 `models.json`，
    带 `baseUrl` + `apiKey` 的 provider 进 `configuredProviders`，`settings.json` 的
    `defaultProvider`/`defaultModel` 是 `findInitialModel` 的第三级来源。
13. **遗留观测项：`models.json`/`settings.json` 的文件模式**。pi 自己写这两个文件是应用私有目录内的 0600 语义，
    app 侧走 `FileOutputStream` + 进程 umask，单测**没有断 mode**（`TemporaryFolder` 的 umask 与 Android 进程不同，断它没意义）。
    真机一条命令可观测：
    `adb shell run-as works.earendil.pihost stat -c '%a %n' files/home/.pi/agent/models.json`。
14. **本轮没有做的两件「§3.2 之外」的事**：不给中转条目做 `apiKey` 的「改/删」独立入口
    （§3.2 只要求「空白 = 沿用」与「清除」，清空 key 目前只能靠清除整条中转）；
    不做 `models.json` 的 `models[].name/cost/input` 等可选字段的编辑（中转场景够用，未知字段保留已覆盖）。
15. **重复任务实例（过程事故，如实记录）**：本轮开工时曾有一个**并发**的 P2H 实例在跑，
    两边同时往同一批文件落草稿。处理：终止多余实例，把外来的四份草稿（`AgentConfigFiles.kt`/`EngineModule.kt`/
    `ModelCredentialStore.kt`/`RelayEndpointStore.kt`）挪到 `/tmp/p2h-foreign/` 留证、不参与构建，
    我的工作副本另存 `/tmp/p2h-stage/`；最终进入 git 的 16 条路径全部来自本会话提交链（§3）。
16. **`/root/apk-serve/` 目录名规则不统一**：P2G 用的 `2dce9c4ca97c` 早于「取包体 sha256 前 12 位」这条约定，
    本轮的 `0944f6f3e161` 按约定命名。两行都写进 `index.html` 并各自标注了包版本，旧 URL 不动（有人可能已经存了书签）。

## 9. §4 验收点名的两段**逐字**断言（`RelayEndpointStoreTest.kt` ⑫ 未知字段保留 / ⑩ 悬空默认清理）

以下两段是从 `core/engine/src/test/kotlin/works/earendil/pihost/core/engine/settings/RelayEndpointStoreTest.kt`
逐字复制（⑩ = 行 357-391，⑫ = 行 415-448），未做任何改写、省略或重排。

### 9.1 ⑩ clear 同步清悬空 default（行 357-391，用例 18 / 19）

```kotlin
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
```

### 9.2 ⑫ 未知字段保留（行 415-448，用例 21 / 22）

```kotlin
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

        saveRelay(baseUrl = "https://new.example.com/v1", modelIds = listOf("new"), apiKey = null)

        val entry = entryTree("my-relay")
        assertEquals("unmanaged 字段留住，managed 四项被重写", setOf("headers", "baseUrl", "api", "apiKey", "models"), entry.keys)
        assertEquals("relay", entry.getValue("headers").jsonObject.getValue("X-Via").jsonPrimitive.content)
        assertEquals("sk-old", entry.getValue("apiKey").jsonPrimitive.content)
        assertEquals("https://new.example.com/v1", entry.getValue("baseUrl").jsonPrimitive.content)
    }
```

两条合起来覆盖「未知字段保留」的两个层级：**顶层**（`note`/`count`/`flag`/`nil` 与 `providers` 并存）
与 **条目内**（`headers` 与 managed 四项并存）；用例 22 还顺手钉了 §2.1 那条「空白 key = 沿用已存 key」的落盘形态。

## 10. 复现命令（逐条可直接跑）

```bash
# —— 编译机（一次 gradle 一个模块；JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64，gradle 8.13 --no-daemon）——
bash /root/pi/.tools/bbuild.sh sync                     # 本机 → /root/android-host（含 .git，排掉 .gradle/.kotlin）
bash /root/pi/.tools/bbuild.sh gradle ':core:engine:cleanTestDebugUnitTest :core:engine:testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh gradle ':feature:settings:cleanTestDebugUnitTest :feature:settings:testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh gradle ':app:cleanTestDebugUnitTest :app:testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh gradle ':app:assembleDebug'
bash /root/pi/.tools/bbuild.sh raw 'python3 /root/tally.py /root/android-host/core/engine/build/test-results/testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh raw 'aapt2=/root/android-sdk/build-tools/36.0.0/aapt2; $aapt2 dump badging /root/apk-serve/0944f6f3e161/pi-host-full-debug.apk | grep -i "^package:\|^uses-permission"'
bash /root/pi/.tools/bssh.sh get /root/apk-serve/0944f6f3e161/pi-host-full-debug.apk /root/pi/dist/pi-host-full-debug.apk

# —— 本机（dshbox，只跑冻结 JVM 模块）——
cd /root/pi/android-host && JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle \
  --no-daemon :core:process:cleanTest :core:process:test :core:rpc:cleanTest :core:rpc:test --no-build-cache

# —— 红线 / 冻结区复核（本轮实测过的原命令）——
git diff --stat 1c4b4e7..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md \
  tools/capture tools/runtime-packaging feature/chat                              # 期望：无输出
grep -rn "sk-" --include="*.kt" . | grep -v '/build/' | grep src/main            # 期望：无输出（90 处命中全在 src/test）
grep -c 'put(' core/environment/src/main/kotlin/works/earendil/pihost/core/environment/EnvFactory.kt   # 期望：11
grep -c "relay\|Relay\|apiKey\|baseUrl" core/engine/src/main/kotlin/works/earendil/pihost/core/engine/settings/SettingsRepository.kt  # 期望：0
tar -xzf /root/pi/.tools/pi-v1.0.4.tar.gz -C /tmp/pi-verify && \
  diff -rq /tmp/pi-verify/pi-1.0.4 /root/pi/.tools/pi-1.0.4; echo "rc=$?"        # 期望：rc=0 且无输出（pi 零改动）

# —— 任务清单里那两处本轮没做的（不假装做过）——
# §8 第 11 条：中转 key 框的变异检查（删 SettingsScreen.kt:428 一行 → h04 必须红）
# §8 第 12 条：真机「填中转 → 新会话 → 有回复」的端到端验收（两侧均无设备）
```

> 注：`bbuild.sh gradle` 内部已带 `cd /root/android-host`，任务串**只填 gradle 参数**；
> 长跑请以编译机侧的 `tally.py` 输出与 `BUILD SUCCESSFUL` 为准，
> 本地 ssh 包装有重试、退出码不代表远端跑完（本轮就用 `MAX_RETRY=1/2` 掐掉过重试风暴）。

## 11. 计数汇总

- **改动**：16 个路径（新增 4 / 修改 12），`+2303 / −80`，全部落在 `core/engine`、`feature/settings`、
  `app`、`docs` 四个允许区；新增主源码 1017 行、测试侧 1263 行（逐项见 §3）。
- **测试**：编译机 **267** 全绿（environment 5 / engine 116 / chat 47 / sessions 25 / settings 57 / app 17），
  本机 **37** 全绿（process 3 + rpc 34）→ **合计 304 用例，0 红 0 跳过**；
  本轮新增 **53** 条（engine 31 = `RelayEndpointStoreTest` 一类；
  settings 22 = VM t18–t26 九条 + UI h01–h13 十三条）。
  与 P2G 的 251（214 + 37）对得上：214 + 53 = 267，加本机 37 = 304。
- **提交**：`3aed524` → `3f1d10a` → `cc6de20` → `f2f3e7b` → `a3afd67` → 本报告（六条）。
  规格书 §0 的「一次逻辑步骤一个 commit」逐条对上：抽取底座 / store+单测 / 设置面 / 补绑定（一步一提交）/ 版本+测试图 / 文档。
  `f2f3e7b` 单独成一条而不是并入 `cc6de20`，因为它是**另一模块的失败暴露出来的**修复（§8 第 9 条），
  合进去就等于把那次的 `MissingBinding` 从历史里抹掉。
- **产物**：APK **63,826,901 B** / `0944f6f3e16124c5b0802e9d60ac7e3e7429149e9eb363e09f9d1752c8907f31`，
  双端逐字相等；`/root/apk-serve/0944f6f3e161/` + `/root/pi/dist/`，`index.html` 已更新两条并列。
- **门槛对照**：`RelayEndpointStoreTest` 31（门槛 ≥12，①–⑫ 全覆盖）/ 中转 UI 用例 13（门槛 ≥8）/
  `assembleDebug` SUCCESSFUL / badging `4` + `0.1.3` / P2H 窗口冻结区 diff 空 / `sk-` 只在测试 /
  EnvFactory 11 键 / pi 源码 `rc=0` 零改动 / 磁盘 5.0G ≥4G
  → §3.4 与 §4 的验收项**全部达标**；两处如实标注的未执行（变异检查、真机端到端）见 §8 第 11/12 条与 §10 末段。
