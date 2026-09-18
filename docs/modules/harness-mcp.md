# Harness MCP

MCP 工具要接进 Harness，有两个地方会用到：Platform 在 Backend 进程内按调用直连 Remote Streamable HTTP 服务，Environment Daemon 在目标环境里长期持有一个 Local stdio 子进程。两者的失败面完全不同——前者怕把一次调用挂死，后者怕泄漏子进程、把取消丢掉、或者在重启子进程时污染上一代的在途请求。本模块把底层 LangChain4j MCP SDK 收敛在一处，对调用方只暴露稳定的配置、工具定义、调用结果、总预算与取消契约，因此两个场景可以共用同一份超时、取消与清理语义。

本模块不持久化 MCP Server/Tool，不解析产品层 JSON 配置，不决定 Environment 路由，也不缓存跨调用 client：Remote 侧的 per-call 生命周期与 Local 侧的代际共享分别由 [`platform`](platform.md) 与 [`harness-daemon`](harness-daemon.md) 决定。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.mcp` | MCP client/config/result 模型、LangChain4j 适配、deadline 与取消协调、自定义 stdio transport | 不持久化产品事实、不解析 Environment 路由、不缓存跨调用 client 与工具目录 |

生产依赖只有 `langchain4j-mcp`、[`harness-common`](harness-common.md)、Jackson 与 JDK（见 [`pom.xml`](../../harness/mcp/pom.xml)）；不依赖 Spring、JDBC、[`harness-tool`](harness-tool.md)、[`harness-environment`](harness-environment.md)、Platform 或 Daemon。

## Client 与配置

[`McpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClient.java) 只有三个方法：`listTools`、`callTool` 与幂等 `close`。公共签名不出现任何 SDK 类型——返回值是模块自有的 [`McpToolDefinition`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolDefinition.java)（`inputSchemaJson` 必须是 JSON object，不接受把非法 schema 降级成字符串）与 [`McpToolCallResult`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)，后者的内容列表映射为 [`harness-common`](harness-common.md) 的 `ResultContent`。

[`McpClientFactory`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java) 是唯一构造入口，固定 client protocol version `2025-11-25`，并关闭 SDK 的目录缓存、工具列表订阅与自动健康检查——每次构造出的 client 都是独立实体，不隐式复用任何远端状态：

- `createRemote(RemoteMcpConfig, deadline)` 构造 Streamable HTTP transport。Header 的环境变量替换必须由调用方预先完成，本模块拿到的是已解析值。
- `createStdio(StdioMcpConfig, deadline)` 要求已解析的非空 argv、本机绝对 `cwd` 与环境变量覆盖。

初始化在独立的 daemon 线程完成，调用方只等待同一份剩余预算。超时、构建失败或交接竞态都不会留下半开的连接或无人管理的子进程：实例要么恰好交付给调用方一次，要么当场回收。[`ClientHandoff`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java) 用单一监视器状态保证「交付」与「放弃」互斥，因此 `close` 恰好发生一次。

配置对象本身也不承担泄漏面：[`RemoteMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java) 的 `toString` 只输出 URL 长度与 header 数量，[`StdioMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/StdioMcpConfig.java) 只输出 argv 数量与环境变量数量——URL 可能内嵌凭证，command 与 env 也不该进入日志。

## 总预算与调用级取消

[`McpDeadline`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java) 只保存一个绝对截止时间，`of(Duration)` 用于新建预算、`ofDeadline(Instant)` 用于承接已定时刻。生产调用创建一次 deadline，并把同一实例依次传给 client factory 与 `listTools` / `callTool`：初始化已经花掉的时间从后续阶段扣除，任何阶段都不会重置预算，也没有 unlimited 形式。

[`McpCancellationToken`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java) 是单次操作的取消句柄：`cancel()` 幂等且可从任意线程调用，重复取消无副作用；`none()` 返回共享的不可取消令牌，它既不改变状态也不保留 listener，因此反复使用不会累积内存；普通令牌在取消后注册的 listener 会立即回放，取消先于请求建立时不会丢失取消意图。

取消与派发的汇合点是 [`McpRequestBindings`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpRequestBindings.java)，它同时实现派发闸门接口 [`McpDispatchGate`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDispatchGate.java) 与 SDK 的 client listener。传输在写出请求前调用 `dispatchOrAbort`，该方法把「是否已取消」的判定、在途请求登记与底层写出包在同一个临界区里，因此两种交错都是确定的：取消先取到锁，原请求完全不写出、也不再补发通知；派发先取到锁，原请求完整写入管道后才轮到取消方发送 `notifications/cancelled`，子进程必然先看到原请求、后看到取消，不会出现「取消通知抢在原请求之前、服务端因找不到 request id 而忽略」的静默失效。

