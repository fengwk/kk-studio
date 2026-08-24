# Environment Daemon Gateway

本文描述全局 live Environment 注册表与 Daemon WebSocket Gateway（端点路径 v2；envelope 协议 v3、capabilities v4）的当前协议、内存边界和运行约束。

## 职责与边界

产品级 Tool 只有 `PLATFORM` / `ENVIRONMENT` 两类。Environment 是**服务器内存**中的实时资源，以 canonical `EnvironmentName`（bounded 小写路由名称）为 route 身份唯一。HELLO 后 registry 即保存绑定连接并标为 `CONNECTING`，此时 capabilities 为 null；READY 先补齐严格 v4 能力对象（environment metadata（含 rootPath）+ skills + MCP server 摘要），再转为 `READY`。可用性 = READY + 连接打开 + 心跳未过期（单一配置超时）。ToolInvocation 仍是 PostgreSQL durable 执行事实；WebSocket 连接、Daemon 进程和 Gateway 内存句柄都是可丢弃传输状态。

| 层 | 职责 |
| --- | --- |
| `platform/environment` | `LiveEnvironmentRegistry`、`EnvironmentDaemonGateway`（endpoint/skill loader/transport）、daemon 协议 codec |
| `harness-tool` | route-neutral Tool API、`EnvironmentName`、`ResourceRef`、`RemoteTool`、Daemon v3 envelope/result codec |
| `harness-daemon` | 独立 Daemon 连接、重连、本地工具执行、invocation journal 与 skill 发现 |
| `harness-runtime` | 统一 `ToolProcessor`、ToolInvocation durable 状态与冻结 route 路由 |
| `web` | 提供 `/api/ai/environment/daemon/v2` WebSocket 文本帧与只读 `GET /api/ai/environment`；不直接消费 Harness 类型 |

数据库是 ToolInvocation 状态、lease、终态结果与所属 Thread 推进的唯一来源。partial 进度通过 PostgreSQL realtime notification 投递，丢失时由 Thread snapshot 恢复。Gateway 拥有连接与协议，不是第二套 durable 状态机。

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
      max-message-bytes: ${KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES:16777216}
      queue-capacity: ${KK_STUDIO_ENVIRONMENT_GATEWAY_QUEUE_CAPACITY:256}
      max-bytes: ${KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_BYTES:16777216}
      send-timeout: ${KK_STUDIO_ENVIRONMENT_GATEWAY_SEND_TIMEOUT:10s}
```

`daemon-token` 是部署范围的共享连接密钥；Gateway 在 HELLO 中以常量时间比较它；缺失、空白或不匹配的密钥会关闭连接。生产部署使用 TLS 终止后的 `wss://`。`max-message-bytes` 是 Daemon WebSocket 入站单帧上限（默认 16MiB）；`queue-capacity` / `max-bytes` 分别限制每连接出站待发送帧数与 UTF-8 总字节（均含在途帧）；`send-timeout` 限制单帧底层发送时间。它们都是部署级传输安全边界，不进 SystemSettings。

Web 为每个 Daemon WebSocket 创建独立 `DaemonOutboundSender`：Gateway 的 `sendText` 只做非阻塞有界入队，唯一 sender 虚拟线程按 envelope sequence 严格串行调用 Spring WebSocket `sendMessage`，并由 `ConcurrentWebSocketSessionDecorator` 施加 send time/buffer 上限。Gateway 的连接状态锁内不执行网络 I/O；慢连接不阻塞 receive、heartbeat 或其它连接的 bind。队列帧数/字节拒绝、底层发送异常或超时都使整条连接失败：先解绑 live registry，并以 uncertain/不可用结果完成 active invocation、pending skill load 与 pending directory list，再幂等关闭 sender；不得跳过 `WELCOME` / `INVOKE` / `CANCEL` / `LOAD_SKILL` / `LIST_DIRECTORY` 等任一已分配 sequence 的帧后继续使用连接。

