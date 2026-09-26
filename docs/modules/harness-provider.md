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

[`ProviderAdapter`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderAdapter.java) 是协议工厂：`providerType()`、`mediaCapabilities()` 与 `create(ProviderDescriptor)`。它接收运行时定义的通用对象（[`ProviderRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderRequest.java)、`ProviderMessage`、`ProviderToolDefinition`、`ProviderCacheControl`），并通过 [`ModelCallTimeoutPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ModelCallTimeoutPolicy.java)（默认 30 分钟总时长 / 120 秒闲置）接收超时策略；上游 SDK 类型完全不出现在这个边界上。会话角色 [`ProviderMessageRole`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderMessageRole.java) 仅有 `USER`、`ASSISTANT`、`TOOL`，系统指令单独由 `ProviderRequest.systemInstruction` 承载，会话消息中绝不出现系统角色。各协议把它编码到各自的顶层位置：Anthropic `system`、Gemini `systemInstruction`、OpenAI Chat 唯一的前导 wire `system` 消息、OpenAI Responses `instructions`。流式规范化增量以 sealed [`ProviderStreamEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamEvent.java) 交付（`TextDelta`、`ThinkingDelta`、`ToolCallDelta`），终态是一条 [`ProviderResponse`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderResponse.java) 加可选的原生回放状态。

每个 model variant 可携带 [`ProviderProtocolOptions`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/ProviderProtocolOptions.java)：严格 JSON object，拒绝重复键和尾随 token，canonical JSON 最多 **64 KiB UTF-8**；空值按 `{}` 处理，错误不回显选项内容。后端解析使用 `BigInteger` / `BigDecimal` 避免浮点精度损失，[`ProviderProtocolOptionsJson`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderProtocolOptionsJson.java) 为每次编码提供独立副本。选项只合并到该协议的**请求体**，不是修改凭据、endpoint、标头或传输行为的万能开关；字段所有权、嵌套对象与原生 tools 的合并规则以对应编码器为准，冲突以 `INVALID_REQUEST` 失败。前端编辑器目前用 `JSON.parse` / `JSON.stringify`，超过 JS 安全整数或需要精确十进制的值可能在前端编辑往返时丢精度；后端保真并不能消除这个限制。

[`ProviderReplayState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayState.java) = `(format, affinity, sourcePrefixHash, payload)`，四种格式 [`ProviderReplayFormat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayFormat.java) 为 `anthropic_messages` / `openai_responses` / `openai_chat` / `gemini_content`。回放是 attempt 内的原生上下文，按 JSON 字段保留可回放的上游结构（`reasoning_content`、`encrypted_content`、thinking `signature` / redacted thinking、`thoughtSignature`），不承诺保留 wire 字节排版；它不进入公共 DTO、日志或异常。

冻结与回放的口径按协议有实质差异，这是最容易写错的地方：

| 协议 | 冻结条件 | 工具调用诊断 |
| --- | --- | --- |
| Anthropic | `COMPLETE`、`LENGTH` 或 `CONTINUE`，且无 tool call 诊断 | LENGTH 截断时把未闭合工具参数固化为 diagnostic，不伪造 `{}`；CONTINUE 不允许 tool call |
| Gemini | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Chat | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Responses | `COMPLETE` 或 `LENGTH`，且无诊断、非 FILTERED、非空占位符 | 同 Anthropic 语义 |

原位回放须满足：原生 payload 结构合法，`affinity` 与当前 `ProviderDescriptor.affinity(requestedModel)` 相等，`sourcePrefixHash` 与当前 canonical prefix hash 相等，已规范化的文本/思考/函数工具事实与 durable 消息逐字段一致。已知 item/block 的合法原生附加字段不再被一概白名单裁剪：能证明可重建的字段可在 affinity/hash 失配时回退语义编码；签名、密文、注解、未知 item 等只有原生回放才能保真的事实若遇失配，必须 **fail closed** 为 `INVALID_REQUEST`，不得假装回退成功。非法 role、损坏结构或与 durable 事实矛盾也拒绝；回放 payload 不被改写。prefix hash 由各协议自己的 `*PrefixHasher` 计算（稳定字典序与确定性序列化，Anthropic 侧显式排除 `cache_control`），因此回放链路的稳定性不依赖 JSON 字段顺序。

OpenAI Responses 有两处需要特别维护的边界：流式收到的 `reasoning.encrypted_content` 在终态 output 省略该字段时会被合并保留，避免把可回放的原生推理丢掉；而当终态 `reasoning` 只给出空占位符（`summary: []` 且无密文）时，流式累积的思考是唯一可得的语义表示，会被保留用于展示，但该 replay 不承载原生推理，既不冻结也不原位回放（非空的权威终态摘要仍优先）。私有推理文本只以 `summary` / durable thinking 的形式暴露，`encrypted_content` 绝不当作文本外泄。

媒体能力由 [`ProviderMediaCapabilities`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderMediaCapabilities.java) 按用户内容与工具结果分别声明；Platform 把它与模型 `inputModalities` 取交集，把 durable Blob 引用转换成 attempt-only 的 Base64 data URI。编码器不访问 Blob 存储，也不生成私网地址或预签名 URL，未声明的 adapter 默认为 `NONE`。

| 协议 | 用户内容 | 工具结果 | Base64 wire 形态 |
| --- | --- | --- | --- |
| OpenAI Chat | 图片、音频、PDF | 无媒体 | 图片 `image_url.url`；音频 `input_audio.data/format`；PDF `file.file_data` |
| OpenAI Responses | 图片、PDF | 图片、PDF | 用户内容 `input_image.image_url`、`input_file.file_data`/`file_url`；工具结果 `function_call_output.output` 数组的 `input_text` / `input_image` / `input_file` 项 |
| Anthropic | 图片、PDF | 图片、PDF | `source.type=base64` 与 `source.media_type/data`（图片也接受 http(s) `url`；文档只接受 base64） |
| Gemini | 图片、音频、视频、PDF | 图片、PDF | 用户内容 `inlineData.mimeType/data`（或 `fileData`）；工具结果只允许内联在 `functionResponse.parts[].inlineData` |

工具结果媒体有两个容易写反的位置约束：

- **Gemini**：媒体必须内联在 `functionResponse.parts[].inlineData`，绝不能作为外层 `Content.parts` 的同级 part——同级 part 会被解析为该 role 的独立输入，而不是这次函数调用的返回值。Gemini Developer API 的 v1beta schema 里 `FunctionResponseBlob` 只有 `mimeType` 与 `data`，没有 `displayName`，官方文档描述的 `response` 内 `{"$ref": "<displayName>"}` 引用形态只成立于 Vertex AI 的 `FunctionResponseBlob` / `FunctionResponseFileData`；本编码器因此只把媒体挂到 `parts` 上，不伪造 `displayName` 或 `$ref`。工具结果只接受保守白名单 `image/jpeg`、`image/png`、`image/webp` 与 `application/pdf` 的 Base64 data URI，音频、视频、其他 MIME 与 http(s)/`gs` URL 来源一律以 `INVALID_REQUEST` 失败。
- **OpenAI Chat**：tool message 的 `content` 只能是字符串，任何媒体块都以 `INVALID_REQUEST` 失败。

能力声明只描述本编码器能表达的 schema，不承诺具体上游模型支持该模态，也不替代编码器自己的 MIME / 格式校验。Chat 侧单个纯文本块保持字符串，多块或含媒体时使用数组；Responses 工具结果同理：单个文本或 JSON 保持字符串，含媒体时使用 `output` 数组。

请求体的最终 UTF-8 字节上限在序列化完成后统一收口：[`RequestBodySizeGuard`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/RequestBodySizeGuard.java) 的 `MAX_REQUEST_BODY_BYTES` 是 **192 MiB**，Chat、Responses 与 Gemini 都走它；Anthropic 保留更严格的 **32 MiB** 协议级上限。超限一律以 `INVALID_REQUEST` 明确失败，不静默截断、不降级、不改写请求；厂商仍可能有自己的更严格限制。

## 原生事件与流式聚合

[`ProviderProtocolEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderProtocolEvent.java) 以 `(eventType, data)` 向 [`ProviderStreamHandler.onProtocolEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamHandler.java) 提供独立的 **attempt-only 原生 SSE 通道**：四个协议在处理已接收的 SSE 帧时先回调原生内容，再解析为规范化增量或协议错误。它与规范化回调共享取消/终态仲裁，但不进入 durable/realtime delta、产品 DTO 或日志；`toString()` 不打印 data。未知事件仍可从原生通道观察，**不代表**未知 delta 可以凭空拼成安全的终态 replay 或已规范化语义。

四个累积器把可理解的增量事件收敛成一条 `ProviderResponse` 加可选 replay state，并用 `message_stop`、`response.completed` 之类的协议证据证明「这条流确实正常结束」。[`GenerationStopReason`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/GenerationStopReason.java) 包含 `COMPLETE` / `LENGTH` / `FILTERED` / `CONTINUE`；后者表示本次必须续写（如 Anthropic `pause_turn` / `compaction`），`FILTERED` 与 `CONTINUE` 都不得承载工具调用意图。

- Anthropic [`AnthropicStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicStreamAccumulator.java)：按原生 block index 维护内容块状态机，支持交错的多工具调用 delta 与连续 `toolOrdinal`；usage 按累计快照更新，支持 `cache_creation` 的 5m / 1h 拆分；只有 `message_stop` 才算成功。`end_turn` / `stop_sequence` / `tool_use` 归一为 COMPLETE，`max_tokens` / `model_context_window_exceeded` 为 LENGTH，`refusal` 为 FILTERED，`pause_turn` / `compaction` 为 CONTINUE；文本 citations 等已知 delta 保留在原生 block，未知 block/delta 配对不能安全合成时显式失败。Messages 协议不提供原生 `total_tokens`，累计器把 `providerTotalTokens` 固定为 `0L` 而不是合成 input + output，分类明细仍保留在 `ModelUsage.categorizedTokens()` 中。
- OpenAI Chat [`OpenAiChatStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatStreamAccumulator.java)：只规范化 `choices[0]`，按 `delta.tool_calls[i].index` 累积片段；其他 choices 只在原生 SSE 通道可见。`stop` / `tool_calls` 归一为 COMPLETE，`length` 为 LENGTH，`content_filter` 为 FILTERED；usage 缺 `total_tokens` 时保持 `0L`。原生 assistant audio 冻结为可请求回放的 `audio.id`，audio 其他字段及 annotations 仅在原生事件中保留；旧版 `finish_reason=function_call` 不支持。
- OpenAI Responses [`OpenAiResponsesStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStreamAccumulator.java)：`response.completed` 为 COMPLETE，`response.incomplete` 按 `incomplete_details.reason` 区分 FILTERED 与其他 LENGTH；工具调用按 `response.output_item.*` 分配连续 `0..N-1` ordinal；已知 `message` / `reasoning` / `function_call` item 的 id/status/附加字段与输出文本注解等可保留在原生 replay，未知 item 按槽位保留而不冒充规范化工具调用；usage 缺失 `total_tokens` 时不输出该键且原生总数为 `0L`。
- Gemini [`GeminiStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiStreamAccumulator.java)：只规范化 `candidates[0]`（其他 candidates 只在原生 SSE 通道可见）。同 slot 的纯文本可证明为增量或累计快照时才合并；含 `thoughtSignature` 的 Part 保持原生边界，不能把签名迁移到拼接后的另一个 Part。`STOP` 为 COMPLETE，`MAX_TOKENS` 为 LENGTH，`SAFETY` / `RECITATION` / `PROHIBITED_CONTENT` / `SPII` / `BLOCKLIST` / `IMAGE_SAFETY` / `IMAGE_RECITATION` / `IMAGE_PROHIBITED_CONTENT` / `LANGUAGE` / `ESCALATION` 为 FILTERED；`MALFORMED_*`、`MISSING_THOUGHT_SIGNATURE`、`UNEXPECTED_TOOL_CALL`、`TOO_MANY_TOOL_CALLS`、`OTHER`、`IMAGE_OTHER`、`NO_IMAGE` 等异常结束原因直接判为 `INVALID_RESPONSE`。

## 四协议能力与边界

| 流式协议 | 原生请求选项与运行时保护 | 原生事件、终态与回放 |
| --- | --- | --- |
| Anthropic Messages | 合并非运行时字段及原生 tools；拒绝覆盖 `model/max_tokens/stream/messages/system/cache_control`，运行时推理开启时保护 `thinking` 与 `output_config.effort` | 独立 SSE 原生通道；text/thinking/function tool 增量，citations 等已知 block 原样保留；`pause_turn` / `compaction` 为 CONTINUE，可按原生状态续写 |
| OpenAI Chat Completions | 合并扩展选项与原生 tools；拒绝覆盖模型、消息、预算、stream 及缓存所有权字段；`stream_options.include_usage` 由配置决定 | 独立 SSE 原生通道；只规范化 `choices[0]`，assistant 原生字段在可证明时回放，audio 只回放 id、annotations 只保留原生帧 |
| OpenAI Responses | 合并原生 tools/include/reasoning；运行时 `model/stream/store/instructions` 不可冲突，拒绝有状态 `previous_response_id/conversation` 与运行时缓存字段 | 独立 SSE 原生通道；已知 output item 的附加字段和未知 item 可原生保留，函数调用才进入规范化 tool 路径 |
| Gemini streamGenerateContent | 合并 `generationConfig` 与原生 tools；拒绝覆盖 `contents/systemInstruction/cachedContent`、`maxOutputTokens` 及运行时指定的 `thinkingConfig` | 独立 SSE 原生通道；只规范化 `candidates[0]`，签名 Part 边界保留，只有可证明安全的文本片段才合并 |

这不是上游全部能力的规范化交付承诺。其他 Chat choices / Gemini candidates 仅原生可见；custom/client computer/shell/MCP approval 等工具或审批没有规范化执行与结果提交路径，原生选项能表达请求不代表 Runtime 会处理其调用。遇到无法重建的原生事实、affinity/hash 失配不能保真时失败关闭；未知 delta 不凭猜测生成 replay。原生 SSE 仅限当前 attempt，不能通过 durable/realtime API 当作通用能力消费。

## 协议差异

**Anthropic Messages**（[`AnthropicProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicProviderAdapter.java)）。端点由 [`AnthropicEndpoints.resolveMessagesUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicEndpoints.java) 幂等解析：已以 `/v1/messages` 或 `/messages` 结尾保持不变，以 `/v1` 结尾追加 `/messages`，其余 base path 追加 `/v1/messages`；严格拒绝 user-info、query 与 fragment，并保真保留 raw authority（含 IPv6 与端口）与已转义 raw path。请求同时发送 `x-api-key` 与 `Authorization: Bearer`，并始终带 `anthropic-version: 2023-06-01`。`anthropic-beta` 合并配置的 beta features 与运行时需要的标识并去重；仅在**实际启用 BUDGET 思考**（`reasoning=true`、effort 非空且模式为 BUDGET）时自动追加 `interleaved-thinking-2025-05-14`，其他模式不会自动追加，但显式配置的 beta 标识仍会发送。

[`AnthropicConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicConfiguration.java) 解析 `anthropicThinkingMode`（`ADAPTIVE` / `BUDGET`，缺省 ADAPTIVE）和 `anthropicBetaFeatures`（去重、标识及头长度有界）：wire 模型标识直接取 `ModelDescriptor.modelId()`，不存在别名替换；JSON 语法与字段类型严格校验，未知配置字段忽略，异常绝不回显配置内容或凭据。推理编码上，`reasoningOff` 发送 `thinking: {"type": "disabled"}`；ADAPTIVE 发送 `thinking: {"type": "adaptive", "display": "summarized"}` 与 `output_config.effort`；BUDGET 发送 `thinking: {"type": "enabled", "budget_tokens": ..., "display": "summarized"}` 并把 `low` / `medium` / `high` 映射为 2048 / 8192 / 16384，`budget_tokens` 必须小于 `max_tokens`。运行时指定 effort 时原生 `thinking` / `output_config.effort` 不能冲突；`reasoningEffort` 为 null 时不由运行时声明推理字段，原生选项可按协议自身规则携带这些字段。Prompt Cache 支持 SYSTEM、TOOLS、CONVERSATION 三类显式断点，SHORT 用默认短 TTL，LONG 映射 `ttl: "1h"`；请求中没有任何合格断点时拒绝构造。

