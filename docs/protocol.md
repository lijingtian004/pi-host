# pi RPC 协议规范（v1.0.4 实测冻结版）

> 依据：pi-coding-agent 1.0.4 源码（`src/modes/rpc/{rpc-types,rpc-mode,json-event,jsonl}.ts`）+ golden 抓包 001–008（`/root/pi/.capture/golden/`）。
> 本文是 Android 宿主（`works.earendil.pihost`）core/rpc 模块的**唯一协议事实源**；字段以本文为准，源码行号供追溯。

## 1. 传输与帧

| 项 | 规定 | 证据 |
|---|---|---|
| 通道 | 子进程 stdin/stdout，双向 JSONL | rpc-mode.ts |
| 帧 | **字节级 LF（`\n`，0x0A）分隔**；行尾 `\r` 会被剥离，但绝不能依赖——发送端只发 LF | jsonl.ts:30-40 |
| 解码 | 每行单独 UTF-8 解码；**多字节字符可能跨缓冲区**，不得按 readline 语义切片 | jsonl.ts StringDecoder |
| 陷阱 | U+2028/U+2029（E2 80 A8/A9）在 JSON 字符串外是合法 JSON 空白，JS `readline` 会把它当行终止符 → **必须字节级找 0x0A** | 设计要求 |
| stderr | 不属于协议；宿主侧用 8KB 环形缓冲采集供诊断 | rpc-client.ts 错误信息拼接 |
| 序列化 | `JSON.stringify(v)+"\n"`；stdout 被 takeOverStdout() 接管，无任何非 JSON 输出 | rpc-mode.ts |

**客户端→服务端**：一行一个 JSON 命令对象。解析失败 → 回 `{type:"response",command:"parse",success:false,error:"Failed to parse command: ..."}`（无 id）。

**服务端→客户端**两类行：
1. `response`（对命令的应答，可选用 id 关联）
2. 事件（单向通知，无 id）

## 2. 生命周期

- **无 ready 信号（D4 已闭）**：进程不广播任何启动完成事件。就绪判定 = 收到首个 `response`（推荐先发 `get_state` 探活）。
- 启动 argv：`node <cli.js> --mode rpc [--no-session] [--resume <id>] [--provider p --model m]`；cwd = 项目目录。
- **退出**：客户端关 stdin（EOF）→ 优雅 shutdown 退出码 0。`quit` 不是命令（001 实测 `Unknown command: quit`）。
- 信号：SIGTERM→143，SIGHUP→129。
- `agent_settled` 事件仅表示一轮 prompt 的所有工作（含重试/队列消费）结束，可作为发送下一条 prompt 的门槛。

## 3. 命令（RpcCommand）

所有命令字段 `type` = 命令名；`id?: string` 由客户端提供，原样回显在 response。

### 3.1 会话与模型

| 命令 | 请求字段 | 成功 data | 备注 |
|---|---|---|---|
| `get_state` | — | `RpcSessionState`（§5.1） | 探活首选 |
| `set_model` | `provider,modelId` | `{model}`（ModelInfo） | 坏 id → `"Model not found: mock/no-such-model"` |
| `cycle_model` | — | `{model:{provider,id},thinkingLevel,isScoped}` 或 `null` | |
| `get_available_models` | — | `{models:ModelInfo[]}` | |
| `set_thinking_level` | `level` | — | **无校验**：对 reasoning:false 模型 set "high" 也 success:true（003 实测）；App 必须先用 `get_available_thinking_levels` 过滤 |
| `cycle_thinking_level` | — | `{level}` | |
| `get_available_thinking_levels` | — | `{levels:string[]}` | reasoning:false → `["off"]` |
| `set_steering_mode` | `mode:"all"\|"one-at-a-time"` | — | |
| `set_follow_up_mode` | `mode` | — | |

### 3.2 对话与队列

