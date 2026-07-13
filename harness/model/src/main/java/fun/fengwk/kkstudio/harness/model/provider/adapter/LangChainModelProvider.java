package fun.fengwk.kkstudio.harness.model.provider.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
import dev.langchain4j.model.output.TokenUsage;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** LangChain4j SDK 留在 model adapter 内的标准 Provider 流桥接。 */
abstract class LangChainModelProvider implements ModelProvider {

  @Override
  public final ProviderStream stream(ProviderRequest request, ProviderStreamHandler handler) {
    BridgeStream stream = new BridgeStream();
    try {
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
                    handler.onEvent(new ProviderStreamEvent.TextDelta(response.text()), stream);
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
                    handler.onEvent(
                        new ProviderStreamEvent.ToolCallDelta(
                            call.index(), call.id(), call.name(), call.partialArguments()),
                        stream);
                  }
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                  if (!stream.terminal.compareAndSet(false, true)) {
                    return;
                  }
                  try {
                    handler.onComplete(toResponse(request, response), stream);
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
                            ProviderErrorKind.TRANSIENT, "provider request failed", error),
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

  private static ChatRequestParameters parameters(ProviderRequest request) {
    return DefaultChatRequestParameters.builder()
        .modelName(request.model().modelId())
        .maxOutputTokens(request.variant().maxOutputTokens())
        .temperature(request.variant().temperature())
        .topP(request.variant().topP())
        .topK(request.variant().topK())
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
        result.add(UserMessage.from(text(message)));
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
    return ToolExecutionResultMessage.builder()
        .id(result.toolCallId())
        .toolName("tool")
        .text(text(result.contents()))
        .isError(result.error())
        .build();
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

  private static ProviderResponse toResponse(ProviderRequest request, ChatResponse response) {
    List<ProviderToolCall> calls = new ArrayList<>();
    if (response != null && response.aiMessage() != null) {
      for (ToolExecutionRequest call : response.aiMessage().toolExecutionRequests()) {
        calls.add(new ProviderToolCall(call.id(), call.name(), call.arguments()));
      }
    }
    TokenUsage usage =
        response == null || response.metadata() == null ? null : response.metadata().tokenUsage();
    ModelUsage modelUsage =
        new ModelUsage(
            usage == null ? 0 : usage.inputTokenCount(),
            usage == null ? 0 : usage.outputTokenCount(),
            0,
            0,
            0);
    ProviderStopReason stopReason =
        calls.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new ProviderResponse(
        response == null || response.aiMessage() == null ? "" : response.aiMessage().text(),
        response == null || response.aiMessage() == null ? "" : response.aiMessage().thinking(),
        calls,
        stopReason,
        modelUsage,
        ModelCost.calculate(request.model().pricing(), modelUsage));
  }

  private static boolean hasValue(String id, String name, String arguments) {
    return id != null || name != null || arguments != null;
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
