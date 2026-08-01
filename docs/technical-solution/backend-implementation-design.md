# 后端落地设计

本文描述当前 `share`、`core` 和 `web` 的 Harness 控制面。领域状态机与 framework-free 用例编排由 `harness-runtime` 管理；`core` 提供 application boundary、composition 与基础设施 adapter，`web` 负责 HTTP、SSE、WebSocket 与内嵌前端静态资源适配。

## 分层

```mermaid
flowchart LR
    Client[Browser / Daemon]
    Web[web controllers and adapters]
    Core[core application services]
    Runtime[harness-runtime API / SPI]
    Store[(PostgreSQL / Redis / S3)]

    Client --> Web --> Core --> Runtime
    Core --> Store
```

| 层 | 职责 |
| --- | --- |
| `share` | DTO、JSON 字段和 HTTP 数据边界 |
| `web` | 路由、参数解析、SSE emitter、WebSocket adapter、HTTP 状态映射、`classpath:/static` 与 BrowserRouter fallback |
| `core.ai.runtime` | 薄 application boundary、Spring composition 与 PostgreSQL/Redis/Provider adapter |
| `core.ai.environment` | 内存 Live Environment Registry、RemoteToolTransport、daemon gateway |
| `harness-runtime` | Command coordinators、Reconciler、Invocation workers、Interaction、Model 契约与 outbound SPI |
| `harness-tool` | Tool API / RemoteTool / Daemon 协议 |

## API 边界

所有 durable ID 在 HTTP 载荷和路径中均为正十进制字符串。格式非法返回 `400`；不存在返回 `404`；冲突返回 `409`。

| 域 | 接口 | 用途 |
| --- | --- | --- |
| Provider / Model / Agent | `/api/ai/catalog/providers`、`/api/ai/catalog/models`、`/api/ai/catalog/agents` | 全局 Provider、Model、Agent 资源 CRUD；Agent config 只包含 tools/skills |
| Tool catalog | `GET /api/ai/catalog/tools` | 本地可选工具与固定十个 Environment 工具的统一目录；runtime-managed `load_skill` 不出现在可选目录 |
| Chat | `/api/ai/chat` | 持久 Chat CRUD；必填 `defaultAgentId` 与可空 `defaultEnvironmentName`；默认 Agent 引用允许 stale，Environment 名称是实时身份 |
| Chat Thread | `GET/POST /api/ai/chat/{chatId}/threads`、`PUT /api/ai/chat/{chatId}/threads/{threadId}` | Chat 范围 opaque keyset 列表；POST 原子完成创建、关联、bootstrap、默认配置应用和查询；PUT 幂等关联 |
| Session | `GET /api/ai/runtime/sessions`、`GET /api/ai/runtime/sessions/{sessionId}`、`/entries` | flat Session 查询与 Session Entry Tree；Session 仅由 Thread bootstrap 创建 |
| Thread | `GET /api/ai/runtime/threads?sort&cursor&limit`、`POST /api/ai/runtime/threads`、`GET /api/ai/runtime/threads/{threadId}`、`GET /api/ai/runtime/threads/{threadId}/snapshot` | 全局 opaque keyset Thread 列表（统一返回 `{items,nextCursor}`）；创建 UNBOUND Thread（201，无 body）；单一 chat-runtime snapshot 含 revision、状态、entries、inputs、invocations、open interactions 和 usage |
| Thread head | `POST /api/ai/runtime/threads/{id}/bootstrap`、`PUT /api/ai/runtime/threads/{id}/head` | bootstrap 创建 Session 并绑定 head（201 `{session, thread}`）；`PUT /head` 做 bind/rebind/unbind |
| Thread 输入（202） | `POST /api/ai/runtime/threads/{id}/messages`、`/messages/custom`、`PUT .../agent`、`/model`、`/environment`、`/yolo` | mailbox 入队 |
| Thread realtime | `GET /api/ai/runtime/threads/{id}/events/stream` | durable `revision`/`resync` SSE 加上无 id 的 lossy Redis `realtime`；`afterRevision` 与 `Last-Event-ID` 只表示 revision |
| Thread 控制 | `POST /api/ai/runtime/threads/{id}/stop` | epoch fencing 取消 queued Input、可安全取消的 Invocation 与 OPEN Interaction |
| Retry policy | `GET` / `PUT /api/ai/runtime/settings/retry-policy` | 全局持久化自动重试策略 |
| Realtime Stream policy | `GET` / `PUT /api/ai/runtime/settings/realtime-stream-policy` | 全局持久化 Redis Stream `maxLength` 策略 |
| Tool | `GET /api/ai/runtime/tool-invocations/{id}` | 单个 Tool 状态查询；Thread 集合投影位于 snapshot |
| Interaction | `GET /api/ai/runtime/interactions/{id}`、`GET /api/ai/runtime/interactions/open`、`POST /api/ai/runtime/interactions/{id}/response` | 通用 Interaction |
| Artifact / Usage | `/api/ai/runtime/artifacts/{id}`、`/api/ai/runtime/usage/sessions/{id}`、`/api/ai/runtime/usage/models/{id}` | artifact bytes 与 session/model 用量汇总；Thread usage 位于 snapshot |
| Environment | `GET /api/ai/environment`、`/api/ai/environment/daemon/v1` | 只读实时 Registry 与 daemon WebSocket |

