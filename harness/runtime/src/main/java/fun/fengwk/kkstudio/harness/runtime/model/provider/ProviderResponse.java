package fun.fengwk.kkstudio.harness.runtime.model.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.List;
import java.util.Objects;

/**
 * Provider 流完成时提供的完整响应快照。
 *
 * <p>{@code usage} 是 Provider 归一化后的实际 token 用量，{@code cost} 是基于本次生效价格计算的非空成本快照。 {@code
 * requestId}、{@code serviceTier} 由 Provider 报告，可为空。{@code rawUsageJson} 是 Provider usage 段的原始 JSON
 * 序列化（必须是合法 JSON object 或 array，null 规范化为 {@code "{}"}），仅承载 usage 元数据， 不包含 prompt 或响应正文。
 */
public record ProviderResponse(
    String text,
    String thinking,
    List<ProviderToolCall> toolCalls,
    ProviderStopReason stopReason,
    ModelUsage usage,
    ModelCost cost,
    String requestId,
    String serviceTier,
    String rawUsageJson) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String EMPTY_USAGE_JSON = "{}";

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
      throw new IllegalArgumentException("rawUsageJson must contain JSON", exception);
    }
    if (!node.isObject() && !node.isArray()) {
      throw new IllegalArgumentException("rawUsageJson must be a JSON object or array");
    }
    return value;
  }
}
