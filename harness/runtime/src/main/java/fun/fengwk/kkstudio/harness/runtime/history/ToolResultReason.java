package fun.fengwk.kkstudio.harness.runtime.history;

/** 结果并非真实 execution 时 ToolResult metadata entry 的 durable reason。 */
public enum ToolResultReason {
  /** 历史归一化因缺失工具结果而补写的截断标记。 */
  HISTORY_CUT
}
