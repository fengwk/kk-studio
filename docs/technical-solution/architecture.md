# 架构总览

`kk-studio` 是全局单实例产品，由 Harness/AI 与 Studio/Canvas 两个并列产品域组成。两者共享 `web` 入口与部署，但领域事实、状态机、存储和前端 feature 分开。

| 域 | 职责 |
| --- | --- |
| Harness / AI | Chat、Session Entry Tree、Thread 执行、Model/Tool Invocation、命令 batch 与实时投影 |
| Studio / Canvas | Canvas document、Resource/Function、Blob 生命周期、Patch/SSE 与 Harness Thread 绑定 |

浏览器把当前语言通过 `Accept-Language` 发送给 Web；后端只本地化用户可见错误，不改变稳定字段和领域事实。

## 1. 系统拓扑

```mermaid
flowchart LR
    Browser[浏览器]
    AI[frontend/features/ai]
    Canvas[frontend/features/canvas]
    Web[web]
    Core[core]
    Runtime[harness-runtime]
    Plugin[harness-plugin]
    Goal[plugins/goal]
    RuntimeSpring[harness-runtime-spring]
    Tool[harness-tool]
    Daemon[harness-daemon]
    PG[(PostgreSQL)]
    Redis[(Redis)]
    S3[(Object storage)]
    Env[Environment Daemon]

    Browser --> AI
    Browser --> Canvas
    AI --> Web
    Canvas --> Web
    Web --> Core
    Web --> RuntimeSpring
    Web --> Runtime
    Web --> Goal
    RuntimeSpring --> Runtime
    Core --> Plugin
    Goal --> Plugin
    Plugin --> Runtime
    Runtime --> Tool
    Core --> Runtime
    Core --> Tool
    Daemon --> Tool
    Core --> PG
    Core --> Redis
    Core --> S3
    Browser --> S3
    Env <-->|WebSocket v2| Web
```

## 2. 模块边界

| 模块 | 职责 |
| --- | --- |
| `harness-tool` | `Tool`、descriptor、schema、`ResourceRef`、`RemoteTool` 与 Daemon v3 wire |
| `harness-runtime` | **纯 Java 领域模块**：Session/Entry/Thread/Command/Invocation/Work 状态机、Thread/Model/Tool processor、Stop/Approval/fencing；不依赖 Spring、数据库、Redis、HTTP 或 Provider SDK |
| `harness-plugin` | **纯 Java 受信任插件 API**：构建期注册、启动时冻结的 `PluginCatalog`，以及 `BranchView`、同步 `PluginTool`、声明式 `AppendCustomEntry` 与 context projector |
| `plugins/goal` | Goal 插件：`create_goal` / `get_goal` / `update_goal` v2、branch-scoped `goal/state` 快照与 active goal 上下文投影 |
| `harness-runtime-spring` | `HarnessStore` PostgreSQL 适配、`harness_work` dispatcher（claim/NOTIFY/poll）、Redis realtime overlay、本地 Resource store |
| `harness-daemon` | 独立 Environment 进程适配器，只依赖 `harness-tool` |
| `core` | Catalog、全局 Blob Storage、`DatabaseTurnResolver`、Model/Tool Gateway、Environment、Chat 与 Canvas 应用服务；装配并执行受信任插件；Harness 执行表只经 Runtime/Store 端口写入 |
| `web` | **生产组合根**：装配 Runtime、runtime-spring 与 Core ports，管理 dispatcher/listener 生命周期，并提供 HTTP、SSE、WebSocket v2 与静态资源适配 |
| `share` | HTTP DTO 与公开 JSON 结构 |
| `frontend` | React 页面、Pane、本地状态与 API client |

依赖方向：

```text
frontend -> web API / composition root
web -> core
web -> harness-runtime-spring -> harness-runtime -> harness-tool
web -> harness-runtime
web -> plugins/* -> harness-plugin
core -> harness-runtime -> harness-tool
core -> harness-plugin -> harness-runtime
core -> harness-tool
web -> share
harness-daemon -> harness-tool
```

## 3. Catalog 身份

| 资源 | 身份 | 运行时引用 |
| --- | --- | --- |
| Provider | immutable `name` | Agent 通过 Model 的 `providerName` 引用 |
| Model | `(providerName, name)` | Agent 通过 Model ref 引用 |
| Agent | immutable `name` | Chat defaults 与 Entry branch settings 通过 `agentName` 引用 |
| Variant | Model config 中的 `id` | Agent 的可选 `variant` 覆盖 Model 的 `defaultVariant` |
| Tool | 只有 `PLATFORM` / `ENVIRONMENT` 两类 | Agent config 保存可选择 Tool 名称集合；`load_skill`/`task` 是内部 `PLATFORM` Tool，不在可选择目录 |

公开 Model ref 的格式是 `providerName/modelName`。`ModelRef.parse` 只在第一个 `/` 切分，因此 `modelName` 可以包含额外 `/`。Catalog response 只使用名称、结构化 config 和版本字段，不使用 bigint resource ID。

