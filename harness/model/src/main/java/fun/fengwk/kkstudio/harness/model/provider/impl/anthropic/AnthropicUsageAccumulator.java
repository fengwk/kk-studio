package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Anthropic Messages 流的 usage 累积：原始快照按顶层字段合并，normalized 计数与 cache_creation 拆分独立维护。
 *
 * <p>原始快照是终态 usage JSON 的基座：官方未知字段（如 {@code server_tool_use}）原样保留，normalized 计数只覆盖同名键。
 */
final class AnthropicUsageAccumulator {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final String USAGE_INTEGER_FORMAT = "usage %s must be an integer";
  private static final String USAGE_NEGATIVE_FORMAT = "usage %s must not be negative";

  private static final Set<String> ALLOWED_CACHE_MISS_REASONS =
      Set.of(
          "model_changed",
          "system_changed",
          "tools_changed",
          "messages_changed",
          "previous_message_not_found",
          "unavailable");

  /** usage 快照的原始累积：按顶层字段合并，保留官方未知字段；normalized 计数在终态覆盖同名键。 */
  private final ObjectNode rawUsage = NODES.objectNode();

  private long inputTokens = 0L;
  private long cacheReadInputTokens = 0L;
  private long cacheCreationInputTokens = 0L;
  private long cacheCreation5m = 0L;
  private long cacheCreation1h = 0L;
  private long outputTokens = 0L;
  private boolean hasExplicitCacheBreakdown = false;

  private String serviceTier = null;
  private String cacheMissReasonType = null;
  private Long cacheMissedInputTokens = null;

  /** 原始快照是否已同时给出 input/output 计数。 */
  boolean hasCompleteUsage() {
    return rawUsage.has("input_tokens") && rawUsage.has("output_tokens");
  }

  String serviceTier() {
    return serviceTier;
  }

