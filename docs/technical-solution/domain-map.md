# 领域词汇与双域映射

本文是前后端共用的当前领域词汇事实源。

## 1. 产品域与依赖

| 域 | 代码位置 | 职责 |
| --- | --- | --- |
| Harness / AI | `harness-tool`、`harness-runtime`、`harness-runtime-spring`、`harness-daemon`、`core.ai`、`features/ai` | Catalog、Chat、Session、Entry Tree、Thread、Command、Model/Tool Invocation、Work |
| Studio / Canvas | `studio`、`core.studio`、`features/canvas` | Canvas document、node、link、command dedup |

```text
frontend
  -> web
    -> core
    -> harness-runtime-spring -> harness-runtime -> harness-tool
    -> harness-runtime
core -> harness-runtime -> harness-tool
core -> harness-tool
  -> share
harness-daemon -> harness-tool
```

`harness-runtime` 不依赖 Spring、MyBatis、Harness Tool 实现或 Web；`harness-*` 不依赖 `studio`。

## 2. Catalog 词汇

| 概念 | 含义 |
| --- | --- |
| Provider | 以 immutable `name` 标识的当前连接配置（行内更新覆盖；硬删除后同名可重建） |
| Model | 以 `(providerName, name)` 标识的模型配置 |
| Agent | 以 immutable `name` 标识的系统提示、Model/Variant 与 tools/skills 配置 |
| Model ref | `providerName/modelName`；解析只切第一个 `/` |
| Variant | Model config 中的 variant `id`；Agent 可指定覆盖值 |
| ToolCatalog | 只有 `PLATFORM` / `ENVIRONMENT` 两类产品级 Tool 的目录；selectable 含 Goal 工具（`create_goal`/`get_goal`/`update_goal`，GoalStore 条件装配）；内部 Platform Tool 与可选择目录分离，`load_skill` 是唯一 internal name |

Catalog 没有 bigint resource ID。Catalog 的版本仍作为并发更新 token 以十进制字符串暴露。Provider/Model/Agent 都是带 `expectedVersion` CAS 的硬删除：记录存续期间名称不可修改，删除后同名立即可重建（重建行 version 从 0 重新开始）；旧名称引用在删除到重建之间 fail closed，重建后解析到当前同名资源。

## 3. Harness 词汇

| 概念 | 含义 |
| --- | --- |
| Chat | 保存唯一可见发送设置 `agentName`、`yoloEnabled` 的持久对象 |
| Session | append-only Entry Tree 的边界 |
| Entry | 语义持久事实：`ROOT`、`TURN_START`、`MESSAGE`、`CUSTOM_MESSAGE`、`ASSISTANT_ERROR`、`ASSISTANT_ABORTED`、`TURN_END` |
| BranchSettings | Entry 分支的完整不可变设置快照（environmentId/agentName/model/thinkingLevel/activeTools） |
| HarnessThread | durable 字段只有 `headEntryId`、`yoloEnabled`、`nextCommandSequence`、`revision` 与时间；Session/Environment/status 由 head Entry 分支派生 |
| ThreadCommand | 有序 mailbox，八类：`USER_MESSAGE` / `CUSTOM_MESSAGE` / `SET_ENVIRONMENT` / `SET_AGENT` / `SET_MODEL` / `SET_THINKING_LEVEL` / `SET_ACTIVE_TOOLS` / `SET_YOLO` |
| ModelInvocation | 一次冻结 `ModelInvocationRequest`（route/provider/tools/skills/YOLO）的 Provider 调用 |
| ToolInvocation | 按冻结 ToolBinding 执行的一次 ToolCall durable 事实（approval/status/result）；partial 只进入 Redis realtime projection |
| Work | 唯一调度 mailbox：`(target_type, target_id)` 的 `available_at`/`wake_version`/lease |
| ThreadGoal | Core application-owned 的 per-Thread Goal（`agent_thread_goal` 表，无 `harness_` 前缀，**不是** Runtime 第 8 表）；经 selectable Platform tools `create_goal`/`get_goal`/`update_goal` 读写 |
| Resource | Tool Result 中 Text/Json ≤8KB 保持 inline ToolContent；超过阈值或 Binary 转为 canonical 引用 `{uri, mediaType, name, size, sha256}`（外部 `file:` URI） |
| Environment | 已绑定 Daemon 的服务器内存资源，以 canonical `environmentId`（lowercase UUID）唯一，状态为 CONNECTING/READY；name 只展示，只有 READY 可运行 |
| Realtime projection | Redis Streams 中有界、可丢失的输出覆盖层（非 durable） |

