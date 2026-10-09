# REPORT-P3A — 审计日志 + 崩溃收集 + 备份合规 + 诊断页（versionCode 5 / 0.1.4）

规格书：`/root/pi/.tools/qoder-task-p3a.md`（P3 产品化首批）。
交付窗口：`29571fa`（REPORT-P2H）→ HEAD，**7 条 commit，一小节一条**（§3.1–§3.6 六条 `feat(p3a)` + §3.7 一条 `docs(p3a)`）。

## 1. 结论摘要

- 四件产品化能力全部落地并有实测：
  ①`filesDir/audit/audit-<yyyy-MM-dd>.jsonl` 工具审计（args 原样、result 截 4096、保留 14 天、失败只 `Log.w`）；
  ②`filesDir/crash/crash-<毫秒>[-N].txt` 未捕获异常收集（**同步写 + try/finally 委托原 handler + 保留 10 份**）；
  ③`fullBackupContent` + `dataExtractionRules` 把 `home`/`audit`/`crash`/`runtime` 四个目录从备份面与设备迁移面排除，
  `allowBackup` 仍为 `false`（纵深防御）；④设置页「诊断」Section（列表 + 空态 + 清空 + 复制最近一份）。
- 测试：**304 基线 → 355 用例**，新增 **51** 条（§3.1 14 + §3.2 8 + §3.3 11+4 + §3.4 2 + §3.5 12），
  全部在编译机跑，`failures=0 errors=0`，唯一 1 条 skip 是 `:core:rpc` 的 `PiDesktopE2eTest`（环境门控，P2H 起如此）。
- §2 红线逐条有实测（§4）：冻结区六条路径 `git diff 1f77127..HEAD` **为空**（`PathLayout.kt` 一个字符没动，
  审计/崩溃目录另起 `AuditPaths`/`CrashPaths`）；审计与崩溃的失败都不进对话主流程；`sk-` 明文 grep `src/main` **0 命中**；
  没加任何权限、没加任何第三方依赖。
- APK：**63,866,228 B**，sha256 `515394fca808676372a80406425578aa2d3bd0b97c05f673734947e02e01982c`，
  badging 实测 `versionCode='5' versionName='0.1.4'`；双端（编译机 `/root/apk-serve/515394fca808/` + 本机 `/root/pi/dist/`）
  **逐字相等**，HTTP 下载 200（§7）。包内 29 个 `.so` 与 `assets/runtime/*` 与 P2H **名单逐字一致**。
- 一条必须记住的过程教训（§8 第 17 条）：运行时资产不入 git，`bbuild.sh sync` 的 `--delete` 会把编译机
  `tools/runtime-packaging/out/` 与 `app/src/main/{jniLibs,assets}` 一起清掉，于是第一次 `:app:assembleDebug`
  得到的是 **12,222,700 B 的空壳包**（能装、跑不了 pi）。本轮从 P2H 交付包复原产物后重打，才是交付的那一份。

## 2. 交付物落点（要求 → 代码 → 证据）

### 2.1 §3.1 `core:engine` 审计底座

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| `AuditEntry`（9 字段，`@Serializable`）+ `AuditKind{TOOL_START,TOOL_END,PERMISSION}` | `core/engine/.../audit/AuditEntry.kt` | `FileAuditSinkTest` 01/02/07/08/10 直接拿字段做断言 |
| `AuditPaths`：`auditDir(ctx)` / `auditFile(ctx, day)`，KDoc 注明「`core/environment` 冻结，故独立定义」 | `audit/AuditPaths.kt`（`FILE_PREFIX="audit-"`、`FILE_SUFFIX=".jsonl"`、`RETENTION_DAYS=14`） | 用例 14「文件名 ↔ 日期」往返；`git diff` 证明 `PathLayout.kt` 未动（§5） |
| `AuditSink` 为 `fun interface`，KDoc 写死「实现必须不抛；失败 `Log.w` 后吞」+ `NoopAuditSink` | `audit/AuditSink.kt` | 用例 11（Noop 静默且不创建任何东西）；`ChatViewModelTest` case30 演「违约实现也带不下对话」 |
| `FileAuditSink(dir)`：追加写 jsonl、`Mutex` 串行、`Dispatchers.IO` fire-and-forget（scope 注入）、懒清理 14 天、IO 异常吞掉 | `audit/FileAuditSink.kt:60`（`record`）、`:89`（`writeLocked`）、`:115`（`cleanup`）、`:136`（`RESULT_PREVIEW_MAX=4_096`） | 用例 01/02/03/04/05/06/12/13；并发 09（两协程各 50 条 → 100 条完整行） |
| DI：`@Provides @Singleton`（真 `filesDir/audit` + app 级 scope） | `di/EngineModule.kt:153`（`@AuditScope CoroutineScope`）、`:169`（`FileAuditSink`）、`:179`（接口→实现转发边）；scope 注解 `audit/AuditScope.kt` | `:app` 的 `AppTestDoubles` 补了一条 `auditSink` 假体边（`1d8d622`），Hilt 建图通过 = 绑定形状正确 |

`FileAuditSinkTest` 14 条：01 追加两行读回两条 / 02 按天分文件 / 03 14 天外懒清理 / 04 坏行容忍 /
05 IO 失败不抛 / 06 失败后仍可用 / 07 args 原样 / 08 preview 4096 截断 / 09 并发无交错 / 10 `isError=null` 透传 /
11 Noop 静默 / 12 scope 已取消时丢条目不抛 / 13 清理保边界日 + 不认外国文件 / 14 命名往返。

**一处实测换来的写法**（KDoc `FileAuditSink.kt:102-109` 留了字）：追加必须 `appendText`，
不能 `outputStream().use{}` —— Kotlin stdlib 的 `File.outputStream(append=false)` 默认**截断**，
第一轮编译机实测把前一行洗掉，01/04/08/09/10 五条同时红。

### 2.2 §3.2 `ChatViewModel` 三挂点

| 挂点 | 代码 | 记的条目 |
|---|---|---|
| `PiEvent.ToolExecutionStart` | `feature/chat/.../ChatViewModel.kt:597-608` | `TOOL_START`，`argsJson` = args 原样序列化（不解析、不美化、不截断） |
| `PiEvent.ToolExecutionEnd` | `:619-629` | `TOOL_END`，`isError` + `resultPreview = resultToText(result)` 截 4096 |
| 权限三选一 / 超时 / TurnEnd 抢跑 | `:476-480`（手一锤子）、`:520-527`（兜底）、`:494-504`（`writePermissionAnswer` 唯一出口） | `PERMISSION`，`toolName="permission"`，`toolCallId=requestId`，`argsJson ∈ {confirmed, cancelled, cancelled-timeout}` |

- 构造参数 `auditSink: AuditSink = NoopAuditSink` 放在参数表**末尾**（`:83`），既有 23 条用例一行没改就跑起来。
- **§2 与 §3.2 的字面冲突这样调和**（`:532-544` 的 KDoc 原文记着理由）：三个挂点的调用行确实一字没有 try，
  `runCatching` 只在私有漏斗 `recordAudit(...)` 里出现一次。理由：`AuditSink` 是接口，第三方/违约实现抛出来
  会落在**事件收集协程**上（`viewModelScope` 的 `SupervisorJob` 不挡兄弟自己死），后果正是红线要禁止的
  「审计把对话带下水」。生产 `FileAuditSink` 自己已经全量吞异常，所以这层是**永远不该被触发**的第二道闸，
  触发即 `Log.w("PiHost/Audit", "AuditSink 违反『必须不抛』契约…")`。
