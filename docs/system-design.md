# 系统设计

`kk-studio` 2.0.0 是面向可信单用户部署的自托管 AI 工作台。浏览器是工作台前端，Studio 节点由
[`web`](../web/pom.xml) 装配的 Spring Boot 应用承担目录、持久执行与宿主集成，Environment Daemon 把指定主机的
文件、命令与 LSP 能力接入 Agent，code-server 由 Daemon 托管。

执行与恢复只依赖两类事实：PostgreSQL 保存业务事实与执行权，REST Snapshot 是权威读取；WebSocket 与数据库通知
只提供低延迟提示。连接中断、通知丢失或进程退出后，Worker 从持久 Work 接管执行，浏览器回读 Snapshot 对账。

harness 只提供执行引擎与能力契约，平台实现持久化与宿主适配，产品是自带数据、接口与页面的纵向切片。上层只通过
开放接口或 SPI 使用下层能力，`web` 负责装配而不承载业务规则。

## 分层与模块职责

| 层 | 模块 | 职责 |
| --- | --- | --- |
| L5 组合根 | [`web`](../web/pom.xml) | 装配全部模块与插件；平台与 harness 的 HTTP 接口、浏览器事件通道、Daemon 接入、错误映射、SPA 与链路追踪 |
| L4 产品 | [`apps/chat`](../apps/chat/pom.xml) | Chat 列表与会话归属；自带的 HTTP 接口与 DTO；拥有 `chat`、`chat_session` |
| L4 产品 | [`apps/project`](../apps/project/pom.xml) | Project 与 Issue 工作流、调度、Issue 工具；自带的 HTTP 接口与 DTO；拥有 `project_*` |
| L4 产品 | [`apps/canvas/spi`](../apps/canvas/spi/pom.xml) | 画布函数 SPI，插件只依赖它 |
| L4 产品 | [`apps/canvas/runtime`](../apps/canvas/runtime/pom.xml) | 画布领域、typed command、持久化、Function Run、HTTP 接口、DTO 与版本通知 |
| L4 产品 | [`apps/ide`](../apps/ide/pom.xml) | IDE 视图与数据面、浏览器使用身份、跨节点路由、HTTP 接口与 DTO |
| 插件 | [`plugins`](../plugins/pom.xml) | 构建期插件：Agent 工具与画布函数 |
| L3 平台 | [`platform/runtime`](../platform/runtime/pom.xml) | 目录、存储与 VFS、环境、插件宿主、设置、配置同步、交互待办、独立 Agent 任务、通知、会话归属 SPI、跨节点设施，以及 harness 端口实现与引擎存储适配 |
| L3 平台 | [`platform/daemon-server`](../platform/daemon-server/pom.xml) | Daemon 控制协议、会话与租约、调用裁决、资源票据、数据面隧道、IDE 与更新协调；实现 harness 的能力调用端口 |
| L3 平台 | [`platform/plugin-api`](../platform/plugin-api/pom.xml) | 插件 SPI：身份、配置、凭据、资源、Harness 贡献与按类型返回的产品贡献 |
| L2 harness | [`harness/runtime`](../harness/runtime/pom.xml) | Agent 内核：公开的命令、查询与订阅，SPI 端口，工具与贡献契约，内置工具，提示词渲染，Thread 与 Tool 处理器，执行退出账本 |
| L2 harness | [`harness/agent-builtin`](../harness/agent-builtin/pom.xml) | 内置 Driver 的回合执行、请求冻结、流式与重试、模型组与自动压缩；只声明存储端口，不含 SQL |
| L2 harness | [`harness/environment`](../harness/environment/pom.xml) | `EnvironmentId`、能力目录与能力调用端口 |
| L2 harness | [`harness/model`](../harness/model/pom.xml) | 模型契约与 Provider 协议实现 |
| L1 共享 | [`common`](../common/pom.xml) | 值契约：严格 JSON、资源引用、结果内容、输入 Schema、Work 状态 |
| L1 共享 | [`work`](../work/pom.xml) | `durable_work` 的 claim、租约 SQL 与有界调度骨架 |
| L1 共享 | [`schema`](../schema/pom.xml) | 唯一的 [`V1__schema.sql`](../schema/src/main/resources/db/migration/V1__schema.sql)，按归属模块分段 |
| 宿主 | [`daemon`](../daemon/package.json) | 文件、进程、LSP、技能安装与 IDE 服务；代理由平台下发；在线更新调用官方安装器 |
| 前端 | [`frontend`](../frontend/package.json) | `app` → `platform`、`features` → `shared` 的浏览器工作台 |

依赖只能向下，模块依赖由 [`check.mjs`](../scripts/dev/verify/repository/check.mjs) 守护：

1. 产品依赖平台与 harness，平台依赖 harness；`harness/runtime` 只依赖 `common`、`harness/environment` 与
   `harness/model`，`harness/agent-builtin` 依赖 runtime 与 model。平台从不依赖产品，产品之间互不依赖，harness
   不含 SQL、JDBC 或平台概念。
2. 上层使用 harness 只有两种方式：开放接口 `HarnessRuntime` 的命令、查询与变更订阅；harness 定义、上层实现的
   SPI（网关、`HarnessStore`、`AgentDriver`、`HarnessContributor`）。
3. 平台业务服务、产品与插件不直接 SQL 访问引擎状态表，不使用 `HarnessStore`，不写只供内部使用的命令，不向
   system prompt 注入内容。引擎状态表的 SQL 只属于平台的引擎存储适配包，并按内核与各 Driver 分包隔离。
4. 平台需要产品参与时由平台定义 SPI、产品实现（会话归属、Agent 引用检查）；产品的扩展点由产品定义 SPI
   （画布函数）。插件宿主只管理插件生命周期并按类型转交贡献，不解读贡献内容。
5. 扩展点归消费它的一方：工具与 Contributor 契约在 harness，插件身份、配置与凭据在 `platform/plugin-api`，
   画布函数 SPI 在 Canvas。
6. 表与通知通道只由归属模块访问和定义；依赖 `work` 的模块可以只读 join `durable_work`。产品表只引用 harness
   的公开身份（Session、Thread）。
