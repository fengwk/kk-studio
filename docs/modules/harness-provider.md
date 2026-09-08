# Harness Provider

## 定位

`harness-provider` 提供纯 JDK 21 `HttpClient` + SSE 增量传输，以及原生模型协议实现。模块无 Spring
依赖，生产直接依赖仅限 [`harness-runtime`](harness-runtime.md) 与 Jackson；Platform 负责注入凭据、长生命周期
`HttpClient`、工作线程和 Watchdog 调度器。

## 职责

### 核心职责

- 基于 JDK 21 `HttpClient` 提供异步 SSE 传输（`JdkHttpSseTransport`），在受管工作线程与受管调度器 Watchdog 下受控执行，返回可安全取消的 `ProviderStream`。
- 实现双维度有界防护（`HttpSseLimits`）：支持单行字节限制、单事件字节限制、成功流累计字节上限与错误响应体抓取上限，超限立即中止请求并关闭底层 TCP 连接。
- 实现逐字节增量 SSE 解析器（`IncrementalSseParser`），原生支持 CRLF、LF 与孤立 CR 换行，支持跨 chunk 拼装 UTF-8 多字节字符，静默剔除前导 UTF-8 BOM，检测并严格拦截 NUL 字节与畸形 UTF-8 序列。
- 构建 Secret-Safe 脱敏异常体系（`TransportException` 与 `HttpOpenMetadata`）：严格采用最小白名单放行协议诊断标头（丢弃所有未知标头，杜绝回显凭据泄露），在 `getMessage()`、`toString()` 以及受控的 `SafeCauseException` 异常因果链中严格抹除敏感请求头、Token、URI 参数与未经授权的响应正文，杜绝任何凭据在日志或异常转储中外泄。
- 实现 Anthropic Messages 请求编码、SSE content block 状态机、错误与 usage 归一化、terminal replay
  以及 LENGTH 截断工具诊断；支持文本、thinking/signature、redacted thinking、图片、PDF 和客户端工具。
- Anthropic Prompt Cache 支持 SYSTEM、TOOLS、CONVERSATION 三类显式断点；SHORT 使用默认短 TTL，LONG
  映射 `ttl: "1h"`，canonical prefix hash 递归排除 `cache_control`。
- 真实对齐 LangChain4j 1.20.0（Git 提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）shared HTTP 与 JDK 范围内的 15 个源文件、共 115 个 active 测试方法：按 invocation 展开共 141 个测试项，49 applicable invocations passed / 92 explicit OOS（含同步非流式 HTTP、Multipart 构建器、Reactive Streams TCK 38 项、BlockHound 非阻塞检测、PUBLISHER 异步流分支以及语义不兼容的静默异常吞没用例），所有 OOS 项明确标记为 `NOT_EXECUTED_OUT_OF_SCOPE` 并详述 capability mismatch，以机器可读清单 `upstream-test-manifest.json` 固化对齐口径。
- 真实对齐 LangChain4j 1.20.0（Git 提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）`langchain4j-anthropic` 模块全部 40 个测试源文件和追踪继承/组合的核心基座方法（共 423 个方法），按参数维度展开共 577 个 invocation：实现 155 applicable passed / 422 explicit OOS / 0 pending / 121 credential-bound real interop not executed。所有 PORTED 目标测试均通过反射机制验证本地方法存在且标注 `@Test` 或 `@ParameterizedTest`，坚决拒绝虚假对等；全部 422 项 OUT_OF_SCOPE 明确标记为 `NOT_EXECUTED_OUT_OF_SCOPE` 并详述架构不匹配原因；121 项依赖真实凭据的测试独立维护 `realInteropStatus = NOT_EXECUTED_REQUIRES_CREDENTIAL`，以机器可读清单 `upstream-test-manifest.json` 固化全量可审计口径。

### 协作边界

- 生产直接依赖仅限 `harness-runtime` 与 Jackson，保持 Spring-free，禁止引入 Spring Context、JDBC、
  LangChain4j、Reactor 或外部 HTTP 客户端。
- `transport` 包不耦合厂商协议；各厂商协议只位于独立协议包。
- 仅通过 `HttpSseCallback` 派发 `onOpen`、`onEvent`、`onComplete` 与 `onFailure` 四大生命周期回调，保证 Terminal-Once 确定性，同一连接绝不同时或多次触发终态回调。

## 依赖边界

```text
       upstream caller / platform
                  │
                  ▼
       harness-provider
            │          │
            ▼          ▼
    harness-runtime   Jackson

依赖约束：生产直接依赖仅允许引入 harness-runtime 与 Jackson；禁止引入 Spring、LangChain4j、
Reactor、JDBC 与外部 HTTP 客户端。
```

