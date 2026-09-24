package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.ClaimedIssueWork;
import fun.fengwk.kkstudio.platform.project.model.IssueWork;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IssueWorkStore {

  IssueWork requestWork(UUID issueId, Instant dueAt);

  Optional<ClaimedIssueWork> claimNext(Instant now, String leaseToken, Instant leaseUntil);

  void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  void completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  void rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt);

  IssueWork getWork(UUID issueId);
}
