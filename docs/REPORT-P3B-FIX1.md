# REPORT-P3B-FIX1 — 中转模型思考档修复（versionCode 7 / 0.1.6）

规格书：`/root/pi/.tools/qoder-task-p3b-fix1.md`（P3B 后续修复，用户反馈 v0.1.5「模型思考无法调整」）。
本轮窗口：`7754e45..HEAD`（三条 commit，见 §6）。
构建与测试全部在编译机 `64.90.11.50:/root/android-host` 上跑，**一条 gradle 命令一个模块**；本机零 Android 构建。
文中每一条数字都是命令回显，未执行的步骤在 §8 明写「未执行」。

---

## 1. 根因链（规格书 §1 八条事实 + 行号证据）

用户现象：**10 个中转 provider 全部复现**，模型 chip 的思考档行永远只有 `off` 一档，且切换档位「看起来成功」。

链条自上而下只有一处断裂，就在 app 的写盘侧：

| # | 事实 | 证据（行号级） |
|---|---|---|
| 1 | app 写 `models.json` 时每个模型只写 `{"id":"…"}`，**从不写 `reasoning`** | 改前 `RelayEndpointStore.kt:221-223`（`modelsArray(modelIds: List<String>)` 单字段 `put(FIELD_ID, …)`）；本轮改动的 `git diff` 首屏即是这段 |
| 2 | pi 读盘对缺省的处理是 **`?? false`** | `packages/coding-agent/src/core/provider-composer.ts:236`：`reasoning: definition.reasoning ?? false,`（规格书引的 231 行是同一句的上下文，本机源码树实测行号为 236） |
| 3 | `applyModelsJson` 对同名 id 是**整体替换**，不继承内置目录的 reasoning | 同文件 `:300`（函数头）与 `:332-335`：`findIndex(… model.id === definition.id)` → `models[existingIndex] = model`（**替换**，不是合并）。所以把中转 providerId 起成已知目录 id 也拿不到思考旗，必须显式写 |
| 4 | 思考档表由这一个布尔决定 | `packages/ai/src/models.ts:1222-1231`：`if (!model.reasoning) return ["off"]`；true 才回 `off/minimal/low/medium/high`（`xhigh/max` 另需 `thinkingLevelMap`） |
| 5 | 设档时**钳制静默、RPC 仍回成功** | `packages/coding-agent/src/core/agent-session.ts:2600`（`availableLevels.includes(level) ? level : clampThinkingLevel(...)`）+ `:2669-2670`；钳回后 `isChanging=false` → 不发 `thinking_level_changed`。这就是「表面成功实际钳死」——用户的投诉原话对应这一条 |
| 6 | 声明 `reasoning:true` 后请求侧**真的**带参数 | `packages/coding-agent/src/core/compaction/compaction.ts:606` 与 `bug-report.ts:352` 同一条件式：`model.reasoning && thinkingLevel && thinkingLevel !== "off"` → 写 `options.reasoning`。不是只通 UI 的摆设 |
| 7 | schema 本来就收这个字段 | `packages/coding-agent/src/core/model-config.ts:204` / `:220`：`reasoning: Type.Optional(Type.Boolean())` |
| 8 | app 展示侧无 bug | `feature/chat/.../ui/ModelChip.kt:127-145` 忠实渲染 RPC 回的 `thinkingLevels`；本轮 `feature/chat` 代码与测试**零改动**（§5 实测 0 字节） |

桌面探测实录 `/root/pi/.capture/probe-think/think.jsonl`（规格书 §1 第 6 条）：
`bare-1`（裸 id）→ `{"levels":["off"]}`、`set_thinking_level high` 回 `success:true` 且**无事件**；
`full-1`（带 `"reasoning":true`）→ 五档、`set high` 发 `thinking_level_changed{level:"high"}`。

本轮又按**改后写盘的真实字节**补了一次端到端探测，见 §7。

**结论 = 修复面**：`core/engine` 的 `RelayEndpointStore`（按模型写 `reasoning`）+ `feature/settings` 的表单（`*` 语法入口 + 编辑回填）。
一句话根因：**app 从来没写过 pi 判断思考档所依赖的那一个字段。**

---

## 2. 修复面（改动清单 + 签名前后）

