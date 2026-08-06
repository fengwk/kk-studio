# Environment Daemon Gateway

本文描述全局 live Environment 注册表与 Daemon v2 WebSocket Gateway 的当前协议、内存边界和运行约束。

## 职责与边界

产品级 Tool 只有 `PLATFORM` / `ENVIRONMENT` 两类。Environment 是**服务器内存**中的实时资源，以 canonical `EnvironmentId`（lowercase nonnil UUID）为 route 身份唯一。HELLO 后 registry 即保存绑定连接并标为 `CONNECTING`，READY 后补齐 skills 并转为 `READY`；只有 READY 可参与 resolve/dispatch。ToolInvocation 仍是 PostgreSQL durable 执行事实；WebSocket 连接、Daemon 进程和 Gateway 内存句柄都是可丢弃传输状态。

| 层 | 职责 |
| --- | --- |
| `core/ai/environment` | `LiveEnvironmentRegistry`、`EnvironmentDaemonGateway`（endpoint/skill loader/transport）、daemon 协议 codec |
| `harness-tool` | route-neutral Tool API、`EnvironmentId`、`ResourceRef`、`RemoteTool`、Daemon v2 envelope/result codec |
| `harness-daemon` | 独立 Daemon 连接、重连、本地工具执行、invocation journal 与 skill 发现 |
| `harness-runtime` | 统一 `ToolProcessor`、ToolInvocation durable 状态与冻结 route 路由 |
| `web` | 提供 `/api/ai/environment/daemon/v2` WebSocket 文本帧与只读 `GET /api/ai/environment`；不直接消费 Harness 类型 |

数据库是 ToolInvocation 状态、lease、终态结果与所属 Thread 推进的唯一来源。partial 进度进入 Redis realtime projection。Gateway 拥有连接与协议，不是第二套 durable 状态机。

## Environment 身份

- **route identity 是 `environmentId`**：canonical lowercase nonnil UUID，由 Daemon 在环境根目录的身份文件（`.kkstudio` 子目录）加载/创建，绝不通过名称路由。
- `environmentName` 只是 display label：同一 name 可绑定不同 id，路由永不回退到 name。
- `LiveEnvironmentRegistry` 按 `environmentId` 唯一，**first connected id wins**：后到的 HELLO 不挤占已占用的 id；断线移除条目。无 create/update/delete API，全部是服务器内存投影。

## 配置与连接

服务端配置（`HarnessRuntimeProperties` / gateway properties）：

```yaml
kk-studio:
  harness:
    environment-gateway:
      daemon-token: ${KK_STUDIO_DAEMON_TOKEN}
```

`daemon-token` 是部署范围的共享连接密钥；Gateway 在 HELLO 中以常量时间比较它；缺失、空白或不匹配的密钥会关闭连接。生产部署使用 TLS 终止后的 `wss://`。

Daemon 以 CLI 参数启动（`harness-daemon`，只依赖 `harness-tool`）：

```text
java ... DaemonMain \
  --environment-name local-dev \
  --gateway-uri ws://studio.example/api/ai/environment/daemon/v2 \
  --gateway-token ${KK_STUDIO_DAEMON_TOKEN} \
  --skill-dir ~/.agents/skills \
  --daemon-id optional-stable-daemon-name
```

`environment-name` 与 `gateway-token` 必填；`--skill-dir` 可重复，未提供时若存在则默认 `~/.agents/skills`。Environment 身份从 `CodingToolsConfig.environmentRoot()` 的根目录身份文件加载/创建，随每个 envelope 参与作用域校验。

## 认证、绑定与目录

Daemon 连接后的首帧必须是 HELLO。`environmentId`（canonical UUID）与 display `environmentName` 位于 **envelope**；HELLO **payload** 字段为 `{"daemonId","protocolVersion","toolCatalogVersion","gatewayToken"}`。Gateway 验证 envelope 作用域、共享密钥、协议版本与非空 `environmentName`，并以 `environmentId` 绑定实时 Environment；**同一 environmentId 的首个连接获胜**（后到 HELLO 不挤占），display name 在同一连接内必须稳定（从不重路由 binding）。

握手顺序：

```text
HELLO -> WELCOME {} -> READY {"skills":[...]}
```

READY 后 `HEARTBEAT` 刷新 `lastSeen`；断线时 registry 移除该 id。固定目录版本为 `EnvironmentToolCatalog.version()`，首个 READY 之后不重新协商；`DaemonToolRegistry` 与目录按名称、版本、schema、prompt、side effect 和 timeout 完全一致，启动时校验后冻结。

**并发约束**：每个 Environment 同时最多 1 个 active remote invocation——`EnvironmentDaemonGateway` 以 `activeByEnvironment` 登记 in-flight 调用，已存在 active 时新 INVOKE 确定性失败（`environmentId already has an active remote tool invocation`）。发送 CANCEL 只做幂等取消请求；active 槽位在 terminal callback 或连接 cleanup 时释放。

只读查询：

```text
GET /api/ai/environment
```

返回 `id`（canonical UUID）/ `name`（display only）/ `status` / `tools` / `skills` / `lastSeen`。无 create/update/delete API。

Skill 通过 `LOAD_SKILL` / `SKILL_LOADED` / `SKILL_LOAD_FAILED` 按需加载；`load_skill` 是内部 `PLATFORM` Tool，不出现在 Agent 可选择目录中，必须由 Agent 的 `activeTools` 显式选择（Resolver 不做隐式追加）。

