# Harness Provider

`harness-provider` 用 JDK 21 `HttpClient` 实现四种模型协议，以有界增量解析器读取 SSE，将正常结束、安全过滤、截断与连接故障收敛为 Runtime 能消费的中立结果。它还负责原生推理回放，并把运行时冻结的 `ProviderCacheControl` 映射为各协议 prompt cache wire 字段；错误只做分类，保留上游诊断正文。

生产依赖为 [`harness-runtime`](harness-runtime.md) 的协议类型与 Jackson；凭据、HttpClient、worker 与 Watchdog 调度器由 [Platform](platform.md) 注入。依赖声明见 [`pom.xml`](../../harness/provider/pom.xml)，[`ProviderModuleArchitectureTest`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java) 校验 import 与 POM。Platform 使用 HTTP/1.1、Redirect.NEVER 和受管虚拟线程 worker。

## 传输层

[`JdkHttpSseTransport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransport.java) 是模块唯一的 I/O 入口，构造时强制 `followRedirects() == Redirect.NEVER` 并注入独立的 worker `ExecutorService` 与 `ScheduledExecutorService`。每次调用由 [`HttpSseStreamExecution`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseStreamExecution.java) 承载，它同时是返回给调用方的 [`ProviderStream`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStream.java) 句柄：

- **启动门**：worker 与 watchdog 两个任务都排期成功后才开闸，因此不会出现「watchdog 已跑、worker 还没开始」的中间态。
- **拒绝 inline 执行**：worker 若发现自己就运行在发起 `stream` 的调用线程上（direct / caller-runs 执行器），只置位 `inlineExecutionDetected` 并取消准入后返回；随后由发起线程在同一同步路径上抛 `EXECUTOR_REJECTED`，防止流在提交线程上自等而死锁。
- **任意阶段拒绝**：worker 提交或 watchdog 调度被拒时立即 `abortAdmission()` 并同步抛 `EXECUTOR_REJECTED`，此时任务尚未触碰 `HttpClient`。
- **Deadline**：基于 `System.nanoTime()` 与饱和换算的单调时钟，只排期当前最近的总超时或闲置超时。读取字节只刷新活动时间，不逐字节重排 timer；到期复核后，未超时则按剩余期限重新排期，确已超时才派发 `TIMEOUT`。timer 不执行阻塞关闭或用户回调，真正到期或重排被拒时才启动短生命周期清理虚拟线程，不为每个流保留巡检线程。
- **取消**：`cancel()` 幂等，CAS 推进到取消态后关闭底层 `InputStream` 并取消 watchdog 与 worker future，调用方立刻返回。
- **回调仲裁**：状态机 `PENDING / RUNNING / CANCELLED / COMPLETED / FAILED` 配合回调锁保证 Terminal-Once——`onComplete` 与 `onFailure` 互斥且各至多一次，取消后不再有任何回调；回调内部重入取消安全；外部回调抛出未受检异常时被捕获并转为 `CALLBACK_FAILED` 终态（`onOpen` / `onEvent` 抛出会经 `onFailure` 交付，`onComplete` / `onFailure` 自身抛出则被安全忽略，绝不产生第二终态）。

[`HttpSseLimits`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 以字节而非字符计四项上界，[`DEFAULT`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseLimits.java) 为 `maxLineBytes = 1 MiB`、`maxEventBytes = 1 MiB`、`maxSuccessBodyBytes = 128 MiB`、`maxErrorBodyBytes = 64 KiB`；超限立即中止并关闭底层连接。

[`IncrementalSseParser`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParser.java) 逐字节解析，规则都在字节层：

- 首个事件前静默剔除 UTF-8 BOM（`0xEF 0xBB 0xBF`），但这三个字节仍计入成功流累计字节；不足三字节即 EOF 时按普通字符处理。
- 严格拦截 NUL 字节与畸形多字节序列（`CharsetDecoder` 配 `REPORT`），一律 `INVALID_RESPONSE`。
- 换行同时支持 CRLF、LF 与孤立 CR；`data:` 后仅紧邻的一个空格被剔除，多余前导空格与尾随空白完整保留；多行 `data:` 以 `\n` 合并。
- 每个空行边界无条件重置事件缓冲与事件字节计数（无论该行是否有 data）；流 EOF 时 `flush()` 交付未以空行闭合的尾随事件。

[`HttpOpenMetadata`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpOpenMetadata.java) 与 `TransportException` 的安全标头共用 [`HeaderSanitizer`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HeaderSanitizer.java) 的单一清洗规则：只放行固定白名单 `content-type`、`content-length`、`retry-after`、`x-request-id`、`request-id`，以及 `x-ratelimit-limit`、`x-ratelimit-remaining`、`x-ratelimit-reset`、`x-ratelimit-reset-requests`、`x-ratelimit-reset-tokens`，不是按前缀放行任意限流标头。未知标头一律丢弃；白名单内命中 `token`、`key`、`auth`、`cookie`、`secret`、`credential` 关键词的标头值整体替换为 `[REDACTED]`，其余值剔除 CR/LF 并修剪周边空白，输出按大小写不敏感排序的不可变 Map。

[`TransportErrorKind`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportErrorKind.java) 是封闭的七元集合 `IO`、`TIMEOUT`、`INVALID_RESPONSE`、`HTTP_STATUS`、`EXECUTOR_REJECTED`、`CALLBACK_FAILED`、`CANCELLED`。[`TransportException`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/TransportException.java) 是安全信道：`getMessage()` / `toString()` 只含 kind、状态码等类名级信息（换行被清洗），绝不输出 URI、token、请求体或错误正文；cause 被替换为只保留类名的安全异常；经过有界截断的诊断正文只能通过 `errorBodyBytes()` 防御性副本读取，`isErrorBodyTruncated()` 明确告知是否被截断。[`ServerSentEvent`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/ServerSentEvent.java) 的 `toString()` 也只输出 `eventLength` / `dataLength`，避免日常调试日志打印上游正文。

[`HttpSseCallback`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/HttpSseCallback.java) 是唯一的回调面：`onOpen`、`onEvent`、`onComplete`、`onFailure` 四个生命周期方法，终态至多一次。

## 上游错误原样透传

四个协议的 [`AnthropicErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicErrorMapper.java)、[`GeminiErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiErrorMapper.java)、[`OpenAiChatErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatErrorMapper.java)、[`OpenAiResponsesErrorMapper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesErrorMapper.java) 共用 [`ProviderErrorHelper`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderErrorHelper.java) 的格式化规则，只做错误**分类**，不做正文过滤：

- HTTP 错误消息是 `HTTP <status>\n<原始 UTF-8 响应正文>`，保留空白、非 JSON 正文、厂商扩展字段、请求 ID 与上游给出的全部错误详情，不做字段白名单过滤。
- 正文受 `maxErrorBodyBytes` 限制；一旦传输层截断，消息末尾明确追加 ` [TRUNCATED]`。正文为空时才回退到 `HTTP <status>: <fallback>` 或纯 fallback 文案。
- SSE 协议错误保留完整解析后的 JSON envelope，用 `JsonNode.toString()` 序列化（不保证原始 JSON 排版或 wire 字节一致）。
- 消息刻意不含请求标头、请求 URI、配置凭据与底层异常 cause；错误分类、重试与取消语义不因透传而改变。
- 只有 `CANCELLED` 是静默传输错误，不映射为上游错误；`EXECUTOR_REJECTED` 与 `CALLBACK_FAILED` 都是必须交付的终态，不会在这里被吞掉。

同一消息沿 Runtime 落入 `ModelInvocationError`，持久化为 `harness_model_invocation.error`，再经产品 DTO 原样送达客户端；对话错误卡片与调试详情按纯文本展示，不把上游 HTML 当页面执行。需要注意：如果上游正文自身回显了敏感内容，它同样会进入错误记录与客户端，访问、保存与分享这些诊断时应按敏感数据处理。

## 请求、回放与媒体

[`ProviderAdapter`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderAdapter.java) 是协议工厂：`providerType()`、`mediaCapabilities()`、`create(ProviderDescriptor)` 与无网络预览 `encodeRequestBody`（默认实现抛 `UnsupportedOperationException`，四个协议都覆写为复用正式发送路径上的同一 encoder 与 configuration，返回逐字节一致的最终请求体）。它接收运行时定义的通用对象（[`ProviderRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderRequest.java)、`ProviderMessage`、`ProviderToolDefinition`、`ProviderCacheControl`），并通过 [`ModelCallTimeoutPolicy`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ModelCallTimeoutPolicy.java)（默认 30 分钟总时长 / 120 秒闲置）接收超时策略；上游 SDK 类型完全不出现在这个边界上。会话角色 [`ProviderMessageRole`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderMessageRole.java) 仅有 `USER`、`ASSISTANT`、`TOOL`，系统指令单独由 `ProviderRequest.systemInstruction` 承载，会话消息中绝不出现系统角色。各协议把它编码到各自的顶层位置：Anthropic `system`、Gemini `systemInstruction`、OpenAI Chat 唯一的前导 wire `system` 消息、OpenAI Responses `instructions`。流式规范化增量以 sealed [`ProviderStreamEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamEvent.java) 交付（`TextDelta`、`ThinkingDelta`、`ToolCallDelta`），终态是一条 [`ProviderResponse`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderResponse.java) 加可选的原生回放状态。

每个 model variant 可携带 [`ProviderProtocolOptions`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/ProviderProtocolOptions.java)：严格 JSON object，拒绝重复键和尾随 token，canonical JSON 最多 **64 KiB UTF-8**；空值按 `{}` 处理，错误不回显选项内容。后端解析使用 `BigInteger` / `BigDecimal` 避免浮点精度损失，[`ProviderProtocolOptionsJson`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/ProviderProtocolOptionsJson.java) 为每次编码提供独立副本。选项只合并到该协议的**请求体**，不是修改凭据、endpoint、标头或传输行为的万能开关；字段所有权、嵌套对象与原生 tools 的合并规则以对应编码器为准，冲突以 `INVALID_REQUEST` 失败。

Catalog API 与持久化 variant 使用 **`protocolOptionsJson` 字符串**，例如 `{"id":"default","protocolOptionsJson":"{\"temperature\":0.7}"}`。编辑器只用 `JSON.parse` 检查语法与对象根节点，不把解析后的数字重新序列化；提交和加载保留内部 JSON 文本，仅去除外层空白。后端严格校验重复键、类型、尾随内容与字节上限，运行时才将文本解码为原生对象，因此大整数与高精度小数不会经过浏览器浮点数往返。

[`ProviderReplayState`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayState.java) = `(format, affinity, payload)`，四种格式 [`ProviderReplayFormat`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayFormat.java) 为 `anthropic_messages` / `openai_responses` / `openai_chat` / `gemini_content`；[`ProviderReplayAffinity`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderReplayAffinity.java) = `(providerType, providerName, connectionGenerationId, modelId)`，其中 `modelId` 是发往上游的真实 wire 模型标识，逻辑模型改名不构成亲和性差异。它从一次成功终态中冻结，随助手 Entry 持久保存，供后续请求重建原生上下文；`payload` 按 JSON 字段保留可回放的上游结构（`reasoning_content`、`encrypted_content`、thinking `signature` / redacted thinking、`thoughtSignature`），不承诺保留 wire 字节排版，也不进入公共 DTO、日志或异常。它不同于下文仅在 attempt 内交付的原生 SSE 通道。

各协议的冻结条件如下：

| 协议 | 冻结条件 | 工具调用诊断 |
| --- | --- | --- |
| Anthropic | `COMPLETE`、`LENGTH` 或 `CONTINUE`，且无 tool call 诊断 | LENGTH 截断时把未闭合工具参数固化为 diagnostic，不伪造 `{}`；CONTINUE 不允许 tool call |
| Gemini | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Chat | 仅 `COMPLETE` | 截断时不冻结回放 |
| OpenAI Responses | `COMPLETE` 或 `LENGTH`，且无诊断、非 FILTERED、非空占位符 | 同 Anthropic 语义 |

原位回放只由 `format`、`affinity`（是否等于当前 `ProviderDescriptor.affinity(requestedModel)`）与 payload 结构/durable 一致性决定，不存在前缀哈希或字节级比对。payload 结构非法、与 durable 事实矛盾或违反协议不变量时 fail closed 为 `INVALID_REQUEST`；`format` / `affinity` 失配或 durable 语义已被改写（压缩、编辑）时回退语义编码，能证明可重建的原生附加字段按 durable 语义投影，签名、密文、未知 item 等只有原生回放才能保真的事实随回退被丢弃，不会假装保真。回放 payload 不被改写（仅移除值为 JSON null 的已知可空字段）。因此缓存标记、JSON 字段顺序与 system 前缀都不参与回放判定。

OpenAI Responses 的 durable thinking 与 replay 一致性校验逐个 reasoning item 使用相同规则：终态非空白 `summary[].summary_text` 优先；没有可用摘要时读取 `content[].reasoning_text`，两者绝不拼接，再按 item 顺序聚合。有摘要时，即使 content 文本不同也仍以摘要为准，原生 content 无损保留。仅当终态存在 reasoning 且全部没有可比较纯文本时，保留已有流式思考；空占位符（无密文、无可用摘要、无 content 或其他附加事实）不冻结 native replay，重建时退回语义编码。content 的 array/object/type/text 形状严格校验，真实文本矛盾即使 affinity 失配也以 `INVALID_REQUEST` 拒绝；encrypted-only replay 没有可比较纯文本而 durable 有 thinking 时仍严格拒绝。

流式收到的 `reasoning.encrypted_content` 在终态 output 省略该字段时会被合并保留，密文绝不当作 semantic thinking 外泄。同 affinity 原生回放保留 content、id 与密文等协议事实；切换 provider、wire 模型或连接 generation 后，校验成功才回退语义编码，保留 durable 思考和正文，不携带源 id 或密文。

OpenAI Chat 的 `reasoning_content` 与 `reasoning_details` 随 replay payload 保留：`reasoning_content` 与 durable thinking 逐字校验一致，`reasoning_details` 只做类型校验。回放只受 `affinity` 门控——同一 provider、连接 generation 与 wire 模型内原样回传（[DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode) 在请求带 `tools` 时要求保留历轮 `reasoning_content`），跨 provider、连接 generation 或模型转移时回退语义编码并丢弃思考；`annotations` 与非法形态的 `audio` 以 `INVALID_REQUEST` 拒绝。

工具绑定或 Environment 变化时，无 replay 的历史工具调用可投影为普通上下文。携带 replay 的助手消息若需降级，Runtime 在启动 Gateway 前以 INVALID_REQUEST 拒绝，保留原生签名与密文。恢复使用原工具绑定/环境，或在新上下文中显式提供摘要。模型切换与前缀编辑由编码器按 affinity 校验；压缩是摘要重建的有损边界。

媒体能力由 [`ProviderMediaCapabilities`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderMediaCapabilities.java) 按用户内容与工具结果分别声明；Platform 把它与模型 `inputModalities` 取交集，把 durable Blob 引用转换成 attempt-only 的 Base64 data URI。编码器不访问 Blob 存储，也不生成私网地址或预签名 URL，未声明的 adapter 默认为 `NONE`。

| 协议 | 用户内容 | 工具结果 | Base64 wire 形态 |
| --- | --- | --- | --- |
| OpenAI Chat | 图片、音频、PDF | 无媒体 | 图片 `image_url.url`；音频 `input_audio.data/format`；PDF `file.file_data` |
| OpenAI Responses | 图片、PDF | 图片、PDF | 用户内容 `input_image.image_url`、`input_file.file_data`/`file_url`；工具结果 `function_call_output.output` 数组的 `input_text` / `input_image` / `input_file` 项 |
| Anthropic | 图片、PDF | 图片、PDF | `source.type=base64` 与 `source.media_type/data`（图片也接受 http(s) `url`；文档只接受 base64） |
| Gemini | 图片、音频、视频、PDF | 图片、PDF | 用户内容 `inlineData.mimeType/data`（或 `fileData`）；工具结果只允许内联在 `functionResponse.parts[].inlineData` |

工具结果媒体有两个容易写反的位置约束：

- **Gemini**：用户内容与工具结果的媒体来源都只接受严格 Base64 data URI（`data:<mime>;base64,<payload>`）且 payload 可解码非空；无 base64 标记的 percent/普通 data、非法 Base64 与空载荷都在编码期以 `INVALID_REQUEST` 失败，绝不把无效载荷发往上游，http(s)/`gs` fileUri 仍按协议透传为 `fileData`。工具结果必须内联在 `functionResponse.parts[].inlineData`，绝不能作为外层 `Content.parts` 的同级 part——同级 part 会被解析为该 role 的独立输入，而不是这次函数调用的返回值。Gemini Developer API 的 v1beta schema 里 `FunctionResponseBlob` 只有 `mimeType` 与 `data`，没有 `displayName`，官方文档描述的 `response` 内 `{"$ref": "<displayName>"}` 引用形态只成立于 Vertex AI 的 `FunctionResponseBlob` / `FunctionResponseFileData`；本编码器因此只把媒体挂到 `parts` 上，不伪造 `displayName` 或 `$ref`。工具结果还额外要求 MIME 落在保守白名单 `image/jpeg`、`image/png`、`image/webp` 与 `application/pdf` 内，音频、视频、其他 MIME 与 http(s)/`gs` URL 来源一律以 `INVALID_REQUEST` 失败。
- **OpenAI Chat**：tool message 仅支持文本与 JSON 结果，按原顺序串联为字符串 content；任何媒体块都以 `INVALID_REQUEST` 失败。

能力声明只描述本编码器能表达的 schema，不承诺具体上游模型支持该模态，也不替代编码器自己的 MIME / 格式校验。Chat 用户消息的单个纯文本块保持字符串，多块或含媒体时使用数组；Responses 工具结果的单个文本或 JSON 保持字符串，含媒体时使用 `output` 数组。

请求体的最终 UTF-8 字节上限在序列化完成后统一收口：[`RequestBodySizeGuard`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/RequestBodySizeGuard.java) 的 `MAX_REQUEST_BODY_BYTES` 是 **192 MiB**，Chat、Responses 与 Gemini 都走它；Anthropic 保留更严格的 **32 MiB** 协议级上限。超限一律以 `INVALID_REQUEST` 明确失败，不静默截断、不降级、不改写请求；厂商仍可能有自己的更严格限制。

## 原生事件与流式聚合

[`ProviderProtocolEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderProtocolEvent.java) 以 `(eventType, data)` 向 [`ProviderStreamHandler.onProtocolEvent`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamHandler.java) 提供独立的 **attempt-only 原生 SSE 通道**：四个协议在处理已接收的 SSE 帧时先回调原生内容，再解析为规范化增量或协议错误。它与规范化回调共享取消/终态仲裁，但不进入 durable/realtime delta、产品 DTO 或日志；`toString()` 不打印 data。未知事件仍可从原生通道观察，**不代表**未知 delta 可以凭空拼成安全的终态 replay 或已规范化语义。原生通道与规范化回调共用同一套生命周期仲裁，实现是同处根包的 `ProviderStreamBridge`：`bind` 与 `cancel` 线性化、`complete` / `error` / `cancel` 严格 terminal-once、`cancel` 返回后不再回调 handler，原生帧与规范化增量走同一派发闸门，因此 native 回调先于其派生的 normalized 且各自至多一次。该桥接器只是模块内部的协议适配基础组件，不是对外扩展点，协议差异不写进它。

