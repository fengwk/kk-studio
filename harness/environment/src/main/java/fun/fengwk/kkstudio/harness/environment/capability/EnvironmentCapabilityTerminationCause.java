package fun.fengwk.kkstudio.harness.environment.capability;

/**
 * 运行时请求能力收尾的明确原因。
 *
 * <p>布尔或字符串标记无法让能力区分「运行时判定超时」与「调用方主动取消」，而能力必须按真实原因报告自己的收尾事实（例如进程是被超时终止还是被取消终止），
 * 因此运行时用本枚举发起收尾请求，能力据此产出与原因一致的终态说明。
 */
public enum EnvironmentCapabilityTerminationCause {

  /** 调用超过了运行时登记的执行 deadline。 */
  TIMED_OUT,

  /** 调用方主动取消，或运行时因协议违规强制收敛。 */
  CANCELLED
}
