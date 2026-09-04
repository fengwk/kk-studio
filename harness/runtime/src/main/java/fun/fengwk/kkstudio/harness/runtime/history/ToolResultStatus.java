package fun.fengwk.kkstudio.harness.runtime.history;

/** 一条 ToolResult 消息的 durable terminal 状态。 */
public enum ToolResultStatus {
  /** 工具调用成功执行并写入结果。 */
  SUCCEEDED,

  /** 工具调用以失败终态结束并写入错误信息。 */
  FAILED,

  /** 工具调用被取消并写入取消结果。 */
  CANCELLED,

  /** 工具调用结果无法确定，或由历史截断补写为合成终态。 */
  UNKNOWN
}
