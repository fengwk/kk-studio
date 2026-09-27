package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.Issue;

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

  Issue lockById(UUID id);

  Issue getByProjectAndNumber(UUID projectId, long number);

  List<Issue> listByProjectId(UUID projectId);

  List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived);

  /** 以版本 CAS 更新状态、阻塞恢复点、暂停门禁与编号/序号分配游标。 */
  boolean updateById(Issue issue, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