- 审计挂在 `settledPermissions` 闩**之后**：同一条 request 被兜底路径重入时既不多发线协议应答也不多写一行（case26/27 钉住「恰好一次」）。

`ChatViewModelTest` 新增 case24–case31 八条（args 原样 + cwd、preview 截断、confirmed/cancelled 各恰好一次、
TurnEnd 抢跑补 cancelled 不是 timeout、虚拟时钟到点 `cancelled-timeout`、审计不扰动状态机、sink 抛不崩、Noop 默认值无害）。

### 2.3 §3.3 崩溃收集

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| `CrashPaths`（`crashDir(ctx)` 独立声明，不入 `PathLayout`） | `core/engine/.../crash/CrashPaths.kt`（`FILE_PREFIX/SUFFIX`、`DEFAULT_KEEP=10`、`reports()`、`NEWEST_FIRST`、`millisOf`、`indexOf`、`isReport`） | `CrashReporterTest` 11（只认自己的文件名）、10（同毫秒两份） |
| `install()`：读默认 handler 存为 prev，装自身；`uncaughtException = try { writeSync } finally { prev?.uncaughtException }` | `crash/CrashReporter.kt:74`（`install`）、`:88`（`uninstall`）、`:109-114`（`try/finally`） | 用例 01（委托恰好一次 + 顺序证据）、04（收集面全线炸仍委托）、06（prev=null 安全）、07（二次 install 不形成自指链） |
| `writeSync(thread, throwable)`：**同步** `BufferedWriter` 写 `crash-<ms>.txt`，首行 time/version/device/SDK_INT/thread.name，随后 `toString` + 完整栈（`PrintWriter`→`StringWriter`），**不跑协程、不依赖 DI** | `:130`（`writeSync`）、`:146`（`writeBody`）、`:165`（`header`）、`:179`（`traceText`） | 用例 02（首行五要素 + `at …Test` 栈帧 + `Caused by:`；断言不含 `sk-`） |
| `cleanup(keep=10)` 按 mtime 保留最近 keep 份 | `:197` | 用例 05（6 份留 3 份、再调无操作返回 0、`keep=-1` 当 0 处理） |
| `pendingReports()` 供 §3.5 查询 | `:219`（+ `:222 clearReports`、`:240 readLatest`） | 用例 08/09 + app 侧 13 |
| `PiHostApp.onCreate` → `@Inject crashReporter` → `install()` + `cleanup()`；DI 给真 dir + `BuildConfig.VERSION_NAME` | `app/src/main/kotlin/works/earendil/pihost/PiHostApp.kt`（`onCreate`）+ `app/.../di/CrashReportModule.kt:35` | `CrashReportWiringTest` 11/12/13/14（真 `PiHostApp` 起 Robolectric，断言默认 handler `is CrashReporter`、`version=${BuildConfig.VERSION_NAME}` 上屏到文件、目录 = `filesDir/crash` 且读操作不创建目录） |

`CrashReporterTest` 11 条 + `CrashReportWiringTest` 4 条 = 15 条。
纯 JVM 侧两个纪律：`Thread.setDefaultUncaughtExceptionHandler` 是 **JVM 全局**，所以每条用例
`@Before` 存、`@After` 还原；`android.util.Log` 在纯 JVM 是抛 `not mocked` 的桩，故 `logger` 可注入且默认实现自己裹 `runCatching`。

### 2.4 §3.4 备份合规

- `app/src/main/res/xml/backup_rules.xml`：`<full-backup-content>` 四条 `<exclude domain="file" path="home|audit|crash|runtime" />`。
- `app/src/main/res/xml/data_extraction_rules.xml`：`<cloud-backup>` 与 `<device-transfer>` **两块各四条**（同样的清单）。
- `app/src/main/AndroidManifest.xml`：加 `android:fullBackupContent="@xml/backup_rules"` 与
  `android:dataExtractionRules="@xml/data_extraction_rules"`，`allowBackup="false"` **一字不动**，
  上面留了一段注释说明「为什么在已经关掉备份的包上还写这两份规则」。
- 验证（比 §3.4 的门槛多做一步，因为这两份资源是**合规面**而不是装饰）：
  `ManifestPermissionTest` 04/05 用 `resources.getXml()` 走 `XmlPullParser` 解析编译后的二进制 XML，
  断言「节名集合 + `domain:path` 集合」两条都对，并顺手断言 `ApplicationInfo.FLAG_ALLOW_BACKUP == 0`
  —— 有人把 `allowBackup` 改回 `true` 时这条会红。

### 2.5 §3.5 设置页「诊断」Section

| 规格书要求 | 落点 |
|---|---|
| `CrashReportSummary(fileName, sizeBytes, modified: Instant)` | `feature/settings/.../Diagnostics.kt:28` |
| `crashReports: StateFlow<List<CrashReportSummary>>` + `refreshCrashReports()` + `clearCrashReports()` + `suspend readLatestCrash()` | `SettingsViewModel.kt:147/156`、`:174`、`:187`、`:208`（+ `:211 crashCopied` / `:216 crashCopyMissed` 把两条回执文案也收在 VM） |
| 全部走 `CrashReporter`/`CrashPaths`，不重复实现 | `Diagnostics.kt:54` `FileDiagnosticsSource` 三个方法**只是转发**（`pendingReports/readLatest/clearReports`），本层不碰 `File`、不拼路径、不排序；`withContext(Dispatchers.IO)` 在实现侧做完 |
| DI：CrashReporter 与 VM 之间补注入边 | `feature/settings/.../di/SettingsModule.kt` 加 `@Provides @Singleton diagnosticsSource(impl: FileDiagnosticsSource): DiagnosticsSource`（照 `relayEndpointSource` 先例给**接口**）；`CrashReporter` 本体留在 §2.3 的 `CrashReportModule` |
| UI：列表（文件名 + 大小 + 时间）、空态「无崩溃记录」、按钮「清空」「复制最近一份」（`LocalClipboardManager.setText`；null 时 notice 提示无记录） | `ui/SettingsScreen.kt:150-167`（接线）+ `:630`（`DiagnosticsSection`）+ `:676 crashRowText` / `:680 crashSizeText`（B/KB/MB 一位小数）/ `:687 crashTimeText` |
| tag 前缀 `screen-settings-diagnostics` + 中文串 colocate | `ui/SettingsTags.kt:85-92`（`DIAGNOSTICS_SECTION/EMPTY/CLEAR/COPY/REFRESH` + `diagnosticsRow(fileName)`），`:117-124` 的 `init{}` 把前缀关系钉成编译期自检；文案在 `SettingsStrings`（`:263` 起） |