四个累积器根据协议终止证据冻结 response 与可选 replay，通过 [`ProviderStreamHandler`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderStreamHandler.java) 的 `onComplete(ProviderCompletion, ProviderStream)` 整体交付；失败走 onError，两者互斥且至多一次。Anthropic message_stop、Responses response.completed、Chat 的 [DONE]/首个有效 finish_reason、Gemini 非 UNSPECIFIED finishReason 建立终止边界；此后冲突或重复语义帧 fail closed，usage-only 与空尾帧按各协议规则处理。[`GenerationStopReason`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/GenerationStopReason.java) 为 COMPLETE/LENGTH/FILTERED/CONTINUE；CONTINUE 表示继续生成，FILTERED 与 CONTINUE 均拒绝工具调用意图。

- Anthropic [`AnthropicStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicStreamAccumulator.java)：按原生 block index 维护内容块状态机，支持交错的多工具调用 delta 与连续 `toolOrdinal`；usage 按累计快照更新，支持 `cache_creation` 的 5m / 1h 拆分；只有 `message_stop` 才算成功。`end_turn` / `stop_sequence` / `tool_use` 归一为 COMPLETE，`max_tokens` / `model_context_window_exceeded` 为 LENGTH，`refusal` 为 FILTERED，`pause_turn` / `compaction` 为 CONTINUE；文本 citations 等已知 delta 保留在原生 block，未知 block/delta 配对不能安全合成时显式失败。Messages 协议不提供原生 `total_tokens`，累计器把 `providerTotalTokens` 固定为 `0L` 而不是合成 input + output，分类明细仍保留在 `ModelUsage.categorizedTokens()` 中。
- OpenAI Chat [`OpenAiChatStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatStreamAccumulator.java)：只规范化 `choices[0]`，按 `delta.tool_calls[i].index` 累积片段；其他 choices 只在原生 SSE 通道可见。`stop` / `tool_calls` 归一为 COMPLETE，`length` 为 LENGTH，`content_filter` 为 FILTERED；usage 缺 `total_tokens` 时保持 `0L`。首个有效 `finish_reason` 与 `[DONE]` 都是单向闸门：同帧尾随 content/tool delta 先正常累积再封闭，此后只接受 usage-only 空 choices、无语义空 delta 尾帧与重复 `[DONE]`，再出现文本、refusal、reasoning、tool_calls、仅 native 字段增量或重复、变更的 `finish_reason` 都以 `INVALID_RESPONSE` 失败；非字符串 `finish_reason` 也直接拒绝而不是被 `asText` 吞掉。原生 assistant audio 冻结为可请求回放的 `audio.id`，audio 其他字段及 annotations 仅在原生事件中保留；旧版 `finish_reason=function_call` 不支持。
- OpenAI Responses [`OpenAiResponsesStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStreamAccumulator.java)：`response.completed` 为 COMPLETE，`response.incomplete` 按 `incomplete_details.reason` 区分 FILTERED 与其他 LENGTH；工具调用按 `response.output_item.*` 分配连续 `0..N-1` ordinal；已知 `message` / `reasoning` / `function_call` item 的 id/status/附加字段与输出文本注解等可保留在原生 replay，未知 item 按槽位保留而不冒充规范化工具调用；usage 缺失 `total_tokens` 时不输出该键且原生总数为 `0L`。
- Gemini [`GeminiStreamAccumulator`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiStreamAccumulator.java)：只规范化 `candidates[0]`（其他 candidates 只在原生 SSE 通道可见）。同 slot 的纯文本可证明为增量或累计快照时才合并；含 `thoughtSignature` 的 Part 保持原生边界，不能把签名迁移到拼接后的另一个 Part。`STOP` 为 COMPLETE，`MAX_TOKENS` 为 LENGTH，`SAFETY` / `RECITATION` / `PROHIBITED_CONTENT` / `SPII` / `BLOCKLIST` / `IMAGE_SAFETY` / `IMAGE_RECITATION` / `IMAGE_PROHIBITED_CONTENT` / `LANGUAGE` / `ESCALATION` 为 FILTERED；`MALFORMED_*`、`MISSING_THOUGHT_SIGNATURE`、`UNEXPECTED_TOOL_CALL`、`TOO_MANY_TOOL_CALLS`、`OTHER`、`IMAGE_OTHER`、`NO_IMAGE` 等异常结束原因直接判为 `INVALID_RESPONSE`。

## 四协议能力与边界

| 流式协议 | 原生请求选项与运行时保护 | 原生事件、终态与回放 |
| --- | --- | --- |
| Anthropic Messages | 合并非运行时字段及原生 tools；拒绝覆盖 `model/max_tokens/stream/messages/system/cache_control`，运行时推理开启时保护 `thinking` 与 `output_config.effort` | 独立 SSE 原生通道；text/thinking/function tool 增量，citations 等已知 block 原样保留；`pause_turn` / `compaction` 为 CONTINUE，可按原生状态续写 |
| OpenAI Chat Completions | 合并扩展选项，客户端工具只来自运行时绑定；拒绝覆盖模型、消息、预算、stream 及缓存所有权字段；`stream_options.include_usage` 由配置决定 | 独立 SSE 原生通道；只规范化 `choices[0]`，assistant 原生字段在可证明时回放，audio 只回放 id、annotations 只保留原生帧 |
| OpenAI Responses | 合并原生 tools/include/reasoning；运行时 `model/stream/store/instructions` 不可冲突，拒绝有状态 `previous_response_id/conversation` 与运行时缓存字段 | 独立 SSE 原生通道；已知 output item 的附加字段和未知 item 可原生保留，函数调用才进入规范化 tool 路径 |
| Gemini streamGenerateContent | 合并 `generationConfig` 与原生 tools；拒绝覆盖 `contents/systemInstruction/cachedContent`、`maxOutputTokens` 及运行时指定的 `thinkingConfig` | 独立 SSE 原生通道；只规范化 `candidates[0]`，签名 Part 边界保留，只有可证明安全的文本片段才合并 |

编码器在 HTTP 前检查执行边界，而不是等待模型生成无法处理的调用：

- 所有客户端函数工具只能从 Runtime 绑定注册，不能通过原生 `tools` 绕过执行器。
- Anthropic 原生 tools 开放带日期版本的 `web_search`、`web_fetch`、`code_execution` 家族；工具名必须非空且不与其他声明或运行时工具冲突。
- Responses 开放 `web_search`、`web_search_preview`、`file_search`、`code_interpreter`、`image_generation`；`mcp` 必须显式 `require_approval: "never"`。客户端 computer、shell、custom 等类型及未知类型拒绝；`background` 若出现必须为 `false`。
- Gemini 每个原生 tool 对象只允许一个受支持的 hosted capability：`googleSearch`、`googleSearchRetrieval`、`retrieval`、`codeExecution`、`urlContext`、`googleMaps`。
- Chat 原生 `tools` 只能为空数组，旧 `functions/function_call` 拒绝；Chat `n` 与 Gemini `candidateCount` 若声明，经 JSON 数值规范化后必须为整数 `1`，不允许请求多个候选。

以上是本地执行能力边界，不代替上游对工具版本、模型与参数组合的校验，也不是上游全部能力的规范化交付承诺。上游意外返回的其他 Chat choices / Gemini candidates 仅原生可见。遇到无法重建的原生事实、affinity 失配不能保真时失败关闭；未知 delta 不凭猜测生成 replay。原生 SSE 仅限当前 attempt，不能通过 durable/realtime API 当作通用能力消费。

## 协议差异

**Anthropic Messages**（[`AnthropicProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicProviderAdapter.java)）。端点由 [`AnthropicEndpoints.resolveMessagesUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicEndpoints.java) 幂等解析：已以 `/v1/messages` 或 `/messages` 结尾保持不变，以 `/v1` 结尾追加 `/messages`，其余 base path 追加 `/v1/messages`；严格拒绝 user-info、query 与 fragment，并保真保留 raw authority（含 IPv6 与端口）与已转义 raw path。请求同时发送 `x-api-key` 与 `Authorization: Bearer`，并始终带 `anthropic-version: 2023-06-01`。`anthropic-beta` 合并配置的 beta features 与运行时需要的标识并去重；仅在**实际启用 BUDGET 思考**（`reasoning=true`、effort 非空且模式为 BUDGET）时自动追加 `interleaved-thinking-2025-05-14`，其他模式不会自动追加，但显式配置的 beta 标识仍会发送。

[`AnthropicConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/AnthropicConfiguration.java) 解析 `anthropicThinkingMode`（`ADAPTIVE` / `BUDGET`，缺省 ADAPTIVE）和 `anthropicBetaFeatures`（去重、标识及头长度有界）：wire 模型标识直接取 `ModelDescriptor.modelId()`，不存在别名替换；JSON 语法与字段类型严格校验，未知配置字段忽略，异常绝不回显配置内容或凭据。推理编码上，`reasoningOff` 发送 `thinking: {"type": "disabled"}`；ADAPTIVE 发送 `thinking: {"type": "adaptive", "display": "summarized"}` 与 `output_config.effort`；BUDGET 发送 `thinking: {"type": "enabled", "budget_tokens": ..., "display": "summarized"}` 并把 `low` / `medium` / `high` 映射为 2048 / 8192 / 16384，`budget_tokens` 必须小于 `max_tokens`。运行时指定 effort 时原生 `thinking` / `output_config.effort` 不能冲突；`reasoningEffort` 为 null 时不由运行时声明推理字段，原生选项可按协议自身规则携带这些字段。Prompt Cache 由运行时 `ProviderCacheControl` 驱动：`NONE` 不发任何标记，`SHORT` 写 `cache_control: {"type":"ephemeral"}`，`LONG` 追加 `ttl: "1h"`；会话 `key` 不属于 Anthropic 原生选项，因此不下发。

