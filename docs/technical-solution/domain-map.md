# 领域词汇与双域映射

本文是前后端共用的当前领域词汇事实源。

## 1. 产品域与依赖

| 域 | 代码位置 | 职责 |
| --- | --- | --- |
| Harness / AI | `harness-tool`、`harness-runtime`、`harness-plugin`、`plugins/*`、`harness-runtime-spring`、`harness-daemon`、`core.ai`、`features/ai` | Catalog、Chat、Session、Entry Tree、Thread、Command、Model/Tool Invocation、插件 branch state、Work |
| Studio / Canvas | `studio`、`core.studio`、`features/canvas` | Canvas document、ResourceNode、Resource、Function、Group、Link、typed command |

```text
frontend
  -> web
    -> core
    -> harness-runtime-spring -> harness-runtime -> harness-tool
    -> harness-runtime
core -> harness-runtime -> harness-tool
core -> harness-plugin -> harness-runtime
plugins/* -> harness-plugin
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
| Agent | 以 immutable `name` 标识的系统提示、Model/Variant 与 tools/skills/subagents 配置 |
| Model ref | `providerName/modelName`；解析只切第一个 `/` |
| Variant | Model config 中的 variant `id`；Agent 可指定覆盖值 |
| ToolCatalog | 只有 `PLATFORM` / `ENVIRONMENT` 两类产品级 Tool 的目录；`RuntimeToolsConfiguration` 合并本地 `ToolFactory` 与冻结 `PluginCatalog` 的贡献，selectable 含 Goal v2 工具；内部 Platform Tool 与可选择目录分离，`load_skill` 与 `task` 是两个 internal name |

Catalog 没有 bigint resource ID。Catalog 的版本仍作为并发更新 token 以十进制字符串暴露。Provider/Model/Agent 都是带 `expectedVersion` CAS 的硬删除：记录存续期间名称不可修改，删除后同名立即可重建（重建行 version 从 0 重新开始）；既有名称引用在删除到重建之间 fail closed，重建后解析到当前同名资源。

## 3. Harness 词汇

| 概念 | 含义 |
| --- | --- |
| Chat | 保存可见发送设置 `agentName`、可空默认 `EnvironmentBinding{name, workspacePath}`（两列同存同空）、`yoloEnabled` 的持久对象 |
| Session | append-only Entry Tree 的边界 |
| Entry | 语义持久事实：`ROOT`、`TURN_START`、`MESSAGE`、`CUSTOM`、`MODEL_ATTEMPT_FAILURE`、`CUSTOM_MESSAGE`、`ASSISTANT_ERROR`、`ASSISTANT_ABORTED`、`COMPACTION`、`TURN_END` |
| BranchSettings | Entry 分支的完整不可变设置快照（environment binding/agentName/model；历史 activeTools 只作投影，不是新 turn 的能力事实） |
| HarnessThread | durable 字段为 `sessionId`、`headEntryId`、`materializationHash`、`yoloEnabled`、`nextCommandSequence`、`version` 与时间；Environment/status 由 head Entry 分支派生 |
| ThreadCommand | 有序 mailbox，六类：`USER_MESSAGE` / `CUSTOM_MESSAGE` / `SET_ENVIRONMENT` / `SET_AGENT` / `SET_MODEL` / `SET_ACTIVE_TOOLS`；YOLO 是 Thread 直接控制面 |
| ModelInvocation | 一次持久化 `basisHeadEntryId + compact ModelRequestSpec`（providerType/model/variant/preamble/tool/skill/subagent bindings/cacheControl）的 Provider 执行；完整 ProviderRequest 每次 attempt 由 Materializer 从 EntryPath + spec 内存重建；`failedAttempts` 保存尚未物化的连续瞬态失败审计 |
| ToolInvocation | 按 `call` + 可空 `binding` 执行的一次 ToolCall durable 事实（approval/status/result/effects）；插件 binding 冻结 owner、contribution 与 state accesses，unknown tool 槽位 binding 为 null，非空 effects 只允许属于 terminal `SUCCEEDED` |
| SubagentBinding | 冻结在父 ModelRequestSpec 中的子 Agent 名称 + 描述 allowlist 快照；task 执行绝不依据后续 Agent 配置扩权 |
| SubagentContext | 子 Agent Thread ROOT 上冻结的委派归属 `{parentThreadId, rootThreadId, taskInvocationId, depth}`；rootThreadId 在整棵委派树中不变 |
| task | 内部 `PLATFORM` Tool（rendererKey=task、NON_IDEMPOTENT）：以普通 durable Harness Thread 运行子 Agent，`task` 的 id/session_id 即子 ThreadId（canonical UUID）；进程内 `SubagentRunRegistry` 只做并发 reservation，不是 durable truth |
| Work | 唯一调度 mailbox：`(target_type, target_id)` 的 `available_at`/`wake_version`/lease |
| Goal state | `goal` 插件拥有的 branch-scoped 完整快照；以 `CUSTOM(goal/state@schemaVersion=1)` 追加，当前分支最近快照生效；状态仅 `active` / `complete` / `blocked` |
| Resource | Tool 边界可产生瞬时 `ResourceRef(uri,mediaType,name,size,sha256)`；写入 Entry history 前摄入全局 Blob，durable message 只保存 `resource(blobId,name,preview)`，Session 通过 `harness_session_blob_ref` 持有引用 |
| Environment | 已绑定 Daemon 的服务器内存资源，以 canonical `environmentName`（bounded 小写路由名称）唯一，状态为 CONNECTING/READY；可用性 = READY + 连接打开 + 心跳未过期 |
| Realtime projection | Redis Streams 中有界、可丢失的输出覆盖层（非 durable） |

Agent 的最新 tools/skills/subagents 决定每个新 turn 的运行能力；`DatabaseTurnResolver` 从 `BranchSettings` 读取 agent/model/environment 引用，再读取最新 Agent、Provider、Model、ToolCatalog 与 Environment route，并把插件 `ContextProjector` 基于当前 candidate branch 产生的消息注入 preamble（Agent/Model 修改下一 turn 生效）。直接工具按最新 `Agent.config.tools` 的顺序绑定；skills 非空时追加内部 `load_skill`；subagents 非空且 Session depth 小于 `maxDepth` 时追加内部 `task`。历史 `BranchSettings.activeTools` 不得限制或扩张新 turn。结果冻结为 compact `ModelRequestSpec`（无 YOLO/contextWindow/messages）；每次 Model attempt 由 `ModelRequestMaterializer` 在有效 claim 内从 `basisHeadEntryId + spec` 重建内存 ProviderRequest，再由 Core 按 `providerName` 重新读取当前 `agent_provider` 行（providerType/baseUrl/credential/config），以当前 `ProviderFactory` 构造短生命周期 attempt-local Provider；当前行缺失时 fail closed，同名重建后解析到新行。后续 turn 的上下文只白名单投影 MESSAGE/CUSTOM_MESSAGE/ASSISTANT_ABORTED 与插件 projector，`MODEL_ATTEMPT_FAILURE`、`ASSISTANT_ERROR` 及其中的 partial/error 永不进入 Provider context。ENVIRONMENT 工具按最新 `EnvironmentBinding` 绑定、规划不拒绝；Agent skills 必须由最新选中且 live 的 Environment 精确提供。subagent 名称 + 描述冻结进 `subagentBindings`，执行绝不重读父 Agent 配置扩权。所有确定性 planning 拒绝共用 `PLANNING_FAILED` code，写成 `ASSISTANT_ERROR` barrier。

## 4. Chat、Thread 与前端映射

| 前端对象 | 服务端事实 |
| --- | --- |
| Chat 卡片 | `ChatDTO`：标题、Agent/YOLO 可见设置、版本 |
| Chat 工作区 | `localStorage` 中的八个 Pane 槽位 |
| Pane | 本地 `PaneTarget`（NEW_SESSION_DRAFT / ENTRY_DRAFT / BOUND_THREAD 三态）；pendingAcceptance/pendingStop 等 sidecar 只存浏览器本地 |
| Draft pane | 本地 `BranchDraft`（frozenDraft）物化自 Chat defaults + Catalog |
| Bound pane | `branchState` 从 snapshot `branchSettings` 初始化；queued SET_* 投影 `effectiveBase` |
| Composer | 双层输入与控制栏；Permission 仅 Default/YOLO，Model/Variant 使用 anchored 两级菜单；每次发送构造 SET_* diff batch + `USER_MESSAGE`（不携带 role） |
| Thread transcript | `HarnessThreadSnapshotDTO` 的 entries（root-to-head）、queuedCommands、活跃 Invocation 与尚未物化的 `modelAttemptFailures` |
| `/tree` | 按需读取 `GET /api/ai/runtime/sessions/{sessionId}/entries` 的完整 Session Tree，选择历史 Entry 后把 Pane 切换为 `ENTRY_DRAFT(sessionId,startEntryId)`（零写入，不创建 Thread） |
| Footer | 纯只读展示真实 Environment/Workspace、Git branch、Branch usage、context 与 cache hit；未绑定 Environment 时展示 `none env`，Git 缺失整段省略，usage/cache 缺失按 0 展示，不承载设置或通知入口 |
| Approval | `POST /tool-invocations/{id}/approval`，输入 `ALLOW`/`DENY` |
| Stop | `POST /stop`，`stopRequestId` + `expectedVersion`，三态结果 |

## 5. Canvas 词汇

| 概念 | 含义 |
| --- | --- |
| CanvasDocument | 画布身份、标题、单调 `version` 与创建/更新时间；不含 threadId，经 `canvas_session` 持有 0..N 个 Session |
| ResourceNode | 唯一业务节点；包含 name/world transform/groupId、当前有序 `Resource[]` 与可选 Function |
| Resource | Canvas 内内容事实；可见资源直接 owner 到 Node，Function target/pinned orphan 可暂时无 owner；媒体只引用全局 Blob，TEXT 内联 |
| Function | ResourceNode 上可选的 `modelKey + configJson` 资源生产能力 |
| FunctionRun | Function 节点当前或最后一次运行；start/checkpoint/terminal 都会前进 Canvas version |
| CanvasGroup | 不嵌套、使用 world 绝对坐标的节点分组 |
| CanvasLink | 以 `(canvasId, sourceNodeId, targetNodeId)` 标识；target 必须有 Function；只表示候选引用并允许成环 |
| CanvasCommand | `expectedVersion + commandId + typed commands[]` 原子批次；成功批次前进一次 version 并返回实体 Patch |

节点没有 kind、nodeType、dataJson 或 resourceKind；普通节点类型由当前 Resource 决定，Function 节点生产类型由服务端 model registry 决定。

## 6. API 边界

| API | 当前职责 |
| --- | --- |
| `GET/POST /api/ai/catalog/providers` | Provider 分页查询与创建 |
| `GET/POST /api/ai/catalog/models` | Model 分页查询与创建 |
| `GET/POST /api/ai/catalog/agents` | Agent 分页查询与创建 |
| `GET/POST /api/ai/chat` | Chat 列表与创建 |
| `GET /api/ai/chat/{chatId}/sessions` | Chat-scoped Session 列表（sessionId/createdAt/lastActivityAt/firstMessagePreview/threadCount） |
| `GET /api/ai/canvases/{canvasId}/sessions` | Canvas-scoped Session 列表 |
| `POST /api/ai/runtime/command-batches` | 唯一产品写入口：owner + NEW_SESSION/ENTRY/THREAD target + commands，原子接受并 materialize |
| `GET /api/ai/runtime/sessions/{sessionId}/threads` | Session 内 Thread summary 列表 |
| `GET /api/ai/runtime/sessions/{sessionId}/entries` | Session 的完整 immutable Entry Tree |
| `GET /api/ai/runtime/threads/{threadId}/snapshot` | 单一 Thread 一致投影（含 manualCompaction availability sidecar） |
| `GET /api/ai/runtime/threads/{threadId}/system-prompt` | 按当前 branch 现算的只读 system prompt 预览 |
| `POST /api/ai/runtime/threads/{threadId}/compact` | 手动压缩：expectedVersion CAS 提交 MANUAL Compaction Turn |
| `PUT /api/ai/runtime/threads/{threadId}/yolo` | Thread YOLO policy 直接更新（version CAS；同值 no-op 先于 CAS） |
| `POST /api/ai/runtime/threads/{threadId}/stop` | stopRequestId + version CAS |
| `POST /api/ai/runtime/threads/{threadId}/tool-invocations/{id}/approval` | Tool approval 决定（父/子 Thread 同一端点） |
| WebSocket `/api/events/v1` | 事件通道：`subscribe/unsubscribe`（thread/canvas），`subscribed/event/resync/error` 帧 |
| `GET /api/ai/runtime/resources/{sha256}` | 读取仍处于瞬时/Invocation `ResourceRef` 形态的 managed Resource；Entry history 的 Blob Resource 不走该端点 |
| `GET /api/ai/catalog/tools` | Agent 可选择的 Platform/Environment ToolCatalog（不含内部 load_skill/task） |
| `GET /api/ai/environment` | 当前 live Environment 内存投影（含 CONNECTING/READY status） |
| WebSocket `/api/ai/environment/daemon/v2` | Daemon v3 连接 |
| `GET/POST /api/canvases` | Canvas 列表与创建 |
| `GET /api/canvases/{canvasId}` | Canvas document、ResourceNode/Resource/Function/Run、Group、Link 完整快照 |
| `POST /api/canvases/{canvasId}/commands` | typed Canvas command batch；version CAS、commandId/hash 幂等与实体 Patch |
| `GET /api/canvases/{canvasId}/changes` | 连续 Patch 或 gap Snapshot 恢复 |
| `POST /api/storage/uploads` | 全局 Upload reserve；PENDING 返回带 checksum 的 create-only PUT |
| `POST /api/storage/uploads/{uploadId}/complete` | 校验并绑定 READY Blob |
| `GET /api/storage/blobs/{blobId}/presigned-original` | durable Blob Resource 的渲染期原件 URL，并返回权威 `mediaType/sizeBytes` |
| `GET /api/storage/blobs/{blobId}/presigned-preview` | durable Blob Resource 的渲染期预览 URL |

公开 API 仅限表中列出的 current endpoints；消息写入统一 `command-batches`（`USER_MESSAGE` 命令表达，无独立 `/messages` 端点）；Thread head 由 Runtime 推进（ENTRY target materialization 创建新 Thread，现有 Thread 不 relocation）。

## 7. 一句话实现

Harness 以 Session Entry Tree 记录语义事实（含插件 `CUSTOM` branch state），以 head 非空的 Thread
记录执行控制面（version CAS + 命令 mailbox），以 `BranchSettings` 驱动逐轮
Catalog/Environment/插件上下文解析并冻结 compact spec，以 Model/Tool Invocation + Work 支持恢复；全局 Blob
Storage 为 Chat、Harness history 与 Canvas 提供统一内容身份，Chat/Canvas 经 `chat_session` / `canvas_session` 持有 0..N 个 Harness Session。
