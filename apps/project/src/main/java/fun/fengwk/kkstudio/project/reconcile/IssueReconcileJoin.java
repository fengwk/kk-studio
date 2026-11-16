package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.service.IssueRunService;

import java.time.Duration;
import java.util.UUID;

/**
 * 按 root Join 冻结的结果收敛 Run：正常完成提交冻结的 final-answer 与（仅当本次 Run 仍是当前职责时的）交接目标；失败与取消只做安全 终态，绝不推进阶段。
 *
 * <p>完成只引用 {@code join.finalAnswerEntryId()}，终态 Entry 只引用 {@code join.terminalEntryId()}，都不看当前
 * head；已匹配的 Join 不会被之后到达的普通对话或指示改写，后续业务由下一次 Run/人工输入独立接受。
 *
 * <p>仍持有交接目标的当前 Run 在门禁关闭时停在安全点：Run 转 WAITING 并保留目标，门禁打开后才提交阶段推进。
 *
 * <p>本类只处理已匹配的 Join：没有 receipt 说明调用方判断有误，直接失败，不在这里等待。
 */
@Component
@AllArgsConstructor
final class IssueReconcileJoin {

  private final IssueReconcileEvidence evidence;
  private final IssueRunService issueRunService;
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
    String nextState = current ? run.getNextState() : null;
    if (nextState != null && issue.isGateClosed()) {
      // 交接越过关闭的门禁会把 Issue 写成新阶段却留下阻塞字段，之后既不能恢复也不能执行：
      // 安全点先停活动计时并保留 next_state，门禁打开后的下一次调谐才提交阶段推进。
      // 已耗尽活动额度的 Run 无法转 WAITING，Execution 又已静止，保持 RUNNING 等门禁即可。
      if (run.getStatus() == IssueRunStatus.RUNNING && run.getRemainingExecutionMs() > 0) {
        issueRunService.waitRun(run.getId(), run.getVersion());
      }
      return IssueReconcileStep.poll(
          properties.getBlockedDelay(), IssueReconcileOutcome.WAITING_FOR_GATE);
    }
    evidence.publishEvidence(issue, run, binding, view, join.finalAnswerEntryId());
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
