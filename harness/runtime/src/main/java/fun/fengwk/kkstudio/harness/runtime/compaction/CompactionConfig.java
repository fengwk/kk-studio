package fun.fengwk.kkstudio.harness.runtime.compaction;

/**
 * 自动对话压缩的部署级配置。
 *
 * <p>默认值对齐上游 Pi：{@code enabled=true}、{@code reserveTokens=16_384}、{@code
 * maxRecentTokens=20_000}。{@code reserveTokens} 是 summary prompt + output 的预留 token 预算（FULL/HISTORY
 * 输出上限为 {@code floor(0.8 * reserveTokens)}，TURN_PREFIX 为 {@code floor(0.5 * reserveTokens)}，因此必须
 * {@code >= 2} 才能保证两个预算都为正）； {@code maxRecentTokens} 是压缩后保留最近上下文 token 的上限，与 {@code
 * floor(contextWindow * 0.5)} 取小为有效保留量。
 */
public record CompactionConfig(boolean enabled, int reserveTokens, int maxRecentTokens) {

  public CompactionConfig {
    if (reserveTokens < 2) {
      throw new IllegalArgumentException("reserveTokens must be at least 2");
    }
    if (maxRecentTokens <= 0) {
      throw new IllegalArgumentException("maxRecentTokens must be positive");
    }
  }

  /** 有效最近上下文保留量：{@code min(floor(contextWindow * 0.5), maxRecentTokens)}。 */
  public long effectiveKeepRecentTokens(long contextWindow) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    return Math.min(contextWindow / 2, maxRecentTokens);
  }
}