Model 与 Agent 的 `PUT` 接收完整 editable body。Provider credential 不回显；空 credential 表示保留当前密钥。

`bootstrap`、`PUT /head`、`stop` 与全部 mailbox 请求体都含必填 `expectedExecutionEpoch`。epoch 过期或 Thread 非静止返回 `409`；未知 Thread/Session/Entry/Agent 返回 `404`。

### HTTP 国际化

- HTTP 用户可见错误支持 `Accept-Language: en-US` 与 `zh-CN`；缺失或不支持语言统一回退英文。成功载荷、HTTP 状态、稳定错误码和结构化上下文不随语言变化。
- `StudioMessageService` 在 Web 边界使用 convention4j `AggregateResourceBundle.CONTROL` 与 `StringManager` 缓存两套 `string.properties` 资源。starter 自带的单一启动 Locale 只作为进程默认配置，不承担按请求切换。
- `StudioDomainErrorAdvice` 依据 `DomainErrorCode` 本地化 `message`，保留 `errors.resource`、version conflict 字段，并把原始技术原因放入 `errors.detail`。
- `StudioResponseStatusErrorAdvice` 本地化 `ResponseStatusException` 的顶层 `message` 与 `errors.title`，保留既有大写 HTTP code、`type=about:blank` 和原始 `detail`；SSE 错误继续由原有流式处理链处理。
- Core exception、Harness 持久事实、模型/工具内容与日志保持 locale-independent；客户端不得解析本地化 message 做业务分支。

### Controller 映射

| Controller | 路径前缀 | 职责 |
| --- | --- | --- |
| `StudioHarnessThreadController` | `/api/ai/runtime/threads` | Thread 列表/读取/创建、snapshot、bootstrap、head 重定位、入队 202、Stop、realtime SSE |
| `StudioHarnessRetryPolicyController` | `/api/ai/runtime/settings/retry-policy` | 自动重试策略 |
| `StudioHarnessRealtimeStreamPolicyController` | `/api/ai/runtime/settings/realtime-stream-policy` | realtime Stream 最大保留事件数策略 |
| `StudioHarnessSessionController` | `/api/ai/runtime/sessions` | Session 查询、Session Entries |
| `StudioChatController` | `/api/ai/chat` | Chat CRUD、Chat-scoped Thread 列表/创建/关联 |
| `StudioToolCatalogController` | `/api/ai/catalog/tools` | 统一可选 ToolCatalog 查询 |
| `StudioHarnessObservabilityController` | `/api/ai/runtime` | 单个 tool invocation、artifacts；Thread 集合事实位于 snapshot |
| `StudioInteractionController` | `/api/ai/runtime/interactions` | Interaction 查询与响应 |
| `StudioModelUsageController` | `/api/ai/runtime/usage` | 聚合 |
| `StudioToolEnvironmentController` | `/api/ai/environment` | 只读 live Environment Registry |

## Thread 与 Session

