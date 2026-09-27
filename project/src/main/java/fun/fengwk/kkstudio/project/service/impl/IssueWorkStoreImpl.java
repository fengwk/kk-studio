package fun.fengwk.kkstudio.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue 调度邮箱用例实现：lease/wake 围栏的薄业务边界。
 *
 * <p>只在内存层面校验 lease 与唤醒版本形状，真正的串行化由 {@code project_issue_work} 的行锁与条件更新保证。
 */
@Service
@AllArgsConstructor
public class IssueWorkStoreImpl implements IssueWorkStore {

  private static final int MAX_LEASE_TOKEN_LENGTH = 128;

  private final IssueWorkRepository repository;

  @Override
  @Transactional
  public IssueWork requestWork(UUID issueId, Instant dueAt) {
    Objects.requireNonNull(issueId, "issueId");
    Instant targetDue = dueAt != null ? dueAt : Instant.now();
    IssueWork work = repository.requestWork(issueId, targetDue);
    if (work == null) {
      throw new ProjectValidationException("issue_work", "Failed to request issue work");
    }
    return work;
  }

  @Override
  public IssueWork getWork(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    IssueWork work = repository.getById(issueId);
    if (work == null) {
      throw new ProjectNotFoundException("issue_work");
    }
    return work;
  }

  @Override
  @Transactional
  public Optional<IssueWork> claimNext(Instant now, String leaseToken, Instant leaseUntil) {
    Objects.requireNonNull(now, "now");
    String token = requireLeaseToken(leaseToken);
    Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (!leaseUntil.isAfter(now)) {
      throw new ProjectValidationException("issue_work", "leaseUntil must be after now");
    }
    return Optional.ofNullable(repository.claimNext(now, token, leaseUntil));
  }

  @Override
  @Transactional
  public void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    IssueWork work = repository.lockById(issueId);
    if (work == null) {
      throw new ProjectNotFoundException("issue_work");
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(token)) {
      throw new ProjectValidationException("issue_work", "Lease token mismatch for renewal");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new ProjectValidationException("issue_work", "Lease has already expired");
    }
    if (!newLeaseUntil.isAfter(work.getLeaseUntil())) {
      throw new ProjectValidationException(
          "issue_work", "newLeaseUntil must extend beyond the current leaseUntil");
    }
    if (!repository.renewLease(issueId, token, now, newLeaseUntil)) {
      throw new ProjectValidationException("issue_work", "Failed to renew issue work lease");
    }
  }

  @Override
  @Transactional
  public boolean completeWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    Objects.requireNonNull(now, "now");
    return repository.deleteIfWakeMatches(issueId, token, claimedWakeVersion, now);
  }

  @Override
  @Transactional
  public boolean rescheduleWork(UUID issueId, String leaseToken, Instant dueAt) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    Objects.requireNonNull(dueAt, "dueAt");
    return repository.releaseLease(issueId, token, dueAt);
  }

  private static String requireLeaseToken(String leaseToken) {
    if (leaseToken == null || leaseToken.isBlank()) {
      throw new ProjectValidationException("leaseToken", "leaseToken must not be blank");
    }
    if (leaseToken.length() > MAX_LEASE_TOKEN_LENGTH) {
      throw new ProjectValidationException("leaseToken", "leaseToken is too long");
    }
    return leaseToken;
  }
}
