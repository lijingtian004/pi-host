# REPORT-P2G — 模型凭据接入（auth.json 原子读写 + 设置页「模型接入」+ 0.1.2）

基线：`24b55be`（P2F 报告收尾）。本轮七个提交：
`2f78e20`(docs RECON-P2G) → `9e2d267`(core/engine store) → `840491c`(feature/settings UI) →
`7d8d9e6`(app 版本 3/0.1.2) → `3d86747`(settings 遮罩断言 + 变异实测) → `0067b26`(测试排版复原) → 本报告。

## 1. 结论摘要

- 交付物 §4.1/§4.2/§4.3/§4.4 **全部落地，无降级项**。规格书 §4.2 那句
  「ExposedDropdownMenuBox 或 Dialog 均可」取了 **Dialog**（取舍依据见 RECON-P2G §4.2，不是回避难度：
  material3 1.3→1.5 之间 `menuAnchor()`/`ExposedDropdownMenu` 有签名漂移，而编译机 gradle 缓存里同时躺着
  1.4.0 与 1.5.0-alpha22 两档；`AlertDialog` 这条路本仓已在 Robolectric 上实证过）。
- §7 验证矩阵六行：**五行全绿**（store 单测 17≥8、settings UI 8≥6、全模块 build、badging 3/0.1.2、冻结区 diff 空）。
  第六行「真机配 key → 新对话有回复」规格书自己标的是「方向层验收，**用户执行**」——
  实测两侧都没有设备（编译机 `adb devices` 列表为空、本机 `adb: command not found`），见 §8 第 8 条。
- 用例总数 **251**（编译机 214 + 本机 37），**0 红 0 跳过**；本轮新增 **30** 条
  （engine 17 + settings 13）。P2F 基线 221 + 30 = 251，逐模块相加也对得上。

## 2. 交付物落点（要求 → 代码 → 证据）

### 2.1 §4.1 `core:engine` ModelCredentialStore

| 规格书要求 | 落点 | 证据（用例号见 §2.1 末） |
|---|---|---|
| 类形状逐字照抄：`class ModelCredentialStore(private val homeDir: File)` | `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/settings/ModelCredentialStore.kt:43` | 全部 17 条都是 `ModelCredentialStore(tmp.newFolder())` 直构 |
| `authFile = File(File(homeDir,".pi"),"agent/auth.json")` | `:46`（三段拼法与 pi `config.ts:605-611`/`:623-625` 的 `join()` 逐段对齐） | 02 |
| `ProviderEntry(providerId, keyConfigured)` **不持 key 明文** | `:56` | 13（`declaredFields == 2` + 三种「未配置」形态） |
| `readConfigured()` | `:65-68`（非对象条目跳过、`type=="api_key" && key 非空白` 才算已配置，判据来自 `ai/src/auth/types.ts:17-21` 里 `key?` 是**可选**字段） | 13 / 17 / 08 / 09 |
| `save()` 读-改-写 + 原子 rename | `:76-87`（`buildJsonObject { 旧键全铺一遍; put(providerId, apiKeyCredential) }` → `writeAtomic`） | 01 / 03 / 04 / 14 |
| blank apiKey → `IllegalArgumentException` | `:77-78` 两条 `require` | 12（含 `clear` 的空 id） |
| 坏 JSON / 缺文件 → 空对象继续写 | `:124-128` `readObject` | 07 / 08 / 09 / 17 |
| 自动建父目录 | `:113-116` `ensureDirectory`（逐级 mkdirs，失败才 IOException） | 10 / 11 |
| 同目录 tmp + `renameTo`，不留残骸 | `:138-149` `writeAtomic`（tmp 名 `auth.json.tmp` + `System.nanoTime()`；rename 失败先删目标再 rename；`finally` 清 tmp） | 01（目录里只允许 auth.json 一个文件）/ 11（失败路径也不留半个文件） |
| `clear()` 只删目标、保留他人 | `:98-108` | 05 / 15；06 是**超出规格书**补的幂等语义（§8 第 6 条） |
| kotlinx `JsonObject`、宽松解析 | `:164` `Json { ignoreUnknownKeys = true }`（与 `core/rpc`、`AndroidRuntimeInfo` 同一套） | 14 / 15 |
| 10 项固定清单（§3 顺序） | `:174-185` `KNOWN_PROVIDERS`；展示名回落 `:188` `displayNameOf`（清单外 id 原样回显，不编造） | 16 |
| EngineModule `@Provides @Singleton` + homeDir 走 PathLayout | `core/engine/.../di/EngineModule.kt:117-120`（`PathLayout.homeDir(ctx)`，RECON-P2G §1 确认访问器现成） | §7 的 `:core:engine` 85 / `:app` 17 全绿即建图通过 |

用例 17 条：01 精确形状 + 零残留、02 路径、03 两 provider 共存、04 重复 save 更新、05 clear 只删目标、
06 clear 幂等、07 损坏文件被覆盖成合法对象、08 顶层非对象、09 空文件、10 自动建 `.pi/agent` 全链、
11 目录链被普通文件挡住 → `IOException` 且不落半个文件、12 blank 参数拒绝且不碰文件、
13 不回显 key + 已配置判据、14 读-改-写保留他人条目与未知字段、15 clear 保留未知顶层键、
16 清单十项逐字、17 缺文件读为空。门槛是 ≥8，实到 **17**。

