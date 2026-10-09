# REPORT-P3B — 转录回填 + 流式插话 + 运行时模型切换 + 会话统计条（versionCode 6 / 0.1.5）

规格书：`/root/pi/.tools/qoder-task-p3b.md`（P3 第二批：把「看不见的东西」补上——历史、插话、模型、token）。
交付窗口：`c1513be`（REPORT-P3A 收尾）→ `92c15da`，**一小节一条 commit**：
`8b780d9`(§3.1) / `6bee7a2`(§3.2) / `8124888`(§3.3) / `f287653`(§3.4) / `0430cb9`(§3.5 版本+权限) /
`92c15da`(§3.5 附带：`:app` 竞态修) / 本报告与后续 docs 条目。

## 1. 结论摘要

- 四个能力全部落地、全部有实测用例：
  ①**§3.1 转录回填**——切回旧会话不再一片空白（`get_messages` 只读回填 + 「历史加载失败」不抹状态）；
  ②**§3.2 流式插话**——`steer`/`follow_up` 两条队列可视化与两个 mode 开关，流式期间输入框不再锁死；
  ③**§3.3 运行时模型切换**——顶栏模型 chip + 选择器对话框 + 思考档白名单，形状先探测后实现；
  ④**§3.4 会话统计条**——`tokens｜cost｜ctx` 一行式 + 展开明细 + 压缩确认 + 自动压缩开关。
- 测试：**355（P3A 基线）→ 448**（新增 93：`:feature:chat` +92、`:app` +1），
  `failures=0 errors=0`，唯一 skip 仍是 `:core:rpc` 的 `PiDesktopE2eTest`（环境门控，P2H 起如此）。
- **pi 侧零改动**：`find /root/pi/.tools/pi-1.0.4 -newermt '1 day ago' -type f` = **0**；
  冻结区六条路径 `git diff 1f77127..HEAD` 依旧**为空**（§5）。
  本轮新增的三个线协议未建模事件（`compaction_start`/`compaction_end`/`thinking_level_changed`）
  全部走 `core/rpc` 冻结区**自带**的 `PiEvent.Unknown(raw: JsonObject)` 出口在 `:feature:chat` 消费，
  没有为了新功能动过一条事件模型。
- §2 红线逐条复核见 §4，改动面只有 `feature/chat`（22 个路径 = 10 新增 / 12 修改，+4293/−62）
  与 `app` 的版本两行 + 测试四条；`feature/settings`、`feature/sessions`、`AndroidManifest.xml` **一字未动**。
- APK：**63,925,997 B**，sha256 `8f02f9bda5f953be468e9c4a4266f6c58ad55c782eb9cd8ebf03c46051eb9f24`，badging 实测 `versionCode='6' versionName='0.1.5'`；
  双端（编译机 `/root/apk-serve/8f02f9bda5f9/` + 本机 `/root/pi/dist/`）逐字相等，下载 200（§7）。
  包内 29 个 `.so` 与 `assets/runtime/*` 与 P3A **名单逐字一致**（§7 有 diff 命令与输出）。

## 2. 交付物落点（要求 → 代码 → 证据）

### 2.1 §3.1 转录回填（`8b780d9`）

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| `ChatHistory.parseMessages(data)` / `toTranscriptItems(messages, idAlloc)` 两个纯函数，放 `feature/chat` 非 `ui` 包 | `ChatHistory.kt:34` / `:65`（形状不对返回 **null 不抛**） | `ChatHistoryTest` h01–h07 是 parse 面：h01 system 保留 sections 外壳、h02 user 的 text 块、h03 assistant 的 `stopReason`/`usage`/`responseId`、h04 toolResult 的 `callId`/`toolName`/`isError`、h05 空数组 = 0 条而不是形状失败、h06 各种错形状一律 null 不抛、h07 单个坏条目跳过不拖垮整页 |
| role 映射：system 跳过 / user→UserItem（数组取全部 text 块拼接，string 直接用）/ assistant→AssistantItem 终态（`stopReason=error` 且正文空→NoteItem）/ toolResult→ToolItem（`isError` 决定终态/失败态） | 同文件 `toTranscriptItems` 的 `when (role)` | h08 四类 role → 聊天页已有的那四类 item、h09 成功 toolResult 映射成 ok 灯、h10 块数组与裸串两种 user 形状都只出一个气泡、h11 空 user 消息不成泡、h12 error 且无正文 → 既有错误 NoteItem、h13 error 但仍有正文 → 保留气泡、h14 thinking 进 thinking 槽且 toolCall 不当正文、h15 未知 role 与 system-only 历史产出为空（即「system 跳过」） |
| `backfillTranscript()` 在 roll 成功回执后发 `GetMessages`（新 requestId），回执 → parse → toTranscriptItems → `mutateTranscript { list + items }`（append 不清现有） | `ChatViewModel.kt:848`（发送）→ `:870`（`applyBackfill` 唯一落地口） | `case32`（切会话成功即索取新会话历史）、`case37`（外部 roll 也回填，且不再发第二条 `switch_session`）、`case38`（全新会话回 0 条，页面保持干净）、`case33`（回填按转录顺序替换 loading note） |
| 竞态：回填 append 不覆盖流式中的新 item | `list + items` 语义 + 只认最新 `requestId`（`backfillRequestId`） | `case36`（回填与刚发出的 prompt 竞态：追加而非覆盖）、`case39`（上一条会话的迟到回填被忽略） |
| 失败 → NoteItem「历史加载失败」+ logcat，绝不清空/改写 pi 会话文件 | `ChatViewModel.kt:883`（`onBackfillFailure`：`Log.w` + 一条 `NoteItem`，全程只改内存态） | `case34`（被拒的 `get_messages` → 空页 + 可读失败提示）、`case35`（形状不对 = 失败，既不崩也不当成「历史为空」）+ §4 第 7 条的文件 IO grep |
| 回填不记审计（不新增 `AuditKind`） | 回填三函数 `:848`/`:870`/`:883` 内没有 `recordAudit`；全类 3 处命中在 `:923`（权限应答）/`:1033`/`:1055`（事件流），都不在回填路径上 | 代码面自查（无专用用例——h 系列全是纯函数、case32–39 断言的是线协议与转录态，审计那条红线由 grep 面钉住，见 §4 第 9 条） |
| `FakeChatEngine` 扩展：可编程 response 队列（先 send 后 response） | `FakeChatEngine.scriptReply/deliverScripted/pumpScripted` | 实测：就地 `emit` 会赶在收集协程注册之前（`SharedFlow` 无 replay 直接丢值），与真机同形 |
| 测试 ≥8 | h01–h15（15）+ `case32`–`case39`（8） | 23 条 |

### 2.2 §3.2 流式插话（`6bee7a2`）

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| 流式中可插话：`steer` 插到当前轮的下一个工具边界，`follow_up` 等整轮结束 | `ChatViewModel.steer()/followUp()`（空文本不吃、不改 `turnState`、不上用户气泡、不占切换闩） | `ChatViewModelTest` case40–case44 |
| 队列两列可视化（不是一个数字） | `feature/chat/QueueState.kt`：`QueueState(steering, followUp)` + 派生 `depth`；`ChatScreen` 徽标「排队中: steer×N followUp×N ▸」点开逐条列内容（steering 在前，原样截 60 字） | case45–case48 + `ChatScreenUiTest` 两条 |
| 整表替换、不做乐观更新 | 同上（`queue_update` 是唯一写入口；`steer` 的 response 只有 `{disposition:"queued"}`，无内容） | case46 |
| 两个 mode 开关（`all` / `one-at-a-time`） | `setSteeringMode/setFollowUpMode` + `MODE_ALL`/`MODE_ONE_AT_A_TIME` 常量 | case47/case48 |
| 红线 8「App 永不代替用户决定 steer/follow_up 内容」 | 全类没有任何「看到事件就自动插话」的路径；`disposition` 只进 logcat（`PiHost/Queue`） | 代码面自查 + case44 |
| InputBar 流式档可输入 | `ui/InputBar.kt`：主按钮同 tag 换语义（「发送」↔「插入」），另有「追问」；「清空」仅队列非空时出现 | `ChatScreenUiTest` Streaming 档两条 |

