package fun.fengwk.kkstudio.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import java.time.Duration;
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
  public IssueWork requestWork(UUID issueId, Duration delay) {
    Objects.requireNonNull(issueId, "issueId");
    requireDuration(delay, false);
    IssueWork work = repository.requestWork(issueId, delay);
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
  public Optional<IssueWork> claimNext(String leaseToken, Duration leaseDuration) {
    String token = requireLeaseToken(leaseToken);
    requireDuration(leaseDuration, true);
    return Optional.ofNullable(repository.claimNext(token, leaseDuration));
  }

  @Override
  @Transactional
  public void renewLease(UUID issueId, String leaseToken, Duration leaseDuration) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    requireDuration(leaseDuration, true);
    // 在 reconciler 的外层事务中，这个锁从 heartbeat 一直持有到 finish。
    IssueWork work = repository.lockById(issueId);
    if (work == null) {
      throw new ProjectNotFoundException("issue_work");
    }
    if (!repository.renewLease(issueId, token, leaseDuration)) {
      throw new ProjectValidationException("issue_work", "Failed to renew issue work lease");
    }
  }

  @Override
  @Transactional
  public boolean completeWork(UUID issueId, String leaseToken, long claimedWakeVersion) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    return repository.completeWork(issueId, token, claimedWakeVersion);
  }

  @Override
  @Transactional
  public boolean rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Duration delay) {
    Objects.requireNonNull(issueId, "issueId");
    String token = requireLeaseToken(leaseToken);
    requireDuration(delay, false);
    return repository.rescheduleWork(issueId, token, claimedWakeVersion, delay);
  }

  private static void requireDuration(Duration duration, boolean lease) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative() || (lease && duration.toMillis() == 0)) {
      throw new ProjectValidationException(
          "issue_work",
          lease ? "leaseDuration must be at least 1ms" : "delay must not be negative");
    }
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
