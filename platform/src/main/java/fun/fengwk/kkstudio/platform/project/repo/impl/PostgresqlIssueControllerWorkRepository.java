package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;
import fun.fengwk.kkstudio.platform.project.repo.IssueControllerWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueControllerWorkMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueControllerWorkDO;

import java.time.Instant;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueControllerWorkRepository implements IssueControllerWorkRepository {

  private final IssueControllerWorkMapper mapper;

  @Override
  public IssueControllerWork getById(UUID issueId) {
    return toModel(mapper.getById(issueId));
  }

  @Override
  public IssueControllerWork lockById(UUID issueId) {
    return toModel(mapper.lockById(issueId));
  }

  @Override
  public IssueControllerWork requestWork(UUID issueId, Instant dueAt) {
    return toModel(mapper.upsertRequest(issueId, dueAt));
  }

  @Override
  public IssueControllerWork claimNext(Instant now, String leaseToken, Instant leaseUntil) {
    return toModel(mapper.claimNext(now, leaseToken, leaseUntil));
  }

  @Override
  public boolean renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil) {
    return mapper.renewLease(issueId, leaseToken, now, newLeaseUntil) == 1;
  }

  @Override
  public boolean deleteIfWakeMatches(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
    return mapper.deleteIfWakeMatches(issueId, leaseToken, claimedWakeVersion, now) == 1;
  }

  @Override
  public boolean clearLeaseIfWakeNewer(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
    return mapper.clearLeaseIfWakeNewer(issueId, leaseToken, claimedWakeVersion, now) == 1;
  }

  @Override
  public boolean reschedule(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt) {
    return mapper.reschedule(issueId, leaseToken, claimedWakeVersion, now, requestedAt) == 1;
  }

  private IssueControllerWork toModel(IssueControllerWorkDO row) {
    if (row == null) {
      return null;
    }
    return IssueControllerWork.builder()
        .issueId(row.getIssueId())
        .wakeVersion(row.getWakeVersion() != null ? row.getWakeVersion() : 0L)
        .dueAt(row.getDueAt())
        .leaseToken(row.getLeaseToken())
        .leaseUntil(row.getLeaseUntil())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