### 2.3 §3.3 运行时模型切换（`8124888`）

**首步是探测，不是实现**（规格书点名 `protocol.md` 未记 `get_available_models` 的 `data` 形状）。
探测实录见 §9.1，命令与真实 JSON 逐字贴在那里。

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| `refreshModels()` → `GetAvailableModels` | `ChatViewModel.refreshModels()`（`ChatViewModel.kt:424`） | `case49`/`case50` |
| `setModel(provider, modelId)` / `cycleModel()` | `:444`（两重载：线字段版 / `ProviderModel` 版）、`:450` | `case51`–`case55` |
| 顶栏模型 chip，点击弹 AlertDialog：列表 = models 快照，当前高亮，「轮换下一个」= `cycleModel` | `ui/ModelChip.kt`（`MODEL_CHIP`/`MODEL_DIALOG`/`MODEL_CYCLE`「轮换下一个」/`MODEL_REFRESH`） | `ChatScreenUiTest` 四条 |
| 列表为空 → chip 显示「无可用模型」 | `ModelPickerState.chipText()` 三档措辞：未拉→「选择模型」；拉过且空→「无可用模型」；已知当前→名字（+「· 思考 X」） | `p05`–`p07` + UI 用例 |
| `hasConfiguredAuth` 链未配置时不主动拉 | **偏差**（§8 第 1 条）：App 侧没有可读的 `hasConfiguredAuth` 面，实现成「chip 被点才拉」——用户那一次点击是更可靠的判据，且少一条注入边 | `ChatScreenUiTest`「未点不拉」那条（`MODEL_REFRESH_WIRES` 计数） |
| thinking level：`setThinkingLevel` / `cycleThinkingLevel` / `getAvailableThinkingLevels` 接入同 chip 的二级菜单 | `:452`/`:456` + `ModelChip` 对话框内的档位区（只列 pi 给的白名单，**不给非法档位留出口**） | `p08`–`p13`、`case56`–`case60` |
| 快照解析放 `feature/chat`，不碰 `core/rpc` | 新建 `ChatModelPicker.kt`（`ModelCatalog` + `ModelPickerState` + `ProviderModel`） | 冻结区 diff 为空（§5） |
| 测试 ≥10 | `ModelCatalogTest` p01–p14（14）+ `ChatViewModelTest` case49–case60（12）+ `ChatScreenUiTest` 4 | 共 30 条，门槛 10 |

**凭据面**（§2 红线「凭据永不离开本机」的正面交代）：`ProviderModel` 只留
`provider/id/name/reasoning/contextWindow` 五个字段——pi 快照里的 `baseUrl`、`cost`、`headers`
**一律不解析、不展示、不入内存**；`grep -rn "sk-…" */src/main` = 0（§4）。

### 2.4 §3.4 会话统计条（`f287653`）

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| `refreshStats()` → `GetSessionStats` | `ChatViewModel.kt:627` | `case61` |
| `TurnEnd` **或** `AgentSettled` 时自动 refresh 一次 | 选了 `AgentSettled`（决策与理由见 §9.3、§8 第 3 条） | `case63`（`turn_end` 两次、`agent_settled` 一次，refresh **恰好一次**） |
| 手动刷新 | `StatsBar` 的 `STATS_REFRESH`「⟳」按钮 | UI 用例「统计未加载直到按 ⟳」 |
| `message_update.usage` 流式累积、实时显示 | `PiEvent.MessageUpdate.usage` → `SessionStatsParser.parseUsage` → `inFlightUsage` **替换**（不是相加）；`message_end` 才并入 `closedUsage` | `st07`（快照形状）、`case64`（21 次 update 同一 usage → 只显示一份）、`case65`（定稿后才进总和） |
| 一行式 `tokens \| cost \| ctx tokens/window (percent%)` | `SessionStats.barText()` / `ContextUsage.barText()`；`StatsBar` 挂在 `TranscriptList` 与 `InputBar` 之间（规格书给的两个位置之一） | `st03`/`st11` |
| 点击展开详情（userMessages/assistantMessages/toolCalls/toolResults/totalMessages） | `StatsBar` 的 `STATS_DETAILS` 块（展开是纯视图状态，不入 VM） | `st12` + UI 用例 |
| `compact` 按钮在展开态 + 确认对话框（说明压缩会重写上下文） | `COMPACT_BUTTON`「压缩上下文」→ `CompactConfirmDialog`（正文写足「会写回会话文件、老消息换成摘要」）→ `compactContext()` | UI 用例「展开后压缩仍需确认」 |
| `set_auto_compaction(true/false)` 开关同处 | `AUTO_COMPACT_SWITCH` + `setAutoCompaction(enabled)`；开关的**初值只由 `get_state.autoCompactionEnabled` 写**，不按一下就当已知 | `st09`、`case69`（press 序列断言 `listOf(true,false)`，而显示值仍由 get_state 纠正）|
| 测试 ≥8 | `SessionStatsTest` st01–st12（12）+ `ChatViewModelTest` case61–case72（12）+ `ChatScreenUiTest` 4 | 共 28 条，门槛 8 |

**三处「不猜 pi」的形状结论**（全部由 §9.1 那份探测钉死，代码与用例各留了字）：
1. `contextUsage.percent` **已经是百分数**：`120/128000 = 0.0009375` 而字段值是 `0.09375`，
   再乘 100 就是把 0.09% 报成 9.4%（`st02`；缺字段时按 `tokens/contextWindow` 复算，`st05`）。
2. `cost` 同一个词两种形状：`get_session_stats.data.cost` 是**裸数字**，
   `message_update.usage.cost` 是 `{input,output,cacheRead,cacheWrite,total}` 对象——
   一份 `parseUsage` 两条路径都认（`st06`）。
3. `message_update.usage` 是这条消息的**累计快照**：探测里 21 次 update 的 usage 逐字相同
   （`input:100, output:20, totalTokens:120`），逐条相加会把 120 tok 显示成 2520。

**诚实的未知态**（规格书没写、但「摆一排 0 就是假数据」的那部分）：
统计未加载 = 「统计未加载」；缺 `contextUsage` = 「ctx 未知」/「120/? (—)」；
`get_state` 还没回 = 「自动压缩（未知）」；键名漂了 = 报形状不对并**留着上一版数字**，不清零（`st04`/`case62`）。

### 2.5 §3.5 版本与注入（`0430cb9` + `92c15da`）

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| versionCode 5→6、versionName 0.1.4→0.1.5 | `app/build.gradle.kts:19-20`（**只这两行**，其余字段一字未动，`git diff` 见 §4 第 5 条） | `ManifestPermissionTest` ③（期望值换 `6L` / `"0.1.5"` / `> 5L`） |
| AppTestDoubles 补新注入边（**若有**） | 实测**无**：§3.3/§3.4 给 `ChatViewModel` 加的只有方法与 UI 合成品，`@Inject constructor`（`ChatViewModel.kt:62-83`）六个参数一字未动；统计与模型都经既有 `ChatEngine.send` 走 RPC，解析住在 `feature/chat` | `AppTestDoubles` 那条「注入边清点」KDoc 追加了 P3B 复查段；`:app` Hilt 建图通过 = 绑定形状正确 |
| `ManifestPermissionTest` 权限清单不变断言 | 新增 ⑥：①② 只保证「四条都在」（加第五条照样绿），⑥ 补「不许多」的上界 | 见下面那条实测发现 |