形状决定（都写进 KDoc，不是随手）：
- **正文永不上屏**：一行只有「文件名 · 大小 · 时间」，崩溃正文唯一的出口是「复制最近一份」→ 剪贴板。
  `i02` 用三条 `assertFalse` 钉住栈类名 / 中文异常语 / `/data/user/0` 绝对路径都不出现在任何显示文本里。
- **三颗按钮都不因空列表禁用**：`readLatest` 的语义是「最近一份**读得动**的」，列表非空但全坏时按钮必须还能点，
  点了才拿到 null、才有 `DIAGNOSTICS_NONE_NOTICE`；禁用等于把这条通路堵死。
- 清空回执说**实际删掉的份数**（`crashCleared(deleted)`），不说「已清空」——有一个文件被握着删不掉时屏幕上必须说真数。
- 读列表失败**不弹 notice**（与凭据/中转两处一致）：空态那句「无崩溃记录」已经说明「没有可读的报告」。

测试 12 条 = VM 6（`t27`–`t32`：进屏读一次且保序 / 清空回执与列表清空 / `readLatestCrash` 三态含读口抛 /
读失败留空且不弹 / 清失败保留列表带原因且恢复后能清 / 两条复制回执）
+ UI 6（`i01`–`i06`：空态与三颗可点按钮 / 两行报告 + 正文不上屏 / 清空走 VM 且行消失 /
复制进剪贴板且 notice 不念内容 / 落空时剪贴板原样不动 + 读口抛同形 / 重读有效且失败只是空着）。
剪贴板的断言是**读回系统服务**（`ShadowClipboardManager`），问的是「用户 Ctrl+V 出来的是什么」。

### 2.6 §3.6 版本 + 注入边清点

- `app/build.gradle.kts:19-20`：`versionCode = 5` / `versionName = "0.1.4"`。
- `buildFeatures.buildConfig = true`（AGP 8 起默认关；崩溃报告首行的版本只能来自 `BuildConfig.VERSION_NAME`，
  全仓只有 `:app` 开这一项）。
- `ManifestPermissionTest` 用例 03 改名为 `…versionCode 5 and versionName 0 point 1 point 4`，期望值 `5L` / `"0.1.4"`，
  单调性断言改成「必须严格高于 P2H 的 4」。
- **注入边清点结论：一条都不补**（§3.6 原文写「补 AuditSink/CrashReporter 注入边」）：
  `AuditSink` 在 §3.2 已经补过（`AppTestDoubles.auditSink`，假体 `RecordingAuditSink`）；
  `CrashReporter` 的绑定住在 `:app` **主源集**的 `CrashReportModule`，而测试只 `@UninstallModules(EngineModule::class)`，
  它没被卸掉 → 再给一条就是 `DuplicateBindings`；`DiagnosticsSource` 的转发边住在没被卸载的 `SettingsModule`，同理。
  实证：`MainActivityTest` 那条设置路由真造六依赖的 `SettingsViewModel`，全绿。理由写进 `AppTestDoubles.kt:159-174` 的 KDoc。

## 3. 改动清单（P3A 独占：代码面 **31 个路径 = 14 新增 / 17 修改**；`docs(p3a)` 再加本报告 → `git diff --name-status 29571fa..HEAD` 共 32）

```
新增(14)  core/engine/.../audit/{AuditEntry,AuditPaths,AuditScope,AuditSink,FileAuditSink}.kt
          core/engine/.../crash/{CrashPaths,CrashReporter}.kt
          core/engine/src/test/.../audit/FileAuditSinkTest.kt
          core/engine/src/test/.../crash/CrashReporterTest.kt
          app/src/main/kotlin/works/earendil/pihost/di/CrashReportModule.kt
          app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml
          app/src/test/kotlin/works/earendil/pihost/CrashReportWiringTest.kt
          feature/settings/src/main/kotlin/.../settings/Diagnostics.kt
修改(17)  core/engine/build.gradle.kts · core/engine/.../di/EngineModule.kt
          feature/chat/.../ChatViewModel.kt · feature/chat/src/test/.../{ChatViewModelTest,Fakes}.kt
          feature/settings/.../{SettingsViewModel,di/SettingsModule,ui/SettingsScreen,ui/SettingsTags}.kt
          feature/settings/src/test/.../{Fakes,SettingsViewModelTest,ui/SettingsScreenTest}.kt
          app/build.gradle.kts · app/src/main/AndroidManifest.xml · app/.../PiHostApp.kt
          app/src/test/.../{AppTestDoubles,ManifestPermissionTest}.kt
```

提交链（§0 要求一小节一条）：

| commit | 小节 | 内容 |
|---|---|---|
| `d2c4826` | §3.1 | 审计底座 5 个类 + EngineModule 三条 provider + `FileAuditSinkTest` 14 条 |
| `1d8d622` | §3.2 | ChatViewModel 三挂点 + `recordAudit` 漏斗 + case24–31 + `AppTestDoubles` 审计边 |
| `ed57449` | §3.3 | `CrashPaths`/`CrashReporter` + `CrashReportModule` + `PiHostApp.onCreate` + 11+4 条 + `buildConfig=true` |
| `a0a93c6` | §3.4 | manifest 两属性 + 两份 `res/xml` + `ManifestPermissionTest` 04/05 |
| `4f5197a` | §3.5 | `Diagnostics.kt` + VM/Module/Screen/Tags + 12 条（t27–32、i01–06） |
| `88ad549` | §3.6 | versionCode 5 / 0.1.4 + 用例 03 换值 + 注入边清点 KDoc |
| `docs(p3a)` | §3.7 | 本报告 + 一处 KDoc 行号引用修正（`ChatViewModel.kt:572` 的 `EngineModule:174` → `EngineModule.kt:216`，注释文字，不动代码行号；§8 第 22 条）|

## 4. 红线复核（规格书 §2 逐条，全部实测）

| 红线 | 实测证据 |
|---|---|
| pi 源码零修改 | 实测：`find /root/pi/.tools/pi-1.0.4 -newermt '1 day ago' -type f \| wc -l` = **0**（整个 P3A 窗口内没有任何文件被写），目录 mtime 仍是解包那一次的 `Oct 5 19:22`；访问方式只有 `grep`/`Read`。本轮 31 个代码路径全在 `android-host` 仓库内 |
| 冻结区六条路径 diff=0 | §5 的实测命令，输出为空（含 `core/environment/PathLayout.kt` 单文件复核） |
| 审计/崩溃失败绝不影响对话 | 审计：`FileAuditSinkTest` 05/06/12 + `ChatViewModelTest` case30（违约 sink 也不崩，事件收集协程继续跑完工具定稿 + 权限应答）；崩溃：`CrashReporterTest` 03/04（全线炸时 `runCatching` 成功且委托照旧） |
| handler 必须包装并委托原 handler（try/finally） | `CrashReporter.kt:109-114` 的 `finally`；用例 01 用探针把「**先落盘、后委托**」的顺序钉成 `listOf("file-exists-at-delegate","delegated")` |
| 崩溃文件同步写 | `writeSync` 全程裸 `BufferedWriter(FileWriter)`，无协程；用例 01 在 `prev.uncaughtException` **被调用的那一刻**就能看到文件已在盘上（协程形态证不出这条顺序） |
| apiKey 不回显、不落审计 | `grep -rn "sk-" feature core app --include="*.kt" \| grep src/main` = **0 命中**；审计条目的字段表里没有凭据位（`AuditEntry` 九字段，§2.1）；`i02` 另外断言崩溃正文（含绝对路径与栈）不上屏 |
| args 原样、result 只存 preview 截 4096 | 用例 07（含换行/引号/中文的原文往返）+ 08（5000 字符截到 4096）；截断动作在落盘侧 `FileAuditSink.clamp`（`:125-128`），所以任何调用方都受同一上限保护 |
| 不新增网络权限 / 不引入新第三方依赖 | APK badging 的 `uses-permission` 五行与 P2H 逐字相同（含 AGP 自动加的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）；`git diff 29571fa..HEAD --gradle*` 只有 `app/build.gradle.kts`（版本号 + `buildConfig`）与 `core/engine/build.gradle.kts`（把 `kotlinx-serialization-json` 从 `implementation` 升成 `api`，因 `AuditEntry` 是带 `@Serializable` 的公开 API 类型；库坐标本身 P2H 已在 version catalog 里，**没有新增依赖**） |
| 仓库可编译 + 既有 304 全绿 | §6 矩阵：355 条，`failures=0 errors=0`；`:app:assembleDebug` BUILD SUCCESSFUL |