Daemon 以 CLI 参数启动（`harness-daemon`，只依赖 `harness-tool`）：

```text
java ... DaemonMain \
  --environment-name local-dev \
  --gateway-uri ws://studio.example/api/ai/environment/daemon/v2 \
  --gateway-token ${KK_STUDIO_DAEMON_TOKEN} \
  --note "Local development environment." \
  --environment-root /home/dev/project \
  --skill-dir ~/.agents/skills \
  --mcp-config /etc/kk-studio/daemon-mcp.json \
  --daemon-id optional-stable-daemon-name
```

`environment-name`、`gateway-uri` 与 `gateway-token` 必填；连接、身份、note、environment root、skill 与 MCP 配置只来自 CLI。`--note` 可选且只能出现一次，显式值必须非空、单行、无 ISO control、无首尾空白且不超过 512 字符；它是会进入受信任 SYSTEM Prompt 的模型可见配置，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。下游 XML escape 只保证模板结构安全，不能把不可信内容转化为可信指令。省略 `--note` 时按 Daemon 实测 OS 生成固定说明：Windows=`Windows environment.`、WSL=`WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.`、Linux=`Linux environment.`、macOS=`macOS environment.`。唯一工作目录参数是 `--environment-root`：显式值必须是已存在目录并 canonical/绝对化，未提供时使用启动用户 canonical HOME；该目录约束 CodingTools 本地边界，并以 READY `rootPath` 只读展示（不进入模型 Prompt），目录浏览 API 的路径也相对它解析。

## 认证、绑定与目录

Daemon 连接后的首帧必须是 HELLO。canonical `environmentName` 位于 **envelope**；HELLO **payload** 字段为 `{"daemonId","protocolVersion","toolCatalogVersion","gatewayToken"}`。Gateway 验证 envelope 作用域、共享密钥与协议版本，并以 `environmentName` 绑定实时 Environment。绑定是原子的三态判定（`BindResult`）：

- **Accepted**：无现有条目或同连接幂等重绑，正常完成握手；
- **Rejected**：现有持有者连接仍打开且心跳未过期（租约有效）——后到同名 HELLO 收到 typed ERROR（`code=ENVIRONMENT_NAME_CONFLICT`）并关闭连接，Daemon 视为终态失败停止重连并以非零码退出；
- **Replaced**：现有持有者连接已关闭或心跳超过配置超时（lastSeen 租约过期）——registry 原子切换到新持有者，Gateway 把被替换的旧连接状态恰好清理一次（旧连接 close、其 active remote 按不确定收敛、pending skill load 失败），绝不触碰新持有者，也不泄漏任何执行工作。CONNECTING 的新鲜声明（打开 + 心跳未过期）与 READY 同等受保护，绝不能被抢走。

握手顺序：

```text
HELLO -> WELCOME {} -> READY {"version":4,"environment":{...},"skills":[...],"mcpServers":[...]}
```

READY payload 是严格版本化/类型化的能力对象（`DaemonCapabilities` v4），固定字段顺序为 `version/environment/skills/mcpServers`。`environment={operatingSystem,timeZone,note,rootPath}` 必填且只允许这四个字段：OS wire 值只允许 `windows|wsl|linux|macos`，timeZone 是系统 `ZoneId` ID，note 是显式 `--note` 或对应 OS 的固定默认说明，rootPath 是 canonical 化后的 Environment Root 绝对路径；四项在 `DaemonRuntime` 构造时冻结，重连重复发送同一 metadata。OS 只能由 Daemon 检测：Windows、macOS/Darwin、Linux 保持直接分类；Linux 下先检查 `WSL_DISTRO_NAME` / `WSL_INTEROP`，再以 `/proc/version` 与 `/proc/sys/kernel/osrelease` 的 Microsoft 标记兜底，未知平台 fail closed。codec 拒绝旧协议版本与未来版本、缺失/未知/重复字段、尾随内容、非法 OS/timeZone/note/rootPath。`skills` 为 name/description 摘要，`mcpServers` 为 name/status/error/tools(name+description) 摘要。