  /** 合并一次 usage 快照：官方未知字段原样保留，已知字段按 normalized 口径解析。 */
  void applySnapshot(JsonNode usageNode) {
    for (Map.Entry<String, JsonNode> field : usageNode.properties()) {
      rawUsage.set(field.getKey(), field.getValue().deepCopy());
    }

    if (usageNode.has("input_tokens")) {
      this.inputTokens = usageLong(usageNode.get("input_tokens"), "input_tokens");
    }
    if (usageNode.has("output_tokens")) {
      this.outputTokens = usageLong(usageNode.get("output_tokens"), "output_tokens");
    }
    if (usageNode.has("cache_read_input_tokens")) {
      this.cacheReadInputTokens =
          usageLong(usageNode.get("cache_read_input_tokens"), "cache_read_input_tokens");
    }
    if (usageNode.has("cache_creation_input_tokens")) {
      this.cacheCreationInputTokens =
          usageLong(usageNode.get("cache_creation_input_tokens"), "cache_creation_input_tokens");
    }
    if (usageNode.has("service_tier") && !usageNode.get("service_tier").isNull()) {
      JsonNode tierNode = usageNode.get("service_tier");
      if (!tierNode.isString() || tierNode.stringValue().isBlank()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "usage service_tier must be a non-blank string");
      }
      this.serviceTier = tierNode.stringValue();
    }
    extractAndValidateCacheCreationBreakdown(usageNode);
  }

  /** 记录 message_start diagnostics 中可信的 cache miss 原因；未知原因类型不作诊断事实。 */
  void applyDiagnostics(JsonNode diagnosticsNode) {
    if (diagnosticsNode == null || diagnosticsNode.isNull()) {
      return;
    }
    if (!diagnosticsNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "diagnostics must be an object");
    }
    if (!diagnosticsNode.has("cache_miss_reason")) {
      return;
    }
    JsonNode missNode = diagnosticsNode.get("cache_miss_reason");
    if (!missNode.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache_miss_reason must be an object");
    }
    JsonNode typeNode = missNode.get("type");
    if (typeNode == null || !typeNode.isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache_miss_reason type must be a string");
    }
    String type = typeNode.stringValue();
    if (!ALLOWED_CACHE_MISS_REASONS.contains(type)) {
      return;
    }
    this.cacheMissReasonType = type;
    if (missNode.has("cache_missed_input_tokens")) {
      this.cacheMissedInputTokens =
          usageLong(missNode.get("cache_missed_input_tokens"), "cache_missed_input_tokens");
    }
  }

  /**
   * 冻结终态 usage：先按 cache_creation 拆分守恒口径收敛 5m/1h，再写回 normalized 计数并交付规范化用量与原始 JSON。
   *
   * <p>Anthropic Messages 无原生 total_tokens，不合成虚假总数。
   */
  AnthropicUsageSnapshot freeze() {
    reconcileCacheCreationBreakdown();

    // usage 以原始快照的顶层合并结果为基座：官方未知字段（如 server_tool_use）原样保留，normalized 计数覆盖同名键
    rawUsage.put("input_tokens", this.inputTokens);
    rawUsage.put("output_tokens", this.outputTokens);
    rawUsage.put("cache_read_input_tokens", this.cacheReadInputTokens);
    rawUsage.put("cache_creation_input_tokens", this.cacheCreationInputTokens);
    writeCacheCreationBreakdown();
    writeCacheMissReason();

    String rawUsageJson = rawUsage.toString();

    Map<Long, Long> cacheWriteByTtl = new LinkedHashMap<>();
    if (cacheCreation5m > 0) {
      cacheWriteByTtl.put(300L, cacheCreation5m);
    }
    if (cacheCreation1h > 0) {
      cacheWriteByTtl.put(3600L, cacheCreation1h);
    }

    Long reasoningTokens = reasoningTokens();
    ModelUsage usage =
        new ModelUsage(
            inputTokens,
            outputTokens,
            cacheReadInputTokens,
            cacheCreationInputTokens,
            Map.copyOf(cacheWriteByTtl),
            reasoningTokens,
            null);
    return new AnthropicUsageSnapshot(usage, rawUsageJson);
  }

  /** 未显式声明 breakdown 时全部计入 5 分钟档；显式声明时两侧总和必须与顶层计数一致。 */
  private void reconcileCacheCreationBreakdown() {
    if (!hasExplicitCacheBreakdown) {
      this.cacheCreation5m = this.cacheCreationInputTokens;
      this.cacheCreation1h = 0L;
      return;
    }
    long sum;
    try {
      sum = Math.addExact(this.cacheCreation5m, this.cacheCreation1h);
    } catch (ArithmeticException overflow) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache_creation tokens breakdown overflow");
    }
    if (sum != this.cacheCreationInputTokens) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "cache_creation tokens breakdown mismatch");
    }
  }

  /** breakdown 与 miss reason 写回 raw usage，保留官方未知字段。 */
  private void writeCacheCreationBreakdown() {
    ObjectNode cacheCreationNode = rawUsage.putObject("cache_creation");
    cacheCreationNode.put("ephemeral_5m_input_tokens", this.cacheCreation5m);
    cacheCreationNode.put("ephemeral_1h_input_tokens", this.cacheCreation1h);
  }

  /** cache miss 原因只在协议给出时写入；缺失计数时省略该子字段。 */
  private void writeCacheMissReason() {
    if (this.cacheMissReasonType == null) {
      return;
    }
    ObjectNode missNode = rawUsage.putObject("cache_miss_reason");
    missNode.put("type", this.cacheMissReasonType);
    if (this.cacheMissedInputTokens != null) {
      missNode.put("cache_missed_input_tokens", this.cacheMissedInputTokens);
    }
  }

  /** thinking tokens 是可选明细，且不得超过输出总量。 */
  private Long reasoningTokens() {
    JsonNode outputDetails = rawUsage.get("output_tokens_details");
    if (outputDetails == null || outputDetails.isNull()) {
      return null;
    }
    if (!outputDetails.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "output_tokens_details must be an object");
    }
    JsonNode thinking = outputDetails.get("thinking_tokens");
    if (thinking == null || thinking.isNull()) {
      return null;
    }
    Long tokens = usageLong(thinking, "thinking_tokens");
    if (tokens > outputTokens) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "thinking tokens exceed output tokens");
    }
    return tokens;
  }

  private void extractAndValidateCacheCreationBreakdown(JsonNode usageNode) {
    long m5 = 0L;
    long h1 = 0L;
    boolean has5m = false;
    boolean has1h = false;

    JsonNode nested = usageNode.get("cache_creation");
    if (nested != null) {
      if (!nested.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "cache_creation must be an object");
      }
      if (nested.has("ephemeral_5m_input_tokens")) {
        m5 = usageLong(nested.get("ephemeral_5m_input_tokens"), "ephemeral_5m_input_tokens");
        has5m = true;
      }
      if (nested.has("ephemeral_5m")) {
        long alias5m = usageLong(nested.get("ephemeral_5m"), "ephemeral_5m_input_tokens");
        if (has5m && m5 != alias5m) {
          throw conflictingBreakdown();
        }
        m5 = alias5m;
        has5m = true;
      }
      if (nested.has("ephemeral_1h_input_tokens")) {
        h1 = usageLong(nested.get("ephemeral_1h_input_tokens"), "ephemeral_1h_input_tokens");
        has1h = true;
      }
      if (nested.has("ephemeral_1h")) {
        long alias1h = usageLong(nested.get("ephemeral_1h"), "ephemeral_1h_input_tokens");
        if (has1h && h1 != alias1h) {
          throw conflictingBreakdown();
        }
        h1 = alias1h;
        has1h = true;
      }
    }

    if (usageNode.has("cache_creation_ephemeral_5m_input_tokens")) {
      long flat5m =
          usageLong(
              usageNode.get("cache_creation_ephemeral_5m_input_tokens"),
              "cache_creation_ephemeral_5m_input_tokens");
      if (has5m && m5 != flat5m) {
        throw conflictingBreakdown();
      }
      m5 = flat5m;
      has5m = true;
    }
    if (usageNode.has("cache_creation_ephemeral_1h_input_tokens")) {
      long flat1h =
          usageLong(
              usageNode.get("cache_creation_ephemeral_1h_input_tokens"),
              "cache_creation_ephemeral_1h_input_tokens");
      if (has1h && h1 != flat1h) {
        throw conflictingBreakdown();
      }
      h1 = flat1h;
      has1h = true;
    }

    if (has5m || has1h) {
      if (has5m) {
        this.cacheCreation5m = m5;
      }
      if (has1h) {
        this.cacheCreation1h = h1;
      }
      hasExplicitCacheBreakdown = true;
    }
  }

  private static ProviderException conflictingBreakdown() {
    return new ProviderException(
        ProviderErrorKind.INVALID_RESPONSE, "conflicting cache_creation breakdown fields");
  }

  private static long usageLong(JsonNode node, String fieldName) {
    return ProviderJsonHelper.parseNonNegativeLong(
        node, fieldName, USAGE_INTEGER_FORMAT, USAGE_NEGATIVE_FORMAT);
  }
}
