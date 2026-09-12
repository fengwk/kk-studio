package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueRun;

import java.util.List;
import java.util.UUID;

public interface IssueRunRepository {

  boolean create(IssueRun run);

  IssueRun getById(UUID id);

  IssueRun lockById(UUID id);

  IssueRun findActiveByIssueId(UUID issueId);

  IssueRun findLatestByIssueId(UUID issueId);

  List<IssueRun> listByIssueId(UUID issueId);

  long allocateNextOrdinal(UUID issueId);

  boolean updateById(IssueRun run, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
