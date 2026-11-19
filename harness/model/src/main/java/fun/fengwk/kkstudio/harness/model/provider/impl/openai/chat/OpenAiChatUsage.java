package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;

import java.util.Map;

/**
 * OpenAI Chat 尾部 usage 的累积与归一化。
 *
 * <p>最后一条 usage 快照整体覆盖之前的快照：缺失的字段回到缺省值，而不是与旧值合并。输出总量包含 reasoning；reasoning 只作为其可信子集记录，绝不从输出中扣除。
 */
final class OpenAiChatUsage {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT_DECIMAL;

  private boolean present = false;
  private long promptTokens = 0L;
  private long cachedTokens = 0L;
  private long cacheWriteTokens = 0L;
  private long completionTokens = 0L;
  private Long reasoningTokens = null;
  private Long totalTokens = null;
  private String rawUsageJson = "{}";

  boolean isPresent() {
    return present;
  }

  String rawUsageJson() {
    return rawUsageJson;
  }

  /** 用一条新的 usage 快照替换此前累积的全部用量事实。 */
  void replaceWith(JsonNode usageNode) {
    present = true;
    promptTokens = requiredNonNegativeLong(usageNode.get("prompt_tokens"), "prompt_tokens");
    completionTokens =
        requiredNonNegativeLong(usageNode.get("completion_tokens"), "completion_tokens");
    totalTokens =
        usageNode.has("total_tokens") && !usageNode.get("total_tokens").isNull()
            ? requiredNonNegativeLong(usageNode.get("total_tokens"), "total_tokens")
            : null;

    JsonNode promptDetails = optionalObject(usageNode, "prompt_tokens_details");
    cachedTokens = readCachedTokens(usageNode, promptDetails);
    cacheWriteTokens = readCacheWriteTokens(promptDetails);

    JsonNode completionDetails = optionalObject(usageNode, "completion_tokens_details");
    reasoningTokens =
        completionDetails != null && completionDetails.has("reasoning_tokens")
            ? requiredNonNegativeLong(
                completionDetails.get("reasoning_tokens"),
                "completion_tokens_details.reasoning_tokens")
            : null;

    try {
      rawUsageJson = OBJECT_MAPPER.writeValueAsString(usageNode);
    } catch (JacksonException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cannot preserve provider usage payload");
    }
  }

  /** 缓存读命中：标准 details 优先，其次 DeepSeek 的 prompt cache 命中量，最后是部分厂商的顶层字段。 */
  private static long readCachedTokens(JsonNode usageNode, JsonNode promptDetails) {
    if (promptDetails != null && promptDetails.has("cached_tokens")) {
      return requiredNonNegativeLong(
          promptDetails.get("cached_tokens"), "prompt_tokens_details.cached_tokens");
    }
    if (usageNode.has("prompt_cache_hit_tokens")) {
      return requiredNonNegativeLong(
          usageNode.get("prompt_cache_hit_tokens"), "prompt_cache_hit_tokens");
    }
    if (usageNode.has("cached_tokens")) {
      return requiredNonNegativeLong(usageNode.get("cached_tokens"), "cached_tokens");
    }
    return 0L;
  }

  private static long readCacheWriteTokens(JsonNode promptDetails) {
    if (promptDetails != null && promptDetails.has("cache_write_tokens")) {
      return requiredNonNegativeLong(
          promptDetails.get("cache_write_tokens"), "prompt_tokens_details.cache_write_tokens");
    }
    if (promptDetails != null && promptDetails.has("cache_creation_input_tokens")) {
      return requiredNonNegativeLong(
          promptDetails.get("cache_creation_input_tokens"),
          "prompt_tokens_details.cache_creation_input_tokens");
    }
    return 0L;
  }

  /** 归一化为互斥的用量事实：未缓存输入 = 输入总量减去缓存读与缓存写。 */
  ModelUsage toModelUsage(Long cacheWriteTtlSeconds) {
    if (reasoningTokens != null && reasoningTokens > completionTokens) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "reasoning tokens exceed completion tokens");
    }
    return new ModelUsage(
        uncachedTokens(promptTokens, cachedTokens, cacheWriteTokens),
        completionTokens,
        cachedTokens,
        cacheWriteTokens,
        cacheWriteTokens == 0L || cacheWriteTtlSeconds == null
            ? Map.of()
            : Map.of(cacheWriteTtlSeconds, cacheWriteTokens),
        reasoningTokens,
        totalTokens);
  }

  private static long uncachedTokens(long total, long cached, long cacheWrite) {
    long excluded = addUsageValues(cached, cacheWrite);
    if (excluded > total) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage breakdown exceeds prompt tokens");
    }
    return total - excluded;
  }

  private static long addUsageValues(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "provider usage values overflow");
    }
  }

  private static JsonNode optionalObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value;
  }

  private static long requiredNonNegativeLong(JsonNode value, String field) {
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0L) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid usage field: " + field);
    }
    return value.longValue();
  }
}
