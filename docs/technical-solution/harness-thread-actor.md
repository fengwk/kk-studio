# Harness Session Tree 与 Thread Actor

本文定义 Harness 的会话、分支、输入队列、运行循环、Tool、Subagent、HTTP 与前端投影。数据库是全部可恢复事实的权威来源；浏览器连接、JVM 对象和 worker 线程均可随时丢失。

## 1. 设计目标

Harness 采用以下最小模型：

```text
Session
  = 共享 append-only Entry Tree
  + 稳定 mainThreadId

Thread
  = 指向一个 Entry leaf 的 durable actor
  + 多生产者有序 mailbox
  + 单 Processor main loop
  + Thread Event journal

Branch
  = root -> Thread.headEntryId 的 Entry path
```

客户端通过 Thread ID 无状态提交。多个客户端可以并发向同一个 Thread 入队，但只有持有数据库 fencing token 的 Processor 可以推进 Thread head。

## 2. 领域所有权

| 概念 | 职责 | 持久化 |
| --- | --- | --- |
| Session | Entry Tree 容器、Main Thread 默认入口、父子 Session 关系 | `harness_session` |
| SessionEntry | 消息与 Branch 配置的唯一语义事实 | `harness_session_entry` |
| AgentThread | Branch head、mailbox sequence、执行状态、processor lease/fencing | `harness_thread` |
| ThreadInput | 多生产者有序 mailbox、网络幂等与 apply/cancel 结果 | `harness_thread_input` |
| ThreadStop | Stop 网络幂等回执 | `harness_thread_stop` |
| ThreadEvent | 流式与运行状态 journal、SSE cursor | `harness_thread_event` |
| ToolInvocation | Tool permission、执行 lease、副作用幂等与结果 | `tool_invocation` |
| SubagentTask | parent invocation 到独立 Child Session/Main Thread 的关系 | `harness_subagent_task` |
| Turn | 一次 Provider 请求及其 Tool batch | 运行时概念，不单独建表 |

Session 不拥有唯一当前 Branch。`mainThreadId` 只是稳定的默认主线，不随用户切换或后台活动改变。

## 3. 核心不变量

1. Session 创建、初始配置 Entries 和 Main Thread 必须原子提交。
2. `Session.mainThreadId` 必须指向本 Session 的 Thread，创建后不可更换。
3. Thread head 必须属于同一个 Session，并且只能沿新 Entry 单向推进。
4. 既有 Thread 不 rewind；从历史节点继续必须创建新 Thread。
5. ThreadInput 只按 `(threadId, sequence)` 排序；时间戳和 Snowflake 数值大小不表达 mailbox 因果。
6. 一个 Thread 同时最多一个有效 processor token；所有 Processor 写入都必须校验 token。
7. Entry 是 transcript 与配置的唯一语义事实；ThreadEvent 只提供实时覆盖层。
8. Agent、Model、Toolset、YOLO 均由 Entry path fold 得出，Thread 与 Session 行不保存配置副本。
9. Tool 副作用以 `tool_invocation.id` 幂等，Stop 或 lease 过期不能让旧执行者提交越权结果。
10. 所有 durable trigger 只在事务提交后执行 `kick(threadId)`。
11. Snowflake ID 在 HTTP、SSE、URL 和 TypeScript 中始终使用正十进制字符串。

## 4. 数据模型

### 4.1 `harness_session`

```text
id
title
main_thread_id
parent_session_id
root_session_id
parent_invocation_id
depth
version
gmt_create
gmt_modified
```

- Root Session：`parent_session_id = null`、`root_session_id = id`、`depth = 0`。
- Child Session：记录父 Session、根 Session、父 Task ToolInvocation 和递增 depth。
- `agent_definition_id` 不保留；当前 Agent 身份与冻结配置来自 `AGENT_SNAPSHOT` Entry。

### 4.2 `harness_session_entry`

