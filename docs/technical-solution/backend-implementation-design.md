# 后端落地设计

本文描述当前 `share`、`core`、`web`、Harness Runtime 与受信任插件的后端边界。`harness-runtime` 拥有纯 Java 领域状态机；`harness-plugin` 提供构建期注册、启动时冻结的插件 API；`harness-runtime-spring` 只做 Store/Work/Redis 适配；`core` 提供 Catalog、TurnResolver、Model/Tool Gateway、Environment 与 Chat 应用能力；Goal 由 `plugins/goal` 提供；`web` 是生产组合根并映射 HTTP/WebSocket。

## 1. 分层

```mermaid
flowchart LR
    Client[Browser / Daemon]
    Web[web controllers]
    Core[core application services / adapters]
    RuntimeSpring[harness-runtime-spring]
    Runtime[harness-runtime]
    Store[(PostgreSQL / Redis / S3)]

    Client --> Web
    Web --> Core
    Web --> RuntimeSpring --> Runtime
    Web --> Runtime
    Core --> Runtime
    Core --> Store
```

| 层 | 职责 |
| --- | --- |
| `share` | DTO、JSON 字段、分页与错误边界 |
| `web` | 生产组合根、Runtime/dispatcher/listener 生命周期、路由、参数校验、HTTP 状态、浏览器事件 WebSocket adapter、Daemon WebSocket v2 adapter |
| `core.ai.catalog` | Provider/Model/Agent 的名称身份、结构化 config 与版本并发 |
| `core.ai.chat` | Chat CRUD、`agentName`/可空默认 `EnvironmentBinding{name, workspacePath}`/`yoloEnabled` 可见发送设置与 Chat↔Thread 关系 |
| `core.ai.runtime` | `DatabaseTurnResolver`、`CoreModelGateway`/`CoreToolGateway`、`ToolResultExternalizer`、Environment registry/gateway、query 投影 |
| `harness-plugin` | `PluginCatalog`、`BranchView`、同步 `PluginTool`、state access 声明、intent、context projector 与提示词模板 |
| `plugins/goal` | Goal v2 工具、`goal/state` 完整快照 codec 与 active context projector |
| `harness-runtime-spring` | `HarnessStore`（PostgreSQL）、Work dispatcher、Redis overlay、瞬时 `LocalFileResourceStore` |
| `harness-runtime` | Thread/Command/Invocation/Work 状态机与 Thread/Model/Tool processor |
| `harness-tool` | Tool API、descriptor、`ResourceRef`、RemoteTool 与 Daemon v3 wire |

## 2. 身份与公开数据

Runtime 实体 durable ID 是 `UUID`，在 HTTP 中编码为 canonical UUID strings；HTTP response DTO 的 Java `long`/`Long` 统一编码为 canonical decimal strings，Runtime 请求 mapper 对 cursor/CAS 字段按正数或非负数领域约束严格解析。Catalog 不使用 bigint resource ID：

- Provider 和 Agent 的 identity 是 immutable `name`，Model 的 identity 是 `(providerName, name)`；记录存续期间名称不可修改。
- Provider/Model/Agent 都是带 `expectedVersion` CAS 的硬删除（物理删行）：删除后同名立即可重建，重建行 `version` 从 0 重新开始。
- 所有名称必须非空、拒绝首尾 Unicode whitespace；Provider/Agent 名称还必须是单路径段，禁止包含 `/`。
- API Model ref 是 `providerName/modelName`，只在第一个 `/` 处切分，因此 Model 名称可以包含 `/`。
- Catalog version 是独立的并发 token，以十进制字符串传输。

Agent DTO 的 `model` 使用 Model ref；Model DTO 使用 `providerName` 与 `name` 两个字段。Provider、Model、Agent 的 PUT/DELETE 都用名称定位并携带 `expectedVersion`。

## 3. Composition root

生产组合根位于 `web`；它把 Core ports 与 runtime-spring adapters 装配成完整 Runtime。Core 的 Spring beans 只提供应用能力与端口适配，不写 `harness_*` 表：

