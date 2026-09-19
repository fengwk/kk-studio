package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** Provider 上下文消息角色；系统指令单独承载，不出现在会话消息中。 */
public enum ProviderMessageRole {
  USER,
  ASSISTANT,
  TOOL
}