READY 只上报 canonical `rootPath`（Environment Root 展示路径），不上报其它本地路径，并禁止 SKILL.md 正文、headers/environment 值、命令、URL 与完整工具 schema；完整 schema 只通过固定 `mcp_list_tools` 桥接工具按需返回。公共 `GET /api/ai/environment` 只投影 rootPath，不投影 operatingSystem/timeZone/note。READY 每个连接恰好一次；Gateway 必须先 `updateCapabilities` 再 `markReady`，而 registry 的 `markReady` 会拒绝 null capabilities；`HEARTBEAT` 刷新 `lastSeen`；断线时 registry 移除该名称。固定目录版本为 `EnvironmentToolCatalog.version()`，首个 READY 之后不重新协商；`DaemonToolRegistry` 与目录按名称、版本、schema、prompt、side effect 和 timeout 完全一致，启动时校验后冻结。

**并发约束**：每个 Environment 同时最多 1 个 active remote invocation——`EnvironmentDaemonGateway` 以 `activeByEnvironment` 登记 in-flight 调用。已存在 active 时，同 Environment 的并发 sibling 在发送任何 wire INVOKE 前返回 typed `RemoteToolBusyException`；`PlatformToolGateway` 把它映射为带配置延迟的 `ToolGateway.Busy`，Harness 将调用重新调度并以 retry 序列化，而不是写入 terminal failure。不同 Environment 的 active 槽位互相独立，可以并发执行。发送 CANCEL 只做幂等取消请求，不立即释放槽位；active 槽位仍在 COMPLETED / FAILED / CANCELLED terminal callback 或连接 cleanup 时释放。

只读查询：

```text
GET /api/ai/environment
```

返回 `name`（canonical 路由身份，唯一键）/ `status` / `ready`（统一可用性标记）/ `tools` / `skills` / `mcpServers`（server 状态与工具摘要）/ `rootPath`（canonical Environment Root 展示路径）/ `lastSeen`。不公开 READY operatingSystem/timeZone/note metadata；无 create/update/delete API。

## Environment Root 目录浏览

Environment Root 的单层目录浏览是 **control-plane 只读查询**：不走 Tool Invocation，不经过 Permission，不占用 active tool slot，不写入 invocation journal，也不进入模型上下文。workspace 绑定的 canonical 路径规则与原子语义见 [environment-workspace-binding.md](environment-workspace-binding.md)。

```text
GET /api/ai/environments/{name}/directories?path=.
```

- `path` 缺省为 `'.'`（Environment Root），是可选的 canonical 相对 wire 路径（段一律以 `'/'` 分隔，跨平台拒绝反斜杠）：拒绝 absolute、空段、`'.'`/`'..'` 段、ISO 控制字符与空白路径；`{name}` 是 canonical `EnvironmentName`，非法名称 400 `INVALID_ENVIRONMENT_NAME`。
- 响应 DTO：`path`（canonical 相对 wire 路径，root 为 `'.'`）/ `displayPath`（请求 `path` 的最后一段，root 为 `'.'`；只作展示、绝不暴露 daemon 本地绝对路径，codec 严格拒绝其它值）/ `parentPath`（必须等于请求 `path` 的 lexical 父路径：root 与单段路径均为 `'.'`）/ `truncated`（超过单层上限 1000 条被截断）/ `gitBranch`（可空，浏览目录所在 git 仓库的 symbolic HEAD 分支）/ `entries`（按名称稳定排序的直属子目录，至多 1000 条，`{name,path}`：`name` 是目录名且必须等于 `path` 最后一段，`path` 必须是请求目录的直接子路径；不含 symlink 与非目录；无法编码为合法 wire 子路径的本地目录名被跳过）。
- symlink 语义：列表默认不暴露 symlink 目录；显式请求 root 内 symlink alias 时，成功响应的 `path`/`parentPath`/entry `path` 使用请求的 canonical wire 路径（回显 alias 本身），daemon 内部只用 real path 校验与读取，绝不越过 root；越出 root 的 symlink 穿越是 `INVALID_PATH`。
- HTTP 错误映射（`errorCode.code` 与应用结果分类同名）：`ENVIRONMENT_NOT_FOUND`（registry 无该环境）/ `NOT_FOUND`（路径不存在）→ 404；`ENVIRONMENT_UNAVAILABLE`（环境已注册但未 READY，或连接/心跳不可用）→ 409；`INVALID_PATH` / `NOT_DIRECTORY` → 400；`TIMEOUT`（daemon 往返超时，默认 10 秒，来自 `system_setting.config.environment.directoryListTimeoutMillis`，每次请求现读）→ 504；`IO_ERROR`（daemon 本地 IO 失败）→ 502。
- Controller 直接返回 `CompletionStage<Result<...>>` 交给 Spring MVC async dispatch，不在 servlet 请求线程 `join`；typed 结果仍按上表映射，异常完成的 timeout 映射 504，其它传输异常映射 502。