| 配置 | 装配 |
| --- | --- |
| `web.runtime.HarnessRuntimeConfiguration` | 构造 PostgreSQL Store、Redis sink/tail、ResourceStore、Thread/Model/Tool Processor、`HarnessRuntime`、dispatcher/listener/executor；注入 Core 的 TurnResolver/ModelGateway/ToolGateway ports |
| `HarnessRuntimeLifecycle` | 启动/停止 dispatcher 与 processor；REST 与事件通道只经 `HarnessRuntime` 门面 |
| `ModelExecutionConfiguration` | `ObjectProvider<ProviderFactory>` 收集并索引；`CoreModelGateway`（serialized FIFO 单 drainer 回调桥） |
| `web.runtime.BuiltInPluginConfiguration` | 在生产组合根注册随应用交付的受信任 `GoalPlugin` |
| `PluginCatalogConfiguration` | 收集全部 `HarnessPlugin` beans，构造并冻结 `PluginCatalog`；Core 不依赖具体插件实现 |
| `HarnessToolGatewayConfiguration` | `ObjectProvider<ToolFactory>` + `PluginCatalog` + `PluginBranchViewLoader`；`CoreToolGateway`（preflight + 两阶段激活 + FIFO 回调桥 + intent 校验 + `ToolResultExternalizer`） |
| `RuntimeToolsConfiguration` | 装配两个内部 Platform Tool `load_skill` 与 `task`（含 `SubagentConfig`、并发 reservation/活动 descendant relay 共用的 `SubagentRunRegistry`、子 Agent 执行线程池）；把本地 `ToolFactory` descriptor 与冻结插件贡献合并为 `ToolCatalog`，按插件 visibility 维护 selectable/internal 名称 |
| `HarnessRuntimeWebMapper` | canonical UUID/decimal/JSON 校验：DTO → 领域命令 |

## 4. HTTP API

### Catalog

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET/POST | `/api/ai/catalog/providers` | Provider 分页查询/创建 |
| PUT/DELETE | `/api/ai/catalog/providers/{name}` | Provider 全量更新/按版本删除 |
| GET/POST | `/api/ai/catalog/models` | Model 分页查询/创建 |
| PUT/DELETE | `/api/ai/catalog/models?providerName=&modelName=` | Model 全量更新/按复合名称删除 |
| GET/POST | `/api/ai/catalog/agents` | Agent 分页查询/创建 |
| PUT/DELETE | `/api/ai/catalog/agents/{name}` | Agent 全量更新/按版本删除 |
| GET | `/api/ai/catalog/tools` | Agent 可选择的 Platform/Environment ToolCatalog |

### Chat 与 Thread

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET/POST | `/api/ai/chat` | Chat 列表/创建 |
| GET/PUT/DELETE | `/api/ai/chat/{chatId}` | Chat 读取/部分设置更新/按版本删除 |
| GET | `/api/ai/chat/{chatId}/threads` | Chat 关联的全部 Thread，按关联时间从新到旧 |
| POST | `/api/ai/chat/{chatId}/threads` | 原子创建 Session、ROOT（BranchSettings）、Thread 并关联 Chat；返回 snapshot |
| PUT | `/api/ai/chat/{chatId}/threads/{threadId}` | 幂等建立历史关联 |
| GET | `/api/ai/runtime/threads/{threadId}/snapshot` | revision、entries（root-to-head）、queuedCommands、活跃 Invocation 与尚未物化的 Model attempt failures |
| GET | `/api/ai/runtime/threads/{threadId}/entries` | Thread 所属 Session 的完整 immutable Entry Tree，包含非当前 head 的历史分支 |
| POST | `/api/ai/runtime/threads/{threadId}/commands` | 原子命令 batch 入队（7 类命令），202 |
| PUT | `/api/ai/runtime/threads/{threadId}/head` | 同 Session 非空 head 重定位（revision CAS） |
| POST | `/api/ai/runtime/threads/{threadId}/stop` | `{stopRequestId, expectedRevision}`；STOPPED/IDLE/REPLAYED |
| POST | `/api/ai/runtime/threads/{threadId}/tool-invocations/{toolInvocationId}/approval` | `{decision: ALLOW|DENY, decisionId, actor, reason}` |
| WebSocket | `/api/events/v1` | 浏览器事件通道（thread/canvas 订阅；协议见 [application-event-channel.md](application-event-channel.md)） |
| GET | `/api/ai/runtime/resources/{sha256}` | 瞬时/Invocation `ResourceRef` 兼容下载：Core `ManagedResourceDownloadService` 按 `mediaType`/`size`/可选 `name` 重建引用，Web 只负责 attachment + `X-Content-Type-Options: nosniff`；Entry history 的 Blob Resource 不走该端点 |
| POST/DELETE | `/api/storage/uploads[/{uploadId}]` | 全局 Upload reserve、complete 与释放；READY Handle 供 `ATTACHMENT(uploadId)` 原子消费 |
| GET | `/api/storage/blobs/{blobId}/presigned-original|presigned-preview` | durable Blob Resource 的渲染期短期 URL；原件响应额外携带权威 `mediaType/sizeBytes` |

**不存在**的 API：无全局 Thread 列表、无 Session/Usage/settings/artifacts/interactions 查询、无 `/messages` 或 `/messages/custom` 端点（消息由 `/commands` 的 `USER_MESSAGE`/`CUSTOM_MESSAGE` 命令表达）、无 `expectedExecutionEpoch` 字段。

