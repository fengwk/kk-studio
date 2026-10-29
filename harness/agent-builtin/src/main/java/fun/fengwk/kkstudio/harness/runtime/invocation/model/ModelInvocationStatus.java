package fun.fengwk.kkstudio.harness.runtime.invocation.model;

/** 一次 Model invocation 的 durable 业务状态。 */
public enum ModelInvocationStatus {
  /** 模型调用已创建就绪，等待分派执行。 */
  READY,

  /** 已持久化执行意图（先于请求发出），Runtime 处于受租约保护的执行阶段。 */
  RUNNING,

  /** 模型调用已成功完成并生成完整响应。 */
  SUCCEEDED,

  /** 模型调用因明确错误失败收敛。 */
  FAILED,

  /** 模型调用已被主动取消。 */
  CANCELLED,

  /** 模型调用结果无法安全确定，收敛至不确定终态。 */
  UNKNOWN;

  /** 该状态是否为 terminal 状态。 */
  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == UNKNOWN;
  }
}