| 命令 | 请求字段 | 成功 data | 备注 |
|---|---|---|---|
| `prompt` | `message,images?,streamingBehavior?` | `{disposition:"started"\|"queued"\|"handled"}` | 走 preflightResult 通道 |
| `steer` | `message` | `{disposition:"queued"}` | 触发 `queue_update` 事件 |
| `follow_up` | `message` | `{disposition:"queued"}` | 同上 |
| `abort` | — | — | 进行中 assistant 以 `stopReason:"aborted"`、`content:[]` 结束；turn/agent 照常 end→settled |
| `clear_queue` | — | `{steering:string[],followUp:string[]}` | 返回**清空后**剩余（可能已消费） |

### 3.3 工具与执行

| 命令 | 请求字段 | 成功 data | 备注 |
|---|---|---|---|
| `bash` | `command,excludeFromContext?` | `BashResult{output,exitCode,cancelled,truncated,fullOutputPath?}` | 流式 `bash_execution_update{id?,delta}` 事件 |
| `abort_bash` | — | — | |
| `get_session_stats` | — | `{sessionId,userMessages,assistantMessages,toolCalls,toolResults,totalMessages,tokens,cost,contextUsage{tokens,contextWindow,percent}}` | |

### 3.4 重试

| 命令 | 请求字段 | 成功 data | 备注 |
|---|---|---|---|
| `set_auto_retry` | `enabled` | — | 持久化到 settings.json；默认 enabled:true / maxRetries:3 / baseDelayMs:2000 |
| `abort_retry` | — | — | 必须在 `auto_retry_start` 事件**之后**发才有效（重试控制器已建立）；否则 no-op（008 实测） |

退避：`delayMs = baseDelayMs * 2^(attempt-1)`，封顶 60s；默认序列 2000/4000/8000。

### 3.5 会话持久化（无 `--no-session` 时）

| 命令 | 请求字段 | 成功 data | 备注 |
|---|---|---|---|
| `new_session` | `parentSession?` | `{cancelled:false}` | 之后 `get_state` 的 sessionFile/sessionId 全新 |
| `switch_session` | `sessionPath` | — | |
| `fork` | `entryId` | `{text,cancelled}` | 坏 entryId → `"Invalid entry ID for forking"`；成功后 rebindSession |
| `clone` | — | `{cancelled:false}` | = fork(leafId, position:"at")；无 leafId → `"Cannot clone session: no current entry selected"` |
| `get_fork_messages` | — | `{messages:{entryId,text}[]}` | |
| `export_html` | `outputPath?` | `{path}` | `--no-session` 下 → `"Cannot export in-memory session to HTML"`；默认输出相对 cwd 的 `pi-session-<时间>_<uuid>.html` |

### 3.6 查询

| 命令 | 成功 data |
|---|---|
| `get_entries{since?}` | `{entries:SessionEntry[],leafId:string\|null}` |
| `get_tree` | `{tree:SessionTreeNode[],leafId}` |
| `get_messages` | `{messages:AgentMessage[]}` |
| `get_last_assistant_text` | `{text}`（无文本时 `{}`——undefined 字段被序列化丢弃，**不是 null**） |
| `get_commands` | `{commands:RpcSlashCommand[]}` |
| `compact{customInstructions?}` | — |
| `set_auto_compaction{enabled}` | — |
| `set_session_name{name}` | — （先发 `session_info_changed{name}` 事件再回 response） |

### 3.7 扩展 UI 桥（权限对话框的根基）

服务端 → 客户端（事件）：

```json
{"type":"extension_ui_request","id":"<uuid>","method":"confirm","title":"Allow bash tool call?","message":"git branch --show-current","timeout":15000}
```

method ∈ `confirm|select|input`（notify/setStatus/setWidget/setTitle/set_editor_text 为 fire-and-forget，不上 RPC）。

客户端 → 服务端（普通命令行，非 RPC 命令）：

```json
{"type":"extension_ui_response","id":"<同一uuid>","confirmed":true}
{"type":"extension_ui_response","id":"<同一uuid>","cancelled":true}
```

