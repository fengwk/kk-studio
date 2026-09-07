# Harness Provider

## 定位

`harness-provider` 提供纯 JDK 21 `HttpClient` + SSE 增量传输与受管客户端基础设施，用于大模型流式调用。作为无 Spring 依赖的专用提供方传输层，生产依赖严格受限于 [`harness-runtime`](harness-runtime.md)，直接承载虚拟线程异步 I/O、单调时钟 Watchdog、增量字节流解析与全局敏感凭据脱敏安全屏障。

## 职责

### 核心职责

- 基于 JDK 21 `HttpClient` 提供轻量级异步 SSE 传输（`JdkHttpSseTransport`），在虚拟线程工作器与守护线程 Watchdog 下受控执行，返回可安全取消的 `ProviderStream`。
- 实现双维度有界防护（`HttpSseLimits`）：支持单行字节限制、单事件字节限制、成功流累计字节上限与错误响应体抓取上限，超限立即中止请求并关闭底层 TCP 连接。
- 实现零额外字符分配的增量 SSE 字节解析器（`IncrementalSseParser`），原生支持 CRLF、LF 与孤立 CR 换行，支持跨 chunk 拼装 UTF-8 多字节字符，静默剔除前导 UTF-8 BOM，检测并严格拦截 NUL 字节与畸形 UTF-8 序列。
- 构建 Secret-Safe 脱敏异常体系（`TransportException` 与 `HttpOpenMetadata`）：在 `getMessage()`、`toString()` 以及受控的 `SafeCauseException` 异常因果链中严格抹除敏感请求头、Token、URI 参数与未经授权的响应正文，杜绝任何凭据在日志或异常转储中外泄。
- 严格对齐 LangChain4j 1.20.0（Git 提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）的 SSE 增量解析、闲置超时、取消流断开与错误体读取契约，以机器可读清单 `upstream-test-manifest.json` 固化对齐矩阵。

### 协作边界

- 生产依赖仅限 `harness-runtime`，保持 Spring-free，禁止引入 Spring Context、JDBC 或 WebFlux。
- 仅提供 HTTP/SSE 传输与基础凭据脱敏，不耦合任何特定模型供应商（如 OpenAI、Anthropic、DeepSeek）的专有报文协议。
- 仅通过 `HttpSseCallback` 派发 `onOpen`、`onEvent`、`onComplete` 与 `onFailure` 四大生命周期回调，保证 Terminal-Once 确定性，同一连接绝不同时或多次触发终态回调。

## 依赖边界

```text
       upstream caller / platform
                  │
                  ▼
       harness-provider
                  │
                  ▼
       harness-runtime

依赖约束：生产依赖仅允许引入 harness-runtime；禁止引入 Spring 框架、JDBC 与外部 HTTP 客户端。
```

生产依赖见 [`pom.xml`](../../harness/provider/pom.xml)。[`ProviderModuleArchitectureTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java) 自动化检查主源码 import 与 POM 依赖。

## 包架构

| 包名 | 职责 | 明确边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.provider.transport` | JDK 21 HttpClient 异步流式传输、增量 SSE 字节解析器、有界流限制、响应元数据与 Secret-Safe 传输异常 | 仅依赖 `harness-runtime` 基础模型与 JDK 标准库；禁止暴露任何敏感凭据或包含未清洗的异常上下文 |

## 核心模型 / API

### ServerSentEvent

[`ServerSentEvent`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/ServerSentEvent.java) 表示解析完成的标准 SSE 事件单元：包含可选的 `event` 类型字段与非空的 `data` 正文字符串。其 `toString()` 方法仅输出事件名与 `dataLength` 长度，避免在日常调试日志中打印完整模型输出流。

### HttpSseLimits

[`HttpSseLimits`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 统一定义流式传输与错误响应的字节级有界限制：
- `maxLineBytes`：单行最大字节数（默认 64 KiB）；
- `maxEventBytes`：单个事件最大累积字节数（默认 1 MiB）；
- `maxSuccessBodyBytes`：成功流累计接收的最大字节数（默认 128 MiB）；
- `maxErrorBodyBytes`：非 2xx 错误响应抓取的最长诊断字节数（默认 64 KiB）。

### HttpOpenMetadata

[`HttpOpenMetadata`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpOpenMetadata.java) 携带 HTTP 连接建立成功的响应元数据，包含响应状态码与经过脱敏清洗的只读标头映射（自动脱敏 `authorization`、`api-key`、`cookie`、`set-cookie` 等敏感 Header）。

### TransportErrorKind 与 TransportException

[`TransportErrorKind`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportErrorKind.java) 与 [`TransportException`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java) 构成安全的异常通信信道。异常原因链被限制为纯类名与安全消息，错误正文与敏感 URI 绝不在 `getMessage()` 或 `toString()` 中展开；上层仅可通过受保护的 `errorBodyBytes()` 防御性副本读取经过有界截断的诊断正文。

### IncrementalSseParser

[`IncrementalSseParser`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParser.java) 是字节增量 SSE 解析引擎：
- 采用内部字节缓冲区与 UTF-8 渐进解码器（`CharsetDecoder`）；
- 自动剔除首字节 UTF-8 BOM（`0xEF, 0xBB, 0xBF`）；
- 严格拦截 NUL 字节（`0x00`）注入与非法多字节序列，触发 `MALFORMED_STREAM`；
- 遵循 SSE 规范：仅在 `data:` 后紧邻一个空格时剔除该空格，多余前导空格与尾随空白完整保留；多行 `data:` 字段以 `\n` 进行合并；
- 流结束时调用 `flush()` 自动交付未以空行结尾的尾随事件。

### JdkHttpSseTransport

[`JdkHttpSseTransport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransport.java) 是传输层的主入口：
- 接受标准的 `HttpRequest`、`ModelCallTimeoutPolicy`、`HttpSseLimits` 与 `HttpSseCallback`；
- 在独立虚拟线程（`Thread.ofVirtual()`）中发起异步流式传输；
- 启动单调时钟 Watchdog（基于 `System.nanoTime()`），实时巡检并在超时达到时以 `HttpTimeoutException` 快速熔断；
- 返回标准 `ProviderStream` 句柄，调用方可在流建立前、读取中或完成后安全幂等调用 `cancel()`，立即释放调用方并关闭网络连接；
- 保证严格的 Terminal-Once 回调语义，并在用户回调抛出未受检异常时安全捕获并转为终态错误。

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)。

