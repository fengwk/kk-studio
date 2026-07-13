package fun.fengwk.kkstudio.harness.model;

/** 一次模型响应报告的 token 用量。 */
public record ModelUsage(
    long inputTokens, long outputTokens, long reasoningTokens, long cachedInputTokens) {

  public ModelUsage {
    requireNonNegative(inputTokens, "inputTokens");
    requireNonNegative(outputTokens, "outputTokens");
    requireNonNegative(reasoningTokens, "reasoningTokens");
    requireNonNegative(cachedInputTokens, "cachedInputTokens");
    if (cachedInputTokens > inputTokens) {
      throw new IllegalArgumentException("cachedInputTokens must not exceed inputTokens");
    }
  }

  public long totalTokens() {
    return inputTokens + outputTokens + reasoningTokens;
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