### Environment

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| GET | `/api/ai/environment` | 当前 live Environment 内存投影（name/status/ready/tools/skills/mcpServers/lastSeen，status 可为 CONNECTING/READY；不公开 READY operatingSystem/timeZone/note metadata） |
| WebSocket | `/api/ai/environment/daemon/v2` | Daemon v3 连接（HELLO/WELCOME/READY/INVOKE/回调/心跳） |

## 5. 请求与错误

`POST /commands` 请求包含 `expectedHeadEntryId`、`expectedNextCommandSequence` 与命令数组（每项 `clientCommandId`）。`PUT /head` 包含非空 `targetEntryId` 与 `expectedRevision`。`POST /stop` 包含 `stopRequestId` 与 `expectedRevision`。

统一行为：

| 情况 | HTTP |
| --- | --- |
| DTO、名称格式、Model config、Model ref、id/sequence/revision 格式非法（实体 id 非 canonical UUID、sequence/revision 非 decimal string）或请求体中的 Catalog 引用非法 | 400 |
| 作为请求目标的 Thread/Entry/Catalog 名称不存在（snapshot/entries/commands/head/stop 路径） | 404 |
| 命令 cursor / revision CAS 过期、Thread 非 quiescent、terminal apply pending、跨 Session move、ordered replay 冲突 | 409 |
| approval target 不存在 / 不属于本 Thread / 无 required approval / 不在适用上下文（`APPROVAL_NOT_APPLICABLE`）、已决定但请求不匹配（`APPROVAL_DECISION_MISMATCH`） | 409（approval 路径的 Thread/target 缺失不是 404） |
| 命令 batch 被接受进入 mailbox | 202 |
| Chat-scoped Thread 创建成功 | 201 |

HTTP 错误支持 `en-US` 与 `zh-CN`，稳定错误码、状态和结构化字段不随语言变化。

## 6. 应用服务与写者

| 组件 | 职责 |
| --- | --- |
| `ChatThreadServiceImpl` | 调用 `HarnessRuntime.createThread`（Session/ROOT/Thread 原子）并写入 Chat 关系 |
| `StudioHarnessThreadController` | 仅映射 `HarnessRuntime` 门面 + typed 异常翻译 |
| `HarnessRuntime` | `createThread`/`enqueueCommands`/`moveHead`/`stop`/`decideToolApproval`/`getThreadSnapshot` 单事务控制面 |
| `ThreadProcessor` | Agent Loop：Model terminal apply 前按序物化失败 attempt、continuation、INPUT turn、QUIESCENT |
| `ModelProcessor` | 两阶段激活、checkpoint/failedAttempts/terminal 持久化、terminal-once、Thread revision touch、Work/realtime 与 reschedule；不写 Entry/head |
| `ToolProcessor` | 两阶段激活、preflight、接收已验证/外部化的 terminal `ToolSuccess(result, effects)`、领域校验与严格 terminal CAS，并维护 Thread revision/Work/realtime；不写 Entry/head |
| `DatabaseTurnResolver` | 以 candidate path + YOLO 解析冻结 `ModelInvocationRequest`（含 `subagentBindings`）；每个新 turn 从最新 Agent config 派生 tools/skills/subagents，历史 activeTools 不参与能力计算；冻结插件 contribution/state accesses，并注入插件 context projection；普通解析按单一 Clock instant 构造 `CurrentEnvironmentContext`；ENVIRONMENT 工具按最新 binding 绑定、规划不拒绝；skills 非空时派生 `load_skill` 且要求 Environment live；subagents 非空且 depth < maxDepth 时派生 `task`；planning 拒绝共用 `PLANNING_FAILED` |
| `AgentPromptComposer` | 组合 system prompt 的唯一边界：Agent 正文 → `<current_environment>`（只输出有值的 name/workspace/system/date/note，`none` 整行省略）→ `available_skills`（skills 非空时）→ `available_subagents`（subagents 非空时，含 task 指令）；动态字段 XML escape，日期严格为 yyyy-MM-dd；prompt 模板是 strict classpath resource（Pi 派生资源同目录保留 MIT `NOTICE`） |
| `TaskTool` | 内部 `PLATFORM` Tool（rendererKey=task、NON_IDEMPOTENT）：校验冻结 allowlist、以 `createThread(SubagentContext)` 创建/恢复子 Thread、物化子 Agent branch settings、入队 task prompt、轮询等待并发布 `task.status` 心跳、以 `<task id state>` envelope 结束 |
| `AgentBranchSettingsMaterializer` | 按最新 Agent/Model catalog 物化子 Agent 完整 `BranchSettings`：`activeTools = config.tools + skills 非空时 load_skill + subagents 非空且 depth < maxDepth 时 task` |
| `SubagentRunRegistry` | 进程内并发 reservation（每父/每根上限、resume 单飞）；不是 durable truth，durable 子 Session 仍是 Thread/Entry |
| `HarnessWorkDispatcher` | Work-only claim、round-robin、bounded handoff、NOTIFY/poll 合并 |

