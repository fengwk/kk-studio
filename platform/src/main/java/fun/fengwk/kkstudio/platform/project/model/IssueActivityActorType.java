package fun.fengwk.kkstudio.platform.project.model;

/** Activity 操作者类型：人可经业务入口直接执行同一业务动作，不伪造 Agent Run。 */
public enum IssueActivityActorType {
  AGENT,
  HUMAN,
  SYSTEM
}
