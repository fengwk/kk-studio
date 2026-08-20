package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;

import java.util.Objects;
import java.util.UUID;

/**
 * 统一 deterministic no-gain 判定：用同一估算器比较当前 provider projection {@code before} 与 new summary wrapper +
 * retained suffix 的 {@code after}，必须 {@code after < before} 才算有压缩收益。
 *
 * <p>FULL/TURN_PREFIX 落盘前必须通过本检查；失败时该 Compaction Turn 写 {@code ASSISTANT_ERROR(COMPACTION_NO_GAIN)}
 * 并 FAILED，绝不写 COMPACTION。HISTORY 不做最终 gain 判断。
 */
public final class CompactionNoGain {

  /** no-gain failure 的稳定 AssistantError code。 */
  public static final String NO_GAIN_ERROR_CODE = "COMPACTION_NO_GAIN";

  private CompactionNoGain() {}

  /**
   * 判断 {@code summaryText}（已含 file sections）替换到 cut 之前的上下文是否真正减少 provider projection。
   *
   * @param path 当前 root-to-head 路径（含 open 的 COMPACTION turn 控制条目，估算时按可见掩码被跳过）
   * @param summaryText 最终完整 summary（含 canonical file sections）
   * @param cutEntryId 本次压缩实际第一个保留的上下文消息 Entry id
   */
  public static boolean hasGain(EntryPath path, String summaryText, UUID cutEntryId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(summaryText, "summaryText");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    long before = CompactionPlanner.estimateProjectionTokens(path);
    long after = CompactionPlanner.estimateProjectionTokensAfter(path, summaryText, cutEntryId);
    return after < before;
  }
}
