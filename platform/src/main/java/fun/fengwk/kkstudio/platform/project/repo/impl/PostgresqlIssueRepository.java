package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived) {
    return issueMapper.listByProjectIdAndArchived(projectId, archived).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean updateById(Issue issue, long expectedVersion) {
    return issueMapper.updateById(toDO(issue), expectedVersion) == 1;
  }

  @Override
  public long incrementInputSequence(UUID id) {
    Long seq = issueMapper.incrementInputSequence(id);
    if (seq == null) {
      throw new IllegalStateException("Failed to increment issue input sequence");
    }
    return seq;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    return issueMapper.deleteById(id, expectedVersion) == 1;
  }

  private IssueDO toDO(Issue issue) {
    if (issue == null) {
      return null;
    }
    IssueDO target = new IssueDO();
    target.setId(issue.getId());
    target.setProjectId(issue.getProjectId());
    target.setNumber(issue.getNumber());
    target.setTitle(issue.getTitle());
    target.setDescription(issue.getDescription());
    target.setStatus(issue.getStatus() != null ? issue.getStatus().name() : null);
    target.setAssigneeAgentName(issue.getAssigneeAgentName());
    target.setReviewerAgentName(issue.getReviewerAgentName());
    target.setVersion(issue.getVersion());
    target.setSpecRevision(issue.getSpecRevision());
    target.setInputSequence(issue.getInputSequence());
    target.setArchivedAt(issue.getArchivedAt());
    target.setCreatedAt(issue.getCreatedAt());
    target.setUpdatedAt(issue.getUpdatedAt());
    return target;
  }

  private Issue toModel(IssueDO row) {
    if (row == null) {
      return null;
    }
    return Issue.builder()
        .id(row.getId())
        .projectId(row.getProjectId())
        .number(row.getNumber() != null ? row.getNumber() : 0L)
        .title(row.getTitle())
        .description(row.getDescription())
        .status(row.getStatus() != null ? IssueStatus.valueOf(row.getStatus()) : null)
        .assigneeAgentName(row.getAssigneeAgentName())
        .reviewerAgentName(row.getReviewerAgentName())
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .specRevision(row.getSpecRevision() != null ? row.getSpecRevision() : 0L)
        .inputSequence(row.getInputSequence() != null ? row.getInputSequence() : 0L)
        .archivedAt(row.getArchivedAt())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
