package fun.fengwk.kkstudio.harness.runtime.compaction;

/** 一次压缩 Model 调用的执行阶段。 */
public enum CompactionPhase {
  /** 非切分 Turn 的完整压缩阶段，单次生成最终摘要。 */
  FULL,

  /** 切分 Turn 的历史压缩阶段，针对旧历史生成中间摘要。 */
  HISTORY,

  /** 切分 Turn 的前缀压缩阶段，压缩当前 Turn 前缀并生成最终摘要。 */
  TURN_PREFIX
}
