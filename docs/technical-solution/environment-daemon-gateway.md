# Environment Daemon Gateway

本文描述全局 live Environment 注册表与 Daemon v2 WebSocket Gateway 的当前协议、内存边界和运行约束。

## 职责与边界

产品级 Tool 只有 `PLATFORM` / `ENVIRONMENT` 两类。Environment 是**服务器内存**中的实时资源，以 canonical `EnvironmentName`（bounded 小写路由名称）为 route 身份唯一。HELLO 后 registry 即保存绑定连接并标为 `CONNECTING`，READY 后补齐版本化能力对象（skills + MCP server 摘要）并转为 `READY`；可用性 = READY + 连接打开 + 心跳未过期（单一配置超时）。ToolInvocation 仍是 PostgreSQL durable 执行事实；WebSocket 连接、Daemon 进程和 Gateway 内存句柄都是可丢弃传输状态。

| 层 | 职责 |
| --- | --- |
| `core/ai/environment` | `LiveEnvironmentRegistry`、`EnvironmentDaemonGateway`（endpoint/skill loader/transport）、daemon 协议 codec |
| `harness-tool` | route-neutral Tool API、`EnvironmentName`、`ResourceRef`、`RemoteTool`、Daemon v2 envelope/result codec |
| `harness-daemon` | 独立 Daemon 连接、重连、本地工具执行、invocation journal 与 skill 发现 |
| `harness-runtime` | 统一 `ToolProcessor`、ToolInvocation durable 状态与冻结 route 路由 |
| `web` | 提供 `/api/ai/environment/daemon/v2` WebSocket 文本帧与只读 `GET /api/ai/environment`；不直接消费 Harness 类型 |

数据库是 ToolInvocation 状态、lease、终态结果与所属 Thread 推进的唯一来源。partial 进度进入 Redis realtime projection。Gateway 拥有连接与协议，不是第二套 durable 状态机。

## Environment 身份

- **route identity 是 `environmentName`**：canonical bounded 小写路由名称（`^[a-z0-9]+(-[a-z0-9]+)*$`，≤64 字符，无空白/无 `'/'`），由 Daemon 以 `--environment-name` 声明；不存在 UUID、身份文件或展示名。
- `LiveEnvironmentRegistry` 按 `environmentName` 唯一：持有者连接打开且心跳未过期时，后到的同名 HELLO 是 typed 冲突（ERROR payload 携带 `code=ENVIRONMENT_NAME_CONFLICT`），连接被拒绝；持有者连接已关闭或心跳租约过期时，同名 HELLO **原子接管**（旧连接交还 Gateway 恰好清理一次，见下）；断线移除条目。无 create/update/delete API，全部是服务器内存投影。

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
  --mcp-config /etc/kk-studio/daemon-mcp.json \
  --daemon-id optional-stable-daemon-name
