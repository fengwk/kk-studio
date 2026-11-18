package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.Map;

/** Responses 流式响应的 Token 用量归一化：保留原生 usage 结构，同时给出不虚构缺口的 durable 用量。 */
final class OpenAiResponsesStreamUsage {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  private static final String USAGE_INTEGER_FORMAT =
      "invalid usage token count in field %s: must be an integral number within long range";
  private static final String USAGE_NEGATIVE_FORMAT =
      "invalid usage token count in field %s: must be non-negative";

  private JsonNode latestNativeUsageNode = null;
  private long rawInputTokens = 0L;
  private long rawOutputTokens = 0L;
  private long rawTotalTokens = 0L;
  private boolean hasRawTotalTokens = false;
  private long cachedTokens = 0L;
  private long cacheWriteTokens = 0L;
  private Map<Long, Long> cacheWriteByTtl = Map.of();
  private Long reasoningTokens = null;

  /** 缺失 usage 字段保留此前观测值；显式 null 与损坏结构分别重置或拒绝。 */
  private static long usageRemainder(long total, long... parts) {
    long excluded = 0L;
    try {
      for (long part : parts) {
        excluded = Math.addExact(excluded, part);
      }
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
    if (excluded > total) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage breakdown exceeds total");
    }
    return total - excluded;
  }

  private static long parseUsage(JsonNode node, String fieldName) {
    return ProviderJsonHelper.parseNonNegativeLong(
        node, fieldName, USAGE_INTEGER_FORMAT, USAGE_NEGATIVE_FORMAT);
  }

  private void resetUsage() {
    latestNativeUsageNode = null;
    rawInputTokens = 0L;
    rawOutputTokens = 0L;
    hasRawTotalTokens = false;
    rawTotalTokens = 0L;
    cachedTokens = 0L;
    cacheWriteTokens = 0L;
    cacheWriteByTtl = Map.of();
    reasoningTokens = null;
  }

  void apply(JsonNode responseNode, String modelId) {
    if (!responseNode.has("usage")) {
      return;
    }
    JsonNode usageNode = responseNode.get("usage");
    if (usageNode == null || usageNode.isNull()) {
      resetUsage();
      return;
    }
    if (!usageNode.isObject()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "usage is not a JSON object");
    }
    resetUsage();
    latestNativeUsageNode = usageNode.deepCopy();
    if (!usageNode.has("input_tokens") || !usageNode.has("output_tokens")) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE,
          "missing complete usage: OpenAI Responses model " + modelId);
    }
    rawInputTokens = parseUsage(usageNode.get("input_tokens"), "input_tokens");
    rawOutputTokens = parseUsage(usageNode.get("output_tokens"), "output_tokens");
    JsonNode totalNode = usageNode.get("total_tokens");
    if (totalNode != null && !totalNode.isNull()) {
      hasRawTotalTokens = true;
      rawTotalTokens = parseUsage(totalNode, "total_tokens");
    }
    applyInputTokenDetails(usageNode.get("input_tokens_details"));
    applyOutputTokenDetails(usageNode.get("output_tokens_details"));
  }

  private void applyInputTokenDetails(JsonNode details) {
    if (details == null || details.isNull()) {
      return;
    }
    if (!details.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "input_tokens_details is not a JSON object");
    }
    cachedTokens =
        details.has("cached_tokens")
            ? parseUsage(details.get("cached_tokens"), "cached_tokens")
            : 0L;
    cacheWriteTokens =
        details.has("cache_write_tokens")
            ? parseUsage(details.get("cache_write_tokens"), "cache_write_tokens")
            : 0L;
    applyCacheWriteTokenDetails(details.get("cache_write_tokens_details"));
  }

  private void applyCacheWriteTokenDetails(JsonNode details) {
    if (details == null || details.isNull()) {
      return;
    }
    if (!details.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache_write_tokens_details is not a JSON object");
    }
    if (!details.has("ttl_30m_tokens") || !details.has("ttl_12h_tokens")) {
      return;
    }
    long ttl30m = parseUsage(details.get("ttl_30m_tokens"), "ttl_30m_tokens");
    long ttl12h = parseUsage(details.get("ttl_12h_tokens"), "ttl_12h_tokens");
    long total;
    try {
      total = Math.addExact(ttl30m, ttl12h);
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
    if (total != cacheWriteTokens) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache write TTL breakdown does not match total");
    }
    cacheWriteByTtl = Map.of(1800L, ttl30m, 43200L, ttl12h);
  }

  private void applyOutputTokenDetails(JsonNode details) {
    if (details == null || details.isNull()) {
      return;
    }
    if (!details.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "output_tokens_details is not a JSON object");
    }
    if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isNull()) {
      reasoningTokens = parseUsage(details.get("reasoning_tokens"), "reasoning_tokens");
    }
  }

  /** 是否已从终态响应读到 usage。 */
  boolean isPresent() {
    return latestNativeUsageNode != null;
  }

  /** Token 用量归一化：total_tokens 缺失时为 null，不凭空合成；输出总量包含 reasoning。 */
  ModelUsage toModelUsage() {
    long ordinaryInput = usageRemainder(rawInputTokens, cachedTokens, cacheWriteTokens);
    Long providerTotalTokens = hasRawTotalTokens ? rawTotalTokens : null;
    try {
      return new ModelUsage(
          ordinaryInput,
          rawOutputTokens,
          cachedTokens,
          cacheWriteTokens,
          cacheWriteByTtl,
          reasoningTokens,
          providerTotalTokens);
    } catch (IllegalArgumentException invalidUsage) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values are inconsistent");
    }
  }

  /** 完整 usage 已在终态校验；原生结构高保真保留，不伪造字段。 */
  String rawJson() {
    try {
      return OBJECT_MAPPER.writeValueAsString(latestNativeUsageNode);
    } catch (Exception e) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "failed to serialize raw usage JSON");
    }
  }
}
