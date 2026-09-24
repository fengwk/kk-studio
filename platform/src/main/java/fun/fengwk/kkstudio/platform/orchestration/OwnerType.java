package fun.fengwk.kkstudio.platform.orchestration;

/** Chat/Canvas/IssueAgentSession 共享的 owner 类型。 */
public enum OwnerType {
  CHAT,
  CANVAS,
  /** 稳定的 Issue+Agent 归属：Session 与工作 Branch 随该归属长期存续，不随 Run 终结。 */
  ISSUE_AGENT_SESSION
}