```text
id
session_id
parent_entry_id
entry_type
payload_json
gmt_create
```

Entry append-only，不更新、不软删除。`parent_entry_id` 构成树；同一父 Entry 的多个子 Entry 是合法分叉。

### 4.3 `harness_thread`

```text
id
session_id
head_entry_id
status
input_sequence
processor_token
processor_until
version
gmt_create
gmt_modified
```

Thread 不保存 Agent、runtime config 或 YOLO。

`status`：

| 状态 | 含义 |
| --- | --- |
| `IDLE` | 当前无运行 activation；允许新 Input 将其激活 |
| `RUNNING` | 存在普通可推进工作；可能尚未取得 processor lease |
| `WAITING` | 等待 Tool、Tool ASK 或 Subagent 等外部 durable 条件 |
| `FAILED` | Provider/setup/policy 失败，必须显式 Retry |
| `RETRYING` | 已显式 Retry；必须先偿还失败 Turn，再 Harvest 后续 Queue |

Thread status 与 processor lease 正交。`WAITING` 时通常没有 processor token；`RUNNING` 时 kick 丢失也可能暂时没有 token。

### 4.4 `harness_thread_input`

```text
id
thread_id
sequence
input_type
payload_json
client_message_id
status
applied_entry_id
resolved_at
cancelled_by_stop_id
gmt_create
```

状态：

```text
QUEUED -> APPLIED
QUEUED -> CANCELLED
```

约束：

- 唯一 `(thread_id, sequence)`。
- 唯一 `(thread_id, client_message_id)`。
- `APPLIED` 必须同时具有 `applied_entry_id` 与 `resolved_at`。
- `CANCELLED` 必须具有 `cancelled_by_stop_id` 与 `resolved_at`。
- 所有状态迁移必须先锁 Thread 行。

### 4.5 `harness_thread_stop`

```text
id
thread_id
client_request_id
gmt_create
```

唯一 `(thread_id, client_request_id)`。被该 Stop 取消的 Input 通过 `cancelled_by_stop_id` 关联。重复 Stop 请求返回同一批已取消消息，即使第一次 HTTP 响应丢失也不会丢草稿。

### 4.6 `harness_thread_event`

保持全局 Snowflake `id` 作为 SSE cursor：

```text
id
thread_id
subject_entry_id
event_type
payload_json
gmt_create
```

事件按数据库返回顺序 append，客户端只按字符串 ID 去重，不按时间或 ID 二次排序。

### 4.7 `harness_subagent_task`

除父子 Session/Thread 字段外，增加：

```text
root_thread_id
```

`root_thread_id` 是当前 delegation tree 的根用户 Thread，用于把 Child Session 中的 Tool ASK durable 通知路由到用户正在观察的根 Thread。ToolInvocation 本身仍归属 Child Thread。

## 5. Entry 与 Input 类型

### 5.1 SessionEntryType

```text
MESSAGE
AGENT_SNAPSHOT
MODEL_CHANGE
TOOLSET_CHANGE
YOLO_CHANGE
COMPACTION
BRANCH_SUMMARY
CUSTOM
CUSTOM_MESSAGE
LABEL
```

`AgentSnapshotEntryPayload` 同时记录源 `agentDefinitionId` 与完整冻结 `AgentSnapshot`。Agent Snapshot 重置 Agent、Model、Toolset、Skill、Subagent allowlist 和 execution policy；YOLO 独立 fold，不因切换 Agent 隐式改变。

### 5.2 ThreadInputType

```text
USER_MESSAGE
CUSTOM_MESSAGE
SET_AGENT
SET_MODEL
SET_TOOLSET
SET_YOLO
```

HTTP 边界使用 typed DTO，禁止客户端构造 Assistant、Tool Result、Compaction 或其他服务端 Entry。

## 6. Session 与 Main Thread 创建

`POST /api/sessions` 接收 Agent、title 与初始 YOLO。服务端预分配：

