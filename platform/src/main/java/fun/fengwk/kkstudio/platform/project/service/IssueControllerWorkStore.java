package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IssueControllerWorkStore {

  IssueControllerWork requestWork(UUID issueId, Instant dueAt);

  Optional<ClaimedControllerWork> claimNext(Instant now, String leaseToken, Instant leaseUntil);

  void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  void completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  void rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt);

  IssueControllerWork getWork(UUID issueId);
}