- **无回执**：服务端不确认收到；对话完成后流程自然继续。
- 超时/abort 默认值：confirm→false，select/input→undefined。
- `prompt` 被扩展拦截 → disposition:"handled"。

## 4. 事件流

会话一轮的标准序（001/002 实测）：

```
response(prompt,{disposition:"started"})
→ agent_start
→ turn_start
→ message_start/end(system)          # 首轮才有；sections{preamble,tools,rules,docs}
→ message_start/end(user)            # content:[{type:"text",text}],timestamp=ms
→ message_start(assistant,content:[],stopReason:"pending")
→ message_update ×N                  # §4.2
→ message_end(assistant)             # 完整 content,stopReason,usage,responseId
→ [tool_execution_start/update/end]  # §4.3；工具后第二轮 API
→ turn_end{message,toolResults}
→ agent_end{messages,willRetry}
→ agent_settled
```

### 4.1 事件字段表（golden 001–008 全量实测）

| 事件 | 字段 |
|---|---|
| `agent_start` | （仅 type） |
| `agent_end` | `messages:Message[]`,`willRetry:boolean` |
| `agent_settled` | （仅 type） |
| `turn_start` | （仅 type） |
| `turn_end` | `message:Message`,`toolResults:ToolResultMessage[]` |
| `message_start` / `message_end` | `message:Message` |
| `message_update` | `usage:Usage`,`assistantMessageEvent:AME` |
| `tool_execution_start` | `toolCallId,toolName,args` |
| `tool_execution_update` | `toolCallId,toolName,args,partialResult` |
| `tool_execution_end` | `toolCallId,toolName,result:{content,structuredContent?},isError` |
| `bash_execution_update` | `id?,delta:string` |
| `session_info_changed` | `name:string\|undefined` |
| `queue_update` | `steering:string[],followUp:string[]`（每次入队发，内容=当前队列） |
| `extension_ui_request` | `id,method,title,message,timeout` |
| `auto_retry_start` | `attempt,maxAttempts,delayMs,errorMessage` |
| `auto_retry_end` | `success,attempt,finalError?` |
| `entry_appended` | `entry:SessionEntry`（重试时为 `context_edit{targetId,replacement:null}`——失败尝试从模型投影中删除） |

### 4.2 assistantMessageEvent（message_update.assistantMessageEvent）

`toJsonEvent` 只重写 message_update：`{type:"message_update",usage,assistantMessageEvent:{...去壳 partial}}`。

| AME | 字段 |
|---|---|
| `text_start` | `contentIndex:number` |
| `text_delta` | `contentIndex,delta:string`（每 2 词一个 delta） |
| `text_end` | `contentIndex,content:string` |
| `thinking_start` | `contentIndex` |
| `thinking_delta` | `contentIndex,delta` |
| `thinking_end` | `contentIndex,content` |
| `toolcall_start` | `contentIndex,id,toolName`（json-event.ts enrich） |
| `toolcall_delta` | `contentIndex,delta:string`（**原始 JSON 串片段**，需自行拼接解析） |
| `toolcall_end` | `contentIndex,toolCall:{type:"toolCall",id,name,arguments:object}` |
| `done` | `reason, message` |
| `error` | `reason:"aborted"\|"error",error` |

## 5. 核心数据类型

### 5.1 RpcSessionState（get_state.data）

```json
{
  "model": { "id":"mock-1","name":"...","api":"openai-completions","provider":"mock",
             "baseUrl":"...","reasoning":false,"input":["text"],
             "cost":{...},"contextWindow":128000,"maxTokens":16384 },
  "thinkingLevel": "off",
  "isStreaming": false, "isCompacting": false,
  "steeringMode": "one-at-a-time", "followUpMode": "one-at-a-time",
  "sessionId": "01a10e68-...", "sessionName?": "...", "sessionFile?": "/abs/path.jsonl",
  "autoCompactionEnabled": true, "messageCount": 0, "pendingMessageCount": 0
}
```

