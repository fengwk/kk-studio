# Harness MCP

## 定位

`harness-mcp` 是 Platform 与 Environment Daemon 复用的 MCP client 基础库。它把
LangChain4j MCP SDK 收敛在单一模块内，对调用方只暴露稳定的配置、工具定义、调用结果、
总预算和取消契约，同时提供 Remote Streamable HTTP 与 Local stdio 两种传输。

本模块不持久化 MCP Server/Tool，不解析产品层 JSON 配置，不决定 Environment 路由，
也不缓存跨调用 client。Platform 负责 Remote catalog 与 per-call 执行；Daemon 负责
Local 配置解析、共享 client 代际和子进程排空。

## 职责

- 通过 `McpClient` 提供 `listTools`、`callTool` 与幂等关闭接口，公共签名不泄漏 SDK 类型。
- 以 `McpDeadline` 让初始化、握手和后续 list/call 消耗同一个绝对总预算。
- 以调用级 `McpCancellationToken` 终止单次等待；stdio 额外发送
  `notifications/cancelled`，不关闭共享 client。
- 为 Remote Streamable HTTP 构建 per-call transport；为 Local stdio 按 argv 直接拉起
  子进程并管理 stdin/stdout JSON-RPC、进程树和终态。
- 将协议、连接、超时与取消失败归一为不携带底层 cause 的稳定异常；库自身异常文本不
  回显 URL、headers、command、cwd、env 或协议载荷。

## 依赖边界

```text
harness-common + Jackson + langchain4j-mcp
                      |
                      v
                 harness-mcp
                  /       \
                 v         v
             platform  harness-daemon
```

生产依赖见 [`pom.xml`](../../harness/mcp/pom.xml)。本模块不依赖 Spring、JDBC、
`harness-tool`、`harness-environment`、Platform 或 Daemon。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.mcp` | MCP client/config/result 模型、LangChain4j 适配、deadline/cancellation 协调与自定义 stdio transport | 不持久化产品事实、不解析 Environment 路由、不持有跨调用共享目录 |

## 核心模型 / API

### Client 与配置

[`McpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClient.java)
定义工具发现、工具调用和关闭。返回值使用模块自有的
[`McpToolDefinition`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolDefinition.java)
与
[`McpToolCallResult`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)，
调用结果内容映射为 `harness-common` 的 `ResultContent`。

[`McpClientFactory`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java)
固定 client protocol version `2025-11-25`：

- `createRemote` 接收
  [`RemoteMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java)，
  构造 Streamable HTTP transport；调用方必须事先完成 Header 环境变量解析。
- `createStdio` 接收
  [`StdioMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/StdioMcpConfig.java)，
  要求已解析的非空 argv、本机绝对 cwd 和环境变量覆盖。
- client 初始化在独立 daemon thread 中完成；超时、失败或交接竞态都会关闭 transport，
  已构建实例要么唯一交给调用方，要么当场回收。

### 总预算与调用级取消

[`McpDeadline`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java)
只保存绝对截止时间。生产调用创建一次 deadline，并将同一实例依次传给 client factory
与 `listTools`/`callTool`；每个阶段只能消费剩余时间，不重置 timeout。

[`McpCancellationToken`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java)
提供线程安全的单次取消和 listener 注册。
[`McpRequestBindings`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpRequestBindings.java)
把 SDK request id 与调用 token 关联；`McpDispatchGate` 将 pending 登记与写出置于同一
临界区，确保“取消先到”时请求不入管道，“派发先到”时取消通知严格排在原请求之后。

### Stdio transport 生命周期

[`CustomStdioMcpTransport`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransport.java)
直接使用 `ProcessBuilder(argv)`，不经 shell；显式设置 cwd、覆盖子进程环境，并丢弃
stderr，避免阻塞和敏感输出。每次 start 建立独立 generation，进程退出只失败本代
pending 请求；旧代退出不能污染新代。

`close()` 是 sticky 终态：关闭后不得再次 spawn，并强制结束当前进程及全部后代。
单次 abort 只结束对应 request future 并发送 MCP 取消通知，其它并发请求与 client
继续运行。

## 不变量、failure / recovery

- 所有生产 list/call 都必须显式提供正的总预算；超时统一为 `McpTimeoutException`。
- `McpCancellationToken.none()` 不可取消且不保留 listener；普通 token 的重复取消幂等。
- stdio 请求的登记、写出和取消顺序由单一 dispatch gate 串行化。
- 初始化失败、超时、主动关闭和进程意外退出都收敛 pending future，且不遗留子进程树。
- 对外异常不保留 SDK、JSON、I/O 或系统异常 cause，避免路径、凭据和协议载荷泄漏。

## 测试与源码入口

### 源码入口

- [`McpClientFactory.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java)
- [`LangChainMcpClient.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java)
- [`CustomStdioMcpTransport.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransport.java)
- [`McpDeadline.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java)
- [`McpCancellationToken.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java)

### 关键测试守卫

- [`RemoteMcpClientTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpClientTest.java)：真实 HTTP transport 的发现、调用、Header 和失败收敛。
- [`CustomStdioMcpTransportTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransportTest.java)：argv/cwd/env、请求级取消、进程退出与进程树回收。
- [`McpClientFactoryTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactoryTest.java)、[`ClientHandoffTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoffTest.java)：初始化总预算、失败清理与超时交接竞态。
- [`McpRequestBindingsTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpRequestBindingsTest.java)、[`McpCancellationTokenTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationTokenTest.java)：request id 绑定与取消并发语义。
- [`McpResultExtractorTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractorTest.java)：MCP content 到严格 `ResultContent` 的映射。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Harness Common](harness-common.md)。
