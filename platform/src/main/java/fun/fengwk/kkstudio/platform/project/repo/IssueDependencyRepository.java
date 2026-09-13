package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueDependency;

import java.util.List;
import java.util.UUID;

public interface IssueDependencyRepository {

  boolean addDependency(IssueDependency dependency);

  boolean removeDependency(UUID issueId, UUID dependsOnIssueId);

  List<IssueDependency> listByIssueId(UUID issueId);

  List<IssueDependency> listByDependsOnIssueId(UUID dependsOnIssueId);

  List<IssueDependency> listByProjectId(UUID projectId);

  boolean checkHasPath(UUID fromIssueId, UUID toIssueId);

  int deleteByProjectId(UUID projectId);
}
