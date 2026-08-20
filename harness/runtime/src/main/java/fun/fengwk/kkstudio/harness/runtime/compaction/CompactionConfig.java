package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;

import java.util.Objects;

/**
 * 自动对话压缩的部署级配置。
 *
 * <p>{@code keepRecentTokens} 是压缩后保留最近上下文的 token 上限（默认 20_000）；{@code fallbackModel} 是主压缩 model
 * 正常失败时的可选用替代 model（null 表示禁用 fallback）。不持久化 enabled / reserve / maxRecent。
 *
 * <ul>
 *   <li>{@code effectiveKeep = min(keepRecentTokens, C / 2)}——C 为触发 turn 的冻结 contextWindow。
 *   <li>{@code effectiveReserve = min(16_384, maxOutputTokens)}——summary prompt + output 的预留预算。
 *   <li>{@code softThreshold = max(effectiveKeep, C - effectiveReserve)}——活跃 continuation /
 *       next-demand 门控阈值。
 *   <li>{@code manualMinimum = min(keepRecentTokens * 2, C / 2)}——手动控制的最低上下文量。
 *   <li>输出预算：FULL/HISTORY 为 {@code min(maxOutput, floor(0.8 * reserve), removedPrefixEstimate)}；
 *       TURN_PREFIX 为 {@code min(maxOutput, floor(0.5 * reserve), removedPrefixEstimate)}。
 * </ul>
 */
public record CompactionConfig(int keepRecentTokens, ModelSelection fallbackModel) {

  public static final CompactionConfig DEFAULT = new CompactionConfig(20_000, null);

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

  /** 内部预留预算：{@code min(16_384, maxOutputTokens)}。 */
  public long effectiveReserve(long maxOutputTokens) {
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    return Math.min(EFFECTIVE_RESERVE_CAP, maxOutputTokens);
  }

  /** soft threshold：{@code max(effectiveKeep, C - effectiveReserve)}。 */
  public long softThreshold(long contextWindow, long maxOutputTokens) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    long keep = effectiveKeep(contextWindow);
    long reserve = effectiveReserve(maxOutputTokens);
    return Math.max(keep, contextWindow - reserve);
  }

  /** 手动压缩最低上下文量：{@code min(keepRecentTokens * 2, C / 2)}。 */
  public long manualMinimum(long contextWindow) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    return Math.min(keepRecentTokens * 2L, contextWindow / 2L);
  }

  /**
   * 指定阶段的输出 token 预算（FULL/HISTORY 用 {@code floor(0.8*reserve)}，TURN_PREFIX 用 {@code
   * floor(0.5*reserve)}）。
   */
  public long outputBudget(CompactionPhase phase, long maxOutputTokens, long removedPrefixTokens) {
    Objects.requireNonNull(phase, "phase");
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
    if (removedPrefixTokens < 0) {
      throw new IllegalArgumentException("removedPrefixTokens must not be negative");
    }
    long reserve = effectiveReserve(maxOutputTokens);
    long budgetByReserve =
        phase == CompactionPhase.TURN_PREFIX ? reserve / 2L : (reserve * 4L) / 5L;
    return Math.min(maxOutputTokens, Math.min(budgetByReserve, removedPrefixTokens));
  }
}
