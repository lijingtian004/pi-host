# P2E 侦察记录（会话列表 + 设置屏 + markwon 渲染 + 多会话，动工前）

> 第 0 步产物（规格书 §5 / §6.1）。事实来源全部是**本机/编译机实测输出**，未跑的步骤标注「未执行」。
> 编译机 = `root@64.90.11.50:/root/android-host`；任务行 = `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
> JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStorePassword=changeit
> -Djavax.net.ssl.trustStoreType=PKCS12" /opt/gradle-8.13/bin/gradle --no-daemon --console=plain`（§8）。
> 冒烟顺序按 §6.1：toml 三件套 + 最小 AndroidView composable + DataStore 探针 → 编译机 `:app:assembleDebug`
> → **绿了才写业务代码**（现已绿，见 §1.5）。探针文件 = `feature/chat/src/test/.../ui/MarkwonSmokeTest.kt`
> （4 条）与 `core/engine/src/test/.../DataStoreProbeTest.kt`（4 条），**全部保留进正式套件**。
>
> 断点续跑说明：本里程碑上一轮在网络中断处夭折，工作树里留下 toml/两个 build.gradle.kts/
> SmokeMarkdownView/PiGrammarLocator/MarkwonSmokeTest/DataStoreProbeTest 共 7 处未提交改动。
> 盘存结论 = **形状可用、内容需三处修复**（§1.2 全局排除、§1.4 grammar 签名、§3 的 edit 导入与
> 结论 2 的 store 生命周期）；三处全部在本轮实测修绿，未推翻重写。

## 1. markwon 三件套 + prism4j 在 AGP 8.13 / compileSdk 36 的解析与编译（§5.1）

### 1.1 坐标实测：规格书写法在 Maven Central **不存在**

规格书 §3.3 写 `io.noties.markwon:markwon-core` / `io.noties.markwon:markwon-syntax-highlight` /
`io.noties.prism4j:prism4j`。编译机对 `repo1.maven.org` 逐条实测：

| 规格书坐标 | HTTP | 真实坐标 |
|---|---|---|
| `io.noties.markwon/markwon-core/4.6.2/…pom` | **404** | `io.noties.markwon:core:4.6.2` |
| `io.noties.prism4j/prism4j/2.0.0/…pom` | **404** | `io.noties:prism4j:2.0.0`（group 就是 `io.noties`，没有 `.prism4j`） |
| `io.noties.markwon/syntax-highlight/4.6.2/…pom` | 200 | 与规格书同名，无需改 |

→ toml 按真实坐标落（`gradle/libs.versions.toml` 的 `markwon-core` / `markwon-syntax-highlight` /
`prism4j` 三条 alias 名保持规格书语义不变）。**版本未被替换**：4.6.2 / 4.6.2 / 2.0.0 三条
仍是规格书 §2 白名单里的版本。

### 1.2 实际解析版本实录（编译机 `:app:dependencies --configuration debugRuntimeClasspath` 原文）

```
+--- io.noties.markwon:core:4.6.2
|    \--- com.atlassian.commonmark:commonmark:0.13.0
+--- io.noties.markwon:syntax-highlight:4.6.2
|    +--- io.noties.markwon:core:4.6.2 (*)
|    \--- io.noties:prism4j:2.0.0
\--- io.noties:prism4j:2.0.0
\--- androidx.datastore:datastore-preferences:1.1.7
     \--- androidx.datastore:datastore-preferences-android:1.1.7
          +--- androidx.datastore:datastore:1.1.7 → datastore-android:1.1.7
          +--- androidx.datastore:datastore-core:1.1.7
          +--- androidx.datastore:datastore-core-okio:1.1.7
          +--- androidx.datastore:datastore-preferences-core:1.1.7
          +--- androidx.datastore:datastore-preferences-proto:1.1.7 (c)
          \--- androidx.datastore:datastore-preferences-external-protobuf:1.1.7 (c)
```

- `commonmark 0.13.0` 是 `markwon:core` 的**传递依赖**，不是我新增的白名单条目；
  datastore 的 6 个 `-android`/`-core`/`-okio`/`-proto` 子件同理（1.1.7 自己的模块拆分）。
