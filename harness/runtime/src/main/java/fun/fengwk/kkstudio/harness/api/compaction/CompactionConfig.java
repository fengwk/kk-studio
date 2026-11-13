package fun.fengwk.kkstudio.harness.api.compaction;

/**
 * 自动对话压缩的部署级配置。
 *
 * <p>{@code keepRecentTokens} 是压缩后保留最近上下文的 token 上限（默认 20_000）。
 *
 * <ul>
 *   <li>{@code effectiveKeep = min(keepRecentTokens, C / 2)}——C 为本次请求的对话模型 contextWindow。
 *   <li>{@code effectiveReserve = min(16_384, maxOutputTokens)}——请求预留预算。
 *   <li>摘要输出上限为 {@code floor(0.8 * reserve)}。
 * </ul>
 */
public record CompactionConfig(int keepRecentTokens) {

  public static final CompactionConfig DEFAULT = new CompactionConfig(20_000);

  /** effectiveReserve 的上限常量。 */
  public static final long EFFECTIVE_RESERVE_CAP = 16_384L;

  public CompactionConfig {
    if (keepRecentTokens <= 0) {
      throw new IllegalArgumentException("keepRecentTokens must be positive");
    }
  }

  /** 有效最近上下文保留量：{@code min(keepRecentTokens, C / 2)}。 */
  public long effectiveKeep(long contextWindow) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    return Math.min(contextWindow / 2L, keepRecentTokens);
  }

  /** 请求预留预算：{@code min(16_384, maxOutputTokens)}。 */
  public long effectiveReserve(long maxOutputTokens) {
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    return Math.min(EFFECTIVE_RESERVE_CAP, maxOutputTokens);
  }

  /** Summary output token limit: {@code floor(0.8 * reserve)}. */
  public long outputBudget(long maxOutputTokens) {
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    return (effectiveReserve(maxOutputTokens) * 4L) / 5L;
  }
}
