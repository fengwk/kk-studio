package fun.fengwk.kkstudio.harness.runtime.history;

/** 一条 ToolResult 消息的 durable terminal 状态。 */
public enum ToolResultStatus {
  SUCCEEDED,
  FAILED,
  CANCELLED,
  UNKNOWN
}