wire 消息配对（目录控制面不属于 invocation：envelope `invocationId` 必须为 null，gateway/daemon 以 payload `requestId`（canonical UUID，响应原样回显）关联；sequence 是连接级连续计数，`LIST_DIRECTORY` 是出站消息不占入站序号）：

| Daemon 消息 | payload | 方向 |
| --- | --- | --- |
| `LIST_DIRECTORY` | `{"requestId","path"}` | gateway → daemon |
| `DIRECTORY_LISTED` | `{"requestId","path","displayPath","parentPath","entries":[{"name","path"}],"truncated","gitBranch"}` | daemon → gateway |
| `DIRECTORY_LIST_FAILED` | `{"requestId","path","code","message"}`（`requestId` 是 canonical UUID 回显；`path` 是请求回显归因，只要求非空） | daemon → gateway |

- 三类 payload 共享严格 codec（ObjectMapper 启用 STRICT_DUPLICATE_DETECTION 与 FAIL_ON_TRAILING_TOKENS）：顶层与 entry 的 duplicate/trailing/unknown/missing 字段全部拒绝；`requestId` 必须 canonical UUID。
- 失败分类分成两层，职责不混合：wire 只承载 daemon 的确定性失败——`DaemonDirectoryFailureCode` 枚举 `INVALID_PATH`（wire 路径形状非法或越界）/ `NOT_FOUND` / `NOT_DIRECTORY` / `IO_ERROR`；gateway 在本地计算应用结果 `EnvironmentDirectoryFailureCode` 的 `ENVIRONMENT_NOT_FOUND` / `ENVIRONMENT_UNAVAILABLE` / `TIMEOUT`（其余 code 与 wire 同名投影）。
- daemon 侧 `EnvironmentDirectoryBrowser` 安全契约：`environmentRoot.resolve(path).normalize()` 后 `toRealPath()` canonicalize，越出 root 边界即 `INVALID_PATH`；符号链接不跟随且不列入结果；只列目录；按名称稳定排序；至多 1000 条，超出置 `truncated`；本地子目录名若无法通过共享 `EnvironmentWorkspacePath` 编码为合法 wire 子路径则跳过该条目。失败响应必须能归因非法请求路径，因此 `DIRECTORY_LIST_FAILED.path` 只校验非空。
- daemon 目录 IO 与 Coding/MCP 等阻塞调用统一提交到 `DaemonRuntime` 持有的 virtual-thread-per-task executor；inbound 回调只解析/校验/ACK。executor 已关闭或拒绝任务时立即回 `DIRECTORY_LIST_FAILED`/`IO_ERROR`。
- gateway 侧 pending 管理：`EnvironmentDaemonGateway.listDirectory` 用控制面发送（不登记 active invocation 槽位），先用 `LiveEnvironmentRegistry.find` 区分环境未知（`ENVIRONMENT_NOT_FOUND`）与已注册但未 READY/心跳过期/连接不可用（`ENVIRONMENT_UNAVAILABLE`）；pending 请求在断线清理、超时（`directoryListTimeout`）与协议失败（含 sequence 错乱）时以 `ENVIRONMENT_UNAVAILABLE` / `TIMEOUT` 恰好完成一次，不泄漏未决 future；发送结果不确定（UNCERTAIN）时关闭连接让清理恰好一次。
- 超时后的合法晚响应：timeout 时 pending 转为有界 tombstone（requestId/environment/connection/path，每连接最多 1024 条，FIFO 淘汰；断线清理同时清除）；同连接的晚响应仍严格 decode 并校验 requestId/path 回显后静默丢弃，不当作 unsolicited 协议错误；未知 requestId（无 pending 也无 tombstone）仍是协议失败。handler 先 peek+ownership 再严格 decode/校验，最后 identity remove 并完成：malformed/mismatch 不会把 pending 提前移走，协议关闭路径立即以 `ENVIRONMENT_UNAVAILABLE` 完成该 Future。

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