## 5. 冻结区复核（§4 第一条验收）

```
$ git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment \
      docs/protocol.md tools/capture tools/runtime-packaging
（无输出）
$ git diff --stat 1f77127..HEAD -- core/environment/src/main/kotlin/works/earendil/pihost/core/environment/PathLayout.kt
（无输出）
```

口径说明（沿用 P2H §5 的结论）：`1f77127` 早于 P2F/P2G/P2H，**六条冻结路径在这个基线下仍然为空**，
因为四轮改动从来没碰过它们；P3A 自己的窗口是 `29571fa..HEAD`，同样为空。
审计/崩溃目录因此**另起两套独立声明**（`AuditPaths` / `CrashPaths`，§1.3 的方向层裁决），
KDoc 里各写明「`core/environment` 在冻结区，故本对象自带目录布局」。

## 6. 测试矩阵（编译机，一条 gradle 命令一个模块）

| 模块 | 任务 | 测试类（条数） | 合计 | fail/err/skip | 门槛 | 判定 |
|---|---|---|---|---|---|---|
| `:core:process` | `test` | JvmProcessTest 3 | **3** | 0/0/0 | 3 | ✓（P2H 基线不变） |
| `:core:rpc` | `test` | GoldenReplay 9 / LineFramer 9 / PiSession 8 / RpcModel 7 / PiDesktopE2e 1 | **34** | 0/0/**1** | 34 | ✓（skip 是环境门控的桌面 E2E，与 P2H 同一形态） |
| `:core:environment` | `testDebugUnitTest` | EnvFactory 2 / PathLayout 3 | **5** | 0/0/0 | 5 | ✓ |
| `:core:engine` | `testDebugUnitTest` | **FileAuditSink 14（新）** / **CrashReporter 11（新）** / AssetInstaller 5 / ChatEngineAdapter 6 / DataStoreProbe 4 / ExecFallbackStarter 9 / ModelCredentialStore 17 / PiEngineService 7 / PiSpawn 4 / RealRuntimeAssetsInstall 2 / RelayEndpointStore 31 / RuntimeInstall 5 / SessionSupervisor 14 / SettingsRepository 6 / SpawnDiagnosticsSink 6 | **141** | 0/0/0 | ≥116 | ✓（116 基线 + 25 新） |
| `:feature:chat` | `testDebugUnitTest` | **ChatViewModelTest 31**（23 基线 + case24–31 新 8）/ 其余 7 类 24 | **55** | 0/0/0 | ≥47 | ✓（既有 23 条一条没改） |
| `:feature:sessions` | `testDebugUnitTest` | SessionScanner 10 / SessionsViewModel 12 / SessionsScreen 3 | **25** | 0/0/0 | 25 | ✓ |
| `:feature:settings` | `testDebugUnitTest` | SettingsViewModelTest **36**（30+6：t27–t32）/ SettingsScreenTest **33**（27+6：i01–i06） | **69** | 0/0/0 | ≥57 | ✓（+12） |
| `:app` | `testDebugUnitTest` | MainActivity 11 / **ManifestPermission 5**（3→5：备份两条；用例 03 期望值换 5L/0.1.4）/ PiEngineServiceWiring 3 / **CrashReportWiring 4（新）** | **23** | 0/0/0 | ≥17 | ✓（+6） |
| **合计** | | | **355** | **0/0/1** | ≥304 | **0 红**（304 基线 + 51 新） |

口径补充：`build/test-results/` 下每个模块同时有 `testDebugUnitTest/` 与 `testReleaseUnitTest/` 两堆 XML，
本轮只跑（也只计）**debug** 那一堆；release 侧是早先轮次的残留（`app/.../testReleaseUnitTest/TEST-…MainActivityTest.xml`
的时间戳 = 2026-10-06，比本轮 10-09 02:18 早三天，且只有 2 条），**没有**把它算进 355。

`RealRuntimeAssetsInstallTest` 那 2 条在 §3.7 第一次跑时是 **skip**（`assumeTrue` 找不到
`tools/runtime-packaging/out/assets/runtime/`，见 §8 第 17 条），产物复原后 `--rerun` 重跑为 **2 条真跑全绿**，
所以 engine 这一行是 141 且 skip=0。这条 skip 的存在与消失本身就是「运行时资产不在 git 里」那件事的可观测证据。

docs 窗口（本报告 + 那条 KDoc 文字修正）之后**又复算了一次**，不拿旧数字交差：
`:feature:chat:testDebugUnitTest --rerun` 与 `:app:testDebugUnitTest --rerun` 各强制重跑一遍（55 / 23，`BUILD SUCCESSFUL`），
其余六模块的 Gradle 报告里编译链全 `UP-TO-DATE` —— 注释不进字节码，这条不是推测而是构建图的事实。
上表八个数字（含合计 355）取自复算之后的 `build/test-results/*/**.xml`，由 `/root/pi/.tools/p3a-count-tests.sh` 汇总（§10 有命令）。

## 7. assembleDebug + APK 双端落盘

| 项 | 实测值 |
|---|---|
| 任务 | `:app:assembleDebug` → `BUILD SUCCESSFUL in 37s`（159 tasks） |
| 路径 | 编译机 `/root/android-host/app/build/outputs/apk/debug/app-debug.apk` |
| 字节数 | **63,866,228**（P2H 63,826,901 → **+39,327 B**） |
| sha256 | `515394fca808676372a80406425578aa2d3bd0b97c05f673734947e02e01982c` |
| badging | `package: name='works.earendil.pihost' versionCode='5' versionName='0.1.4' … compileSdkVersion='36'`、`minSdkVersion:'26'`、`targetSdkVersion:'35'` |
| 包内 `lib/arm64-v8a` | 29 个 `.so`，**名单与 P2H 逐字一致**（`diff` 两份 `unzip -Z1` 清单：无输出） |
| 包内 `assets/runtime` | `manifest.json` + `pi-runtime.zip`（sha256 `de05ce57…` / `abe3366c…`，与 P2H 同源） |
| 二进制 manifest | `allowBackup=false`、`fullBackupContent=@0x7f0d0000`、`dataExtractionRules=@0x7f0d0001`、`extractNativeLibs=true` |

落盘与下载：

| 落点 | 尺寸 | sha256 |
|---|---|---|
| 编译机 `/root/apk-serve/515394fca808/pi-host-full-debug.apk`（目录名 = 包体 sha256 前 12 位） | 63,866,228 | `515394fca808…e01982c` |
| 本机 `/root/pi/dist/pi-host-full-debug.apk`（经 HTTP 拉回） | 63,866,228 | `515394fca808…e01982c` |

- **包体 ↔ 最终源码树的对应关系（实测，不是声明）**：docs 窗口的注释文字修正同步进编译机后，
  重跑 `assemble-apk.sh`，159 个 task 全 `UP-TO-DATE`，`app/build/outputs/apk/debug/app-debug.apk` 的 sha256 复算仍是
  `515394fca808…e01982c`（与 `/root/apk-serve/515394fca808/pi-host-full-debug.apk` 逐字相等，APK mtime 未变）。
  即交付包与 `HEAD` 的源码树对得上，不存在「包是旧 tree 打的」这种错位。
- 两端 **逐字相等**（同一条 sha256，本机这份是从 `http://64.90.11.50:8000/` 下载后再算的，不是 scp 直接拷的）。
- HTTP：`GET /index.html` → **200**；`GET /515394fca808/pi-host-full-debug.apk` → **200**，
  `Content-Length: 63866228`；`GET -r 0-0` → **200** + 同长（`python3 -m http.server` 不支持 206，属常态）。
