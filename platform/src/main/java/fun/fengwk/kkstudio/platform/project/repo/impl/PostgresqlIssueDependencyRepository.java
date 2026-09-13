package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueDependencyMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueDependencyDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueDependencyRepository implements IssueDependencyRepository {

  private final IssueDependencyMapper issueDependencyMapper;

  @Override
  public boolean addDependency(IssueDependency dependency) {
    return issueDependencyMapper.insert(toDO(dependency)) == 1;
  }

  @Override
  public boolean removeDependency(UUID issueId, UUID dependsOnIssueId) {
    return issueDependencyMapper.delete(issueId, dependsOnIssueId) == 1;
  }

  @Override
  public List<IssueDependency> listByIssueId(UUID issueId) {
    return issueDependencyMapper.listByIssueId(issueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<IssueDependency> listByDependsOnIssueId(UUID dependsOnIssueId) {
    return issueDependencyMapper.listByDependsOnIssueId(dependsOnIssueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<IssueDependency> listByProjectId(UUID projectId) {
    return issueDependencyMapper.listByProjectId(projectId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean checkHasPath(UUID fromIssueId, UUID toIssueId) {
    return issueDependencyMapper.checkHasPath(fromIssueId, toIssueId);
  }

  @Override
  public int deleteByProjectId(UUID projectId) {
    return issueDependencyMapper.deleteByProjectId(projectId);
  }

  private IssueDependencyDO toDO(IssueDependency dependency) {
    if (dependency == null) {
      return null;
    }
    IssueDependencyDO target = new IssueDependencyDO();
    target.setIssueId(dependency.getIssueId());
    target.setDependsOnIssueId(dependency.getDependsOnIssueId());
    target.setProjectId(dependency.getProjectId());
    target.setCreatedAt(dependency.getCreatedAt());
    return target;
  }

  private IssueDependency toModel(IssueDependencyDO row) {
    if (row == null) {
      return null;
    }
    return IssueDependency.builder()
        .issueId(row.getIssueId())
        .dependsOnIssueId(row.getDependsOnIssueId())
        .projectId(row.getProjectId())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
