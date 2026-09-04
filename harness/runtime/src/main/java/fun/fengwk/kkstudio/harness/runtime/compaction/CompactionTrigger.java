package fun.fengwk.kkstudio.harness.runtime.compaction;

/** 触发一次压缩的 durable 原因。 */
public enum CompactionTrigger {
  /** 上下文超出软阈值且存在继续义务或排队用户输入时触发的常规压缩。 */
  THRESHOLD,

  /** 模型报告上下文溢出，或长度截断且窗口已近满时立即触发的紧急压缩。 */
  OVERFLOW,

  /** 用户显式发起的手动压缩请求。 */
  MANUAL
}