Session 只组织 Entry Tree，不持有 Thread；Thread 是可跨 Session 复用的 durable runtime process，当前 Session 由 head Entry 派生。Chat 通过 `chat_thread(chat_id, thread_id)` 历史聚合 Thread，关系是多对多且不复制 Thread 的 `created_at`/`updated_at`。

`POST /api/ai/runtime/threads` 创建 UNBOUND Thread；`POST /api/ai/runtime/threads/{id}/bootstrap` 在一个事务内创建 Session/ROOT/RUNTIME_CONFIG 并把该 UNBOUND Thread 的 head 绑定到 `RUNTIME_CONFIG` Entry。`RUNTIME_CONFIG` 由 Agent、Model、Environment 名称、tools/skills 短名和 yolo 组成。

`PUT /api/ai/runtime/threads/{id}/head` 是外部修改 head 的唯一入口，可跨 Session 重定位或传 `null` 回到 UNBOUND；不复制 Entry 或 Invocation。它要求 Thread 逻辑静止：无有效 processor lease、`runnable=false`、无 QUEUED Input、当前 epoch 无非终态 Model/Tool Invocation、无相关 OPEN Interaction。满足后 CAS `expectedExecutionEpoch`，成功则 epoch+1 并清 lease/`runnable`，旧 epoch 的执行结果不再能写入。分支就是这样的 head 重定位，没有独立的 Branch 实体。

用户消息与设置变更只进入 `ThreadInput` mailbox，不直接写 Entry；UNBOUND Thread 拒绝入队。`ThreadReconciler` 在无 primary
work 时将 snapshot 中全部 queued Input 按 TURN_INPUT_BATCH 原子 harvest，追加 `RUNTIME_CONFIG`/消息 Entry；随后从最终
head 最多创建一次冻结 `ModelInvocation`，snapshot 后到达者留给下一 turn。

查询：`GET /api/ai/runtime/threads` 与 `GET /api/ai/chat/{chatId}/threads` 都返回 `{items,nextCursor}`。`sort=recent` 使用 `harness_thread.updated_at`，`sort=created` 使用 `harness_thread.created_at`，均按时间、`id` 降序 keyset；`limit` 默认 20、最大 100，cursor 是绑定 sort 的 opaque `v1` token。`sessionId`/`sessionTitle`/`headEntryId` 均可空，DTO 附带当前 `executionEpoch`。`GET /api/ai/runtime/threads/{id}/snapshot` 在 REPEATABLE READ 下返回 revision 与 root→head Entries（UNBOUND 为空）、按 `sequence ASC` 的 inputs、invocations、open interactions 和 usage。`createTime` 只用于展示；不得用墙钟重排因果顺序。

`POST /api/ai/chat/{chatId}/threads` 在一个事务中完成以下步骤：创建 Thread、写入 `chat_thread` 关联、以 Chat 的默认 Agent/Environment 和全局 defaultYolo bootstrap Session/ROOT/RUNTIME_CONFIG、读取最终 Thread 查询投影。任一步失败都回滚，因此客户端收到的 Thread 已经完成 bootstrap。`PUT /api/ai/chat/{chatId}/threads/{threadId}` 使用 `(chat_id, thread_id)` 唯一键幂等写入，重复关联不产生新行。

Java 领域类型使用 `HarnessThread`，避免与 `java.lang.Thread` 冲突。

核心服务：

