# Environment Daemon Gateway

本文描述全局 live Environment 注册表与 Daemon v2 WebSocket Gateway 的当前协议、内存边界和运行约束。

## 职责与边界

产品级 Tool 只有 `PLATFORM` / `ENVIRONMENT` 两类。Environment 是**服务器内存**中的实时资源，以 canonical `EnvironmentName`（bounded 小写路由名称）为 route 身份唯一。HELLO 后 registry 即保存绑定连接并标为 `CONNECTING`，此时 capabilities 为 null；READY 先补齐严格 v3 能力对象（environment metadata + skills + MCP server 摘要），再转为 `READY`。可用性 = READY + 连接打开 + 心跳未过期（单一配置超时）。ToolInvocation 仍是 PostgreSQL durable 执行事实；WebSocket 连接、Daemon 进程和 Gateway 内存句柄都是可丢弃传输状态。

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
  --note "Local development environment." \
  --workdir /home/dev/project \
  --skill-dir ~/.agents/skills \
  --mcp-config /etc/kk-studio/daemon-mcp.json \
  --daemon-id optional-stable-daemon-name
```

`environment-name`、`gateway-uri` 与 `gateway-token` 必填；连接、身份、note、workdir、skill 与 MCP 配置只来自 CLI。`--note` 可选且只能出现一次，显式值必须非空、单行、无 ISO control、无首尾空白且不超过 512 字符；它是会进入受信任 SYSTEM Prompt 的模型可见配置，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。下游 XML escape 只保证模板结构安全，不能把不可信内容转化为可信指令。省略 `--note` 时按 Daemon 实测 OS 生成固定说明：Windows=`Windows environment.`、WSL=`WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows commands may be invocable from WSL.`、Linux=`Linux environment.`、macOS=`macOS environment.`。唯一工作目录参数是 `--workdir`：显式值必须是已存在目录并 canonical/绝对化，未提供时使用启动用户 canonical HOME；该目录只约束 CodingTools 本地边界，不进入 READY 或模型 Prompt。`--skill-dir` 可重复，未提供时若存在则默认 `~/.agents/skills`。`--mcp-config` 可选，指向严格的 UTF-8 JSON 文件（见「本地 MCP server」）。Environment 身份即 CLI 声明的 canonical 名称，随每个 envelope 参与作用域校验。

## 认证、绑定与目录

Daemon 连接后的首帧必须是 HELLO。canonical `environmentName` 位于 **envelope**；HELLO **payload** 字段为 `{"daemonId","protocolVersion","toolCatalogVersion","gatewayToken"}`。Gateway 验证 envelope 作用域、共享密钥与协议版本，并以 `environmentName` 绑定实时 Environment。绑定是原子的三态判定（`BindResult`）：

- **Accepted**：无现有条目或同连接幂等重绑，正常完成握手；
- **Rejected**：现有持有者连接仍打开且心跳未过期（租约有效）——后到同名 HELLO 收到 typed ERROR（`code=ENVIRONMENT_NAME_CONFLICT`）并关闭连接，Daemon 视为终态失败停止重连并以非零码退出；
- **Replaced**：现有持有者连接已关闭或心跳超过配置超时（lastSeen 租约过期）——registry 原子切换到新持有者，Gateway 把被替换的旧连接状态恰好清理一次（旧连接 close、其 active remote 按不确定收敛、pending skill load 失败），绝不触碰新持有者，也不泄漏任何执行工作。CONNECTING 的新鲜声明（打开 + 心跳未过期）与 READY 同等受保护，绝不能被抢走。

握手顺序：

```text
HELLO -> WELCOME {} -> READY {"version":3,"environment":{...},"skills":[...],"mcpServers":[...]}
```

READY payload 是严格版本化/类型化的能力对象（`DaemonCapabilities` v3），固定字段顺序为 `version/environment/skills/mcpServers`。`environment={operatingSystem,timeZone,note}` 必填且只允许这三个字段：OS wire 值只允许 `windows|wsl|linux|macos`，timeZone 是系统 `ZoneId` ID，note 是显式 `--note` 或对应 OS 的固定默认说明；三项在 `DaemonRuntime` 构造时冻结，重连重复发送同一 metadata。OS 只能由 Daemon 检测：Windows、macOS/Darwin、Linux 保持直接分类；Linux 下先检查 `WSL_DISTRO_NAME` / `WSL_INTEROP`，再以 `/proc/version` 与 `/proc/sys/kernel/osrelease` 的 Microsoft 标记兜底，未知平台 fail closed。codec 拒绝 v2、缺失/未知/重复字段、尾随内容、非法 OS/timeZone/note。`skills` 为 name/description 摘要，`mcpServers` 为 name/status/error/tools(name+description) 摘要。

READY 不上报 workdir 或其它本地路径，并禁止 SKILL.md 正文、headers/environment 值、命令、URL 与完整工具 schema；完整 schema 只通过固定 `mcp_list_tools` 桥接工具按需返回。公共 `GET /api/ai/environment` 也不投影 operatingSystem/timeZone/note。READY 每个连接恰好一次；Gateway 必须先 `updateCapabilities` 再 `markReady`，而 registry 的 `markReady` 会拒绝 null capabilities；`HEARTBEAT` 刷新 `lastSeen`；断线时 registry 移除该名称。固定目录版本为 `EnvironmentToolCatalog.version()`，首个 READY 之后不重新协商；`DaemonToolRegistry` 与目录按名称、版本、schema、prompt、side effect 和 timeout 完全一致，启动时校验后冻结。

**并发约束**：每个 Environment 同时最多 1 个 active remote invocation——`EnvironmentDaemonGateway` 以 `activeByEnvironment` 登记 in-flight 调用。已存在 active 时，同 Environment 的并发 sibling 在发送任何 wire INVOKE 前返回 typed `RemoteToolBusyException`；`CoreToolGateway` 把它映射为带配置延迟的 `ToolGateway.Busy`，Harness 将调用重新调度并以 retry 序列化，而不是写入 terminal failure。不同 Environment 的 active 槽位互相独立，可以并发执行。发送 CANCEL 只做幂等取消请求，不立即释放槽位；active 槽位仍在 COMPLETED / FAILED / CANCELLED terminal callback 或连接 cleanup 时释放。

只读查询：

```text
GET /api/ai/environment
```

返回 `name`（canonical 路由身份，唯一键）/ `status` / `ready`（统一可用性标记）/ `tools` / `skills` / `mcpServers`（server 状态与工具摘要）/ `lastSeen`。不公开 READY environment metadata；无 create/update/delete API。

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

Skill 通过 `LOAD_SKILL` / `SKILL_LOADED` / `SKILL_LOAD_FAILED` 按需加载；`load_skill` 是内部 `PLATFORM` Tool（与委派工具 `task` 一样不在 Agent 可选择目录中），必须由 Agent 的 `activeTools` 显式选择（Resolver 不做隐式追加）。Environment 固定目录始终是 11 个工具，不因 Agent 能力或任务委派变化。

## Invocation 分发

`harness_tool_invocation.request` 冻结 binding（descriptor/type/environmentName/plugin）；ENVIRONMENT binding 的 plugin 恒为 null。统一 `ToolProcessor` 只消费 dispatcher 已 claim 的 TOOL Work：

```text
claim TOOL Work（Work-only 短事务）
  -> ToolGateway.preflight（权限 + 机械校验，事务外）
  -> 两阶段激活（start 返回 Started 后由 Processor 在 durable markRunning 之后调用 activate）
  -> RemoteToolTransport.send -> Gateway -> Daemon INVOKE
  -> 回调（serialized FIFO）：STARTED / PARTIAL / COMPLETED / FAILED / CANCELLED
  -> CoreToolGateway 回调桥内 ToolResultExternalizer（durable 外部化）
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

