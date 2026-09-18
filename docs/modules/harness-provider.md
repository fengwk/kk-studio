# Harness Provider

一次模型调用在成功时是一条漂亮的事件流，失败时才是维护者真正面对的东西：上游 200 但内容被安全策略过滤、连接在半个 JSON 里断掉、错误正文里带着要被原样展示给用户的上游诊断、thinking 签名用错一次就整轮作废。`harness-provider` 的职责是把这些情况都变成 Runtime 能安全消费的结果——它用纯 JDK 21 `HttpClient` 说四种上游协议，用有界增量解析器读取 SSE，把每条流收敛成一个中立的 `ProviderCompletion`，并且**绝不**用通用文案掩盖上游到底说了什么。

模块无 Spring 依赖，生产直接依赖只有 [`harness-runtime`](harness-runtime.md)（协议中立 DTO 与错误分类）与 Jackson；凭据、长生命周期 `HttpClient`、工作线程与 Watchdog 调度器由 [Platform](platform.md) 注入。生产依赖见 [`pom.xml`](../../harness/provider/pom.xml)，[`ProviderModuleArchitectureTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java) 扫描主源码 import 与 POM 守卫该边界。Platform 装配的模型专用 `HttpClient` 固定使用 HTTP/1.1（避免明文网关链路上的 h2c 升级）、`Redirect.NEVER`，并以受管虚拟线程为 worker。

## 传输层

[`JdkHttpSseTransport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransport.java) 是模块唯一的 I/O 入口，构造时强制 `followRedirects() == Redirect.NEVER` 并注入独立的 worker `ExecutorService` 与 `ScheduledExecutorService`。每次调用由 [`HttpSseStreamExecution`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseStreamExecution.java) 承载，它同时是返回给调用方的 [`ProviderStream`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStream.java) 句柄：

- **启动门**：worker 与 watchdog 两个任务都排期成功后才开闸，因此不会出现「watchdog 已跑、worker 还没开始」的中间态。
- **拒绝 inline 执行**：worker 一旦发现运行在自己的调用线程上（direct / caller-runs 执行器），立即判定 `inlineExecutionDetected` 并同步抛 `EXECUTOR_REJECTED`，防止流在提交线程上自等而死锁。
- **任意阶段拒绝**：worker 提交或 watchdog 调度被拒时立即 `abortAdmission()` 并同步抛 `EXECUTOR_REJECTED`，此时任务尚未触碰 `HttpClient`。
- **Watchdog**：基于 `System.nanoTime()` 与饱和换算的单调时钟，巡检间隔取 `min(totalTimeout, idleTimeout)/2` 并夹在 `[1ms, 25ms]`；总调用超时或无活动闲置超时都派发 `TIMEOUT`。每次从流读到字节或错误正文都刷新活动时间。
- **取消**：`cancel()` 幂等，CAS 推进到取消态后关闭底层 `InputStream` 并取消 watchdog 与 worker future，调用方立刻返回。
- **回调仲裁**：状态机 `PENDING / RUNNING / CANCELLED / COMPLETED / FAILED` 配合回调锁保证 Terminal-Once——`onComplete` 与 `onFailure` 互斥且各至多一次，取消后不再有任何回调；回调内部重入取消安全；外部回调抛出未受检异常时被捕获并转为 `CALLBACK_FAILED` 终态。

