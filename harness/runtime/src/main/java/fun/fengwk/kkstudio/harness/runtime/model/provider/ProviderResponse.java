package fun.fengwk.kkstudio.harness.runtime.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider 流完成时提供的完整响应快照。
 *
 * <p>{@code stopReason} 是规范化生成结束原因（{@link GenerationStopReason}），与 tool calls 正交：{@code COMPLETE}
 * 可以有或没有 calls，{@code LENGTH} 可以有或没有已观测 calls，{@code FILTERED} 的 calls 必须为空。{@code usage} 是
 * Provider 归一化后的实际 token 用量，{@code cost} 是基于本次生效价格计算的非空成本快照。 {@code requestId}、{@code serviceTier}
 * 由 Provider 报告，可为空。{@code rawUsageJson} 是 Provider usage 段的原始 JSON 序列化（必须是合法 JSON object 或
 * array，null 规范化为 {@code "{}"}），仅承载 usage 元数据， 不包含 prompt 或响应正文。
 */
public record ProviderResponse(
    String text,
    String thinking,
    List<ProviderToolCall> toolCalls,
    GenerationStopReason stopReason,
    ModelUsage usage,
    ModelCost cost,
    String requestId,
    String serviceTier,
    String rawUsageJson,
    List<ProviderToolCallDiagnostic> toolCallDiagnostics) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String EMPTY_USAGE_JSON = "{}";

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ProviderResponse {
    text = text == null ? "" : text;
    thinking = thinking == null ? "" : thinking;
    toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
    stopReason = Objects.requireNonNull(stopReason, "stopReason");
    usage = Objects.requireNonNull(usage, "usage");
    cost = Objects.requireNonNull(cost, "cost");
    requestId = optionalNonBlank(requestId, "requestId");
    serviceTier = optionalNonBlank(serviceTier, "serviceTier");
    rawUsageJson = normalizeRawUsageJson(rawUsageJson);
    toolCallDiagnostics =
        toolCallDiagnostics == null ? List.of() : List.copyOf(toolCallDiagnostics);
    validateDiagnostics(toolCalls, toolCallDiagnostics, stopReason);
  }

  public ProviderResponse(
      String text,
      String thinking,
      List<ProviderToolCall> toolCalls,
      GenerationStopReason stopReason,
      ModelUsage usage,
      ModelCost cost,
      String requestId,
      String serviceTier,
      String rawUsageJson) {
    this(
        text,
        thinking,
        toolCalls,
        stopReason,
        usage,
        cost,
        requestId,
        serviceTier,
        rawUsageJson,
        List.of());
  }

  private static String optionalNonBlank(String value, String name) {
    if (value != null && value.isBlank()) {
      throw new IllegalArgumentException(name + " must be null or non-blank");
    }
    return value;
  }

  private static String normalizeRawUsageJson(String value) {
    if (value == null) {
      return EMPTY_USAGE_JSON;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("rawUsageJson must contain JSON");
    }
    JsonNode node;
    try {
      node = OBJECT_MAPPER.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("rawUsageJson must contain JSON");
    }
    if (!node.isObject() && !node.isArray()) {
      throw new IllegalArgumentException("rawUsageJson must be a JSON object or array");
    }
    return value;
  }

  private static void validateDiagnostics(
      List<ProviderToolCall> toolCalls,
      List<ProviderToolCallDiagnostic> diagnostics,
      GenerationStopReason stopReason) {
    if (stopReason == GenerationStopReason.FILTERED && !diagnostics.isEmpty()) {
      throw new IllegalArgumentException(
          "FILTERED responses must not contain tool call diagnostics");
    }
    int totalOutcomes = toolCalls.size() + diagnostics.size();
    Set<Integer> seenIndices = new HashSet<>();
    for (ProviderToolCallDiagnostic diagnostic : diagnostics) {
      if (diagnostic == null) {
        throw new IllegalArgumentException("toolCallDiagnostic must not be null");
      }
      int index = diagnostic.callIndex();
      if (index < 0 || index >= totalOutcomes) {
        throw new IllegalArgumentException(
            "toolCallDiagnostic callIndex out of bounds: "
                + index
                + ", totalOutcomes: "
                + totalOutcomes);
      }
      if (!seenIndices.add(index)) {
        throw new IllegalArgumentException("duplicate toolCallDiagnostic callIndex: " + index);
      }
    }
  }
}
