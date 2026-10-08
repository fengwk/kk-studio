package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.notification.ProjectChangeNotifier;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.impl.mapper.IssueActivityMapper;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueActivityDO;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueActivityRepository implements IssueActivityRepository {

  private final IssueActivityMapper mapper;
  private final ProjectChangeNotifier notifier;

  @Override
  public boolean insert(IssueActivity activity) {
    boolean changed = mapper.insert(toDO(activity)) == 1;
    if (changed) {
      notifier.issueChanged(activity.getIssueId());
    }
    return changed;
  }

  @Override
  public IssueActivity findByIdempotencyKey(UUID issueId, String idempotencyKey) {
    IssueActivityDO row = mapper.findByIdempotencyKey(issueId, idempotencyKey);
    return row == null ? null : toModel(row);
  }

  @Override
  public IssueActivity findByIdempotencyKeyUnderLock(UUID issueId, String idempotencyKey) {
    IssueActivityDO row = mapper.findByIdempotencyKeyUnderLock(issueId, idempotencyKey);
    return row == null ? null : toModel(row);
  }

  @Override
  public List<IssueActivity> listByIssueId(UUID issueId) {
    return mapper.listByIssueId(issueId).stream()
        .map(PostgresqlIssueActivityRepository::toModel)
        .toList();
  }

  @Override
  public long countByIssueId(UUID issueId) {
    return mapper.countByIssueId(issueId);
  }

  @Override
  public List<IssueActivity> listPage(UUID issueId, long afterSequence, int limit) {
    return mapper.listPage(issueId, afterSequence, limit).stream()
        .map(PostgresqlIssueActivityRepository::toModel)
        .toList();
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    int changed = mapper.deleteByIssueId(issueId);
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  private static IssueActivityDO toDO(IssueActivity activity) {
    IssueActivityDO row = new IssueActivityDO();
    row.setIssueId(activity.getIssueId());
    row.setSequence(activity.getSequence());
    row.setKind(activity.getKind() != null ? activity.getKind().name() : null);
    row.setActorType(activity.getActorType() != null ? activity.getActorType().name() : null);
    row.setActorAgentName(activity.getActorAgentName());
    row.setRunId(activity.getRunId());
    row.setBody(activity.getBody());
    row.setData(activity.getData());
    row.setIdempotencyKey(activity.getIdempotencyKey());
    row.setRequestHash(activity.getRequestHash());
    row.setCreatedAt(activity.getCreatedAt());
    return row;
  }

  private static IssueActivity toModel(IssueActivityDO row) {
    return IssueActivity.builder()
        .issueId(row.getIssueId())
        .sequence(row.getSequence() != null ? row.getSequence() : 0L)
        .kind(row.getKind() != null ? IssueActivityKind.valueOf(row.getKind()) : null)
        .actorType(
            row.getActorType() != null ? IssueActivityActorType.valueOf(row.getActorType()) : null)
        .actorAgentName(row.getActorAgentName())
        .runId(row.getRunId())
        .body(row.getBody())
        .data(row.getData())
        .idempotencyKey(row.getIdempotencyKey())
        .requestHash(row.getRequestHash())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
