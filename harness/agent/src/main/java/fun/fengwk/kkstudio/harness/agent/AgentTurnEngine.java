package fun.fengwk.kkstudio.harness.agent;

/** 单个 Assistant Turn 的执行边界。 */
public interface AgentTurnEngine {

  /** 执行一个 Turn 并立即返回取消句柄。 */
  AgentTurnHandle execute(AgentTurnRequest request, AgentTurnEventHandler handler);
}
