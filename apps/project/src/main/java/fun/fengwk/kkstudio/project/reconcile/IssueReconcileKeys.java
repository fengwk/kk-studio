package fun.fengwk.kkstudio.project.reconcile;

import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 调谐器写操作的稳定幂等键：同一事实重放不会产生第二个 Run、第二条收尾活动或第二次 Stop。
 *
 * <p>键只由 Issue/ Run 的持久化事实推导，不掺入时间或随机值，因此事务回滚后重试可以命中同一 receipt。
 */
final class IssueReconcileKeys {

  private IssueReconcileKeys() {}

  /** 接受 key 由 Issue 内待分配的 Run 序号确定：同一次催促重放不会创建第二个 Run。 */
  static String accept(Issue issue) {
    return "reconciler-accept:" + issue.getNextRunOrdinal();
  }

  static String complete(IssueRun run) {
    return "reconciler-complete:" + run.getId() + ":" + run.getVersion();
  }

  static String fail(IssueRun run, UUID endEntryId) {
    return "reconciler-fail:" + run.getId() + ":" + endEntryId;
  }

  static String cancel(IssueRun run, UUID endEntryId) {
    return "reconciler-cancel:" + run.getId() + ":" + endEntryId;
  }

  /** 预算 Stop 的稳定幂等键：同一 Run 的预算停止重放不会产生第二次 Stop。 */
  static UUID budgetStopRequestId(UUID runId) {
    return UUID.nameUUIDFromBytes(
        ("issue-run-budget-stop:" + runId).getBytes(StandardCharsets.UTF_8));
  }
}
