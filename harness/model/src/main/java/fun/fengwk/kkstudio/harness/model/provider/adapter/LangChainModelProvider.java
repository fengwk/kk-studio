package fun.fengwk.kkstudio.harness.model.provider.adapter;

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
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
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
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** LangChain4j SDK 留在 model adapter 内的标准 Provider 流桥接。 */
abstract class LangChainModelProvider implements ModelProvider {

  private static final ObjectMapper USAGE_OBJECT_MAPPER = new ObjectMapper();

  @Override
  public final ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    BridgeStream stream = new BridgeStream();
    ToolCallNormalizer toolCallNormalizer = new ToolCallNormalizer();
    ThinkTagSplitter thinkTagSplitter = extractsThinkTags() ? new ThinkTagSplitter() : null;
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
                  } catch (RuntimeException error) {
                    handler.onError(
                        new ProviderException(
                            ProviderErrorKind.INVALID_REQUEST, "invalid SDK response", error),
                        stream);
                  }
                }

                @Override
                public void onError(Throwable error) {
                  if (stream.terminal.compareAndSet(false, true)) {
                    handler.onError(
                        new ProviderException(
                            classify(error, stream), "provider request failed", error),
                        stream);
                  }
                }
              });
    } catch (RuntimeException error) {
      if (stream.terminal.compareAndSet(false, true)) {
        handler.onError(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "cannot start provider request", error),
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

  /** OpenAI 兼容端点仅在 MiniMax 等已知端点启用正文 think-tag 回退。 */
  protected boolean extractsThinkTags() {
    return false;
  }

  private static ChatRequestParameters parameters(ProviderRequest request) {
    return DefaultChatRequestParameters.builder()
        .modelName(request.model().modelId())
        .maxOutputTokens(request.variant().maxOutputTokens())
        .temperature(request.variant().temperature())
        .topP(request.variant().topP())
        .topK(request.variant().topK())
        .frequencyPenalty(request.variant().frequencyPenalty())
        .presencePenalty(request.variant().presencePenalty())
        .stopSequences(request.variant().stopSequences())
        .toolSpecifications(tools(request.tools()))
        .build();
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
        result.add(ImageContent.from(image.source(), image.mediaType()));
      } else if (block instanceof ProviderAudioBlock audio) {
        result.add(AudioContent.from(audio.source(), audio.mediaType()));
      } else if (block instanceof ProviderVideoBlock video) {
        result.add(VideoContent.from(video.source(), video.mediaType()));
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
    TokenUsage usage =
        response == null || response.metadata() == null ? null : response.metadata().tokenUsage();
    ModelUsage modelUsage = toUsage(usage);
    FinishReason finishReason =
        response == null || response.metadata() == null ? null : response.metadata().finishReason();
    ProviderStopReason stopReason = toStopReason(finishReason, !calls.isEmpty());
    String text =
        response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
    String thinking =
        response == null || response.aiMessage() == null ? "" : response.aiMessage().thinking();
    if (splitter != null) {
      ThinkTagSplitter.Split split = ThinkTagSplitter.parse(text);
      text = split.text();
      if (thinking.isEmpty()) {
        thinking = split.thinking();
      }
    }
    return new ProviderResponse(
        text,
        thinking,
        calls,
        stopReason,
        modelUsage,
        ModelCost.calculate(request.model().pricing(), modelUsage),
        null,
        null,
        rawUsageJson(usage));
  }

  private static String rawUsageJson(TokenUsage usage) {
    if (usage == null) {
      return "{}";
    }
    return USAGE_OBJECT_MAPPER.valueToTree(usage).toString();
  }

  private static ModelUsage toUsage(TokenUsage usage) {
    long input = usage == null ? 0 : valueOrZero(usage.inputTokenCount());
    long output = usage == null ? 0 : valueOrZero(usage.outputTokenCount());
    long providerTotal =
        usage == null || usage.totalTokenCount() == null
            ? Math.addExact(input, output)
            : usage.totalTokenCount();
    if (usage instanceof AnthropicTokenUsage anthropicUsage) {
      long cacheRead = valueOrZero(anthropicUsage.cacheReadInputTokens());
      long cacheWrite = valueOrZero(anthropicUsage.cacheCreationInputTokens());
      return new ModelUsage(input, output, cacheRead, cacheWrite, 0, 0, providerTotal);
    }
    if (usage instanceof OpenAiTokenUsage openAiUsage) {
      long cacheRead =
          openAiUsage.inputTokensDetails() == null
              ? 0
              : valueOrZero(openAiUsage.inputTokensDetails().cachedTokens());
      long reasoning =
          openAiUsage.outputTokensDetails() == null
              ? 0
              : valueOrZero(openAiUsage.outputTokensDetails().reasoningTokens());
      return new ModelUsage(
          subtractCategory(input, cacheRead, "cached input tokens"),
          subtractCategory(output, reasoning, "reasoning output tokens"),
          cacheRead,
          0,
          0,
          reasoning,
          providerTotal);
    }
    return new ModelUsage(input, output, 0, 0, 0, 0, providerTotal);
  }

  private static long valueOrZero(Integer value) {
    return value == null ? 0 : value.longValue();
  }

  private static long subtractCategory(long total, long category, String categoryName) {
    if (category > total) {
      throw new IllegalArgumentException(categoryName + " must not exceed its reported total");
    }
    return total - category;
  }

  static ProviderStopReason toStopReason(FinishReason finishReason, boolean hasToolCalls) {
    if (finishReason == null) {
      return hasToolCalls ? ProviderStopReason.TOOL_CALLS : ProviderStopReason.COMPLETED;
    }
    return switch (finishReason) {
      case STOP -> ProviderStopReason.COMPLETED;
      case LENGTH -> ProviderStopReason.LENGTH;
      case TOOL_EXECUTION -> ProviderStopReason.TOOL_CALLS;
      case CONTENT_FILTER -> ProviderStopReason.CONTENT_FILTER;
      case OTHER -> ProviderStopReason.OTHER;
    };
  }

  private static ProviderErrorKind classify(Throwable error, ProviderStream stream) {
    if (stream.isCancelled()) {
      return ProviderErrorKind.CANCELLED;
    }
    String message = String.valueOf(error.getMessage()).toLowerCase();
    if (message.contains("401") || message.contains("403") || message.contains("auth")) {
      return ProviderErrorKind.AUTHENTICATION;
    }
    if (message.contains("402") || message.contains("billing") || message.contains("quota")) {
      return ProviderErrorKind.BILLING;
    }
    if (message.contains("413")
        || message.contains("context length")
        || message.contains("too large")) {
      return ProviderErrorKind.OVERFLOW;
    }
    if (message.contains("400") || message.contains("invalid")) {
      return ProviderErrorKind.INVALID_REQUEST;
    }
    return ProviderErrorKind.TRANSIENT;
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