- server 名必须是 canonical 小写连字符命名（`[a-z][a-z0-9-]{0,63}`，上限 64 字符）；`transport` 为 `stdio` / `streamable-http` / `websocket`；`timeoutSeconds` 可选且必须为正整数；stdio 要求非空 `command`（可选 `environment`），HTTP/WS 要求合法 scheme 的 `url`（可选 `headers`）；environment/headers 的键必须非空白。
- 每个配置的 server **独立初始化**（LangChain4j `DefaultMcpClient` + 对应 transport）：单个失败只记录为 `FAILED`（错误摘要单行有界且剔除 headers/environment 值、命令与 URL），不阻断 coding tools、skills 或其它 server；启动失败的 server 保持 `FAILED` 直到 daemon 重启。退出时对每个成功创建的 client 恰好关闭一次。
- 每个 READY server 的完整 `McpToolSpec` 在初始化时一次性读取并冻结；READY wire 只投影 name/description 摘要，`mcp_list_tools` 返回同一冻结规格中的完整 schema，`mcp_call_tool` 也只按这份冻结 allowlist 校验。运行期间不再调用 `client.listTools()`，不存在 live/frozen 双目录。
- MCP 工具**从不**动态进入 `EnvironmentToolCatalog` 或 Agent 可选目录；只有两个固定桥接工具始终注册（即使未配置 `--mcp-config`）：
  - `mcp_list_tools`（READ_ONLY）：确定性 JSON 报告全部/单个 server 的状态与 READY server 的工具（name/description/完整输入 schema）；
  - `mcp_call_tool`（NON_IDEMPOTENT）：按精确 `server`/`tool`/JSON 对象 `arguments` 调用，保留上游 isError 与文本/结构化 JSON 结果；未知/未 READY server 或未知工具是确定性错误。

Skill 通过 `LOAD_SKILL` / `SKILL_LOADED` / `SKILL_LOAD_FAILED` 按需加载；`load_skill` 是内部 `PLATFORM` Tool（与委派工具 `task` 一样不在 Agent 可选择目录中）。每个新 turn 由 Resolver 从最新 Agent config 派生工具集合：skills 非空时追加 `load_skill`，subagents 非空且未达最大深度时追加 `task`；历史 `BranchSettings.activeTools` 只作投影，不限制或扩张当前能力。Environment 固定目录始终是 11 个工具，不因 Agent 能力或任务委派变化。

## Invocation 分发

`harness_tool_invocation` 持久化 `call` + 可空 `binding`（binding 冻结 descriptor/type/environment binding/plugin；ENVIRONMENT binding 的 plugin 恒为 null，unknown tool 槽位 binding 为 null 且 renderer 固定回退 `tool`）；只有 READY Tool 在 ToolProcessor/Gateway 边界临时构造 executable request。统一 `ToolProcessor` 只消费 dispatcher 已 claim 的 TOOL Work：

