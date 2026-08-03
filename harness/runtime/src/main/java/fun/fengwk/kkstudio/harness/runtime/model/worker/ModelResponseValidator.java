package fun.fengwk.kkstudio.harness.runtime.model.worker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ToolCallVisibility;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates final Provider response semantics against the frozen ProviderRequest. */
final class ModelResponseValidator {

  private static final ObjectMapper OBJECT_MAPPER = strictObjectMapper();

  private ModelResponseValidator() {}

  private static ObjectMapper strictObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    return mapper;
  }

  static void validate(ProviderRequest request, ProviderResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    List<String> availableToolNames = ToolCallVisibility.availableToolNames(request);
    Set<String> declaredToolNames = declaredToolNames(availableToolNames);
    boolean hasToolCalls = !response.toolCalls().isEmpty();
    Set<String> toolCallIds = new HashSet<>();
    for (ProviderToolCall call : response.toolCalls()) {
      if (!declaredToolNames.contains(call.name())) {
        throw new IllegalArgumentException(
            ToolCallVisibility.unavailableMessage(call.name(), availableToolNames));
      }
      if (!toolCallIds.add(call.id())) {
        throw new IllegalArgumentException("tool call ids must be unique: " + call.id());
      }
      requireJsonObject(call.argumentsJson(), "tool call argumentsJson");
    }
    if (response.stopReason() == ProviderStopReason.TOOL_CALLS && !hasToolCalls) {
      throw new IllegalArgumentException("tool-call response requires tool calls");
    }
    if (response.stopReason() != ProviderStopReason.TOOL_CALLS && hasToolCalls) {
      throw new IllegalArgumentException(
          "only TOOL_CALLS stop reason may return executable tool calls");
    }
  }

  private static Set<String> declaredToolNames(Iterable<String> definitions) {
    Set<String> result = new HashSet<>();
    for (String name : definitions) {
      if (!result.add(name)) {
        throw new IllegalArgumentException("provider request contains duplicate tool: " + name);
      }
    }
    return result;
  }

  private static void requireJsonObject(String value, String name) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(value);
      if (!node.isObject()) {
        throw new IllegalArgumentException(name + " must contain a JSON object");
      }
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(name + " must contain JSON", exception);
    }
  }
}