窗口内**代码面 12 个路径，全部为修改（0 新增文件），+649 / −109**（不含 §6 那条 `docs` commit 的本报告；`git diff --name-only 7754e45..HEAD` 连报告共 13 个路径）：

```
app/build.gradle.kts                                              |   4 +-
app/src/test/kotlin/works/earendil/pihost/AppTestDoubles.kt        |   4 +-
app/src/test/kotlin/works/earendil/pihost/ManifestPermissionTest.kt|  22 +-
core/engine/.../core/engine/settings/RelayEndpointStore.kt         | 109 ++++--
core/engine/.../core/engine/settings/RelayEndpointStoreTest.kt     | 224 ++++++++--
feature/settings/.../feature/settings/RelayEndpoints.kt            |  73 ++--
feature/settings/.../feature/settings/SettingsViewModel.kt         |  31 +-
feature/settings/.../feature/settings/ui/SettingsScreen.kt         |  30 +-
feature/settings/.../feature/settings/ui/SettingsTags.kt           |  25 +-
feature/settings/src/test/.../Fakes.kt                              |  11 +-
feature/settings/src/test/.../SettingsViewModelTest.kt              | 133 ++++++-
feature/settings/src/test/.../ui/SettingsScreenTest.kt              |  92 +++++-
```

### 2.1 §3.1 core/engine（`RelayEndpointStore.kt`）

| 位置 | 前 | 后 |
|---|---|---|
| 模型项类型 | —（只有 `List<String>`） | 新增 `data class RelayModel(val id: String, val reasoning: Boolean = false)`（**类外、同包**，§3.1 第 1 条） |
| 写盘签名 | `fun save(providerId: String, baseUrl: String, api: String, modelIds: List<String>, apiKey: String?)` | `fun save(providerId: String, baseUrl: String, api: String, models: List<RelayModel>, apiKey: String?)` |
| 落盘形状 | `modelsArray(modelIds: List<String>)` → 每项 `{"id":"…"}` | `modelsArray(models: List<RelayModel>)` → true 项 `{"id":"…","reasoning":true}`，false 项**省略字段**（`?? false` 与写 false 等价，文件保持精瘦） |
| 读侧 | `RelayEntry.modelIds: List<String>` / `JsonObject.modelIdList()` | `RelayEntry.models: List<RelayModel>` / `JsonObject.modelEntries()`；新增 `JsonObject.reasoningFlag()`：**只认真布尔 true**（缺/false/`"true"`/`1`/`null`/对象 → false） |
| 清洗 | `modelIds.map{trim}.filter{isNotEmpty}.distinct()` | 私有 `cleanModels(List<RelayModel>)`：trim、去空 id、按 id 去重、**同 id 混旗取 OR**，顺序 = 首次出现 |
| 字段常量 | — | 新增 `private const val FIELD_REASONING = "reasoning"` |

类头「形状」一节补了 `reasoning` 是思考档唯一开关的完整因果（含 `applyModelsJson` 整体替换那条），`save` 的 `@throws` 文案随签名改写。

### 2.2 §3.2 feature/settings（`*` 语法 + 回填）

| 位置 | 前 | 后 |
|---|---|---|
| `RelayEndpoints.kt` | `fun parseModelIds(text: String): List<String>` | `fun parseRelayModels(text: String): List<RelayModel>`（`parseModelIds` 删除）；新增 `const val REASONING_MARK = '*'` 与逆函数 `relayModelsToText(models)` |
| `RelayEndpointSource.save` | `modelIds: List<String>` | `models: List<RelayModel>`；`FileRelayEndpointSource.save` 同步转发（只做调度，不建第二份真相） |
| `SettingsViewModel.saveRelay` | `(providerId, baseUrl, api, modelIds: List<String>, apiKey)` | `(providerId, baseUrl, api, models: List<RelayModel>, apiKey)`，第三道门 `modelIds.isEmpty()` → `models.isEmpty()` |
| `SettingsViewModel` | **完全没有草稿播种口**（本轮实测：表单只有 `onRelayDraftChange` 一条路） | 新增 `fun editRelay(providerId: String)`：把已存条目播种回 draft，`models = relayModelsToText(entry.models)`、`apiKey = ""`（§2 红线不回显）、`api` 跟行走；行不存在 → 静默不播种 |
| `SettingsScreen.kt` | `RelaySection(…, onSave, onSetDefault, …)` | 多一个 `onEdit: (providerId: String) -> Unit`（接 `viewModel::editRelay`）；行末「编辑」按钮（放在「设为默认」之前）；`val models = parseRelayModels(draft.models)`；保存按钮 enable 条件与入参换型；行文本 `entry.models.size` / `entry.models.firstOrNull()?.id` |
| `SettingsTags.kt` | `RELAY_MODELS_HINT` 只说「逗号分隔」 | 文案说出 `*` 语法与后果（「标了才有五档，不标只有 off」）；新增 `RELAY_MODELS_EXAMPLE = "deepseek-chat,deepseek-reasoner*"`（**placeholder 与 supportingText 共用这一个常量**）、`RELAY_EDIT = "编辑"`、`fun relayEdit(providerId)`；`init { check(...) }` 补 relayEdit 与 clear/set-default/key 四个 tag 不撞车 |

