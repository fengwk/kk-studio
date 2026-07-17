package fun.fengwk.kkstudio.harness.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.agent.extension.BeforeProviderRequestInterceptor;
import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 将一个 Provider 流聚合为完整 Assistant 消息的默认 Turn Engine。 */
public final class DefaultAgentTurnEngine implements AgentTurnEngine {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ModelProvider provider;
  private final ProviderRequestInterceptorChain providerRequestInterceptors;

  public DefaultAgentTurnEngine(ModelProvider provider) {
    this(provider, List.of());
  }

  public DefaultAgentTurnEngine(
      ModelProvider provider, List<BeforeProviderRequestInterceptor> providerRequestInterceptors) {
    this(provider, new ProviderRequestInterceptorChain(providerRequestInterceptors));
  }

  public DefaultAgentTurnEngine(
      ModelProvider provider, ProviderRequestInterceptorChain providerRequestInterceptors) {
    this.provider = Objects.requireNonNull(provider, "provider");
    this.providerRequestInterceptors =
        Objects.requireNonNull(providerRequestInterceptors, "providerRequestInterceptors");
  }

  @Override
  public AgentTurnHandle execute(AgentTurnRequest request, AgentTurnEventHandler handler) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(handler, "handler");
    TurnState state = new TurnState(handler, descriptorsByName(request.tools()));
    handler.onStarted();
    try {
      ProviderRequest baseRequest =
          new ProviderRequest(
              request.model(),
              request.variant(),
              request.messages(),
              toProviderTools(request.tools()),
              ProviderCacheControl.none());
      ProviderRequest finalRequest = providerRequestInterceptors.intercept(baseRequest);
      state.bindFinalRequest(finalRequest);
      ProviderStream stream = provider.stream(finalRequest, state);
      state.bind(stream);
    } catch (ProviderException error) {
      state.fail(error);
    } catch (RuntimeException error) {
      state.fail(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "provider stream failed", error));
    }
    return state;
  }

  private static Map<String, ToolDescriptor> descriptorsByName(List<ToolDescriptor> descriptors) {
    Map<String, ToolDescriptor> result = new HashMap<>();
    for (ToolDescriptor descriptor : descriptors) {
      ToolDescriptor previous = result.putIfAbsent(descriptor.name(), descriptor);
      if (previous != null) {
        throw new IllegalArgumentException("tool names must be unique: " + descriptor.name());
      }
    }
    return Map.copyOf(result);
  }

  private static List<ProviderToolDefinition> toProviderTools(List<ToolDescriptor> descriptors) {
    List<ProviderToolDefinition> result = new ArrayList<>();
    for (ToolDescriptor descriptor : descriptors) {
      result.add(
          new ProviderToolDefinition(
              descriptor.name(), descriptor.description(), toJson(descriptor.inputSchema())));
    }
    return List.copyOf(result);
  }

  private static String toJson(ToolParamsSchema schema) {
    try {
      return OBJECT_MAPPER.writeValueAsString(
          objectSchema(
              schema.description(),
              schema.properties(),
              schema.required(),
              schema.additionalProperties()));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot serialize tool input schema", error);
    }
  }

  private static Map<String, Object> objectSchema(
      String description,
      Map<String, ToolSchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "object");
    if (description != null && !description.isBlank()) {
      result.put("description", description);
    }
    Map<String, Object> propertySchemas = new LinkedHashMap<>();
    for (Map.Entry<String, ToolSchemaElement> entry : properties.entrySet()) {
      propertySchemas.put(entry.getKey(), schemaElement(entry.getValue()));
    }
    result.put("properties", propertySchemas);
    result.put("required", List.copyOf(required));
    result.put("additionalProperties", additionalProperties);
    return result;
  }

  private static Map<String, Object> schemaElement(ToolSchemaElement element) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (element instanceof ToolStringSchema schema) {
      result.put("type", "string");
      description(result, schema.description());
    } else if (element instanceof ToolIntegerSchema schema) {
      result.put("type", "integer");
      description(result, schema.description());
    } else if (element instanceof ToolNumberSchema schema) {
      result.put("type", "number");
      description(result, schema.description());
    } else if (element instanceof ToolBooleanSchema schema) {
      result.put("type", "boolean");
      description(result, schema.description());
    } else if (element instanceof ToolEnumSchema schema) {
      result.put("type", "string");
      result.put("enum", schema.values());
      description(result, schema.description());
    } else if (element instanceof ToolArraySchema schema) {
      result.put("type", "array");
      result.put("items", schemaElement(schema.items()));
      description(result, schema.description());
    } else if (element instanceof ToolObjectSchema schema) {
      result.putAll(
          objectSchema(
              schema.description(),
              schema.properties(),
              schema.required(),
              schema.additionalProperties()));
    } else {
      throw new IllegalArgumentException("unsupported tool schema element: " + element.getClass());
    }
    return result;
  }

  private static void description(Map<String, Object> schema, String description) {
    if (description != null && !description.isBlank()) {
      schema.put("description", description);
    }
  }

  private static final class TurnState implements AgentTurnHandle, ProviderStreamHandler {

    private final AgentTurnEventHandler handler;
    private final Map<String, ToolDescriptor> descriptors;
    private final AtomicReference<ProviderStream> stream = new AtomicReference<>();
    private final AtomicReference<ProviderRequest> finalRequest = new AtomicReference<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<Integer, PartialToolCall> partialToolCalls = new HashMap<>();

    private TurnState(AgentTurnEventHandler handler, Map<String, ToolDescriptor> descriptors) {
      this.handler = handler;
      this.descriptors = descriptors;
    }

    void bindFinalRequest(ProviderRequest request) {
      Objects.requireNonNull(request, "request");
      if (!finalRequest.compareAndSet(null, request)) {
        throw new IllegalStateException("final request is already bound");
      }
    }

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream callbackStream) {
      if (terminal.get()) {
        return;
      }
      bind(callbackStream);
      try {
        // Aggregate and deliver deltas under the same monitor as terminal transition so a late
        // onDelta cannot race past onCompleted/onFailed once the turn has finished.
        synchronized (this) {
          if (terminal.get()) {
            return;
          }
          aggregate(event);
          handler.onDelta(event);
        }
      } catch (RuntimeException error) {
        fail(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid provider stream event", error));
      }
    }

    @Override
    public void onComplete(ProviderResponse response, ProviderStream callbackStream) {
      bind(callbackStream);
      synchronized (this) {
        if (!terminal.compareAndSet(false, true)) {
          return;
        }
        if (cancelled.get()) {
          handler.onFailed(
              new ProviderException(ProviderErrorKind.CANCELLED, "assistant turn cancelled"));
          return;
        }
        ProviderRequest boundRequest = finalRequest.get();
        if (boundRequest == null) {
          handler.onFailed(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "final provider request was never bound"));
          return;
        }
        try {
          validateStopReason(response);
          if (response.stopReason() == ProviderStopReason.CANCELLED) {
            handler.onFailed(
                new ProviderException(ProviderErrorKind.CANCELLED, "assistant turn cancelled"));
            return;
          }
          emitFinalGaps(response);
          List<ToolCall> calls = validateToolCalls(response);
          handler.onCompleted(
              new AgentTurnResult(
                  new AgentAssistantMessage(response.text(), response.thinking(), calls),
                  response,
                  boundRequest));
        } catch (RuntimeException error) {
          handler.onFailed(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid provider response", error));
        }
      }
    }

    @Override
    public void onError(ProviderException error, ProviderStream callbackStream) {
      bind(callbackStream);
      fail(error);
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      ProviderStream current = stream.get();
      if (current != null) {
        current.cancel();
      }
      synchronized (this) {
        if (terminal.compareAndSet(false, true)) {
          handler.onFailed(
              new ProviderException(ProviderErrorKind.CANCELLED, "assistant turn cancelled"));
        }
      }
    }

    @Override
    public boolean isCancelled() {
      ProviderStream current = stream.get();
      return cancelled.get() || (current != null && current.isCancelled());
    }

    @Override
    public boolean isDone() {
      return terminal.get();
    }

    private void bind(ProviderStream callbackStream) {
      if (callbackStream == null) {
        return;
      }
      stream.compareAndSet(null, callbackStream);
      if (cancelled.get()) {
        callbackStream.cancel();
      }
    }

    private void fail(ProviderException error) {
      synchronized (this) {
        if (terminal.compareAndSet(false, true)) {
          handler.onFailed(Objects.requireNonNull(error, "error"));
        }
      }
    }

    private void aggregate(ProviderStreamEvent event) {
      Objects.requireNonNull(event, "event");
      if (event instanceof ProviderStreamEvent.TextDelta delta) {
        text.append(delta.text());
      } else if (event instanceof ProviderStreamEvent.ThinkingDelta delta) {
        thinking.append(delta.text());
      } else if (event instanceof ProviderStreamEvent.ToolCallDelta delta) {
        partialToolCalls
            .computeIfAbsent(delta.index(), ignored -> new PartialToolCall())
            .append(delta);
      }
    }

    /** 最终快照只能补全已发送的前缀；事件协议没有 reset，因此发现任何分歧立即失败， 不能向已消费 partial 的 handler 静默交付另一份语义结果。 */
    private void emitFinalGaps(ProviderResponse response) {
      emitTextGap(text, response.text(), true);
      emitTextGap(thinking, response.thinking(), false);
      if (partialToolCalls.keySet().stream()
          .anyMatch(index -> index >= response.toolCalls().size())) {
        throw new IllegalArgumentException("final response omits a streamed tool call");
      }
      for (int index = 0; index < response.toolCalls().size(); index++) {
        ProviderToolCall complete = response.toolCalls().get(index);
        PartialToolCall partial =
            partialToolCalls.computeIfAbsent(index, ignored -> new PartialToolCall());
        ProviderStreamEvent.ToolCallDelta gap = partial.gap(index, complete);
        if (gap != null) {
          handler.onDelta(gap);
        }
      }
    }

    private void emitTextGap(StringBuilder received, String complete, boolean isText) {
      String partial = received.toString();
      if (!complete.startsWith(partial)) {
        throw new IllegalArgumentException(
            "final response conflicts with streamed " + (isText ? "text" : "thinking"));
      }
      if (complete.length() > received.length()) {
        String gap = complete.substring(received.length());
        received.append(gap);
        handler.onDelta(
            isText
                ? new ProviderStreamEvent.TextDelta(gap)
                : new ProviderStreamEvent.ThinkingDelta(gap));
      }
    }

    private List<ToolCall> validateToolCalls(ProviderResponse response) {
      Set<String> ids = new HashSet<>();
      List<ToolCall> calls = new ArrayList<>();
      for (ProviderToolCall providerCall : response.toolCalls()) {
        ToolDescriptor descriptor = descriptors.get(providerCall.name());
        if (descriptor == null) {
          throw new IllegalArgumentException(
              "tool call references undeclared tool: " + providerCall.name());
        }
        if (!ids.add(providerCall.id())) {
          throw new IllegalArgumentException("tool call ids must be unique: " + providerCall.id());
        }
        ToolArgumentsValidator.requireJsonObject(providerCall.argumentsJson());
        ToolCall call =
            new ToolCall(providerCall.id(), providerCall.name(), providerCall.argumentsJson());
        call.validateFor(descriptor);
        calls.add(call);
      }
      return List.copyOf(calls);
    }

    private void validateStopReason(ProviderResponse response) {
      boolean hasToolCalls = !response.toolCalls().isEmpty();
      if (response.stopReason() == ProviderStopReason.TOOL_CALLS) {
        if (!hasToolCalls) {
          throw new IllegalArgumentException("tool-call response requires tool calls");
        }
        return;
      }
      if (hasToolCalls) {
        throw new IllegalArgumentException(
            "only TOOL_CALLS stop reason may return executable tool calls");
      }
    }
  }

  private static final class PartialToolCall {

    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    private void append(ProviderStreamEvent.ToolCallDelta delta) {
      append(id, delta.id());
      append(name, delta.name());
      append(arguments, delta.argumentsJson());
    }

    private ProviderStreamEvent.ToolCallDelta gap(int index, ProviderToolCall complete) {
      String idGap = gap(id, complete.id());
      String nameGap = gap(name, complete.name());
      String argumentsGap = gap(arguments, complete.argumentsJson());
      if (idGap == null && nameGap == null && argumentsGap == null) {
        return null;
      }
      return new ProviderStreamEvent.ToolCallDelta(index, idGap, nameGap, argumentsGap);
    }

    private static void append(StringBuilder target, String value) {
      if (value != null) {
        target.append(value);
      }
    }

    private static String gap(StringBuilder received, String complete) {
      String value = received.toString();
      if (!complete.startsWith(value)) {
        throw new IllegalArgumentException("final tool call conflicts with streamed data");
      }
      if (complete.length() == value.length()) {
        return null;
      }
      String gap = complete.substring(value.length());
      received.append(gap);
      return gap;
    }
  }
}
