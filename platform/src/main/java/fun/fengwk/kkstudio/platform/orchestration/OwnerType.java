package fun.fengwk.kkstudio.platform.orchestration;

/**
 * 产品 owner 类型的唯一判别符。
 *
 * <p>只有真正持有 Harness Session 的产品归属才是 owner：Chat 通过 {@code chat_session} 直接持有；Issue+Agent 通过 {@code
 * project_issue_agent_thread} 的 {@code (issue_id, agent_name) -> thread_id} 稳定绑定持有，Session 由该
 * Thread 解析。Canvas 不持有任何 Harness Session，因此没有 Canvas owner 形态。
 */
public enum OwnerType {
  CHAT,
  /** 稳定的 Issue+Agent 归属：Thread 与 Session 随该归属长期存续，不随 Run 终结，也不重绑。 */
  ISSUE_AGENT
}
