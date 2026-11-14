package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.service.IssueRunService;

import java.util.UUID;

/**
 * 把活动 Run 写成终态的唯一出口：完成、失败与取消都以 Run 的稳定幂等键调用 {@link IssueRunService#finishRun}。
 *
 * <p>调用方必须在调谐事务内使用。完成只提交 Join 冻结的终态 Entry 与 final answer，交接目标由调用方决定（只有仍持有当前职责的 Run
 * 才允许传入非空目标）；失败与取消绝不推进阶段。
 */
@Component
@AllArgsConstructor
final class IssueRunFinishing {

  private final IssueRunService issueRunService;

  void complete(IssueRun run, ThreadJoin join, String nextState) {
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        IssueReconcileKeys.complete(run),
        IssueRunStatus.COMPLETED,
        join.terminalEntryId(),
        join.finalAnswerEntryId(),
        nextState,
        null);
  }

  void fail(IssueRun run, UUID endEntryId, String reason) {
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        IssueReconcileKeys.fail(run, endEntryId),
        IssueRunStatus.FAILED,
        endEntryId,
        null,
        null,
        reason);
  }

  void cancel(IssueRun run, UUID endEntryId) {
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        IssueReconcileKeys.cancel(run, endEntryId),
        IssueRunStatus.CANCELLED,
        endEntryId,
        null,
        null,
        null);
  }
}
