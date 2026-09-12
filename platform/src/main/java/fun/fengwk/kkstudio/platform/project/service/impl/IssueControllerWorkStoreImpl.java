package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;
import fun.fengwk.kkstudio.platform.project.repo.IssueControllerWorkRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueControllerWorkStoreImpl implements IssueControllerWorkStore {

  private final IssueControllerWorkRepository repository;

  @Transactional
  @Override
  public IssueControllerWork requestWork(UUID issueId, Instant dueAt) {
    Objects.requireNonNull(issueId, "issueId");
    Instant targetDue = dueAt != null ? dueAt : Instant.now();
    return repository.requestWork(issueId, targetDue);
  }

  @Transactional
  @Override
  public Optional<ClaimedControllerWork> claimNext(
      Instant now, String leaseToken, Instant leaseUntil) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (!leaseUntil.isAfter(now)) {
      throw new AiValidationException("controller_work", "leaseUntil must be after now");
    }
    IssueControllerWork claimed = repository.claimNext(now, leaseToken, leaseUntil);
    if (claimed == null) {
      return Optional.empty();
    }
    return Optional.of(
        ClaimedControllerWork.builder()
            .issueId(claimed.getIssueId())
            .claimedWakeVersion(claimed.getWakeVersion())
            .leaseToken(claimed.getLeaseToken())
            .leaseUntil(claimed.getLeaseUntil())
            .build());
  }

  @Transactional
  @Override
  public void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    IssueControllerWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("controller_work", issueId.toString());
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(leaseToken)) {
      throw new AiValidationException("controller_work", "Lease token mismatch for renewal");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("controller_work", "Lease expired at current time");
    }
    if (!newLeaseUntil.isAfter(work.getLeaseUntil())) {
      throw new AiValidationException(
          "controller_work", "newLeaseUntil must extend beyond current leaseUntil");
    }
    boolean renewed = repository.renewLease(issueId, leaseToken, now, newLeaseUntil);
    if (!renewed) {
      throw new AiValidationException("controller_work", "Failed to renew controller work lease");
    }
  }

  @Transactional
  @Override
  public void completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(now, "now");
    IssueControllerWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("controller_work", issueId.toString());
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(leaseToken)) {
      throw new AiValidationException("controller_work", "Lease token mismatch for completion");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("controller_work", "Lease expired at current time");
    }
    if (claimedWakeVersion > work.getWakeVersion()) {
      throw new AiValidationException(
          "controller_work", "Claimed wake version exceeds current wake version");
    }
    if (claimedWakeVersion == work.getWakeVersion()) {
      boolean deleted =
          repository.deleteIfWakeMatches(issueId, leaseToken, claimedWakeVersion, now);
      if (!deleted) {
        throw new AiValidationException("controller_work", "Failed to complete controller work");
      }
    } else {
      // 存在更新的 wake 请求，清除 lease 保留行
      boolean cleared =
          repository.clearLeaseIfWakeNewer(issueId, leaseToken, claimedWakeVersion, now);
      if (!cleared) {
        throw new AiValidationException(
            "controller_work", "Failed to clear lease on controller work with newer wake");
      }
    }
  }

  @Transactional
  @Override
  public void rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt) {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(requestedAt, "requestedAt");
    IssueControllerWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("controller_work", issueId.toString());
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(leaseToken)) {
      throw new AiValidationException("controller_work", "Lease token mismatch for rescheduling");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("controller_work", "Lease expired at current time");
    }
    if (claimedWakeVersion > work.getWakeVersion()) {
      throw new AiValidationException(
          "controller_work", "Claimed wake version exceeds current wake version");
    }
    boolean rescheduled =
        repository.reschedule(issueId, leaseToken, claimedWakeVersion, now, requestedAt);
    if (!rescheduled) {
      throw new AiValidationException("controller_work", "Failed to reschedule controller work");
    }
  }

  @Override
  public IssueControllerWork getWork(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return repository.getById(issueId);
  }
}