Catalog 只有 `agent_provider`、`agent_model`、`agent_definition` 三张名称资源表。三表都是带 `expectedVersion` CAS 的硬删除：删除后同名立即可重建，重建行 `version` 从 0 重新开始；记录存续期间名称不可修改。所有引用都只按名称：Chat 的 `agentName`、Thread 的 `BranchSettings`、Model 的 `providerName` 都不绑定资源 ID。Agent/Model 修改在下一 turn 由 `DatabaseTurnResolver` 解析最新行生效；每次 Model attempt 由 Core 按 `providerName` 重新读取当前 `agent_provider` 行（providerType/baseUrl/credential/config）形成短生命周期 attempt-local Provider。硬删除到同名重建之间既有名称引用 fail closed（turn 拒绝 / attempt 确定性失败）；重建后既有 Chat/Thread 的名称引用解析到当前同名资源。

## 4. Harness 所有权模型

| 事实 | 当前职责 |
| --- | --- |
| Chat | 保存 `agentName`、可空默认 `environmentName`、`yoloEnabled` 三个可见发送设置，以及标题、版本和时间 |
| Pane | 浏览器 `localStorage` 中的八个固定槽位、布局、焦点和每个槽位的 `threadId` |
| Session | 一棵 append-only Entry Tree 的边界；由 Chat-scoped Thread 创建事务产生 |
| Entry | 对话与运行审计事实，只允许十种 `EntryType`（见 [harness-runtime-architecture.md](harness-runtime-architecture.md)） |
| HarnessThread | durable 字段只有 `headEntryId`、`yoloEnabled`、`nextCommandSequence`、`revision` 与时间；Session/Environment/status 由 head Entry 分支派生 |
| ThreadCommand | 有序 mailbox，只允许七类 command（见 [harness-runtime-contracts.md](harness-runtime-contracts.md)） |
| ModelInvocation | 一次冻结的 `ModelInvocationRequest`（route/provider/tools/skills/subagentBindings/YOLO/contextWindow/可空 compaction metadata）及其状态、attempt-local checkpoint、连续 `failedAttempts` 与 terminal 事实 |
| ToolInvocation | 一次 ToolCall 的冻结 binding、approval、状态、结果与 `effects`；插件 provenance/access 随 binding 冻结，非空 effects 只允许出现在 `SUCCEEDED` 且 terminal immutable |
| SubagentContext | 子 Agent Thread ROOT 上冻结的委派归属 `{parentThreadId, rootThreadId, taskInvocationId, depth}`；task id/session_id 即子 ThreadId（canonical UUID） |
| Work | 唯一调度 mailbox：`(target_type, target_id)` 的 `available_at`/`wake_version`/lease |
| Goal state | `goal` 插件拥有的 branch-scoped 完整快照：`CUSTOM(pluginId=goal, customType=state, schemaVersion=1)`；只取当前分支最近快照，无独立 Goal 表 |
| Live Environment | 已绑定 Daemon 的服务器内存投影，按 canonical `environmentName` 唯一，状态为 `CONNECTING`/`READY`；可用性 = READY + 连接打开 + 心跳未过期 |

Session、Environment、status 与 branch settings 都从 head Entry 分支派生，**不**在 Thread 行上复制；Thread 行不保存 execution epoch、processor lease 或 runnable 标志。

## 5. 创建、发送与执行

`POST /api/ai/chat/{chatId}/threads` 是 Thread 创建入口：一个事务内创建 Session、唯一 ROOT（携带完整 `BranchSettings`）、head 指向 ROOT 的 Thread（`nextCommandSequence=1`、`revision=0`），并写入 Chat↔Thread 关系；创建 API 刻意非幂等（无 createRequestId），每次创建全新 Session/Thread。

发送路径如下：

```text
Blank first send:
  POST /api/ai/chat/{chatId}/threads          -> Session + ROOT + Thread（完整 BranchSettings 已固化）
  POST /api/ai/runtime/threads/{threadId}/commands  -> 原子 USER_MESSAGE-only batch（202）

绑定 Pane 的每次发送:
  构造 SET_* diff batch（ENV→AGENT→MODEL→TOOLS→YOLO）+ USER_MESSAGE
  -> POST /commands（expectedHeadEntryId + expectedNextCommandSequence CAS，202）
  -> ThreadProcessor 收割 queued Commands（全量已存在 id 时是 ordered replay）
  -> TurnPlanBuilder 追加 TURN_START(INPUT) + Message Entries
  -> TurnResolver 读取最新 Catalog/Environment 并冻结 ModelInvocationRequest（fail closed；Agent/Model 修改下一 turn 生效）
  -> ModelProcessor / Provider（每次 attempt 按 providerName 重读当前 agent_provider 行，见 harness-capability-wiring）
  -> 失败 attempt 审计 Entry（0..N）+ Assistant Entry + 可选 ToolInvocation -> ToolProcessor -> Tool Result Entry
  -> TURN_END(COMPLETED, continueModel=true) -> continuation，直到 Model 无 ToolCall
```