语法四条规则（`t33`/`t34` 钉死）：分隔符半角 `,` + 全角 `，`；项尾缀半角 `*` = 思考模型（`a**` 合法 = `(a,true)`；全角 `＊` 与中间位置的 `*` **都不是语法**）；trim/去空/按 id 去重且同 id **OR 合并**；剥掉尾缀后只剩空白或只剩 `*` 的项丢弃（`"* *"` → 无效，绝不落盘成「只有语法字符没有 id」的模型）。
core 侧第二道门这四条里除最后一条外逐字同语义；最后一条守的是 `*` 这层**表单语法**，core 收到的是已解析的 `RelayModel`，不该再认星号（手改 `models.json` 写一个名叫 `*` 的模型是用户的自由）——这个不对称写在 `RelayEndpoints.kt` 的 KDoc 里，不是暗差异。

### 2.3 §3.3 app 版本

`app/build.gradle.kts`：`versionCode = 6 → 7`、`versionName = "0.1.5" → "0.1.6"`（该文件本轮唯一改动，见 §5）。
`ManifestPermissionTest` ③ 随动：`6L → 7L`、`"0.1.5" → "0.1.6"`、单调性断言改为 `> 6L`（「必须严格高于 P3B 的 6，否则带 bug 的旧包覆盖不掉」）；
`AppTestDoubles` 两处叙述（版本注脚、`RelayEndpointStoreTest` 用例数 31 → 37）同步，避免注释与磁盘分叉。

---

## 3. 测试矩阵（编译机，一条 gradle 命令一个模块，2026-10-09）

计数取自各模块 `build/test-results/<task>/TEST-*.xml` 头部 `tests= / skipped= / failures= / errors=`，逐文件相加；
task 名按模块性质区分（`core/process`、`core/rpc` 是纯 JVM 模块用 `test`，Android 模块用 `testDebugUnitTest`）。

| 模块 | task | 用例 | skipped | failures | errors | 本轮 |
|---|---|---:|---:|---:|---:|---|
| `core/process` | `test` | 3 | 0 | 0 | 0 | UP-TO-DATE（源码本轮 0 改动） |
| `core/rpc` | `test` | 34 | 1 | 0 | 0 | UP-TO-DATE（源码本轮 0 改动） |
| `core/environment` | `testDebugUnitTest` | 5 | 0 | 0 | 0 | UP-TO-DATE（源码本轮 0 改动） |
| **`core/engine`** | `testDebugUnitTest` | **147** | 2 | 0 | 0 | **本轮执行**，基线 141 → **+6** |
| `feature/chat` | `testDebugUnitTest` | 147 | 0 | 0 | 0 | **本轮执行**（零改动回归，规格书点名必须绿） |
| `feature/sessions` | `testDebugUnitTest` | 25 | 0 | 0 | 0 | 本轮执行 |
| **`feature/settings`** | `testDebugUnitTest` | **77** | 0 | 0 | 0 | **本轮执行**，基线 69 → **+8** |
| `app` | `testDebugUnitTest` | 24 | 0 | 0 | 0 | 本轮执行（③ 的期望值随 §3.3 换数字，条数不变） |
| **合计** | | **462** | 3 | **0** | **0** | 448 基线**全保** + **14 条新增** |