### 2.2 §4.2 `feature:settings` 模型接入 UI

| 规格书要求 | 落点 | 证据 |
|---|---|---|
| 新 Section「模型接入」 | `ui/SettingsScreen.kt:217-305`，挂在 `ModelSection` 之后、`ProbeCard` 之前（`:107-115` + `HorizontalDivider()`） | g07 |
| provider 下拉（10 项固定清单） | 展示按钮 `:230-238` + `CredentialPickerDialog` `:309-336`（`AlertDialog` 内 `verticalScroll` 的 10 个 TextButton，tag 用 providerId） | g08（10 项逐个 `assertIsDisplayed` + 清单长度钉死）/ g08b（取消不改选中） |
| key 输入框 `PasswordVisualTransformation` | `:239-246`，变换在 `:244` | g09 `:353` 断 13 个圆点、g12 `:424` 断 4 个圆点——**§4 变异检查证明这两条真能咬人** |
| 保存按钮 | `:249-253`，`enabled = draft.isNotBlank()` | g07（空 key 时禁用）/ g09 |
| 已配置列表（每行 providerId + 清除按钮） | `:260-293`（行内只有展示名 + `已配置/无可用密钥` + 清除；`ProviderEntry` 里没有 key 字段，所以**连尝试回显的入口都没有**） | g11 / g13（三行种子：2 个「已配置」+ 1 个「无可用密钥」用 `assertCountEquals` 钉数、清单外 id 原样显示、三个清除按钮一个不漏） |
| VM `credentials: StateFlow<List<ProviderEntry>>` | `SettingsViewModel.kt:103/:109` | t13（进屏读一次）/ t17 |
| VM `saveCredential` / `clearCredential`（runCatching，失败进 notice） | `:148-167` / `:170-184` | t14 / t16 / g09 / g11 / g12 |
| 写后刷新 + 清空输入 + notice 原文 | `:155-162`（`snapshot()` 成功后才 `_credentialDraft.value = ""`）；文案 `ui/SettingsTags.kt:133` `已保存，新会话生效（对话进行中请停止后重开）` | t14 逐字比字符串、g09 逐字断言同一条 |
| 失败不吞草稿 | `:164` 走 `credentialWriteFailed(reasonOf(t))`，不碰 `_credentialDraft` | t16（VM 面）/ g12（UI 面：`assertKeyValue("sk-x")`） |
| 新 tag 前缀 `SCREEN+"-credential"` 系列 | `ui/SettingsTags.kt:33-39` 常量 + `:45-52` 派生函数 + `:58-61` `init{}` 前缀自检 | 自检跑不到就直接 `ExceptionInInitializerError` |
| 新中文 strings 进 SettingsStrings | 同文件 `:112-139`（`SECTION_CREDENTIAL`/`CREDENTIAL_*`/`credentialCleared()`；**注意**：`SettingsStrings` 与 `SettingsTags` 在同一个 `.kt` 文件里，见 §8 第 3 条） | g07–g13 全部按常量引用 |
| UI 测试注入 fake store，≥6 case | `src/test/.../Fakes.kt` 新增 `FakeModelCredentialSource` | 实到 **8** 条（g07、g08、g08b、g09、g10、g11、g12、g13）+ VM 面 5 条（t13–t17） |

「空 key 保存不触发写入」做成**三道门**：UI 按钮禁用（`SettingsScreen.kt:251`）→ VM 拦截
（`SettingsViewModel.kt:149-152`，给 `CREDENTIAL_BLANK_KEY`）→ store `require`（`ModelCredentialStore.kt:77-78`）。
t15 同时钉中间那道（`saves` 必须为空），g07 钉最外面那道（按钮 `assertIsNotEnabled`）。

### 2.3 §4.3 `app` 版本

- `app/build.gradle.kts:19-20`：`versionCode = 3` / `versionName = "0.1.2"`。
- 护栏：`app/src/test/kotlin/works/earendil/pihost/ManifestPermissionTest.kt:77-88` 用例 03
  期望值同步改成 `3L` / `"0.1.2"`，并**单列一条单调性断言**（`longVersionCode > 2L`）——
  写死 3L 已隐含这件事，但单调性那条的失败信息会直接说「必须严格高于上一版」，
  排查 `adb install -r` 拒装时少绕一步。装箱陷阱（`Integer(3) != Long(3)` 会假红）保留在原注释里。

## 3. 改动清单（P2G 独占，`git diff --name-status 24b55be..0067b26` = **15 个路径：4 新增 / 11 修改**）

> 计数区间钉在 `24b55be..0067b26`（= 本报告之前的最后一个提交，15 个路径全是代码 + RECON）：
> 报告自身落盘会让路径数变 16、行数随排版浮动，写成 `HEAD` 就是一条「提交即过期」的引用。
> P2F 就是踩在这里 —— 它先按 `HEAD` 计数，随后专门补了一条 `24b55be`「路径计数对齐 HEAD（19 路径），
> shortstat 基准固定在 99980f1」；本轮从一开始就把基准写死在代码提交上，不再需要这类补救。