- daemon coding tool 自身可因输出超过 preview 限制（默认 2000 行 / 50KB）或二进制内容产生 Resource（daemon 侧 `resourceStore.store` 落盘并返回 ref）；ANSI terminal 文本中的 ESC 不单独触发二进制判定，常见彩色测试输出仍以可读 Text 投影；
- core 对 Text/Json >8KB 及 Binary 内容统一在 `ToolResultExternalizer`（CoreToolGateway callback bridge）外部化：先 `ResourceStore.reference` 无副作用计划、再逐项 `put`、返回 ref 与计划精确相等。Text/Json 的 durable `ResourceToolContent` 同时携带最多 16 KiB 的 UTF-8 安全 preview（能完整容纳时原样保留，否则稳定前缀加截断标记）；Binary preview 为 null。codec 继续接受旧的无 preview Resource JSON。

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

静态 Tool prompt 资源位于 `harness/tool/src/main/resources/.../environment/prompts/`。LSP bridge 协议为 JSON stdin/stdout；未配置 bridge 时不得伪造成功结果。`DaemonMain` 只以 `DaemonConfig.workdir()` 构造 coding 配置，`CodingToolsConfig.environmentRoot/defaultWorkdir` 都等于该 canonical 目录；旧 `kkstudio.daemon.environment-root` / `kkstudio.daemon.default-workdir` 系统属性不再读取。

`grep` / `find` 由 Java 21 NIO、regex 与仓库内 glob/`.gitignore` 规则实现，不启动 `rg`、`fd`、`grep` 或 `find` 子进程，也不读取对应 executable 系统属性。搜索不会跟随符号链接，硬排除 `.git`，按 workdir 相对 POSIX 路径稳定排序，并从 environment root 到搜索目录逐层应用 `.gitignore`；被忽略目录在加载后代规则前剪枝。目录 grep 跳过二进制或不可读文件，直接二进制目标返回错误。`bash`、可选 LSP bridge、`javap` 与 resource 相关系统属性保持有效。默认 preview 上限为 2000 行 / 50KB，超出部分外部化为 ResourceRef。
