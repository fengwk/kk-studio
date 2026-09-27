package fun.fengwk.kkstudio.platform.project.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;

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
 *   <li><b>安全等待</b>：只有执行完全静止（无 queued command、无模型/工具在途、无 continuation 义务）才置 WAITING 或收尾；问卷出现但在途
 *       执行未停时绝不宣称已暂停。
 *   <li><b>Run 收尾</b>：正常收尾要求 {@code (start,end]} 真实落在该 Thread 的历史路径上；无交接时保持当前阶段，之后的催促按额度新建 Run。
 *   <li><b>失败暂停原子</b>：活动时长耗尽、迟到旧 Run 都通过与 ERROR 暂停门禁同事务的失败收尾收敛，绝不留下半套事实。
 *   <li><b>迟到回调拒绝</b>：Run 冻结的阶段与归属必须仍与当前 Issue 阶段一致，否则只安全收尾而不推进阶段。
 *   <li><b>交接必须无未处理 command/invocation</b>：{@code next_state} 只在静止点提交，未处理输入绝不带进新历史区间。
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
  private final IssueWorkStore issueWorkStore;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
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
      IssueWorkStore issueWorkStore,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
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
        issueWorkStore,
        acceptanceOrchestrator,
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
      IssueWorkStore issueWorkStore,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
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
    this.issueWorkStore = Objects.requireNonNull(issueWorkStore, "issueWorkStore");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
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
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_ARCHIVED);
    }
    heartbeat(claim, now);

    Project project = locked.project();
    Issue issue = locked.issue();
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (issue.isArchived()
        || ProjectWorkflowReservedState.DONE.code().value().equals(issue.getState())) {
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_ARCHIVED);
    }
    if (activeRun != null) {
      return reconcileActiveRun(project, issue, activeRun, claim, now);
    }
    return reconcileIdle(project, issue, claim, now);
  }

  private IssueReconcileOutcome reconcileActiveRun(
      Project project, Issue issue, IssueRun run, IssueWorkClaim claim, Instant now) {
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(run.getThreadId());
    if (binding == null || !binding.issueId().equals(issue.getId())) {
      // 归属已不存在：Run 无法再被驱动，安全收尾为失败并显式要求人工核查。
      return failRun(
          run, currentHead(run), STALE_REASON, claim, now, IssueReconcileOutcome.STALE_RUN_CLOSED);
    }
    ProjectWorkflowState stage = workflowStage(project, run.getState());
    boolean stageStillOwned =
        stage != null && stage.enabled() && stage.agent().equals(binding.agentName());
    boolean stateStillCurrent =
        run.getState().equals(issue.getState())
            || (issue.getBlockedFromState() != null
                && run.getState().equals(issue.getBlockedFromState()));
    if (!stageStillOwned || !stateStillCurrent) {
      // 迟到回调：旧 Run 的同名阶段或旧归属不得刷新当前职责，只做安全收尾，绝不提交交接目标。
      ThreadSnapshot staleSnapshot = requireRuntime().getThreadSnapshot(run.getThreadId());
      if (hasProgress(run, staleSnapshot)) {
        issueRunService.completeRun(
            run.getId(),
            run.getVersion(),
            completeKey(run),
            staleSnapshot.thread().headEntryId(),
            null,
            null);
        return finish(
            claim,
            now,
            true,
            now.plus(properties.getActiveDelay()),
            IssueReconcileOutcome.STALE_RUN_CLOSED);
      }
      return failRun(
          run,
          staleSnapshot.thread().headEntryId(),
          STALE_REASON,
          claim,
          now,
          IssueReconcileOutcome.STALE_RUN_CLOSED);
    }

    ThreadSnapshot snapshot = requireRuntime().getThreadSnapshot(run.getThreadId());
    if (run.getStatus() == IssueRunStatus.RUNNING && !chargeActiveTime(run, now)) {
      return failRun(
          run,
          snapshot.thread().headEntryId(),
          BUDGET_REASON,
          claim,
          now,
          IssueReconcileOutcome.RUN_BUDGET_EXHAUSTED);
    }
    if (!snapshot.queuedCommands().isEmpty() || isProcessing(snapshot)) {
      // 未处理命令或在途调用：不投递、不等待、不收尾。
      return finish(
          claim,
          now,
          true,
          now.plus(properties.getActiveDelay()),
          IssueReconcileOutcome.DEFERRED_PROCESSING);
    }

    if (run.getStatus() == IssueRunStatus.WAITING) {
      if (issue.isPaused()) {
        return finish(
            claim,
            now,
            true,
            now.plus(properties.getBlockedDelay()),
            IssueReconcileOutcome.WAITING_FOR_GATE);
      }
      // 最后一次额度被等待消耗时，恢复的是同一 Run：不新建 Run，也不重新扣额度。
      issueRunService.resumeRun(run.getId(), run.getVersion());
      return finish(
          claim,
          now,
          true,
          now.plus(properties.getActiveDelay()),
          IssueReconcileOutcome.RUN_RESUMED);
    }

    if (issue.isPaused()) {
      // 门禁关闭：到达安全点后才停止活动计时，保留交接目标等待显式恢复。
      issueRunService.waitRun(run.getId(), run.getVersion());
      return finish(
          claim,
          now,
          true,
          now.plus(properties.getBlockedDelay()),
          IssueReconcileOutcome.WAITING_FOR_GATE);
    }

    if (run.getNextState() != null) {
      // 交接只在完全静止点提交：没有 queued command、没有在途模型/工具调用。
      issueRunService.completeRun(
          run.getId(),
          run.getVersion(),
          completeKey(run),
          snapshot.thread().headEntryId(),
          null,
          run.getNextState());
      return finish(claim, now, true, now, IssueReconcileOutcome.RUN_HANDED_OFF);
    }

    IssueActivity deliverable = nextDeliverableInstruction(run);
    if (deliverable != null) {
      deliverInstruction(issue, run, binding, snapshot, deliverable);
      return finish(claim, now, true, now, IssueReconcileOutcome.INSTRUCTION_DELIVERED);
    }

    if (hasProgress(run, snapshot)) {
      issueRunService.completeRun(
          run.getId(),
          run.getVersion(),
          completeKey(run),
          snapshot.thread().headEntryId(),
          null,
          null);
      // 保持当前阶段：下一次催促必须重新检查额度后新建 Run。
      return finish(claim, now, true, now, IssueReconcileOutcome.RUN_COMPLETED);
    }
    return finish(
        claim,
        now,
        true,
        now.plus(properties.getActiveDelay()),
        IssueReconcileOutcome.DEFERRED_IDLE);
  }

  private IssueReconcileOutcome reconcileIdle(
      Project project, Issue issue, IssueWorkClaim claim, Instant now) {
    if (issue.isPaused()) {
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE);
    }
    ProjectStateCode state = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_NO_AGENT);
    }
    ProjectWorkflowState stage = workflowStage(project, issue.getState());
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_NO_AGENT);
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
            now,
            true,
            now.plus(properties.getBlockedDelay()),
            IssueReconcileOutcome.CONVERGED_BUDGET_EXHAUSTED);
      }
    }
    try {
      issueRunService.acceptRun(issue.getId(), acceptKey(issue));
    } catch (RuntimeException error) {
      // 接受期的确定性拒绝（归档、暂停、已有活动 Run、缺 Agent 等）不构成调度失败。
      log.debug(
          "Issue run was not accepted; issueId={}, failure={}",
          issue.getId(),
          error.getClass().getName());
      return finish(claim, now, false, now, IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE);
    }
    return finish(
        claim,
        now,
        true,
        now.plus(properties.getActiveDelay()),
        IssueReconcileOutcome.RUN_ACCEPTED);
  }

  /**
   * 结束一次 claim：{@code keepPolling} 为真时归还 lease 并把 {@code due_at} 推后（mailbox 保留），否则完成并删除 mailbox 行。
   *
   * <p>两种情况都以调用方自己的 lease token 围栏；围栏未匹配说明 lease 已被接管或并发新唤醒已到达，此时新事实由下一次 claim 处理，因此只记录而不抛出。
   */
  private IssueReconcileOutcome finish(
      IssueWorkClaim claim,
      Instant now,
      boolean keepPolling,
      Instant dueAt,
      IssueReconcileOutcome outcome) {
    boolean applied =
        keepPolling
            ? issueWorkStore.rescheduleWork(claim.issueId(), claim.leaseToken(), dueAt)
            : issueWorkStore.completeWork(
                claim.issueId(), claim.leaseToken(), claim.wakeVersion(), now);
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

  /** 领取即续租：把 lease 至少延长一个完整租期，处理时长不再受初始租期限制。 */
  private void heartbeat(IssueWorkClaim claim, Instant now) {
    Instant base = claim.leaseUntil().isAfter(now) ? claim.leaseUntil() : now;
    issueWorkStore.renewLease(
        claim.issueId(), claim.leaseToken(), now, base.plus(properties.getLeaseDuration()));
  }

  /** 扣减本次 Run 的活动执行时长；返回是否仍有可用额度。 */
  private boolean chargeActiveTime(IssueRun run, Instant now) {
    if (run.getActiveSince() == null) {
      return true;
    }
    long elapsed = Math.max(0L, Duration.between(run.getActiveSince(), now).toMillis());
    long remaining = run.getRemainingExecutionMs() - elapsed;
    if (remaining <= 0) {
      return false;
    }
    long expectedVersion = run.getVersion();
    run.setRemainingExecutionMs(remaining);
    run.setActiveSince(now);
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new IllegalStateException("failed to charge run execution budget");
    }
    run.setVersion(expectedVersion + 1);
    return true;
  }

  private IssueReconcileOutcome failRun(
      IssueRun run,
      UUID endEntryId,
      String reason,
      IssueWorkClaim claim,
      Instant now,
      IssueReconcileOutcome outcome) {
    issueRunService.failRun(
        run.getId(), run.getVersion(), failKey(run, endEntryId), endEntryId, reason);
    // 失败与 ERROR 暂停门禁同事务写入，等待显式恢复；mailbox 保留以便恢复后立即推进。
    return finish(claim, now, true, now.plus(properties.getBlockedDelay()), outcome);
  }

  private boolean hasProgress(IssueRun run, ThreadSnapshot snapshot) {
    EntryPath path = snapshot.entryPath();
    List<UUID> entryIds = path.entries().stream().map(Entry::id).toList();
    int start = entryIds.indexOf(run.getStartEntryId());
    return start >= 0 && entryIds.size() - 1 > start;
  }

  private UUID currentHead(IssueRun run) {
    return requireRuntime().getThreadSnapshot(run.getThreadId()).thread().headEntryId();
  }

  /** 分类工具 sibling：存在等待审批/回答的调用也是"静止但未完成"的安全点。 */
  private static boolean isProcessing(ThreadSnapshot snapshot) {
    if (snapshot.model() != null || !snapshot.toolSiblings().isEmpty()) {
      return true;
    }
    Entry head = snapshot.entryPath().head();
    return head.payload() instanceof TurnEndPayload end && end.continueModel();
  }

  /** 读取投递给当前 Run 的下一条 INSTRUCTION：跳过普通评论与系统事件，绝不因它们永久卡住游标。 */
  private IssueActivity nextDeliverableInstruction(IssueRun run) {
    List<IssueActivity> window =
        issueActivityRepository.listPage(
            run.getIssueId(), run.getObservedActivitySequence(), ACTIVITY_SCAN_LIMIT);
    long maxSeen = run.getObservedActivitySequence();
    IssueActivity target = null;
    for (IssueActivity activity : window) {
      maxSeen = Math.max(maxSeen, activity.getSequence());
      if (activity.getKind() == IssueActivityKind.INSTRUCTION
          && run.getId().equals(activity.getRunId())) {
        target = activity;
        break;
      }
    }
    if (target == null && maxSeen > run.getObservedActivitySequence()) {
      // 已分类的普通评论与系统事件直接跳过，避免游标被永久卡住。
      advanceObservedSequence(run, maxSeen);
    }
    return target;
  }

  private void advanceObservedSequence(IssueRun run, long sequence) {
    long expectedVersion = run.getVersion();
    run.setObservedActivitySequence(sequence);
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new IllegalStateException("failed to advance run activity cursor");
    }
    run.setVersion(expectedVersion + 1);
  }

  /** 把定向指示作为 USER 消息投递给明确的当前 Run；幂等键由 Issue 事实位置确定，重放不会重复入队。 */
  private void deliverInstruction(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      ThreadSnapshot snapshot,
      IssueActivity activity) {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent(activity.getBody())));
    CustomMessageCommandPayload payload = new CustomMessageCommandPayload(message);
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("issue-instruction:" + issue.getId() + ":" + activity.getSequence())
                .getBytes(StandardCharsets.UTF_8));
    NewThreadCommand command = new NewThreadCommand(payload, idempotencyKey);
    acceptanceOrchestrator.accept(
        new OwnerRef.IssueAgent(issue.getId(), binding.agentName()),
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                run.getThreadId(),
                snapshot.thread().headEntryId(),
                snapshot.thread().nextCommandSequence()),
            List.of(command)));
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