| 状态 | 路径 | `+增/−删`（`git diff --numstat 24b55be..0067b26`） |
|---|---|---|
| A | `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/settings/ModelCredentialStore.kt` | 207/0 |
| M | `core/engine/src/main/kotlin/works/earendil/pihost/core/engine/di/EngineModule.kt` | 17/0 |
| A | `core/engine/src/test/kotlin/works/earendil/pihost/core/engine/settings/ModelCredentialStoreTest.kt` | 346/0 |
| A | `feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/ModelCredentials.kt` | 57/0 |
| M | `feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/SettingsViewModel.kt` | 95/0 |
| M | `feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/di/SettingsModule.kt` | 11/0 |
| M | `feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/ui/SettingsScreen.kt` | 156/1 |
| M | `feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/ui/SettingsTags.kt` | 58/0 |
| M | `feature/settings/src/test/kotlin/works/earendil/pihost/feature/settings/Fakes.kt` | 45/0 |
| M | `feature/settings/src/test/kotlin/works/earendil/pihost/feature/settings/SettingsViewModelTest.kt` | 97/2 |
| M | `feature/settings/src/test/kotlin/works/earendil/pihost/feature/settings/ui/SettingsScreenTest.kt` | 246/4 |
| M | `app/src/test/kotlin/works/earendil/pihost/AppTestDoubles.kt` | 20/0 |
| M | `app/src/test/kotlin/works/earendil/pihost/ManifestPermissionTest.kt` | 9/6 |
| M | `app/build.gradle.kts` | 2/2 |
| A | `docs/RECON-P2G.md` | 133/0 |

合计 `15 files changed, 1499 insertions(+), 15 deletions(-)`（`git diff --shortstat 24b55be..0067b26`）。
复原前（`git diff --shortstat 24b55be 3d86747`）是 `1500/17`，差的 1 增 / 2 删全在 `Fakes.kt`
（`46/2` → `45/0`），原因就是 §8 第 10 条那条换行事故。
这 15 行删除逐条核过（`git diff 24b55be..0067b26 -- <file> | grep '^-' | grep -v '^---'`，
分文件计数 2 / 6 / 1 / 2 / 4）：**没有一条是公开 API 签名**。
- `app/build.gradle.kts` 2 行：`versionCode = 2` / `versionName = "0.1.1"`（§4.3 要求的就是这件事）。
- `ManifestPermissionTest.kt` 6 行：用例 03 的分节注释、`fun` 名里的旧版本、两行装箱陷阱注释、
  两条旧期望值 `2L` / `"0.1.1"`（§2.3）。
- 主源码仅 1 行：`SettingsScreen.kt` 顶部 KDoc 的屏描述——原文
  「memTier 三选 + 并行上限 stepper + `get_state` 探针卡片 + 版本卡片」，改写后同一行换成
  「… + 默认模型只读 + **模型接入（P2G §4.2）**」，纯注释，不涉及任何签名。
- `SettingsViewModelTest.kt` 2 行 + `SettingsScreenTest.kt` 4 行：三参 `SettingsViewModel(repository, engine, info)`
  的构造调用与 `Rig(...)` 返回行（为第 4 个注入参数 `credentialStore` 重写），以及 ScreenTest 顶部两行 KDoc
  （现文为「§4.2 的四个部件 + P2G §4.2 的『模型接入』」`:45`、「四个依赖都是假体」`:50`）。
新增主源码 **264 行**（`wc -l` 实测：`ModelCredentialStore.kt` 207 + `ModelCredentials.kt` 57，含 KDoc）。
全部落在 `core/engine`、`feature/settings`、`app`、`docs` 四个允许区。

## 4. 变异检查（证明「不回显密钥」的护栏不是纸糊的）

规格书 §4.2 点名 `PasswordVisualTransformation`，§2 红线又要求「UI 永不回显已存 key」。
先记录三条**编译机实测**的语义树事实（第一次跑 g09/g12 就是撞在上面才查出来的）：

| 属性 | 密钥输入框上的实际值 | 含义 |
|---|---|---|
| `SemanticsProperties.Text` | `[API 密钥]` | 只有 label——`assertTextEquals` 比的是 `Text + EditableText`，所以它断不了「清空」也断不了「草稿还在」，只会给你 `[API 密钥, ••••]` |
| `SemanticsProperties.EditableText` | `••••`（草稿 `sk-x` 时） | **变换后的显示形态**，屏幕上看得见的就是它 |
| `SemanticsProperties.InputText` | `sk-x` | 真值只住这一个属性 |

护栏因此分三写法（`SettingsScreenTest.kt:71-125`）：`inputTexts()` 读 `InputText` 并**用列表相等**同时钉住
「此刻真值」与「全树只有输入框这一个明文持有者」（多一个持有者 = 多一处回显）；
`keyFieldDisplay()` 读 `EditableText` 钉圆点；`assertKeyNotEchoed()` 只扫 `Text`
——`onNodeWithText(substring = true)` 的匹配面**含 `InputText`**，草稿存在时拿它断「无回显」会假红。

变异操作：把 `SettingsScreen.kt:244` 的 `visualTransformation = PasswordVisualTransformation(),` 整行删掉，
只跑 `:feature:settings`。**实测结果**（编译机 XML 里的原文）：