规格书 §3.4 点名的五条命令，逐条真实 rc：

| # | 命令（本机发起，经 `p3b-gradle.sh` = sync + 重建 `local.properties` + 单条 gradle） | rc | 输出尾行 |
|---|---|---|---|
| 1 | `:core:engine:test` | **0** | `BUILD SUCCESSFUL in 55s` / `116 actionable tasks: 1 executed, 115 up-to-date` |
| 2 | `:feature:settings:test` | **0** | `BUILD SUCCESSFUL in 3m 8s`（**中间有两次红，见 §8.1/§8.2**）；提交后复跑再一次 `BUILD SUCCESSFUL in 3m 2s` rc=0 |
| 3 | `:feature:chat:test` | **0** | `BUILD SUCCESSFUL in 2m 42s` / `156 actionable tasks: 16 executed` |
| 4 | `:app:test` | **0** | `BUILD SUCCESSFUL in 4m 53s` / `279 actionable tasks: 61 executed` |
| 5 | `:app:assembleDebug` | **0** | `BUILD SUCCESSFUL in 1m 37s`（提交态复跑：`42s / 159 actionable tasks: 159 up-to-date`，sha 不变 → 交付的包就是 HEAD 的树） |

另外三条为凑齐同一轮的全仓 XML 而顺带跑（不在 §3.4 五条内）：`:core:process:test` rc=0（32s）、`:core/rpc:test` rc=0（37s）、`:core:environment:testDebugUnitTest` rc=0（38s）、`:feature:sessions:testDebugUnitTest` rc=0（1m35s）。

### 3.1 新增 14 条用例逐条名单

`core/engine` `RelayEndpointStoreTest`（31 → 37 条）：

- `32 save writes reasoning true only on the flagged models` —— 逐字节钉死落盘文本：
  `{"providers":{"my-relay":{"baseUrl":…,"api":…,"apiKey":…,"models":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner","reasoning":true},{"id":"gpt-4o"}]}}}`
- `33 reasoning round-trips through save and reads a hand written true` —— app 自写的读得回；**手写**的 `"reasoning":true` 同样读得回（用户会直接编辑这份文件）
- `34 only a real json true counts as reasoning` —— 同一份文件里塞 `false`/`"true"`/`1`/`null`/`{"deep":true}`/裸字符串项/空白 id，只有真 `true` 那一项为 true
- `35 cleaning merges the same id with OR and keeps first-seen order` —— 第二道门的 OR 语义与顺序
- `36 a list of blank ids is rejected after cleaning without touching the file` —— 清洗后为空 → `require` 拦下，磁盘一次都不碰
- `37 clear and setDefault are untouched by the reasoning field` —— 撤销与钉选通路不被新字段带偏

`feature/settings` `SettingsViewModelTest`（36 → 41 条）：

- `t33 the star suffix marks a reasoning model and tolerates repeats and padding`（规格书原文那串 `deepseek-chat,deepseek-reasoner*,gpt-5*`；`a**`；`a＊` 不是语法；`a*b` 中间不算后缀；裸 `*` 与 `"*, ,* *"` 全为无效项）
- `t34 duplicate ids merge with OR instead of letting false swallow true`
- `t35 the draft text and the parse are exact inverses`（`relayModelsToText` ↔ `parseRelayModels` 无损往返）
- `t36 editing a relay seeds the draft with the star and never the key`（回填带 `*`、`apiKey` 仍为空、api 跟行走，且不发一次写入）
- `t37 editing a vanished row leaves the draft alone`

`feature/settings` `SettingsScreenTest`（33 → 36 条）：

- `h14 editing a row brings the star back and saving keeps the reasoning flag` —— 点「编辑」→ 四个框真值 `["my-relay","https://relay.example.com/v1","deepseek-chat,deepseek-reasoner*",""]` → 点保存 → source 收到的仍是那两条带旗的清单、key 为 `null`、列表只有一条行
- `h15 the models field spells the star syntax out on screen` —— 语法与例子必须作为**可见文本**上屏
- `h15b the placeholder and the hint carry the very same example` —— placeholder 与 supportingText 共用同一个常量（改一处漏一处的防线；理由见 §8.2）