7. `web` 是唯一组合根，装配各层实现不算越层；SPA、错误映射、浏览器事件通道与 Daemon 传输只在这里。

方框是 Maven 模块（省略 `kk-studio-` 前缀），箭头指向被依赖的模块：

```text
                                         ┌─────┐
L5                                       │ web │
                                         └──┬──┘
        ┌────────────┬───────────┬──────────┴───┬────────────────────────────┐
        ▼            ▼           ▼              ▼                            ▼
    ┌────────┐  ┌─────────┐  ┌───────┐  ┌────────────────┐   ┌────────────────────────────────┐
L4  │  chat  │  │ project │  │  ide  │  │ canvas-runtime │   │ plugins: comfyui media opencli │
    └───┬────┘  └────┬────┘  └───┬───┘  └────┬───────┬───┘   │          minimax-mavis         │
        │            │           │           │       ▼       └────┬──────────┬────────────────┘
        └────────────┴───────────┴───────────┤ ┌────────────┐     │          │
                                             │ │ canvas-spi │◀────┘          │
                                             ▼ └────────────┘                ▼
    ┌────────────────────────┐    ┌────────────────────┐          ┌─────────────────────┐
L3  │ platform-daemon-server │◀───│  platform-runtime  │─────────▶│ platform-plugin-api │
    └────────────┬───────────┘    └──────────┬─────────┘          └──────────┬──────────┘
                 │                           │                               │
                 │                           ▼                               │
                 │               ┌───────────────────────┐                   │
                 │               │ harness-agent-builtin │                   │
                 │               └───────────┬───────────┘                   │
                 │                           │                               │
                 │                           ▼                               │
                 │                  ┌─────────────────┐                      │
L2               │                  │ harness-runtime │◀─────────────────────┘
                 │                  └───┬─────────┬───┘
                 │                      │         │
                 ▼                      │         ▼
      ┌─────────────────────┐           │ ┌───────────────┐
      │ harness-environment │◀──────────┘ │ harness-model │
      └──────────┬──────────┘             └───────────────┘
                 ▼
            ┌────────┐    ┌──────┐   ┌───────┐   ┌────────┐
L1          │ common │◀───│ work │   │ share │   │ schema │
            └────────┘    └──────┘   └───────┘   └────────┘
```

图中只画不能由其他路径推出的直接依赖。没有画出的边：`platform-runtime` 依赖 `share` 与 `work`；`canvas-spi`
与多数模块直接依赖 `common`；`web` 以 runtime 范围依赖四个插件与 `schema`。插件中只有 `minimax-mavis` 不依赖
`canvas-spi`；`harness-model` 不依赖任何内部模块。测试只复用下层模块的测试夹具（test-jar）与 `schema`。

Maven 之外的连接：

```text
frontend ──REST 与 /ws/events WebSocket──▶ web
daemon ──每台主机一条 WSS，承载 HTTP/2 的控制流与 IDE 数据流──▶ platform/daemon-server
daemon ──托管──▶ code-server（版本与摘要由 daemon/runtime-manifest.json 固定）
```

构建期生成物各有唯一来源：`web` 测试源码中的契约生成器产出前端的
[`contracts.ts`](../frontend/src/shared/api/generated/contracts.ts)；
[`control.proto`](../platform/daemon-server/src/main/protocol/control.proto) 生成 Daemon 的协议代码；
`harness/environment` 的能力 Schema 同步到 `daemon/resources/capabilities`。