### Subagent 委派（task）运行语义

`task` 是内部 `PLATFORM` Tool（`ToolDescriptor` 冻结 `rendererKey=task`、`ToolSideEffect.NON_IDEMPOTENT`）。Model 调用后 `TaskTool.execute` 在独立虚拟线程执行：

- 参数 `{subagent_type, prompt, maxTurns?, session_id?}` 严格校验；`subagent_type` 必须命中父 Invocation 冻结的 `subagentBindings`（执行绝不重读父 Agent 配置）。
- 新建时按 `AgentBranchSettingsMaterializer` 物化子 Agent branch settings（继承父 branch 的完整 `EnvironmentBinding` 与父 Thread 的 YOLO），以 `createThread` 原子创建 Session + ROOT（`SubagentContext{parentThreadId, rootThreadId, taskInvocationId, depth}`）+ Thread（yolo 继承父 Thread），随后一个原子 batch 入队 settings diff + `USER_MESSAGE`（task prompt）；`clientCommandId` 以 `task-{invocationId}-{ordinal}` 稳定生成。
- 恢复（`session_id`，子 ThreadId）：要求 ROOT 的 parent/root 归属与当前父一致、子 Thread quiescent（无 model/tool siblings/queued、head 非 continueModel TURN_END）；目标 Agent 可在恢复时切换。
- 轮询等待期间：以 durable snapshot 指纹判定活动（idle 超时排除 active tool 时间）；约 1s 一次发布非 durable `TOOL_PARTIAL` 心跳（完整 JSON 快照，`details.kind=task.status`：threadId/subagentType/state/depth/turns/toolCalls/lastActivity/approvals/descendants）。`descendants` 由进程内活动 registry 扁平 relay 给祖先，仅用于实时展示和审批寻址；`turns >= maxTurns` 起每 5 turn 入队一条 SYSTEM `CUSTOM_MESSAGE` 软提醒；达到 idle 超时/被取消时 `stop` 子 Thread 并保留可恢复 Session。
- 终态 ToolResult 为 `<task id state>` envelope（`<task_result>` / `<task_error>`，报告正文最多保留 8000 字符），`details.kind=task.result`；`state` 为 `completed` / `error` / `cancelled`。

配置（`HarnessRuntimeProperties` → `SubagentConfig`）：`subagent-max-depth=2`、`subagent-max-concurrency=10`（每父）、`subagent-max-total-concurrency`（每根，默认不限）、`subagent-idle-timeout=0`（默认禁用）、`subagent-max-turns=50`、`subagent-poll-interval=100ms`。`SubagentRunRegistry` 只做进程内并发 reservation（每父/每根上限、resume 单飞），进程重启后仅由 durable Thread 恢复。

权限保持既有管线：YOLO 在加载 settings/evaluator 之前直接 Allow；否则 Allow/Ask/Deny，Ask 即既有 ToolInvocation `WAITING_APPROVAL`，不存在第二套权限实体；子 Thread 继承父 Thread YOLO，子工具审批仍走同一 `POST /tool-invocations/{id}/approval` 端点（携带子 ThreadId）。

## 7. Snapshot-first 事件通道

客户端先读取权威 snapshot，再经单条应用级 WebSocket（`/api/events/v1`）订阅资源：

```text
GET /api/ai/runtime/threads/{threadId}/snapshot
-> WS subscribe {version:1, type:'subscribe', resource:{kind:'thread', id}}
```

`subscribed` ack 携带订阅建立瞬间的 durable revision；其后的事件保证送达（事件帧不先于 ack 帧）。`revision` 事件只触发 snapshot invalidate，Redis `realtime` delta 只作为临时 overlay；`resync` 要求整体快照。重连重订阅后重新读取 snapshot，Redis Stream 不承担恢复职责。完整帧协议见 [application-event-channel.md](application-event-channel.md)。

## 8. 代码入口

- [StudioHarnessThreadController](../../web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioHarnessThreadController.java)
- [StudioChatController](../../web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioChatController.java)
- [StudioToolEnvironmentController](../../web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioToolEnvironmentController.java)
- [HarnessRuntime](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)
- [HarnessRuntimeWebMapper](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/HarnessRuntimeWebMapper.java)
- [DatabaseTurnResolver](../../core/src/main/java/fun/fengwk/kkstudio/core/ai/runtime/thread/command/DatabaseTurnResolver.java)
