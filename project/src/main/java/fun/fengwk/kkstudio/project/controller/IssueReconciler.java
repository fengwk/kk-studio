package fun.fengwk.kkstudio.project.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.port.EvidenceBlobPort;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 目标 Project 的确定性 Issue 调谐器：每次调用只执行一个有界动作。
 *
 * <p>Work 只表达「重新检查当前 Issue」，因此本类领取 claim 后一律重新读取当前 Issue、阶段、稳定 Thread 归属与活动 Run，绝不复用领取时的旧决定。锁序固定为
 * Project (FOR KEY SHARE) → Issue (FOR UPDATE) → 活动 Run (FOR UPDATE) → 业务仓库；业务生命周期转变委托 {@link
 * IssueRunService}，Run 的投递游标与活动时长在版本 CAS 下就地推进。
 *
 * <p>关键不变量：
 *
 * <ul>
 *   <li><b>durable mailbox lease + heartbeat</b>：领取即续租；结束用 claim 完成，仍需重检则归还 lease 并把 {@code due_at}
 *       延后，丢失通知由轮询恢复。
 *   <li><b>安全等待</b>：区分真正在途执行与静止等待（问答/审批等待）；已冻结 root Join 的执行先收尾，在途执行未被收尾时只延后不投递，静止等待进入 WAITING
 *       并停止活动计时。
 *   <li><b>预算耗尽安全收尾</b>：活动时长耗尽时对当前 Thread 发起显式 Stop，并以 Stop 回执的权威 head 作为收尾区间，同一业务事务失败收尾；不写通用
 *       Pause，也不再等待执行静止。
 *   <li><b>BLOCKED 覆盖门禁</b>：业务阻塞关闭派发门禁，阻止自动派发与 WAITING 恢复；Issue 控制暂停只阻止新 Run 调度，不再挂起已接受的 Thread。
 *   <li><b>指示不丢</b>：定向指示按活动事实位置投递，普通评论与系统事件不卡游标；root Join 冻结先收尾时未投递的指示保留游标，由下一个 Run 处理。
 *   <li><b>完成只认 root Join</b>：Run 结束只读取本次 root Join 冻结的 terminal/final-answer entry，既不按快照最新 head
 *       猜测，也不等待永久子树 idle；工具结果未定只是 Loop 反馈，不再升级为阻塞 Thread 的人工核查门禁。
 *   <li><b>Run 收尾</b>：正常收尾冻结真实历史区间与 final-answer entry，只发布该最终答复明确引用且来源 Session 持有的
 *       Resource；无交接时保持当前阶段，之后的催促按额度新建 Run。
 *   <li><b>迟到回调拒绝</b>：旧 Run 只做安全收尾，绝不提交旧交接目标推进当前阶段；未冻结 root Join 的迟到 Run 仍在静止点才失败收尾。
 *   <li><b>确定性调度决策</b>：在写路径前完成归档/门禁/额度/阶段/证据合法性判定，不以全局 try-catch 吞掉数据库或编程异常。
 * </ul>
 */
@Slf4j
@Service
public class IssueReconciler {

  /** 单次 reconcile 检视的 Activity 窗口上限：只读有界窗口，窗口未到流尾时先推进已检视游标再立即续扫。 */
  static final int ACTIVITY_SCAN_LIMIT = 200;

  private static final String BUDGET_REASON = "Run active execution budget is exhausted";
  private static final String STALE_REASON = "Run no longer matches the current issue stage";

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueRunService issueRunService;
  private final IssueEvidenceService issueEvidenceService;
  private final EvidenceBlobPort evidenceBlobPort;
  private final IssueWorkStore issueWorkStore;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final IssueControllerProperties properties;
  private final Clock clock;

