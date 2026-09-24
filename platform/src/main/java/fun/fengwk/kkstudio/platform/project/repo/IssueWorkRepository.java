package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueWork;

import java.time.Instant;
import java.util.UUID;

/**
 * Issue 调度工作仓储：每 Issue 最多单行，lease/wake 围栏保证同一 Issue 的业务决定串行。
 *
 * <p>BLOCKED 收敛为等待人工动作，不按轮询反复唤醒。
 */
public interface IssueWorkRepository {

  IssueWork getById(UUID issueId);

  IssueWork lockById(UUID issueId);

  IssueWork requestWork(UUID issueId, Instant dueAt);

  IssueWork claimNext(Instant now, String leaseToken, Instant leaseUntil);

  boolean renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  boolean deleteIfWakeMatches(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  boolean clearLeaseIfWakeNewer(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  boolean reschedule(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now, Instant requestedAt);

  int deleteByIssueId(UUID issueId);
}
