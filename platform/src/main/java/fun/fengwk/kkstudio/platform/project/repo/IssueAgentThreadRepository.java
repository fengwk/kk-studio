package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;

import java.util.UUID;

/**
 * {@code project_issue_agent_thread} 稳定归属的持久化端口。
 *
 * <p>绑定只保存 {@code (issueId, agentName) -> threadId}，Session 由 Thread
 * 解析；绑定不可重绑，插入冲突必须显式失败而不是静默复用别的绑定。 删除必须显式进行：Run 对绑定的复合 FK 是 RESTRICT，因此存在 Run 时删除会失败，绝不绕过业务清理硬删已绑定的
 * Thread 与 Session。
 */
public interface IssueAgentThreadRepository {

  /** 读取稳定绑定；不存在返回 {@code null}。 */
  IssueAgentThread findByIssueIdAndAgentName(UUID issueId, String agentName);

  /**
   * 插入稳定绑定；同一 {@code (issueId, agentName)} 或同一 Thread 已被绑定时抛 {@code
   * DataIntegrityViolationException}，绝不静默复用既有绑定。
   *
   * <p>该写入由 Issue+Agent 的产品接受路径在 Harness NEW_SESSION 接受返回后、同一物理事务内执行（设计 §7.1）：此时 {@code
   * harness_thread} 行已存在，行内复合 FK 立即成立，因此不需要延迟 FK 或预创建空会话。
   */
  boolean insert(IssueAgentThread binding);

  /** 删除稳定绑定并返回删除行数（深删除时必须先于 harness_thread 行删除）。 */
  int deleteByIssueIdAndAgentName(UUID issueId, String agentName);
}
