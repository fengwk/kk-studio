package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.IssueWork;

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

  void completeWork(UUID issueId, String leaseToken, long claimedWakeVersion, Instant now);
}
