package fun.fengwk.kkstudio.harness.runtime.entry;

/** 开启 Model response turn 的 durable reason。 */
public enum TurnStartReason {
  INPUT,
  CONTINUATION,
  /** 自动对话压缩 turn：消费零 queued Command，只承载一次压缩 Model 调用。 */
  COMPACTION
}
