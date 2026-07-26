package fun.fengwk.kkstudio.harness.runtime.model;

/**
 * 一次模型响应报告的 token 用量。
 *
 * <p>共七类：
 *
 * <ul>
 *   <li>{@code inputTokens}、{@code outputTokens}：扣除 cache 与 reasoning 后由 Provider 计费的输入/输出 token。
 *   <li>{@code cacheReadTokens}、{@code cacheWriteTokens}、{@code cacheWriteLongTokens}：命中与写入缓存的
 *       token。
 *   <li>{@code reasoningTokens}：单独计费的推理 token。
 *   <li>{@code providerTotalTokens}：Provider 报告的总 token，{@link #totalTokens()} 原样返回。
 * </ul>
 *
 * <p>前六类构成互斥计费类别。{@link #categorizedTokens()} 用 {@link Math#addExact(long, long)} 求和，溢出即失败。
 */
public record ModelUsage(
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    long cacheWriteLongTokens,
    long reasoningTokens,
    long providerTotalTokens) {

  public ModelUsage {
    requireNonNegative(inputTokens, "inputTokens");
    requireNonNegative(outputTokens, "outputTokens");
    requireNonNegative(cacheReadTokens, "cacheReadTokens");
    requireNonNegative(cacheWriteTokens, "cacheWriteTokens");
    requireNonNegative(cacheWriteLongTokens, "cacheWriteLongTokens");
    requireNonNegative(reasoningTokens, "reasoningTokens");
    requireNonNegative(providerTotalTokens, "providerTotalTokens");
  }

  /** 返回 Provider 报告的总 token（{@code providerTotalTokens}）。 */
  public long totalTokens() {
    return providerTotalTokens;
  }

  /** 返回前六类计费类别之和，使用 {@link Math#addExact(long, long)} 检测溢出。 */
  public long categorizedTokens() {
    long sum = inputTokens;
    sum = Math.addExact(sum, outputTokens);
    sum = Math.addExact(sum, cacheReadTokens);
    sum = Math.addExact(sum, cacheWriteTokens);
    sum = Math.addExact(sum, cacheWriteLongTokens);
    sum = Math.addExact(sum, reasoningTokens);
    return sum;
  }

  /**
   * 表示本次请求至少命中了一次缓存。
   *
   * <p>仅作为单条记录的命中事实；命中率属于聚合服务，不在本 domain 内计算。
   */
  public boolean cacheHit() {
    return cacheReadTokens > 0;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