- **`org.jetbrains:annotations-java5` 在解析图里出现 0 次**（`grep` 全文无命中）—— 见下面排除项。

**第一轮的失败原文**（`:app:checkDebugDuplicateClasses FAILED`，共 30+ 行同形）：

```
Duplicate class org.intellij.lang.annotations.Flow found in modules
  annotations-23.0.0.jar -> annotations-23.0.0 (org.jetbrains:annotations:23.0.0) and
  annotations-java5-17.0.0.jar -> annotations-java5 (org.jetbrains:annotations-java5:17.0.0)
Duplicate class org.jetbrains.annotations.NotNull found in modules …
```

根因与**修法**（这条是本轮最有价值的踩点）：上一轮把 `exclude(group="org.jetbrains",
module="annotations-java5")` 写在 `:feature:chat` 的 `implementation(libs.prism4j){…}` 上，
`:\u0066ature:chat` 自己解析确实干净，**但库模块的 exclude 不进它暴露给消费者的依赖图**：
`:app` 解析 `debugRuntimeClasspath` 时重走一遍图，annotations-java5 照样被 prism4j 拖进来，
所以 `:app:assembleDebug` 仍然判重复类。
→ 定案：把排除写到**根 `build.gradle.kts` 的 `allprojects { configurations.configureEach { exclude(…) } }`**，
一次覆盖全仓所有解析面（含 `:app` 的 dex 与 `:feature:chat` 的单测）。`:feature:chat`
里那条局部 exclude 保留为双保险，不承担 `:app` 侧的修复责任。
被排除的是**纯编译期注解 jar**，同一批 FQN 由 `org.jetbrains:annotations:23.0.0`
（kotlin-stdlib 2.2.20 本来就在拖）提供，运行期语义不变。

R8：debug 不开 minify（`app/build.gradle.kts` 无 `isMinifyEnabled`），本轮只验 compile + dex，
与规格书 §5.1 的口径一致 → **未执行** release/R8 验证（P2E 不发 release）。

### 1.3 prism4j 2.0.0 **不带任何语法表**（规格书 §3.3「languages() 全量注入」的前提不成立）

编译机实测（`find /root/.gradle/caches -name prism4j-2.0.0.jar` → `unzip -l`）：

```
37 files（其中 31 个 .class），包只有两个：io/noties/prism4j、io/noties/prism4j/annotations
```

没有 `components/*.json`、没有 `Prism4jGrammarLocator`、没有任何语言语法数据。
prism4j 的语法表从来不是库自带的，而是 **`prism4j-bundler`**（一个 KAPT 注解处理器 +
语法定义包 `prism4j-languages`）在编译期生成 `Prism4jGrammarLocator implements GrammarLocator`。
bundler **不在 §2 依赖白名单**，且要再拉一条 kapt 插件线（本工程全 KSP）。

→ 落地形态按规格书的**意图**（「code-fence 默认语法、未覆盖语言按普通 code-block 展示、D5 开档、
不引 WebView」）：`PiGrammarLocator` 自给一套**通用五类 token**（comment/string/keyword/function/number）
语法，11 门规范语言 + 24 条别名共用同一张定义；别名表没有的语言（含 mermaid）
经 `SyntaxHighlightPlugin.create(prism4j, theme, FALLBACK="text")` 的 fallback 落到同一套通用语法，
即「灰色 monospace 普通代码块」。实测见 §2 的 smoke04。

### 1.4 `GrammarLocator.grammar` 的真实签名（第二轮的编译失败原文）

```
e: PiGrammarLocator.kt:25:1 Class 'PiGrammarLocator' is not abstract and does not implement abstract member:
   fun grammar(p0: Prism4j, p1: String): Prism4j.Grammar?
e: PiGrammarLocator.kt:35:5 'grammar' overrides nothing. Potential signatures for overriding:
   fun grammar(p0: Prism4j, p1: String): Prism4j.Grammar?
```

接口参数是**非空 `String`**，上一轮写成 `language: String?` → Kotlin 2.2.20 判「overrides nothing」。
→ 改成 `override fun grammar(prism4j: Prism4j, language: String)`，空串/空白由 `isBlank()` 兜住返回 null。