```
SettingsScreenTest > g09 ... FAILED
org.junit.ComparisonFailure: expected:<[•••••••••••••]> but was:<[sk-live-value]>
    at ...SettingsScreenTest.g09 ...(SettingsScreenTest.kt:353)
SettingsScreenTest > g12 ... FAILED
org.junit.ComparisonFailure: expected:<[••••]> but was:<[sk-x]>
    at ...SettingsScreenTest.g12 ...(SettingsScreenTest.kt:424)
35 tests completed, 2 failed
```

- 两条红都在**新加的那一行**上，其余 33 条不误伤 → 断言精准命中变换本身。
- 探针跑完 `git restore` 复原实现，再跑一次：`BUILD SUCCESSFUL`、35 绿（VM 21 / Screen 14）。
- 复原后 `git diff --stat 7d8d9e6..HEAD -- '*/src/main/*' app/build.gradle.kts` → **空**，
  即 APK 的输入源码与最终 HEAD 逐字节一致（§7.3 的 APK 因此不用重打包）。
- 为什么不再补一条「列表行显示 key」的变异：那要往 `ProviderEntry` 加 key 字段（§4.1 明令不得持有），
  加害代码本身就不合法。现在的形状是**类型层面拿不到 key**，比断言更强。

## 5. 红线复核（§2 逐条，全部实测）

- **pi 源码零修改**：两条独立证据。① 本仓 15 个改动路径（§3 表）里没有任何 pi 源码路径，pi 1.0.4 在仓外
  `/root/pi/.tools/pi-1.0.4/`；② **正证**：把原始包 `/root/pi/.tools/pi-v1.0.4.tar.gz` 解到 `/tmp/pi-verify/pi-1.0.4`，
  与工作树做 `diff -rq /tmp/pi-verify/pi-1.0.4 /root/pi/.tools/pi-1.0.4` → **rc=0、输出 0 行**
  （28M 全树逐文件内容比对，含 RECON §1 引用的 `packages/coding-agent/src/config.ts` 等）。
  口径说清：`diff -rq` 比的是**文件集合与内容**，不比 mtime/权限位，所以它是「内容没被改过」的证明，
  不是「一个字节没被 touch 过」的证明。
- **冻结区不动**：
  `git diff --stat 24b55be..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging app/src/main/jniLibs gradle/libs.versions.toml`
  → **无输出**。同一集合再按 `2f78e20^..HEAD`、`99980f1..HEAD` 各复核一次，同样空。
  **规格书 §7 那一行的字面判据是 `1f77127..HEAD`**：按 §2 的六个冻结路径
  （不含 `app/src/main/jniLibs`/`libs.versions.toml`）实测
  `git diff --stat 1f77127..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md tools/capture tools/runtime-packaging`
  → **无输出**，且 `git diff --quiet` 同集合返回 0 —— 冻结面自 P2C 的那条基线起就没被动过，不止本轮。
  **EnvFactory 11 键**：`EnvFactory.kt` 没出现在改动清单里（冻结区 diff 已含它），
  `grep -c 'put(' ` 实测仍是 11。
  > 纠偏 P2F §5 的一条口径：`git diff --stat 1f77127..HEAD -- … gradle/libs.versions.toml` 会显示
  > `15 insertions(+)`，那是 **P2E** 加 markwon/datastore 的遗留，不是本轮也不是冻结路径
  > （规格书 §2 的冻结清单里没有 `libs.versions.toml`）。按 P2G 自己的 base（`24b55be`）测才是空的。
- **凭据只落 auth.json**：
  - 不进 DataStore：`core/engine/src/main/kotlin/works/earendil/pihost/core/engine/settings/SettingsRepository.kt:89-91`
    仍是 `mem_tier`/`max_parallel_sessions`/`default_model` 三键，该文件本轮未改（不在 §3 的 15 个路径里）；
  - 不进日志：本轮改到的 **7 个主源码文件**（`git diff --name-only 24b55be..HEAD | grep src/main`）里
    `Log.` / `println` / `System.out` **逐文件 `grep -c` 全为 0**；失败 notice 只带 `reasonOf(t)` = 异常 message
    （例如「无法创建目录：…」，是路径不是密钥）；
  - 不进 env：`grep -rni "auth" core/environment/src/main` → **0 命中**（`EnvFactory.kt` 的 `put(` 仍是 11 个，冻结区 diff 已含它）；
  - 不进提交/测试快照：用例里只出现合成串 `sk-x` / `sk-live-value` / `sk-do-not-echo`；`ProviderEntry` 两字段（用例 13 钉 `declaredFields == 2`）；`FakeModelCredentialSource.rows` 只存 `(providerId, keyConfigured)`。
    一处如实说明：该假体同时把 `(providerId, apiKey)` 记进 `saves` 用于「参数确实传到写入面」的断言（g09 逐字比对），
    这是**内存里的入参捕获**，值是用例自造的假串，不落盘、不写日志、不构成凭据快照。
- **原子 + 读-改-写 + 保留未知字段**：`core/engine/.../settings/ModelCredentialStore.kt:138-149` `writeAtomic`（同目录 tmp + `renameTo`，失败退化为「删目标再 rename」，`finally` 清 tmp）；
  保留性由用例 01（零残留）/ 14（他人条目与未知字段都在）/ 15（clear 后未知顶层键仍在）/ 11（失败不留半个文件）钉。