```text
sessionId
snapshotEntryId
optionalYoloEntryId
mainThreadId
```

一个事务内：

1. 解析并冻结 Agent Snapshot。
2. 插入 Session。
3. 插入根 `AGENT_SNAPSHOT` Entry。
4. 初始 YOLO 非默认值时追加 `YOLO_CHANGE` Entry。
5. 创建 Main Thread，head 指向最后一个初始配置 Entry，status=`IDLE`。
6. 回写/校验 `Session.mainThreadId`。
7. 写 Main Thread `THREAD_STARTED` Event。

任一步失败整体回滚。Main Thread 不允许删除或归档。

Child Session 使用同一创建原语，但在同一 Task 事务中额外：

1. 写父子 Session/Invocation 关系。
2. 创建独立 Main Thread。
3. 把 Subagent prompt 作为首条 `USER_MESSAGE` ThreadInput 入队。
4. Thread status=`RUNNING`，提交后 kick Child Main Thread。

## 7. Thread 分支与 Tree 选择

`POST /api/sessions/{sessionId}/threads` 必须携带属于该 Session 的 durable `fromEntryId`。新 Thread：

```text
headEntryId = fromEntryId
status = IDLE
inputSequence = 0
processorToken = null
```

不复制 Entry、ToolInvocation、Event 或配置。配置由共享祖先路径自动继承。

Tree UI 对齐 pi：

| 选择类型 | 新 Thread 起点 | Composer |
| --- | --- | --- |
| USER / CUSTOM_MESSAGE | 所选 Entry 的父 Entry | 回填所选消息原文，可编辑后提交 |
| ASSISTANT / TOOL / Compaction / 其他可见 Entry | 所选 Entry | 清空，从该点继续 |

Filter 是纯 UI 投影：

```text
default
no-tools
user-only
assistant-only
labeled-only
all
```

`assistant-only` 是产品扩展。Filter 不改变 Entry Tree、Thread head 或 Provider Context。

## 8. 无状态提交与 Mailbox

消息提交：

```http
POST /api/threads/{threadId}/messages
```

事务：

1. 用 `(threadId, clientMessageId)` 查询幂等重放。
2. 锁 Thread 行。
3. 再次检查幂等键，避免并发双 miss。
4. `input_sequence += 1`。
5. 插入 `QUEUED` Input。
6. `IDLE -> RUNNING`；`FAILED` 保持失败，其他状态不变。
7. 提交后仅在 Thread 可运行时 kick。

配置命令走同一 mailbox 与顺序规则。客户端不提交 head、anchor、processor token 或 Activity ID。

## 9. Steer-all Harvest

Harvest 只发生在安全边界：

- 第一次 Provider 调用前。
- 一个无 Tool 的 Turn 完成后。
- 当前 Tool batch 全部终态并应用后。
- Compaction 完成后。

不得在 Provider stream 中途或 Tool batch 未完成时 Harvest。

原子算法：

1. 锁定并校验 Thread processor token。
2. 读取 Thread 当前 `input_sequence` 作为 cutoff。
3. 按 sequence 查询 `QUEUED AND sequence <= cutoff` 的全部 Input。
4. 按顺序 decode、校验并逐条 append Entry，父节点从原 head 连续推进。
5. 每条 Input CAS 为 `APPLIED` 并记录 `applied_entry_id`。
6. Thread head 更新为批次最后 Entry。
7. 写 `INPUT_APPLIED` / batch 事件。

与 Harvest 并发、在 Thread 行锁释放后分配的新 sequence 自动进入下一批。

同批包含一个或多个 USER/CUSTOM 消息时，只触发一次新的 Assistant Turn。配置-only 批次只更新 Branch 配置投影，不调用 Provider。

## 10. Thread Main Loop

逻辑上每个 Thread 有一个 main loop；物理上所有 Thread 共享有界 Executor。