### 1.5 冒烟结论（全部实测，编译机）

| 轮 | 任务 | 结果 |
|---|---|---|
| 1 | `:app:assembleDebug` | FAILED — `:app:checkDebugDuplicateClasses`（§1.2） |
| 2 | `:app:assembleDebug` | FAILED — `:feature:chat:compileDebugKotlin`（§1.4）；同轮 `:app:mergeExtDexDebug` 已过 → 排除项生效 |
| 3 | `:app:assembleDebug :core:engine:testDebugUnitTest :feature:chat:testDebugUnitTest` | FAILED — `DataStoreProbeTest.probe03`（§3 结论 2）；`:app:assembleDebug` **绿**、`MarkwonSmokeTest` **4/4 绿** |
| 4 | 同上（修 probe03 后） | **BUILD SUCCESSFUL in 1m38s**（150 tasks）；`:core:engine` 44 全绿、`:feature:chat` 27 全绿 |

冒烟 APK：`app/build/outputs/apk/debug/app-debug.apk` = **63,675,532 字节**，
`sha256=68df6bdb25569f1aa574670e49f2a2098c75d58b1264c124d1c49f116d50af3f`；
`aapt2 dump badging` 形状不变（`package name='works.earendil.pihost' versionCode='1' versionName='0.1.0'`
/ `compileSdkVersion='36' platformBuildVersionName='16'` / `targetSdkVersion:'35'` / `native-code: 'arm64-v8a'`）。
对比 P2D 收尾的 62,765,189B：**+910,343B** = markwon core + syntax-highlight + prism4j + commonmark
+ datastore 五个包的 dex 与资源（so 与 assets 部分字节不变）。

**渲染层可以动工**（§4.6 的门已开）。

## 2. Robolectric 下 markwon AndroidView 渲染可行性（§5.2 → §4.5 降级预案的判定）

**可行，不需要降级。**`MarkwonSmokeTest` 4 条实测全绿（`@Config(sdk=[35], qualifiers="w420dp-h800dp")`
+ `createAndroidComposeRule<ComponentActivity>()`）：