- `/root/apk-serve/index.html` 已更新（源文件 `/root/pi/.tools/p3a-index.html`）：P3A / P2H / P2G 三行并列，
  P3A 标 `.latest`，caption 写了 +39,327 B 的来源与运行时资产不变。
- 磁盘：全矩阵跑完时编译机 `/dev/vda1` 可用 **5.3G**；§3.7 收尾（复原 `out/` 115M + 复跑 `assemble-apk.sh` + 两个模块 `--rerun`）之后复测 **4.5G**，仍在 ≥4G 门槛之上；整轮无 OOM kill。

## 8. 偏差、遗留与未执行

1. **cwd 的取值来源**（§3.1/§3.2 点名要记的取舍）：审计条目的 `cwd` = `PathLayout.projectDir(ctx, EngineModule.DEFAULT_PROJECT).absolutePath`
   （`ChatViewModel.kt:576`）。这与生产装配喂给 `SessionSupervisor.workingDir` 的那一条（`EngineModule.kt:216`）
   是**同一个表达式**，所以「审计记的目录 = 子进程真实 chdir 的目录」，没有第二份真相；
   `PathLayout.projectDir` 只构造路径不建目录，故审计不碰磁盘、不挂起。整条包在 `runCatching{…}.getOrDefault("")` 里，
   Context 不可用时宁可写空串也不炸对话。P3 之后有项目切换时，这一行是唯一要跟着改的地方。
2. **sessionId 的来源与一条真实缺口**：`sessionId = ChatViewModel.sessionId`（`:138`，切会话时 `:273` 赋值）。
   工具两个挂点在**无会话**时不可能收到事件（引擎没进程），所以 `sessionId=null` 只可能是异常态；
   权限挂点走 `writePermissionAnswer`，那里第一句是 `val id = sessionId ?: return`（应答本来就需要会话 id）——
   **后果**：冷启动阶段如果真来了 confirm 请求，用户答案会留在线协议里但**不落审计**。本轮没有为此改应答通路
   （改它等于把审计塞回主流程），如实记录为遗留 1。
3. **`PERMISSION` 条目借用 `argsJson` 装三态措辞**：规格书没给权限条目 `argsJson` 的形状。定案：
   `toolName="permission"`、`toolCallId=<uiRequest.id>`、`argsJson ∈ {"confirmed","cancelled","cancelled-timeout"}`、
   `resultPreview=null`。三态由调用点给原文（`:476`/`:525`），不从 `confirmed: Boolean` 反推——
   「拒绝」和「TurnEnd 抢跑兜底」都映射到 `cancelled=true`，只有调用点知道自己属于哪一种，而审计要留的正是这个区别。
   `TOOL_END` 的 `argsJson` 留空串（args 已在 START 那行，重复一遍等于把命令原文抄两遍）。
4. **§2「不额外包 try」与 §3.2「sink 抛不崩」的调和**：唯一 `recordAudit` 漏斗（见 §2.2）。
   规格书两处字面互斥，必须记一条。
5. **`CrashPaths` 多了撞名让路**：§3.3 只写 `crash-<System.currentTimeMillis()>.txt`。同一毫秒崩两次
   （典型形态：一个崩在 UI 线程、一个紧随崩在子线程）会互相覆盖成一份，实现改成基准名 + `-1`、`-2`…（上限 3600 档）。
   连带排序键变成 `(毫秒 desc, 序号 desc)`：`indexOf()` 让基准名 = -1，所以「最新一份」不会误认成同毫秒里更早那个
   （用例 10 钉死 `listOf("crash-5000-1.txt","crash-5000.txt")`）。`isReport`/`millisOf` 只认这套形态，
   外国文件一律不删（用例 11、`cleanup` 的安全边界）。
6. **`cleanup` 主键是 mtime（按 §3.3 原文），同 mtime 用文件名兜底**（`:197-217`）。生产里两者同源，
   兜底只在「拷贝过文件、mtime 被洗成一样」时才咬。
7. **`install()` 的幂等守卫**：§3.3 没要求。加它的原因是 `previous = 自己` 会形成自指委托链 →
   `uncaughtException` 无限递归（栈溢出，连崩溃文件都写不出）。用例 07 钉这条。同理 `uninstall()` 只在
   「当前默认 handler 确实是自己」时才还原，别把别人装的 handler 拆掉。
8. **委托只在 install 过之后存在**（§3.3 隐含）。第一轮 `CrashReporterTest` 04 就是栽在这里：
   没 `install()` 直接调 handler，`previous` 还是 null，委托计数 0≠1。KDoc 与用例注释都留了这句话。
9. **`:app` 开 `buildConfig=true`**：AGP 8 默认关（`RECON`：全仓此前无一处引用 `BuildConfig`）。
   崩溃首行的版本必须是 `BuildConfig.VERSION_NAME`，否则 §3.6 的 bump 与崩溃报告版本行会成两份真相。
   `CrashReporter` 的 DI 因此只能住在 `:app`（`CrashReportModule`）——`core:engine` 拿不到 app 的 `BuildConfig`。
