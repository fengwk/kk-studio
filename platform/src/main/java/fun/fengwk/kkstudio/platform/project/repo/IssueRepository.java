package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.Issue;

import java.util.List;
import java.util.UUID;

public interface IssueRepository {

  boolean create(Issue issue);

  Issue getById(UUID id);

  Issue lockById(UUID id);

  Issue getByProjectAndNumber(UUID projectId, long number);

  List<Issue> listByProjectId(UUID projectId);

  List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived);

  boolean updateById(Issue issue, long expectedVersion);

  long incrementInputSequence(UUID id);

  boolean deleteById(UUID id, long expectedVersion);
}