**⑥ 一上手就检出到一条不是我们声明的权限**（这是本轮最有用的意外）：
合并清单的 `uses-permission` 有 **5** 条，第 5 条是
`works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，
来源是 `androidx.core:core`（编译机解析到的 1.18.0）自己 manifest 的 `:22-26`：

```xml
<permission android:name="${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
            android:protectionLevel="signature" />
<uses-permission android:name="${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
```

它不是「向系统要能力」：名字带 `applicationId` 前缀、`protectionLevel=signature`、App 自声明自使用，
是 AndroidX 为 `ContextCompat.registerReceiver(…, RECEIVER_NOT_EXPORTED)` 造的内部闸门，
**从 P2A 起每一份 APK 都有它**（§7 的 badging diff 与 P3A 包逐字相同就是这件事的旁证）。
所以 ⑥ 的断言分两半：`android.permission.*` 必须**恰好**四条；
剩余杂项必须**只有**这条库自带的那一条。硬写「恰好四条全等」会当场假红（实测第一版就是红的，
失败信息把第五条原样打了出来，才没有把它误读成「P3B 新增了权限」）。

### 2.6 §3.5 附带：`:app` 的一条竞态修复（`92c15da`）

现象：`MainActivityTest` ④c **单类跑绿、全量跑红**（红在 `sessions-row:<file>.jsonl` 找不到节点）。
两条实测证据把它定性成时序而非行为回归：① 只跑该类 → 11/11 绿；② 同一棵 tree 全量跑 → 同类同方法红。
根因：那一行是后台线程扫出来的——`SessionsViewModel.refresh()` → `PiSessionScanner.scan()`
走 `withContext(Dispatchers.IO)` 真读磁盘，而 `pump()` 只 idle 主 looper 两次；
IO 没跑完时续体还没投递，断言就发生在「列表仍是 Loading」那一刻。
改法：点行前先 `waitUntil(5s)` 等该 tag 出现。断言语义一字未改（点仍是真扫出来的那条会话，
`SwitchSession`+`GetMessages`、`startCalls==1`、`SCREEN` 消失、「会话切换中…」四条原样保留）。
`:app` 主源集与 `feature/sessions` 零改动。

## 3. 改动清单（P3B 窗口 `c1513be..92c15da`：代码面 **22 个路径 = 10 新增 / 12 修改**，+4293 / −62）

```
A  feature/chat/src/main/kotlin/…/chat/ChatHistory.kt              §3.1
A  feature/chat/src/main/kotlin/…/chat/QueueState.kt               §3.2
A  feature/chat/src/main/kotlin/…/chat/ChatModelPicker.kt          §3.3
A  feature/chat/src/main/kotlin/…/chat/ChatSessionStats.kt         §3.4
A  feature/chat/src/main/kotlin/…/chat/ui/ModelChip.kt             §3.3
A  feature/chat/src/main/kotlin/…/chat/ui/StatsBar.kt              §3.4
A  feature/chat/src/test/kotlin/…/chat/ChatHistoryTest.kt          §3.1（h01–h15）
A  feature/chat/src/test/kotlin/…/chat/ModelCatalogTest.kt         §3.3（p01–p14）
A  feature/chat/src/test/kotlin/…/chat/ProbeFixtures.kt            §3.3/§3.4 共用的唯一一份线形表
A  feature/chat/src/test/kotlin/…/chat/SessionStatsTest.kt         §3.4（st01–st12）
M  feature/chat/src/main/kotlin/…/chat/ChatModels.kt               （§3.1/§3.2 的 UI 模型）
M  feature/chat/src/main/kotlin/…/chat/ChatViewModel.kt            （四个小节都动它：回填/插话/模型/统计）
M  feature/chat/src/main/kotlin/…/chat/ui/ChatScreen.kt            （徽标 + chip + StatsBar 接线）
M  feature/chat/src/main/kotlin/…/chat/ui/ChatTags.kt              （QUEUE_*/MODEL_*/STATS_*/COMPACT_*）
M  feature/chat/src/main/kotlin/…/chat/ui/InputBar.kt              §3.2
M  feature/chat/src/test/kotlin/…/chat/ChatViewModelTest.kt        （case32–case72，编号延续 P3A 的 case31）
M  feature/chat/src/test/kotlin/…/chat/FakeChatEngine.kt           （scriptReply/deliverScripted/pumpScripted）
M  feature/chat/src/test/kotlin/…/chat/ui/ChatScreenUiTest.kt      （§3.2 两条 + §3.3 四条 + §3.4 四条）
M  app/build.gradle.kts                                            §3.5（只 versionCode/versionName 两行）
M  app/src/test/kotlin/…/AppTestDoubles.kt                         §3.5（只补 KDoc 清点，无新绑定）
M  app/src/test/kotlin/…/ManifestPermissionTest.kt                 §3.5（③ 期望值 + ⑥ 新增）
M  app/src/test/kotlin/…/MainActivityTest.kt                       §3.5 附带（④c 竞态）
```

**没在这里的东西一律没动**：`core/*`（四个模块）、`feature/sessions`、`feature/settings`、
`app/src/main/**`（含 `AndroidManifest.xml`）、`docs/protocol.md`、`tools/**`。

## 4. 红线复核（规格书 §2 逐条，全部实测）

| # | 红线 | 实测命令 | 结果 |
|---|---|---|---|
| 1 | pi 源码零写入 | `find /root/pi/.tools/pi-1.0.4 -newermt '1 day ago' -type f \| wc -l` | **0** |
| 2 | 冻结区零 diff | `git diff --stat 1f77127..HEAD -- core/rpc/src/main core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging` | **空**（本轮又扩了一档：整目录 `core/rpc` 在 `c1513be..HEAD` 也是空） |
| 3 | 不碰 `feature/settings` / `feature/sessions` | `git diff --stat c1513be..HEAD -- feature/settings feature/sessions` | **空** |
| 4 | P3A 的 355 条保持绿 | §6 矩阵：基线八模块 `failures=0 errors=0` | **成立（448 条 `failures=0 errors=0`，355 基线一条没掉）** |
| 5 | 不新增权限；`build.gradle.kts` 只改版本两行 | `git diff c1513be..HEAD -- app/src/main/AndroidManifest.xml`（空）；`git diff 0430cb9^..0430cb9 -- app/build.gradle.kts`（4 行：2−/2+） | **空 / 只有版本两行** |
| 6 | 回填是纯读，失败只留空转录 + NoteItem，绝不清空/改写 pi 会话文件 | `grep -c "File(\|writeText\|appendText\|RandomAccessFile" ChatHistory.kt` = **0**；失败面 `ChatViewModel.kt:883` 只有 `Log.w` + `mutateTranscript`；用例 `case34`/`case35` | **成立**（失败只多一行提示，转录不清） |
| 7 | 私有目录不写新文件；审计沿用 P3A 的 `AuditSink` | P3B 新增的 6 个主源码文件 `grep -n "File(\\|writeText\\|appendText"` = **0**；回填三函数 `ChatViewModel.kt:848/870/883` 内无 `recordAudit`（全类 3 处命中在 `:923/:1033/:1055`，属权限应答与事件流）。（`:feature:chat` 全模块另有 3 处命中，全在 `ChatViewModel.kt:1161/1328/1341`，是流式正文的内存草稿 `appendTextDelta(StringBuilder)`，与文件 IO 无关） | **成立** |
| 8 | App 永不代替用户决定 steer/follow_up 内容 | 全类无「看到事件就自动插话」路径；`disposition` 只进 logcat | **成立** |
| 9 | 不猜 pi 行为：`protocol.md` 没写的一律先实测 | §9.1 探测实录（65 行 jsonl 原始落盘 + 20 条命令脚本） | **成立**（三处形状结论、一处事件缺失，全部来自实跑） |
| 10 | 每步都要有测试 | §6 矩阵 + 每条 commit body 的「测试」段 | **成立**（四个小节各 23/11/30/28 条新增，`§3.5` 1 条） |
| 11 | 凭据永不离开本机 | `grep -rn "sk-" --include="*.kt" */src/main` = **0**；`ProviderModel` 不含 `baseUrl/headers/cost` | **成立** |

## 5. 冻结区复核

```bash
$ git diff --stat 1f77127..HEAD -- core/rpc/src/main core/process/src/main \
    core/environment docs/protocol.md tools/capture tools/runtime-packaging
