package fun.fengwk.kkstudio.harness.runtime.compaction;

/**
 * 触发一次压缩的 durable 原因。
 *
 * <p>{@code THRESHOLD} 是上下文超 soft threshold 且下一真实 user input 已排队的常规压缩；{@code OVERFLOW} 是最近一次 非压缩
 * model turn 命中 hard context wall（{@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind#OVERFLOW} 或严格 LENGTH
 * 近满窗口）后的立即压缩； {@code MANUAL} 是用户通过手动控制显式发起的压缩。
 */
public enum CompactionTrigger {
  THRESHOLD,
  OVERFLOW,
  MANUAL
}