Agent 的 tools/skills 决定本次运行能力；每次 turn 通过 `DatabaseTurnResolver` 从 `BranchSettings` 读取最新 Agent、Provider、Model、ToolCatalog 与 Environment route（Agent/Model 修改下一 turn 生效）。每次 Model attempt 由 Core 按 `providerName` 重新读取当前 `agent_provider` 行（providerType/baseUrl/credential/config），以当前 `ProviderFactory` 构造短生命周期 attempt-local Provider；当前行缺失时 fail closed，同名重建后解析到新行。fail closed：**非 null `environmentId` 无论 activeTools 都必须 registry 命中且 READY**；**null `environmentId` 只允许 platform-only 且无 skills 的 turn**——ENVIRONMENT tool 或 Agent skill 均确定性拒绝，绝不静默省略；skills 必须由选中 READY Environment 精确提供且 `activeTools` 显式包含 `load_skill`。所有确定性拒绝共用 `PLANNING_FAILED` code，写成 `ASSISTANT_ERROR` barrier。

## 4. Chat、Thread 与前端映射

| 前端对象 | 服务端事实 |
| --- | --- |
| Chat 卡片 | `ChatDTO`：标题、Agent/YOLO 可见设置、版本 |
| Chat 工作区 | `localStorage` 中的八个 Pane 槽位 |
| Pane | 本地 `threadId` 绑定；服务端通过 Chat↔Thread 历史关系聚合 |
| Blank pane | 本地 `BranchDraft`（frozenDraft）物化自 Chat defaults + Catalog |
| Bound pane | `branchState` 从 snapshot `branchSettings` 初始化；queued SET_* 投影 `effectiveBase` |
| Composer | 每次发送构造 SET_* diff batch + `USER_MESSAGE`（不携带 role） |
| Thread transcript | `HarnessThreadSnapshotDTO` 的 entries（root-to-head）、queuedCommands 与活跃 Invocation |
| `/tree` | 选择历史 Entry 后调用 `PUT /api/ai/runtime/threads/{threadId}/head`（同 Session） |
| Footer | 依据 pane 本地 draft 与 Catalog 展示 agent/model/environment 标签 |
| Approval | `POST /tool-invocations/{id}/approval`，输入 `ALLOW`/`DENY` |
| Stop | `POST /stop`，`stopRequestId` + `expectedRevision`，三态结果 |

## 5. Canvas 词汇

| 概念 | 含义 |
| --- | --- |
| CanvasDocument | 画布身份、标题与 revision |
| CanvasNodeKind | `RESOURCE` / `FUNCTION` |
| CanvasLink | 同一 Canvas 内的可见性边 |
| CanvasCommand | 带 `commandId` 与 `baseRevision` 的幂等命令 |

当前可持久化的 Function 节点是 `system.generate-text` v1；前端 demo 节点在构造时直接携带对应的 `domainKind`。

## 6. API 边界

| API | 当前职责 |
| --- | --- |
| `GET/POST /api/ai/catalog/providers` | Provider 分页查询与创建 |
| `GET/POST /api/ai/catalog/models` | Model 分页查询与创建 |
| `GET/POST /api/ai/catalog/agents` | Agent 分页查询与创建 |
| `GET/POST /api/ai/chat` | Chat 列表与创建 |
| `GET /api/ai/chat/{chatId}/threads` | Chat-scoped Thread 全量列表（按关联时间从新到旧） |
| `POST /api/ai/chat/{chatId}/threads` | 原子创建 Session、ROOT、Thread 并关联 Chat |
| `GET /api/ai/runtime/threads/{threadId}/snapshot` | 单一 Thread 一致投影 |
| `POST /api/ai/runtime/threads/{threadId}/commands` | 原子命令 batch 入队（八类），202 |
| `PUT /api/ai/runtime/threads/{threadId}/head` | 同 Session 非空 head 重定位（revision CAS） |
| `POST /api/ai/runtime/threads/{threadId}/stop` | stopRequestId + revision CAS |
| `POST /api/ai/runtime/threads/{threadId}/tool-invocations/{id}/approval` | Tool approval 决定 |
| `GET /api/ai/runtime/threads/{threadId}/events/stream` | durable revision SSE + realtime overlay |
| `GET /api/ai/catalog/tools` | Agent 可选择的 Platform/Environment ToolCatalog |
| `GET /api/ai/environment` | 当前 live Environment 内存投影（含 CONNECTING/READY status） |
| WebSocket `/api/ai/environment/daemon/v2` | Daemon v2 连接 |

**不存在**的 API：无全局 Thread 列表、无 Session/Usage/settings/artifacts/interactions 端点、无 `/messages` 或 `/messages/custom`（消息由 `/commands` 命令表达）、无 `expectedExecutionEpoch`。

## 7. 一句话实现

Harness 以 Session Entry Tree 记录语义事实（TURN_START/MESSAGE/TOOL/TURN_END），以 head 非空的 Thread 记录执行控制面（revision CAS + 命令 mailbox），以 `BranchSettings` 驱动逐轮 Catalog/Environment 解析并冻结请求，以 Model/Tool Invocation + Work 支持恢复；Studio 独立承载 Canvas。
