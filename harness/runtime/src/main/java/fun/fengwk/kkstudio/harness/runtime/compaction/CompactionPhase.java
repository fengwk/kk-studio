package fun.fengwk.kkstudio.harness.runtime.compaction;

/**
 * 一次压缩 Model 调用的执行阶段。
 *
 * <p>{@code FULL} 是非切分 turn 的最终摘要；切分 turn 先执行 {@code HISTORY}（若存在先前历史），再执行独立的 {@code TURN_PREFIX}
 * 第二次调用，由后者写出 complete 的最终 payload。
 */
public enum CompactionPhase {
  FULL,
  HISTORY,
  TURN_PREFIX
}
