package fun.fengwk.kkstudio.project.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.time.MillisecondTime;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *   <li><b>预算耗尽安全收尾</b>：活动时长耗尽时对当前 Thread 发起显式 Stop，并以 Stop 回执的权威 head 作为收尾区间，在同一业务事务失败收尾。
 *   <li><b>门禁与安全等待</b>：控制暂停在活动 Run 收尾后设置，阻止新 Run 派发；活动 Run 在安全点因业务阻塞或安全工具等待进入 WAITING。
 *   <li><b>指示不丢</b>：定向指示按活动事实位置投递，普通评论与系统事件不卡游标；root Join 冻结先收尾时未投递的指示保留游标，由下一个 Run 处理。
 *   <li><b>完成只认 root Join</b>：Run 结束只读取本次 root Join 冻结的 terminal/final-answer entry，既不按快照最新 head
 *       猜测，也不等待永久子树 idle；工具结果未定作为 Loop 反馈处理。
 *   <li><b>Run 收尾</b>：正常收尾冻结真实历史区间与 final-answer entry，只发布该最终答复明确引用且来源 Session 持有的
 *       Resource；无交接时保持当前阶段，之后的催促按额度新建 Run。
 *   <li><b>迟到回调拒绝</b>：旧 Run 只做安全收尾，绝不提交旧交接目标推进当前阶段；未冻结 root Join 的迟到 Run 仍在静止点才失败收尾。
 *   <li><b>确定性调度决策</b>：在写路径前完成归档/门禁/额度/阶段/证据合法性判定，不以全局 try-catch 吞掉数据库或编程异常。
 * </ul>
 */
@Slf4j
@Service
public class IssueReconciler {

  /** Issue Reconcile 在 {@code durable_work} 上的 target type。 */
  public static final String TARGET_TYPE = "ISSUE";

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueStageBudgetRepository stageBudgetRepository;
  private final IssueRunService issueRunService;
  private final DurableWorkStore issueWorkQueue;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final IssueReconcileProperties properties;
  private final IssueReconcileActiveRun activeRun;
  private final Clock clock;

  @Autowired
  public IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueStageBudgetRepository stageBudgetRepository,
      IssueRunService issueRunService,
      DurableWorkStore issueWorkQueue,
      ProjectWorkflowJsonCodec workflowCodec,
      IssueReconcileProperties properties,
      IssueReconcileActiveRun activeRun) {
    this(
        projectRepository,
        issueRepository,
        issueRunRepository,
        stageBudgetRepository,
        issueRunService,
        issueWorkQueue,
        workflowCodec,
        properties,
        activeRun,
        Clock.systemUTC());
  }

  IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueStageBudgetRepository stageBudgetRepository,
      IssueRunService issueRunService,
      DurableWorkStore issueWorkQueue,
      ProjectWorkflowJsonCodec workflowCodec,
      IssueReconcileProperties properties,
      IssueReconcileActiveRun activeRun,
      Clock clock) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.stageBudgetRepository =
        Objects.requireNonNull(stageBudgetRepository, "stageBudgetRepository");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.issueWorkQueue = Objects.requireNonNull(issueWorkQueue, "issueWorkQueue");
    this.workflowCodec = Objects.requireNonNull(workflowCodec, "workflowCodec");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.activeRun = Objects.requireNonNull(activeRun, "activeRun");
    this.clock = MillisecondTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
  }

  /** 调谐一次已领取的 work；整个动作在单个物理事务内完成，外部执行从不在锁内发生。 */
  @Transactional
  public IssueReconcileOutcome reconcile(WorkClaim claim) {
    validateClaim(claim);
    Instant now = clock.instant();
    LockedIssue locked = lockIssue(claim.targetId());
    if (locked == null) {
      return apply(claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_ARCHIVED));
    }
    heartbeat(claim);

    Project project = locked.project();
    Issue issue = locked.issue();
    if (project.isArchived()
        || ProjectWorkflowReservedState.DONE.code().value().equals(issue.getState())) {
      return apply(claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_ARCHIVED));
    }
    IssueRun run = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (run != null) {
      return apply(claim, activeRun.step(project, issue, run, now));
    }
    return reconcileIdle(project, issue, claim);
  }

  private IssueReconcileOutcome reconcileIdle(Project project, Issue issue, WorkClaim claim) {
    if (project.isArchived()
        || ProjectWorkflowReservedState.DONE.code().value().equals(issue.getState())) {
      return apply(claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_ARCHIVED));
    }
    if (issue.isGateClosed()) {
      return apply(
          claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE));
    }
    ProjectStateCode state = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return apply(claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_NO_AGENT));
    }
    ProjectWorkflowState stage =
        IssueReconcileStages.find(workflowCodec, project, issue.getState());
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return apply(claim, IssueReconcileStep.converged(IssueReconcileOutcome.CONVERGED_NO_AGENT));
    }
    var budget = stageBudgetRepository.get(issue.getId(), issue.getState());
    if (budget != null && budget.usedRuns() >= budget.maxRuns()) {
      // 额度用尽只展示待人工重置：保留 mailbox 以便重置后立即推进。
      return apply(
          claim,
          IssueReconcileStep.poll(
              properties.getBlockedDelay(), IssueReconcileOutcome.CONVERGED_BUDGET_EXHAUSTED));
    }
    IssueRun active = issueRunRepository.lockActiveByIssueId(issue.getId());
    if (active != null) {
      return apply(
          claim,
          IssueReconcileStep.poll(
              properties.getActiveDelay(), IssueReconcileOutcome.DEFERRED_PROCESSING));
    }
    issueRunService.acceptRun(issue.getId(), IssueReconcileKeys.accept(issue));
    return apply(
        claim,
        IssueReconcileStep.poll(properties.getActiveDelay(), IssueReconcileOutcome.RUN_ACCEPTED));
  }

  /**
   * 结束一次 claim：{@code keepPolling} 为真时归还 lease 并把 {@code due_at} 推后（mailbox 保留），否则完成当前版本（有新 wake
   * 时只释放 lease，保留 mailbox）。
   *
   * <p>两种情况都以调用方自己的 lease token 围栏；返回 false 说明未删除（含新 wake 已释放 lease）或租约围栏未匹配，此时新事实由下一次 claim
   * 处理，因此只记录而不抛出。
   */
  private IssueReconcileOutcome apply(WorkClaim claim, IssueReconcileStep step) {
    boolean applied = finishClaim(claim, step.keepPolling(), step.delay());
    if (!applied) {
      log.debug(
          "Issue work claim was already superseded; issueId={}, leaseToken={}",
          claim.targetId(),
          claim.leaseToken());
    }
    return step.outcome();
  }

  /** 成功重排或删除当前 wake 为 true；保留更新 wake 或已经失权为 false。 */
  private boolean finishClaim(WorkClaim claim, boolean keepPolling, Duration delay) {
    if (issueWorkQueue.lockClaimed(claim).isEmpty()) {
      return false;
    }
    if (keepPolling) {
      issueWorkQueue.reschedule(claim, delay);
      return true;
    }
    return issueWorkQueue.complete(claim).isEmpty();
  }

  private void validateClaim(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(claim.targetId(), "claim.targetId");
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
  private void heartbeat(WorkClaim claim) {
    if (issueWorkQueue.lockClaimed(claim).isEmpty()) {
      throw new IllegalStateException("issue work lease is no longer held: " + claim.targetId());
    }
    issueWorkQueue.renew(claim, properties.getLeaseDuration());
  }

  private record LockedIssue(Project project, Issue issue) {}
}
