# Harness Provider

## 定位

`harness-provider` 提供纯 JDK 21 `HttpClient` + SSE 增量传输，以及原生模型协议实现。模块无 Spring
依赖，生产直接依赖仅限 [`harness-runtime`](harness-runtime.md) 与 Jackson；Platform 负责注入凭据、长生命周期
`HttpClient`、工作线程和 Watchdog 调度器。

Platform 默认装配的模型专用 `HttpClient` 固定使用 HTTP/1.1（HTTP 与 HTTPS 均适用），不发起 h2c 升级或协商 HTTP/2；SSE 流式响应与连接复用保持可用，重定向策略为 `Redirect.NEVER`。

## 职责

### 核心职责

- 基于 JDK 21 `HttpClient` 提供异步 SSE 传输（`JdkHttpSseTransport`），在受管工作线程与受管调度器 Watchdog 下受控执行，返回可安全取消的 `ProviderStream`。
- 实现双维度有界防护（`HttpSseLimits`）：支持单行字节限制、单事件字节限制、成功流累计字节上限与错误响应体抓取上限，超限立即中止请求并关闭底层 TCP 连接。
- 实现逐字节增量 SSE 解析器（`IncrementalSseParser`），原生支持 CRLF、LF 与孤立 CR 换行，支持跨 chunk 拼装 UTF-8 多字节字符，静默剔除前导 UTF-8 BOM，检测并严格拦截 NUL 字节与畸形 UTF-8 序列。
- 传输异常（`TransportException`）不展开请求凭据、URI 与底层异常上下文；响应元数据（`HttpOpenMetadata`）仅放行安全诊断标头。协议层独立保留上游错误正文，不对白名单之外的错误字段做过滤，也不将真实错误替换为通用文案。
- 实现 Anthropic Messages 请求编码、SSE content block 状态机、错误与 usage 归一化、terminal replay
  以及 LENGTH 截断工具诊断；支持文本、thinking/signature、redacted thinking、图片、PDF 和客户端工具。
- Anthropic Prompt Cache 支持 SYSTEM、TOOLS、CONVERSATION 三类显式断点；SHORT 使用默认短 TTL，LONG
  映射 `ttl: "1h"`，canonical prefix hash 递归排除 `cache_control`。
- 真实对齐 LangChain4j 1.20.0（Git 提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）shared HTTP 与 JDK 范围内的 15 个源文件、共 115 个 active 测试方法：按 invocation 展开共 141 个测试项，49 applicable invocations passed / 92 explicit OOS（含同步非流式 HTTP、Multipart 构建器、Reactive Streams TCK 38 项、BlockHound 非阻塞检测、PUBLISHER 异步流分支以及语义不兼容的静默异常吞没用例），所有 OOS 项明确标记为 `NOT_EXECUTED_OUT_OF_SCOPE` 并详述 capability mismatch，以机器可读清单 `upstream-test-manifest.json` 固化对齐口径。
- 真实对齐 LangChain4j 1.20.0（Git 提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）`langchain4j-anthropic` 模块全部 40 个测试源文件和追踪继承/组合的核心基座方法（共 423 个方法），按参数维度展开共 577 个 invocation：实现 155 applicable passed / 422 explicit OOS / 0 pending / 121 credential-bound real interop not executed。所有 PORTED 目标测试均通过反射机制验证本地方法存在且标注 `@Test` 或 `@ParameterizedTest`，坚决拒绝虚假对等；全部 422 项 OUT_OF_SCOPE 明确标记为 `NOT_EXECUTED_OUT_OF_SCOPE` 并详述架构不匹配原因；121 项依赖真实凭据的测试独立维护 `realInteropStatus = NOT_EXECUTED_REQUIRES_CREDENTIAL`，以机器可读清单 `upstream-test-manifest.json` 固化全量可审计口径。
- OpenAI Responses 对齐 `langchain4j-open-ai` 中全部 15 个 Responses 测试源文件、165 个 invocation：53 PORTED / 112 OUT_OF_SCOPE / 0 pending，93 个真实凭据 invocation 独立标记为 `NOT_EXECUTED_REQUIRES_CREDENTIAL`。
- OpenAI Chat 对齐 `langchain4j-open-ai` 全部 70 个测试源文件、659 个 invocation：247 PORTED / 412 OUT_OF_SCOPE / 0 pending，390 个真实凭据 invocation 独立标记为 `NOT_EXECUTED_REQUIRES_CREDENTIAL`。
- Gemini 对齐 `langchain4j-google-ai-gemini` 全部 51 个测试源文件、676 个 invocation：190 PORTED / 486 OUT_OF_SCOPE / 0 pending，281 个真实凭据 invocation 独立标记为 `NOT_EXECUTED_REQUIRES_CREDENTIAL`。

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
| `fun.fengwk.kkstudio.harness.provider` | 跨协议共享的 HTTP 错误格式化与静默传输类型判断 | 不解析厂商错误分类，不修改原始响应正文 |
| `fun.fengwk.kkstudio.harness.provider.transport` | JDK 21 HttpClient 异步流式传输、增量 SSE 字节解析器、有界流限制、响应元数据与 Secret-Safe 传输异常 | 仅依赖 `harness-runtime` 基础模型与 JDK 标准库；禁止暴露任何敏感凭据或包含未清洗的异常上下文 |
| `fun.fengwk.kkstudio.harness.provider.anthropic` | Anthropic Messages wire 编码、流式聚合、cache、usage、错误、replay 与 Runtime adapter | 只经 transport 发起 I/O；opaque replay 不进入公共 DTO、日志或异常 |
| `fun.fengwk.kkstudio.harness.provider.gemini` | Google AI Gemini GenerateContent wire 编码、流式聚合、隐式 Prompt Cache 与 Runtime adapter | 只经 transport 发起 I/O；固定声明 AUTOMATIC 缓存能力 |
| `fun.fengwk.kkstudio.harness.provider.openai.chat` | OpenAI Chat Completions wire 编码、流式聚合、动态 Prompt Cache、tool call 与 Runtime adapter | 只经 transport 发起 I/O；按 configJson 动态解析 PromptCacheCapability |
| `fun.fengwk.kkstudio.harness.provider.openai.responses` | OpenAI Responses wire 编码、流式聚合、动态 Prompt Cache 与 Runtime adapter | 只经 transport 发起 I/O；按 configJson 动态解析 PromptCacheCapability |

