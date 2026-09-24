package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueRun;

import java.util.List;
import java.util.UUID;

public interface IssueRunRepository {

  boolean create(IssueRun run);

  IssueRun getById(UUID id);

  IssueRun lockById(UUID id);

  IssueRun findActiveByIssueId(UUID issueId);

  IssueRun lockActiveByIssueId(UUID issueId);

  /** 该 Project 下是否存在活动 Run（任一 Issue、任一角色的 RUNNING / WAITING_HUMAN）。 */
  boolean hasActiveByProjectId(UUID projectId);

  IssueRun findByTerminalActionId(String terminalActionId);

  IssueRun findLatestByIssueId(UUID issueId);

  List<IssueRun> listByIssueId(UUID issueId);

  long allocateNextOrdinal(UUID issueId);

  boolean updateById(IssueRun run, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
