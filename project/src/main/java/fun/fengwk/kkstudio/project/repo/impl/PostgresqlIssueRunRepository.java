package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.impl.mapper.IssueRunMapper;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueRunDO;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueRunRepository implements IssueRunRepository {

  private final IssueRunMapper mapper;
  private final PostgresqlProjectChangeNotifier notifier;

  @Override
  public boolean insert(IssueRun run) {
    boolean changed = mapper.insert(toDO(run)) == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  @Override
  public IssueRun getById(UUID id) {
    return toModel(mapper.getById(id));
  }

  @Override
  public IssueRun getByIdAuthoritative(UUID id) {
    return toModel(mapper.getByIdAuthoritative(id));
  }

  @Override
  public IssueRun lockById(UUID id) {
    return toModel(mapper.lockById(id));
  }

  @Override
  public IssueRun getActiveByIssueId(UUID issueId) {
    return toModel(mapper.getActiveByIssueId(issueId));
  }

  @Override
  public IssueRun lockActiveByIssueId(UUID issueId) {
    return toModel(mapper.lockActiveByIssueId(issueId));
  }

  @Override
  public IssueRun getLatestByIssueId(UUID issueId) {
    return toModel(mapper.getLatestByIssueId(issueId));
  }

  @Override
  public List<IssueRun> listByIssueId(UUID issueId) {
    return mapper.listByIssueId(issueId).stream()
        .map(PostgresqlIssueRunRepository::toModel)
        .toList();
  }

  @Override
  public long countByIssueIdAndStateAfterOrdinal(UUID issueId, String state, long afterOrdinal) {
    return mapper.countByIssueIdAndStateAfterOrdinal(issueId, state, afterOrdinal);
  }

  @Override
  public boolean updateById(IssueRun run, long expectedVersion) {
    boolean changed = mapper.updateById(toDO(run), expectedVersion) == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    IssueRun run = getById(id);
    boolean changed = mapper.deleteById(id, expectedVersion) == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    int changed = mapper.deleteByIssueId(issueId);
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  private static IssueRunDO toDO(IssueRun run) {
    IssueRunDO row = new IssueRunDO();
    row.setId(run.getId());
    row.setIssueId(run.getIssueId());
    row.setOrdinal(run.getOrdinal());
    row.setState(run.getState());
    row.setSessionId(run.getSessionId());
    row.setThreadId(run.getThreadId());
    row.setStatus(run.getStatus() != null ? run.getStatus().name() : null);
    row.setStartEntryId(run.getStartEntryId());
    row.setEndEntryId(run.getEndEntryId());
    row.setFinalAnswerEntryId(run.getFinalAnswerEntryId());
    row.setNextState(run.getNextState());
    row.setObservedActivitySequence(run.getObservedActivitySequence());
    row.setRemainingExecutionMs(run.getRemainingExecutionMs());
    row.setActiveSince(run.getActiveSince());
    row.setError(run.getError());
    row.setVersion(run.getVersion());
    row.setStartedAt(run.getStartedAt());
    row.setEndedAt(run.getEndedAt());
    return row;
  }

  private static IssueRun toModel(IssueRunDO row) {
    if (row == null) {
      return null;
    }
    return IssueRun.builder()
        .id(row.getId())
        .issueId(row.getIssueId())
        .ordinal(row.getOrdinal() != null ? row.getOrdinal() : 0L)
        .state(row.getState())
        .sessionId(row.getSessionId())
        .threadId(row.getThreadId())
        .status(row.getStatus() != null ? IssueRunStatus.valueOf(row.getStatus()) : null)
        .startEntryId(row.getStartEntryId())
        .endEntryId(row.getEndEntryId())
        .finalAnswerEntryId(row.getFinalAnswerEntryId())
        .nextState(row.getNextState())
        .observedActivitySequence(
            row.getObservedActivitySequence() != null ? row.getObservedActivitySequence() : 0L)
        .remainingExecutionMs(
            row.getRemainingExecutionMs() != null ? row.getRemainingExecutionMs() : 0L)
        .activeSince(row.getActiveSince())
        .error(row.getError())
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .startedAt(row.getStartedAt())
        .endedAt(row.getEndedAt())
        .build();
  }
}