`ThreadProcessor` 是执行阶段 Entry/head 的唯一写者；控制面 `HarnessRuntime` 只在 create/move/stop 等同步事务写 Entry/head。`ModelProcessor`/`ToolProcessor` 不写 Entry/head，但会更新各自 Invocation、为可见状态变化 touch Thread revision、维护 Work，并发布 realtime overlay。Model terminal apply 与 Stop 会先把普通 invocation 的 `failedAttempts` 物化为透明 `MODEL_ATTEMPT_FAILURE` Entry，再写唯一 Assistant 结果。插件 Tool terminal success 先由 `CoreToolGateway` 校验 provenance/access 与 intents，再外部化结果，由 `ToolProcessor` 将 `ToolResult + effects` 原子写为 `SUCCEEDED`。正常 apply 与 Stop 共用唯一 `ToolOutcomeAppender`，按 effects 中 CUSTOM 的声明顺序追加后再追加 Tool Result。

## 6. Canvas

Canvas 使用 `CanvasDocument` 聚合：所有业务节点都是 `ResourceNode`，当前内容通过直接 owner 的有序
`Resource[]` 表达，资源生产能力通过可选 Function 表达；Group 与 Link 独立存在，Link target
必须有 Function且允许成环。用户 typed command batch 通过 document 行 `version` CAS 原子提交，并以
`(canvasId, commandId) + requestHash` 幂等。Function start 与 terminal 状态也前进同一 version，
checkpoint 不前进。

媒体 Resource 只引用全局 `storage_blob`；`canvas_resource` 的
`ownerNodeId + resourceIndex` 是成对可空字段，使 Function target 和 pinned orphan 可以暂时无 owner。
`canvas_function_resource_ref` 的 INPUT/OUTPUT pin 只保护 Resource 生命周期，不增加 Blob ref_count。
Canvas 首次 Chat 发送会在同一事务创建并绑定真实 Harness 根 Thread；ordered ATTACHMENT 复用共享 Chat
command use-case 物化为 durable `resource(blobId,name,preview)`。

## 7. Realtime 与恢复

PostgreSQL 是唯一 durable truth，`harness_work` 是 Harness 唯一调度 mailbox（NOTIFY 只是可用性
提示）。Harness Redis Streams 只保存有界 realtime overlay；浏览器先读取 REST snapshot，再以 durable
`revision` 打开 SSE。

Canvas 使用同一原则：PostgreSQL 实体与 `canvas_document.version` 是事实源，Redis Stream 只保存事务
提交后的 bounded Patch Cache，PostgreSQL `NOTIFY canvas_version` 只唤醒 SSE hub。`/changes` 仅在
cache 覆盖连续版本时返回 Patch；任何 gap、损坏或 Redis 不可用都回退权威 Snapshot。

## 8. Subagent 委派（task）

`task` 是内部 `PLATFORM` Tool（`rendererKey=task`、`NON_IDEMPOTENT`）：Model 调用后，Core 的 `TaskTool` 以普通 durable Harness Thread 创建子 Agent Session，复用现有 ToolInvocation/approval/stop/Work 与 Thread 状态机——**没有新表、新状态机或新调度器**。关键事实：

- 子 Thread ROOT payload 携带可选 `subagentContext {parentThreadId, rootThreadId, taskInvocationId, depth}`；`task` 的 id/session_id 是子 ThreadId（canonical UUID）。
- 委派权限在父 ModelInvocationRequest 冻结为 `subagentBindings`（Agent 名称 + 描述 allowlist）；执行绝不重读父 Agent 配置扩权。子 Agent branch settings 由 `AgentBranchSettingsMaterializer` 按最新 catalog 物化（`activeTools = config.tools + skills 非空时 load_skill + subagents 非空且未达最大深度时 task`）。
- 运行期控制全部是配置（`SubagentConfig`）：`maxDepth`、每父/每根并发上限、idle 超时、`maxTurns` 软预算、轮询间隔；进程内 `SubagentRunRegistry` 只做并发 reservation，不是 durable truth。恢复（`session_id`）要求同 parent/root 归属且子 Thread quiescent；Stop/取消保留可恢复 Session。
- 进度经非 durable Redis `TOOL_PARTIAL` 心跳（约 1s）发布完整 JSON 快照（`details.kind=task.status`：threadId/subagentType/state/depth/turns/toolCalls/lastActivity/approvals/descendants）；`descendants` 是进程内 relay 的扁平活动子树状态，使根 Thread 可直接处理任意深度审批，且不参与调度或终态判定。前端整帧替换而非增量合并。最终 ToolResult 是 `<task id state>` envelope（`<task_result>` / `<task_error>`），`details.kind=task.result`。

详细契约见 [harness-runtime-architecture.md](harness-runtime-architecture.md)、[harness-runtime-contracts.md](harness-runtime-contracts.md) 与 [harness-storage-runtime.md](harness-storage-runtime.md)。
