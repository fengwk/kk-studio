package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueActivityMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueActivityDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueActivityRepository implements IssueActivityRepository {

  private final IssueActivityMapper issueActivityMapper;

  @Override
  public IssueActivity appendOrGet(IssueActivity activity) {
    if (activity == null) {
      return null;
    }
    String idempotencyKey = activity.getIdempotencyKey();
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      IssueActivityDO existing =
          issueActivityMapper.findByIssueIdAndIdempotencyKey(activity.getIssueId(), idempotencyKey);
      if (existing != null) {
        return toModel(existing);
      }
    }

    int maxRetries = 5;
    for (int attempt = 0; attempt < maxRetries; attempt++) {
      try {
        IssueActivityDO inserted = issueActivityMapper.insert(toDO(activity));
        if (inserted != null) {
          return toModel(inserted);
        }
      } catch (DuplicateKeyException ex) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
          IssueActivityDO existing =
              issueActivityMapper.findByIssueIdAndIdempotencyKey(
                  activity.getIssueId(), idempotencyKey);
          if (existing != null) {
            return toModel(existing);
          }
        }
        if (attempt == maxRetries - 1) {
          throw ex;
        }
      }
    }
    throw new IllegalStateException("Failed to append issue activity after retries");
  }

  @Override
  public IssueActivity findByIssueIdAndIdempotencyKey(UUID issueId, String idempotencyKey) {
    return toModel(issueActivityMapper.findByIssueIdAndIdempotencyKey(issueId, idempotencyKey));
  }

  @Override
  public List<IssueActivity> listByIssueId(UUID issueId) {
    return issueActivityMapper.listByIssueId(issueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public List<IssueActivity> listPage(UUID issueId, long afterSequence, int limit) {
    return issueActivityMapper.listPage(issueId, afterSequence, limit).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean existsByIssueIdAndKind(UUID issueId, IssueActivityKind kind) {
    return issueActivityMapper.existsByIssueIdAndKind(issueId, kind != null ? kind.name() : null);
  }

  @Override
  public boolean existsAfterSequenceAndKind(
      UUID issueId, long afterSequence, IssueActivityKind kind) {
    return issueActivityMapper.existsAfterSequenceAndKind(
        issueId, afterSequence, kind != null ? kind.name() : null);
  }

  @Override
  public long findReviewWindowStartSequence(UUID issueId) {
    return issueActivityMapper.findReviewWindowStartSequence(issueId);
  }

  @Override
  public long countRejectionsSince(UUID issueId, long afterSequence) {
    return issueActivityMapper.countRejectionsSince(issueId, afterSequence);
  }

  @Override
  public long countRejectionsSince(
      UUID issueId, long afterSequence, IssueActivityActorType actorType) {
    return issueActivityMapper.countRejectionsSinceWithActorType(
        issueId, afterSequence, actorType != null ? actorType.name() : null);
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    return issueActivityMapper.deleteByIssueId(issueId);
  }

  private IssueActivityDO toDO(IssueActivity activity) {
    if (activity == null) {
      return null;
    }
    IssueActivityDO target = new IssueActivityDO();
    target.setIssueId(activity.getIssueId());
    target.setSequence(activity.getSequence() > 0 ? activity.getSequence() : null);
    target.setKind(activity.getKind() != null ? activity.getKind().name() : null);
    target.setActorType(activity.getActorType() != null ? activity.getActorType().name() : null);
    target.setActorAgentName(activity.getActorAgentName());
    target.setTargetRole(activity.getTargetRole() != null ? activity.getTargetRole().name() : null);
    target.setRunId(activity.getRunId());
    target.setSubmissionRunId(activity.getSubmissionRunId());
    target.setDecision(activity.getDecision() != null ? activity.getDecision().name() : null);
    target.setBody(activity.getBody() != null ? activity.getBody() : "");
    target.setIdempotencyKey(activity.getIdempotencyKey());
    target.setCreatedAt(activity.getCreatedAt());
    return target;
  }

  private IssueActivity toModel(IssueActivityDO row) {
    if (row == null) {
      return null;
    }
    return IssueActivity.builder()
        .issueId(row.getIssueId())
        .sequence(row.getSequence() != null ? row.getSequence() : 0L)
        .kind(row.getKind() != null ? IssueActivityKind.valueOf(row.getKind()) : null)
        .actorType(
            row.getActorType() != null ? IssueActivityActorType.valueOf(row.getActorType()) : null)
        .actorAgentName(row.getActorAgentName())
        .targetRole(row.getTargetRole() != null ? IssueRunRole.valueOf(row.getTargetRole()) : null)
        .runId(row.getRunId())
        .submissionRunId(row.getSubmissionRunId())
        .decision(row.getDecision() != null ? ReviewDecision.valueOf(row.getDecision()) : null)
        .body(row.getBody())
        .idempotencyKey(row.getIdempotencyKey())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
