package fun.fengwk.kkstudio.project.repo;

import fun.fengwk.kkstudio.project.model.Issue;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue} 聚合根持久化端口。
 *
 * <p>只有在事务内先以 {@link #lockById(UUID)} 取得行级 UPDATE 锁，才能以版本 CAS 写回状态、门禁与分配游标。
 */
public interface IssueRepository {

  boolean create(Issue issue);

  Issue getById(UUID id);

  /**
   * 读取当前已提交的 Issue 行，绕过事务内 MyBatis 一级缓存。
   *
   * <p>事务早先读到的旧快照不得遮蔽并发已提交的版本推进：请求键重放必须在观察到 receipt 之后返回这个权威事实。
   */
  Issue getByIdAuthoritative(UUID id);

  Issue lockById(UUID id);

  Issue getByProjectAndNumber(UUID projectId, long number);

  List<Issue> listByProjectId(UUID projectId);

  List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived);

  /** 以版本 CAS 更新状态、阻塞恢复点、暂停门禁与编号/序号分配游标。 */
  boolean updateById(Issue issue, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