`sessionFile` 仅持久会话（无 --no-session）出现；路径 = `~/.pi/agent/sessions/<cwd 魔化目录>/<ISO时间>_<uuid>.jsonl`。

### 5.2 Message（role 区分）

- `system`：`{role, content:string, sections?{preamble,tools,rules,docs}}`
- `user`：`{role, content:[{type:"text",text}]|string, timestamp:number(ms)}`
- `assistant`：`{role, content:[Text|Thinking|ToolCall][], stopReason, usage?, responseId?, timestamp}`；`stopReason ∈ pending|stop|length|toolUse|error|aborted|deferred`
- `toolResult`：`{role, toolCallId, toolName, content:[Text|Image][], details?, isError, timestamp}`

### 5.3 SessionEntry（get_entries.data.entries）

`{type:"message"|"model_change"|"thinking_level_change"|"usage"|"context_edit", id, parentId:string|null, timestamp:ISO, ...载荷}`；`model_change{provider,modelId}`；`thinking_level_change{thinkingLevel}`。

## 6. 错误与边界（实测）

| 情形 | 行为 |
|---|---|
| 非法 JSON 行 | `{type:"response",command:"parse",success:false,error:"Failed to parse command: ..."}` |
| 未知命令 | `success:false, error:"Unknown command: <type>"` |
| 命令处理抛错 | `success:false, error:<message>`；进程不死 |
| `--no-session` 下 export_html | `success:false,"Cannot export in-memory session to HTML"` |
| 模型 500（可重试类错误） | 错误 assistant 轮（content:[], stopReason:"error", errorMessage）→ auto_retry 循环 → 耗尽后 `auto_retry_end{success:false,attempt:3,finalError}` |
| `insufficient_quota` 类 | **不重试**，直接失败 |
| 权限拒绝（扩展 block） | `tool_execution_end{isError:true, result.content.text=<block reason>}` → toolResult isError:true → 模型继续、turn 正常结束 |

## 7. Android 宿主实现要点（对 §14 开放决策的闭合）

- **D3 权限桥（已闭）**：App 不发明权限协议。权限策略以 pi 扩展注入（`~/.pi/agent/extensions/perm.ts`，tool_call hook + ctx.ui.confirm）；RPC 面上只有 `extension_ui_request{method:"confirm"}` ↔ `extension_ui_response{confirmed|cancelled}` 转发。用户点「允许」→ confirmed:true；「拒绝」/超时 → cancelled:true（hook 收到 false 走 block 分支）。
- **D4 ready 信号（已闭）**：无 ready 事件。宿主 spawn 后发 `get_state`（无 id），以收到该 response 为就绪；`agent_settled` 作为 prompt 完成门槛。
- 事件与 response 交错到达，**单读循环按行分发**：有 id 且匹配 pending → resolve；否则进事件流。
- `set_thinking_level` 无服务端校验 → UI 层用 levels 白名单。
- `abort_retry` 只在 `auto_retry_start` 后有效。
- 退出顺序：stdin.close() → 等待 exit（0）→ 5s 兜底 destroy()（Android Process.destroy()=SIGKILL）。

## 8. Golden 样本索引

| 文件 | 覆盖 |
|---|---|
| `001-basic.jsonl` | 基本对话轮、get_state/models/levels、quit 错误 |
| `002-toolcall.jsonl` | 工具调用全事件流、stats/entries/tree |
| `003-errors-bash.jsonl` | 错误命令、bash 直执行、session_name、export_html 失败 |
| `004-thinking-fork.jsonl` | thinking 流、fork/clone/get_fork_messages |
| `005-perm-ext.jsonl` | **权限桥全路径**（允许+拒绝） |
| `006-queue-abort.jsonl` | steer/follow_up 队列、abort、clear_queue |
| `007-session-persist.jsonl` | 持久会话、new_session、export_html 成功 |
| `008-auto-retry.jsonl` | 重试耗尽/退避中 abort/禁用重试 |