旧用例适配（不算新增）：`RelayEndpointStoreTest` 24 处、`SettingsViewModelTest` 9 处、`SettingsScreenTest` 10 处、`Fakes.kt` 的 `Save` 记录与 `FakeRelayEndpointSource.save` 换型；`t26` 由 `parseModelIds` 改名 `parseRelayModels` 并补 `RelayModel` 期望。

---

## 4. APK 指纹（§3.5 交付）

| 项 | 值（全部为命令回显） |
|---|---|
| 产物 | 编译机 `/root/android-host/app/build/outputs/apk/debug/app-debug.apk` |
| 字节数 | `63928829`（`stat -c %s`）；相对 P3B `63925997` → **+2,832 B** |
| sha256 | `7e5de17aea14f3cd1abcc44f1b7b43e97a980a06c571226612c61a0aacdee779` |
| 槽位目录 | `/root/apk-serve/7e5de17aea14/pi-host-full-debug.apk`（sha 前 12 位命名，权限 644） |
| 槽位文件指纹 | `63928829` / `7e5de17aea14f3cd…`（与产物逐字相同） |
| 下载 URL | `http://64.90.11.50:8000/7e5de17aea14/pi-host-full-debug.apk` |
| HTTP 实测 | `HTTP/1.0 200 OK`，`Content-Length: 63928829`，`Content-type: application/vnd.android.package-archive` |
| 本机拉回 | `curl -s -f` → `/root/pi/dist/pi-host-full-debug.apk`：`63928829`、sha256 `7e5de17aea14f3cd1abcc44f1b7b43e97a980a06c571226612c61a0aacdee779` —— **双端逐字相等** |
| badging | `package: name='works.earendil.pihost' versionCode='7' versionName='0.1.6'`，`targetSdkVersion:'35'` |
| uses-permission | 五条，与 P3B 同集合：`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` / `INTERNET` / `works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（**零新增**） |
| `lib/arm64-v8a/*` 名单 diff | `SO_LIST_DIFF=EMPTY`（29 个，与 P3B 逐项相同） |
| `assets/*` 名单 diff | `ASSET_LIST_DIFF=EMPTY` |
| APK 总条目 | `183`（P3B 亦 `183`）—— 增量只在 dex 一侧，与修复面只有 Kotlin 改动一致 |
| 下载页 | `/root/apk-serve/index.html` 新增 `class="latest"` 行（旧 P3B/P3A/P2H/P2G 四行原样保留）；槽位串命中 3 处、完整 sha256 命中 1 处；`http://64.90.11.50:8000/index.html` 亦 `200` |
| 编译机磁盘 | `/dev/vda1 49G 44G 4.5G 91% /` |

---

## 5. 红线复核（规格书 §2 逐条，全部实测）

```
$ git diff 1f77127..HEAD -- core/rpc | wc -c                  → 0
$ git diff 1f77127..HEAD -- core/process/src/main | wc -c     → 0
$ git diff 1f77127..HEAD -- core/environment | wc -c           → 0
$ git diff 1f77127..HEAD -- docs/protocol.md | wc -c           → 0
$ git diff 1f77127..HEAD -- tools/capture | wc -c              → 0
$ git diff 1f77127..HEAD -- tools/runtime-packaging | wc -c    → 0
$ git diff 7754e45..HEAD -- feature/chat | wc -c               → 0   （本轮区间零改动；代码与测试都没动）
$ git diff 7754e45..HEAD -- app/src/main/AndroidManifest.xml | wc -c → 0
$ git diff 7754e45..HEAD -- gradle/libs.versions.toml | wc -c  → 0
$ git diff 7754e45..HEAD --name-only -- '*build.gradle.kts'    → 只有 app/build.gradle.kts
$ git diff 7754e45..HEAD -- '*build.gradle.kts' | grep '^+'     → 仅 `versionCode = 7` / `versionName = "0.1.6"` 两行
$ grep -rn "sk-" --include="*.kt" */src/main | wc -l           → 0
$ find /root/pi/.tools/pi-1.0.4 -type f -newermt "2026-10-09 00:00" | wc -l → 0（pi 源码树零修改）
```

凭据面：`RelayEntry` 仍只有 `keyConfigured` 布尔，`apiKey` 明文既不进 `RelayEntry` 也不进日志；
`editRelay` 播种草稿时 `apiKey = ""`（`t36`/`h14` 各钉一次）；「留空 = 沿用已存 key」的换算（空白 → `null`）由 `t20` 继续守着。
`models.json` provider 条目里的非托管字段：`save()` 的读-改-写与 `with()` 语义一字未动，旧用例 `28 an api value outside the picker list still round-trips`、`09 a dollar-ENV key is stored verbatim` 原样绿。
不新增依赖；`feature/chat` 零改动。

---

## 6. commit 与复现命令

本轮 commit（§0 前缀纪律，窗口 `7754e45..HEAD`；逐条实参见 `git log --oneline 7754e45..HEAD`）：

| commit | 步骤 | 面 |
|---|---|---|
| `9479b22` `feat(p3b-fix1)` | §3.1 + §3.2（签名变更必须同 commit，否则整树编译红） | core/engine 1 + feature/settings 4（main）+ 4（test）共 9 个路径 |
| `1a4ec50` `feat(p3b-fix1)` | §3.3 版本 7 / 0.1.6 | `app/build.gradle.kts` + `ManifestPermissionTest` + `AppTestDoubles` |
| `ba5f443` 起若干条 `docs(p3b-fix1)` | §3.6 与自校 | 本报告 `docs/REPORT-P3B-FIX1.md`：首条 `ba5f443`，其后 `13614ea`、`0036399` 与承载本行的这最后一条（自校只动本文，不碰代码）。commit 总条数以 `git log --oneline 7754e45..HEAD` 为准 |

```bash
# 第 0 步（每条 gradle 命令都要，sync 会 gitignore 掉 local.properties）：
#   p3b-gradle.sh 内部已含这一步，无需手敲
bash /root/pi/.tools/p3b-gradle.sh :core:engine:test          # 55s  rc=0
bash /root/pi/.tools/p3b-gradle.sh :feature:settings:test     # 3m8s  rc=0
bash /root/pi/.tools/p3b-gradle.sh :feature:chat:test         # 2m42s rc=0
bash /root/pi/.tools/p3b-gradle.sh :app:test                  # 4m53s rc=0
bash /root/pi/.tools/p3b-gradle.sh :app:assembleDebug         # 1m37s rc=0

