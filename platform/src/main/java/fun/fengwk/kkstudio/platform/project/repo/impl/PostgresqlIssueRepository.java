package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueDO;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueRepository implements IssueRepository {

  private final IssueMapper issueMapper;

  @Override
  public boolean create(Issue issue) {
    return issueMapper.insert(toDO(issue)) == 1;
  }

  @Override
  public Issue getById(UUID id) {
    return toModel(issueMapper.getById(id));
  }

  @Override
  public Issue lockById(UUID id) {
    return toModel(issueMapper.lockById(id));
  }

  @Override
  public Issue getByProjectAndNumber(UUID projectId, long number) {
    return toModel(issueMapper.getByProjectAndNumber(projectId, number));
  }

  @Override
  public List<Issue> listByProjectId(UUID projectId) {
    return issueMapper.listByProjectId(projectId).stream()
        .map(PostgresqlIssueRepository::toModel)
        .toList();
  }

  @Override
  public List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived) {
    return issueMapper.listByProjectIdAndArchived(projectId, archived).stream()
        .map(PostgresqlIssueRepository::toModel)
        .toList();
  }

  @Override
  public boolean updateById(Issue issue, long expectedVersion) {
    return issueMapper.updateById(toDO(issue), expectedVersion) == 1;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    return issueMapper.deleteById(id, expectedVersion) == 1;
  }

  private static IssueDO toDO(Issue issue) {
    if (issue == null) {
      return null;
    }
    IssueDO row = new IssueDO();
    row.setId(issue.getId());
    row.setProjectId(issue.getProjectId());
    row.setNumber(issue.getNumber());
    row.setTitle(issue.getTitle());
    row.setDescription(issue.getDescription());
    row.setState(issue.getState());
    row.setBlockedFromState(issue.getBlockedFromState());
    row.setBlockReason(issue.getBlockReason());
    row.setPauseReason(issue.getPauseReason());
    row.setPauseDetail(issue.getPauseDetail());
    row.setNextRunOrdinal(issue.getNextRunOrdinal());
    row.setNextActivitySequence(issue.getNextActivitySequence());
    row.setVersion(issue.getVersion());
    row.setArchivedAt(issue.getArchivedAt());
    row.setCreatedAt(issue.getCreatedAt());
    row.setUpdatedAt(issue.getUpdatedAt());
    return row;
  }

  private static Issue toModel(IssueDO row) {
    if (row == null) {
      return null;
    }
    return Issue.builder()
        .id(row.getId())
        .projectId(row.getProjectId())
        .number(row.getNumber() != null ? row.getNumber() : 0L)
        .title(row.getTitle())
        .description(row.getDescription())
        .state(row.getState())
        .blockedFromState(row.getBlockedFromState())
        .blockReason(row.getBlockReason())
        .pauseReason(row.getPauseReason())
        .pauseDetail(row.getPauseDetail())
        .nextRunOrdinal(row.getNextRunOrdinal() != null ? row.getNextRunOrdinal() : 1L)
        .nextActivitySequence(
            row.getNextActivitySequence() != null ? row.getNextActivitySequence() : 1L)
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .archivedAt(row.getArchivedAt())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