```text
durable commit
  -> afterCommit kick(threadId)
  -> local activation coalescing
  -> DB tryAcquire lease
  -> runLoop
  -> idle / wait / fail 后退出
```

普通 `RUNNING`：

```text
renew lease
-> 收敛 Tool 状态
-> 若仍有非终态 Tool：WAITING + release
-> 应用当前 Tool batch 的终态结果
-> Harvest cutoff 内全部 Input
-> 若 Branch 欠一次模型响应：beginTurn + Provider
-> 否则原子确认 quiescent：IDLE + release
```

`RETRYING` 是唯一例外：先用失败时的 durable head 重新执行 Provider，不 Harvest 后续 Queue；重试成功后回到普通安全边界。

## 11. Response debt 判定

配置 Entry 可能位于最后一条消息之后，因此不能只检查 head Entry 类型。运行时沿当前 path 反向查找最后一个有效消息/Compaction：

- 最后有效消息为 USER 或 TOOL：需要 Provider。
- 最后有效消息为 ASSISTANT：不需要 Provider。
- 最新有效 Compaction 尚未产生 Assistant：需要 Provider。
- 只有配置或无消息：不需要 Provider。

CUSTOM_MESSAGE 以其投影角色参与判定。

## 12. Provider Context 与 orphan ToolCall

从任意 durable Tree Message 分支时，路径可能包含无完整 ToolResult 的 Assistant ToolCall。新 Thread 不复制或重新执行源 Thread 的 ToolInvocation。

Provider 投影在消息角色变化或 Context 结束前，为每个缺失结果的 ToolCall 插入 synthetic error ToolResult：

```text
No result provided
```

Synthetic result 只存在于 Provider request，不写 Session Entry、不创建 ToolInvocation、不触发副作用。Errored/aborted Assistant 覆盖层不进入 Provider Context。

## 13. 多节点 lease 与 fencing

`tryAcquire` 仅在以下条件成功：

```text
status in (RUNNING, WAITING, RETRYING)
and (processor_token is null or processor_until <= now)
```

Processor 使用随机不可复用 token，并按 `lease / 3` heartbeat renew。所有 Entry、Input、Thread status、Tool prepare/apply、Usage 和 Processor Event 写入均在 Thread 行锁下校验 token。

Stop、其他节点 acquire 或 lease 过期后，旧 token 的任何终态提交必须失败并回滚。进程内 `inflight` map 只用于减少重复调度，不参与正确性。

## 14. Stop

```http
POST /api/threads/{threadId}/stop
```

请求携带稳定 `clientRequestId`。Stop 是 Thread 级共享操作，不区分消息来源客户端。

事务：

1. 查询既有 `(threadId, clientRequestId)` Stop 回执并幂等返回。
2. 锁 Thread 行。
3. 创建 Stop 回执。
4. 按 sequence 查询全部 `QUEUED` Input。
5. 把全部 queued inputs 标记为 `CANCELLED` 并关联 Stop ID。
6. 请求取消 Thread 下全部非终态 ToolInvocation。
7. 清除 processor token/lease，使旧 Processor 立即失去写权限。
8. Thread status 置 `IDLE`。
9. 写 `THREAD_STOPPED` Event。
10. 提交后 best-effort 取消本节点 active Provider handle，并唤醒 Tool worker 观察 cancel request。

响应按 sequence 返回被取消的 USER/CUSTOM 消息文本。配置 Input 同样取消但不回填。调用 Stop 的客户端把返回消息以空行连接，并与 Composer 当前文本合并；重新提交必须使用新的 `clientMessageId`。

跨节点无法保证远端 Provider socket 立即关闭，但 fencing 保证其结果永远不能提交；远端 holder 在下一次 renew 时取消本地 handle。

## 15. Provider 失败与 Retry

Provider、Context、Resource、Interceptor 或 Tool prepare 失败：