- **UI 永不回显已存 key**：类型层无 key 字段 + g10 全树明文扫描 + g09/g12 圆点断言（§4 变异实测）。
- **`feature/chat` 不改**：`git diff --name-only 24b55be..HEAD -- feature/chat` → **无输出**。
- **不新增第三方依赖**：`gradle/libs.versions.toml` 未改（上面已证）；`kotlinx-serialization-json` 是 `core/engine/build.gradle.kts:48` 既有的 `implementation`，
  `ModelCredentialStore` 的公开签名是纯 JVM 类型，没为它升 `api`。
- **版本必须升**：3/0.1.2，见 §7.2 的 `aapt2 dump badging` 实录。
- **提交纪律**：七次提交全部显式路径 `git add`（无 `git add -A`/`.`），一步测绿才进下一步，顺序同规格书 §6。

## 6. 本机（dshbox）冻结模块基线

命令：`JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle --no-daemon
:core:process:cleanTest :core:process:test :core:rpc:cleanTest :core:rpc:test --no-build-cache`
→ `BUILD SUCCESSFUL in 25s`（11 actionable tasks: 4 executed, 7 up-to-date）。

| 模块 | 测试类 | tests | fail | err | skip |
|---|---|---|---|---|---|
| `:core:process:test` | JvmProcessTest | 3 | 0 | 0 | 0 |
| `:core:rpc:test` | GoldenReplayTest | 9 | 0 | 0 | 0 |
| | LineFramerTest | 9 | 0 | 0 | 0 |
| | PiSessionTest | 8 | 0 | 0 | 0 |
| | RpcModelTest | 7 | 0 | 0 | 0 |
| | PiDesktopE2eTest | 1 | 0 | 0 | 0 |
| **合计** | | **37** | 0 | 0 | 0 |

**37 基线不回归**成立（3+9+9+8+7+1 = 37）。`PiDesktopE2eTest` 与 P2F 同况：本机抓包环境齐备，
门（`core/rpc/src/test/.../PiDesktopE2eTest.kt:46` 的 `assumeTrue(environmentReady())`）没坏也没被绕，
这一条本轮仍是**真跑而非 skip**（`skipped=0`）。
上表是本轮早先那次跑的输出；`0067b26`（本报告之前的最后一个提交）定稿后本机又原样重跑了一次，
`tally` 结果逐类相同：process 3 + rpc 34 = 37，fail/err/skip 全 0，
所以这两个数字对得上报告落盘时的真实 HEAD。

## 7. 编译机矩阵（规格书 §7）+ assembleDebug + APK 双端落盘

矩阵脚本 `/root/matrix.sh`（P2E/P2F 那份原样复用：逐模块**一次 gradle 调用**、`--no-build-cache`、
每模块先 `cleanTestDebugUnitTest`、模块间 `pkill KotlinCompileDaemon`、打包前 `rm` 旧 APK 逼 `packageDebug` 真跑）。
**7 步全部 `RC=0`**、`MATRIX-DONE`；逐步 `BUILD SUCCESSFUL`：37s / 1m9s / 1m10s / 54s / 53s / 1m / 41s。
同步点：矩阵跑的是 `7d8d9e6`（版本提交之后、遮罩断言之前）；`settings` 模块随后单独重跑三次
（变异一次红 / 复原一次绿 / `0067b26` 换行复原一次绿，逐次 tally 见 §4 与 §8 第 5 条），计数恒 35。

| 模块 | 测试类（tests/fail/err） | 合计 | 门槛 | 判定 |
|---|---|---|---|---|
| `:core:environment` | EnvFactoryTest 2 / PathLayoutTest 3 | **5** / 0 / 0 | 5 | ✓ |
| `:core:engine` | AssetInstallerTest 5 / ChatEngineAdapterTest 6 / DataStoreProbeTest 4 / ExecFallbackStarterTest 9 / PiEngineServiceTest 7 / PiSpawnTest 4 / RealRuntimeAssetsInstallTest 2 / RuntimeInstallTest 5 / SessionSupervisorTest 14 / SpawnDiagnosticsSinkTest 6 / **ModelCredentialStoreTest 17** / SettingsRepositoryTest 6 | **85** / 0 / 0 | ≥57 | ✓（68 基线 + 新增 17） |
| `:feature:chat` | ChatViewModelTest 23 / ChatScreenUiTest 6 / ComposeProbeTest 3 / HiltComposeProbeTest 1 / HiltProbeTest 1 / InstallGateFailedTest 5 / MarkwonRenderTest 4 / MarkwonSmokeTest 4 | **47** / 0 / 0 | 47 | ✓（一条没动，§5 红线） |
| `:feature:sessions` | SessionScannerTest 10 / SessionsViewModelTest 12 / SessionsScreenTest 3 | **25** / 0 / 0 | 25 | ✓ |
| `:feature:settings` | SettingsViewModelTest 21 / SettingsScreenTest 14 | **35** / 0 / 0 | ≥28 | ✓（22 基线 + 新增 13） |
| `:app` | MainActivityTest 11 / ManifestPermissionTest 3 / PiEngineServiceWiringTest 3 | **17** / 0 / 0 | 17 | ✓（版本期望值已随 §2.3 改） |
| **编译机合计** | | **214**，fail 0 / err 0 / skip 0 | | 全绿零红 |

