package fun.fengwk.kkstudio.harness.runtime.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次模型响应归一化后的 token 用量。
 *
 * <p>{@code inputTokens} 是未缓存输入；{@code outputTokens} 是包含 reasoning 的输出总量，绝不与 reasoning 重复相加。 {@code
 * cacheWriteTokens} 是所有缓存写入总量；{@code cacheWriteByTtl} 只承载可信的 TTL 拆分，存在时必须与总量守恒。 {@code
 * reasoningTokens} 是输出总量的子集，null 表示上游未报告，而不是已知 0。{@code providerTotalTokens} 只保留上游原始 报告，null 表示未报告。
 */
public record ModelUsage(
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    Map<Long, Long> cacheWriteByTtl,
    Long reasoningTokens,
    Long providerTotalTokens) {

  public ModelUsage {
    requireNonNegative(inputTokens, "inputTokens");
    requireNonNegative(outputTokens, "outputTokens");
    requireNonNegative(cacheReadTokens, "cacheReadTokens");
    requireNonNegative(cacheWriteTokens, "cacheWriteTokens");
    cacheWriteByTtl = copyTtlTokens(cacheWriteByTtl, cacheWriteTokens);
    reasoningTokens = optionalNonNegative(reasoningTokens, "reasoningTokens");
    if (reasoningTokens != null && reasoningTokens > outputTokens) {
      throw new IllegalArgumentException("reasoningTokens must not exceed outputTokens");
    }
    providerTotalTokens = optionalNonNegative(providerTotalTokens, "providerTotalTokens");
  }

  /** 返回归一化后互斥的 token 总量（output 已包含 reasoning，不重复相加）。 */
  public long categorizedTokens() {
    long sum = inputTokens;
    sum = Math.addExact(sum, outputTokens);
    sum = Math.addExact(sum, cacheReadTokens);
    sum = Math.addExact(sum, cacheWriteTokens);
    return sum;
  }

  /** 计入 context 条件的输入总量：未缓存输入 + 缓存读 + 所有缓存写。 */
  public long contextTokens() {
    long sum = Math.addExact(inputTokens, cacheReadTokens);
    return Math.addExact(sum, cacheWriteTokens);
  }

  /** 非 reasoning 输出 token；reasoning 未知时返回 null。 */
  public Long nonReasoningOutputTokens() {
    return reasoningTokens == null ? null : outputTokens - reasoningTokens;
  }

  /** 表示本次请求至少命中了一次缓存。 */
  public boolean cacheHit() {
    return cacheReadTokens > 0;
  }

  private static Map<Long, Long> copyTtlTokens(Map<Long, Long> source, long total) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<Long, Long> copy = new LinkedHashMap<>();
    long sum = 0L;
    for (Map.Entry<Long, Long> entry : source.entrySet()) {
      Long ttl = entry.getKey();
      if (ttl == null || ttl <= 0) {
        throw new IllegalArgumentException("cacheWriteByTtl keys must be positive seconds");
      }
      Long tokens = optionalNonNegative(entry.getValue(), "cacheWriteByTtl tokens");
      if (tokens == null) {
        throw new IllegalArgumentException("cacheWriteByTtl values must not be null");
      }
      sum = Math.addExact(sum, tokens);
      copy.put(ttl, tokens);
    }
    if (sum != total) {
      throw new IllegalArgumentException(
          "cacheWriteByTtl must sum to cacheWriteTokens (" + sum + " != " + total + ")");
    }
    return Map.copyOf(copy);
  }

  private static Long optionalNonNegative(Long value, String name) {
    if (value == null) {
      return null;
    }
    requireNonNegative(value, name);
    return value;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
