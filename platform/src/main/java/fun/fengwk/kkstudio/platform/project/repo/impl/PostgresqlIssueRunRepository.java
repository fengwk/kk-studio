package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueRunMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueRunDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueRunRepository implements IssueRunRepository {

  private final IssueRunMapper issueRunMapper;

  @Override
  public boolean create(IssueRun run) {
    return issueRunMapper.insert(toDO(run)) == 1;
  }

  @Override
  public IssueRun getById(UUID id) {
    return toModel(issueRunMapper.getById(id));
  }

  @Override
  public IssueRun lockById(UUID id) {
    return toModel(issueRunMapper.lockById(id));
  }

  @Override
  public IssueRun findActiveByIssueId(UUID issueId) {
    return toModel(issueRunMapper.findActiveByIssueId(issueId));
  }

  @Override
  public IssueRun findLatestByIssueId(UUID issueId) {
    return toModel(issueRunMapper.findLatestByIssueId(issueId));
  }

  @Override
  public List<IssueRun> listByIssueId(UUID issueId) {
    return issueRunMapper.listByIssueId(issueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public long allocateNextOrdinal(UUID issueId) {
    Long next = issueRunMapper.allocateNextOrdinal(issueId);
    if (next == null) {
      throw new IllegalStateException("Failed to allocate next ordinal for issue " + issueId);
    }
    return next;
  }

  @Override
  public boolean updateById(IssueRun run, long expectedVersion) {
    return issueRunMapper.updateById(toDO(run), expectedVersion) == 1;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    return issueRunMapper.deleteById(id, expectedVersion) == 1;
  }

  private IssueRunDO toDO(IssueRun run) {
    if (run == null) {
      return null;
    }
    IssueRunDO target = new IssueRunDO();
    target.setId(run.getId());
    target.setIssueId(run.getIssueId());
    target.setOrdinal(run.getOrdinal());
    target.setRole(run.getRole() != null ? run.getRole().name() : null);
    target.setActorType(run.getActorType() != null ? run.getActorType().name() : null);
    target.setAgentName(run.getAgentName());
    target.setSubmissionRunId(run.getSubmissionRunId());
    target.setStatus(run.getStatus() != null ? run.getStatus().name() : null);
    target.setOutcome(run.getOutcome() != null ? run.getOutcome().name() : null);
    target.setObservedSpecRevision(run.getObservedSpecRevision());
    target.setObservedInputSequence(run.getObservedInputSequence());
    target.setContinuationCount(run.getContinuationCount());
    target.setMaxContinuations(run.getMaxContinuations());
    target.setDeadline(run.getDeadline());
    target.setWaitingReason(run.getWaitingReason());
    target.setResult(run.getResult());
    target.setTerminalActionId(run.getTerminalActionId());
    target.setVersion(run.getVersion());
    target.setCreatedAt(run.getCreatedAt());
    target.setUpdatedAt(run.getUpdatedAt());
    target.setCompletedAt(run.getCompletedAt());
    return target;
  }

  private IssueRun toModel(IssueRunDO row) {
    if (row == null) {
      return null;
    }
    return IssueRun.builder()
        .id(row.getId())
        .issueId(row.getIssueId())
        .ordinal(row.getOrdinal() != null ? row.getOrdinal() : 0L)
        .role(row.getRole() != null ? IssueRunRole.valueOf(row.getRole()) : null)
        .actorType(
            row.getActorType() != null ? IssueRunActorType.valueOf(row.getActorType()) : null)
        .agentName(row.getAgentName())
        .submissionRunId(row.getSubmissionRunId())
        .status(row.getStatus() != null ? IssueRunStatus.valueOf(row.getStatus()) : null)
        .outcome(row.getOutcome() != null ? IssueRunOutcome.valueOf(row.getOutcome()) : null)
        .observedSpecRevision(
            row.getObservedSpecRevision() != null ? row.getObservedSpecRevision() : 0L)
        .observedInputSequence(
            row.getObservedInputSequence() != null ? row.getObservedInputSequence() : 0L)
        .continuationCount(row.getContinuationCount() != null ? row.getContinuationCount() : 0)
        .maxContinuations(row.getMaxContinuations() != null ? row.getMaxContinuations() : 10)
        .deadline(row.getDeadline())
        .waitingReason(row.getWaitingReason())
        .result(row.getResult())
        .terminalActionId(row.getTerminalActionId())
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .completedAt(row.getCompletedAt())
        .build();
  }
}