跑完后编译机资源实录（§8 要求磁盘 ≥4G）：`/dev/vda1 49G 44G **4.6G** 91%`，`Mem available **3437M**`。
整轮没有出现 OOM kill（`dmesg` 里最后一条 `Killed process` 的时间戳早于本轮开工）。

### 7.1 测试副作用（如实记录，已清理）

`SessionSupervisorTest` 的 P2F 修复（spawn 前 `mkdirs()`）在 root 编译机上仍会把
`/data/user/0/works.earendil.pihost/files/projects/default` 真建出来。本轮矩阵跑到 `:core:engine`（85 条）后，
`find` 实测四条路径全在（`works.earendil.pihost` / `files` / `files/projects` / `files/projects/default`）。
这与 P2F §7.1 同源，**不是本轮新引入的行为**；已按 `rm -rf /data/user/0/works.earendil.pihost` 清理，
复核输出 `CLEANED`。

本轮新增的 `ModelCredentialStoreTest` 17 条**全部跑在 `TemporaryFolder` 派生目录里**，
不碰任何设备路径常量（照 P2F §8 第 5 条那条纪律），所以凭据这面没有新增越界写入。
`0067b26` 那次 `:feature:settings` 重跑之后再查一遍（该模块不含 `SessionSupervisorTest`）：
`find /data/user/0/works.earendil.pihost -maxdepth 3` → **无任何输出**，即目录没有随本轮重跑复活。

### 7.2 新 APK 体检

`aapt2 dump badging`（编译机 build-tools 36.0.0）实测：

```
package: name='works.earendil.pihost' versionCode='3' versionName='0.1.2' compileSdkVersion='36'
minSdkVersion:'26'  targetSdkVersion:'35'  native-code: 'arm64-v8a'
uses-permission: FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / INTERNET
uses-permission: works.earendil.pihost.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION（aapt 自动生成，非本轮引入）
```

对照 P2F 包：`versionCode 2 / 0.1.1` → **3 / 0.1.2**；权限条数不变（INTERNET 是 P2F 加的，本轮只动版本，
§2.3 的用例 01/02 仍绿即证明没把旧的四条覆盖掉）。

### 7.3 双端落盘

| 落点 | 尺寸 | sha256 |
|---|---|---|
| 编译机 `/root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk` | 63,787,581 B | `05268cf3d2ea44488cf8693bcf477b88597f5a86088b5558dc33c906abf85f75` |
| 本机 `/root/pi/dist/pi-host-full-debug.apk` | 63,787,581 B | `05268cf3d2ea44488cf8693bcf477b88597f5a86088b5558dc33c906abf85f75` |

两处 sha256 **逐字相等**，下载 URL 不变（同名覆盖）。对比 P2F 包：63,761,093 B → 63,787,581 B =
**+26,488 B**（`ModelCredentialStore` 207 行 + `ModelCredentials` 57 行 + Section/Dialog/文案常量 + 一次 dex 增长抖动）。
哈希分段复核：`len=64`，三段 `05268cf3d2ea44488cf869` / `3bcf477b88597f5a86088b` / `5558dc33c906abf85f75`，
不是抄来的单点回显。
`0067b26`（换行复原，只动 `src/test`）之后两侧各重测一遍 `stat -c %s` + `sha256sum`，
尺寸与哈希**逐字仍是上表这两个值** → 该提交没有改变 APK 输入，无需重打包（同一结论的另一半见 §4
那条 `git diff 7d8d9e6..HEAD -- '*/src/main/*' app/build.gradle.kts` 为空的实测）。

## 8. 偏差、遗留与未执行

1. **多了一层 `ModelCredentialSource` 缝合（规格书没写，必须写）**：`feature/settings/.../ModelCredentials.kt:24-41`
   是消费者侧 suspend 接口，`:43-56` 的 `FileModelCredentialSource` 包 `ModelCredentialStore` 并自己 `withContext(Dispatchers.IO)`。
   理由：规格书 §4.1 给的三个方法是**阻塞**的，而 §4.2 的 VM 跑在 `viewModelScope`（主线程）。
   本仓既有纪律（`RuntimeInfo.kt` 的「实现方自己负责挪出主线程，VM 侧不做调度决定」）要求 hop 关在实现里，
   否则 Robolectric 用例得等真线程。**规格书 §4.1 的类形状一字未改**；「注入 fake store」正好落在这个接口的假体上。
   绑定在 `di/SettingsModule.kt:39-41`（照 `runtimeInfoSource` 那条式）。
2. **provider 选择用 `AlertDialog` 不用 `ExposedDropdownMenuBox`**：§4.2 两者都允许，取舍见 RECON-P2G §4.2。
3. **RECON-P2G §3 的一处引用纠偏**：侦察时把文案表写成 `ui/SettingsStrings.kt:42`，实际 `SettingsStrings`
   与 `SettingsTags` 同文件（`ui/SettingsTags.kt:67` 起），本仓没有独立的 `SettingsStrings.kt`。
   规格书 §4.2 说的「新中文 strings 进 SettingsStrings」按**对象**满足，落点是 `:112-139`。
