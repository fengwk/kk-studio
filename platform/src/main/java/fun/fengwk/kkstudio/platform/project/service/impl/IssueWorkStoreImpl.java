package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.ClaimedIssueWork;
import fun.fengwk.kkstudio.platform.project.model.IssueWork;
import fun.fengwk.kkstudio.platform.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Service
public class IssueWorkStoreImpl implements IssueWorkStore {

  private final IssueWorkRepository repository;

  @Transactional
  @Override
  public IssueWork requestWork(UUID issueId, Instant dueAt) {
    Objects.requireNonNull(issueId, "issueId");
    Instant targetDue = dueAt != null ? dueAt : Instant.now();
    IssueWork requested = repository.requestWork(issueId, targetDue);
    if (requested == null) {
      throw new AiValidationException("issue_work", "Failed to request issue work");
    }
    return requested;
  }

  @Transactional
  @Override
  public Optional<ClaimedIssueWork> claimNext(Instant now, String leaseToken, Instant leaseUntil) {
    Objects.requireNonNull(now, "now");
    String trimmedToken =
        ProjectValidationUtils.trimAndValidate(leaseToken, "leaseToken", 128, true);
    Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (!leaseUntil.isAfter(now)) {
      throw new AiValidationException("issue_work", "leaseUntil must be after now");
    }
    IssueWork claimed = repository.claimNext(now, trimmedToken, leaseUntil);
    if (claimed == null) {
      return Optional.empty();
    }
    return Optional.of(
        ClaimedIssueWork.builder()
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
    String trimmedToken =
        ProjectValidationUtils.trimAndValidate(leaseToken, "leaseToken", 128, true);
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    IssueWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("issue_work");
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(trimmedToken)) {
      throw new AiValidationException("issue_work", "Lease token mismatch for renewal");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("issue_work", "Lease expired at current time");
    }
    if (!newLeaseUntil.isAfter(work.getLeaseUntil())) {
      throw new AiValidationException(
          "issue_work", "newLeaseUntil must extend beyond current leaseUntil");
    }
    boolean renewed = repository.renewLease(issueId, trimmedToken, now, newLeaseUntil);
    if (!renewed) {
      throw new AiValidationException("issue_work", "Failed to renew issue work lease");
    }
  }

  @Transactional
  @Override
  public void completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now) {
    Objects.requireNonNull(issueId, "issueId");
    String trimmedToken =
        ProjectValidationUtils.trimAndValidate(leaseToken, "leaseToken", 128, true);
    if (claimedWakeVersion <= 0) {
      throw new AiValidationException("issue_work", "claimedWakeVersion must be positive");
    }
    Objects.requireNonNull(now, "now");
    IssueWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("issue_work");
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(trimmedToken)) {
      throw new AiValidationException("issue_work", "Lease token mismatch for completion");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("issue_work", "Lease expired at current time");
    }
    if (claimedWakeVersion > work.getWakeVersion()) {
      throw new AiValidationException(
          "issue_work", "Claimed wake version exceeds current wake version");
    }
    if (claimedWakeVersion == work.getWakeVersion()) {
      boolean deleted =
          repository.deleteIfWakeMatches(issueId, trimmedToken, claimedWakeVersion, now);
      if (!deleted) {
        throw new AiValidationException("issue_work", "Failed to complete issue work");
      }
    } else {
      boolean cleared =
          repository.clearLeaseIfWakeNewer(issueId, trimmedToken, claimedWakeVersion, now);
      if (!cleared) {
        throw new AiValidationException(
            "issue_work", "Failed to clear expired lease for newer wake");
      }
    }
  }

  @Transactional
  @Override
  public void rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt) {
    Objects.requireNonNull(issueId, "issueId");
    String trimmedToken =
        ProjectValidationUtils.trimAndValidate(leaseToken, "leaseToken", 128, true);
    if (claimedWakeVersion <= 0) {
      throw new AiValidationException("issue_work", "claimedWakeVersion must be positive");
    }
    Objects.requireNonNull(now, "now");
    Instant targetRequested = requestedAt != null ? requestedAt : now;
    IssueWork work = repository.lockById(issueId);
    if (work == null) {
      throw new AiResourceNotFoundException("issue_work");
    }
    if (work.getLeaseToken() == null || !work.getLeaseToken().equals(trimmedToken)) {
      throw new AiValidationException("issue_work", "Lease token mismatch for reschedule");
    }
    if (work.getLeaseUntil() == null || !work.getLeaseUntil().isAfter(now)) {
      throw new AiValidationException("issue_work", "Lease expired at current time");
    }
    if (claimedWakeVersion > work.getWakeVersion()) {
      throw new AiValidationException(
          "issue_work", "Claimed wake version exceeds current wake version");
    }
    boolean rescheduled =
        repository.reschedule(issueId, trimmedToken, claimedWakeVersion, now, targetRequested);
    if (!rescheduled) {
      throw new AiValidationException("issue_work", "Failed to reschedule issue work");
    }
  }

  @Transactional(readOnly = true)
  @Override
  public IssueWork getWork(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    return repository.getById(issueId);
  }
}
