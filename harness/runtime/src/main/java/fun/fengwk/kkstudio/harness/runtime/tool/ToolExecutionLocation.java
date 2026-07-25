package fun.fengwk.kkstudio.harness.runtime.tool;

/**
 * 持久 ToolInvocation / ToolBinding 的已冻结执行路由位置，不属于 Tool API 功能描述。
 *
 * <p>{@link #PLATFORM} 由平台 worker 领取（控制面 Tool 与平台托管云端 Tool 共用平台执行路径）。{@link #ENVIRONMENT} 必须绑定具体
 * environmentName，由对应 Environment Daemon 领取。
 */
public enum ToolExecutionLocation {
  /** 平台侧执行：控制面与云端托管 Tool 均走 PLATFORM worker。 */
  PLATFORM,
  /** 指定 Environment Daemon 中的 Tool，必须同时冻结 environmentName。 */
  ENVIRONMENT
}