## 核心模型 / API

### ServerSentEvent

[`ServerSentEvent`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/ServerSentEvent.java) 表示解析完成的标准 SSE 事件单元：包含可选的 `event` 类型字段与非空的 `data` 正文字符串。其 `toString()` 方法仅输出 `eventLength` 与 `dataLength` 长度，避免在日常调试日志中打印完整事件名称或模型输出流。

### 内联媒体与原生回放

`ProviderAdapter.mediaCapabilities()` 按用户内容与工具结果分别声明当前编码器可表达的模态。Platform
将此能力与模型的 `inputModalities` 取交集，把 durable Blob 引用转换为 attempt-only Base64 data URI；
编码器不访问 Blob 存储，也不为附件生成私网地址或预签名 URL。未声明能力的 adapter 默认为空。

| 协议 | 用户内容 | 工具结果 | Base64 wire 形态 |
| --- | --- | --- | --- |
| OpenAI Chat | 配置启用的 IMAGE / AUDIO / PDF | 无媒体 | 图片 `image_url.url`；音频 `input_audio.data/format`；PDF `file.file_data` |
| OpenAI Responses | 图片、PDF | 图片 | `input_image.image_url`、`input_file.file_data` |
| Anthropic | 图片、PDF | 图片、PDF | `source.type=base64`、`source.media_type/data` |
| Gemini | 图片、音频、视频、PDF | 图片、音频、视频、PDF | `inlineData.mimeType/data` |

Chat 的 `openAiChatMediaTypes` 缺省为空；PDF 映射为 Runtime `DOCUMENT`。能力声明不替代编码器的
MIME / 格式校验，也不承诺具体上游模型支持该模态。Chat 单个纯文本块保持字符串，多块或媒体使用数组。

Chat、Responses、Gemini 在最终 UTF-8 序列化后实施 **192 MiB 应用安全上限**；Anthropic 保持
**32 MiB** 上限。超限明确失败，不截断、不改写请求；具体供应商仍可能有更严格限制。

附件物化必须原样保留 assistant 的 `ProviderReplayState`，包括 Chat `reasoning_content`、
Responses `encrypted_content`、Anthropic thinking/signature/redacted thinking 和 Gemini
`thoughtSignature`。原生回放仍要求 format、affinity、源前缀 hash 与 durable 内容一致；本边界不伪造思考，
不取消损坏校验，也不把不透明签名或加密数据当作普通思考文本。

### HttpSseLimits

