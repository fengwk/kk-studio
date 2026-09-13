package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueControllerWork;

import java.time.Instant;
import java.util.UUID;

public interface IssueControllerWorkRepository {

  IssueControllerWork getById(UUID issueId);

  IssueControllerWork lockById(UUID issueId);

  IssueControllerWork requestWork(UUID issueId, Instant dueAt);

  IssueControllerWork claimNext(Instant now, String leaseToken, Instant leaseUntil);

  boolean renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  boolean deleteIfWakeMatches(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  boolean clearLeaseIfWakeNewer(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  boolean reschedule(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt);
}