4. **`:app` 测试图补了一条提供者**（`AppTestDoubles.kt:108-111`）：三条 app 测试都 `@UninstallModules(EngineModule::class)`，
   `SettingsViewModel` 多一条注入边就必须补一条边，否则 Hilt 建图阶段直接失败（RECON-P2G §2 预警过）。
   给的是**真类 + Robolectric 沙箱 `filesDir`**，所以写的是每沙箱唯一的临时目录，不碰设备路径。
5. **settings 模块被跑了三轮**：全量矩阵里 35 条（HEAD=`7d8d9e6`），随后为遮罩断言（`3d86747`：16 行 =
   `keyFieldDisplay()` 助手 + g09/g12 各一条圆点断言）单独重跑两次：变异一次红（35 tests / 2 failed）、复原一次绿（35 绿）；`0067b26` 的排版复原再跑一次，
   `python3 /root/tally.py` 实测 `tests=35 failures=0 errors=0 skipped=0`（VM 21 / Screen 14）。
   其余五个模块的数字来自同一次全量矩阵，
   且 `git diff 7d8d9e6..HEAD -- '*/src/main/*' app/build.gradle.kts` 为空 → 主源码与 APK 输入完全对得上
   （`0067b26` 只动 `src/test`，所以 §7.3 的 APK 不必重打包，实测该 diff 无输出）。
6. **`clear()` 的幂等语义是补的**（用例 06：文件或条目不存在时静默返回、不建文件）。规格书只写「clear(providerId)」，
   没定不存在时的行为；按「撤销通路不该因为重复点而炸」处理。
7. **遗留观测项（本机与编译机都验不了）**：`auth.json` 的**文件模式**。pi 自己 `ensureFileExists` 时是 0600（方向层实测），
   app 侧走 `FileOutputStream` + 应用私有目录 umask，单测没有断 mode（`TemporaryFolder` 的 umask 与 Android 进程不同，断它没有意义）。
   真机上一条命令即可观测：`adb shell run-as works.earendil.pihost stat -c '%a %n' files/home/.pi/agent/auth.json`。
8. **未执行（必须真机才能做的验收，规格书 §7 最后一行标的就是「用户执行」）**：
   两侧都无设备——编译机 `/root/android-sdk/platform-tools/adb devices` 实测 `List of devices attached` 下为空，
   本机 `adb: command not found`（没装 platform-tools）。有设备的机器上：
   `adb install -r pi-host-full-debug.apk`（或真机浏览器直接装 `/root/apk-serve/2dce9c4ca97c/` 那个不变 URL；
   versionCode 3 > 2 允许覆盖安装）→ 设置页「模型接入」选提供方 + 填 key + 保存
   → 出现「已保存，新会话生效（对话进行中请停止后重开）」→ **开新会话**发「你好」→ assistant 有回复。
   预期链路（方向层已实证，不在本轮重做）：pi spawn 时同步读 `auth.json`，
   `credential.key` 非空即进 `configuredProviders`，`findInitialModel` 按 `defaultModelPerProvider` 选默认模型
   （deepseek→deepseek-v4-pro、anthropic→claude-opus-4-8、openai→gpt-5.5）。
   已运行会话不吃新凭据（§9 定案），所以必须**新会话**。
9. **按 §9 定案本轮不做的三件事**：不写 `settings.json`（单 provider 自动选已够）；不做运行中会话热加载
   （notice 明示即可，且 `feature/chat` 是禁改区）；不做默认模型切换（`set_model` RPC 属 P2H）。
10. **本轮自己制造又修掉的一处排版缺陷（如实记录）**：`840491c` 往 `Fakes.kt` 插入 `FakeModelCredentialSource`
    时，把 `probeResponse` 上方注释行尾的换行一起吞了——KDoc 与 `fun` 挤在同一行
    （`...语义撑出来）。 */fun probeResponse(id: String?, ...)`）。Kotlin 的词法分析把 `*/` 当分隔，
    所以它**能编译、能跑绿**，35 条用例全程没有感知；但那是 Edit 工具替换范围多带了一个换行的事故，
    收尾自查揪了出来：`git grep -n -E '\*/[^ ]' 3d86747 -- '*.kt'` 只有两条命中，一条是
    `feature/chat/.../PiGrammarLocator.kt:89` 的正则字面量（本意如此，不是缺陷），另一条就是这里；
    复原后同类命中只剩正则那条。已由 `0067b26` 拆回两行（签名与默认参数一字未动）并重跑该模块绿。
    这条也解释了 §3 里 `Fakes.kt` 的 `45/0`：复原前是 `46/2`，那 2 行删除本就是把 KDoc 与签名重抄一遍造成的。

## 9. 复现命令（逐条可直接跑）