10. **§3.3 点名的 app 侧 Robolectric 装配测试不能用 Hilt 测试基建**，两条实测拒绝（原文照录）：
    - `java.lang.IllegalStateException: Hilt test, works.earendil.pihost.CrashReportWiringTest, cannot use a @HiltAndroidApp application but found works.earendil.pihost.PiHostApp`
    - `e: [ksp] .../CrashReportWiringTest.kt:55: [Hilt] @CustomTestApplication value cannot be annotated with @HiltAndroidApp. Found: works.earendil.pihost.PiHostApp`

    （第二条里的 `:55` 是**中间形态**那个文件的行号——那一版写的是 `@CustomTestApplication(PiHostApp::class)`，
    已被删除；最终形态（`@Config(application = PiHostApp::class)`）在 HEAD 的 `:49`，`:55` 现在是一句普通的字段声明。
    原文照录是为了留下「为什么最终形态长这样」的证据，不是声称当前树里还有那行。）

    即 `@HiltAndroidTest` 与真 `PiHostApp` 互斥，`@CustomTestApplication(PiHostApp::class)` 又嫌基类带 `@HiltAndroidApp`。
    最终形态：纯 `RobolectricTestRunner` + `@Config(application = PiHostApp::class)`，
    **不建 Hilt 图**——`onCreate` 里的 `@Inject lateinit var crashReporter` 在 Robolectric 下不装配（保持 null），
    所以用例 11 自己把真 `CrashReporter` 赋进字段再走 `onCreate`。§3.3 要求的两条
    （handler 被包装、`pendingReports` 反映磁盘）因此都落在「真实 Application 生命周期」这一侧，比走 Hilt 假体更贴生产。
    `AppTestDoubles` 那条「注入假目录」的写法没有采用：假目录由 Robolectric 沙箱 `filesDir` 天然提供（每沙箱唯一临时目录）。
11. **§3.6「补 CrashReporter 注入边」这条没照做**（理由见 §2.6，KDoc 在 `AppTestDoubles.kt:159-174`）：
    `:app` 主源集已经绑了，测试只卸载 `EngineModule`，再补即 `DuplicateBindings`（KSP 期失败，不是运行期）。
12. **备份排除路径不带尾斜杠、`domain="file"`**：§3.4 原文写的是 `home/`、`audit/`、`crash/`、`runtime/`。
    AOSP `Backups` 语义里 `path` 是**相对 domain 根的路径**，目录内容随目录一起排除；
    `full-backup-content` 的官方示例（`do_not_back_up`）也不带斜杠。写成 `home/` 是另一种形态，
    测试按 `domain:path` 精确匹配反而会把带斜杠的写法判成不认识的条目。两份 XML 的注释各自写了这条理由。
13. **`device-transfer` 也填了四条排除**（§3.4 只点名 `cloud-backup` 语义）。设备迁移与 ADB 备份走的是同一份数据，
    崩溃栈和审计里的绝对路径没道理允许迁走。用例 05 断言两块清单**相等**。
14. **Play dataSafety 表单的衔接**（§3.4 与合规面的关系，一句话版本）：本轮只改「**不离开设备**」这一面——
    `allowBackup=false` + 两份规则管的是 Android 云备份与设备迁移通道；dataSafety 表单问的是「收集/共享」。
    P3A 没新增任何网络出口（§4 权限复核），审计与崩溃报告都只写本地沙箱、只有人复制时才进剪贴板，
    所以表单侧的答案不变（不收集、不共享、不上报）。**如果**将来接「崩溃上报」，那就是 dataSafety 的一次改口，
    必须连同这一节的第 15 条一起重审。
15. **崩溃正文的出口只有一个**：`readLatest()` → 剪贴板。UI 列表只给「文件名 · 大小 · 时间」。
    规格书 §3.5 只要求「复制最近一份」，把「正文永不上屏」也写成断言（`i02` 三条 `assertFalse`）
    是因为崩溃栈里天然带 `/data/user/0/works.earendil.pihost/files/...` 这类路径，截图外流时它是内部结构。
16. **`:core:rpc`/`:core:process` 在编译机跑**：§0 说这两个「本机只跑」，但本机（Debian arm64 容器）**没有 JDK**
    （`java: command not found`，全盘 `find` 无 `java` 可执行体），也无 `gradlew`。等价载体：
    在编译机跑同一对任务，并且它们是纯 JVM 模块，任务名是 `test` 而**不是** `testDebugUnitTest`
    （第一次 `:core:rpc:testDebugUnitTest` 因此报 task-not-found）。用例数与 P2H 本机基线逐类相等（34/3）。
17. **运行时资产不入 git，被 `bbuild.sh sync` 的 `--delete` 清掉**（本轮最大的一次踩坑，如实记录）：
    `.gitignore:8/11/12` 三条分别排除 `tools/runtime-packaging/out/`、`app/src/main/jniLibs/`、`app/src/main/assets/`
    （P2B 起就有的规则：107M `.so` + 8M zip 不入版本库）。编译机上这些目录是**上一轮**产物，本机没有，
    于是每次 `sync` 都被 `--delete` 抹掉。后果：第一次 `:app:assembleDebug` 得到
    **12,222,700 B**（sha256 `ff95c4a2ff5644dd777417f90c37557969162def1fb8b4593092fcc5c4f720ec`）的空壳 APK——
    能装、能起 UI、跑不了 pi。P2H 报告没留这条，所以本轮第一次撞上。
    处理（**没改任何冻结脚本**）：从编译机 `/root/apk-serve/0944f6f3e161/pi-host-full-debug.apk` 里
    `unzip -j` 复原 `out/jniLibs/arm64-v8a`（27 个 `libpi_*.so`，107M）与 `out/assets/runtime/{manifest.json,pi-runtime.zip}`，
    再跑 `GRADLE=… bash tools/runtime-packaging/assemble-apk.sh` 得到交付的那一份。
    两个遗留：① 下一轮开工第一件事应当是复原 `out/`，否则产物尺寸会掉一个数量级；
    ② `bbuild.sh` 可以考虑给这三个 gitignore 路径加 `--exclude`（不在冻结区，但属工具面，本轮没动）。
18. **`assemble-apk.sh` 的两条体检不绿，且不是本轮引入**（照录，不解释成通过）：
    - `lib/arm64-v8a/*.so = 29（期望 27）` → 脚本用 `out/` 的文件数当期望值，而 P2G 起依赖侧自己带两个
      `.so`（`libandroidx.graphics.path.so`、`libdatastore_shared_counter.so`）也进 `lib/arm64-v8a/`，
      于是数量必然多 2。P2H 交付包同样是 29 个（本轮 `diff` 过名单）。
    - `smoke-elf: FAIL`：报的正是这两个 AAR 带的 `.so`「RUNPATH='' / 无 RUNPATH」。
      27 个 `libpi_*` **全部 OK**（`SONAME`/`NEEDED`/`e_machine` 逐条过）。
      `smoke-elf.sh` 的第 ③ 条判据是给 Termux 改写产物定的，对 androidx 预编译库是口径外。
    结论：APK 的运行时部分与 P2H 等价（名单逐字一致 + 两个 assets sha 一致），体检的两处红是脚本口径滞后，
    **本轮未修改冻结区的这两个脚本**。
19. **`:feature:settings` 测试虚拟屏 6400dp → 7200dp**：新 Section 插在 `ProbeCard` 之前，
    把排在最末的 `NOTICE` 又往下推；`assertIsDisplayed()` 问的是可见区相交，不加高就会得到
    「内容对但看不见」的假红（P2H 同一坑的第二次，理由写在类 KDoc）。
