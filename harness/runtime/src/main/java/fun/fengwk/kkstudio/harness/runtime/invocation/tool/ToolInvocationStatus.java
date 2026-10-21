package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

/** 一次 Tool invocation 的 durable 业务状态。 */
public enum ToolInvocationStatus {
  /** 工具调用等待人工确认或策略审批。 */
  WAITING_APPROVAL,

  /**
   * 内部人工输入工具（{@code ask_user}）已冻结，等待用户回答冻结问卷。
   *
   * <p>与 WAITING_APPROVAL 分离：问卷不能授权工具，审批也不能代替回答。等待不占用 Worker / 外部执行；回答以 SUCCEEDED + inputReceipt
   * 落盘，Stop 则以 CANCELLED 收敛。
   */
  WAITING_INPUT,

  /** 工具调用已就绪，等待分派执行。 */
  READY,

  /** 工具调用已通过本地 admission 并持久化为执行中：此后任何副作用都可能已发生，崩溃恢复只会收敛为 UNKNOWN。 */
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