[`HttpSseLimits`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 统一定义流式传输与错误响应的字节级有界限制：
- `maxLineBytes`：单行最大字节数（默认 1 MiB，与单事件上限对齐，允许单行大 JSON 数据在 1 MiB 事件预算内传输）；
- `maxEventBytes`：单个事件最大累积字节数（默认 1 MiB）；
- `maxSuccessBodyBytes`：成功流累计接收的最大字节数（默认 128 MiB）；
- `maxErrorBodyBytes`：非 2xx 错误响应抓取的最长诊断字节数（默认 64 KiB）。

### HttpOpenMetadata

[`HttpOpenMetadata`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpOpenMetadata.java) 携带 HTTP 连接建立成功的响应元数据，包含响应状态码与经过统一脱敏清洗的只读标头映射（严格采用最小白名单放行协议诊断所需标头，丢弃未知标头，自动脱敏含有 `token`、`key`、`auth`、`cookie`、`secret`、`credential` 等关键词的敏感标头，并过滤非法换行）。

### TransportErrorKind 与 TransportException

[`TransportErrorKind`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportErrorKind.java) 与 [`TransportException`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java) 构成安全的异常通信信道。异常原因链被限制为纯类名与安全消息，错误正文与敏感 URI 绝不在 `getMessage()` 或 `toString()` 中展开；上层可通过受保护的 `errorBodyBytes()` 防御性副本读取经过有界截断的诊断正文，并通过 `isErrorBodyTruncated()` 明确获知是否发生截断。

### Provider 错误透传

OpenAI Chat、OpenAI Responses、Anthropic 与 Gemini 的错误映射器将错误分类与消息内容分开处理：

- HTTP 错误消息为 `HTTP <status>\n<原始 UTF-8 响应正文>`，保留空白、非 JSON 正文、厂商扩展字段、请求 ID 及上游返回的全部错误详情，不脱敏、不做字段白名单过滤。
- 错误正文仍受 `maxErrorBodyBytes` 限制；传输层截断时，消息明确追加 ` [TRUNCATED]`。没有正文时才使用状态码与分类兜底文案。
- SSE 协议错误保留完整解析后 JSON envelope，使用 `JsonNode.toString()` 序列化，不保证原始 JSON 排版或 SSE wire 字节一致。
- 错误分类、重试和取消语义不因消息透传而改变；不额外拼接请求标头、请求 URI、配置凭据或底层异常 cause。
- Runtime、持久化错误与客户端 DTO 继续传递同一消息。对话错误卡片和 `/debug` 详情按纯文本展示，不将上游 HTML 当作页面执行。

上游正文自身若回显敏感内容，也会原样进入错误记录和客户端；访问、保存和分享这些诊断信息时应按敏感数据处理。

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
基于 `ProviderDescriptor.endpoint`（`AnthropicEndpoints.resolveMessagesUri`）幂等解析 `/messages` 端点：已以 `/v1/messages` 或 `/messages` 结尾直接保持，以 `/v1` 结尾追加 `/messages`，其余 base path（含空 path、根路径与自定义 proxy 路径）追加 `/v1/messages`；严格拒绝 user-info、query 与 fragment，保真保留 raw authority（含 IPv6 与端口）及已转义 raw path。
请求同时发送标准 `x-api-key` 与 `Authorization: Bearer`；当且仅当当前请求实际启用了 BUDGET thinking（`model.reasoning=true`，`reasoningEffort` 非 null/空白/none，配置模式为 `BUDGET`）时发送 `anthropic-beta: interleaved-thinking-2025-05-14`，普通请求、未启用推理以及 `ADAPTIVE` 模式均不发送该 beta 头。
适配器按 `AnthropicConfiguration` 解析配置：支持 `anthropicThinkingMode`（`ADAPTIVE` / `BUDGET`）与可选的 `modelAliases`（JSON object，将 logical model name 映射为 wire model id，key 与 value 必须为无环绕空白的非空字符串）；`AnthropicRequestEncoder` 仅将 wire 请求根字段 `model` 替换为解析后的别名，所有 durable 存储、replay affinity 与 source consistency 校验严格保持基于 `request.model().modelName()` 逻辑模型标识；配置解析严格屏蔽原始输入与底层异常回显。
在启用推理时，`thinking` 对象均携带 `display: "summarized"`；`BUDGET` 模式编码 `type: "enabled"` 与 `budget_tokens` 且省略 `output_config`，`ADAPTIVE` 模式编码 `type: "adaptive"` 与 `output_config.effort`。在 `reasoningEffort` 为 `none`、空白或 `null` 时，完全抑制 `thinking` 与 `output_config` 字段。
`AnthropicStreamAccumulator` 将原生总 token 置为 `0L`（Anthropic Messages 协议不提供原生 `total_tokens`），分类分项明细保留在 `ModelUsage.categorizedTokens()` 中。

`AnthropicRequestEncoder` 对请求体实施 32 MiB 上限，并把 Runtime semantic history 编码为 Anthropic content
blocks。`AnthropicStreamAccumulator` 按原生 block index 独立维护交错工具调用，usage 按累计快照更新；只有
`message_stop` 证明协议成功。COMPLETE 可保存已验证的 opaque replay，FILTERED 撤回未完成协议内容，LENGTH
把未闭合工具参数固化为 diagnostic 而不伪造 `{}`。

### OpenAI Chat Completions

[`OpenAiChatProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatProviderAdapter.java)
支持标准 OpenAI Chat Completions 协议，通过 `OpenAiChatConfiguration` 支持扩展参数：
- `openAiChatThinkingFormat`（`STANDARD` / `DEEPSEEK`）：在 `DEEPSEEK` 格式下，当模型声明 `reasoning=true` 时，若未启用思考（`reasoningEffort` 为 `none`、空白或 `null`）显式发送 `thinking: {"type": "disabled"}` 并省略 `reasoning_effort`；若启用思考则发送 `thinking: {"type": "enabled"}` 并附带 `reasoning_effort`；对于非 reasoning 模型或 `STANDARD` 格式保持标准编码。
- 缺失原生 `total_tokens` 时，`OpenAiChatStreamAccumulator` 保持 `providerTotalTokens = 0L`，不人为合成 input + output。

### OpenAI Responses

[`OpenAiResponsesProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesProviderAdapter.java)
支持 OpenAI Responses 协议，通过 `OpenAiResponsesConfig` 提供配置支持（异常消息消毒，不回显输入与 cause）：
- 系统指令角色：`model.reasoning = true` 时 SYSTEM 消息编码为 `role: "developer"`，非推理模型保持 `role: "system"`。
- 推理编码：当 `reasoningEffort` 为 `none` 时仅生成 `reasoning: {"effort": "none"}`，省略 `summary` 且不包含 `reasoning.encrypted_content`；启用思考时生成 `reasoning: {"effort": ..., "summary": "auto"}` 并注入顶层 `include: ["reasoning.encrypted_content"]`。
- 工具：函数工具一律以 `strict: true` 发送，参数 schema 深拷贝后递归归一化为 strict 子集——每个 object 节点（含 `items` 与嵌套 object）显式写出全量 `required` 与 `additionalProperties: false`；源 schema 确实把属性排除在 `required` 之外（即该属性可缺省）且其类型不允许 null 时，才改写为 `anyOf: [原 schema, {"type": "null"}]` 以保留可缺省语义；共享的 `inputSchemaJson` 绝不被改写。模型按 strict schema 为这类原可选属性回传显式 `null` 时，runtime 在原 schema 校验前只把该 null 等价为缺省；required/unknown null 仍严格拒绝。
- 输出上限：`max_output_tokens` 取 variant 上限与本 Provider 下限 `16` 的较大者——低于 16 的值提升到 16，显式更大值原样保留，未声明时不发送该字段。
- 提示缓存：缺省 `AUTOMATIC` 模式遵循 Provider 自治缓存语义（`PromptCacheCapability.automatic()`），不发送任何缓存提示（不发送 `prompt_cache_key`、`prompt_cache_retention`、`prompt_cache_options` 或 `prompt_cache_breakpoint`）；显式模式 `LEGACY`（affinity + retention）与 `GPT_5_6_EXPLICIT`（options + breakpoints）继续按配置映射对应字段。
- Replay：`COMPLETE` / `LENGTH` 且无工具诊断时把白名单 native output（`reasoning` 的 `encrypted_content` 与 `summary`、`message`、`function_call`）冻结为 durable replay state；流式接收的 `reasoning.encrypted_content` 在 terminal output 省略该字段时被合并保留；下一轮仅在 affinity 与 `sourcePrefixHash` 匹配、且回放项与 durable 消息内容一致时原位回放，否则回退语义编码；私有推理文本只作为 `summary`/durable thinking 暴露，`encrypted_content` 不作为文本外泄。
- 不完整推理回放修复：终态 `reasoning` 只给出空占位符（例如 `summary: []` 且无 `encrypted_content`）时，流式累积的思考是唯一可得的语义表示并予以保留（非空的权威终态摘要仍优先），且不冻结自身无法承载该思考的 native replay，改由语义编码承载。消费历史 replay 时，无密文且无任何可用摘要文本的 `reasoning` 占位符被识别为“上游未提供原生推理”而非损坏请求，整体回退语义编码；结构性损坏、未知字段、密文/摘要与 durable 思考矛盾、以及消息文本与工具调用不一致仍严格抛出 `INVALID_REQUEST`，绝不伪造签名或密文，持久化 payload 不被改写。
- Token 审计：当服务端事件流 usage 缺失 `total_tokens` 时，原生总 token 置为 `0L`，且 `rawUsageJson` 白名单中不输出 `total_tokens` 键。

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)。
