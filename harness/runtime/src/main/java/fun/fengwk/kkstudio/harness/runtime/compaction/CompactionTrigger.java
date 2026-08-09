package fun.fengwk.kkstudio.harness.runtime.compaction;

/**
 * 触发一次压缩的 durable 原因。
 *
 * <p>{@code THRESHOLD} 是上下文超阈值后的常规压缩；{@code OVERFLOW} 是最近一次非压缩 model turn 以 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind#OVERFLOW} 失败后的压缩，完成后以 {@code
 * continueModel=true} 关闭，让既有 CONTINUATION 路径从 summary + kept suffix 重试失败的用户/model turn。
 */
public enum CompactionTrigger {
  THRESHOLD,
  OVERFLOW
}
