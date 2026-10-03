# Harness MCP

`harness-mcp` 将 LangChain4j Streamable HTTP client 适配为模块自有的配置、工具定义和结果，供 Platform 的发现与执行共用。每次操作显式接收总 deadline 与取消令牌，client 所有者负责关闭连接与 worker。

调用在 Backend 内执行，初始化和后续操作从同一份剩余预算扣除。产品 Server/Tool 的配置、名称寻址与持久目录见 [`Platform`](platform.md#mcp-server-与运行时工具目录)；本模块提供可独立使用的 client 生命周期与 wire 结果映射。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.mcp` | MCP client/config/result 模型、LangChain4j Streamable HTTP 适配、deadline 与取消协调 | 管理 client 内的连接与 worker；产品事实持久化、工具身份与 per-call 生命周期归 Platform |

生产依赖只有 `langchain4j-mcp`、[`harness-common`](harness-common.md)、Jackson 与 JDK（见 [`pom.xml`](../../harness/mcp/pom.xml)）；不依赖 Spring、JDBC、[`harness-tool`](harness-tool.md)、[`harness-environment`](harness-environment.md)、Platform 或 Daemon。

## Client 与配置

[`McpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClient.java) 继承 `AutoCloseable`，只有三个方法：`listTools`、`callTool` 与幂等 `close`。公共签名不出现任何 SDK 类型——返回值是模块自有的 [`McpToolDefinition`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolDefinition.java)（`inputSchemaJson` 必须是 JSON object，不接受把非法 schema 降级成字符串）与 [`McpToolCallResult`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)，后者的内容列表映射为 [`harness-common`](harness-common.md) 的 `ResultContent`。`listTools` 与 `callTool` 都强制调用方显式传入 deadline 与取消令牌，不存在无预算的隐式默认。

[`McpClientFactory`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java) 是唯一构造入口，只有 `createRemote` 一条路径（`Duration` 重载只是 `McpDeadline.of` 的便捷包装）：构造 Streamable HTTP transport，固定 client protocol version `2025-11-25`，并关闭 SDK 的工具列表缓存（`cacheToolList(false)`）、工具列表变更订阅与自动健康检查——每次构造出的 client 都是独立实体，不隐式复用任何远端状态。Header 的环境变量替换由调用方预先完成，本模块拿到的是已解析值；Request 侧的 `timeout` 与初始化共用同一份剩余预算。

初始化在独立的 daemon 线程完成，调用方只等待同一份剩余预算。超时、构建失败或交接竞态都不会留下半开的连接：[`ClientHandoff`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java) 用监视器保护的状态保证「交付」与「放弃」互斥，已构建的 client 要么恰好交付给调用方一次，要么当场回收；底层 transport 的关闭是幂等兜底动作，可能被重复调用，绝不依赖其次数。

[`RemoteMcpConfig`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java) 的 toString 输出 URL 长度与 header 数量；完整 URL 仅通过显式 url() 读取，因为它可能内嵌凭证。

## 总预算与调用级取消

[`McpDeadline`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java) 只保存一个绝对截止时间，`of(Duration)` 用于新建预算、`ofDeadline(Instant)` 用于承接已定时刻，其余方法（`remaining()` / `requireRemaining()`、`isExpired()` / `checkNotExpired()`）只是同一时刻的只读判定，不引入第二个时间源。生产调用创建一次 deadline，并把同一实例依次传给 client factory 与 `listTools` / `callTool`：初始化已经花掉的时间从后续阶段扣除，任何阶段都不会重置预算，也没有 unlimited 形式。

[`McpCancellationToken`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java) 是单次操作的取消句柄：`cancel()` 幂等且可从任意线程调用，重复取消无副作用；`none()` 返回共享的不可取消令牌，它既不改变状态也不保留 listener，因此反复使用不会累积内存；普通令牌在取消后注册的 listener 会立即回放，取消先于请求建立时不会丢失取消意图。

取消只终止本次本地等待：令牌取消时取消该调用的 worker future 并中断其线程，因此不注册额外的协议取消 aborter，也绝不关闭共享 client，同一 client 上的其它并发调用不受影响。

[`LangChainMcpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java) 把阻塞 SDK 调用限制在每个 client 实例的 64 个真实 worker 上，交接队列为不保存等待任务的 `SynchronousQueue`，与外层工具 admission 的默认并发相同，不另设配置。池满或 client 已关闭时提交抛 `McpException`，分别标明容量不足或实例关闭，不会挂起调用方；该异常不携带重试分类，是否重试由调用方决定。`future.cancel` 只结束本地等待，不响应 interrupt 的 worker 继续占用名额，因此后续调用不会靠取消提前腾出容量。

[`LangChainMcpClient`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java) 还区分了两类失败：工具自身以错误结束（SDK 抛 `ToolExecutionException`）映射为 `McpToolCallResult.errorText`，因为它仍是可读的工具输出；只有连接或协议失败才抛 [`McpException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpException.java)、[`McpTimeoutException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpTimeoutException.java) 或 [`McpCancelledException`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancelledException.java)。上层据此决定是保留 client 还是重建，把工具报错误判成链路故障会连带销毁仍健康的连接。

## 结果映射

[`McpResultExtractor`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java) 由 factory 安装到 SDK builder，把 MCP content 转成 [`harness-common`](harness-common.md) 的内容单元。文本计入单次响应的累计预算；`image` 在解码前按 encoded 长度检查 decoded 上界，非法 Base64 退化为保留结构的有界 JSON，缺省 mediaType 用 `application/octet-stream`；resource 或任意扩展类型先用有界 JSON writer 写出，超过 `JsonResultContent` 的 1 MiB 上限时直接失败，不退化为同尺寸无界文本。单次响应的聚合内容预算为 64 MiB，不是调用配置。空内容补一个空 `TextResultContent`。工具入参侧则相反地严格：`callTool` 要求 arguments 是严格 JSON object，若入参存在重复键或尾随内容，本地直接以固定文本拒绝，绝不让服务端按「哪个键胜出」的偶然实现执行，也不回显 payload 片段。

## 源码与测试

- 生命周期：[`McpClientFactory.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactory.java)、[`LangChainMcpClient.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClient.java)、[`ClientHandoff.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoff.java)。
- 预算与取消：[`McpDeadline.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpDeadline.java)、[`McpCancellationToken.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationToken.java)。
- 结果与配置：[`McpResultExtractor.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractor.java)、[`McpToolCallResult.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/McpToolCallResult.java)、[`RemoteMcpConfig.java`](../../harness/mcp/src/main/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpConfig.java)。
- [`ClientHandoffTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/ClientHandoffTest.java) 覆盖「交付」与「放弃」的全部交错与恰好一次关闭；[`McpDeadlineTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpDeadlineTest.java)、[`McpCancellationTokenTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpCancellationTokenTest.java) 覆盖单一预算与取消幂等；[`RemoteMcpClientTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/RemoteMcpClientTest.java) 用真实 HTTP transport 跑发现与调用；[`McpResultExtractorTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpResultExtractorTest.java) 覆盖内容降级与空内容；[`LangChainMcpClientTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/LangChainMcpClientTest.java) 覆盖并发上界、容量拒绝、失败分类与入参严格性，[`McpClientFactoryTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpClientFactoryTest.java) 覆盖初始化失败分类与初始化线程收敛，[`McpValueModelTest.java`](../../harness/mcp/src/test/java/fun/fengwk/kkstudio/harness/mcp/McpValueModelTest.java) 锁定配置与结果值模型。

---

上级：[系统设计](../system-design.md)。相关文档：[Platform](platform.md)、[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)。
