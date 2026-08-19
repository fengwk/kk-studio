package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ContextPressureDetector;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ContextPressureFacts;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** LangChain4j SDK 留在 model adapter 内的标准 Provider 流桥接。 */
abstract class LangChainModelProvider implements ModelProvider {

  private final ProviderType providerType;

  /** HTTP status 数字只在独立 token 上匹配，避免把 token 计数（如 {@code 1401}）误判为 auth/billing status。 */
  private static final Pattern HTTP_STATUS_401 = Pattern.compile("\\b401\\b");

  private static final Pattern HTTP_STATUS_402 = Pattern.compile("\\b402\\b");
  private static final Pattern HTTP_STATUS_403 = Pattern.compile("\\b403\\b");

  /**
   * 绑定 Adapter 自身对应的 {@link ProviderType}，由 {@link ProviderUsageNormalizer} 决定七类语义与 raw usage JSON
   * 提取策略；不允许在 metadata 上重新猜类型。
   */
  protected LangChainModelProvider(ProviderType providerType) {
    this.providerType = Objects.requireNonNull(providerType, "providerType");
  }

  @Override
  public final ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    BridgeStream stream = new BridgeStream();
    ToolCallNormalizer toolCallNormalizer = new ToolCallNormalizer();
    ThinkTagSplitter thinkTagSplitter = extractsThinkTags(request) ? new ThinkTagSplitter() : null;
    try {
      try {
        validateRequest(request);
      } catch (ProviderException validationFailure) {
        if (stream.terminal.compareAndSet(false, true)) {
          handler.onError(validationFailure, stream);
        }
        return stream;
      } catch (IllegalArgumentException validationFailure) {
        if (stream.terminal.compareAndSet(false, true)) {
          handler.onError(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST,
                  validationFailure.getMessage(),
                  validationFailure),
              stream);
        }
        return stream;
      }
      chatModel(request)
          .chat(
              ChatRequest.builder()
                  .messages(messages(request.messages()))
                  .parameters(parameters(request))
                  .build(),
              new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(
                    PartialResponse response, PartialResponseContext context) {
                  stream.bind(context.streamingHandle());
                  if (response != null && response.text() != null) {
                    emitText(handler, stream, thinkTagSplitter, response.text());
                  }
                }

                @Override
                public void onPartialThinking(
                    PartialThinking response, PartialThinkingContext context) {
                  stream.bind(context.streamingHandle());
                  if (response != null && response.text() != null) {
                    handler.onEvent(new ProviderStreamEvent.ThinkingDelta(response.text()), stream);
                  }
                }

                @Override
                public void onPartialToolCall(
                    PartialToolCall call, PartialToolCallContext context) {
                  stream.bind(context.streamingHandle());
                  if (call != null && hasValue(call.id(), call.name(), call.partialArguments())) {
                    ProviderStreamEvent.ToolCallDelta delta =
                        toolCallNormalizer.partial(
                            call.index(), call.id(), call.name(), call.partialArguments());
                    if (delta != null) {
                      handler.onEvent(delta, stream);
                    }
                  }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                  if (!stream.terminal.compareAndSet(false, true)) {
                    return;
                  }
                  try {
                    flushThinkTags(handler, stream, thinkTagSplitter);
                    handler.onComplete(
                        toResponse(request, response, thinkTagSplitter, toolCallNormalizer),
                        stream);
                  } catch (ProviderException providerFailure) {
                    // 分类过的 Provider 失败（例如不可映射的 finish reason -> INVALID_RESPONSE）原样保留 kind。
                    handler.onError(providerFailure, stream);
                  } catch (RuntimeException error) {
                    // terminal response 归一化（toResponse / tool call 归一化 / usage/cost）失败是 invalid
                    // provider terminal shape，不是调用方请求问题：INVALID_RESPONSE 供 Runtime 自动重试。
                    handler.onError(
                        new ProviderException(
                            ProviderErrorKind.INVALID_RESPONSE, userFacingMessage(error), error),
                        stream);
                  }
                }

                @Override
                public void onError(Throwable error) {
                  if (stream.terminal.compareAndSet(false, true)) {
                    // Message 是写入 assistant_error / assistant_failed 的持久化用户可见文本。
                    // 保留完整 cause chain 细节（而非通用常量），同时附上原始 Throwable 供诊断。
                    handler.onError(
                        new ProviderException(
                            classify(providerType, error, stream), userFacingMessage(error), error),
                        stream);
                  }
                }
              });
    } catch (RuntimeException error) {
      if (stream.terminal.compareAndSet(false, true)) {
        handler.onError(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, userFacingMessage(error), error),
            stream);
      }
    }
    return stream;
  }

  protected abstract StreamingChatModel chatModel(ProviderRequest request);

  /**
   * Provider 类型与模型描述符的运行时绑定检查；不匹配时抛 {@link ProviderException} ({@link
   * ProviderErrorKind#INVALID_REQUEST})，由 {@link #stream(ProviderRequest, ProviderStreamHandler)}
   * 转换为调用方可见的错误事件。
   */
  protected abstract void validateRequest(ProviderRequest request);

  /**
   * 是否把 assistant content 中的纯文本 {@code <think>} 块拆分为 thinking delta / response.thinking。
   * 当已知模型会在文本内发出标签时（例如启用了 reasoning 的 OpenAI 兼容模型），可按请求覆盖。
   */
  protected boolean extractsThinkTags(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    return false;
  }

  /**
   * 该 adapter 的 Provider 是否会把携带可执行 tool call 的完成回合上报为 OTHER/未知 finish reason。 公共映射不根据 {@code
   * hasToolCalls} 覆盖 generation stop reason；只有确实存在该协议事实的 adapter 覆盖此钩子（例如 MiniMax 兼容端点 对带 tool call
   * 的回合上报 OTHER），该特例只在其 adapter 内生效。
   */
  protected boolean reportsToolCallsWithOtherFinishReason(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    return false;
  }

  private static ChatRequestParameters parameters(ProviderRequest request) {
    ModelVariant variant = request.variant();
    DefaultChatRequestParameters.Builder<?> builder =
        DefaultChatRequestParameters.builder().modelName(request.model().modelName());
    // Model 未声明 tools 时绝不注入 tool specs，即使 Agent 配了 tools。
    if (request.model().tools()) {
      List<ToolSpecification> toolSpecs = tools(request.tools());
      if (!toolSpecs.isEmpty()) {
        builder.toolSpecifications(toolSpecs);
      }
    }
    // null 字段不传，走厂商默认；空 stopSequences 同样不传。
    if (variant.maxOutputTokens() != null) {
      builder.maxOutputTokens(variant.maxOutputTokens());
    }
    if (variant.temperature() != null) {
      builder.temperature(variant.temperature());
    }
    if (variant.topP() != null) {
      builder.topP(variant.topP());
    }
    if (variant.topK() != null) {
      builder.topK(variant.topK());
    }
    if (variant.frequencyPenalty() != null) {
      builder.frequencyPenalty(variant.frequencyPenalty());
    }
    if (variant.presencePenalty() != null) {
      builder.presencePenalty(variant.presencePenalty());
    }
    if (!variant.stopSequences().isEmpty()) {
      builder.stopSequences(variant.stopSequences());
    }
    return builder.build();
  }

  private static List<ChatMessage> messages(List<ProviderMessage> messages) {
    List<ChatMessage> result = new ArrayList<>();
    for (ProviderMessage message : messages) {
      if (message.role() == ProviderMessageRole.SYSTEM) {
        result.add(SystemMessage.from(text(message)));
      } else if (message.role() == ProviderMessageRole.USER) {
        result.add(UserMessage.from(contents(message.contents())));
      } else if (message.role() == ProviderMessageRole.ASSISTANT) {
        result.add(assistant(message));
      } else if (message.role() == ProviderMessageRole.TOOL) {
        result.add(toolResult(message));
      }
    }
    return result;
  }

  private static AiMessage assistant(ProviderMessage message) {
    AiMessage.Builder builder = AiMessage.builder().text(text(message));
    List<ToolExecutionRequest> calls = new ArrayList<>();
    for (Object content : message.contents()) {
      if (content instanceof ProviderThinkingBlock thinking) {
        builder.thinking(thinking.thinking());
      } else if (content instanceof ProviderToolCallBlock toolCall) {
        calls.add(
            ToolExecutionRequest.builder()
                .id(toolCall.toolCall().id())
                .name(toolCall.toolCall().name())
                .arguments(toolCall.toolCall().argumentsJson())
                .build());
      }
    }
    return builder.toolExecutionRequests(calls).build();
  }

  private static ToolExecutionResultMessage toolResult(ProviderMessage message) {
    ProviderToolResultBlock result = (ProviderToolResultBlock) message.contents().get(0);
    ToolExecutionResultMessage.Builder builder =
        ToolExecutionResultMessage.builder()
            .id(result.toolCallId())
            .toolName(result.toolName())
            .isError(result.error());
    List<Content> contents = contents(result.contents());
    if (contents.size() == 1 && contents.get(0) instanceof TextContent textContent) {
      builder.text(textContent.text());
    } else {
      builder.contents(contents);
    }
    return builder.build();
  }

  private static String text(ProviderMessage message) {
    return text(message.contents());
  }

  private static String text(List<?> contents) {
    StringBuilder result = new StringBuilder();
    for (Object content : contents) {
      if (content instanceof ProviderTextBlock block) {
        result.append(block.text());
      }
    }
    return result.toString();
  }

  private static List<Content> contents(List<ProviderContentBlock> blocks) {
    List<Content> result = new ArrayList<>();
    for (ProviderContentBlock block : blocks) {
      if (block instanceof ProviderTextBlock text) {
        result.add(TextContent.from(text.text()));
      } else if (block instanceof ProviderImageBlock image) {
        // source 是 URI 字符串（含合法 data: URI）。LangChain4j 的 from(base64, mimeType)
        // 会把 https://... 误包成 data:image/...;base64,https://...。
        result.add(ImageContent.from(image.source()));
      } else if (block instanceof ProviderAudioBlock audio) {
        result.add(AudioContent.from(audio.source()));
      } else if (block instanceof ProviderVideoBlock video) {
        result.add(VideoContent.from(video.source()));
      } else if (block instanceof ProviderJsonBlock json) {
        result.add(TextContent.from(json.json()));
      } else {
        throw new IllegalArgumentException(
            "unsupported Provider content block: " + block.getClass());
      }
    }
    return result;
  }

  private static List<ToolSpecification> tools(List<ProviderToolDefinition> definitions) {
    List<ToolSpecification> result = new ArrayList<>();
    for (ProviderToolDefinition definition : definitions) {
      result.add(
          ToolSpecification.builder()
              .name(definition.name())
              .description(definition.description())
              .parameters(objectSchema(definition.inputSchemaJson()))
              .build());
    }
    return result;
  }

  private static JsonObjectSchema objectSchema(String schemaJson) {
    try {
      JsonNode schema = new ObjectMapper().readTree(schemaJson);
      if (!schema.isObject() || !"object".equals(schema.path("type").asText())) {
        throw new IllegalArgumentException("tool schema must be a JSON object schema");
      }
      JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
      if (schema.hasNonNull("description")) {
        builder.description(schema.get("description").asText());
      }
      Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
      schema
          .path("properties")
          .fields()
          .forEachRemaining(
              entry -> properties.put(entry.getKey(), schemaElement(entry.getValue())));
      if (!properties.isEmpty()) {
        builder.addProperties(properties);
      }
      List<String> required = new ArrayList<>();
      schema.path("required").forEach(value -> required.add(value.asText()));
      if (!required.isEmpty()) {
        builder.required(required);
      }
      if (schema.has("additionalProperties")) {
        builder.additionalProperties(schema.get("additionalProperties").asBoolean());
      }
      return builder.build();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("tool schema must be valid JSON", error);
    }
  }

  private static JsonSchemaElement schemaElement(JsonNode schema) {
    String description = schema.path("description").asText(null);
    return switch (schema.path("type").asText()) {
      case "string" -> schema.has("enum")
          ? JsonEnumSchema.builder()
              .description(description)
              .enumValues(strings(schema.path("enum")))
              .build()
          : JsonStringSchema.builder().description(description).build();
      case "integer" -> JsonIntegerSchema.builder().description(description).build();
      case "number" -> JsonNumberSchema.builder().description(description).build();
      case "boolean" -> JsonBooleanSchema.builder().description(description).build();
      case "array" -> JsonArraySchema.builder()
          .description(description)
          .items(schemaElement(schema.path("items")))
          .build();
      case "object" -> objectSchema(schema.toString());
      default -> throw new IllegalArgumentException("unsupported tool schema type");
    };
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }

  private static void emitText(
      ProviderStreamHandler handler,
      ProviderStream stream,
      ThinkTagSplitter splitter,
      String text) {
    if (splitter == null) {
      handler.onEvent(new ProviderStreamEvent.TextDelta(text), stream);
      return;
    }
    ThinkTagSplitter.Split split = splitter.append(text);
    emitSplit(handler, stream, split);
  }

  private static void flushThinkTags(
      ProviderStreamHandler handler, ProviderStream stream, ThinkTagSplitter splitter) {
    if (splitter != null) {
      emitSplit(handler, stream, splitter.finish());
    }
  }

  private static void emitSplit(
      ProviderStreamHandler handler, ProviderStream stream, ThinkTagSplitter.Split split) {
    if (!split.text().isEmpty()) {
      handler.onEvent(new ProviderStreamEvent.TextDelta(split.text()), stream);
    }
    if (!split.thinking().isEmpty()) {
      handler.onEvent(new ProviderStreamEvent.ThinkingDelta(split.thinking()), stream);
    }
  }

  private ProviderResponse toResponse(
      ProviderRequest request,
      ChatResponse response,
      ThinkTagSplitter splitter,
      ToolCallNormalizer toolCallNormalizer) {
    List<ProviderToolCall> calls = new ArrayList<>();
    if (response != null && response.aiMessage() != null) {
      List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
      for (int index = 0; index < requests.size(); index++) {
        ToolExecutionRequest call = requests.get(index);
        ProviderToolCall normalized =
            toolCallNormalizer.complete(index, call.id(), call.name(), call.arguments());
        if (normalized != null) {
          calls.add(normalized);
        }
      }
    }
    ChatResponseMetadata metadata = response == null ? null : response.metadata();
    ProviderUsageNormalizer.NormalizedUsage normalized =
        ProviderUsageNormalizer.normalize(providerType, metadata);
    ModelUsage modelUsage = normalized.modelUsage();
    FinishReason finishReason = metadata == null ? null : metadata.finishReason();
    GenerationStopReason stopReason =
        toStopReason(
            finishReason, reportsToolCallsWithOtherFinishReason(request) && !calls.isEmpty());
    if (stopReason == null) {
      // null / unknown finish reason 不是成功 ProviderResponse：显式映射为 INVALID_RESPONSE 供 Runtime 重试。
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "cannot map provider finish reason to a canonical generation stop reason: "
              + finishReason);
    }
    // LangChain AiMessage.thinking()/text() 即使流式输出过 reasoning 也可能返回 null。
    // 在任何 isEmpty() 检查前先归一化，避免 completion 因 NPE 落入 "invalid provider
    // response"。
    String text =
        response == null || response.aiMessage() == null || response.aiMessage().text() == null
            ? ""
            : response.aiMessage().text();
    String thinking =
        response == null || response.aiMessage() == null || response.aiMessage().thinking() == null
            ? ""
            : response.aiMessage().thinking();
    if (splitter != null) {
      ThinkTagSplitter.Split split = ThinkTagSplitter.parse(text);
      text = split.text();
      if (thinking.isEmpty()) {
        thinking = split.thinking();
      }
    }
    if (stopReason == GenerationStopReason.FILTERED && !calls.isEmpty()) {
      // canonical 约束：FILTERED 响应不得携带 tool calls（绝不创建 ToolInvocation）；过滤完成时丢弃残余调用。
      calls = List.of();
    }
    return new ProviderResponse(
        text,
        thinking,
        calls,
        stopReason,
        modelUsage,
        ModelCost.calculate(request.model().pricing(), modelUsage),
        normalized.requestId(),
        normalized.serviceTier(),
        normalized.rawUsageJson());
  }

  /**
   * 显式 finish reason 映射：generation stop reason 与 tool call 存在性正交，绝不根据 calls 覆盖结果。STOP 与
   * TOOL_EXECUTION（OpenAI Chat tool_calls / Responses completed+calls / Anthropic tool_use / Gemini
   * function）都归一为 COMPLETE；LENGTH（OpenAI length / Responses incomplete / Anthropic max_tokens /
   * Gemini MAX_TOKENS）为 LENGTH；CONTENT_FILTER（OpenAI content_filter / Gemini safety 等）为 FILTERED。
   * null / OTHER 返回 null，表示不可映射（INVALID_RESPONSE）；{@code otherFinishMeansToolCalls} 仅为明确存在该 协议事实的
   * adapter（MiniMax）保留 OTHER+calls -&gt; COMPLETE 特例。
   */
  static GenerationStopReason toStopReason(
      FinishReason finishReason, boolean otherFinishMeansToolCalls) {
    if (finishReason == null) {
      return null;
    }
    return switch (finishReason) {
      case STOP, TOOL_EXECUTION -> GenerationStopReason.COMPLETE;
      case LENGTH -> GenerationStopReason.LENGTH;
      case CONTENT_FILTER -> GenerationStopReason.FILTERED;
      case OTHER -> otherFinishMeansToolCalls ? GenerationStopReason.COMPLETE : null;
    };
  }

  /**
   * Provider 错误分类。优先级：CANCELLED → authentication / billing（typed status 或明确 message 标记）→ rate limit
   * 保持 TRANSIENT → context pressure 经 {@link ProviderErrorFactsExtractor} + {@link
   * ContextPressureDetector} 判定（不再散落 {@code 413} / {@code context length} / {@code too large} 子串）→
   * 普通 invalid / transient。401/402/403 即使消息同时提到 context 字样也不得返回 OVERFLOW；HTTP 429 与普通 400 invalid
   * 同样不误判；绝不新增 LENGTH kind。
   */
  static ProviderErrorKind classify(
      ProviderType providerType, Throwable error, ProviderStream stream) {
    if (stream.isCancelled()) {
      return ProviderErrorKind.CANCELLED;
    }
    ContextPressureFacts facts = ProviderErrorFactsExtractor.extract(providerType, error);
    String message =
        facts.errorMessage() == null ? "" : facts.errorMessage().toLowerCase(Locale.ROOT);
    if (authenticationFailure(facts, message)) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (billingFailure(facts, message)) {
      return ProviderErrorKind.BILLING;
    }
    if (ContextPressureDetector.isRateLimited(facts)) {
      return ProviderErrorKind.TRANSIENT;
    }
    if (ContextPressureDetector.detect(facts)) {
      return ProviderErrorKind.OVERFLOW;
    }
    // 确定性的 client/route 错误不得进入自动重试。nginx 的 404 HTML 与 405 方法不匹配对当前请求
    // 配置是永久性的。
    if (message.contains("404")
        || message.contains("405")
        || message.contains("not found")
        || message.contains("method not allowed")
        || message.contains("400")
        || message.contains("invalid")) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    // 5xx / 网络失败有意作为 TRANSIENT 落入。
    return ProviderErrorKind.TRANSIENT;
  }

  private static boolean authenticationFailure(ContextPressureFacts facts, String message) {
    Integer status = facts.httpStatus();
    if (status != null && (status == 401 || status == 403)) {
      return true;
    }
    return HTTP_STATUS_401.matcher(message).find()
        || HTTP_STATUS_403.matcher(message).find()
        || message.contains("auth");
  }

  private static boolean billingFailure(ContextPressureFacts facts, String message) {
    Integer status = facts.httpStatus();
    if (status != null && status == 402) {
      return true;
    }
    return HTTP_STATUS_402.matcher(message).find()
        || message.contains("billing")
        || message.contains("quota");
  }

  /**
   * 构建持久化、用户可见的 Provider 失败文本。
   *
   * <p>遍历 cause chain 并拼接去重后的非空白消息。绝不包含堆栈。类凭据子串会被脱敏， 保证文本可安全用于 Entry/SSE/UI。
   */
  static String userFacingMessage(Throwable error) {
    if (error == null) {
      return "provider request failed";
    }
    LinkedHashSet<String> parts = new LinkedHashSet<>();
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
      String raw = current.getMessage();
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      parts.add(redactSecrets(trimmed));
    }
    if (parts.isEmpty()) {
      return error.getClass().getSimpleName();
    }
    String joined = String.join(" | ", parts);
    return joined.length() <= 2000 ? joined : joined.substring(0, 2000);
  }

  private static String redactSecrets(String text) {
    // 保留完整 provider 正文，但绝不回显 bearer token / 类 key 值。
    // 顺序很重要：先处理 Bearer/sk-，宽泛的 key=value 规则才不会先抹掉它们。
    return text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._\\-]+", "Bearer ***")
        .replaceAll("(?i)\\bsk-[A-Za-z0-9]{8,}\\b", "sk-***")
        .replaceAll(
            "(?i)((?:api[_-]?key|credential|secret|token)\\s*[:=]\\s*)([^\\s,;\"']+)", "$1***");
  }

  private static boolean hasValue(String id, String name, String arguments) {
    return id != null || name != null || arguments != null;
  }

  /** 保持 SDK partial/complete 回调的 tool id 与名称一致，并拒绝不可执行的语义残缺调用。 */
  private static final class ToolCallNormalizer {

    private final Map<Integer, String> ids = new LinkedHashMap<>();
    private final Map<Integer, String> names = new LinkedHashMap<>();

    private ProviderStreamEvent.ToolCallDelta partial(
        int index, String id, String name, String arguments) {
      String canonicalName = normalizeName(index, name, arguments);
      if (canonicalName == null) {
        return null;
      }
      String canonicalId = id == null || id.isBlank() ? null : normalizeId(index, id);
      return new ProviderStreamEvent.ToolCallDelta(
          index, canonicalId, name == null || name.isBlank() ? null : canonicalName, arguments);
    }

    private ProviderToolCall complete(int index, String id, String name, String arguments) {
      String canonicalName = normalizeName(index, name, arguments);
      if (canonicalName == null) {
        return null;
      }
      return new ProviderToolCall(
          normalizeId(index, id), canonicalName, arguments == null ? "{}" : arguments);
    }

    private String normalizeName(int index, String name, String arguments) {
      if (name == null || name.isBlank()) {
        String existing = names.get(index);
        if (existing != null) {
          return existing;
        }
        if (arguments == null || arguments.isBlank() || "{}".equals(arguments)) {
          return null;
        }
        throw new IllegalArgumentException("provider tool call is missing a name");
      }
      return names.computeIfAbsent(index, ignored -> name);
    }

    private String normalizeId(int index, String id) {
      String existing = ids.get(index);
      if (existing != null) {
        return existing;
      }
      String canonical =
          id == null || id.isBlank()
              ? "compat_" + index + "_" + UUID.randomUUID().toString().replace("-", "")
              : id;
      ids.put(index, canonical);
      return canonical;
    }
  }

  private static final class BridgeStream implements ProviderStream {

    private final AtomicReference<StreamingHandle> delegate = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();

    private void bind(StreamingHandle handle) {
      if (handle != null && delegate.compareAndSet(null, handle) && cancelled.get()) {
        handle.cancel();
      }
    }

    @Override
    public void cancel() {
      cancelled.set(true);
      StreamingHandle handle = delegate.get();
      if (handle != null) {
        handle.cancel();
      }
    }

    @Override
    public boolean isCancelled() {
      StreamingHandle handle = delegate.get();
      return cancelled.get() || (handle != null && handle.isCancelled());
    }
  }
}