两种传输的取消通路不同，这是有意为之：stdio 的 [`CustomStdioMcpTransport`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransport.java) 上报 `requiresCancellationNotification() = true`，SDK 会因此向服务端发送协议取消；Remote HTTP 没有这条通道，取消通过关闭 per-request SSE 流实现，因此不注册 aborter。无论哪条通路，取消都只结束本次请求的等待，绝不关闭共享 client，同一 client 上的其它并发调用不受影响。

[`LangChainMcpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java) 还区分了两类失败：工具自身以错误结束（SDK 抛 `ToolExecutionException`）映射为 `McpToolCallResult.errorText`，因为它仍是可读的工具输出；只有连接、进程或协议失败才抛 [`McpException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpException.java)、[`McpTimeoutException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpTimeoutException.java) 或 [`McpCancelledException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancelledException.java)。上层据此决定是保留 client 还是重建，把工具报错误判成链路故障会连带销毁仍健康的连接。

## Stdio 子进程生命周期

`CustomStdioMcpTransport` 用 `ProcessBuilder(argv)` 直接拉起进程：argv 逐项传入、不经 shell 词法拼接，因此参数中的空格与元字符不会被重新解释。它还显式设置 `cwd`、覆盖子进程环境，并把 stderr 重定向丢弃——既避免输出过大阻塞，也不让子进程输出进入日志。它自行处理 stdin/stdout 上的 JSON-RPC 帧，并登记每个在途 request id，从而能按 request id 精确中止单次请求：结束本地等待、发送 `notifications/cancelled`，同一连接上的其它请求继续运行。

`start` 与 `close` 由同一把生命周期锁串行化，`close` 是 sticky 终态：

- 关闭后 `start` 一律 fail closed，绝不再拉起子进程，否则关闭方将无法回收它；
- 若 spawn 已经进入临界区，随后的关闭必然持有该进程引用，并强制结束它连同全部后代；
- 每次 `start` 会先终止上一代再建立新一代。每一代拥有独立的在途请求表，旧代退出只失败属于旧代的请求，不会污染新代，也不会把「被替换后的正常退出」误报为新代故障。

请求级的 `abort` 与 `close` 是两件事：前者只影响那一个 request；后者回收整棵进程树。构建路径上的任何失败（包括 spawn 成功但 IO handler 构造失败）都就地关闭 transport，最终由 `McpClientFactory` 的 `finally` 兜底覆盖全部交错。

## 结果映射

[`McpResultExtractor`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java) 把 MCP content 转成 [`harness-common`](harness-common.md) 的内容单元，且不因畸形内容二次失败：文本直通；`image` 的 base64 解码失败时退化为保留结构的 JSON 内容，缺省 mediaType 用 `application/octet-stream`；resource 或任意扩展类型序列化为 `JsonResultContent`，超过其 1 MiB 上限时退化为文本；空内容补一个空 `TextResultContent`。工具入参侧则相反地严格：`callTool` 要求 arguments 是严格 JSON object，若入参存在重复键或尾随内容，本地直接以固定文本拒绝，绝不让服务端按「哪个键胜出」的偶然实现执行，也不回显 payload 片段。

## 源码与测试

- 生命周期：[`McpClientFactory.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java)、[`LangChainMcpClient.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java)、[`CustomStdioMcpTransport.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransport.java)、[`ClientHandoff.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java)
- 预算与取消：[`McpDeadline.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java)、[`McpCancellationToken.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java)、[`McpRequestBindings.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpRequestBindings.java)
- 结果与配置：[`McpResultExtractor.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java)、[`McpToolCallResult.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)、[`RemoteMcpConfig.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java)、[`StdioMcpConfig.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/StdioMcpConfig.java)
- 取消与派发的双向竞态由 [`McpRequestBindingsTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpRequestBindingsTest.java) 穷举；[`CustomStdioMcpTransportTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/CustomStdioMcpTransportTest.java) 覆盖 argv/cwd/env、请求级取消、进程树回收与 sticky close；[`McpClientFactoryTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactoryTest.java) 验证单一总预算覆盖初始化、失败时子进程被回收、异常文本不泄漏；[`RemoteMcpClientTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpClientTest.java) 用真实 HTTP transport 跑发现与调用。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Daemon](harness-daemon.md)、[Platform](platform.md)、[Harness Common](harness-common.md)。
