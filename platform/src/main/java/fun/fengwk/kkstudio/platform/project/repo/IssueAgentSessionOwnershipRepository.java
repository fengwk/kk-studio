package fun.fengwk.kkstudio.platform.project.repo;

import java.util.List;
import java.util.UUID;

/**
 * {@code session_owner.issue_agent_session_id} 归属边的持久化端口。
 *
 * <p>Session 的产品归属是全局互斥弧：一个 Session 至多被一个 Chat、Canvas 或 Issue+Agent 归属持有；该归属行随 IssueAgentSession
 * 稳定存在并复用于后续 Run。
 */
public interface IssueAgentSessionOwnershipRepository {

  /**
   * 插入归属边（{@code created_at} 由数据库默认填充）；Session 已由任一 owner 持有时抛 {@code
   * DataIntegrityViolationException}。
   */
  boolean insert(UUID sessionId, UUID issueAgentSessionId);

  /** 按 Session id 读取归属的 IssueAgentSession id；不存在返回 {@code null}。 */
  UUID findAgentSessionIdBySessionId(UUID sessionId);

  /** 枚举某 Issue+Agent 归属持有的全部 Session id，按归属时间倒序。 */
  List<UUID> listSessionIds(UUID issueAgentSessionId);

  /** 删除指定 Session 的归属边并返回删除行数（深删除时先于 Session 行删除）。 */
  int deleteBySessionId(UUID sessionId);
}