1. 写 `ASSISTANT_FAILED` 与 `THREAD_FAILED`。
2. Thread status=`FAILED`。
3. 释放 processor token。
4. 保留全部 queued Input。
5. Recovery 不自动重试 FAILED Thread。

```http
POST /api/threads/{threadId}/retry
```

事务把 `FAILED -> RETRYING`，写 `THREAD_RETRYING`，提交后 kick。RETRYING 必须先偿还失败 Turn；成功后再 Harvest 后续 Queue。

Policy rejection 与不可恢复配置错误也进入 FAILED，不形成后台自旋。

## 16. Tool Call permission

Tool permission 只属于 ToolInvocation，不建立通用用户权限系统。行为对齐 pi-base：

```text
ordered global rules
-> ordered tool-specific rules
-> 最后一个匹配规则生效
-> ALLOW / ASK / DENY
```

- YOLO=true 时直接 ALLOW。
- Bash 无法静态分析时，除显式 DENY 外降级为 ASK。
- ALLOW 创建 `QUEUED` Invocation。
- DENY 创建 deterministic FAILED Invocation 与 error result。
- ASK 创建 `WAITING_APPROVAL` Invocation，并写 `PERMISSION_REQUESTED`。

任意客户端可调用 decision API；Invocation 行 CAS 保证第一条 durable decision 生效，其余返回冲突。若无客户端连接，ASK 持久等待，不静默允许。

Child Session ASK：

1. Invocation 与原始 permission event 归属 Child Thread。
2. 根据 SubagentTask `root_thread_id` 向根 Thread 追加 relay event。
3. 根 UI 使用同一 Invocation ID 决策。
4. `PERMISSION_RESOLVED` 同样镜像到根 Thread。

## 17. Tool 与外部等待

Assistant + ToolInvocation + Usage 在一个事务内提交。只要存在非终态 Invocation，Thread status=`WAITING`，Processor 释放 token。

Tool terminal、Permission decision、Environment callback 和 Subagent report 均：

```text
锁 Thread
-> 锁 Invocation/Task
-> durable transition + events
-> 必要时 Thread status=RUNNING
-> commit
-> kick(threadId)
```

固定锁序：

```text
非锁 peek -> Thread -> Invocation / Task -> Input（按需）
```

## 18. Subagent

Subagent Task 创建独立 Child Session 和独立 Main Thread，不在父 Session Tree 建 Branch。

```text
Parent Thread
  -> Task ToolInvocation
     -> SubagentTask
        -> Child Session
           -> Child Main Thread
```

Child Thread 使用同一 Processor、Queue、Tool、Stop、Retry 和 Event 机制。`maxTurns`、`maxDepth`、`maxDirectSubagents`、`maxTotalSubagents` 在 beginTurn 与 Task 创建事务中验证。

Child 成功/失败后写 report，终结父 ToolInvocation，并 kick Parent Thread。取消只阻止后续 Turn admission；已开始的外部副作用按 Tool cancel 语义 best-effort 收敛。

## 19. Thread Event 与 SSE

核心事件：

```text
thread_started
thread_running
thread_waiting
thread_idle
thread_failed
thread_retrying
thread_stopped
input_applied
input_cancelled
turn_started
assistant_started
assistant_delta_batch
assistant_completed
assistant_failed
tool_prepared
tool_started
tool_delta_batch
tool_completed
tool_results_applied
permission_requested
permission_resolved
subagent_started
subagent_completed
```

SSE：

```http
GET /api/threads/{threadId}/events/stream?afterEventId={cursor}
```

- event name：`thread_event`。
- SSE id：全局十进制 `eventId`。
- cursor 取 query 与合法 `Last-Event-ID` 的较大值。
- 建连先重放 durable events，再监听未来轮询结果。
- SSE 断线不改变 Thread 执行和所有权。

## 20. HTTP API

### Session

```text
POST /api/sessions
GET  /api/sessions
GET  /api/sessions/{sessionId}
GET  /api/sessions/{sessionId}/entries
GET  /api/sessions/{sessionId}/threads
POST /api/sessions/{sessionId}/threads
```