20. **`MainActivityTest` 的一次偶发红**（过程观察，本轮最终为绿）：§3.3 收尾时全量跑
    `tapping a session row switches the session and lands on chat without a second spawn`（`MainActivityTest.kt:301`）红过一次，
    单跑这一条与 `--rerun` 全量各一次都绿。形状是 Compose/Robolectric 的 `LazyColumn` 时序，与 P3A 改动无关
    （那条路不碰审计/崩溃/诊断）。没有为它加 sleep 或降级断言，留作观察项。
21. **`i02`/`i06` 的时间列只断言形状**（`Regex("""\d{2}-\d{2} \d{2}:\d{2}""")`）：测试机时区不是常量，
    写死 `11-14 22:13` 会是一条假断言。大小列（`1.2 KB`/`512 B`）写死，因为 `String.format` 的小数位不随时区变。
22. **报告行号是逐条对着源码复核过的**（本轮写报告时抓到四处凭记忆写下的偏移并改掉）：
    `FileAuditSink.kt` 的 `cleanup` 是 `:115`（不是 114）、`RESULT_PREVIEW_MAX` 是 `:136`（不是 125）、
    那段 `appendText` 教训的 KDoc 是 `:102-109`；`CrashReporter.kt` 的 `header` 是 `:165`（不是 172）；
    `Diagnostics.kt` 的两个符号是 `:28`/`:54`；`AppTestDoubles.kt` 的清点 KDoc 是 `:159-174`；
    用例块尾行 `ChatViewModelTest` case30 = `:1081-1115`、`CrashReporterTest` 04 = `:152-175`；
    偶发红那条在 `MainActivityTest.kt:301`。同一趟还抓到源码里一处 stale 引用
    （`ChatViewModel.kt:572` 把 `SessionSupervisor.workingDir` 记成 `EngineModule:174`，实际 `:216`），
    随本 commit 一并改掉。§9 三段贴的是**逐字复制**，与 `sed -n` 的输出对过，未改写。
23. **未执行**：① 真机安装 + `adb install -r` 覆盖安装（本机与编译机都无设备）；
    ② 故意抛未捕获异常验证崩溃文件真的出现在「诊断」页（同一原因）；
    ③ `out/` 复原流程的自动化（本轮是手工 `unzip -j`）；
    ④ §3.1 里 `AuditPaths` 的 `RETENTION_DAYS=14` 与 §3.3 `DEFAULT_KEEP=10` 没有做进设置页（规格书没要求）。
    命令与判据在 §7 与 §10，可直接复现。

## 9. §4 点名的三条**逐字**断言

以下三段是从测试文件逐字复制，未改写、未省略、未重排（行号 = 本轮 HEAD `88ad549` 的文件行号）。

### 9.1 ①「delegation（prev handler 被调用且 finally 保证）」

`core/engine/src/test/kotlin/works/earendil/pihost/core/engine/crash/CrashReporterTest.kt:52-77`（用例 01）：

```kotlin
    @Test
    fun `01 install replaces the default handler and remembers the previous one`() {
        val order = mutableListOf<String>()
        // 探针：委托那一刻文件是否已在盘上——这才证得出「try 写 / finally 委托」的顺序；
        // 只在 uncaughtException 返回后数文件是证不出顺序的。
        val probe = RecordingHandler(order = order, probe = {
            order += if (CrashPaths.reports(dir).isNotEmpty()) "file-exists-at-delegate" else "no-file-at-delegate"
        })
        Thread.setDefaultUncaughtExceptionHandler(probe)
        val reporter = reporter(dir)

        reporter.install()

        assertSameThreadHandler(reporter)
        assertTrue("installed 旗必须跟着真实全局状态走", reporter.installed)

        val boom = IllegalStateException("delegation-check")
        reporter.uncaughtException(worker("main-ish"), boom)

        assertEquals("原 handler 必须被叫到恰好一次", 1, probe.calls.size)
        val received = probe.calls.single()
        assertEquals("main-ish", received.first.name)
        assertSame("交回原 handler 的必须是同一个异常对象", boom, received.second)
        assertEquals("证据先落盘，再委托", listOf("file-exists-at-delegate", "delegated"), order)
        assertEquals(1, CrashPaths.reports(dir).size)
    }
```

同一格的极端形态（收集面全线炸时 `finally` 仍委托），`CrashReporterTest.kt:152-175`（用例 04，下面贴的是 `:169-174` 那段结论行）：

```kotlin
        val outcome = runCatching { reporter.uncaughtException(worker("hard-case"), IllegalStateException("boom")) }

        assertTrue("收集面全线坏掉时也不许把异常交回 JVM：${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals("`finally` 里的委托必须照样发生（否则 App 卡在垂死状态）", 1, probe.calls.size)
        assertEquals("hard-case", probe.calls.single().first.name)
        assertTrue("写盘失败时不该有半截文件", !dir.isDirectory)
```

被委托的那段实现在 `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/crash/CrashReporter.kt:109-114`：

```kotlin
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            runCatching { writeSync(t, e) }
        } finally {
            runCatching { previous?.uncaughtException(t, e) }
        }
```

### 9.2 ②「IO 失败不抛且后续调用仍可用」

`core/engine/src/test/kotlin/works/earendil/pihost/core/engine/audit/FileAuditSinkTest.kt:130-159`（用例 05 + 06）：

```kotlin
    // ── ⑤ IO 失败不抛（目录位置被一个普通文件占着）──────────────────────────────

    @Test
    fun `05 a failing mkdir does not throw out of record`() {
        val blocked = File(tmp.root, "audit-blocked")
        blocked.writeText("我不是目录")
        val failing = FileAuditSink(blocked, scope, logger = sinkLogger)

        failing.record(entry()) // 不抛 = 本条第一句胜利；抛了 JUnit 直接红

        assertTrue("失败要响一声：${logs}", logs.any { it.contains("审计落盘失败") })
        assertFalse("目录建不成时不该凭空写出文件", blocked.isDirectory)
    }

    // ── ⑤续 后续调用仍可用（同一实例、单日写入点被堵 ≠ 整个 sink 报废）──────────

    @Test
    fun `06 a failed write leaves the sink usable for later records`() {
        val blockedDay = LocalDate.now().minusDays(2)
        assertTrue(dir.mkdirs())
        // 把某一天的「文件」做成目录：那天的 append 必然 FileNotFoundException，其它天不受牵连
        assertTrue(File(dir, AuditPaths.auditFileName(blockedDay)).mkdirs())

        sink.record(entry(ts = tsOf(blockedDay), toolCallId = "blocked"))
        assertTrue("堵住的写入响了一声但没抛：${logs}", logs.any { it.contains("审计落盘失败") })

        sink.record(entry(toolCallId = "still-here"))

        assertEquals(listOf("still-here"), sink.readEntries(LocalDate.now()).map { it.toolCallId })
    }
```

崩溃侧同一形状的另一份证明，`CrashReporterTest.kt:117-148`（用例 03，节选）：