`check.mjs` 同时守护聚合 POM 的模块清单、插件只 import 插件 SPI 与产品 SPI、内核契约包不反向依赖 runtime
内部实现、Harness 公开包访问边界、按表名前缀的表归属、Java 包跨模块唯一，以及平台组合根与无 Redis /
LangChain4j 约束。各模块的契约与实现细节见[文档导航](README.md#按代码区域理解系统)。

## HTTP 契约

JSON 成功响应直接返回资源：读取、更新和动作使用 200，创建使用 201，异步受理使用 202，无内容使用
204，204 不带响应体。下载、HTML 与字节流按各自的传输格式返回。
HTTP JSON 的 `Instant` 以 epoch 毫秒整数读写。

错误使用 `application/problem+json`。响应包含固定的 `type: "about:blank"`、按请求语言本地化的
`title`、HTTP `status` 和稳定 `code`；可选的 `detail` 是去敏的英文详情，`reason` 是模块内稳定的大写下划线原因，
`errors` 是结构化详情。版本冲突的 `errors` 含 `expectedVersion` 和 `actualVersion`，Canvas 命令冲突包含
`conflicts`。响应不包含内部 `resource`。

| code | HTTP 状态 | 含义 |
| --- | --- | --- |
| `validation` | 400 | 请求无效 |
| `not_found` | 404 | 资源不存在 |
| `version_conflict`、`conflict`、`duplicate`、`in_use`、`unavailable` | 409 | 版本、业务状态、唯一性、引用或依赖可用性冲突 |
| `upstream_failed` | 502 | 上游请求失败 |
| `unauthorized` | 401 | 集群内部认证失败 |
| `internal` | 500 | 未预期错误 |

`conflict` 带 `reason`，客户端据此区分 Harness 与 Canvas 的不同冲突；环境与 IDE 的 `unavailable`
分别使用 `ENVIRONMENT_UNAVAILABLE` 和 `IDE_UNAVAILABLE`。用户取消关闭 IDE 使用
`validation` / `IDE_CLOSE_CANCELLED`。框架异常保持其 HTTP 状态，并按状态归入相应类别：400、405、406、
413、415、422 为 `validation`，401、403 为 `unauthorized`，404 为 `not_found`，409 为 `conflict`，
502、504 为 `upstream_failed`，503 为 `unavailable`，其余为 `internal`。
`/internal/**` 同样使用此格式，集群客户端按 `code`、`reason` 和 `detail` 处理错误。

## Harness 引擎

### 公开面与存储端口

`harness/runtime` 的包按职责划分：`api` 是公开值与订阅契约，`spi` 是上层实现的窄端口，其余是内部实现。

| 包 | 内容 |
| --- | --- |
| `api` | 命令、会话、线程、历史、工具、执行、压缩、交互、Join、realtime、retry 等公开值；`HarnessChangeSubscriptions` 是变更订阅入口 |
| `spi` | `ToolGateway`、`RealtimeEventSink`、`RealtimePayloadFetcher`、`AcceptancePreflight`、`ToolResultHistoryMaterializer`、`ToolHistoryActionResolver`、`VfsGateway` |
| `spi.store` | `HarnessStore` 与 `SessionStore`、`EntryStore`、`ThreadStore`、`CommandStore`、`ToolStore`、`WorkStore`、`JoinStore`、`StopReceiptStore`、`ExecutionExitStore` |
| `spi.driver` | `AgentDriver`、`DriverWork`、`DriverThreadDeletion`、`LockedDriverState` 与进程外 Driver 的公共构件 |
| `spi.work` | Work 值契约，以及 claim 后的 `ClaimedExecutionSupport`、租约与心跳 |
| `tool`、`contributor/api` | 工具描述、输入 Schema、调用与结果内容；`HarnessContributor`、`HarnessCatalog`、`Tool` |
| `contributor/builtin` | 内置工具与内置历史渲染 |
| `prompt` | `PromptRenderer`：JMustache 严格模板，编译后缓存 |
| `runtime` | `HarnessRuntime`、`DriverRegistry`、Thread 与 Tool 处理器、回合计划、压缩、Join 与执行退出账本 |

`spi.store` 只供引擎消费、平台的引擎存储适配包实现、`web` 装配；平台业务服务、产品与插件只使用其余公开面与
`contributor.api`、`tool`，不绕过引擎读写引擎状态。

`HarnessRuntime` 是同步命令与控制入口：接受命令与 spawn，停止与放弃执行，提交工具输入与审批决定，
设置 YOLO，重命名 Thread 与 Session，手动压缩，读取 Session、Thread 快照、执行树、历史与命令回执，列出待处理
交互、环境等待候选与可执行环境，读取 Join。工具终态结果由 `ToolOutcomeQuery` 查询。

删除会话是 `deleteSessions(sessionIds, deleteRelations, releaseRefs)`：调用方必须持有产品 owner 锁，两个回调
与删除加入同一物理事务，线程树、命令、调用、Join、执行退出与产品关系一起收敛。

变更订阅由平台实现、上层只看到订阅：`threadVersions()` 携带 durable version，`executionTree()` 与
`interactions()` 只发失效提示，`HarnessChangeSubscription` 返回建立瞬间的 cursor 与释放句柄。

### Driver 与 Work

内核拥有除 Driver 私有状态之外的全部公共事实：append-only Entry Tree、Thread 的 head 与命令 cursor、控制状态、
`ToolInvocation` 与审批、`durable_work` 邮箱与执行退出账本。Driver 拥有一次回合的生成解析、自己的私有持久化、
事务外的外部执行，以及把终态物化为公共 Entry。

- `AgentDriver` 声明 `id()`、`capabilities()` 与 `validateSettings()`，实现回合生命周期（`resolveTurn`、
  `readState`、`lockState`、`applyResponse`、`completeToolBatch`、`prepareAutomaticCompaction`、`stop`），
  并通过 `works()` 交出 `DriverWork(type, handler)`、通过 `cancelWork()` 与 `hasActiveWork()` 参与停止与排空。
- Work 类型由定义方声明：内核只有 `WorkTargetType.THREAD` 与 `TOOL`；Driver 用
  `WorkTargetType.driver(value, rank)` 定义自己的类型并声明环境亲和性。`rank` 决定调度顺序与锁阶梯，Builtin 的
  `MODEL` 位于 THREAD 与 TOOL 之间。`DriverRegistry` 拒绝内核类型与重复类型，并把各 Driver 的处理器交给平台分发。
- 执行退出只有三种：`ExecutionExitKind.DRIVER`、`TOOL`、`REMOTE`；结果不明的外部执行收敛为未确认事实，不重放。
- 线程深删由两阶段 Driver 生命周期完成：`DriverRegistry` 按 Driver id 顺序调用
  `lockThreadStateForDeletion` 与 `deleteLockedThreadState`。通用 `PostgresqlThreadStore` 只负责内核锁序、Work 与
  Tool 行和 Thread 本身，从不引用具体 Driver 的私有表。
- 内置 Driver 是 `BuiltinAgentDriver`：`id()` 为 `builtin`，用 `MODEL` Work 在 Studio 进程内调用模型，
  `BuiltinStore` 是它的私有持久化端口（`ModelInvocation` 的读写与按回合索引），`TurnResolver` 与
  `ModelGateway` 是它的执行端口。模块不依赖 Spring、JDBC 或数据库；模型组成员冷却由平台模型目录持有，Builtin
  经 `ModelGateway` 在调用方事务与数据库时间下读写。
- Agent 的执行方式以 `execution = {driver, settings}` 保存在 Agent 定义中，Builtin 的 settings 为
  `{modelName, variant}`。模型改名或删除时，模型目录在同一事务内更新或拒绝引用了它的 Agent。
- 完整方法契约、锁序与新增 Driver 的步骤见 [Harness Runtime](modules/harness-runtime.md#agent-driver)，
  Builtin 的请求冻结与流式投影见 [Harness Agent Builtin](modules/harness-agent-builtin.md)。

## 平台

### 引擎存储适配

`platform.harness` 实现引擎端口并拥有引擎状态表的 SQL，DDL 在唯一的 V1 中按段归属，但这里不决定 Thread 的下一步动作：

| 包 | 职责 |
| --- | --- |
| `store` | `HarnessStore` 的 PostgreSQL 实现：事务核心、九个聚合视图、行映射与锁阶梯守护 |
| `dispatch` | 有界 Work 分发：claim 短事务、按 `rank` 轮转、合并唤醒、周期 poll 与停止生命周期 |
| `realtime` | 本地优先的 realtime 总线与 PostgreSQL 通知通道，跨节点只传输有损 overlay |
| `builtin`、`builtin/store` | Builtin 专属适配：模型预算、请求预览与调试、流式追帧，以及 Builtin 私有行 |
| `thread`、`task`、`model`、`tool`、`prompt`、`read` | 与 Driver 无关的回合上下文：Agent、环境、技能、工具目录、system prompt、`TurnResolver`、`ModelGateway`、`ToolGateway`，以及技能与平台资源内容的读取 |

事务与状态约束：

- 每个 store 回调在 `READ_COMMITTED` 的 `PROPAGATION_REQUIRED` 事务内执行，允许加入调用方已有的外层事务，
  禁止 `REQUIRES_NEW` 与嵌套事务，使 owner 锁与引擎写入保持同一物理事务。
- 事务核心独占一条连接与一个线程，以数据库时间判定 due、租约与冷却，缓存 `EntryPath`，并在提交前重抛首个数据库
  故障，避免脏状态被提交。
- 受控行锁遵守 `SESSION → THREAD → COMMAND → DRIVER → TOOL → WORK` 的顺序；同阶与批量锁用稳定排序获取。
- 聚合视图按表归属拆分：`harness_session`、`harness_entry`、`harness_thread`、`harness_thread_command`、
  `harness_tool_invocation`、`durable_work`、`harness_thread_join`、`harness_thread_stop_receipt`、
  `harness_execution_exit` 分别只由对应 store 访问；`WorkStore` 的 SQL 委托 [`work`](../work/pom.xml) 的
  `DurableWorkStore`，与业务事实共享同一事务。
- Builtin 的私有行只在 `builtin/store` 内出现：`PostgresqlBuiltinHarnessStore` 在通用 store 之上注入 Driver
  存储视图，`PostgresqlModelTransaction` 只访问 `builtin_model_invocation`（另读 `harness_tool_invocation`），
  通用代码通过 `PostgresqlDriverWorkStorage` 的 `workOwner` 与 `workTargetExists` 做围栏，不认识 Builtin 概念。
- `durable_work.target_type` 只校验大写格式，类型常量由各模块定义：内核 `THREAD`、`TOOL`，Builtin `MODEL`，
  Project `ISSUE`，Canvas `CANVAS_RUN`。环境亲和性由 Work 类型声明，只有当前节点持有目标 Environment 的 READY
  路由租约时该 Work 才可被 claim。

### 存储与附件

`storage_blob` 是全局字节事实：内容寻址的 SHA-256、大小、媒体类型、可选归档索引与引用计数，对象实体在 S3，行状态
只有 `ACTIVE` 与 `DELETING`。

- 上传是显式流程：reserve 取得预签名 PUT，complete 校验并探针临时对象后复制最终对象、去重插入 Blob 并绑定上传。
  写对象与绑定期间持有上传操作锁，S3 的 HEAD、复制与删除绝不发生在数据库事务或行锁内。
- 引用按行记录：Session 用 `session_blob_ref` 逐行 retain 与 release，工具与环境产生的临时资源引用在写入历史前
  物化为 Blob，物化失败则拒绝提交历史。
- 下载由服务端签发短期 URL，浏览器与 Daemon 都不直接持有长期凭据。
- 回收先登记耐久清理记录，再由后台在事务外幂等删除对象；`DELETING` 是终态，迟到写入不会把它改回 `ACTIVE`。
- 过期上传按数据库时钟的 TTL 判定，claim 后不再受 TTL 影响；清理中断由 token 围栏重试。

### 会话归属 SPI

平台定义 `SessionOwnership`、`SessionOwner`（不透明 `{kind, id}`）与 `SessionOwnerDisplay`
（`{kind, id, title, link}`），产品实现归属事实。每个实现回答：类型、是否允许分叉与通用直写、接受前的锁、是否拥有
某个 Session、创建与列举 Session、删除前的锁与清理钩子、按根线程与根会话解析展示信息、交互判定前的锁。

- 消费者是平台编排，不是产品：命令接受、会话删除、交互查询与交互裁决、会话查询统一通过这个 SPI 交互，避免按
  具体产品类型分支。Chat 的实现允许分叉与直写；Project 的实现不允许，删除前必须解除 Issue 与 Agent 的绑定。
- Agent 引用检查同属这一类：`AgentReferenceCheck` 由 Project 实现（工作流阶段引用 Agent），平台删除 Agent 前
  逐实现询问，不用 SQL 读产品表。
- 跨模块传输只携带不透明的 `{kind, id, title?, link?}`：`HarnessCommandOwnerDTO` 与 `InteractionOwnerDTO`
  的 title 与 link 只用于展示，不参与授权。

### Daemon 连接服务

`platform/daemon-server` 是不依赖 Spring 的连接服务：

| 包 | 内容 |
| --- | --- |
| `platform.daemon` | 与 Daemon 共享的值对象：能力快照、运行时状态、更新命令、IDE 租约、官方发行信息 |
| `protocol` | [`control.proto`](../platform/daemon-server/src/main/protocol/control.proto)、`ControlProtocolCodec` 与 `ControlProtocolValidator`：二进制编解码、领域映射、应用层语义校验、golden 夹具 |
| `server` | `EnvironmentDaemonServer`、会话准入与租约、在途调用追踪、IDE 协调、资源票据、更新协调 |
| `transport` | Jetty 12 上的控制通道与 HTTP/2 会话管道 |
| `tunnel` | 传输无关的数据面隧道端口 |

`EnvironmentDaemonServer` 同时实现 `DaemonSessions`、`EnvironmentTunnels` 与 harness 的
`EnvironmentCapabilityTransport`，由组合根装配进 harness；平台环境管理经它的开放接口与租约持久化端口工作。会话以
`(environment_id, owner_node_id, lease_token)` 围栏推进状态：READY、HEARTBEAT 与断开只接受当前租约，心跳只续租
不发布通知，状态、宿主信息、技能投影与运维事件的围栏写与 `environment_changed` 在同一事务提交。

### 插件 SPI

插件是构建期 JAR，[`web`](../web/pom.xml) 的 runtime 依赖决定发行物包含哪些插件，Spring Boot
auto-configuration 注册安装定义。`Plugin` 声明身份、配置 Schema、认证形态与实例构建；`PluginInstance`
暴露 `harnessContributors()` 与按类型读取产品贡献的 `contributions(Class<T>)`——画布函数这类贡献类型属于
Canvas，平台只按类型转交。凭据经 `PluginCredentialAccess` 在上游失败时作废，资源经 `PluginResourceGateway`
读取，提示词资源经 `PluginPromptContent` 提供。插件与宿主同进程、拥有相同 JVM 权限，部署者须信任其代码。完整
契约见 [Platform Plugin API](modules/platform-plugin-api.md)。

## VFS

文件工具按路径路由，`vfs:` 是平台存储的统一命名空间：

```text
vfs:/
├── skills/<包>/<技能>/…      只读   全部已发布技能
├── resources/<资源 ID>        只读   当前对话持有的资源
└── sessions/<会话 ID>/        读写   执行根会话的目录，父子 Agent 共享
    ├── threads/<线程 ID>/…   读写   线程自己的文件
    └── …                     Agent 创建的其他文件
```

- 访问范围只按执行根会话解析：其他会话 ID 与不存在的路径一样返回「不存在」，`threads/<线程 ID>/` 只接受本对话
  中存在的线程，`vfs:/sessions` 只列出执行根会话。子 Agent 运行在独立会话中，但 VFS 始终解析到执行根会话。
- `skills` 与 `resources` 只读：写入在工具预检与执行两处都以固定文案拒绝并提示写入会话目录；列目录标出
  `read-only` 与 `read-write`。技能按只读层级目录列举（`vfs:/skills` 列出技能包，`<包>/` 列出技能，`<技能>/`
  列出文件及大小），按 `vfs:/skills/<包>/<技能>/<相对路径>` 读取文件；资源根目录不提供条目列举，直接按
  `vfs:/resources/<资源 ID>` 读取。
- 操作只有 `read`、`write`、`edit`、`find`、`grep`：目录用 `read` 列举；`find` 与 `grep` 仅在会话目录内检索，
  `find` 只匹配路径，`grep` 在有文件数、字节数、结果数与截止时间的扫描预算内执行，超出时明确返回未完成。
- 存储是 `session_file` 与全局 `storage_blob`：`(session_id, path)` 确定一个文件版本，写入先上传新 Blob，再按
  `revision` 条件更新，最后一个引用释放时回收旧 Blob。目录由路径推导，不单独存储；会话删除先删文件行，再释放
  Blob 引用。
- 并发按 `revision` 检测冲突，冲突与不存在分别返回明确错误；写权限沿用工具权限策略，只读前缀在审批前拒绝，YOLO
  也不能绕过。
- 限制按会话计：文件数、单文件大小与总量都有上限；目录响应、`find` 结果与 `grep` 预算各自有界。
- `VfsGateway` 是 harness 定义、平台实现的窄端口，只回答一次文件工具调用；实现负责命名空间、只读前缀、会话
  归属、revision 与预算，harness 只按参数路由并保留错误文案。
- 文件工具从工具执行上下文直接获取执行根线程、执行根会话与当前会话，由调用方在装配时注入所需上下文，无需反查引擎存储。
- 工具层面的亲和性因此分三类：`read`、`write`、`edit`、`find`、`grep` 按每次调用的 `path` 路由；`bash` 与
  LSP 工具只能在环境执行，没有环境时从工具声明中隐藏；`task`、`ask_user`、Goal、MCP、插件与产品工具始终在
  Studio 执行。没有环境时传入绝对路径得到「未执行」并提示改用 `vfs:` 路径。
- 浏览器只读：会话文件接口按产品归属校验后提供列目录、读内容与下载，编辑只由 Agent 完成。

## 技能包

技能包是「名称 + 当前版本」：当前版本是版本号、一份规范化归档与技能清单，每个包只保留当前版本。`skill_package`
持有归档 Blob、`manifest`、发布时间与乐观锁版本；`skill_package_git_source` 只在 Git 渠道的包上存在，保存仓库
地址、分支、令牌、最近一次观察到的 HEAD 与检查错误；`storage_blob.archive_index` 保存服务端从最终 ZIP 读出的条目
索引（路径、大小、偏移、压缩长度、CRC），文件树与解压总量都从索引推导。

- 构建规则对两个渠道相同：包根是仓库根或压缩包根（唯一一级目录可作为根）；技能是根下直接包含 `SKILL.md` 的
  一级目录，其余内容忽略并在结果中列出；拒绝符号链接、硬链接、子模块、特殊文件、绝对路径、`..` 与重复路径，报错
  给出具体路径；包名与技能名按 Agent Skills 规范且必须与目录名一致。
- 归档格式固定：ZIP + DEFLATE，条目按路径排序，时间与属主固定，权限 0644/0755，每个技能一个顶层目录；归档
  大小、解压总量、条目数与单文件大小都有上限，由服务端校验而不信任客户端提交的结构信息。
- Git 渠道只用 `ls-remote` 检查分支 HEAD，不下载内容；发布时按检查到的 commit 浅拉取并直接遍历对象树，不检出
  工作区。上传渠道沿用预签名上传，服务端读取原始压缩包后走同一个构建器。
- 预览与发布：构建结果作为暂存上传保留给用户预览；发布在短事务内校验乐观锁与 Agent 引用、切换当前版本并释放旧
  归档；取消或超期的暂存对象由存储清理回收。配置导入导出只包含 Git 渠道的包，上传的包可在详情页下载归档备份。
- 同步：平台的技能对账按环境串行执行，期望集合是全部当前归档的摘要与预签名下载地址，经控制消息下发；连接就绪、
  发布或删除、通知重建与手动重新同步都会触发，同一环境运行期间的新触发合并为结束后再跑一次。平台不轮询，也不在
  组装 Prompt 时等待安装。
- Daemon 回报 `{packageName, installedSha256, localPath, error}`，平台把它写回该连接的 `skill_state` 投影。
- Prompt 路径：执行根未选择 Environment 时使用 `vfs:` 路径；已选择 Environment 且 Daemon 安装摘要与当前
  artifact 一致时给出本地绝对路径，否则该 Skill 不进入 system prompt。

## 产品纵向切片

产品是完整的纵向切片：领域、持久化、HTTP 接口、DTO、实时通知与运行配置都在自己的模块里，页面在前端自己的功能
目录里。产品之间不互相调用。

| 产品 | 模块 | 拥有 | 实现的平台 SPI |
| --- | --- | --- | --- |
| Chat | [`apps/chat`](../apps/chat/pom.xml) | `chat`、`chat_session`；`ChatController` 与模块内 DTO | 会话归属 `CHAT` |
| Project | [`apps/project`](../apps/project/pom.xml) | `project_*`；`ProjectController`、`IssueController` 与模块内 DTO | 会话归属 `ISSUE_AGENT`、Agent 引用检查 |
| Canvas | [`apps/canvas/runtime`](../apps/canvas/runtime/pom.xml) | `canvas_*`；三个控制器与模块内 DTO | 无（不参与会话归属） |
| IDE | [`apps/ide`](../apps/ide/pom.xml) | 视图与数据面状态；IDE 控制器、过滤器与模块内 DTO | 无（读取平台环境与租约） |

**Chat**：控制器以 `/api/chat/chats` 提供列表、创建、详情、整体替换、YOLO 子资源、删除与会话列表，DTO 在模块内，删除按预期版本校验。
会话归属允许分叉与通用直写，`chat_session` 记录 Chat 与 Session 的关联。

**Project**：领域含可配置工作流与阶段、Issue、Run、Activity、阶段预算与证据，HTTP 面覆盖 `/api/project/projects`、
`/api/project/issues` 与项目下的 Issue 创建。Run 接受只使用通用命令提交 Agent、执行方式与环境，加一条项目模板渲染的任务
消息，不写产品专属的 harness 状态；`issue_transition` 是 SELECTABLE 工具，由 Issue Agent 的配置显式声明，工具按
调用线程查询项目自己的 Run 记录并在业务锁内复核身份、阶段、版本与合法交接边。Issue 对账以 `ISSUE` Work 认领，只
认本次 Run 冻结的终态收尾。归属实现按 Project 再 Issue 的锁序序列化，证据引用 `vfs:/resources/…`。

**Canvas**：[`apps/canvas/spi`](../apps/canvas/spi/pom.xml) 只有画布函数 SPI（adapter、冻结运行计划、执行上下
文与严格 codec），只依赖 JDK、`common` 与 Jackson 流式 API，插件通过它贡献函数。Runtime 拥有节点模型、typed
command 与幂等键、资源 pin、`CANVAS_RUN` Work 与版本通知，并按类型从插件目录快照取出画布函数贡献。

**IDE**：`apps/ide` 拥有视图服务与 `IdeCommandTransport` 端口、同源数据面代理（HTTP 转发与 WebSocket
隧道）、浏览器使用身份、跨节点路由，以及 IDE 的 HTTP 接口与 DTO；`web` 只保留组合装配与全局错误映射。视图
生命周期为打开、续租、关闭与强制关闭，目录列举只返回直接子目录，详见 [IDE](modules/ide.md)。

**插件**：构建期注册安装定义与配置 Schema，实例按类型交出 Harness 贡献与产品贡献；插件目录快照在启动时校验依赖
与名称并冻结，失败候选释放资源。MCP 不通过插件接入，由平台用 Streamable HTTP 发现与执行，预算与取消见
[Platform](modules/platform.md#mcp-client)。

## 环境与 Daemon

### 环境注册与对账

环境身份是全局唯一且不可变的 name，运行时解析为内部 UUID。`environment_connection` 同时承载路由租约、连接状态、
宿主信息、技能投影与运维事件窗口；`cluster_node` 记录节点身份与存活。READY、HEARTBEAT 与断开都以
`(environment_id, owner_node_id, lease_token)` 为围栏，心跳只续租，只有状态、宿主信息、技能状态与运维事件真正
变化时才发布 `environment_changed`。READY 会清空 `skill_state`，随后由技能对账全量重建；自然到期没有数据库写
事件，由读取时按截止点判定。

系统设置中的全局代理是 Studio 后端与所有 Daemon 共用的唯一定义。平台在 Daemon 连接就绪时下发当前设置，设置变更
后向全部在线 Daemon 重新下发，Daemon 侧行为见下文。

跨节点由 `cluster_node` 注册、单跳内部认证与路由租约协调：节点滚动发布时先停止认领、排空本机执行、向 Daemon
发送 GOAWAY 并释放路由租约、移交在途远端工具，最后反注册。`/ws/events` 的跨节点载荷与 IDE 集群接口走内部路径。

### 控制面与主机能力

Studio 与每个 Daemon 之间只有一条 WSS 连接承载 HTTP/2：`POST /control` 建立控制流，IDE 数据面使用独立流。控制流使用 Protobuf
Envelope 成帧，消息类型覆盖 `READY`、`HEARTBEAT`、`INVOKE`、`STARTED`、`PROGRESS`、`COMPLETED`、`FAILED`、
`CANCEL`、`CANCELLED`、`RECOVER`、`RECOVER_NOT_FOUND`、`EXECUTION_EXITED`、资源上传三段式
（`RESOURCE_UPLOAD_REQUEST`、`RESOURCE_UPLOAD_TICKET`、`RESOURCE_UPLOAD_COMMIT`）、`TEMPORARY_RESOURCE_POLICY`、
`UPDATE`、`UPDATE_RESULT`、`IDE_COMMAND`、`IDE_RESULT`、`IDE_LEASE`、`SETTINGS`、`RUNTIME_STATUS`、`ERROR`，
以及技能对账的 `SKILL_SYNC` 与 `SKILL_STATE`。

能力目录由 harness 定义、Daemon 实现：`FS_READ`、`FS_WRITE`、`FS_EDIT`、`FS_EDIT_PREVIEW`、`PROCESS_EXEC`、
`FS_GREP`、`FS_FIND`、三个 LSP 能力。每个能力带输入 Schema 与默认超时，需要 `workdir` 的能力只从参数里取目录。
调用契约区分「发送前一定未执行」（Busy、Unavailable）与「可能已被接受」（SendUncertain，禁止重放），成功启动后
事件流严格是若干 partial 加一次 terminal，迟到事件静默丢弃。完整协议与租约见
[Harness Environment](modules/harness-environment.md)。

大文本落在 Daemon 本地并回传有界 tail，Agent 分页读取；资源上传由平台分配预签名 PUT，Daemon 直连 S3 上传后提交，
重试只重放同一 transfer。

### 技能安装

Daemon 逐个处理期望集合内的包：摘要与本地标记一致就跳过；否则边下载边计算摘要（超过声明大小立即中止），校验后解压
到暂存目录（校验条目数、解压总量、路径与条目类型），写入摘要标记文件，再用两次改名替换正式目录，最后删除 ZIP 与
备份。不在集合中的包只在带标记时删除。对账单飞执行，运行期间到达的新集合合并为下一轮，同一包的操作串行。

替换期间读取不会看到解压一半的内容，但两次改名之间正式路径会短暂不存在；Windows 上文件被占用时替换失败，旧版本
保留并把失败状态上报平台。

### IDE 服务

Daemon 的 IDE 服务托管受管 code-server，并接受 `OPEN_VIEW`、`CHANGE_DIRECTORY`、`FOCUS_TERMINAL`、
`CLOSE_VIEW`、`LIST_VIEWS`、`LIST_DIRECTORIES`、`COLLAPSE_MOBILE`；续租走独立心跳帧，只有当前绑定的新鲜证明
才延长视图。

- 正常关闭经 bridge 触发原生关闭，用户在 IDE 内选择保存、不保存或取消；等待期间数据面保持可用，取消则视图回到
  打开状态，只有全部关闭且清理完成才释放资源。
- 强制关闭只在 bridge 不可达或状态未知时可用：先把未保存文档备份到恢复配额内，再关闭并清理。
- 视图过期时不直接关闭：只有 bridge 报告没有未保存修改才自动回收，否则保留视图等待用户决定。
- 目录列举只返回某个目录下的直接子目录名，按名称排序并有数量上限；窄屏布局由 bridge 执行收起侧栏与面板的命令，不
  修改用户设置。

### 代理与在线更新

代理只来自平台下发的全局设置。Daemon 不读环境变量，也不查询操作系统代理；只支持无认证的 `http://host:port`。

- 生效范围是 Daemon 的出站请求（S3、更新安装器下载）以及它启动的命令、LSP 与 code-server。子进程通过
  `HTTP_PROXY`、`HTTPS_PROXY`、`NO_PROXY` 获得同一设置，设置变更只影响之后启动的子进程。
- 控制连接始终直连 Studio：它使用独占的直连 Agent，不经过代理策略，否则错误的代理设置会让 Daemon 再也收不到修正。
- 在线更新只走官方安装器：平台下发目标版本，Daemon 启动对应版本 tag 的 `install.sh` 或 `install.ps1`（带平台
  代理），由安装器下载、校验、替换并重启；Daemon 只回执阶段与结果，不自行下载或替换制品。

## 一次请求的执行链

```text
产品（Chat / Project） ──▶ HarnessRuntime.acceptCommands
  ──▶ 短事务：Session / Thread / Command CAS，命令邮箱 + THREAD Work
  ──▶ Worker claim
  ──▶ ThreadProcessor 选择下一项 durable action
  ──▶ TurnResolver（平台）解析 Agent、环境、技能、工具与 system prompt
  ──▶ Driver（Builtin 为 ModelProcessor）──▶ ModelGateway ──▶ Provider
  ──▶ 工具调用 ──▶ ToolGateway ──▶ VfsGateway（vfs: 路径）或环境能力（绝对路径、bash、LSP）
  ──▶ 围栏写回 checkpoint 或终态 ──▶ REST Snapshot 读取
```

- 接受阶段校验 owner 与目标，物化附件，冻结本回合设置。Session 拥有 append-only Entry Tree，Thread 保存 head、
  命令 cursor、控制状态与单调 version，命令邮箱与 Work 在同一事务登记。
- system prompt 由平台在回合解析时组装：Agent 正文、当前环境、可用技能与 task/subagent 说明按实际注入的工具与
  目录快照生成，模板由 `PromptRenderer` 以严格 section 渲染；未注入的内容不出现，日期等易变信息不进入固定文案。
- 流式输出在当前 execution 内有界聚合并推送有损 delta；checkpoint、重试与终态在短事务中复验 attempt 与 Work
  所有权，提交后才发布 realtime 事件。
- 审批与 `ask_user` 是持久等待：等待期间释放 Worker，人工决定按冻结调用、身份与提交约束接受后登记后续 Work。
- 模型用量随 assistant Entry 保存为 durable 事实；金额不是 durable 事实，读取时按当前价格用精确十进制现算，压缩
  输出按其冻结的执行模型计价，模型组按本次实际执行的成员计价。

## 执行权与恢复

全部后台调度共用单一 `durable_work` 邮箱：内核的 `THREAD` 与 `TOOL`、Builtin 的 `MODEL`、Project 的
`ISSUE`、Canvas 的 `CANVAS_RUN`。到期 Work 经 claim 获得随机 token 与数据库时钟截止点，heartbeat 只续租当前
owner，checkpoint、终态与 reschedule 再次复验执行权。绑定环境的 Work 还要求目标节点持有该 Environment 的 READY
路由租约。

节点内另有有界 admission：模型与工具在真正发出请求前获取名额，容量不足直接返回可重试结果。节点容量只用于限流，
恢复始终依据 PostgreSQL 中的 Work 与租约。

| 场景 | 恢复依据与处理 |
| --- | --- |
| 通知丢失或监听重建 | 固定轮询发现到期 Work，并全量 resync 订阅 |
| Worker 退出 | 租约到期后由合格节点重新 claim |
| 节点滚动发布 | 排空后释放路由租约，在途远端工具移交给其它节点 |
| 节点崩溃 | 租约到期重新认领；结果不确定的外部执行收敛为未确认事实，Thread 需显式放弃才离开运行态 |
| 旧回调晚到 | token、attempt、version 与 wake version 围栏使写回成为 no-op |
| 重复命令 | 幂等键与 cursor 返回已有结果或拒绝冲突 |
| 浏览器断线或缓冲溢出 | 回读 REST Snapshot |
| Daemon 连接中断 | 同实例重连按 journal 重放；在途远端工具由认领该 `TOOL` Work 的节点重新挂接，不重发 `INVOKE` |
| Blob 清理中断 | 持久 cleanup 状态与 token 支持后台继续 |

多节点共享 PostgreSQL 与 Blob Storage，路由、邮箱、Work 与通知经数据库协调。数据库不可达时停止依赖 ownership
的准入与写回；租约围栏只保护内部状态，外部执行结果必须由确定响应、查询或人工核实确认。实现见
[Platform](modules/platform.md) 与 [Durable Work](modules/work.md)。

## 浏览器读取与前端分层

### 快照与订阅

浏览器先加载 Snapshot，再建立订阅；服务端的订阅握手封住 cursor 读取窗口：

```text
subscribe -> 注册资源 -> 读取 durable cursor -> subscribed(cursor) -> events
```

- 资源分四类：Thread 同时有 durable version 与 realtime 真负载；Canvas 有 revision；Projects、Interactions 与
  Environments 只有失效提示；执行树按根线程订阅失效。
- 事件 gap、畸形 payload、通知降级与缓冲溢出触发 Snapshot 回读；终态一律从数据库读取。
- 通知的生产方拥有 channel 与 payload 协议：Harness 的 thread version、执行树、交互，Canvas 的 revision，
  Project 的变更与环境变更。`VersionNotificationHub` 承担按 key 注册与以权威 version 作 ack cursor 的通用语义，
  `NotificationInvalidationHub` 只做定点失效；组合根持有唯一的 LISTEN 循环并按绑定投递与 resync。
- 通道与帧格式见 [Web](modules/web.md)，合并与恢复见 [Frontend](modules/frontend.md)。

### 前端分层

| 目录 | 职责 |
| --- | --- |
| `app` | 组合根：路由、providers、页面与弹窗贡献的组装 |
| `platform` | 应用外壳与扩展契约：`AppShell`、`WorkbenchShell`、贡献类型 |
| `features` | 业务特性：通用 `thread` 与 `canvas`、`catalog`、`chat`、`environment`、`extensions`、`projects`、`settings` |
| `shared` | API 客户端与生成契约、事件通道客户端、冲突处理、国际文案、通用 UI |

- 页面、弹窗与工具渲染由应用贡献在组装时冻结：重复 id 直接报错，没有运行时注册、卸载或优先级。
- 生成契约 [`contracts.ts`](../frontend/src/shared/api/generated/contracts.ts) 由 web 测试源码的生成器产出，
  用 [`write.sh`](../scripts/dev/verify/generated-contracts/write.sh) 重新生成，漂移由 `TsContractArtifactTest`
  拦截。
- IDE 面板是外壳级分栏：iframe 常驻挂载，切换路由不重建；拖拽只改变分栏宽度并持久化，空间不足时改为全屏，页面内容
  按容器宽度响应。
- `shared/app-events` 是唯一的事件通道客户端：严格 codec、重连退避、按资源同步的 hook；页面只消费资源信号。

## 数据模型

数据库只有一个 Flyway baseline，按归属模块分段：

| 段 | 内容 |
| --- | --- |
| 1 | 共享基础：全局 Blob（`storage_blob` 含 `archive_index`）、上传（`storage_upload`）与对象清理（`storage_object_cleanup`） |
| 2 | 平台内核存储：环境（`environment`、`environment_connection`、`cluster_node`）、单行 `system_setting`、Harness 执行协议（`harness_session`、`harness_entry`、`harness_thread`、`realtime_subscription`、`harness_thread_command`、`harness_tool_invocation`、`durable_work`、`harness_thread_join`、`harness_thread_stop_receipt`、`harness_execution_exit`）与 Session 引用（`session_blob_ref`、`session_file`） |
| 3 | 平台 Driver 存储：目录（`provider`、`model`、`agent`、`model_group_cooldown`）、技能包（`skill_package`、`skill_package_git_source`）、插件（`plugin_configuration`、`plugin_credential`、`plugin_auth_attempt`）、MCP（`mcp_server`、`mcp_tool`）与 `builtin_model_invocation` |
| 4 | 产品表：Canvas（`canvas_*`）、Chat（`chat`、`chat_session`）与 Project（`project_*`） |

- 表与 SQL 只由归属模块访问：产品表由产品模块维护，目录、设置、存储、环境与插件由平台维护，`harness_*` 由平台的
  内核存储适配包维护，`builtin_model_invocation` 由平台的 Builtin 适配包维护。
- 引擎表的领域语义由 harness 定义，DDL 与 SQL 实现归平台；新增 Driver 只在自己的适配分包内加表，不改通用存储。
- 产品表只引用 harness 的公开身份（Session、Thread），不复制引擎内部状态，也不 join 引擎内部表做查询。
- 约束表达真实不变量：Agent 定义的 `execution` 形状与 USER 类型必须有执行方式、`session_file` 的路径与
  revision、`durable_work` 的租约成对与类型格式、技能包的命名与描述长度。
- 关系、索引与约束细节见 [Schema](modules/schema.md)。

## 部署与安全条件

应用面向可信单用户，登录鉴权由外部入口承担。默认本地栈监听 `127.0.0.1`；局域网或公网部署必须配置 TLS 与访问
控制。Daemon 继承启动用户的主机权限，**没有文件系统沙箱**；插件与 Contributor 也是同进程的可信代码。

- HTTP 边界严格校验 UUID、cursor、sealed union 与 JSON 形状，拒绝未知或重复字段及错误类型。
- 资源访问按业务持有关系授权；UUID、URI 与跳转地址只用于定位。VFS 只开放执行根会话，只读前缀在预检与执行两处
  拒绝。
- Provider、MCP、Daemon 与插件凭据经受控入口写入，普通 DTO、日志与 Debug 保持脱敏；它们以明文存入 PostgreSQL，
  保护依赖数据库访问控制与备份加密。技能仓库令牌不回显，也不下发给 Daemon。
- 注册令牌文件要求 owner-only 权限；路径操作要求显式绝对路径，只有进程执行要求显式绝对 `workdir`。
- 代理只支持无认证的 HTTP 代理，控制连接始终直连；在线更新只由官方安装器执行，Daemon 不自行替换制品。
- 生产变更需经过审批并准备可恢复备份；备份同时覆盖 PostgreSQL 与 S3 数据及部署密钥。

首次运行见[项目 README](../README.md)，参数与生产操作见[部署与运行](operations/deployment.md)，开发与质量入口见
[开发与测试](operations/development-and-testing.md)。漏洞报告见 [Security Policy](../SECURITY.md)。
