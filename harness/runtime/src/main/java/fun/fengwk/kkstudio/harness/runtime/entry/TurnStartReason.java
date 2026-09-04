package fun.fengwk.kkstudio.harness.runtime.entry;

/** 开启 Model response turn 的 durable reason。 */
public enum TurnStartReason {
  /** 消费排队的用户或自定义输入命令开启 Turn。 */
  INPUT,

  /** 由上一轮工具执行结果或继续义务驱动开启 Turn。 */
  CONTINUATION,

  /** 自动对话压缩 Turn，消费零排队命令且仅承载压缩模型调用。 */
  COMPACTION
}
