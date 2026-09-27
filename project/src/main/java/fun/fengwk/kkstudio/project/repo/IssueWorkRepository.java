package fun.fengwk.kkstudio.project.repo;

import fun.fengwk.kkstudio.project.model.IssueWork;

import java.time.Instant;
import java.util.UUID;

/** {@code project_issue_work} 调度邮箱持久化端口：每 Issue 最多单行，lease/wake 围栏保证同一 Issue 的业务决定串行。 */
public interface IssueWorkRepository {

  IssueWork getById(UUID issueId);

  IssueWork lockById(UUID issueId);

  /** 请求唤醒：新行 wake_version=1，已存在则合并 due、递增 wake_version 并清空 lease。 */
  IssueWork requestWork(UUID issueId, Instant dueAt);

  IssueWork claimNext(Instant now, String leaseToken, Instant leaseUntil);

  boolean renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  /** 完成并删除 claim：仅当 lease 仍有效且 wake_version 未被新唤醒推进时生效。 */
  boolean deleteIfWakeMatches(
      UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  /**
   * 归还当前 claim 的 lease 并把下一次检查延后到 {@code dueAt}。
   *
   * <p>只以调用方自己的 lease token 围栏，因此即使处理期间到达了新唤醒（wake_version 已推进）也能立即归还；{@code due_at}
   * 只取更早值，不会把别的唤醒推后。围栏失败（lease 已过期或已被接管）返回 false，由 lease 自愈兜底。
   */
  boolean releaseLease(UUID issueId, String leaseToken, Instant dueAt);

  int deleteByIssueId(UUID issueId);
}
