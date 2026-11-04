package fun.fengwk.kkstudio.harness.spi.work;

/** Work mailbox target 的固定集合。 */
public enum WorkTargetType {
  /** 由 ThreadProcessor 消费，用于规划新 Turn 或应用已完成的调用结果。 */
  THREAD,

  /** 用于分派或恢复以 Driver 私有身份为键的模型执行（如 Builtin 的 Model 调度）。 */
  MODEL,

  /** 由 Agent 处理器消费，用于进程外 Agent 派发、事件接收与清理。 */
  AGENT,

  /** 由 ToolProcessor 消费，用于预检、分派或恢复单个工具调用。 */
  TOOL
}