（无输出）
$ git diff --name-status c1513be..HEAD -- core/rpc
（无输出）
```

`core/rpc` 里为 P3B 预留的那条通道是**早就存在**的：`PiEvent.Unknown(raw: JsonObject)`
（P2 起就有的前向兼容出口）。本轮三个未建模事件（`thinking_level_changed`、`compaction_start`、
`compaction_end`）全部由 `ChatViewModel.onUnknownEvent` 消费，`when` 的 `else -> Unit`
保持「未来 pi 多发一种事件不会把 App 打挂」的语义。

## 6. 测试矩阵（编译机，一条 gradle 命令一个模块，2026-10-09）

| 模块 | 任务 | rc | 测试类（条数，取自 XML 头部） | 合计 | fail/err/skip | vs P3A | 判定 |
|---|---|---|---|---|---|---|---|
| `:core:process` | `test` | 0 | JvmProcess 3 | **3** | 0/0/0 | 3 → 3 | ✓ 基线不动 |
| `:core:rpc` | `test` | 0 | GoldenReplay 9 / LineFramer 9 / PiSession 8 / RpcModel 7 / PiDesktopE2e 1 | **34** | 0/0/**1** | 34 → 34 | ✓ 冻结区未动，skip 仍是环境门控的桌面 E2E |
| `:core:environment` | `testDebugUnitTest` | 0 | EnvFactory 2 / PathLayout 3 | **5** | 0/0/0 | 5 → 5 | ✓ 冻结区未动 |
| `:core:engine` | `testDebugUnitTest` | 0 | AssetInstaller 5 / ChatEngineAdapter 6 / DataStoreProbe 4 / ExecFallbackStarter 9 / PiEngineService 7 / PiSpawn 4 / RealRuntimeAssetsInstall 2 / RuntimeInstall 5 / SessionSupervisor 14 / SpawnDiagnosticsSink 6 / FileAuditSink 14 / CrashReporter 11 / ModelCredentialStore 17 / RelayEndpointStore 31 / SettingsRepository 6 | **141** | 0/0/0 | 141 → 141 | ✓ 本轮没给 engine 加东西（模型与统计都住在 `:feature:chat`） |
| `:feature:chat` | `testDebugUnitTest` | 0 | **ChatHistory 15（新）** / **ModelCatalog 14（新）** / **SessionStats 12（新）** / ChatViewModel **72**（31→72：case32–72 新 41）/ ChatScreenUi **16**（6→16：新 10）/ ComposeProbe 3 / HiltComposeProbe 1 / HiltProbe 1 / InstallGateFailed 5 / MarkwonRender 4 / MarkwonSmoke 4 | **147** | 0/0/0 | 55 → 147（**+92**） | ✓ 四个小节的用例全在这里 |
| `:feature:sessions` | `testDebugUnitTest` | 0 | SessionScanner 10 / SessionsViewModel 12 / SessionsScreen 3 | **25** | 0/0/0 | 25 → 25 | ✓ 红线 3：整模块一字未动 |
| `:feature:settings` | `testDebugUnitTest` | 0 | SettingsViewModel 36 / SettingsScreen 33 | **69** | 0/0/0 | 69 → 69 | ✓ 红线 3：整模块一字未动 |
| `:app` | `testDebugUnitTest` | 0 | MainActivity 11 / **ManifestPermission 6**（5→6：新增 ⑥；③ 期望值换 `6L`/`0.1.5`）/ CrashReportWiring 4 / PiEngineServiceWiring 3 | **24** | 0/0/0 | 23 → 24（**+1**） | ✓ ④c 的竞态修正在 `92c15da`（§2.6） |
| **合计** | 九条命令（八 `test` + `assembleDebug`）| **全 0** | | **448** | **0/0/1** | 355 → **448**（**+93**） | **0 红**（355 基线一条没掉 + 93 条新增） |

新增 93 条按小节拆：§3.1 **23**（ChatHistory 15 + case32–39 8）、§3.2 **11**（case40–48 9 + ChatScreenUi 2）、
§3.3 **30**（ModelCatalog 14 + case49–60 12 + ChatScreenUi 4）、§3.4 **28**（SessionStats 12 + case61–72 12 +
ChatScreenUi 4）、§3.5 **1**（ManifestPermission ⑥）。15+8+9+2+14+12+4+12+12+4+1 = 93 ✓。

规格书门槛逐小节对照（门槛都是「不少于 N 条」）：§3.1 ≥8 → 23 ✓；§3.2 ≥8 → 11 ✓；§3.3 ≥10 → 30 ✓；
§3.4 ≥8 → 28 ✓；§3.5「ManifestPermission 断言」→ 1 条新增 + 1 条改写 ✓。

口径：只取每个模块 `build/test-results/<任务>*/TEST-*.xml` 的 **debug** 那一堆
（`:core:process`/`:core:rpc` 是纯 JVM 模块，任务名是 `test`、结果在 `test/`；
`:core:environment` 是 Android 模块，结果在 `testDebugUnitTest/`——表格第一版把它当纯 JVM 模块查，
那一行报「没有 XML」，合计因此是 443 而不是 448。这条已按实测改回，见 §8 第 12 条）。
`:app:test` 与 `:feature:chat:test` 这类聚合任务会同时跑 debug 与 release 两堆 XML，
release 侧的条数与 debug 相同，**不重复计入**。
上表由编译机上的 `/root/pi/.tools/p3b-matrix-table.py`（同一份脚本在 `/tmp/p3b-matrix-table.py`）
直接读 XML 头部生成，数字不手抄；逐模块 rc 在 `/tmp/p3b-matrix.rc`（§10 有命令）。

## 7. assembleDebug + APK 双端落盘

| 项 | 实测值 |
|---|---|
| 任务 | `:app:assembleDebug` → `BUILD SUCCESSFUL in 1m 35s`（159 tasks：9 executed / 150 up-to-date） |
| 路径 | 编译机 `/root/android-host/app/build/outputs/apk/debug/app-debug.apk`（mtime 07:34 UTC） |
| 字节数 | **63,925,997**（P3A 63,866,228 → **+59,769 B**） |
| sha256 | `8f02f9bda5f953be468e9c4a4266f6c58ad55c782eb9cd8ebf03c46051eb9f24` |
| badging（编译机 `aapt2 dump badging`） | `package: name='works.earendil.pihost' versionCode='6' versionName='0.1.5' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'`、`targetSdkVersion:'35'`、`native-code: 'arm64-v8a'` |
| 二进制 manifest 自证（本机解包体，不依赖编译机） | `python3 /root/pi/.tools/p3b-axml.py /root/pi/dist/pi-host-full-debug.apk` → `package=works.earendil.pihost` / `versionCode=6` / `versionName=0.1.5` / `minSdkVersion=26` / `targetSdkVersion=35`；同一脚本跑 P3A 那份 → `versionCode=5` / `0.1.4`，`minSdk/target` 都是 26/35（§10 有命令；写这个脚本的原因见 §8 第 13 条） |
| `uses-permission` | 5 条，与 P3A 包**逐字相同**（四条 `android.permission.*` + androidx 自带那条 `works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，§2.5） |
| 包内 `lib/arm64-v8a` | **29** 个 `.so`，`diff` P3A/P3B 两份 `unzip -Z1` 清单 → **无输出**（`SO_LIST_DIFF=EMPTY`） |
| 包内 `assets/*` | 清单 `diff` → **无输出**（`ASSET_LIST_DIFF=EMPTY`）；条目总数 183 → 183 |
| 编译机磁盘 | `/dev/vda1` 交付时可用 **4.4G**（总 49G，已用 44G，92%），规格书门槛 ≥4G ✓ |

落盘与下载：

| 落点 | 尺寸 | sha256 |
|---|---|---|
| 编译机 `/root/apk-serve/8f02f9bda5f9/pi-host-full-debug.apk`（目录名 = 包体 sha256 前 12 位） | 63,925,997 | `8f02f9bda5f9…51eb9f24` |
| 本机 `/root/pi/dist/pi-host-full-debug.apk`（经 `http://64.90.11.50:8000/` 拉回，不是 scp 直拷） | 63,925,997 | `8f02f9bda5f9…51eb9f24` |

- **双端逐字相等**：两份 `sha256sum` 输出同一串（上表），本机那份是 HTTP 下载之后重算的。
  P3A 那份本机包没有覆盖，改名留作对照：`/root/pi/dist/pi-host-full-debug-0.1.4-p3a.apk`（63,866,228）。
- HTTP：`GET /index.html` → **200**（3,484 B，`/root/pi/.tools/p3b-index.py` 由 `sha256sum`/`stat` 的回显生成，
  P3B 行标 `.latest`、P3A 行降级为普通行）；`GET /8f02f9bda5f9/pi-host-full-debug.apk` → **200**、
  `Content-Length: 63925997`、`Content-type: application/vnd.android.package-archive`；
  `curl -r 0-0` → **200** 且 `bytes=63925997`（`python3 -m http.server` 不理 Range 头，与 P3A 同一常态）。
- **包体 ↔ 最终源码树**：本轮九条命令跑在同一个 `HEAD`（`92c15da`）的同步树上，
  `:app:assembleDebug` 与矩阵里 `:app:testDebugUnitTest` 用的是同一批 class 产物（150 个 task `UP-TO-DATE`
  就是这件事的构建图证据）；docs 窗口只新增 `docs/REPORT-P3B.md` 一个未跟踪文件，不进 APK。
- 上一轮的教训没有复发（REPORT-P3A §1）：跑 `assembleDebug` 前先在编译机核对了
  `tools/runtime-packaging/out/assets/runtime/`（29 个 `.so` + `manifest.json` + `pi-runtime.zip` 在位），
  所以这份是 63.9 MB 的全量包，不是 12 MB 的空壳。

## 8. 偏差、遗留与未执行

1. **`hasConfiguredAuth` 链不存在 → 改成「chip 点开才拉」**（§3.3 原文「未配置时不主动拉」）。
   App 侧没有任何可读的「配好凭据了」的面：凭据住在 `auth.json`，读它要在 `:feature:chat` 新增一条
   `ModelCredentialStore` 注入边（那是 `:core:engine` 的 DI，且违反「少一条边少一份风险」）。
   替代实现：chip 被用户点这一次就是最可靠的「用户想拉列表」判据，未点则一条 `get_available_models` 都不发。
   副作用是好的：不启动就拉、切会话就拉这类「白刷 provider」的流量为零。用例：`ChatScreenUiTest`
   那条按 `MODEL_REFRESH_WIRES` 计数的「未点不拉」。
2. **模型列表是惰性快照，不做后台刷新**：`ModelPickerState` 只由四条回执（`get_available_models` /
   `get_state` / `set_model` / `cycle_model`）与一条 `thinking_level_changed` 事件写。
   规格书要求「列表 = models 快照」，没要求自动重拉；而 pi 的模型注册表可以在别处（CLI/设置页）变，
   所以 chip 上的名字有「可能是旧的」这一性质——对话框里放了 `MODEL_REFRESH`（⟳）作为显式纠偏入口。
3. **自动 refresh 点选 `AgentSettled` 而不是 `TurnEnd`**（规格书写的是「TurnEnd **或** AgentSettled」，二选一合法）。
   探测实测一轮完整对话里 `turn_end` 出现 **2** 次而 `agent_settled` 只 **1** 次（§9.1 事件清点），
   挂 TurnEnd 等于每轮多问一次 `get_session_stats`，语义上还是同一个数。
   用例 `case63` 钉的是「两次 turn_end + 一次 agent_settled → refresh 恰好一次」。
4. **`compaction_start`/`compaction_end`/`thinking_level_changed` 不在 `core/rpc` 建模**：
   红线 2 不许动冻结区，而 `PiEvent.Unknown` 就是为此准备的通道。代价是这三个事件的载荷要在
   `feature/chat` 手工按 `JsonObject` 取值（`SessionStatsParser.parseCompactionEnd` 等）；
   收益是将来 pi 再加事件不会让 App 解不出线。形状漂移的用例：`st10`/`case67`。
5. **`percent` 字段按 pi 给的口径显示，不重新定义**：`contextUsage.percent` 已经是百分数（0.09375 = 0.09%），
   本 App 直接取它拼 `%`；只有字段缺失时才用 `tokens/contextWindow` 复算（`st02`/`st05`）。
   如果哪天 pi 把它改成 0–1 的比例，显示会差 100 倍——这条已经记在这里，改的时候有地方对照。
6. **压缩失败不改任何状态**：探测里 `compact` 的回执是 `success=false, error="Nothing to compact (session too small)"`
   （会话太小压不动，属正常），而 `compaction_end` 事件仍带着 `errorMessage` 发出来。
   实现按 §2 纪律：`success=false` 只报 notice（原文照抄 pi 的话，`case66`），
   `compacting` 位由 `compaction_start`/`compaction_end` 与 `get_state.isCompacting` 三方纠正（`case68`/`case70`）。
7. **`new_session` 之后的统计清零要等回执**：`case71` 第一轮写错（以为点新建就清），实测
   `ChatViewModel` 的 session roll 发生在 `new_session` 的 `Response` 落地之后，
   所以用例补了那条 `PiEvent.Response(command="new_session", success=true)`。这条不是妥协，是照实。
8. **规格书 §3.5「补新注入边（若有）」判定为无**（§2.5 第二行）：本轮没有新的可注入协作者。
   如果将来 `StatsBar` 要独立读 `models.json` 之类的东西，才会需要新边。
9. **`app/build.gradle.kts` 里 `buildConfig = true` 与崩溃报告版本号**：P3A 打开的 BuildConfig 让
   `CrashReporter` 的首行自动跟到 `0.1.5`（`CrashReportWiringTest` 断言的是
   `text.contains("version=${BuildConfig.VERSION_NAME}")`，不是写死串），所以版本升级不需要动那条测试。
10. **`:app` ④c 的竞态**（§2.6）：P2E 写它时靠运气绿，本轮第一次全量复现才暴露。
    记这一条是为了下一个人在别处看到「单类绿、全量红」时不必重走一遍排查。
11. **未执行**：真机 `adb install -r` 覆盖安装（§3.7 清单交付给用户执行）；
    真机上「压缩后 ctx% 下降」需要够大的会话，本轮只有桌面 mock provider 的证据
    （mock 会话太小，pi 直接回 `Nothing to compact`）。复现下一条命令：见 §10 末段。
12. **矩阵表第一版少算了一个模块（脚本口径错，不是构建错）**：`p3b-matrix-table.py` 最初把
    `:core:environment` 当纯 JVM 模块去读 `build/test-results/test/`，而它是 Android 模块
    （结果在 `testDebugUnitTest/`，EnvFactory 2 + PathLayout 3），于是那一行报「没有 XML」、合计报 **443**。
    发现方式就是「443 与预期的 448 差 5」——差值恰好是 environment 那一行的条数。
    改脚本（不是改数字）后重跑，合计 **448**、八行齐全。§6 表里那一行的任务名写的是实际读到的目录。
13. **`minSdkVersion` 那一项换了载体**：`aapt2` 只装在编译机，而本轮 §7 收尾时 ssh 又掉过两次
    （`connect to host 64.90.11.50 port 22: Connection timed out`；重试后 `uses-permission`/`native-code` 拿到了）。
    等第二条 `minSdkVersion` 时不改用「转述 gradle 配置」，而是写了 `/root/pi/.tools/p3b-axml.py`
    直接解**交付包里的二进制 `AndroidManifest.xml`**（本机那份是 HTTP 下载、sha256 已核对的同一份字节），
    得到 `versionCode=6 / versionName=0.1.5 / minSdkVersion=26 / targetSdkVersion=35`，
    同脚本对 P3A 那份给 `5 / 0.1.4 / 26 / 35`。踩到的坑记一笔：AXML 的元素 chunk 类型是 `0x0102`
    （不是枚举序号 `0x0006`），字符串池 `stringsStart` 是相对 chunk 起点的偏移。
14. **commit `0430cb9` 的 message 里有一处行号引用写错**：正文写的是「`ChatViewModel` 的
   `@Inject constructor`（`:404-424`）」，实际构造函数在 `ChatViewModel.kt:62-83`
   （`:404-424` 是模型区那几个方法的邻域）。commit 已发出且后面还有两条 commit，
   不改历史（红线：不做 `--amend`/`reset --hard`），以本节为勘误；
   留在树上的两处引用都不带那个错号——`app/src/test/kotlin/works/earendil/pihost/AppTestDoubles.kt:178`
   只说「`@Inject constructor` 的六个参数一字未动」（不引行号），§2.5 表格写的是 `ChatViewModel.kt:62-83`。

## 9. 规格书点名的两条**逐字**交付

### 9.1 §3.3 `get_available_models` 形状探测实录（命令 + 真实 JSON）

驱动脚本（不改仓库里 `tools/capture/` 那份冻结的采集器，用同一个 `cli.js`、独立的 HOME/cwd）：

```bash
# 1) 桌面基准的 mock provider（P2 起就在用的那只，端口 8787，场景表 scenarios.json）
node /root/pi/.capture/mock/mock-server.js          # 后台
# 2) 起 pi RPC 模式并按 cmds-33.json 发 20 条命令，stdout 原始字节落 rpc33.jsonl
node /root/pi/.tools/p3b-probe.js \
     /root/pi/.capture/probe33/rpc33.jsonl \
     /root/pi/.capture/probe33/cmds-33.json probe
#    内部即：spawn node <桌面基准 cli.js> --mode rpc
#    PI_HOME=/root/pi/.capture/probe33/home  PI_WORK=/root/pi/.capture/probe33/work
```

产物：`/root/pi/.capture/probe33/rpc33.jsonl`（65 行）+ `cmds-33.json`（p01–p20）。
`get_available_models` 那条回执的**真实 JSON 原文**（未加工、未格式化）：

```json
{"id":"p01","type":"response","command":"get_available_models","success":true,"data":{"models":[{"id":"mock-1","name":"Mock Model 1","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":16384},{"id":"mock-2","name":"Mock Model 2","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":true,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":200000,"maxTokens":16384}]}}
```

`get_state`（p11，含 chip 需要的 `model`/`thinkingLevel` 与 §3.4 需要的 `autoCompactionEnabled`/`isCompacting`）：

```json
{"id":"p11","type":"response","command":"get_state","success":true,"data":{"model":{"id":"mock-1","name":"Mock Model 1","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":16384},"thinkingLevel":"off","isStreaming":false,"isCompacting":false,"steeringMode":"all","followUpMode":"all","sessionFile":"/root/pi/.capture/probe33/home/.pi/agent/sessions/--root-pi-.capture-probe33-work--/2026-10-09T05-03-15-088Z_01a11f0b-468f-712a-90d4-aa4fa5772d31.jsonl","sessionId":"01a11f0b-468f-712a-90d4-aa4fa5772d31","autoCompactionEnabled":true,"messageCount":0,"pendingMessageCount":0}}
```

`get_available_thinking_levels`（p02：`reasoning:false` 的模型只给 `["off"]`；p04 换成 `mock-2`（`reasoning:true`）后五档全给）：

```json
{"id":"p02","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":["off"]}}
{"id":"p04","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":["off","minimal","low","medium","high"]}}
```

`cycle_model`（p07：模型对象在 `data.model` 下，同级还有 `thinkingLevel`/`isScoped`）与 `set_model` 失败态（p08）：

```json
{"id":"p07","type":"response","command":"cycle_model","success":true,"data":{"model":{"id":"mock-1","name":"Mock Model 1","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":16384},"thinkingLevel":"off","isScoped":false}}
{"id":"p08","type":"response","command":"set_model","success":false,"error":"Model not found: mock/no-such-model"}
```

`get_session_stats`（p13）与流式中的 `message_update.usage`（同一条消息的 21 次 update 里 usage **逐字相同**）：

```json
{"id":"p13","type":"response","command":"get_session_stats","success":true,"data":{"sessionFile":"/root/pi/.capture/probe33/home/.pi/agent/sessions/--root-pi-.capture-probe33-work--/2026-10-09T05-03-15-088Z_01a11f0b-468f-712a-90d4-aa4fa5772d31.jsonl","sessionId":"01a11f0b-468f-712a-90d4-aa4fa5772d31","userMessages":1,"assistantMessages":2,"toolCalls":1,"toolResults":1,"totalMessages":5,"tokens":{"input":200,"output":40,"cacheRead":0,"cacheWrite":0,"total":240},"cost":0,"contextUsage":{"tokens":120,"contextWindow":128000,"percent":0.09375}}}
{"type":"message_update","usage":{"input":100,"output":20,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":120,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"assistantMessageEvent":{"type":"text_start","contentIndex":0}}
// 21 次 message_update 里 usage 只有 1 种取值（逐字相同）
```

`compact`（p15）失败态与它的两条事件（`compaction_start`/`compaction_end`），以及 `set_auto_compaction` 之后 `get_state` 的纠正（p17）：

```json
{"type":"compaction_start","reason":"manual"}
{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false,"errorMessage":"Compaction failed: Nothing to compact (session too small)"}
{"id":"p15","type":"response","command":"compact","success":false,"error":"Nothing to compact (session too small)"}
{"id":"p17","type":"response","command":"get_state","success":true,"data":{"model":{"id":"mock-1","name":"Mock Model 1","api":"openai-completions","provider":"mock","baseUrl":"http://127.0.0.1:8787/v1","reasoning":false,"input":["text"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":128000,"maxTokens":16384},"thinkingLevel":"off","isStreaming":false,"isCompacting":false,"steeringMode":"all","followUpMode":"all","sessionFile":"/root/pi/.capture/probe33/home/.pi/agent/sessions/--root-pi-.capture-probe33-work--/2026-10-09T05-03-15-088Z_01a11f0b-468f-712a-90d4-aa4fa5772d31.jsonl","sessionId":"01a11f0b-468f-712a-90d4-aa4fa5772d31","autoCompactionEnabled":false,"messageCount":5,"pendingMessageCount":0}}
```

探测脚本发出的 20 条命令（`cmds-33.json` 逐字）：

```json
[
  { "cmd": { "type": "get_available_models", "id": "p01" }, "sleepMs": 500 },
  { "cmd": { "type": "get_available_thinking_levels", "id": "p02" }, "sleepMs": 500 },
  { "cmd": { "type": "set_model", "id": "p03", "provider": "mock", "modelId": "mock-2" }, "sleepMs": 700 },
  { "cmd": { "type": "get_available_thinking_levels", "id": "p04" }, "sleepMs": 500 },
  { "cmd": { "type": "set_thinking_level", "id": "p05", "level": "high" }, "sleepMs": 700 },
  { "cmd": { "type": "cycle_thinking_level", "id": "p06" }, "sleepMs": 700 },
  { "cmd": { "type": "cycle_model", "id": "p07" }, "sleepMs": 700 },
  { "cmd": { "type": "set_model", "id": "p08", "provider": "mock", "modelId": "no-such-model" }, "sleepMs": 500 },
  { "cmd": { "type": "set_thinking_level", "id": "p09", "level": "high" }, "sleepMs": 700 },
  { "cmd": { "type": "get_available_thinking_levels", "id": "p10" }, "sleepMs": 500 },
  { "cmd": { "type": "get_state", "id": "p11" }, "sleepMs": 500 },
  { "cmd": { "type": "prompt", "id": "p12", "message": "run the branch command" }, "waitForEvent": "agent_settled", "timeoutMs": 30000 },
  { "cmd": { "type": "get_session_stats", "id": "p13" }, "sleepMs": 800 },
  { "cmd": { "type": "get_messages", "id": "p14" }, "sleepMs": 800 },
  { "cmd": { "type": "compact", "id": "p15" }, "sleepMs": 500 },
  { "cmd": { "type": "set_auto_compaction", "id": "p16", "enabled": false }, "sleepMs": 500 },
  { "cmd": { "type": "get_state", "id": "p17" }, "sleepMs": 500 },
  { "cmd": { "type": "set_auto_compaction", "id": "p18", "enabled": true }, "sleepMs": 500 },
  { "cmd": { "type": "get_session_stats", "id": "p19" }, "sleepMs": 800 },
  { "cmd": { "type": "quit", "id": "p20" }, "sleepMs": 500 }
]
```

**事件清点**（`rpc33.jsonl` 全 65 行的 `type` 计数，原样贴）：

```
 21  message_update
  5  message_end
  5  message_start
  3  response/get_available_thinking_levels
  2  response/get_session_stats
  2  response/get_state
  2  response/set_auto_compaction
  2  response/set_model
  2  response/set_thinking_level
  2  thinking_level_changed
  2  turn_end
  2  turn_start
  1  agent_end
  1  agent_settled
  1  agent_start
  1  compaction_end
  1  compaction_start
  1  extension_ui_request
  1  response/compact
  1  response/cycle_model
  1  response/cycle_thinking_level
  1  response/get_available_models
  1  response/get_messages
  1  response/prompt
  1  response/quit
  1  tool_execution_end
  1  tool_execution_start
```

三条从这份实录里读出来的结论（不是推测）：
- **`model_change` 一次都没有出现**（0 次）。它确实存在，但只作为**会话树 entry**
  出现在持久化文件与 `get_entries` 回执的 `data.entries[]` 里——
  `core/rpc` 金样 `007-session-persist.jsonl:59` 那条就是：
  `{"type":"model_change","id":"1589a80b","parentId":null,"timestamp":"…","provider":"mock","modelId":"mock-1"}`
  （带 `id`/`parentId`/`timestamp` 的 entry 形状，与总线事件形状不同）；
  而 `dist/bundle/cli.js` 里 `grep -c "model_change"` = **0**，RPC 侧根本没有这个事件名。
- `set_model` 的 `data` **就是那条 model 对象本身**，没有外层 `{model: …}` 包装；
  `cycle_model` 才有 `data.model` 包装，且同级另带 `thinkingLevel`/`isScoped`（两个函数各认各的线格式）。
- `set_thinking_level` 回执**只有 success 没有 data**，而 `cycle_thinking_level` 回 `{"level":"off"}`（键名不是 `thinkingLevel`）。

### 9.2 §3.3 `model_change` 事件决策（规格书点名「决策必须落在 REPORT-P3B」）

规格书给的分支是：「若真有独立事件（非 session entry），feature/chat 层自建轻量事件模型；
若无，靠 response 回执刷新 chip」。

**实测结果 = 后者**。判定链条：
1. 桌面基准探测：20 条命令里 6 条改模型/改档位（p03/p05/p06/p07/p08/p09），
   65 行事件流里 `model_change` **0 次**（§9.1 清点）。
2. `core/rpc` 八份金样里 `model_change` 只出现在 `002-toolcall.jsonl` / `007-session-persist.jsonl`，
   且两处都在 `get_entries` 回执的 `data.entries[]` 里，即 **session entry**，不是总线事件。
3. 决定性一条：`grep -c model_change /root/pi/.capture/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js`
   = **0**——RPC 模式下那段代码里连这个字符串都不存在，不可能发出来。

**落地的 chip 真相来源**（四条回执 + 唯一白捡的事件）：
`get_available_models`（列表）、`get_state`（当前模型/档位/自动压缩/是否压缩中）、
`set_model`（回执 data 即新当前模型）、`cycle_model`（`data.model` + `data.thinkingLevel`），
外加 `thinking_level_changed`——这个是**真**的总线事件（探测里发了 2 次，p05/p06 各一次），
走 `PiEvent.Unknown` 消费。
不做任何本地乐观更新：`set_model` 被 provider 拒掉（p08 `Model not found: mock/no-such-model`）时
chip 必须还写着旧模型，这就是「已发出 ≠ 已生效」那条纪律的用例（`case53`/`case66` 同形）。

**为什么不自建轻量事件模型**：规格书给的那个分支前提是「真有独立事件」，前提不成立；
在没有事件的情况下造一个事件模型，就是给不存在的线格式建模，白多一份解析与一条死路。

### 9.3 §3.4 自动刷新的选点（TurnEnd vs AgentSettled）

同一份探测里 `turn_end` **2** 次、`agent_settled` **1** 次、`agent_end` 1 次。
挂 `turn_end` 会在一次用户提问里多发一条 `get_session_stats`，而它问的是同一个数；
挂 `agent_settled` 是「整轮真的安静了」那一刻，恰好也是用户想看统计的时刻。
用例 `case63` 把「两次 turn_end + 一次 agent_settled → 恰好一条 refresh」钉住。

## 10. 复现命令（逐条可直接跑）

```bash
# —— 编译机（一条命令一个模块；JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64，gradle 8.13 --no-daemon）——
# 第 0 步：运行时资产（不入 git），本轮从 P3A 交付包复原到**本机**再 rsync 过去，
#          否则 assembleDebug 得到 ~12MB 空壳包（REPORT-P3A §1 那条教训）
T=$(mktemp -d); unzip -q -o /root/pi/dist/pi-host-full-debug.apk 'lib/arm64-v8a/*' 'assets/runtime/*' -d "$T"
mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/assets/runtime
cp -a "$T"/lib/arm64-v8a/. app/src/main/jniLibs/arm64-v8a/; cp -a "$T"/assets/runtime/. app/src/main/assets/runtime/
bash /root/pi/.tools/bbuild.sh sync
bash /root/pi/.tools/bbuild.sh raw 'printf "sdk.dir=/root/android-sdk\n" > /root/android-host/local.properties'

# —— §3.6 的矩阵：每模块一条 gradle 命令，在编译机脱管跑（ssh 掉线不会把构建带走）——
#      驱动脚本 /root/pi/.tools/p3b-matrix.sh 推到编译机 /tmp，setsid nohup 起，逐模块 rc 追加到 /tmp/p3b-matrix.rc
rsync -az -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv" \
  /root/pi/.tools/p3b-matrix.sh root@64.90.11.50:/tmp/p3b-matrix.sh
ssh root@64.90.11.50 "setsid nohup bash /tmp/p3b-matrix.sh >/dev/null 2>&1 &"
ssh root@64.90.11.50 'cat /tmp/p3b-matrix.rc; tail -3 /tmp/p3b-matrix.log'      # 本轮最终九行全 rc=0

# /tmp/p3b-matrix.sh 内部就是这九条（JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64，
# /opt/gradle-8.13/bin/gradle --no-daemon --console=plain，工作树 /root/android-host）：
:core:process:test  :core:rpc:test  :core:environment:test  :core:engine:test
:feature:chat:test  :feature:sessions:test  :feature:settings:test  :app:test  :app:assembleDebug
# 想在本地一条条跑，等价格（`test` 会连 release 变体一起跑；只跑 debug 用 `:app:testDebugUnitTest`）：
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':core:process:test'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':feature:chat:test'
bash /root/pi/.tools/p3b-gradle.sh --nosync ':app:testDebugUnitTest' --tests 'works.earendil.pihost.ManifestPermissionTest'
#   注意：`:app:test` 是聚合生命周期任务，**不接受** `--tests`（实测报 Unknown command-line option '--tests'），
#   单类过滤要落到 `:app:testDebugUnitTest`。

# —— 计数（数字不手抄：编译机上直接读 XML 头部）——
rsync -az -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv" \
  /root/pi/.tools/p3b-matrix-table.py root@64.90.11.50:/tmp/p3b-matrix-table.py
ssh root@64.90.11.50 'python3 /tmp/p3b-matrix-table.py'      # §6 那张表的原样输出（合计 448 / skip 1）
bash /root/pi/.tools/bbuild.sh raw 'bash -s' < /root/pi/.tools/p3a-count-tests.sh   # P3A 那份口径复算

# —— APK 双端落盘（目录名 = 包体 sha256 前 12 位；本轮实际执行的是 /tmp/p3b-deliver.sh 这一条链）——
rsync -az -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv" \
  /tmp/p3b-deliver.sh root@64.90.11.50:/tmp/p3b-deliver.sh
ssh root@64.90.11.50 'bash /tmp/p3b-deliver.sh'
# 该脚本等价于（逐条可单跑）：
#   A=/root/android-host/app/build/outputs/apk/debug/app-debug.apk
#   sha=$(sha256sum $A | cut -d' ' -f1); slot=${sha:0:12}
#   mkdir -p /root/apk-serve/$slot && cp $A /root/apk-serve/$slot/pi-host-full-debug.apk && sync
#   sha256sum /root/apk-serve/$slot/pi-host-full-debug.apk; stat -c %s …
#   /root/android-sdk/build-tools/36.0.0/aapt2 dump badging … | grep -E '^package:|uses-permission|sdkVersion|native-code'
#   diff <(unzip -Z1 /root/apk-serve/515394fca808/pi-host-full-debug.apk 'lib/arm64-v8a/*' | sort) \
#        <(unzip -Z1 $A 'lib/arm64-v8a/*' | sort)                       # 无输出 = so 名单一致
#   diff <(unzip -Z1 /root/apk-serve/515394fca808/pi-host-full-debug.apk 'assets/*' | sort) \
#        <(unzip -Z1 $A 'assets/*' | sort)                               # 无输出 = assets 名单一致
#   df -h /dev/vda1 | tail -1                                            # 交付时 4.4G 可用
python3 /root/pi/.tools/p3b-index.py < <(printf '%s %s\n' \
  8f02f9bda5f953be468e9c4a4266f6c58ad55c782eb9cd8ebf03c46051eb9f24 63925997) > /root/pi/.tools/p3b-index.html
rsync -az -e "ssh -o BatchMode=yes -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv" \
  /root/pi/.tools/p3b-index.html root@64.90.11.50:/root/apk-serve/index.html
curl -s -o /dev/null -w "index %{http_code} %{size_download}\n" http://64.90.11.50:8000/index.html
curl -sI http://64.90.11.50:8000/8f02f9bda5f9/pi-host-full-debug.apk | head -6
curl -fsS -o /root/pi/dist/pi-host-full-debug.apk http://64.90.11.50:8000/8f02f9bda5f9/pi-host-full-debug.apk
sha256sum /root/pi/dist/pi-host-full-debug.apk          # 必须与编译机逐字相等（本轮：8f02f9bda5f9…51eb9f24）
python3 /root/pi/.tools/p3b-axml.py /root/pi/dist/pi-host-full-debug.apk          # 包体自证版本四项
python3 /root/pi/.tools/p3b-axml.py /root/pi/dist/pi-host-full-debug-0.1.4-p3a.apk  # 对照组（P3A 那份留档）

# —— 红线 / 冻结区复核 ——
cd /root/pi/android-host
git diff --stat 1f77127..HEAD -- core/rpc/src/main core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging
git diff --stat c1513be..HEAD -- feature/settings feature/sessions app/src/main/AndroidManifest.xml   # 三条都必须为空
grep -rn "sk-" --include="*.kt" core app feature | grep src/main | wc -l                              # 期望 0
find /root/pi/.tools/pi-1.0.4 -newermt '1 day ago' -type f | wc -l                                     # 期望 0

# —— 「没新增权限 / 运行时面不变」的对照（P3A 交付包 vs P3B 交付包，都在编译机 /root/apk-serve/）——
A=/root/android-sdk/build-tools/36.0.0/aapt2
diff <($A dump badging /root/apk-serve/515394fca808/pi-host-full-debug.apk | grep '^uses-permission') \
     <($A dump badging /root/apk-serve/8f02f9bda5f9/pi-host-full-debug.apk | grep '^uses-permission')        # 无输出 = 逐字相同
diff <(unzip -Z1 /root/apk-serve/515394fca808/pi-host-full-debug.apk | grep '^lib/arm64-v8a/') \
     <(unzip -Z1 /root/apk-serve/8f02f9bda5f9/pi-host-full-debug.apk | grep '^lib/arm64-v8a/')               # 无输出 = so 名单一致

# —— §3.3 探测复现（桌面基准，不动仓库冻结的 tools/capture）——
node /root/pi/.capture/mock/mock-server.js &
node /root/pi/.tools/p3b-probe.js /root/pi/.capture/probe33/rpc33.jsonl /root/pi/.capture/probe33/cmds-33.json probe

# —— 本轮没做的（不假装做过）——
# 真机 adb install -r / 真机大会话压缩（mock 会话太小，pi 回 Nothing to compact）
```

## 11. 计数汇总

| 项 | 值 |
|---|---|
| P3B 小节 | 7（§3.1–§3.7）→ 4 条 `feat` + 1 条 `feat/test` 收尾（§3.5 两条：版本与竞态修）+ docs 条目 |
| 代码改动面 | 22 个路径（10 新增 / 12 修改），+4293 / −62，全部在 `feature/chat` 与 `app` 的版本两行 + 测试 |
| 测试 | 355 → **448**（新增 93：§3.1 23 / §3.2 11 / §3.3 30 / §3.4 28 / §3.5 1），`failures=0 errors=0`，skip=1 |
| 编号延续 | `ChatViewModelTest` case32–case72（P3A 停在 case31）；新增类内编号 h01–h15 / p01–p14 / st01–st12 |
| APK | 63925997 B / `8f02f9bda5f9…51eb9f24` / versionCode 6 / 0.1.5 / +59,769 B vs P3A |
| 交付 URL | `http://64.90.11.50:8000/8f02f9bda5f9/pi-host-full-debug.apk`（200） |
| 冻结区 | 六条路径 `git diff 1f77127..HEAD` 为空；`core/rpc` 整目录在 `c1513be..HEAD` 亦为空 |
| pi 源码 | 24h 内被写入的文件数 = 0 |
| 编译机磁盘 | 交付时 4.4G 可用 |