Anthropic 每次最多放置 **4 个**断点：最后一个合格 tool、最后一个合格 system block，其余按最新优先分配给最近的会话请求端点。断点只添加到支持的非空 text、image、document、tool_use、tool_result 块，thinking、redacted thinking 与 compaction 等块保留但不新增标记。compaction 块回放时清空此前累积的消息与端点，使该 assistant 消息成为首条；标记只在原生校验完成后注入。

缓存字段由运行时独占：原生顶层 `cache_control` 与工具直属的 `protocolOptions.tools[].cache_control`（包括显式 `null`）都在编码前以 `INVALID_REQUEST` 拒绝，即使 retention 为 NONE 也不允许透传；工具 schema 内同名业务属性不受影响。

**OpenAI Chat Completions**（[`OpenAiChatProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatProviderAdapter.java)）。端点去尾斜杠后追加 `/chat/completions`。图片使用 image_url.url 的 data URI；音频只接收 Base64 wav/mp3。媒体准入由 adapter schema 能力与模型 inputModalities 共同决定。[`OpenAiChatConfiguration`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/OpenAiChatConfiguration.java) 支持 `openAiChatIncludeUsage`、`openAiChatRequireDone`、`promptCacheRetention`（`NONE` / `SHORT` / `LONG`，缺省 `NONE`）与 `openAiChatThinkingFormat`（`STANDARD` / `DEEPSEEK`）。推理模型的 DEEPSEEK 关闭态写 thinking disabled 并省略 reasoning_effort，开启态写 thinking enabled 与 reasoning_effort；STANDARD 关闭值为 reasoning_effort none。非推理模型省去运行时推理字段。

**OpenAI Responses**（[`OpenAiResponsesProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesProviderAdapter.java)）。[`OpenAiResponsesConfig`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesConfig.java) 解析 `promptCacheRetention`（缺省 `NONE`）。系统指令编码为顶层 `instructions` 字符串，会话输入项中绝不出现系统或开发者指令；有状态 `previous_response_id` / `conversation` 与无状态 replay 不兼容，明确拒绝。推理字段：运行时关闭时写 `reasoning.effort: "none"` 且不自动添加 `summary` 与 `include`；启用时写 `reasoning.effort`，缺失时补 `summary: "auto"` 与顶层 `include: ["reasoning.encrypted_content"]`，不覆盖兼容的原生附加选项。函数工具一律以 `strict: true` 发送，参数 schema 深拷贝后由 [`OpenAiResponsesStrictSchema`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesStrictSchema.java) 递归归一化：每个 object 节点（含 `items` 与嵌套 object）显式写出全量 `required` 与 `additionalProperties: false`；只有源 schema 确实把某属性排除在 `required` 之外、且它当前不允许 null 时才改写为 `anyOf: [原 schema, {"type": "null"}]`，本来就是必填或已允许 null 的属性保持原样；调用方共享的 `inputSchemaJson` 绝不被修改。`max_output_tokens` 的下限 `16` 只属于本 Provider——**低于 16 直接以 `INVALID_REQUEST` 拒绝，不静默提升也不改写**。提示缓存由运行时 `ProviderCacheControl` 驱动：`NONE` 不发缓存字段，`SHORT` 发送 `prompt_cache_key` 与 `prompt_cache_retention: "in_memory"`，`LONG` 发送 `24h`。

