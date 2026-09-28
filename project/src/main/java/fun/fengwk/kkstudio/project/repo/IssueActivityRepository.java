package fun.fengwk.kkstudio.project.repo;

import fun.fengwk.kkstudio.project.model.IssueActivity;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_activity} 持久化端口：有序、幂等的 Issue 事实流。
 *
 * <p>序号由 Issue 行的分配游标决定；同一 Issue 的 {@code idempotencyKey} 唯一决定重放语义。
 */
public interface IssueActivityRepository {

  boolean insert(IssueActivity activity);

  /** 按同 Issue 的请求键读取既有活动；用于请求键去重（fail closed，不静默重放）。 */
  IssueActivity findByIdempotencyKey(UUID issueId, String idempotencyKey);

  /**
   * 在已经持有 Issue 行锁的同一语句中读取 receipt。
   *
   * <p>单独的后续查询在 READ COMMITTED 下仍可能使用锁等待开始前的快照，从而漏看等待期间提交的活动。
   */
  IssueActivity findByIdempotencyKeyForUpdate(UUID issueId, String idempotencyKey);

  List<IssueActivity> listByIssueId(UUID issueId);

  /** 按序号升序读取有界窗口 {@code (afterSequence, ...]}：投递游标只推进真正检视过的窗口。 */
  List<IssueActivity> listPage(UUID issueId, long afterSequence, int limit);

  long countByIssueId(UUID issueId);

  int deleteByIssueId(UUID issueId);
}
