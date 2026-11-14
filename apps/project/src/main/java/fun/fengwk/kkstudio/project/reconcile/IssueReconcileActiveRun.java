package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.service.IssueRunService;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 活动 Run 的收敛策略：先按 root Join 收尾，再按缺失归属/迟到 Run/额度/执行事实/等待门禁的顺序决定下一个有界动作。
 *
 * <p>判定顺序即语义顺序，不能重排：已冻结的 root Join 必须先于任何 busy/等待/指示判断收尾，终态 Entry 与 final 只取 Join 冻结值；缺失归属或迟到 Run
 * 只在没有在途执行时才安全收尾；额度耗尽先显式 Stop 再过户收尾区间。
 *
 * <p>所有写操作都在调用方的调谐事务内、且已持有 Project → Issue → Run 锁；本类只读 Harness 事实，不在锁内做网络等待。
 */
@Component
@AllArgsConstructor
final class IssueReconcileActiveRun {

  private static final String BUDGET_REASON = "Run active execution budget is exhausted";
  private static final String STALE_REASON = "Run no longer matches the current issue stage";

  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueRunService issueRunService;
  private final IssueReconcileJoin joins;
  private final IssueReconcileInstructions instructions;
  private final IssueRunFinishing finishing;
  private final IssueReconcileHarness harness;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final IssueReconcileProperties properties;

  /** 收敛一次活动 Run；返回本次 claim 的下一个动作。 */
  IssueReconcileStep step(Project project, Issue issue, IssueRun run, Instant now) {
    IssueThreadView view = harness.require().readThread(run.getThreadId());
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(run.getThreadId());
    IssueRunOwnership ownership =
        IssueRunOwnership.assess(
            issue, run, binding, IssueReconcileStages.find(workflowCodec, project, run.getState()));

    // 已冻结的 root Join 决定执行终态：必须先于任何 busy/等待/指示判断收尾，终态 Entry 与 final 只取 Join 冻结值，
    // 本 Thread 之后的普通对话或后代忙碌都不能再把已结束的执行重新拉回在途。
    ThreadJoin join = joins.matched(run.getId());
    if (join != null) {
      return joins.close(issue, run, binding, view, join, ownership.current());
    }

    // 统一执行事实：本地工作（命令/Model/工具/continuation）与人工等待各由唯一投影给出，本类只决定预算、等待与停止政策。
    ThreadExecutionFacts facts = view.executionFacts();

    IssueReconcileStep stale = staleStep(issue, run, ownership, facts, view.headEntryId());
    if (stale != null) {
      return stale;
    }
    if (run.getStatus() == IssueRunStatus.RUNNING && isBudgetExhausted(run, now)) {
      // 业务预算要求停止在途长 loop：显式 Stop（当前 Thread 版本 CAS + 稳定 requestId）结束执行，再在同一业务事务失败收尾。
      // 收尾区间以 Stop 回执的权威 Thread head 为界，绝不再反查当前 head，冻结本次停止的确定边界。
      UUID stopHeadEntryId =
          harness
              .require()
              .stop(
                  new StopCommand(
                      run.getThreadId(),
                      IssueReconcileKeys.budgetStopRequestId(run.getId()),
                      view.version()));
      finishing.fail(run, stopHeadEntryId, BUDGET_REASON);
      return IssueReconcileStep.poll(
          properties.getBlockedDelay(), IssueReconcileOutcome.RUN_BUDGET_EXHAUSTED);
    }
    if (run.getStatus() == IssueRunStatus.RUNNING) {
      chargeActiveTime(run, now);
    }
    if (facts.hasWork()) {
      // 未处理命令、合法在途 Model/Tool 或 owned continuation：不投递、不等待、不收尾。已收敛的 UNKNOWN 工具不计为工作。
      return IssueReconcileStep.poll(
          properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
    }
    return settledStep(issue, run, facts);
  }

  /** 归属或阶段已失效时的安全收尾；仍在途执行只延后，绝不抢写。 */
  private IssueReconcileStep staleStep(
      Issue issue,
      IssueRun run,
      IssueRunOwnership ownership,
      ThreadExecutionFacts facts,
      UUID headEntryId) {
    if (ownership.bindingValid() && ownership.stageStillOwned() && ownership.stateStillCurrent()) {
      return null;
    }
    if (facts.hasWork()) {
      return IssueReconcileStep.poll(
          properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
    }
    if (ownership.bindingValid() && instructions.deliverNext(issue, run)) {
      return IssueReconcileStep.poll(Duration.ZERO, IssueReconcileOutcome.INSTRUCTION_DELIVERED);
    }
    finishing.fail(run, headEntryId, STALE_REASON);
    return IssueReconcileStep.poll(
        properties.getBlockedDelay(), IssueReconcileOutcome.STALE_RUN_CLOSED);
  }

  /** 执行静止后的等待门禁政策：门禁/安全等待保持或进入 WAITING，解除后恢复同一 Run。 */
  private IssueReconcileStep settledStep(Issue issue, IssueRun run, ThreadExecutionFacts facts) {
    boolean blocked = issue.isBlocked();
    boolean waitingTool = facts.waitingForHuman();
    if (run.getStatus() == IssueRunStatus.WAITING) {
      if (issue.isGateClosed() || waitingTool) {
        // 门禁或安全等待尚未解除：保持 WAITING，不消耗新额度也不恢复执行。
        return IssueReconcileStep.poll(
            properties.getBlockedDelay(), IssueReconcileOutcome.WAITING_FOR_GATE);
      }
      // 门禁与安全等待均已解除，恢复同一 Run：不新建 Run，也不重新扣额度。
      issueRunService.resumeRun(run.getId(), run.getVersion());
      return IssueReconcileStep.poll(
          properties.getActiveDelay(), IssueReconcileOutcome.RUN_RESUMED);
    }
    if (blocked || waitingTool) {
      // 阻塞或工具等待：到达安全点后才停止活动计时，保留交接目标等待显式恢复。
      issueRunService.waitRun(run.getId(), run.getVersion());
      return IssueReconcileStep.poll(
          properties.getBlockedDelay(), IssueReconcileOutcome.WAITING_FOR_GATE);
    }
    if (instructions.deliverNext(issue, run)) {
      return IssueReconcileStep.poll(Duration.ZERO, IssueReconcileOutcome.INSTRUCTION_DELIVERED);
    }
    // 执行静止但 root Join 尚未冻结结果：既不按当前 head 猜测完成，也不等待永久子树 idle。
    return IssueReconcileStep.poll(
        properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_IDLE);
  }

  /** 判断本次 Run 的活动执行时长是否已耗尽。 */
  private static boolean isBudgetExhausted(IssueRun run, Instant now) {
    if (run.getActiveSince() == null) {
      return false;
    }
    long elapsed = Math.max(0L, Duration.between(run.getActiveSince(), now).toMillis());
    return run.getRemainingExecutionMs() - elapsed <= 0;
  }

  /** 扣减本次 Run 的活动执行时长。 */
  private void chargeActiveTime(IssueRun run, Instant now) {
    if (run.getActiveSince() == null) {
      return;
    }
    long elapsed = Math.max(0L, Duration.between(run.getActiveSince(), now).toMillis());
    long remaining = Math.max(0L, run.getRemainingExecutionMs() - elapsed);
    long expectedVersion = run.getVersion();
    run.setRemainingExecutionMs(remaining);
    run.setActiveSince(now);
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new IllegalStateException("failed to charge run execution budget");
    }
    run.setVersion(expectedVersion + 1);
  }
}
