package fun.fengwk.kkstudio.platform.harness.thread.command;

/**
 * Agent 目录快照的确定性规划拒绝：只由显式校验失败产生，绝不包装基础设施或编程异常。
 *
 * <p>Builtin resolver 把它投影为稳定的 {@code PLANNING_FAILED}。调用方不得把它当成可重试的 transient 失败。
 */
public final class AgentTurnContextException extends RuntimeException {

  public AgentTurnContextException(String message) {
    super(message);
  }
}
