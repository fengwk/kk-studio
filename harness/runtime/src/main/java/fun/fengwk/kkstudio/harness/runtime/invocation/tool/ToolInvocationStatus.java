package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

/** 一次 Tool invocation 的 durable 业务状态。 */
public enum ToolInvocationStatus {
  /** 工具调用等待人工确认或策略审批。 */
  WAITING_APPROVAL,

  /** 工具调用已就绪，等待分派执行。 */
  READY,

  /** 已写入持久化分派围栏，但 Gateway 是否成功接受尚未确认。 */
  DISPATCHING,

  /** Gateway 已接受工具调用，Runtime 已进入受租约保护的执行阶段。 */
  RUNNING,

  /** 工具调用已成功完成并产出结果。 */
  SUCCEEDED,

  /** 工具调用因确定性错误收敛为失败终态。 */
  FAILED,

  /** 工具调用已被主动取消。 */
  CANCELLED,

  /** 工具调用结果无法安全确定，收敛至不确定终态。 */
  UNKNOWN;

  /** 该状态是否为 terminal 状态。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
