package fun.fengwk.kkstudio.harness.runtime.processor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionFileSections;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ToolCallVisibility;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 校验最终 Provider response 相对冻结 {@link ModelRequestSpec} 的语义（工具可见性 / arguments JSON /
 * stopReason）；压缩调用（{@code request.compaction()} 非空）成功必须是 {@code COMPLETED}、零 tool call、非空摘要文本，并在
 * durable SUCCEEDED 前剥离 Runtime-owned file sections。失败走 INVALID_REQUEST terminal。
 */
final class ModelResponseValidator {

  private static final ObjectMapper OBJECT_MAPPER = strictObjectMapper();

  private ModelResponseValidator() {}

  private static ObjectMapper strictObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    return mapper;
  }

  static ProviderResponse validate(ModelRequestSpec request, ProviderResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    List<String> availableToolNames = availableToolNames(request);
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
    if (request.compaction() != null) {
      if (response.stopReason() != ProviderStopReason.COMPLETED) {
        throw new IllegalArgumentException(
            "compaction responses must be COMPLETED, got " + response.stopReason());
      }
      if (hasToolCalls) {
        throw new IllegalArgumentException("compaction responses must not contain tool calls");
      }
      String canonicalText = CompactionFileSections.stripReservedSections(response.text());
      if (canonicalText.isBlank()) {
        throw new IllegalArgumentException(
            "compaction responses must contain nonblank text outside reserved file sections");
      }
      if (!canonicalText.equals(response.text())) {
        return new ProviderResponse(
            canonicalText,
            response.thinking(),
            response.toolCalls(),
            response.stopReason(),
            response.usage(),
            response.cost(),
            response.requestId(),
            response.serviceTier(),
            response.rawUsageJson());
      }
    }
    return response;
  }

  private static List<String> availableToolNames(ModelRequestSpec request) {
    List<String> names = new ArrayList<>(request.toolBindings().size());
    for (ToolBinding binding : request.toolBindings()) {
      names.add(binding.descriptor().name());
    }
    return List.copyOf(names);
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