| 服务 | 职责 |
| --- | --- |
| `ThreadCommandCoordinator` | harness-runtime 内 framework-free 命令编排：Thread 创建/bootstrap/head 重定位、payload 构造、配置冻结、幂等短路、映射 coordinator-owned results；Core 不依赖 transaction SPI。Session / ROOT / `RUNTIME_CONFIG` 通过 `bootstrapThread` 内部私有 helper 落库 |
| `RuntimeConfigSource` | live Agent/Model 冻结 SPI；Core `RuntimeConfigSnapshotResolver` 实现；纯 YOLO 替换在 runtime |
| `HarnessThreadCommandService` | Core 薄边界：decimal/DTO、同事务 durable target mutation；bootstrap 响应内嵌 Session DTO |
| `InteractionCoordinator` | harness-runtime 内 Tool permission 投影与 approval resolution |
| `InteractionService` | Core 薄边界：decimal/DTO |
| `HarnessRetryPolicyService` | 全局自动重试策略 |
| `HarnessRealtimeStreamPolicyService` | 全局 realtime Stream 容量策略；Redis sink 以短期缓存按写入解析 |
| `HarnessThreadQueryService` | thread 列表与一致 snapshot |
| Thread command / reconcile transactions | PostgreSQL 原子事务适配 |
| `HarnessSessionQueryService` | Session 只读；列表按 derived max-entry `updated_at` desc, id desc 排序 |
| `HarnessObservabilityQueryService` | tool / model invocations、interactions、artifacts 查询投影 |
| `ModelUsageAggregationService` | 账本聚合 |
| `PostgresqlExecutionTargetListener` / `PostgresqlExecutionTargetDispatcher` | LISTEN/NOTIFY、startup/reconnect/nearest-due wake 与本地 Thread/Model/Tool target 分发 |

## 事务与锁

```text
锁 Thread 行 → 锁 Invocation / Interaction（按需）→ Entry/Input append
```

- 入队、Stop 与 head 重定位都锁 Thread 行并 CAS `expectedExecutionEpoch`；Stop 与 head 重定位递增 `execution_epoch` 并 fencing 旧执行。
- Assistant Entry 与 `harness_model_usage` 同事务；`assistant_entry_id` 唯一。
- Tool 副作用以 `tool_invocation.id` 为幂等键。
- Model/Tool worker terminal 与 Thread `runnable=true` 同事务。

## SSE 与 snapshot-first 投影

客户端先读取 PostgreSQL REST snapshot，再按 snapshot revision 打开 SSE：

```text
GET /api/ai/runtime/threads/{id}/events/stream?afterRevision={revision}
```

`revision` 事件以 revision 为 SSE id；`resync` 与 `realtime` 没有 SSE id。Redis delta 只做临时 text/thinking overlay，重新连接从 `$` live edge 开始；每次 `XADD` 使用持久化全局策略的 `maxLength`，保存策略不会扫描既有 key，而是在其下一次写入时精确裁剪。

## Tool、Environment 与 Artifact

`ToolCatalog` 是 Core 与 Agent 编辑、runtime resolver、Daemon 生产注册共同使用的统一目录：本地可选工具与固定十个 Environment 工具按短名和版本索引，`load_skill` 作为 runtime-managed 工具保留在内部注册中，不接受 Agent 选择。`GET /api/ai/catalog/tools` 只返回可选目录。

`ToolBinding.environmentName == null` 表示由当前 runtime 的本地 ToolRegistry 执行；非空表示通过 `RemoteTool` 将调用发送到指定 Environment。ToolInvocation 与 Binding 的路由只由这个可空目标名决定。发送前发现远程目标不可用时终态为 `FAILED`；发送结果不确定时终态为 `UNKNOWN`，不得重放可能已经发生的副作用。

Gateway 只管理连接与协议，不是第二套 durable 状态机。Environment 不提供 REST CRUD；`GET /api/ai/environment` 投影当前连接 Daemon 的内存 Registry。协议细节见 [environment-daemon-gateway.md](environment-daemon-gateway.md)。

WebSocket handler 只依赖 Core `EnvironmentDaemonEndpoint`；Gateway 以 `RemoteToolTransport` SPI 接入统一 `ToolWorker`。Daemon READY event 通过不可变 listener bridge 离开 WebSocket 栈后调用 PostgreSQL dispatcher wake；没有 `pollOnce` 或 caller-thread fallback。

`GET /api/ai/runtime/artifacts/{id}` 返回原始 bytes 与有效 media type；异常 media 降级为 `application/octet-stream`，并附加 `X-Content-Type-Options: nosniff` 与 `Content-Security-Policy: sandbox`。

## 验证

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn clean verify -Dspotless.check.skip=true
env JAVA_HOME=$JAVA_HOME_21 mvn validate -Dspotless.check.skip=true
```

Java 代码还需通过仓库 Checkstyle、Google Java Format 1.18.0、newline audit 和 `git diff --check`。