生产依赖见 [`pom.xml`](../../harness/provider/pom.xml)。[`ProviderModuleArchitectureTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java) 自动化检查主源码 import 与 POM 依赖。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.provider.transport` | JDK 21 HttpClient 异步流式传输、增量 SSE 字节解析器、有界流限制、响应元数据与 Secret-Safe 传输异常 | 仅依赖 `harness-runtime` 基础模型与 JDK 标准库；禁止暴露任何敏感凭据或包含未清洗的异常上下文 |
| `fun.fengwk.kkstudio.harness.provider.anthropic` | Anthropic Messages wire 编码、流式聚合、cache、usage、错误、replay 与 Runtime adapter | 只经 transport 发起 I/O；opaque replay 不进入公共 DTO、日志或异常 |

## 核心模型 / API

### ServerSentEvent

[`ServerSentEvent`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/ServerSentEvent.java) 表示解析完成的标准 SSE 事件单元：包含可选的 `event` 类型字段与非空的 `data` 正文字符串。其 `toString()` 方法仅输出 `eventLength` 与 `dataLength` 长度，避免在日常调试日志中打印完整事件名称或模型输出流。

### HttpSseLimits

[`HttpSseLimits`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 统一定义流式传输与错误响应的字节级有界限制：
- `maxLineBytes`：单行最大字节数（默认 64 KiB）；
- `maxEventBytes`：单个事件最大累积字节数（默认 1 MiB）；
- `maxSuccessBodyBytes`：成功流累计接收的最大字节数（默认 128 MiB）；
- `maxErrorBodyBytes`：非 2xx 错误响应抓取的最长诊断字节数（默认 64 KiB）。

### HttpOpenMetadata

[`HttpOpenMetadata`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpOpenMetadata.java) 携带 HTTP 连接建立成功的响应元数据，包含响应状态码与经过统一脱敏清洗的只读标头映射（严格采用最小白名单放行协议诊断所需标头，丢弃未知标头，自动脱敏含有 `token`、`key`、`auth`、`cookie`、`secret`、`credential` 等关键词的敏感标头，并过滤非法换行）。

### TransportErrorKind 与 TransportException

[`TransportErrorKind`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportErrorKind.java) 与 [`TransportException`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java) 构成安全的异常通信信道。异常原因链被限制为纯类名与安全消息，错误正文与敏感 URI 绝不在 `getMessage()` 或 `toString()` 中展开；上层可通过受保护的 `errorBodyBytes()` 防御性副本读取经过有界截断的诊断正文，并通过 `isErrorBodyTruncated()` 明确获知是否发生截断。

### IncrementalSseParser

[`IncrementalSseParser`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParser.java) 是逐字节增量 SSE 解析引擎：
- 采用内部行缓冲区与 UTF-8 渐进解码器（`CharsetDecoder`）；
- 自动剔除首字节 UTF-8 BOM（`0xEF, 0xBB, 0xBF`），且 BOM 字节计入成功流 wire 字节数统计；
- 严格拦截 NUL 字节（`0x00`）注入与非法多字节序列，触发 `INVALID_RESPONSE` 异常；
- 遵循 SSE 规范：仅在 `data:` 后紧邻一个空格时剔除该空格，多余前导空格与尾随空白完整保留；多行 `data:` 字段以 `\n` 进行合并；
- 每个空行事件边界（无论是否有 data）必须重置 `currentEvent` 与 `currentEventBytes`；
- 流结束时调用 `flush()` 自动交付未以空行结尾的尾随事件。

### JdkHttpSseTransport

[`JdkHttpSseTransport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransport.java) 是传输层的主入口：
- 构造时强制要求 `followRedirects == Redirect.NEVER`，并注入独立的受管 `workerExecutor` 与 `scheduler`；
- 接受标准的 `HttpRequest`、`ModelCallTimeoutPolicy`、`HttpSseLimits` 与 `HttpSseCallback`；
- 启动门（Start Gate）两阶段安全准入：检测并拒绝 direct / caller-runs 执行器的 inline execution，防止流死锁；任一阶段执行器拒绝时同步抛出 `EXECUTOR_REJECTED` 异常，且保证工作任务尚未接触 `HttpClient`；
- 竞态安全的 Future 挂接机制：任何 future 一经挂接若已处于终态必须立即 cancel；
- 在受管 worker 线程中发起阻塞式流传输，启动自适应单调时钟 Watchdog（基于 `System.nanoTime()` 与饱和算术），实时巡检并在超时达到时派发 `TransportErrorKind.TIMEOUT` 错误；
- 返回标准 `ProviderStream` 句柄，调用方可在流建立前、读取中或完成后安全幂等调用 `cancel()`，立即释放调用方并关闭网络连接；
- 保证严格的单一状态仲裁与权威 RUNNING 回调派发，终态后任何非终态回调为零，所有回调串行派发且支持回调内部重入取消，并在外部回调抛出未受检异常时安全捕获并转为终态错误且停止后续读取。

### Anthropic Messages

[`AnthropicProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicProviderAdapter.java)
基于 `ProviderDescriptor.endpoint` 解析 `/messages`，移除 base URL 的 user-info、query 与 fragment。
请求同时发送标准 `x-api-key` 与 `Authorization: Bearer`，不发送 `anthropic-beta`。

`AnthropicRequestEncoder` 对请求体实施 32 MiB 上限，并把 Runtime semantic history 编码为 Anthropic content
blocks。`AnthropicStreamAccumulator` 按原生 block index 独立维护交错工具调用，usage 按累计快照更新；只有
`message_stop` 证明协议成功。COMPLETE 可保存已验证的 opaque replay，FILTERED 撤回未完成协议内容，LENGTH
把未闭合工具参数固化为 diagnostic 而不伪造 `{}`。

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)。