```text
claim TOOL Work（Work-only 短事务）
  -> ToolGateway.preflight（权限 + 机械校验，事务外）
  -> 两阶段激活（start 返回 Started 后由 Processor 在 durable markRunning 之后调用 activate）
  -> RemoteToolTransport.send -> Gateway -> Daemon INVOKE
  -> 回调（serialized FIFO）：STARTED / PARTIAL / COMPLETED / FAILED / CANCELLED
  -> PlatformToolGateway 回调桥内 ToolResultExternalizer（durable 外部化）
  -> ToolProcessor 接收 ToolSuccess(result, empty effects) -> terminal CAS -> 请求 THREAD Work 做 sibling apply
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
    G->>R: update capabilities + lastSeen
    G->>R: mark READY
    R-->>G: READY hint
    G-->>WD: wake hint
    WD->>DB: claim due TOOL Work
    WD-->>TP: bounded handoff
    TP->>G: RemoteTool send INVOKE
    G->>D: INVOKE (frozen name@version, arguments, timeout)
    D->>G: STARTED / PARTIAL / COMPLETED / FAILED / CANCELLED
    G->>TP: transport callback (ownership-checked)
    TP->>DB: partial -> realtime NOTIFY; terminal -> ToolInvocation + Work
```

Wire `invocationId` 始终是持久 Invocation ID 的 canonical UUID string。每个 envelope 由 `environmentName` 作用域校验；连接/环境/invocation ownership 不匹配的回调被拒绝。

分发前校验：

1. 调用未取消、未过期；
2. 冻结 `name@version` 必须命中共享固定目录；
3. frozen arguments 是 JSON object；
4. recovered lease 的非幂等调用保守收敛为 `UNKNOWN`，不重发。

连接丢失或发送失败（send outcome uncertain / connection-close notify）对 active listener 上报 uncertain；`RemoteToolSendUncertainException` 由 `PlatformToolGateway` 令 Invocation 收敛 `UNKNOWN`，**不重发可能已发生的副作用**。瞬时 active handle 只是传输状态，持久 lease 与 fencing 仍由 PostgreSQL claim 约束。

## 回调、结果与 Resource

Daemon 回调使用连续 sequence；相同 sequence 的完全相同 envelope 是幂等重放。

| Daemon callback | 效果 |
| --- | --- |
| `STARTED` | transport 执行确认 |
| `PARTIAL` | 解码 result，经 ToolProcessor 写入 PostgreSQL realtime notification（TOOL_PARTIAL 不携带 Resource） |
| `COMPLETED` | Gateway 解码 Resource 为**瞬时 BinaryToolContent** 后交付回调桥；`PlatformToolGateway` 在桥内做 durable 外部化，随后 `SUCCEEDED` terminal CAS |
| `FAILED` | `FAILED` terminal CAS |
| `CANCELLED` | `CANCELLED` terminal CAS |

`ACK` / `ERROR` 只描述 transport，不单独改写 durable 状态。`DaemonToolResultCodec` 使用严格 JSON shape；Gateway 的 COMPLETED 解码会把 Resource 引用读回并校验为瞬时 Binary 内容，**不直接形成最终 durable file URI**：

- daemon coding tool 自身可因输出超过 preview 限制（默认 2000 行 / 50KB）或二进制内容产生 Resource（daemon 侧 `resourceStore.store` 落盘并返回 ref）；ANSI terminal 文本中的 ESC 不单独触发二进制判定，常见彩色测试输出仍以可读 Text 投影；
- platform 对 Text/Json >8KB 及 Binary 内容统一在 `ToolResultExternalizer`（PlatformToolGateway callback bridge）外部化：先 `ResourceStore.reference` 无副作用计划、再逐项 `put`、返回 ref 与计划精确相等。Text/Json 的 durable `ResourceToolContent` 同时携带最多 16 KiB 的 UTF-8 安全 preview（能完整容纳时原样保留，否则稳定前缀加截断标记）；Binary preview 为 null。preview 是可选字段，缺省时按 null 解码。

