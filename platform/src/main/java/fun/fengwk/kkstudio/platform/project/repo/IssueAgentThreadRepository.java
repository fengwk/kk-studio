package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;

import java.util.List;
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

  /** 读取该 Issue 的全部稳定绑定：Thread 查询面按 Agent 名称顺序返回，不泄漏别的 Issue。 */
  List<IssueAgentThread> listByIssueId(UUID issueId);

  /**
   * 按 Harness Thread 反向读取稳定绑定；该 Thread 未绑定时返回 {@code null}。
   *
   * <p>Thread 身份全局唯一（{@code uk_project_issue_agent_thread_thread}），因此这是产品控制面（Turn 规划与交接）把 Harness
   * Thread 解析回唯一 Issue+Agent 归属的唯一入口；不能靠 Session 或阶段字符串推断归属。
   */
  IssueAgentThread findByThreadId(UUID threadId);

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

  /**
   * 判定一个 Harness Thread 是否属于任何 Issue+Agent 稳定归属：包含同一 Session 的兄弟分支。
   *
   * <p>只读取持久化归属事实（{@code project_issue_agent_thread} 与其 Harness Session 归属），不触达运行中的 Runtime；Thread
   * 不存在或没有任何归属都返回 false，绝不猜成别的 owner。
   */
  boolean isIssueAgentBranch(UUID threadId);
}