# 交付（编译机侧）：sha256 前 12 位建槽位 → cp → curl 拉回本机 → 比 sha256
SLOT=$(ssh … 'sha256sum app/build/outputs/apk/debug/app-debug.apk' | cut -c1-12)
ssh … "mkdir -p /root/apk-serve/$SLOT && cp -f app/build/outputs/apk/debug/app-debug.apk /root/apk-serve/$SLOT/pi-host-full-debug.apk"
curl -s -f -o /root/pi/dist/pi-host-full-debug.apk http://64.90.11.50:8000/$SLOT/pi-host-full-debug.apk
sha256sum /root/pi/dist/pi-host-full-debug.apk      # 7e5de17aea14f3cd…（与编译机一致）

# 用例计数（XML 头逐文件相加）
bash /root/pi/.tools/bbuild.sh raw 'cd /root/android-host && <上面 §3 的计数脚本>'
```

---

## 7. 端到端探测（本轮新跑，`/root/pi/.capture/probe-fix1/`）

规格书 §1 的探测用的是为实验造的 `bare-1` / `full-1` 两个模型；本轮把 **§3.1 落盘的真实字节**（用例 32 断言的那串文本，只把 baseUrl 换成 mock 端口）直接喂给桌面基准 pi 1.0.4，验一次「app 写出去的形状」在 pi 侧到底得到几档。

`home/.pi/agent/models.json`（逐字节 = 改后 `save()` 的输出）：

```json
{"providers":{"my-relay":{"baseUrl":"http://127.0.0.1:8787/v1","api":"openai-completions","apiKey":"sk-relay-test-123","models":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner","reasoning":true},{"id":"gpt-4o"}]}}}
```

命令脚本 `cmds.json` 八条（`get_available_models` → 选无旗模型问档并设 high → 选有旗模型问档并设 high → `get_state`），
驱动脚本沿用 P3B 的 `/root/pi/.tools/p3b-probe.js`（冻结区 `tools/capture/` 零改动），原始字节落 `fix1.jsonl`（9 行，sha256 前 16 位 `0780e7296b269af5`）：

```json
{"id":"f02","type":"response","command":"set_model","success":true,"data":{"id":"deepseek-chat", … "reasoning":false, …}}
{"id":"f03","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":["off"]}}
{"id":"f04","type":"response","command":"set_thinking_level","success":true}          ← 无 thinking_level_changed 事件
{"id":"f05","type":"response","command":"set_model","success":true,"data":{"id":"deepseek-reasoner", … "reasoning":true, …}}
{"id":"f06","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":["off","minimal","low","medium","high"]}}
{"type":"thinking_level_changed","level":"high"}                                       ← 事件出来了
{"id":"f08","type":"response","command":"get_state","success":true,"data":{"model":{"id":"deepseek-reasoner","provider":"my-relay", … "reasoning":true, …}}}
```

`f08` 的 `data` 里 `thinkingLevel` 为 `high`（清点脚本回显 `thinkingLevel=high model=deepseek-reasoner`）。
这条把根因链两头接上了：**同一份文件里，省略 `reasoning` 的项只有一档、写了 `true` 的项有五档且设档真发事件**，
而 app 现在对带 `*` 的模型写的就是后者。

---

## 8. 偏差、遗留与未执行

### 8.1 本轮真实出现过的一次红（已修，非绕过）

`:feature:settings:test` 第二次跑（编译过了、用例红一条）：

```
SettingsViewModelTest > t33 the star suffix marks a reasoning model and tolerates repeats and padding FAILED
77 tests completed, 1 failed
java.lang.AssertionError: 裸星号与空白混在一起也只剩无效项 expected:<[]> but was:<[RelayModel(id=* , reasoning=true)]>
```

原因：`parseRelayModels("* *")` 用 `trimEnd('*')` 剥完得到 `"* "`，留着它 = 往 `models.json` 写一个只有语法字符、没有 id 的模型。
修的是**主代码**（不是改期望值）：剥完尾缀再 `trim()` 一次，且 `id.all { it == '*' }` 也算无效项丢弃；
顺带把「双闸语义」注释校准——表单门比 core 门多守一条（理由见 §2.2 末段）。修后 `BUILD SUCCESSFUL`，77 条全绿。

### 8.2 一次编译红与被换掉的断言法

h15 最初想用 `SemanticsProperties.Placeholder` 单独问占位符，编译失败（`Unresolved reference 'Placeholder'`，两次 variant 都红）。
在编译机对 `ui-release-api.jar` 跑 `javap` 实证：这版 Compose 的 `SemanticsProperties` **没有 `Placeholder` 这个键**
（有 `Text` / `EditableText` / `InputText` / `ContentDescription` 等）。
于是改为把「例子」抽成 `SettingsStrings.RELAY_MODELS_EXAMPLE` 这一个常量，placeholder 与 supportingText 共用它，
再用 `h15b` 钉两处同源（屏幕上看得见的那条必然含这串例子）。
这条断言方式与规格书 §3.2「文案更新」不冲突，但**不是**「在语义树里找到占位符」——按实机可观测面做了替换，如实记此。

### 8.3 真机验证未执行

```
$ which adb        → （本机无 adb）
$ adb devices      → 未执行
```

- `adb install -r` 覆盖安装 0.1.6 到用户设备：**未执行**（本机没有 adb，也没有连接的设备/模拟器）。
  用户侧一条命令即可：`adb install -r /root/pi/dist/pi-host-full-debug.apk`（或浏览器直接下 §4 的 URL）。
- 真机上的两条实机回归留给用户清单：
  1. **`*` 的输入法实机回归**：中文输入法（尤其九宫格 / 候选栏里 `＊` 与 `*` 并存）打出的星号是否为**半角**——
     全角 `＊` 按设计**不是语法**（`t33` 钉住），标了等于没标，用户会以为「还是调不动」。
     这是 `*` 这个语法入口唯一的环境依赖，屏幕上无法自证。
  2. **旧数据不自动获得思考档**：修复只影响**新保存**的那一行。v0.1.5 上已配好的 10 个中转，
     磁盘上仍是 `{"id":"…"}`，pi 仍判 false。用户需要逐行点「编辑」→ 在要开思考档的模型 ID 后加 `*` → 保存
     （或手改 `filesDir/home/.pi/agent/models.json`——读口认手写的 `true`，用例 33 守着）。
     **本轮刻意不做「迁移式自动补旗」**：哪些模型真有思考档只有用户/中转站知道，app 猜 = 替用户发出他没要求的请求参数。
- 桌面端到端探测（§7）**已执行**，但它代替不了 1 和 2：真机 APK 的 dex 与 Compose 运行时行为不在其中。

### 8.4 其它偏差

| 项 | 规格书 | 实际 |
|---|---|---|
| §3.4 命令 | `:<mod>:test` | 一律用 `test`（Android 模块的 `test` 同时跑 debug+release 两个 variant，两条都绿；§3 的**计数**按既有惯例只取 `testDebugUnitTest`，与 P3B/P3A 的 448 基线同一口径） |
| §3.1 第 1 条位置 | 「同文件或同包文件」 | 放同文件类外（`RelayEndpointStore.kt` 末尾），避免为一个两字段数据类多开一个公共 API 文件 |
| §3.2 第 2 条回填 | 「找到 draft 播种点改」 | 实测**没有任何播种点**（表单草稿只能由 `onRelayDraftChange` 逐字段改），故新增 VM `editRelay` + 行末「编辑」按钮 + `relayEdit` tag。回填因此有了用户可及的入口，也才有 `h14` 那条闭环可测 |
| §0 porcelain 纪律 | 每步开工前为 0 | §3.1/§3.2/§3.3 三步合并到「实现 + 各自测试」时，中间态确为非 0（同一 commit 内必须一起改完才能编译），提交后为 0；§4 末次实测 `git status --porcelain | wc -l` = 0（本文件 commit 前除外） |
| 规格书行号 | `provider-composer.ts:231` / `:327-332`、`model-config.ts:199-221` | 本机源码树实测为 `:236` / `:300+332-335` 与 `:204/:220`。语义与规格书逐字一致，只是行号漂移；本文按实测行号书写 |
| 构建机环境 | — | `bbuild.sh sync` 用 `rsync --delete`，会删掉 gitignored 的 `local.properties`；每条 gradle 前由 `p3b-gradle.sh` 重建（`sdk.dir=/root/android-sdk`），沿用 P3B 同一条坑的处理 |
| SSH 抖动 | — | 本轮出现过 3 次 `Connection timed out` / `rsync error (code 255)`，均由 `MAX_RETRY` 重试吸收；一次红重跑发生在重试之后的同一条命令上，未污染结论 |

---

## 9. §4 验收清单（逐项打勾 + 真实回显）

- [x] **冻结区六路径 `git diff 1f77127..HEAD` 逐项 0 字节** → `0 / 0 / 0 / 0 / 0 / 0`（§5 命令块）
- [x] **`feature/chat` `git diff` 零改动** → `git diff 7754e45..HEAD -- feature/chat | wc -c` = `0`；且 `:feature:chat:test` rc=0、147 条全绿
- [x] **`grep -rn "sk-" --include="*.kt" */src/main` = 0** → `0`
- [x] **工作树 porcelain = 0** → docs commit 之后为 0（`9479b22` / `1a4ec50` 之后各自实测为 0）
- [x] **五条 gradle 全 rc=0，failures=0 errors=0** → §3 表：`55s / 3m8s / 2m42s / 4m53s / 1m37s` 五条 rc=0；全仓 462 条 `fail=0 err=0`
- [x] **APK badging：versionCode=7 / versionName=0.1.6** → `package: name='works.earendil.pihost' versionCode='7' versionName='0.1.6'`
- [x] **apk-serve URL HTTP 200，index.html 两处收录** → `HTTP/1.0 200 OK` + `Content-Length: 63928829`；`index.html` 内 `7e5de17aea14` 命中 3 处、完整 sha256 命中 1 处，旧四行保留
- [x] **REPORT-P3B-FIX1.md §1–§8 完整** → 本文件（§1 根因链 / §2 修复面 / §3 测试矩阵 / §4 APK 指纹 / §5 红线 / §6 commit+复现 / §7 端到端探测 / §8 偏差与遗留）