**Google Gemini**（[`GeminiProviderAdapter`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiProviderAdapter.java)）。[`GeminiEndpoints.resolveStreamUri`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/GeminiEndpoints.java) 保留 raw authority 与 raw base path，去除尾斜杠后按 RFC 3986 path-segment 语义编码 model name 并追加 `/models/{encodedModel}:streamGenerateContent?alt=sse`，绝不把 API Key 拼进 URL。角色映射只有 `user` 与 `model`，相邻同 role 内容合并为一个 content 节点；原生 signed Part 回放时仍保持签名及 Part 边界。Prompt Cache 固定为服务端隐式缓存：编码器不发任何 cache hint，`cacheControl.retention` 非 `NONE` 一律以 `INVALID_REQUEST` 拒绝，原生 `cachedContent` 也不可覆盖。

### OpenAI prompt cache 请求边界

Chat 与 Responses 都只由运行时 `ProviderCacheControl` 决定缓存字段：`NONE` 不发任何 cache 字段；非 `NONE` 时 `prompt_cache_key` 直接使用运行时给的会话 key，`prompt_cache_retention` 按 `SHORT` / `LONG` 映射为 `in_memory` / `24h`。原生 `protocolOptions` 上的 `prompt_cache_key`、`prompt_cache_retention`、`prompt_cache_options` 等运行时独占字段一律拒绝；编码器不再发送 `prompt_cache_options` 或 `prompt_cache_breakpoint`，也不改写历史内容结构。缓存有效不等于必然命中：上游最小 token 长度、TTL、路由与上下文变化仍影响实际命中率。