[`HttpSseLimits`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 以字节而非字符计四项上界，[`DEFAULT`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 为 `maxLineBytes = 1 MiB`、`maxEventBytes = 1 MiB`、`maxSuccessBodyBytes = 128 MiB`、`maxErrorBodyBytes = 64 KiB`；超限立即中止并关闭底层连接。

[`IncrementalSseParser`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParser.java) 逐字节解析，规则都在字节层：

- 首个事件前静默剔除 UTF-8 BOM（`0xEF 0xBB 0xBF`），但这三个字节仍计入成功流累计字节；不足三字节即 EOF 时按普通字符处理。
- 严格拦截 NUL 字节与畸形多字节序列（`CharsetDecoder` 配 `REPORT`），一律 `INVALID_RESPONSE`。
- 换行同时支持 CRLF、LF 与孤立 CR；`data:` 后仅紧邻的一个空格被剔除，多余前导空格与尾随空白完整保留；多行 `data:` 以 `\n` 合并。
- 每个空行边界无条件重置事件缓冲与事件字节计数（无论该行是否有 data）；流 EOF 时 `flush()` 交付未以空行闭合的尾随事件。

[`HttpOpenMetadata`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpOpenMetadata.java) 只放行诊断所需的白名单标头（`content-type`、`content-length`、`retry-after`、`x-ratelimit-*`、`x-request-id`、`request-id`），未知标头一律丢弃，命中 `token`、`key`、`auth`、`cookie`、`secret`、`credential` 关键词的值被替换为 `[REDACTED]`，并剔除非法换行。

[`TransportErrorKind`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportErrorKind.java) 是封闭的七元集合 `IO`、`TIMEOUT`、`INVALID_RESPONSE`、`HTTP_STATUS`、`EXECUTOR_REJECTED`、`CALLBACK_FAILED`、`CANCELLED`。[`TransportException`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java) 是安全信道：`getMessage()` / `toString()` 只含 kind、状态码等类名级信息（换行被清洗），绝不输出 URI、token、请求体或错误正文；cause 被替换为只保留类名的安全异常；经过有界截断的诊断正文只能通过 `errorBodyBytes()` 防御性副本读取，`isErrorBodyTruncated()` 明确告知是否被截断。[`ServerSentEvent`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/ServerSentEvent.java) 的 `toString()` 也只输出 `eventLength` / `dataLength`，避免日常调试日志打印上游正文。

[`HttpSseCallback`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseCallback.java) 是唯一的回调面：`onOpen`、`onEvent`、`onComplete`、`onFailure` 四个生命周期方法，终态至多一次。

## 上游错误原样透传

四个协议的 [`AnthropicErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicErrorMapper.java)、[`GeminiErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiErrorMapper.java)、[`OpenAiChatErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatErrorMapper.java)、[`OpenAiResponsesErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesErrorMapper.java) 共用 [`ProviderErrorHelper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderErrorHelper.java) 的格式化规则，只做错误**分类**，不做正文过滤：

- HTTP 错误消息是 `HTTP <status>\n<原始 UTF-8 响应正文>`，保留空白、非 JSON 正文、厂商扩展字段、请求 ID 与上游给出的全部错误详情，不做字段白名单过滤。
- 正文受 `maxErrorBodyBytes` 限制；一旦传输层截断，消息末尾明确追加 ` [TRUNCATED]`。正文为空时才回退到 `HTTP <status>: <fallback>` 或纯 fallback 文案。
- SSE 协议错误保留完整解析后的 JSON envelope，用 `JsonNode.toString()` 序列化（不保证原始 JSON 排版或 wire 字节一致）。
- 消息刻意不含请求标头、请求 URI、配置凭据与底层异常 cause；错误分类、重试与取消语义不因透传而改变。
- 三类静默错误（`CANCELLED`、`EXECUTOR_REJECTED`、`CALLBACK_FAILED`）不映射为上游错误，因为本地取消或拒绝不代表调用已经失败。

同一消息沿 Runtime 落入 `ModelInvocationError`，持久化为 `harness_model_invocation.error`，再经产品 DTO 原样送达客户端；对话错误卡片与调试详情按纯文本展示，不把上游 HTML 当页面执行。需要注意：如果上游正文自身回显了敏感内容，它同样会进入错误记录与客户端，访问、保存与分享这些诊断时应按敏感数据处理。

## 请求、回放与媒体

[`ProviderAdapter`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderAdapter.java) 是协议工厂：`providerType()`、`mediaCapabilities()` 与 `create(ProviderDescriptor)`。它只接收运行时定义的通用对象（[`ProviderRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderRequest.java)、`ProviderMessage`、`ProviderToolDefinition`、`ProviderCacheControl`），并通过 [`ModelCallTimeoutPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ModelCallTimeoutPolicy.java)（默认 30 分钟总时长 / 120 秒闲置）接收超时策略；上游 SDK 类型完全不出现在这个边界上。流式增量以 sealed [`ProviderStreamEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamEvent.java) 交付（`TextDelta`、`ThinkingDelta`、`ToolCallDelta`），终态是一条 [`ProviderResponse`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderResponse.java) 加可选的原生回放状态。

[`ProviderReplayState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayState.java) = `(format, affinity, sourcePrefixHash, payload)`，四种格式 [`ProviderReplayFormat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayFormat.java) 为 `anthropic_messages` / `openai_responses` / `openai_chat` / `gemini_content`。回放是 attempt 内的原生上下文，逐字节保留上游结构（`reasoning_content`、`encrypted_content`、thinking `signature` / redacted thinking、`thoughtSignature`），它不进入公共 DTO、日志或异常。

冻结与回放的口径按协议有实质差异，这是最容易写错的地方：

| 协议 | 冻结条件 | 工具调用诊断 |
| --- | --- | --- |
| Anthropic | `COMPLETE` 或 `LENGTH`，且无 tool call 诊断 | LENGTH 截断时把未闭合工具参数固化为 diagnostic，不伪造 `{}` |
| Gemini | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Chat | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Responses | `COMPLETE` 或 `LENGTH`，且无诊断、非 FILTERED、非空占位符 | 同 Anthropic 语义 |

原位回放必须同时满足四项：白名单字段与结构合法、`affinity` 与当前 `ProviderDescriptor.affinity(requestedModel)` 相等、`sourcePrefixHash` 与当前 canonical prefix hash 相等、回放内容与 durable 消息文本/思考/工具调用逐字段一致。affinity 或 hash 失配但 payload 合法时静默回退语义编码；未知字段、非法 role、损坏 JSON、密文或非空摘要与 durable 思考矛盾、文本或工具调用与 durable 矛盾一律抛 `INVALID_REQUEST`，且密文原样保留、持久化 payload 不被改写。prefix hash 由各协议自己的 `*PrefixHasher` 计算（稳定字典序与确定性序列化，Anthropic 侧显式排除 `cache_control`），因此回放链路的稳定性不依赖 JSON 字段顺序。

OpenAI Responses 有两处需要特别维护的边界：流式收到的 `reasoning.encrypted_content` 在终态 output 省略该字段时会被合并保留，避免把可回放的原生推理丢掉；而当终态 `reasoning` 只给出空占位符（`summary: []` 且无密文）时，流式累积的思考是唯一可得的语义表示，会被保留用于展示，但该 replay 不承载原生推理，既不冻结也不原位回放（非空的权威终态摘要仍优先）。私有推理文本只以 `summary` / durable thinking 的形式暴露，`encrypted_content` 绝不当作文本外泄。

媒体能力由 [`ProviderMediaCapabilities`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderMediaCapabilities.java) 按用户内容与工具结果分别声明；Platform 把它与模型 `inputModalities` 取交集，把 durable Blob 引用转换成 attempt-only 的 Base64 data URI。编码器不访问 Blob 存储，也不生成私网地址或预签名 URL，未声明的 adapter 默认为 `NONE`。

| 协议 | 用户内容 | 工具结果 | Base64 wire 形态 |
| --- | --- | --- | --- |
| OpenAI Chat | 由 `openAiChatMediaTypes` 派生，缺省为空；IMAGE / AUDIO / PDF 分别映射 IMAGE / AUDIO / DOCUMENT | 无媒体 | 图片 `image_url.url`；音频 `input_audio.data/format`；PDF `file.file_data` |
| OpenAI Responses | 图片、PDF | 图片 | `input_image.image_url`、`input_file.file_data` |
| Anthropic | 图片、PDF | 图片、PDF | `source.type=base64` 与 `source.media_type/data` |
| Gemini | 图片、音频、视频、PDF | 图片、音频、视频、PDF | `inlineData.mimeType/data`（或 `fileData`） |

能力声明只描述本编码器能表达的 schema，不承诺具体上游模型支持该模态，也不替代编码器自己的 MIME / 格式校验。Chat 侧单个纯文本块保持字符串，多块或含媒体时使用数组。

请求体的最终 UTF-8 字节上限在序列化完成后统一收口：[`RequestBodySizeGuard`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/RequestBodySizeGuard.java) 的 `MAX_REQUEST_BODY_BYTES` 是 **192 MiB**，Chat、Responses 与 Gemini 都走它；Anthropic 保留更严格的 **32 MiB** 协议级上限。超限一律以 `INVALID_REQUEST` 明确失败，不静默截断、不降级、不改写请求；厂商仍可能有自己的更严格限制。

## 流式聚合与终止

四个累积器都实现同一件事：把增量事件收敛成一条完整 `ProviderResponse` 加可选 replay state，并用 `message_stop`、`response.completed` 之类的协议证据证明「这条流确实正常结束」。[`GenerationStopReason`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/GenerationStopReason.java) 只有 `COMPLETE` / `LENGTH` / `FILTERED`，与工具调用正交（`FILTERED` 必须无 calls）。

- Anthropic [`AnthropicStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicStreamAccumulator.java)：按原生 block index 维护内容块状态机，支持交错的多工具调用 delta 与连续 `toolOrdinal`；usage 按累计快照更新，支持 `cache_creation` 的 5m / 1h 拆分；只有 `message_stop` 才算成功。`end_turn` / `stop_sequence` / `tool_use` 归一为 COMPLETE，`max_tokens` / `model_context_window_exceeded` 为 LENGTH，`refusal` 为 FILTERED。Messages 协议不提供原生 `total_tokens`，累计器把 `providerTotalTokens` 固定为 `0L` 而不是合成 input + output，分类明细仍保留在 `ModelUsage.categorizedTokens()` 中。
- OpenAI Chat [`OpenAiChatStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatStreamAccumulator.java)：按 `delta.tool_calls[i].index` 累积片段，`stop` / `tool_calls` 归一为 COMPLETE，`length` 为 LENGTH，`content_filter` 为 FILTERED；usage 缺 `total_tokens` 时保持 `0L`。
- OpenAI Responses [`OpenAiResponsesStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStreamAccumulator.java)：`response.completed` 为 COMPLETE，`response.incomplete` 按 `incomplete_details.reason` 区分 FILTERED 与其他 LENGTH；工具调用按 `response.output_item.*` 分配连续 `0..N-1` ordinal；usage 缺失 `total_tokens` 时不输出该键且原生总数为 `0L`。
- Gemini [`GeminiStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiStreamAccumulator.java)：`STOP` 为 COMPLETE，`MAX_TOKENS` 为 LENGTH，`SAFETY` / `RECITATION` / `PROHIBITED_CONTENT` / `SPII` / `BLOCKLIST` / `IMAGE_SAFETY` / `IMAGE_RECITATION` / `IMAGE_PROHIBITED_CONTENT` / `LANGUAGE` 为 FILTERED；`MALFORMED_*`、`MISSING_THOUGHT_SIGNATURE`、`UNEXPECTED_TOOL_CALL`、`TOO_MANY_TOOL_CALLS`、`OTHER` 等异常结束原因直接判为 `INVALID_RESPONSE`。

## 协议差异

**Anthropic Messages**（[`AnthropicProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicProviderAdapter.java)）。端点由 [`AnthropicEndpoints.resolveMessagesUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicEndpoints.java) 幂等解析：已以 `/v1/messages` 或 `/messages` 结尾保持不变，以 `/v1` 结尾追加 `/messages`，其余 base path 追加 `/v1/messages`；严格拒绝 user-info、query 与 fragment，并保真保留 raw authority（含 IPv6 与端口）与已转义 raw path。请求同时发送 `x-api-key` 与 `Authorization: Bearer`，并始终带 `anthropic-version: 2023-06-01`。`anthropic-beta: interleaved-thinking-2025-05-14` 只在**实际启用 BUDGET 思考**时发送（`reasoning=true`、effort 非空且模式为 BUDGET）；`.env` 之外的普通请求、未启用推理与 ADAPTIVE 模式都不发送该 beta 头。

[`AnthropicConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicConfiguration.java) 只解析一个字段 `anthropicThinkingMode`（`ADAPTIVE` / `BUDGET`，缺省 ADAPTIVE）：wire 模型标识直接取 `ModelDescriptor.modelId()`，不存在别名替换；JSON 语法与字段类型严格校验，未知字段忽略，异常绝不回显配置内容或凭据。推理编码上，`reasoningOff` 发送 `thinking: {"type": "disabled"}`；ADAPTIVE 发送 `thinking: {"type": "adaptive", "display": "summarized"}` 与 `output_config.effort`；BUDGET 发送 `thinking: {"type": "enabled", "budget_tokens": ..., "display": "summarized"}` 并把 `low` / `medium` / `high` 映射为 2048 / 8192 / 16384，`budget_tokens` 必须小于 `max_tokens`。`reasoningEffort` 为 null 时完全不声明推理字段，由服务端决定。Prompt Cache 支持 SYSTEM、TOOLS、CONVERSATION 三类显式断点，SHORT 用默认短 TTL，LONG 映射 `ttl: "1h"`；请求中没有任何合格断点时拒绝构造。

**OpenAI Chat Completions**（[`OpenAiChatProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatProviderAdapter.java)）。端点解析在去尾斜杠后追加 `/chat/completions`。[`OpenAiChatConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatConfiguration.java) 支持 `includeUsage`、`requireDone`、`openAiChatMediaTypes`、`openAiChatPromptCacheMode` 与 `openAiChatThinkingFormat`（`STANDARD` / `DEEPSEEK`）：DEEPSEEK 格式下，`reasoning=true` 且关闭思考时显式发送 `thinking: {"type": "disabled"}` 并省略 `reasoning_effort`，启用时发送 `thinking: {"type": "enabled"}` 并附带 `reasoning_effort`；STANDARD 格式的关闭值仍是 `reasoning_effort: "none"`，非推理模型不发送任何推理字段。

**OpenAI Responses**（[`OpenAiResponsesProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesProviderAdapter.java)）。[`OpenAiResponsesConfig`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesConfig.java) 只解析 `openAiPromptCacheMode`。系统指令在 `model.reasoning=true` 时编码为 `role: "developer"`，非推理模型保持 `role: "system"`。推理字段：关闭时只生成 `reasoning: {"effort": "none"}` 且不含 `summary` 与 `include`；启用时生成 `reasoning: {"effort": ..., "summary": "auto"}` 并注入顶层 `include: ["reasoning.encrypted_content"]`。函数工具一律以 `strict: true` 发送，参数 schema 深拷贝后由 [`OpenAiResponsesStrictSchema`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStrictSchema.java) 递归归一化：每个 object 节点（含 `items` 与嵌套 object）显式写出全量 `required` 与 `additionalProperties: false`；只有源 schema 确实把某属性排除在 `required` 之外、且它当前不允许 null 时才改写为 `anyOf: [原 schema, {"type": "null"}]`，本来就是必填或已允许 null 的属性保持原样；调用方共享的 `inputSchemaJson` 绝不被修改。`max_output_tokens` 的下限 `16` 只属于本 Provider——**低于 16 直接以 `INVALID_REQUEST` 拒绝，不静默提升也不改写**。提示缓存模式下 `AUTOMATIC` 不发送任何缓存字段，`LEGACY` 发送 `prompt_cache_key` 与 `prompt_cache_retention`，`GPT_5_6_EXPLICIT` 发送 `prompt_cache_options` 与 content block 上的 `prompt_cache_breakpoint`。

**Google Gemini**（[`GeminiProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiProviderAdapter.java)）。[`GeminiEndpoints.resolveStreamUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiEndpoints.java) 保留 raw authority 与 raw base path，去除尾斜杠后按 RFC 3986 path-segment 语义编码 model name 并追加 `/models/{encodedModel}:streamGenerateContent?alt=sse`，绝不把 API Key 拼进 URL。角色映射只有 `user` 与 `model`，相邻同 role 内容合并为一个 content 节点。Prompt Cache 固定为隐式 `PromptCacheCapability.automatic()`：服务端自行评估并回报 `cachedContentTokenCount`，编码器不发任何 cache hint，且显式非空 `cacheControl` 会被拒绝。

## 包架构

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.provider` | 跨协议共享的 HTTP 错误格式化 `ProviderErrorHelper` 与请求体上限守卫 `RequestBodySizeGuard` | 不解析厂商错误分类，不修改原始响应正文 |
| `fun.fengwk.kkstudio.harness.provider.transport` | `JdkHttpSseTransport`、`HttpSseStreamExecution`、`IncrementalSseParser`、`HttpSseLimits`、`HttpOpenMetadata`、`HeaderSanitizer`、`TransportErrorKind`、`TransportException`、`ServerSentEvent`、`HttpSseCallback` | 只依赖 `harness-runtime` 基础模型与 JDK；不暴露凭据，不保留未清洗的异常上下文 |
| `fun.fengwk.kkstudio.harness.provider.anthropic` | Messages wire 编码、流式聚合、Prompt Cache、thinking 编码、错误映射、prefix hash 与 adapter | 只经 transport 发起 I/O；opaque replay 不进入公共 DTO、日志或异常 |
| `fun.fengwk.kkstudio.harness.provider.gemini` | GenerateContent wire 编码、流式聚合、隐式 Prompt Cache 与 adapter | 只经 transport 发起 I/O；固定声明 AUTOMATIC 缓存能力 |
| `fun.fengwk.kkstudio.harness.provider.openai.chat` | Chat Completions wire 编码、流式聚合、动态 Prompt Cache、tool call 与 adapter | 只经 transport 发起 I/O；按 configJson 动态解析媒体与 thinking 格式 |
| `fun.fengwk.kkstudio.harness.provider.openai.responses` | Responses wire 编码、strict schema 归一化、流式聚合、动态 Prompt Cache 与 adapter | 只经 transport 发起 I/O；按 configJson 动态解析 PromptCacheMode |

## 源码与测试

- 传输：[`JdkHttpSseTransport.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransport.java)、[`HttpSseStreamExecution.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseStreamExecution.java)、[`IncrementalSseParser.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParser.java)、[`HttpSseLimits.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java)、[`TransportException.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java)。
- 共享契约：[`ProviderErrorHelper.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderErrorHelper.java)、[`RequestBodySizeGuard.java`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/RequestBodySizeGuard.java)。
- 四个协议各有一套 `*ProviderAdapter`、`*RequestEncoder`、`*StreamAccumulator`、`*StreamBridge`、`*ErrorMapper`、`*PrefixHasher`、`*Endpoints` 与 `*Configuration`。

测试守卫：`IncrementalSseParserTest`、`JdkHttpSseTransportTest`、`HttpSseLimitsTest`、`HeaderSanitizerTest` 覆盖字节级解析、启动门与 Watchdog、上限边界与标头脱敏；`ProviderErrorHelperTest`、各协议 `*ErrorMapperTest` 锁定原始正文透传、`[TRUNCATED]` 标记与分类；`*RequestEncoderTest`、`*WireTest`、`*ThinkingTest`、`*PrefixHasherTest` 锁定 wire 形状、推理编码与 prefix hash；`*StreamAccumulatorTest`、`*StreamingDecoderTest`、`*StreamBridgeTest` 覆盖增量聚合、终态证据与桥接取消；`OpenAiResponsesDurableReplayTest`、`OpenAiResponsesEmptyReasoningReplayRepairTest` 专门守住 Responses 的加密内容合并与空占位符回退。每个协议还有一个 `UpstreamTestManifestTest` 校验同目录的 `upstream-test-manifest.json`：它以机器可读清单固化 LangChain4j 1.20.0（提交 `3a2f4dca6fb447e4d191624b3d588952ed9f4ce9`）上游测试的对齐口径——transport 115 个方法 / 141 个 invocation 中 49 PORTED、92 OUT_OF_SCOPE；Anthropic 40 文件 / 423 方法 / 577 invocation 中 146 PORTED、431 OUT_OF_SCOPE、121 REQUIRES_CREDENTIAL；OpenAI Chat 70 文件 / 502 方法 / 659 invocation 中 238 / 421 / 390；Responses 15 文件 / 165 invocation 中 45 / 120 / 93；Gemini 51 文件 / 676 invocation 中 145 / 531 / 281。清单拒绝虚构对等：PORTED 项必须经反射验证目标测试真实存在且带 `@Test` / `@ParameterizedTest`，OUT_OF_SCOPE 项必须逐条说明架构不匹配原因且 `targetTest` 为 null，需要真实凭据的项独立标记为未执行。本模块 POM 绑定 JaCoCo `check`，对上述全部关键生产类（含四个协议的 Encoder / Accumulator / Bridge / Adapter / Hasher / Mapper 与传输核心）要求行覆盖率不低于 0.90。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Infra](harness-infra.md)、[Platform](platform.md)、[Harness Environment](harness-environment.md)。