## Invocation 分发

`harness_tool_invocation.request` 冻结 binding（descriptor/type/environmentId）。统一 `ToolProcessor` 只消费 dispatcher 已 claim 的 TOOL Work：

```text
claim TOOL Work（Work-only 短事务）
  -> ToolGateway.preflight（权限 + 机械校验，事务外）
  -> 两阶段激活（start 返回 Started 后由 Processor 在 durable markRunning 之后调用 activate）
  -> RemoteToolTransport.send -> Gateway -> Daemon INVOKE
  -> 回调（serialized FIFO）：STARTED / PARTIAL / COMPLETED / FAILED / CANCELLED
  -> CoreToolGateway 回调桥内 ToolResultExternalizer（durable 外部化）
  -> ToolProcessor 接收已外部化 terminal ToolResult -> terminal CAS -> 请求 THREAD Work 做 sibling apply
```

```mermaid
sequenceDiagram
    participant D as Daemon
    participant W as WebSocket adapter
    participant G as EnvironmentDaemonGateway
    participant R as Live Registry
    participant WD as Work Dispatcher
    participant TP as ToolProcessor
    participant DB as PostgreSQL

    D->>W: HELLO (environmentId, token)
    W->>G: HELLO envelope
    G->>R: tryBind(id, name, connection)
    G-->>W: WELCOME {}
    W-->>D: WELCOME {}
    D->>W: READY(skills)
    W->>G: READY(skills)
    G->>R: mark READY, skills, lastSeen
    R-->>G: READY hint
    G-->>WD: wake hint
    WD->>DB: claim due TOOL Work
    WD-->>TP: bounded handoff
    TP->>G: RemoteTool send INVOKE
    G->>D: INVOKE (frozen name@version, arguments, timeout)
    D->>G: STARTED / PARTIAL / COMPLETED / FAILED / CANCELLED
    G->>TP: transport callback (ownership-checked)
    TP->>DB: partial -> Redis overlay; terminal -> ToolInvocation + Work
```

Wire `invocationId` 始终是持久 Invocation ID 的十进制字符串。每个 envelope 由 `environmentId` 作用域校验；连接/环境/invocation ownership 不匹配的回调被拒绝。

分发前校验：

1. 调用未取消、未过期；
2. 冻结 `name@version` 必须命中共享固定目录；
3. frozen arguments 是 JSON object；
4. recovered lease 的非幂等调用保守收敛为 `UNKNOWN`，不重发。

连接丢失或发送失败（send outcome uncertain / connection-close notify）对 active listener 上报 uncertain；`RemoteToolSendUncertainException` 由 `CoreToolGateway` 令 Invocation 收敛 `UNKNOWN`，**不重发可能已发生的副作用**。瞬时 active handle 只是传输状态，持久 lease 与 fencing 仍由 PostgreSQL claim 约束。

## 回调、结果与 Resource

Daemon 回调使用连续 sequence；相同 sequence 的完全相同 envelope 是幂等重放。

| Daemon callback | 效果 |
| --- | --- |
| `STARTED` | transport 执行确认 |
| `PARTIAL` | 解码 result，经 ToolProcessor 写入 Redis realtime projection（TOOL_PARTIAL 不携带 Resource） |
| `COMPLETED` | Gateway 解码 Resource 为**瞬时 BinaryToolContent** 后交付回调桥；`CoreToolGateway` 在桥内做 durable 外部化，随后 `SUCCEEDED` terminal CAS |
| `FAILED` | `FAILED` terminal CAS |
| `CANCELLED` | `CANCELLED` terminal CAS |

`ACK` / `ERROR` 只描述 transport，不单独改写 durable 状态。`DaemonToolResultCodec` 使用严格 JSON shape；Gateway 的 COMPLETED 解码会把 Resource 引用读回并校验为瞬时 Binary 内容，**不直接形成最终 durable file URI**：

- daemon coding tool 自身可因输出超过 preview 限制（默认 2000 行 / 50KB）或二进制内容产生 Resource（daemon 侧 `resourceStore.store` 落盘并返回 ref）；
- core 对 Text/Json >8KB 及 Binary 内容统一在 `ToolResultExternalizer`（CoreToolGateway callback bridge）外部化：先 `ResourceStore.reference` 无副作用计划、再逐项 `put`、返回 ref 与计划精确相等，durable 只保存 ResourceRef JSON。

terminal CAS 成功后请求 owning Thread Work；CAS 失败表示 ownership 已丢失，仅丢弃本地 handle。

## Daemon 本地执行

Daemon 的 invocation journal 是去重事实源（WebSocket 仅传递消息），记录本地 RUNNING/terminal 状态。重连后重新完成 HELLO/WELCOME/READY 握手。重复 `INVOKE` 在 journal 命中 RUNNING 时返回 `STARTED {"replayed":true}`，命中终态时重放该终态。

Daemon 仅依赖 `harness-tool`，不反向依赖 runtime/model。

### Coding tools

独立 Daemon 进程通过 `CodingTools.registerAll` 注册固定目录中的十个 coding tool：

```text
read, write, edit, apply_patch, bash, grep, find,
lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile
```

静态 MIT prompt 资源位于 `harness/daemon/src/main/resources/.../coding/prompts/`。LSP bridge 协议为 JSON stdin/stdout；未配置 bridge 时不得伪造成功结果。`CodingToolsConfig` 的默认 preview 上限为 2000 行 / 50KB，超出部分外部化为 ResourceRef。