## 包架构

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `fun.fengwk.kkstudio.harness.provider` | 跨协议共享的流式生命周期桥接 `ProviderStreamBridge`、HTTP 错误格式化 `ProviderErrorHelper`、请求体上限守卫 `RequestBodySizeGuard` 与协议选项副本 `ProviderProtocolOptionsJson` | 不解析厂商错误分类，不修改原始响应正文；桥接器只承载 bind / cancel / terminal-once 生命周期，协议差异留在各协议包 |
| `fun.fengwk.kkstudio.harness.provider.transport` | `JdkHttpSseTransport`、`HttpSseStreamExecution`、`IncrementalSseParser`、`HttpSseLimits`、`HttpOpenMetadata`、`HeaderSanitizer`、`TransportErrorKind`、`TransportException`、`ServerSentEvent`、`HttpSseCallback` | 只依赖 `harness-runtime` 基础模型与 JDK；不暴露凭据，不保留未清洗的异常上下文 |
| `fun.fengwk.kkstudio.harness.provider.anthropic` | Messages wire 编码、流式聚合、Prompt Cache 断点、thinking 编码、错误映射与 adapter | 只经 transport 发起 I/O；opaque replay 不进入公共 DTO、日志或异常 |
| `fun.fengwk.kkstudio.harness.provider.gemini` | GenerateContent wire 编码、流式聚合、隐式 Prompt Cache 与 adapter | 只经 transport 发起 I/O；仅接受 `NONE` retention（服务端隐式缓存） |
| `fun.fengwk.kkstudio.harness.provider.openai.chat` | Chat Completions wire 编码、流式聚合、动态 Prompt Cache、tool call 与 adapter | 只经 transport 发起 I/O；按 configJson 动态解析 Prompt Cache 与 thinking 格式 |
| `fun.fengwk.kkstudio.harness.provider.openai.responses` | Responses wire 编码、strict schema 归一化、流式聚合、Prompt Cache 映射与 adapter | 只经 transport 发起 I/O；按 configJson 解析 `promptCacheRetention` |