```

`environment-name` 与 `gateway-token` 必填；`--skill-dir` 可重复，未提供时若存在则默认 `~/.agents/skills`。`--mcp-config` 可选（属性回退 `kkstudio.daemon.mcp-config`），指向严格的 UTF-8 JSON 文件（见「本地 MCP server」）。Environment 身份即 CLI 声明的 canonical 名称，随每个 envelope 参与作用域校验。

## 认证、绑定与目录

Daemon 连接后的首帧必须是 HELLO。canonical `environmentName` 位于 **envelope**；HELLO **payload** 字段为 `{"daemonId","protocolVersion","toolCatalogVersion","gatewayToken"}`。Gateway 验证 envelope 作用域、共享密钥与协议版本，并以 `environmentName` 绑定实时 Environment。绑定是原子的三态判定（`BindResult`）：

- **Accepted**：无现有条目或同连接幂等重绑，正常完成握手；
- **Rejected**：现有持有者连接仍打开且心跳未过期（租约有效）——后到同名 HELLO 收到 typed ERROR（`code=ENVIRONMENT_NAME_CONFLICT`）并关闭连接，Daemon 视为终态失败停止重连并以非零码退出；
- **Replaced**：现有持有者连接已关闭或心跳超过配置超时（lastSeen 租约过期）——registry 原子切换到新持有者，Gateway 把被替换的旧连接状态恰好清理一次（旧连接 close、其 active remote 按不确定收敛、pending skill load 失败），绝不触碰新持有者，也不泄漏任何执行工作。CONNECTING 的新鲜声明（打开 + 心跳未过期）与 READY 同等受保护，绝不能被抢走。

握手顺序：

```text
HELLO -> WELCOME {} -> READY {"version":1,"skills":[...],"mcpServers":[...]}
```

READY payload 是严格版本化/类型化的能力对象（`DaemonCapabilities` v1）：`skills` 为 name/description 摘要，`mcpServers` 为 name/status/error/tools(name+description) 摘要。**摘要绝不包含 headers/environment 值、命令、URL、本地路径或完整工具 schema**；完整 schema 只通过固定 `mcp_list_tools` 桥接工具按需返回。READY 每个连接恰好一次；`HEARTBEAT` 刷新 `lastSeen`；断线时 registry 移除该名称。固定目录版本为 `EnvironmentToolCatalog.version()`，首个 READY 之后不重新协商；`DaemonToolRegistry` 与目录按名称、版本、schema、prompt、side effect 和 timeout 完全一致，启动时校验后冻结。

**并发约束**：每个 Environment 同时最多 1 个 active remote invocation——`EnvironmentDaemonGateway` 以 `activeByEnvironment` 登记 in-flight 调用，已存在 active 时新 INVOKE 确定性失败（`environmentName already has an active remote tool invocation`）。发送 CANCEL 只做幂等取消请求；active 槽位在 terminal callback 或连接 cleanup 时释放。

只读查询：

```text
GET /api/ai/environment
```

返回 `name`（canonical 路由身份，唯一键）/ `status` / `ready`（统一可用性标记）/ `tools` / `skills` / `mcpServers`（server 状态与工具摘要）/ `lastSeen`。无 create/update/delete API。

## 本地 MCP server

Daemon 可选的 `--mcp-config` 指向严格 UTF-8 JSON（`{"servers":[...]}`；拒绝未知字段、重复键、重复 server 名与 transport 不适用字段）：

```json
{
  "servers": [
    {
      "name": "filesystem",
      "transport": "stdio",
      "timeoutSeconds": 30,
      "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem"],
      "environment": {"LANG": "C"}
    },
    {
      "name": "remote",
      "transport": "streamable-http",
      "url": "https://mcp.example.com/mcp",
      "headers": {"Authorization": "Bearer ..."}
    }
  ]
}
```

- server 名必须是 canonical 小写连字符命名（`[a-z][a-z0-9-]{1,64}`，上限 64 字符）；`transport` 为 `stdio` / `streamable-http` / `websocket`；`timeoutSeconds` 可选且必须为正整数；stdio 要求非空 `command`（可选 `environment`），HTTP/WS 要求合法 scheme 的 `url`（可选 `headers`）；environment/headers 的键必须非空白。
- 每个配置的 server **独立初始化**（LangChain4j `DefaultMcpClient` + 对应 transport）：单个失败只记录为 `FAILED`（错误摘要单行有界且剔除 headers/environment 值、命令与 URL），不阻断 coding tools、skills 或其它 server；启动失败的 server 保持 `FAILED` 直到 daemon 重启。退出时对每个成功创建的 client 恰好关闭一次。
- MCP 工具**从不**动态进入 `EnvironmentToolCatalog` 或 Agent 可选目录；只有两个固定桥接工具始终注册（即使未配置 `--mcp-config`）：
  - `mcp_list_tools`（READ_ONLY）：确定性 JSON 报告全部/单个 server 的状态与 READY server 的工具（name/description/完整输入 schema）；
  - `mcp_call_tool`（NON_IDEMPOTENT）：按精确 `server`/`tool`/JSON 对象 `arguments` 调用，保留上游 isError 与文本/结构化 JSON 结果；未知/未 READY server 或未知工具是确定性错误。

Skill 通过 `LOAD_SKILL` / `SKILL_LOADED` / `SKILL_LOAD_FAILED` 按需加载；`load_skill` 是内部 `PLATFORM` Tool，不出现在 Agent 可选择目录中，必须由 Agent 的 `activeTools` 显式选择（Resolver 不做隐式追加）。

## Invocation 分发

`harness_tool_invocation.request` 冻结 binding（descriptor/type/environmentName）。统一 `ToolProcessor` 只消费 dispatcher 已 claim 的 TOOL Work：

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

    D->>W: HELLO (environmentName, token)
    W->>G: HELLO envelope
    G->>R: tryBind(name, connection)
    G-->>W: WELCOME {}
    W-->>D: WELCOME {}
    D->>W: READY(capabilities)
    W->>G: READY(capabilities)
    G->>R: mark READY, capabilities, lastSeen
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

Wire `invocationId` 始终是持久 Invocation ID 的十进制字符串。每个 envelope 由 `environmentName` 作用域校验；连接/环境/invocation ownership 不匹配的回调被拒绝。

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

独立 Daemon 进程通过 `CodingTools.registerAll` 注册固定目录中的九个 coding tool：

```text
read, write, edit, bash, grep, find,
lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile
```

另由 `McpBridgeTools.registerAll` 注册固定桥接工具 `mcp_list_tools` / `mcp_call_tool`（见「本地 MCP server」），Environment 固定目录共 11 个工具。动态 MCP 工具绝不进入该目录。

静态 MIT prompt 资源位于 `harness/daemon/src/main/resources/.../coding/prompts/`。LSP bridge 协议为 JSON stdin/stdout；未配置 bridge 时不得伪造成功结果。`CodingToolsConfig` 的默认 preview 上限为 2000 行 / 50KB，超出部分外部化为 ResourceRef。
