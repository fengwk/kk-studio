package fun.fengwk.kkstudio.project.repo;

import fun.fengwk.kkstudio.project.model.IssueWork;

import java.time.Duration;
import java.util.UUID;

/** {@code project_issue_work} 调度邮箱持久化端口：每 Issue 最多单行，lease/wake 围栏保证同一 Issue 的业务决定串行。 */
public interface IssueWorkRepository {

  IssueWork getById(UUID issueId);

  IssueWork lockById(UUID issueId);

  /** 请求唤醒：新行 wake_version=1，已存在则合并 due、递增 wake_version，保留正在处理的 lease。 */
  IssueWork requestWork(UUID issueId, Duration delay);

  IssueWork claimNext(String leaseToken, Duration leaseDuration);

  boolean renewLease(UUID issueId, String leaseToken, Duration leaseDuration);

  /**
   * 有效 lease 且版本匹配时删除；新 wake 保留行并释放 lease，返回 false。
   *
   * <p>释放分支是真实写入（due 已提前到当前时刻），在同一事务内发送到期提示；围栏未匹配的返回 false 不写也不提示。
   */
  boolean completeWork(UUID issueId, String leaseToken, long claimedWakeVersion);

  /**
   * 归还当前有效 claim 的 lease 并把下一次检查延后 {@code delay}。
   *
   * <p>版本匹配直接设置新 due；有新 wake 时合并更早值。token 错误或 lease 过期返回 false。
   */
  boolean rescheduleWork(UUID issueId, String leaseToken, long claimedWakeVersion, Duration delay);

  int deleteByIssueId(UUID issueId);
}