## 源码与测试

- 协议实现：[`transport`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/transport/) 与四个协议包 [`anthropic`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/anthropic/)、[`gemini`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/gemini/)、[`openai.chat`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/chat/)、[`openai.responses`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/openai/responses/)，各自含一套 `*ProviderAdapter`、`*ModelProvider`、`*RequestEncoder`、`*StreamAccumulator`、`*ErrorMapper` 与 `*Endpoints`；配置类只在有协议选项可解析时存在（`AnthropicConfiguration`、`OpenAiChatConfiguration`、`OpenAiResponsesConfig`，Gemini 包没有配置类）。
- 共享契约：[`provider`](../../harness/provider/src/main/java/fun/fengwk/kkstudio/harness/provider/) 根包的 `ProviderStreamBridge`、`ProviderErrorHelper` 与 `RequestBodySizeGuard`；测试 fixtures 放在 [`test/resources` 目录](../../harness/provider/src/test/resources/fun/fengwk/kkstudio/harness/provider/) 下。

按维护任务分组的测试入口（每组先给目录，再给代表文件）：

- 传输与字节级解析：[`transport` 测试目录](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/)；[`IncrementalSseParserTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/IncrementalSseParserTest.java) 锁定换行/BOM/上限规则，[`JdkHttpSseTransportTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/JdkHttpSseTransportTest.java) 锁定启动门、Watchdog 与取消仲裁，[`HeaderSanitizerTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/transport/HeaderSanitizerTest.java) 锁定标头脱敏。
- 上游错误透传：根包 [`ProviderErrorHelperTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderErrorHelperTest.java) 与各协议目录下同名的 `*ErrorMapper` 测试锁定原始正文透传、`[TRUNCATED]` 标记与分类。
- 工具结果媒体矩阵契约：根包 [`ProviderAdapterMediaCapabilitiesTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderAdapterMediaCapabilitiesTest.java) 锁定四个协议的用户/工具结果声明矩阵，同包 [`ProviderToolResultMediaMatrixWireTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderToolResultMediaMatrixWireTest.java) 用本地离线 `HttpServer` 逐格验证「声明 ↔ 真实 HTTP 请求体 ↔ 不支持模态以 `INVALID_REQUEST` 失败且不发起请求」；各协议目录内的参数化用例锁定各自的 wire 形态与拒绝理由，全部离线，不访问真实 Provider。
- 协议测试：[`anthropic`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/anthropic/)、[`gemini`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/gemini/)、[`openai.chat`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/chat/)、[`openai.responses`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/) 分别验证 wire、推理、流式聚合与终止证据。共享生命周期由根包 ProviderStreamBridgeTest 验证。Responses 的 [`OpenAiResponsesDurableReplayTest`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesDurableReplayTest.java) 与 [`OpenAiResponsesEmptyReasoningReplayRepairTest`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/openai/responses/OpenAiResponsesEmptyReasoningReplayRepairTest.java) 验证密文合并和空占位符处理。
- 上游映射：各 UpstreamTestManifestTest 校验资源目录的 manifest（例如 [`transport`](../../harness/provider/src/test/resources/fun/fengwk/kkstudio/harness/provider/transport/upstream-test-manifest.json)）。PORTED 经反射校验目标类、方法与测试注解，并验证声明 fixture 的存在和格式；PASSED 是静态元数据，目标执行结果须运行测试确认。OUT_OF_SCOPE 给出范围原因，真实凭据项另记执行状态。反射映射、fixture 声明、离线测试与真实上游执行分别提供不同证据；维护规则见各协议资源 README。

覆盖率契约：本模块 POM 绑定 JaCoCo `check` 到 `verify`，对四个协议的 Encoder / Accumulator / Adapter / Hasher / Mapper、跨协议共享的 `ProviderStreamBridge` 与传输核心要求行覆盖率不低于 0.90，被检查类清单以 [`harness/provider/pom.xml`](../../harness/provider/pom.xml) 为准；运行命令与门禁含义见 [开发与测试](../operations/development-and-testing.md)。依赖方向与 POM 守卫见 [`ProviderModuleArchitectureTest.java`](../../harness/provider/src/test/java/fun/fengwk/kkstudio/harness/provider/ProviderModuleArchitectureTest.java)，它同时要求根包共享组件（`ProviderStreamBridge`、`ProviderErrorHelper`、`ProviderProtocolOptionsJson`、`RequestBodySizeGuard`）不携带被禁止的框架注解或接口。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Infra](harness-infra.md)、[Platform](platform.md)、[Harness Environment](harness-environment.md)。