| 用例 | 验的东西 | 结果 |
|---|---|---|
| smoke01 | `markwon.render(markwon.parse(…))` 出 `Spanned` 且**真带 span**（不是原文透传） | ✅ |
| smoke02 | **`AndroidView(factory=TextView, update=markwon.setMarkdown)` 在 Robolectric 下渲染出可读文本 + span** | ✅ |
| smoke03 | ```kotlin 围栏过 prism4j 后代码原文一字不丢（含字符串字面量） | ✅ |
| smoke04 | 语法表语义：注册语言/别名命中、`FALLBACK` 存在、未注册语言回 null、bash 五类 token、tokenize 产出节点 | ✅ |

配方要点（坑位记录）：

- smoke02 取「渲染结果」的方式是**遍历 decorView 的 TextView 子树**（`collectTextViews`），
  不是 `assertTextEquals`：`AndroidView` 的语义节点在 Robolectric 下不暴露内部 TextView 的文本
  （RECON-P2D §4 的同一条「容器不聚合子文本」限制）。所以断言写成
  「存在一个 TextView，其 `text` 含关键字且 `getSpans()` 非空」。
- `markwon` 实例在测试里用 `RuntimeEnvironment.getApplication()` 造，
  与正式装配（`@ApplicationContext`）同形，避免 Activity 泄漏。
- **结论落到 §4.5**：markwon 渲染 UI Test 走「Robolectric + createComposeRule + AndroidView」
  这条**正路**，不降级为纯 `Spanned` 函数测试（纯函数面 smoke01/03 已经覆盖，作为第二层保险保留）。

## 3. DataStore Preferences 1.1.7 在 Robolectric 4.16 的测试配方（§5.3）

`DataStoreProbeTest` 4 条实测全绿（`@Config(sdk=[35])` + `TemporaryFolder`）。四条结论：

1. **`PreferenceDataStoreFactory.create(scope = …, produceFile = …)` 可用**（1.1.7 上该重载仍是
   公开 API，只有 deprecation 提示，无编译失败）。**必须显式给 scope**：默认 scope 挂在
   `Dispatchers.IO` 真线程上，`runTest` 的虚拟时钟下会「测试先结束、写盘后完成」而偶发读到旧值；
   探针用 `runBlocking`（真实时间）钉死，正式实现显式传 `@EngineScope` 那条。
2. **同一个文件只允许一个活跃实例**（第一轮 probe03 就是挂在这里，原文）：

   ```
   java.lang.IllegalStateException: There are multiple DataStores active for the same file:
   /tmp/junit6623419324519293515/persisted.preferences_pb. You should either maintain your
   DataStore as a singleton or confirm that there is no two DataStore's active on the same file
   (by confirming that the scope is cancelled).
       at androidx.datastore.core.FileStorage.createConnection(FileStorage.kt:52)
   ```

   占用锁随**创建时那条 scope 的完成**释放 → 换实例重读同一份盘之前必须先 `scope.cancel()`
   （测试里包成 `withStore(…) { … }` 的 try/finally）。
   **生产侧纪律**：`SettingsRepository` 必须 `@Singleton`，且全进程只由一条 scope（`@EngineScope`）
   构造一个 store；任何「再拿一个 repository 实例」的写法都会在第二次注入时炸掉。
3. 文件路径必须**每条用例独立**（`TemporaryFolder` 天然满足），否则跨用例复用路径踩结论 2。
4. **键缺失 → null，不抛、不落盘**（probe01 实测 `asMap().size == 0`）→
   SettingsRepository 的默认值语义 = 「读不到键就取仓库常量」，**不做首次落盘初始化**。
   `members.edit { it[key] = v }` 是 `androidx.datastore.preferences.core` 包的**扩展函数**，
   必须单独 `import androidx.datastore.preferences.core.edit`（上一轮漏了这条 →
   `Unresolved reference 'edit'` ×4）。

## 4. SessionScanner「只读头 64KB」的实现细节与 pi 侧对齐点（§5.4）

依据 = `/root/pi/.tools/pi-1.0.4/packages/coding-agent/src/core/session-manager.ts` 实读
（`:587-597` 目录编码 / `:806-880` `buildSessionInfo` / `:921` 排序 / `:1060-1080` `newSession` 写头）。

### 4.1 pi 侧事实（逐条行号）

- 目录名（`:592`）：`` `--${resolvePath(cwd).replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--` ``，
  落在 `getAgentDir()/sessions`（`config.ts:605-611` = `join(homedir(), ".pi", "agent")`，
  可被 `PI_CODING_AGENT_DIR` 覆盖 —— EnvFactory **没有**设这条，所以恒走 `~/.pi/agent`）。
- 文件名（`:1077-1079`）：`timestamp.replace(/[:.]/g, "-") + "_" + sessionId + ".jsonl"`，
  `timestamp = new Date().toISOString()`。
- 首行 header（`:1064-1071`）：`{type:"session", version:CURRENT_SESSION_VERSION(=3), id, timestamp, cwd, parentSession?}`。
- `buildSessionInfo`（`:806-880`）逐行扫（`createReadStream` + `readline`，`crlfDelay: Infinity`），
  读缓冲 `SESSION_READ_BUFFER_SIZE = 1024*1024`，另有 `SESSION_HEADER_READ_BUFFER_SIZE = 4096`。
- 排序（`:921`）`sessions.sort((a,b) => b.modified.getTime() - a.modified.getTime())`；
  文件枚举侧（`:756-757`）`statSync(path).mtimeMs` 降序；`:1983-1986` 的候选排序
  = `mtimeMs` 降序 **再** `basename.localeCompare`。→ 规格书 §3.1 的
  「mtime 降序 → 文件名字典序」即抄的这条。
- 列表读取用 `mapWithConcurrency(files, limit, …)`（`:904-919`）——**pi 自己也知道全文件扫太重**。

### 4.2 App 侧头读的实现选择（本里程碑定案）

`RandomAccessFile("r")` + 一次 `readFully(ByteArray(min(size, 64KB)))`，
按 `\n` 切行（同时吃 `\r\n`），**最后一行若不完整就丢掉**（半行 JSON 必然解析失败）。
不整文件读入、不 mmap、不用 `BufferedReader`（后者要么全读、要么靠 `ready()` 猜边界）。

对齐/偏差清单（**规格书 §9 偏差 2 的实证内容**）：

| 项 | pi 侧 | App 侧 SessionScanner | 定性 |
|---|---|---|---|
| 扫描范围 | 全文件逐行 | 头部 ≤64KB（且 ≤200 行双上限） | **有意偏差**（列表页 IO 不能卡 UI） |
| 缺 header | 第一条**可解析**行的 `type!=="session"` → 整个文件返回 null；前面的坏行被 `continue` 跳过 | 同：第一条**可解析**行必须是 `type:"session"`，否则跳过该文件 | 对齐 |
| `name` | 最后一条 `session_info`，`name?.trim() \|\| undefined` → **后来的空串会清空名字** | 同（不是「取最新非空」，而是「取最新，空即无」） | 对齐（比规格书 §3.1 的措辞更准） |
| `messageCount` | 全文件 `type==="message"` 计数（含 toolResult 等所有 role） | 头窗内同规则计数 | **截断语义**：>64KB 的文件里它是「窗口内条数」，不是总数 |
| `firstMessage` | 第一条 `role==="user"` 且 `extractTextContent` 非空；没有则 `"(no messages)"` | 同规则，取不到 → `null`，UI 显示占位符 | 对齐（占位符在 UI 层） |
| `modified` | 优先**最后一条 message 的时间戳**，退回首行 timestamp，再退回 `stats.mtime` | 恒取 `File.lastModified()`（= mtime） | **有意偏差**：头窗看不到尾部活动时间；排序规则仍与 §3.1 一致 |
| 解析失败 | `try/catch` → null | 单文件 `runCatching` → 跳过 + `Log.w` | 对齐 |
| 目录不存在 | `getDefaultSessionDir` 会 mkdir | **返回空表，绝不 mkdir**（App 只读 pi 的存储） | 有意（§4.1「App 不复制」） |

## 5. `SwitchSession` 的 sessionPath 在 Android 沙盒里的真实形状（§5.5）

**真机未验证**（本里程碑没有设备；下面是「EnvFactory 推导 + 冻结区实读」的确定性推导，
步骤 3 的 `SessionScannerTest` 会用 Robolectric 的可写临时目录把**同一套相对结构**跑通）。

推导链（每一环都有源码出处）：

1. `EnvFactory.build`（冻结区 `core/environment/EnvFactory.kt:16-20`）设
   `HOME = PathLayout.homeDir(ctx).absolutePath` = `<filesDir>/home`。
2. pi `getAgentDir()` = `join(homedir(), ".pi", "agent")` →
   **sessions 根 = `<filesDir>/home/.pi/agent/sessions/`**。
3. `SessionSupervisor` 的 `workingDir` = `EngineModule.DEFAULT_PROJECT` 经
   `PathLayout.projectDir(ctx, "default")` = `<filesDir>/projects/default`；
   pi 侧 `resolvePath(cwd)` = `path.resolve`（**规范化但不解 symlink**，utils/paths.ts:103-107）。
4. 编码规则（§4.1）→ 目录名 =
   `--` + `<filesDir>/projects/default` 去头 `/` 后把 `/ \ :` 全替成 `-` + `--`。
   真机上 `filesDir` 通常是 `/data/user/0/works.earendil.pihost/files`
   （`/data/data/<pkg>` 是它的 symlink，但 §3 说 `resolvePath` 不解 symlink，
   所以**取决于 `Context.getFilesDir()` 原样返回哪一条**，真机两种形状都见过 → 标注未验证），
   于是完整 sessionPath 形状：

   ```
   /data/user/0/works.earendil.pihost/files/home/.pi/agent/sessions/
     --data-user-0-works.earendil.pihost-files-projects-default--/
       2026-10-06T11-22-33-444Z_11111111-2222-4444-8888-555555555555.jsonl
   ```

5. 因此 **SessionScanner 不猜目录名**：它扫 `sessions/` 下**所有** `--*--` 子目录，
   每条 jsonl 的 `cwd` 从首行 header 里读（§4.1），这样多项目/符号链接差异都不成为问题；
   `SwitchSession(sessionPath)` 直接把这条绝对路径原样发下去（规格书 §3.2 的参数就是路径）。
6. 可测面（步骤 3 落实）：Robolectric 的 `filesDir` 是 `RuntimeEnvironment.getApplication().filesDir`
   下的真目录，测试里用 `TemporaryFolder` 复刻 `--<编码cwd>--/<ts>_<id>.jsonl` 结构，
   并额外断言「编码函数对 `/data/user/0/…/projects/default` 的输出 = 上面那串」，
   把编码规则本身钉死。

## 6. 编译机磁盘回收实录（§5.6 / §2 红线 / §8 顺序）

`df -h /` 逐轮实录（回收顺序 = §8：`app/build/intermediates` → `runtime-pkg/dl` → `out/stage`）：

| 时点 | 余量 | 动作 |
|---|---|---|
| 动工前（上一轮已回收） | **5.1G** | — |
| 冒烟 4 轮 + 依赖下载后 | 4.7G | — |
| 回收执行 | — | `rm -rf /root/android-host/app/build/intermediates`（375M） |
| 回收后 | **5.0G** | 满足 §2 的 ≥4G 红线 |

- `runtime-pkg/dl` 与 `out/stage` 在编译机上**不存在**（`du` 报 `No such file or directory`）——
  这两条是 job A/B 打包侧的本机目录，编译机上无对应物 → 本轮无可回收项，如实记录。
- 其它大头：`/root/.gradle/caches` 4.8G（不动：删了就退化成全量重下）、`/tmp` 591M、
  `/root/.android` 6.2M，均不在 §8 的回收顺序里。
- 后续每轮 gradle 前 `df -h /` 打印仍在 `bbuild.sh gradle` 的包装里（日志首部可见）。

## 7. 规格书未覆盖处的默认取值（本轮自行定案，逐条进收尾报告）

1. **依赖坐标**按 §1.1 的真实 FQN 落（版本不变）。
2. **prism4j 语法表** = 自给通用语法 + fallback（§1.3），D5 保持开档。
3. **`ScannedSession.modified` 取 mtime**（§4.2 表第 6 行），排序规则仍照 §3.1。
4. **`firstMessage: String?`**，`null` 时 UI 显示「（无消息）」占位（§4.2 表第 5 行）。
5. **SessionScanner 落在 `:feature:sessions`**（§4.1 允许「core/engine 或 feature 层，qoder 自定」）：
   它的消费方只有 sessions 屏，落 core 会让引擎层带上一条与状态机无关的文件 IO；
   同时暴露 `SessionScanner` 接口（`scan()` / `delete()`），
   让 `SessionsViewModelTest` 能用 FakeScanner（§4.5 要求）。
6. **SettingsRepository 落在 `core/engine`**（同 §4.1 的「core/engine 或独立 data 模块」二选一）：
   `EngineModule` 的 `envFor` lambda 要在 core 侧同步读 memTier，放 feature 会形成
   `core:engine → feature` 的反向依赖（§2 红线的原话依据）。
7. **Markwon 实例的 Hilt 供给点 = `:feature:chat` 自己的 `MarkwonModule`**，
   由 `ChatViewModel` 注入后暴露给 UI（§4.2 说「挂 settings 或 EngineModule，qoder 自定」）：
   不放 `EngineModule` 是为了不让 core/engine 的编译/运行面带上 markwon 这个纯 UI 依赖；
   不放 `:feature:settings` 是因为设置屏根本不渲染 markdown。
8. **下拉刷新用 material3 `PullToRefreshBox`**（compose-bom 2025.09.01 → material3 1.4.0 自带，
   不新增 §2 之外的依赖）；同时顶栏留一条**刷新按钮**，
   因为 Robolectric 下 `performTouchInput` 的下拉手势不可靠（步骤 3 实测后回填结论）。
