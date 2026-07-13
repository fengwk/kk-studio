package fun.fengwk.kkstudio.harness.model;

/**
 * 一次模型响应报告的 token 用量。
 *
 * <p>input、cacheRead 与 cacheWrite 是 Provider 分别报告的输入类别，互不重叠；reasoning 为单独计费时报告的推理 token。
 */
public record ModelUsage(
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    long reasoningTokens) {

  public ModelUsage {
    requireNonNegative(inputTokens, "inputTokens");
    requireNonNegative(outputTokens, "outputTokens");
    requireNonNegative(cacheReadTokens, "cacheReadTokens");
    requireNonNegative(cacheWriteTokens, "cacheWriteTokens");
    requireNonNegative(reasoningTokens, "reasoningTokens");
  }

  /** 返回 Provider 报告的所有 token 类别总数。 */
  public long totalTokens() {
    return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens + reasoningTokens;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
