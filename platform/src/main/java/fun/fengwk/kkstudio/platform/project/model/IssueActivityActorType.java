package fun.fengwk.kkstudio.platform.project.model;

/** Issue 活动的操作者类型。 */
public enum IssueActivityActorType {
  /** 人类操作者。 */
  HUMAN,

  /** Agent，必须携带 {@code actorAgentName}。 */
  AGENT,

  /** 系统生成，不带 Agent 作者。 */
  SYSTEM
}
