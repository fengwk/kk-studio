package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.util.List;
import java.util.Map;

/**
 * Gemini UsageMetadata 累积与终态归一化。
 *
 * <p>{@code prompt}/{@code candidates}/{@code cache} 是普通 proto3 int32，ProtoJSON 可以省略零值：这是 Gemini
 * 协议事实， 不是通用缺字段补零，显式 null 与非法数字仍然拒绝。{@code candidatesTokenCount} 不含 thoughts，内部输出总量包含 reasoning
 * 以避免重复计费。
 */
final class GeminiUsageAccumulator {

  private JsonNode latestUsageSnapshot = null;
  private String rawUsageJson = "{}";
  private String serviceTier = null;

  /** 是否已收到过 usage 快照。 */
  boolean hasSnapshot() {
    return latestUsageSnapshot != null;
  }

  void update(JsonNode usageNode) {
    for (String field :
        List.of(
            "promptTokenCount",
            "candidatesTokenCount",
            "cachedContentTokenCount",
            "thoughtsTokenCount",
            "totalTokenCount")) {
      JsonNode value = usageNode.get(field);
      if (value != null && !value.isNull()) {
        optionalTokenCount(usageNode, field);
      }
    }
    if (usageNode.has("serviceTier")) {
      requireServiceTierShape(usageNode);
    }
    // 保留最新原始快照；终态才解释 proto3 省略零，不跨快照补字段。
    latestUsageSnapshot = usageNode.deepCopy();
  }

  GeminiUsageSnapshot freeze() {
    ModelUsage normalized = normalizeFinalUsage(latestUsageSnapshot);
    return new GeminiUsageSnapshot(normalized, rawUsageJson, serviceTier);
  }

  private ModelUsage normalizeFinalUsage(JsonNode usageNode) {
    // UsageMetadata 的 prompt/candidates/cache 是普通 proto3 int32，ProtoJSON 可省略零值。
    // 这是 Gemini 协议事实，不是通用缺字段补零；显式 null、非法数字仍拒绝。
    long promptTokens = proto3ZeroDefaultCount(usageNode, "promptTokenCount");
    long output = proto3ZeroDefaultCount(usageNode, "candidatesTokenCount");
    long cached = proto3ZeroDefaultCount(usageNode, "cachedContentTokenCount");
    Long providerTotal = optionalReportedCount(usageNode, "totalTokenCount");
    Long reasoning = optionalReportedCount(usageNode, "thoughtsTokenCount");
    if (cached > promptTokens) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cached tokens exceed prompt tokens");
    }
    long ordinaryInput = promptTokens - cached;
    this.serviceTier = serviceTier(usageNode);

    // Gemini candidatesTokenCount 不含 thoughts；内部输出总量包含 reasoning，避免重复计费。
    long outputTotal;
    try {
      outputTotal = Math.addExact(output, reasoning == null ? 0L : reasoning);
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "output token count overflow");
    }
    this.rawUsageJson = usageNode.toString();
    return new ModelUsage(
        ordinaryInput, outputTotal, cached, 0L, Map.of(), reasoning, providerTotal);
  }

  /** 校验本帧的 serviceTier 形状；档位本身在终态从最新快照统一取值。 */
  private static void requireServiceTierShape(JsonNode usageNode) {
    JsonNode value = usageNode.get("serviceTier");
    if (!value.isString() || value.stringValue().isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: serviceTier");
    }
  }

  private static String serviceTier(JsonNode usageNode) {
    if (!usageNode.has("serviceTier")) {
      return null;
    }
    requireServiceTierShape(usageNode);
    return usageNode.get("serviceTier").stringValue();
  }

  private static long proto3ZeroDefaultCount(JsonNode usageNode, String field) {
    JsonNode value = usageNode.get(field);
    if (value == null || value.isMissingNode()) {
      return 0L;
    }
    return optionalTokenCount(usageNode, field);
  }

  private static Long optionalReportedCount(JsonNode usageNode, String field) {
    JsonNode value = usageNode.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    return optionalTokenCount(usageNode, field);
  }

  private static long optionalTokenCount(JsonNode usageNode, String field) {
    JsonNode value = usageNode.get(field);
    if (value == null
        || value.isNull()
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0L) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value.longValue();
  }

  String serviceTier() {
    return serviceTier;
  }

  String rawUsageJson() {
    return rawUsageJson;
  }
}