**OpenAI Chat Completions**（[`OpenAiChatProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatProviderAdapter.java)）。端点解析在去尾斜杠后追加 `/chat/completions`。用户图片按标准 content part 编码为 `{"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}`；音频仅支持 Base64 输入的 wav/mp3，不接受音频 http(s) URL；adapter 声明的是协议 schema 能力，具体模型是否接收仍由模型 `inputModalities` 控制，不再额外设置 Provider 媒体开关。[`OpenAiChatConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatConfiguration.java) 支持 `openAiChatIncludeUsage`、`openAiChatRequireDone`、`openAiPromptCacheMode`（`AUTOMATIC` / `LEGACY` / `GPT_5_6_EXPLICIT`）与 `openAiChatThinkingFormat`（`STANDARD` / `DEEPSEEK`）：DEEPSEEK 格式下，`reasoning=true` 且关闭思考时显式发送 `thinking: {"type": "disabled"}` 并省略 `reasoning_effort`，启用时发送 `thinking: {"type": "enabled"}` 并附带 `reasoning_effort`；STANDARD 格式的关闭值仍是 `reasoning_effort: "none"`，非推理模型不发送运行时推理字段。

**OpenAI Responses**（[`OpenAiResponsesProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesProviderAdapter.java)）。[`OpenAiResponsesConfig`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesConfig.java) 解析 `openAiPromptCacheMode`。系统指令编码为顶层 `instructions` 字符串，会话输入项中绝不出现系统或开发者指令；有状态 `previous_response_id` / `conversation` 与无状态 replay 不兼容，明确拒绝。推理字段：运行时关闭时写 `reasoning.effort: "none"` 且不自动添加 `summary` 与 `include`；启用时写 `reasoning.effort`，缺失时补 `summary: "auto"` 与顶层 `include: ["reasoning.encrypted_content"]`，不覆盖兼容的原生附加选项。函数工具一律以 `strict: true` 发送，参数 schema 深拷贝后由 [`OpenAiResponsesStrictSchema`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStrictSchema.java) 递归归一化：每个 object 节点（含 `items` 与嵌套 object）显式写出全量 `required` 与 `additionalProperties: false`；只有源 schema 确实把某属性排除在 `required` 之外、且它当前不允许 null 时才改写为 `anyOf: [原 schema, {"type": "null"}]`，本来就是必填或已允许 null 的属性保持原样；调用方共享的 `inputSchemaJson` 绝不被修改。`max_output_tokens` 的下限 `16` 只属于本 Provider——**低于 16 直接以 `INVALID_REQUEST` 拒绝，不静默提升也不改写**。提示缓存模式下 `AUTOMATIC` 不发送任何缓存字段，`LEGACY` 发送 `prompt_cache_key` 与 `prompt_cache_retention`，`GPT_5_6_EXPLICIT` 发送 `prompt_cache_options` 与 conversation content block 上的 `prompt_cache_breakpoint`（无 SYSTEM 断点）。

**Google Gemini**（[`GeminiProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiProviderAdapter.java)）。[`GeminiEndpoints.resolveStreamUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiEndpoints.java) 保留 raw authority 与 raw base path，去除尾斜杠后按 RFC 3986 path-segment 语义编码 model name 并追加 `/models/{encodedModel}:streamGenerateContent?alt=sse`，绝不把 API Key 拼进 URL。角色映射只有 `user` 与 `model`，相邻同 role 内容合并为一个 content 节点；原生 signed Part 回放时仍保持签名及 Part 边界。Prompt Cache 固定为隐式 `PromptCacheCapability.automatic()`：服务端自行评估并回报 `cachedContentTokenCount`，编码器不发任何 cache hint，且显式非空 `cacheControl` 会被拒绝。

## 包架构

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.provider` | 跨协议共享的 HTTP 错误格式化 `ProviderErrorHelper` 与请求体上限守卫 `RequestBodySizeGuard` | 不解析厂商错误分类，不修改原始响应正文 |
| `fun.fengwk.kkstudio.harness.provider.transport` | `JdkHttpSseTransport`、`HttpSseStreamExecution`、`IncrementalSseParser`、`HttpSseLimits`、`HttpOpenMetadata`、`HeaderSanitizer`、`TransportErrorKind`、`TransportException`、`ServerSentEvent`、`HttpSseCallback` | 只依赖 `harness-runtime` 基础模型与 JDK；不暴露凭据，不保留未清洗的异常上下文 |
| `fun.fengwk.kkstudio.harness.provider.anthropic` | Messages wire 编码、流式聚合、Prompt Cache、thinking 编码、错误映射、prefix hash 与 adapter | 只经 transport 发起 I/O；opaque replay 不进入公共 DTO、日志或异常 |
| `fun.fengwk.kkstudio.harness.provider.gemini` | GenerateContent wire 编码、流式聚合、隐式 Prompt Cache 与 adapter | 只经 transport 发起 I/O；固定声明 AUTOMATIC 缓存能力 |
| `fun.fengwk.kkstudio.harness.provider.openai.chat` | Chat Completions wire 编码、流式聚合、动态 Prompt Cache、tool call 与 adapter | 只经 transport 发起 I/O；按 configJson 动态解析 Prompt Cache 与 thinking 格式 |
| `fun.fengwk.kkstudio.harness.provider.openai.responses` | Responses wire 编码、strict schema 归一化、流式聚合、动态 Prompt Cache 与 adapter | 只经 transport 发起 I/O；按 configJson 动态解析 PromptCacheMode |

## 源码与测试

- 协议实现：[`transport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/) 与四个协议包 [`anthropic`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/)、[`gemini`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/)、[`openai.chat`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/)、[`openai.responses`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/)，各自含一套 `*ProviderAdapter`、`*RequestEncoder`、`*StreamAccumulator`、`*StreamBridge`、`*ErrorMapper`、`*PrefixHasher`、`*Endpoints` 与 `*Configuration`。
- 共享契约：[`provider`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/) 根包的 `ProviderErrorHelper` 与 `RequestBodySizeGuard`；测试 fixtures 放在 [`test/resources` 目录](../../harness/provider/src/test/resources/fun/fengwk/kkstudio/harness/provider/) 下。

按维护任务分组的测试入口（每组先给目录，再给代表文件）：

- 传输与字节级解析：[`transport` 测试目录](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/)；[`IncrementalSseParserTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParserTest.java) 锁定换行/BOM/上限规则，[`JdkHttpSseTransportTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransportTest.java) 锁定启动门、Watchdog 与取消仲裁，[`HeaderSanitizerTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/HeaderSanitizerTest.java) 锁定标头脱敏。
- 上游错误透传：根包 [`ProviderErrorHelperTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderErrorHelperTest.java) 与各协议目录下同名的 `*ErrorMapper` 测试锁定原始正文透传、`[TRUNCATED]` 标记与分类。
- 工具结果媒体矩阵契约：根包 [`ProviderAdapterMediaCapabilitiesTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderAdapterMediaCapabilitiesTest.java) 锁定四个协议的用户/工具结果声明矩阵，同包 [`ProviderToolResultMediaMatrixWireTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderToolResultMediaMatrixWireTest.java) 用本地离线 `HttpServer` 逐格验证「声明 ↔ 真实 HTTP 请求体 ↔ 不支持模态以 `INVALID_REQUEST` 失败且不发起请求」；各协议目录内的参数化用例锁定各自的 wire 形态与拒绝理由，全部离线，不访问真实 Provider。
- 协议回归：四个协议各自的测试目录（[`anthropic`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/anthropic/)、[`gemini`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/gemini/)、[`openai.chat`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/chat/)、[`openai.responses`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/)）按 wire 形状、推理编码、流式聚合、终态证据与桥接取消分文件组织；改动某个协议时从对应目录进入，先跑该目录再跑全模块。Responses 的原生回放回归单独由 [`OpenAiResponsesDurableReplayTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesDurableReplayTest.java) 与 [`OpenAiResponsesEmptyReasoningReplayRepairTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesEmptyReasoningReplayRepairTest.java) 守住加密内容合并与空占位符回退。
- 上游对齐清单：每个协议目录下的 `UpstreamTestManifestTest` 校验同目录的 `upstream-test-manifest.json`（如 [`transport` 清单](../../harness/provider/src/test/resources/fun/fengwk/kkstudio/harness/provider/transport/upstream-test-manifest.json)）。清单拒绝虚构对等：`PORTED` 项必须经反射验证目标测试真实存在且带 `@Test` / `@ParameterizedTest`，`OUT_OF_SCOPE` 项必须逐条说明架构不匹配原因，需要真实凭据的项独立标记为未执行；新增或迁移上游对等测试时同步更新对应清单。

覆盖率契约：本模块 POM 绑定 JaCoCo `check` 到 `verify`，对四个协议的 Encoder / Accumulator / Bridge / Adapter / Hasher / Mapper 与传输核心要求行覆盖率不低于 0.90，被检查类清单以 [`harness/provider/pom.xml`](../../harness/provider/pom.xml) 为准；运行命令与门禁含义见 [开发与测试](../operations/development-and-testing.md)。依赖方向与 POM 守卫见 [`ProviderModuleArchitectureTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Infra](harness-infra.md)、[Platform](platform.md)、[Harness Environment](harness-environment.md)。