terminal CAS 成功后请求 owning Thread Work；CAS 失败表示 ownership 已丢失，仅丢弃本地 handle。

## Daemon 本地执行

Daemon 的 invocation journal 是去重事实源（WebSocket 仅传递消息），记录本地 RUNNING/terminal 状态。重连后重新完成 HELLO/WELCOME/READY 握手。重复 `INVOKE` 在 journal 命中 RUNNING 时返回 `STARTED {"replayed":true}`，命中终态时重放该终态。

Daemon 仅依赖 `harness-tool`，不反向依赖 runtime/model。

### Coding tools

Environment 固定目录共 11 个工具。`CodingTools.registerAll` 注册 9 个 coding tool，它们是 pi-base 既有 coding 能力的本地 Java 实现：

```text
read, write, edit, bash, grep, find,
lsp_goto_definition, lsp_workspace_symbols, lsp_java_decompile
```

`McpBridgeTools.registerAll` 另注册固定桥接工具 `mcp_list_tools` / `mcp_call_tool`（见「本地 MCP server」）。`PLATFORM` 工具与动态 MCP 工具是独立边界，绝不进入该固定目录。

静态 Tool prompt 资源位于 `harness/tool/src/main/resources/.../environment/prompts/`。LSP bridge 协议为 JSON stdin/stdout；未配置 bridge 时不得伪造成功结果。`read` 文本输出 header 的 `lsp` 行反映同一 LspBridge 配置：配置了 `kkstudio.daemon.lsp-bridge` 时为 `lsp: supported`，否则为 `lsp: unsupported`。`edit` 执行确定性精确替换：old/new 在 LF/CRLF/CR 归一后相同、`replace_all` 下 occurrence 重叠、或编码后的输出字节与原文完全相同时拒绝写入，文件保持原样；匹配在 LF 归一空间进行，未修改区域原样保留 CR/LF/CRLF，`new_string` 内换行按文件检测样式插入（mixed 且歧义时回退 LF）。`DaemonRuntime` 是执行资源的唯一生命周期所有者：单线程 scheduled pool 统一处理 heartbeat、reconnect、invocation timeout 与 bash process timeout，另一个 virtual-thread-per-task executor 承载 Coding/MCP/目录 IO 等阻塞任务；Tool 不持有 static 或自建 executor。关闭时先停止 transport 接入，再取消运行任务，随后对 scheduler/executor 执行 `shutdownNow` 并有界等待，最后关闭 MCP；重复关闭幂等，生产装配失败也释放已创建资源。`DaemonMain` 只以 `DaemonConfig.environmentRoot()` 构造 coding 配置，`CodingToolsConfig` 只持有 canonical `environmentRoot`；每次 invocation 的默认 workdir 由 Daemon 在 INVOKE 时把 payload `workspacePath` canonicalize 为 Environment Root 内现存目录后写入 `ToolExecutionRequest.workdir`，coding tools 以它为缺省基准（相对 `workdir` 值也以其为基准，absolute 允许但必须 canonical 在 root 内）。旧 `kkstudio.daemon.environment-root` / `kkstudio.daemon.default-workdir` 系统属性不再读取。

`grep` / `find` 由 Java 21 NIO、regex、仓库内 glob 与 JGit `FastIgnoreRule` 实现，不启动 `rg`、`fd`、`grep` 或 `find` 子进程，也不读取对应 executable 系统属性。搜索不会跟随符号链接，硬排除 `.git`，按 environment root 相对 POSIX 路径稳定排序，并从 environment root 到搜索目录逐层应用标准 `.gitignore` 语义；被忽略目录在加载后代规则前剪枝。目录 grep 跳过二进制或不可读文件，直接二进制目标返回错误。`bash`、可选 LSP bridge、`javap` 与 resource 相关系统属性保持有效。默认 preview 上限为 2000 行 / 50KB，超出部分外部化为 ResourceRef。
