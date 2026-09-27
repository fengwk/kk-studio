package fun.fengwk.kkstudio.harness.runtime.entry;

/** 开启 Model response turn 的 durable reason。 */
public enum TurnStartReason {
  /** 消费排队的用户或自定义输入命令开启 Turn。 */
  INPUT,

  /** 由上一轮工具执行结果或继续义务驱动开启 Turn。 */
  CONTINUATION,

  /** 自动对话压缩 Turn，消费零排队命令且仅承载压缩模型调用。 */
  COMPACTION,

  /**
   * 显式停止屏障 Turn，由 StopControl 在唯一的 Stop 事务内完整写入：唯一一条 {@code ASSISTANT_ERROR}(CANCELLED) 取消屏障 +
   * {@code STOPPED} 且带 {@code closeRequestId} 的 TURN_END。它不消费任何 Command、从不调度模型，也不计入模型工作轮数；它的 {@code
   * ownerThreadId} 是执行停止的 Thread，因此停止边界属于该 Thread 自己。
   */
  STOP
}
