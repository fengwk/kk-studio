package fun.fengwk.kkstudio.harness.runtime.work;

/** Work mailbox target 的固定集合。 */
public enum WorkTargetType {
  /** 由 ThreadProcessor 消费，用于规划新 Turn 或应用已完成的调用结果。 */
  THREAD,

  /** 由 ModelProcessor 消费，用于分派或恢复单个模型调用。 */
  MODEL,

  /** 由 ToolProcessor 消费，用于预检、分派或恢复单个工具调用。 */
  TOOL
}