```bash
# —— 编译机（64.90.11.50，密钥 pihost-builder）——
bash /root/pi/.tools/bbuild.sh sync                      # 本机 → /root/android-host（含 .git，排掉 .gradle/.kotlin）
bash /root/pi/.tools/bbuild.sh gradle ':core:engine:cleanTestDebugUnitTest :core:engine:testDebugUnitTest \
  --no-build-cache -Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m'   # 换模块名即可逐模块跑
bash /root/pi/.tools/bbuild.sh raw 'bash /root/matrix.sh > /tmp/matrix.log 2>&1; cat /tmp/matrix.sum'   # 全量矩阵+打包
bash /root/pi/.tools/bbuild.sh raw 'python3 /root/tally.py /root/android-host/core/engine/build/test-results/testDebugUnitTest'
bash /root/pi/.tools/bbuild.sh raw 'aapt2=/root/android-sdk/build-tools/36.0.0/aapt2; $aapt2 dump badging /root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk | grep -i "^package:\|^uses-permission"'
bash /root/pi/.tools/bssh.sh get /root/apk-serve/2dce9c4ca97c/pi-host-full-debug.apk /root/pi/dist/pi-host-full-debug.apk

# —— 本机（dshbox，只跑冻结 JVM 模块）——
cd /root/pi/android-host && JAVA_HOME=/root/pi/.tools/jdk-21.0.12.1+1 /root/pi/.tools/gradle-8.13/bin/gradle \
  --no-daemon :core:process:cleanTest :core:process:test :core:rpc:cleanTest :core:rpc:test --no-build-cache

# —— 红线复核 ——
tar -xzf /root/pi/.tools/pi-v1.0.4.tar.gz -C /tmp/pi-verify            # 期望目录：/tmp/pi-verify/pi-1.0.4
diff -rq /tmp/pi-verify/pi-1.0.4 /root/pi/.tools/pi-1.0.4; echo "rc=$?" # 期望：rc=0 且无输出（§5 的正证）
git diff --stat 24b55be..HEAD -- core/rpc core/process/src/main core/environment docs/protocol.md \
  tools/capture tools/runtime-packaging app/src/main/jniLibs gradle/libs.versions.toml   # 期望：无输出
git diff --name-only 24b55be..HEAD -- feature/chat                                       # 期望：无输出

# —— 变异检查（§4）：删掉 SettingsScreen.kt:244 的 visualTransformation 一行 ——
bash /root/pi/.tools/bbuild.sh gradle ':feature:settings:cleanTestDebugUnitTest :feature:settings:testDebugUnitTest \
  --no-build-cache -Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m'
# 期望：g09（SettingsScreenTest.kt:353）与 g12（:424）双红，expected:<••••> but was:<明文>
git restore feature/settings/src/main/kotlin/works/earendil/pihost/feature/settings/ui/SettingsScreen.kt  # 探针后复原
```

> 注：`-Dorg.gradle.jvmargs=-Xmx1100m -Dkotlin.daemon.jvmargs=-Xmx700m` 是 P2E 实证过的限额串；
> `bbuild.sh gradle` 内部已带 `cd $REPO`，任务串**只填 gradle 参数**。
> `bbuild.sh` 的 ssh 包装有重试，长跑任务请**只看编译机上的 `/tmp/matrix.sum` 与 `MATRIX-DONE`**，
> 本地包装退出码不代表远端跑完（本轮就出现过本地先退、远端仍在跑第 2 次的情况，靠 `ps -etimes` 判定）。

## 10. 计数汇总

- **改动**：15 个路径（新增 4 / 修改 11），`+1499 / −15`（`git diff --shortstat 24b55be..0067b26`，
  基准钉在报告之前的最后一个提交，理由见 §3 顶部那条注），
  全部落在 `core/engine`、`feature/settings`、`app`、`docs` 四个允许区；新增主源码 264 行。
  **未删除任何既有公共函数或公共签名**：`git diff 24b55be..0067b26 -- core/engine/src/main feature/settings/src/main`
  里 `−` 只有 1 行（`SettingsScreen.kt` 顶部 KDoc 的屏描述，改写后同时列出「模型接入」），
  其余 14 行是版本号、测试期望值与测试侧构造调用（逐条拆分见 §3）。
- **测试**：编译机 **214** 全绿（environment 5 / engine 85 / chat 47 / sessions 25 / settings 35 / app 17），
  本机 **37** 全绿（process 3 + rpc 34，含真跑的 e2e 1）→ **合计 251 用例，0 红 0 跳过**；
  本轮新增 **30** 条（engine 17；settings 13 = VM t13–t17 五条 + UI g07、g08、g08b、g09、g10、g11、g12、g13 八条）。
  与 P2F 的 221（184+37）对得上：184 + 30 = 214，加本机 37 = 251。
- **提交**：`2f78e20` → `9e2d267` → `840491c` → `7d8d9e6` → `3d86747` → `0067b26` → 本报告（七条 + 本条）。
  规格书 §6 的提交链是 RECON/store/settings/version/REPORT 五段，`3d86747`（遮罩断言）与 `0067b26`（换行复原）
  都是 settings 段的补强，各自单独成一条、不并入 REPORT，以免把测试代码混进文档提交；
  两条都带自己的用例计数标记（35 绿），复原那次还在编译机重跑并 `tally.py` 复核过（§8 第 5 条）。
- **产物**：APK 63,787,581 B / `05268cf3…abf85f75`，双端逐字相等；URL 不变。
- **门槛对照**：store 17（门槛 8）、settings UI 8（门槛 6）、build SUCCESSFUL、badging 3/0.1.2、冻结区 diff 空
  → §7 六行里五行达标，第六行「真机」按规格书归用户执行（§8 第 8 条，本机与编译机均无设备）。