  @Autowired
  public IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueActivityRepository issueActivityRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueStageBudgetRepository stageBudgetRepository,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService,
      EvidenceBlobPort evidenceBlobPort,
      IssueWorkStore issueWorkStore,
      ObjectProvider<HarnessRuntime> runtimes,
      ProjectWorkflowJsonCodec workflowCodec,
      IssueControllerProperties properties) {
    this(
        projectRepository,
        issueRepository,
        issueRunRepository,
        issueActivityRepository,
        issueAgentThreadRepository,
        stageBudgetRepository,
        issueRunService,
        issueEvidenceService,
        evidenceBlobPort,
        issueWorkStore,
        runtimes,
        workflowCodec,
        properties,
        Clock.systemUTC());
  }

  IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueActivityRepository issueActivityRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueStageBudgetRepository stageBudgetRepository,
      IssueRunService issueRunService,
      IssueEvidenceService issueEvidenceService,
      EvidenceBlobPort evidenceBlobPort,
      IssueWorkStore issueWorkStore,
      ObjectProvider<HarnessRuntime> runtimes,
      ProjectWorkflowJsonCodec workflowCodec,
      IssueControllerProperties properties,
      Clock clock) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueActivityRepository =
        Objects.requireNonNull(issueActivityRepository, "issueActivityRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.stageBudgetRepository =
        Objects.requireNonNull(stageBudgetRepository, "stageBudgetRepository");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
    this.evidenceBlobPort = Objects.requireNonNull(evidenceBlobPort, "evidenceBlobPort");
    this.issueWorkStore = Objects.requireNonNull(issueWorkStore, "issueWorkStore");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
  }

  /** 调谐一次已领取的 work；整个动作在单个物理事务内完成，外部执行从不在锁内发生。 */
  @Transactional
  public IssueReconcileOutcome reconcile(IssueWorkClaim claim) {
    validateClaim(claim);
    Instant now = clock.instant();
    LockedIssue locked = lockIssue(claim.issueId());
    if (locked == null) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_ARCHIVED);
    }
    heartbeat(claim);

    Project project = locked.project();
    Issue issue = locked.issue();
    if (project.isArchived()
        || issue.isArchived()
        || ProjectWorkflowReservedState.DONE.code().value().equals(issue.getState())) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_ARCHIVED);
    }
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (activeRun != null) {
      return reconcileActiveRun(project, issue, activeRun, claim, now);
    }
    return reconcileIdle(project, issue, claim);
  }

  private IssueReconcileOutcome reconcileActiveRun(
      Project project, Issue issue, IssueRun run, IssueWorkClaim claim, Instant now) {
    ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(run.getThreadId());
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(run.getThreadId());
    boolean bindingValid = binding != null && binding.issueId().equals(issue.getId());
    ProjectWorkflowState stage = workflowStage(project, run.getState());
    boolean stageStillOwned =
        stage != null
            && stage.enabled()
            && stage.hasAgent()
            && bindingValid
            && stage.agent().equals(binding.agentName());
    boolean stateStillCurrent =
        run.getState().equals(issue.getState())
            || (issue.getBlockedFromState() != null
                && run.getState().equals(issue.getBlockedFromState()));
    boolean current = bindingValid && stageStillOwned && stateStillCurrent;

    // 已冻结的 root Join 决定执行终态：必须先于任何 busy/等待/指示判断收尾，终态 Entry 与 final 只取 Join 冻结值，
    // 本 Thread 之后的普通对话或后代忙碌都不能再把已结束的执行重新拉回在途。
    ThreadJoin join = matchedJoin(run.getId());
    if (join != null) {
      return closeMatchedJoin(issue, run, binding, snapshot, join, current, claim);
    }

    if (!bindingValid) {
      if (isProcessing(snapshot)) {
        return finish(
            claim, true, properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
      }
      // 归属已不存在：Run 无法再被驱动，安全收尾为失败并显式要求人工核查。
      return failRun(
          run,
          snapshot.thread().headEntryId(),
          STALE_REASON,
          claim,
          IssueReconcileOutcome.STALE_RUN_CLOSED);
    }
    if (!stageStillOwned || !stateStillCurrent) {
      // 迟到回调：旧 Run 的同名阶段或旧归属不得刷新当前职责，只做安全收尾，绝不提交交接目标。
      if (isProcessing(snapshot)) {
        return finish(
            claim, true, properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
      }
      IssueActivity deliverable = nextDeliverableInstruction(run);
      if (deliverable != null) {
        deliverInstruction(issue, run, snapshot, deliverable);
        return finish(claim, true, Duration.ZERO, IssueReconcileOutcome.INSTRUCTION_DELIVERED);
      }
      return failRun(
          run,
          snapshot.thread().headEntryId(),
          STALE_REASON,
          claim,
          IssueReconcileOutcome.STALE_RUN_CLOSED);
    }

    if (run.getStatus() == IssueRunStatus.RUNNING && isBudgetExhausted(run, now)) {
      // 业务预算要求停止在途长 loop：显式 Stop（当前 Thread 版本 CAS + 稳定 requestId）结束执行，再在同一业务事务失败收尾。
      // 收尾区间以 Stop 回执的权威 Thread head 为界，绝不再反查当前 head，冻结本次停止的确定边界。
      StopResult stopped =
          requireRuntime()
              .stop(
                  new StopCommand(
                      run.getThreadId(),
                      budgetStopRequestId(run.getId()),
                      snapshot.thread().version()));
      return failRun(
          run,
          stopped.thread().headEntryId(),
          BUDGET_REASON,
          claim,
          IssueReconcileOutcome.RUN_BUDGET_EXHAUSTED);
    }
    if (run.getStatus() == IssueRunStatus.RUNNING) {
      chargeActiveTime(run, now);
    }

    if (isProcessing(snapshot)) {
      // 未处理命令或在途调用：不投递、不等待、不收尾。
      return finish(
          claim, true, properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
    }

    // Issue 控制暂停只阻止新 Run 调度，不挂起已接受的 Thread；只有业务阻塞或安全工具等待才暂停本 Run。
    boolean blocked = issue.isBlocked();
    boolean waitingTool = hasWaitingTool(snapshot);

    if (run.getStatus() == IssueRunStatus.WAITING) {
      if (issue.isGateClosed() || waitingTool) {
        // 暂停/阻塞或安全等待尚未解除：保持 WAITING，不消耗新额度也不恢复执行。
        return finish(
            claim, true, properties.getBlockedDelay(), IssueReconcileOutcome.WAITING_FOR_GATE);
      }
      // 门禁与安全等待均已解除，恢复同一 Run：不新建 Run，也不重新扣额度。
      issueRunService.resumeRun(run.getId(), run.getVersion());
      return finish(claim, true, properties.getActiveDelay(), IssueReconcileOutcome.RUN_RESUMED);
    }

    if (blocked || waitingTool) {
      // 阻塞或工具等待：到达安全点后才停止活动计时，保留交接目标等待显式恢复。
      issueRunService.waitRun(run.getId(), run.getVersion());
      return finish(
          claim, true, properties.getBlockedDelay(), IssueReconcileOutcome.WAITING_FOR_GATE);
    }

    IssueActivity deliverable = nextDeliverableInstruction(run);
    if (deliverable != null) {
      deliverInstruction(issue, run, snapshot, deliverable);
      return finish(claim, true, Duration.ZERO, IssueReconcileOutcome.INSTRUCTION_DELIVERED);
    }

    // 执行静止但 root Join 尚未冻结结果：既不按当前 head 猜测完成，也不等待永久子树 idle。
    return finish(claim, true, properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_IDLE);
  }

  /**
   * 按 root Join 冻结的结果收敛 Run：正常完成提交冻结的 final-answer 与（仅当本次 Run 仍是当前职责时的）交接目标；失败与取消只做安全终态， 绝不推进阶段。
   *
   * <p>完成只引用 {@code join.finalAnswerEntryId()}，终态 Entry 只引用 {@code join.terminalEntryId()}，都不看当前
   * head；已匹配的 Join 不会被之后到达的普通对话或指示改写，后续业务由下一次 Run/人工输入独立接受。
   */
  private IssueReconcileOutcome closeMatchedJoin(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      ThreadSnapshot snapshot,
      ThreadJoin join,
      boolean current,
      IssueWorkClaim claim) {
    ThreadJoinReceipt receipt =
        requireRuntime()
            .projectJoinReceipt(run.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "matched root join has no receipt for run " + run.getId()));
    switch (receipt.outcome()) {
      case COMPLETED -> {
        publishEvidence(issue, run, binding, snapshot, join.finalAnswerEntryId());
        String nextState = current ? run.getNextState() : null;
        issueRunService.completeRun(
            run.getId(),
            run.getVersion(),
            completeKey(run),
            join.terminalEntryId(),
            join.finalAnswerEntryId(),
            nextState);
        if (!current) {
          return finish(
              claim, true, properties.getActiveDelay(), IssueReconcileOutcome.STALE_RUN_CLOSED);
        }
        return finish(
            claim,
            true,
            Duration.ZERO,
            nextState != null
                ? IssueReconcileOutcome.RUN_HANDED_OFF
                : IssueReconcileOutcome.RUN_COMPLETED);
      }
      case ERROR -> {
        String error =
            receipt.error() == null || receipt.error().isBlank()
                ? "Subagent execution failed"
                : receipt.error();
        return failRun(
            run,
            join.terminalEntryId(),
            error,
            claim,
            current ? IssueReconcileOutcome.RUN_FAILED : IssueReconcileOutcome.STALE_RUN_CLOSED);
      }
      case CANCELLED -> {
        issueRunService.cancelRun(
            run.getId(),
            run.getVersion(),
            cancelKey(run, join.terminalEntryId()),
            join.terminalEntryId());
        return finish(
            claim,
            true,
            properties.getBlockedDelay(),
            current ? IssueReconcileOutcome.RUN_CANCELLED : IssueReconcileOutcome.STALE_RUN_CLOSED);
      }
      default -> throw new IllegalStateException("unsupported join outcome: " + receipt.outcome());
    }
  }

  private IssueReconcileOutcome reconcileIdle(Project project, Issue issue, IssueWorkClaim claim) {
    if (project.isArchived()
        || issue.isArchived()
        || ProjectWorkflowReservedState.DONE.code().value().equals(issue.getState())) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_ARCHIVED);
    }
    if (issue.isGateClosed()) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE);
    }
    ProjectStateCode state = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_NO_AGENT);
    }
    ProjectWorkflowState stage = workflowStage(project, issue.getState());
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return finish(claim, false, Duration.ZERO, IssueReconcileOutcome.CONVERGED_NO_AGENT);
    }
    var budget = stageBudgetRepository.get(issue.getId(), issue.getState());
    if (budget != null) {
      long used =
          issueRunRepository.countByIssueIdAndStateAfterOrdinal(
              issue.getId(), issue.getState(), budget.getBudgetAfterOrdinal());
      if (used >= budget.getMaxRuns()) {
        // 额度用尽只展示待人工授权：保留 mailbox 以便授权后立即推进。
        return finish(
            claim,
            true,
            properties.getBlockedDelay(),
            IssueReconcileOutcome.CONVERGED_BUDGET_EXHAUSTED);
      }
    }
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (activeRun != null) {
      return finish(
          claim, true, properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING);
    }
    issueRunService.acceptRun(issue.getId(), acceptKey(issue));
    return finish(claim, true, properties.getActiveDelay(), IssueReconcileOutcome.RUN_ACCEPTED);
  }

  /**
   * 结束一次 claim：{@code keepPolling} 为真时归还 lease 并把 {@code due_at} 推后（mailbox 保留），否则完成当前版本（有新 wake
   * 时只释放 lease，保留 mailbox）。
   *
   * <p>两种情况都以调用方自己的 lease token 围栏；返回 false 说明未删除（含新 wake 已释放 lease）或租约围栏未匹配，此时新事实由下一次 claim
   * 处理，因此只记录而不抛出。
   */
  private IssueReconcileOutcome finish(
      IssueWorkClaim claim, boolean keepPolling, Duration delay, IssueReconcileOutcome outcome) {
    boolean applied =
        keepPolling
            ? issueWorkStore.rescheduleWork(
                claim.issueId(), claim.leaseToken(), claim.wakeVersion(), delay)
            : issueWorkStore.completeWork(claim.issueId(), claim.leaseToken(), claim.wakeVersion());
    if (!applied) {
      log.debug(
          "Issue work claim was already superseded; issueId={}, leaseToken={}",
          claim.issueId(),
          claim.leaseToken());
    }
    return outcome;
  }

  private void validateClaim(IssueWorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(claim.issueId(), "claim.issueId");
    Objects.requireNonNull(claim.leaseToken(), "claim.leaseToken");
    Objects.requireNonNull(claim.leaseUntil(), "claim.leaseUntil");
  }

  private LockedIssue lockIssue(UUID issueId) {
    Issue reference = issueRepository.getById(issueId);
    if (reference == null) {
      return null;
    }
    Project project = projectRepository.lockForKeyShare(reference.getProjectId());
    if (project == null) {
      return null;
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null || !issue.getProjectId().equals(project.getId())) {
      return null;
    }
    return new LockedIssue(project, issue);
  }

  /** 领取即续租：由数据库保证至少剩余一个完整租期且不缩短当前 lease，并持有 work 行锁到 finish。 */
  private void heartbeat(IssueWorkClaim claim) {
    issueWorkStore.renewLease(claim.issueId(), claim.leaseToken(), properties.getLeaseDuration());
  }

  /** 判断本次 Run 的活动执行时长是否已耗尽。 */
  private boolean isBudgetExhausted(IssueRun run, Instant now) {
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

  private IssueReconcileOutcome failRun(
      IssueRun run,
      UUID endEntryId,
      String reason,
      IssueWorkClaim claim,
      IssueReconcileOutcome outcome) {
    issueRunService.failRun(
        run.getId(), run.getVersion(), failKey(run, endEntryId), endEntryId, reason);
    // 失败与 ERROR 暂停门禁同事务写入，等待显式恢复；mailbox 保留以便恢复后立即推进。
    return finish(claim, true, properties.getBlockedDelay(), outcome);
  }

  /**
   * 只发布最终 assistant 答复明确引用、且来源 Run Session 当前持有的 Resource。
   *
   * <p>中间消息、工具结果与用户附件保持私有。{@code finalAnswerEntryId} 必须落在本次 Run 的可见区间内。
   */
  private void publishEvidence(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      ThreadSnapshot snapshot,
      UUID finalAnswerEntryId) {
    if (finalAnswerEntryId == null
        || binding == null
        || snapshot == null
        || snapshot.entryPath() == null
        || snapshot.thread() == null) {
      return;
    }
    EntryPath path = snapshot.entryPath();
    List<Entry> entries = path.entries();
    if (entries == null) {
      return;
    }
    List<UUID> entryIds = entries.stream().map(Entry::id).toList();
    int start = entryIds.indexOf(run.getStartEntryId());
    int end = entryIds.indexOf(snapshot.thread().headEntryId());
    int answer = entryIds.indexOf(finalAnswerEntryId);
    if (start < 0 || answer <= start || end < answer) {
      return;
    }
    Entry entry = entries.get(answer);
    if (entry == null || !(entry.payload() instanceof MessagePayload msg)) {
      return;
    }
    if (msg.message().role() != AgentMessageRole.ASSISTANT) {
      return;
    }
    UUID sessionId = snapshot.thread().sessionId();
    for (AgentMessageContent content : msg.message().contents()) {
      if (content instanceof ResourceMessageContent res
          && isPublishableResource(res, binding, sessionId)) {
        issueEvidenceService.publishBlob(
            issue.getId(), binding.agentName(), run.getId(), res.blobId(), res.name());
      }
    }
  }

  /**
   * 确定性预校验 Resource 是否属于可发布的合法受管 Blob：非空、显示名合法、Agent 名合法、且 Blob 存在且为 ACTIVE 状态。
   *
   * <p>在调用 `@Transactional` 参与方 {@code publishBlob} 之前完成所有前置判定，避免异常逃逸导致共享事务被标记为 rollback-only。
   */
  private boolean isPublishableResource(
      ResourceMessageContent res, IssueAgentThread binding, UUID sessionId) {
    if (res == null || res.blobId() == null || sessionId == null) {
      return false;
    }
    if (!evidenceBlobPort.isSessionBlobRef(sessionId, res.blobId())) {
      return false;
    }
    if (binding == null || binding.agentName() == null || binding.agentName().isBlank()) {
      return false;
    }
    String name = res.name();
    if (name == null
        || name.isBlank()
        || !name.equals(name.strip())
        || name.codePointCount(0, name.length()) > 512) {
      return false;
    }
    String agentName = binding.agentName();
    if (agentName.indexOf('/') >= 0
        || !agentName.equals(agentName.strip())
        || agentName.codePointCount(0, agentName.length()) > 64) {
      return false;
    }
    return evidenceBlobPort.isBlobActive(res.blobId());
  }

  /**
   * 在途命令、模型或工具仍未静止时，Run 不得投递指示或收尾。
   *
   * <p>结果未定（UNKNOWN）的工具是已收敛的 Loop 反馈，会由 Runtime 交给下一轮模型，因此这里不再把它升级为阻塞 Thread 的人工核查门禁； 永久子树的忙碌也不阻塞本
   * Run 收尾。
   */
  private boolean isProcessing(ThreadSnapshot snapshot) {
    if (snapshot == null) {
      return false;
    }
    if (!snapshot.queuedCommands().isEmpty()) {
      return true;
    }
    if (snapshot.model() != null
        && (snapshot.model().status() == null || !snapshot.model().status().isTerminal())) {
      return true;
    }
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      ToolInvocationStatus status = tool.status();
      if (status == null
          || status == ToolInvocationStatus.READY
          || status == ToolInvocationStatus.DISPATCHING
          || status == ToolInvocationStatus.RUNNING) {
        return true;
      }
    }
    if (snapshot.entryPath() != null) {
      Entry head = snapshot.entryPath().head();
      if (head != null && head.payload() instanceof TurnEndPayload end && end.continueModel()) {
        return true;
      }
    }
    return false;
  }

  /** 本次 Run 的 root Join 已冻结结果时返回它，否则返回 {@code null}。 */
  private ThreadJoin matchedJoin(UUID invocationId) {
    return requireRuntime().findJoin(invocationId).filter(ThreadJoin::matched).orElse(null);
  }

  /** 判断是否存在处于安全等待点（等待输入或等待审批）的工具调用。 */
  private static boolean hasWaitingTool(ThreadSnapshot snapshot) {
    if (snapshot == null) {
      return false;
    }
    for (ToolInvocation tool : snapshot.toolSiblings()) {
      ToolInvocationStatus status = tool.status();
      if (status == ToolInvocationStatus.WAITING_APPROVAL
          || status == ToolInvocationStatus.WAITING_INPUT) {
        return true;
      }
    }
    return false;
  }

  /**
   * 读取投递给当前 Run 的下一条 INSTRUCTION：跳过普通评论与系统事件，扫描至活动流尾，绝不因它们永久卡住游标。
   *
   * <p>只按「活动事实位置是否已被任何 Run 观察」判定，不要求指示恰好绑定当前 Run：上一个 Run 因匹配 root Join 安全收尾时未在安全点投递的
   * 业务指示（游标未推进）会保留给下一个 Run 投递，绝不被收尾吞掉。
   */
  private IssueActivity nextDeliverableInstruction(IssueRun run) {
    long cursor = run.getObservedActivitySequence();
    long maxSeen = cursor;
    while (true) {
      List<IssueActivity> window =
          issueActivityRepository.listPage(run.getIssueId(), cursor, ACTIVITY_SCAN_LIMIT);
      if (window.isEmpty()) {
        break;
      }
      for (IssueActivity activity : window) {
        maxSeen = Math.max(maxSeen, activity.getSequence());
        if (activity.getKind() == IssueActivityKind.INSTRUCTION) {
          if (activity.getSequence() > run.getObservedActivitySequence() + 1) {
            advanceObservedSequence(run, activity.getSequence() - 1);
          }
          return activity;
        }
      }
      cursor = maxSeen;
      if (window.size() < ACTIVITY_SCAN_LIMIT) {
        break;
      }
    }
    if (maxSeen > run.getObservedActivitySequence()) {
      advanceObservedSequence(run, maxSeen);
    }
    return null;
  }

  private void advanceObservedSequence(IssueRun run, long sequence) {
    if (sequence <= run.getObservedActivitySequence()) {
      return;
    }
    long expectedVersion = run.getVersion();
    run.setObservedActivitySequence(sequence);
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new IllegalStateException("failed to advance run activity cursor");
    }
    run.setVersion(expectedVersion + 1);
  }

  /** 把来自人的定向指示作为 USER_MESSAGE 投递给明确的当前 Run；幂等键由 Issue 事实位置确定，重放不会重复入队。 */
  private void deliverInstruction(
      Issue issue, IssueRun run, ThreadSnapshot snapshot, IssueActivity activity) {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent(activity.getBody())));
    UserMessageCommandPayload payload = new UserMessageCommandPayload(message);
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("issue-instruction:" + issue.getId() + ":" + activity.getSequence())
                .getBytes(StandardCharsets.UTF_8));
    NewThreadCommand command = new NewThreadCommand(payload, idempotencyKey);
    requireRuntime()
        .acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    run.getThreadId(),
                    snapshot.thread().headEntryId(),
                    snapshot.thread().nextCommandSequence()),
                List.of(command)),
            AcceptancePreflight.IDENTITY);
    advanceObservedSequence(run, activity.getSequence());
  }

  private ProjectWorkflowState workflowStage(Project project, String state) {
    try {
      ProjectWorkflow workflow = workflowCodec.decode(project.getWorkflowJson());
      return workflow.find(ProjectStateCode.of(state)).orElse(null);
    } catch (IllegalArgumentException error) {
      return null;
    }
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }

  /** 接受 key 由 Issue 内待分配的 Run 序号确定：同一次催促重放不会创建第二个 Run。 */
  private static String acceptKey(Issue issue) {
    return "reconciler-accept:" + issue.getNextRunOrdinal();
  }

  private static String completeKey(IssueRun run) {
    return "reconciler-complete:" + run.getId() + ":" + run.getVersion();
  }

  private static String failKey(IssueRun run, UUID endEntryId) {
    return "reconciler-fail:" + run.getId() + ":" + endEntryId;
  }

  private static String cancelKey(IssueRun run, UUID endEntryId) {
    return "reconciler-cancel:" + run.getId() + ":" + endEntryId;
  }

  /** 预算 Stop 的稳定幂等键：同一 Run 的预算停止重放不会产生第二次 Stop。 */
  private static UUID budgetStopRequestId(UUID runId) {
    return UUID.nameUUIDFromBytes(
        ("issue-run-budget-stop:" + runId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 一次 claim 的最小事实：Issue、租约令牌、到期时间与领取到的唤醒版本。
   *
   * <p>{@code wakeVersion} 只用于「完成并删除」的围栏；归还 lease 用 {@code leaseToken} 围栏，因此处理期间到达的新唤醒不会丢。
   */
  public record IssueWorkClaim(
      UUID issueId, String leaseToken, Instant leaseUntil, long wakeVersion) {

    public IssueWorkClaim {
      Objects.requireNonNull(issueId, "issueId");
      Objects.requireNonNull(leaseToken, "leaseToken");
      Objects.requireNonNull(leaseUntil, "leaseUntil");
    }
  }

  private record LockedIssue(Project project, Issue issue) {}
}
