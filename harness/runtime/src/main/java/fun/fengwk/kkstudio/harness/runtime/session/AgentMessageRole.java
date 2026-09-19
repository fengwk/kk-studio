package fun.fengwk.kkstudio.harness.runtime.session;

/** Session 中持久化的模型上下文消息角色；模型系统指令由每次请求单独承载。 */
public enum AgentMessageRole {
  USER,
  ASSISTANT,
  TOOL
}
