# Harness MCP

MCP 工具要接进 Harness，关键风险只有一条：一次远端调用把 Backend 线程挂死，或者取消与超时互相覆盖，让「已发出的请求」和「已放弃的等待」产生两种事实。本模块把底层 LangChain4j MCP SDK 收敛在一处，对调用方只暴露稳定的配置、工具定义、调用结果、总预算与取消契约，因此产品侧的发现与执行可以共用同一份超时、取消与清理语义。

本模块交付的是一份无状态的 MCP client 能力：把配置收敛为不可变值对象、列举远端工具、执行单次调用并映射结果，并用一份覆盖初始化与执行的总预算与调用级取消约束每次调用。传输只有 Streamable HTTP 一种，调用发生在 Backend 进程内；产品侧的 MCP Server/Tool 持久化、名称寻址、工具身份与 per-call 生命周期由 [`platform`](platform.md) 拥有。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.mcp` | MCP client/config/result 模型、LangChain4j Streamable HTTP 适配、deadline 与取消协调 | 无状态调用能力；产品事实持久化、工具身份与 per-call 生命周期归 Platform |

生产依赖只有 `langchain4j-mcp`、[`harness-common`](harness-common.md)、Jackson 与 JDK（见 [`pom.xml`](../../harness/mcp/pom.xml)）；不依赖 Spring、JDBC、[`harness-tool`](harness-tool.md)、[`harness-environment`](harness-environment.md)、Platform 或 Daemon。

## Client 与配置

[`McpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClient.java) 只有三个方法：`listTools`、`callTool` 与幂等 `close`。公共签名不出现任何 SDK 类型——返回值是模块自有的 [`McpToolDefinition`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolDefinition.java)（`inputSchemaJson` 必须是 JSON object，不接受把非法 schema 降级成字符串）与 [`McpToolCallResult`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)，后者的内容列表映射为 [`harness-common`](harness-common.md) 的 `ResultContent`。`listTools` 与 `callTool` 都强制调用方显式传入 deadline 与取消令牌，不存在无预算的隐式默认。

[`McpClientFactory`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java) 是唯一构造入口，只有 `createRemote` 一条路径（`Duration` 重载只是 `McpDeadline.of` 的便捷包装）：构造 Streamable HTTP transport，固定 client protocol version `2025-11-25`，并关闭 SDK 的目录缓存、工具列表订阅与自动健康检查——每次构造出的 client 都是独立实体，不隐式复用任何远端状态。Header 的环境变量替换由调用方预先完成，本模块拿到的是已解析值；Request 侧的 `timeout` 与初始化共用同一份剩余预算。

初始化在独立的 daemon 线程完成，调用方只等待同一份剩余预算。超时、构建失败或交接竞态都不会留下半开的连接：[`ClientHandoff`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java) 用单一监视器状态保证「交付」与「放弃」互斥，因此底层 transport 的关闭恰好发生一次，实例要么恰好交付给调用方一次，要么当场回收。

配置对象本身也不承担泄漏面：[`RemoteMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java) 的 `toString` 只输出 URL 长度与 header 数量——URL 可能内嵌凭证，只有显式调用 `url()` 才读取完整值。

## 总预算与调用级取消

[`McpDeadline`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java) 只保存一个绝对截止时间，`of(Duration)` 用于新建预算、`ofDeadline(Instant)` 用于承接已定时刻。生产调用创建一次 deadline，并把同一实例依次传给 client factory 与 `listTools` / `callTool`：初始化已经花掉的时间从后续阶段扣除，任何阶段都不会重置预算，也没有 unlimited 形式。

[`McpCancellationToken`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java) 是单次操作的取消句柄：`cancel()` 幂等且可从任意线程调用，重复取消无副作用；`none()` 返回共享的不可取消令牌，它既不改变状态也不保留 listener，因此反复使用不会累积内存；普通令牌在取消后注册的 listener 会立即回放，取消先于请求建立时不会丢失取消意图。

取消通路由 transport 自身提供：Streamable HTTP 通过关闭 per-request SSE 流结束本次请求的等待，因此不注册额外的协议取消 aborter，也绝不关闭共享 client，同一 client 上的其它并发调用不受影响。

[`LangChainMcpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java) 还区分了两类失败：工具自身以错误结束（SDK 抛 `ToolExecutionException`）映射为 `McpToolCallResult.errorText`，因为它仍是可读的工具输出；只有连接或协议失败才抛 [`McpException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpException.java)、[`McpTimeoutException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpTimeoutException.java) 或 [`McpCancelledException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancelledException.java)。上层据此决定是保留 client 还是重建，把工具报错误判成链路故障会连带销毁仍健康的连接。

## 结果映射

[`McpResultExtractor`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java) 把 MCP content 转成 [`harness-common`](harness-common.md) 的内容单元，且不因畸形内容二次失败：文本直通；`image` 的 base64 解码失败时退化为保留结构的 JSON 内容，缺省 mediaType 用 `application/octet-stream`；resource 或任意扩展类型序列化为 `JsonResultContent`，超过其 1 MiB 上限时退化为文本；空内容补一个空 `TextResultContent`。工具入参侧则相反地严格：`callTool` 要求 arguments 是严格 JSON object，若入参存在重复键或尾随内容，本地直接以固定文本拒绝，绝不让服务端按「哪个键胜出」的偶然实现执行，也不回显 payload 片段。

## 源码与测试

- 生命周期：[`McpClientFactory.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java)、[`LangChainMcpClient.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java)、[`ClientHandoff.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java)。
- 预算与取消：[`McpDeadline.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java)、[`McpCancellationToken.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java)。
- 结果与配置：[`McpResultExtractor.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java)、[`McpToolCallResult.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)、[`RemoteMcpConfig.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java)。
- [`ClientHandoffTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoffTest.java) 覆盖「交付」与「放弃」的全部交错与恰好一次关闭；[`McpDeadlineTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpDeadlineTest.java)、[`McpCancellationTokenTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationTokenTest.java) 覆盖单一预算与取消幂等；[`RemoteMcpClientTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpClientTest.java) 用真实 HTTP transport 跑发现与调用；[`McpResultExtractorTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractorTest.java) 覆盖内容降级与空内容。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)。
