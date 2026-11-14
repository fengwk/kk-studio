package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;

import java.time.Duration;
import java.util.UUID;

/**
 * 按 root Join 冻结的结果收敛 Run：正常完成提交冻结的 final-answer 与（仅当本次 Run 仍是当前职责时的）交接目标；失败与取消只做安全 终态，绝不推进阶段。
 *
 * <p>完成只引用 {@code join.finalAnswerEntryId()}，终态 Entry 只引用 {@code join.terminalEntryId()}，都不看当前
 * head；已匹配的 Join 不会被之后到达的普通对话或指示改写，后续业务由下一次 Run/人工输入独立接受。
 *
 * <p>本类只处理已匹配的 Join：没有 receipt 说明调用方判断有误，直接失败，不在这里等待。
 */
@Component
@AllArgsConstructor
final class IssueReconcileJoin {

  private final IssueReconcileEvidence evidence;
  private final IssueRunFinishing finishing;
  private final IssueReconcileProperties properties;
  private final IssueReconcileHarness harness;

  /** 本次 Run 的 root Join 已冻结结果时返回它，否则返回 {@code null}。 */
  ThreadJoin matched(UUID invocationId) {
    return harness.require().findJoin(invocationId).filter(ThreadJoin::matched).orElse(null);
  }

  /** 收敛已匹配的 root Join，返回调谐决策。 */
  IssueReconcileStep close(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      IssueThreadView view,
      ThreadJoin join,
      boolean current) {
    ThreadJoinReceipt receipt =
        harness
            .require()
            .projectJoinReceipt(run.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "matched root join has no receipt for run " + run.getId()));
    return switch (receipt.outcome()) {
      case COMPLETED -> complete(issue, run, binding, view, join, current);
      case ERROR -> fail(run, join, receipt, current);
      case CANCELLED -> cancel(run, join, current);
    };
  }

  private IssueReconcileStep complete(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      IssueThreadView view,
      ThreadJoin join,
      boolean current) {
    evidence.publishEvidence(issue, run, binding, view, join.finalAnswerEntryId());
    String nextState = current ? run.getNextState() : null;
    finishing.complete(run, join, nextState);
    if (!current) {
      return IssueReconcileStep.poll(
          properties.getActiveDelay(), IssueReconcileOutcome.STALE_RUN_CLOSED);
    }
    return IssueReconcileStep.poll(
        Duration.ZERO,
        nextState != null
            ? IssueReconcileOutcome.RUN_HANDED_OFF
            : IssueReconcileOutcome.RUN_COMPLETED);
  }

  private IssueReconcileStep fail(
      IssueRun run, ThreadJoin join, ThreadJoinReceipt receipt, boolean current) {
    String error =
        receipt.error() == null || receipt.error().isBlank()
            ? "Subagent execution failed"
            : receipt.error();
    finishing.fail(run, join.terminalEntryId(), error);
    return IssueReconcileStep.poll(
        properties.getBlockedDelay(),
        current ? IssueReconcileOutcome.RUN_FAILED : IssueReconcileOutcome.STALE_RUN_CLOSED);
  }

  private IssueReconcileStep cancel(IssueRun run, ThreadJoin join, boolean current) {
    finishing.cancel(run, join.terminalEntryId());
    return IssueReconcileStep.poll(
        properties.getBlockedDelay(),
        current ? IssueReconcileOutcome.RUN_CANCELLED : IssueReconcileOutcome.STALE_RUN_CLOSED);
  }
}