### Thread

```text
GET  /api/threads/{threadId}
GET  /api/threads/{threadId}/entries
GET  /api/threads/{threadId}/inputs
GET  /api/threads/{threadId}/events
GET  /api/threads/{threadId}/events/stream
POST /api/threads/{threadId}/messages
PUT  /api/threads/{threadId}/agent
PUT  /api/threads/{threadId}/model
PUT  /api/threads/{threadId}/toolset
PUT  /api/threads/{threadId}/yolo
POST /api/threads/{threadId}/stop
POST /api/threads/{threadId}/retry
```

提交类接口成功返回 `202`；创建 Session/Thread 返回 `201`。Malformed 参数返回 `400`，不存在返回 `404`，状态、幂等 payload 或引用冲突返回 `409`。

## 21. 前端

路由：

```text
/sessions
/sessions/:sessionId
/sessions/:sessionId/threads/:threadId
```

- `/sessions/:sessionId` 加载 Session 后打开稳定 `mainThreadId`。
- 显式 Thread URL 优先，不使用全局 last-active Thread。
- Session 面板显示 Main Thread 与 Secondary Threads，Main 固定置顶。
- 切换 Thread 只切换 REST/SSE 投影；其他 Thread 继续后台运行。

Thread 页面事实：

```text
Transcript = root->head Entries
           + QUEUED USER/CUSTOM Inputs
           + active ThreadEvents
```

启动顺序：

1. 获取 Thread、Entry path、Inputs、ToolInvocations。
2. 分页获取 ThreadEvents 到末页。
3. 以最后 eventId 建立 SSE。
4. Durable Entry 到达后抑制相同 `subjectEntryId` 的 overlay。

Stop 成功后把 `restoredMessages` 以 `\n\n` 合并到当前 Composer，并重置客户端消息幂等 ID。

## 22. Recovery

主路径只由 durable commit 后 kick。默认每 30 秒、batch 100 的低频 Recovery 查询：

- RUNNING/RETRYING 且无有效 token。
- token 已过期。
- WAITING 下存在 due/cancelled/terminal Tool work。
- 已终态 Tool Result 尚未应用。

Recovery 不选择：

- IDLE。
- FAILED。
- 纯 WAITING_APPROVAL 且没有新 durable work。

## 23. Extension

`SessionContextBuilder` 继续按固定顺序执行默认 fold 和 Host `ContextTransform`。Provider、Tool、Compaction 与 Lifecycle hooks 均保持：

- 修改型 Hook 按 Host 顺序串行。
- Lifecycle Observer 只在 durable transition 提交成功后发布。
- Observer 失败隔离，不参与恢复。
- Tool permission boundary 位于普通 BeforeToolCall hooks 之后。

## 24. 验证要求

后端必须覆盖：

- Session/Main Thread 原子创建与回滚。
- 多客户端 Input sequence、幂等与 cutoff 并发。
- steer-all 多消息单 Turn、配置-only 无 Turn。
- Stop 幂等、队列取消、草稿恢复、stale processor fencing。
- FAILED/RETRYING 不 Harvest 后续 Queue。
- Tool WAITING/decision/terminal 与锁序。
- Subagent Child Main Thread、root permission relay。
- 任意 Tree Message 分支与 orphan ToolCall synthetic result。
- lease 过期、lost kick 与低频 recovery。

前端必须覆盖：

- Session 默认 Main Thread。
- Thread 列表与切换。
- Tree filters 和 USER/其他 Entry 分支行为。
- pending Input、SSE overlay 与 durable Entry 去重。
- Stop 回填 Composer。
- Snowflake 字符串 cursor。

全量 coverage statements / branches / functions / lines 均不得低于 80%；Thread 创建、Harvest、Stop、Retry、Tool permission 和 Subagent 核心路径目标行覆盖率不低于 90%。