```kotlin
        val first = runCatching { reporter.uncaughtException(worker("io-fail"), IllegalStateException("first")) }

        assertTrue("写盘失败绝不能把异常交回 JVM：$first", first.isSuccess)
        assertFalse("失败时不该冒出目录", dir.isDirectory)
        assertEquals("失败必须留下一条日志：$logs", 1, logs.size)
        assertTrue("日志要指名落盘失败：$logs", logs.single().contains("崩溃报告落盘失败"))
        assertTrue("日志要带上原始异常类型：$logs", logs.single().contains("IOException"))

        // 「后续调用仍可用」：障碍清掉后**同一个实例**（同一个 dir 路径）照常工作，
        // 不留「失败过一次就废掉」的内部状态。
        assertTrue(dir.delete())
        assertTrue(dir.mkdirs())
        val second = runCatching { reporter.uncaughtException(worker("io-recover"), IllegalStateException("second")) }

        assertTrue("恢复后不该再抛：$second", second.isSuccess)
        val reports = CrashPaths.reports(dir)
        assertEquals("恢复后必须真的写出文件", 1, reports.size)
        assertTrue(reports.single().readText().contains("second"))
```

### 9.3 ③「sink 抛 RuntimeException 时 ChatViewModel 不崩」

`feature/chat/src/test/kotlin/works/earendil/pihost/feature/chat/ChatViewModelTest.kt:1081-1115`（case30）：

```kotlin
    @Test
    fun `case30 a sink that throws RuntimeException does not take the conversation down`() = runTest {
        // 红线用例：AuditSink 契约是「必须不抛」，这里**故意违约**，验 VM 那道单点闸真的挡住
        val audit = FakeAuditSink().apply { boom = RuntimeException("假体违约：模拟第三方 sink 抛异常") }
        val rig = rig(audit = audit)
        val session = ready(rig)

        rig.engine.emit(
            PiEvent.ToolExecutionStart(
                toolCallId = "boom-1", toolName = "bash", args = buildJsonObject { put("command", "ls") },
            ),
        )
        runCurrent()

        // ① 事件收集协程还活着：同一条总线上的后续事件照样进得来
        rig.engine.emit(
            PiEvent.ToolExecutionEnd(
                toolCallId = "boom-1", toolName = "bash", isError = false,
                result = buildJsonObject { put("content", "ok") },
            ),
        )
        runCurrent()
        assertEquals("违约之后工具卡仍按序定稿", ToolStatus.Ok, rig.tool("boom-1").status)

        // ② 用户动作路径同样不受牵连：权限应答继续发到线协议
        rig.engine.emit(uiRequest(id = "boom-2", method = "confirm", timeout = 5_000L))
        runCurrent()
        rig.vm.answerPermission(allowOnce = true)
        runCurrent()
        assertEquals(1, rig.engine.uiResponses.count { it.id == "boom-2" })
        assertEquals(true, rig.engine.uiResponses.last().confirmed)

        // ③ VM 本体仍在 Ready，会话 id 没被洗掉
        assertEquals(ChatUiState.Ready(session), rig.vm.uiState.value)
        assertEquals(TurnState.Streaming, rig.vm.turnState.value)
    }
```

## 10. 复现命令（逐条可直接跑）

```bash
# —— 编译机（一条命令一个模块；JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64，gradle 8.13 --no-daemon）——
# 第 0 步：先复原运行时资产，否则 assembleDebug 得到 12MB 空壳包（§8 第 17 条）
bash /root/pi/.tools/bbuild.sh sync
bash /root/pi/.tools/bbuild.sh raw 'printf "sdk.dir=/root/android-sdk\n" > /root/android-host/local.properties'
bash /root/pi/.tools/bbuild.sh raw 'A=/root/apk-serve/0944f6f3e161/pi-host-full-debug.apk;
  O=/root/android-host/tools/runtime-packaging/out; rm -rf "$O"; mkdir -p "$O/jniLibs/arm64-v8a" "$O/assets/runtime";
  (cd "$O/jniLibs/arm64-v8a" && unzip -q -o -j "$A" "lib/arm64-v8a/libpi_*.so");
  (cd "$O/assets/runtime" && unzip -q -o -j "$A" "assets/runtime/*"); ls "$O/jniLibs/arm64-v8a" | wc -l'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':core:process:test'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':core:rpc:test'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':core:environment:testDebugUnitTest'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':core:engine:testDebugUnitTest'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':feature:chat:testDebugUnitTest'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':feature:sessions:testDebugUnitTest'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':feature:settings:testDebugUnitTest'
MAX_RETRY=1 bash /root/pi/.tools/bbuild.sh gradlenosync ':app:testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh raw 'cd /root/android-host && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  GRADLE=/opt/gradle-8.13/bin/gradle bash tools/runtime-packaging/assemble-apk.sh'   # §8 第 18 条：两条体检不绿，属脚本口径

# —— APK 落盘与验证 ——
bash /root/pi/.tools/bbuild.sh raw 'sha256sum /root/android-host/app/build/outputs/apk/debug/app-debug.apk;
  /root/android-sdk/build-tools/36.0.0/aapt2 dump badging <apk> | head -2'
scp -o UserKnownHostsFile=/root/pi/.tools/kh_buildsrv \
  root@64.90.11.50:/root/apk-serve/515394fca808/pi-host-full-debug.apk /root/pi/dist/
curl -s -o /dev/null -w "%{http_code} %{size_download}\n" http://64.90.11.50:8000/515394fca808/pi-host-full-debug.apk

# —— 汇总八个模块的 XML 计数（本机 scp 到编译机 /tmp 后 bash 执行；§6 表格的数字来自这里）——
bash /tmp/p3a-count-tests.sh      # 源文件：/root/pi/.tools/p3a-count-tests.sh

# —— 红线 / 冻结区复核（本轮实测过的原命令，输出见 §4/§5）——
cd /root/pi/android-host
git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging
grep -rn "sk-" feature core app --include="*.kt" | grep src/main | wc -l          # 期望 0

# —— 本轮没做的（不假装做过，§8 第 23 条）——
# 真机 adb install -r / 故意抛异常看诊断页 / out/ 复原流程自动化
```

## 11. 计数汇总

| 项 | 值 |
|---|---|
| P3A 小节 | 7（§3.1–§3.7）→ 6 条 feat commit + 1 条 `docs(p3a)`（§3.7 的构建与落盘不产生代码提交，报告与一处 KDoc 行号修正进这一条） |
| 改动路径 | 31 个代码路径（14 新增 / 17 修改）+ 本报告 = 32，全部在非冻结区 |
| 测试 | 304 → **355**（+51），`failures=0 errors=0`，skip=1（`:core:rpc` 环境门控） |
| 新增用例分布 | `:core:engine` 25（审计 14 + 崩溃 11）/ `:feature:chat` 8 / `:feature:settings` 12（VM 6 + UI 6）/ `:app` 6（崩溃装配 4 + 备份 2） |
| APK | 63,866,228 B / `515394fca808…e01982c` / versionCode 5 / 0.1.4 / +39,327 B vs P2H |
| 交付 URL | `http://64.90.11.50:8000/515394fca808/pi-host-full-debug.apk`（200），`index.html` 三行并列 |
| 冻结区 | 六条路径 `git diff 1f77127..HEAD` 为空；`PathLayout.kt` 单文件亦为空 |
| 编译机磁盘 | 交付时 5.3G 可用（门槛 ≥4G） |
