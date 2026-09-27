package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.project.model.IssueWork;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue 调度邮箱用例：Work 是「重新检查当前 Issue」的唯一 durable 入口。
 *
 * <p>Worker 领取后必须重新读取当前 Run、阶段、Thread 与门禁；{@code wakeVersion} 防止旧 Worker 完成 claim 时吞掉新唤醒。丢失通知由
 * claim 轮询恢复，事件不能代替 Work。
 */
public interface IssueWorkStore {

  IssueWork requestWork(UUID issueId, Instant dueAt);

  IssueWork getWork(UUID issueId);

  Optional<IssueWork> claimNext(Instant now, String leaseToken, Instant leaseUntil);

  void renewLease(UUID issueId, String leaseToken, Instant now, Instant newLeaseUntil);

  boolean completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);

  /**
   * 归还本次 claim 的 lease 并把下一次检查延后到 {@code dueAt}；mailbox 行保留。
   *
   * <p>与 {@link #completeWork} 的区别：完成表示「当前没有仍需自动重检的事实」，归还表示「仍需在未来某个时刻重新检查当前 Issue」。两者都以调用方自己的 lease
   * token 围栏，因此并发到达的新唤醒不会被旧 Worker 吞掉。
   */
  boolean rescheduleWork(UUID issueId, String leaseToken, Instant dueAt);
}
