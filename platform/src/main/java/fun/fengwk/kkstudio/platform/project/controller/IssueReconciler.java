package fun.fengwk.kkstudio.platform.project.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.Inspection;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.InspectionStatus;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.QualifiedSubmission;
import fun.fengwk.kkstudio.platform.project.model.ClaimedIssueWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 确定性 Issue Controller 状态机。
 *
 * <p>每次调用只执行一个有界动作。锁序固定为 Project (FOR SHARE) -&gt; Issue (FOR UPDATE) -&gt; IssueRun (FOR UPDATE)
 * -&gt; project_issue_work；租约 fencing 失败直接中止事务。
 */
@Service
public class IssueReconciler {

  private static final String DEADLINE_REASON = "Run deadline exceeded";
  private static final String HARNESS_UNKNOWN_REASON = "Harness state is unknown";
  private static final String BUDGET_REASON = "Continuation budget exhausted";
  private static final String CANCEL_REASON = "Issue was canceled";

  /** 单次 reconcile 检视的 Activity 窗口上限：只读有界窗口，绝不无界加载整条事实流；窗口未到流尾时先推进已检视游标再立即续扫。 */
  private static final int ACTIVITY_SCAN_LIMIT = 200;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueDependencyRepository issueDependencyRepository;
  private final IssueService issueService;
  private final IssueRunService issueRunService;
  private final IssueWorkStore workStore;
  private final IssueHarnessController harnessController;
  private final IssueControllerProperties properties;
  private final Clock clock;

  @Autowired
  public IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueActivityRepository issueActivityRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      IssueDependencyRepository issueDependencyRepository,
      IssueService issueService,
      IssueRunService issueRunService,
      IssueWorkStore workStore,
      IssueHarnessController harnessController,
      IssueControllerProperties properties) {
    this(
        projectRepository,
        issueRepository,
        issueRunRepository,
        issueActivityRepository,
        issueAgentSessionRepository,
        issueDependencyRepository,
        issueService,
        issueRunService,
        workStore,
        harnessController,
        properties,
        Clock.systemUTC());
  }

  IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueActivityRepository issueActivityRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      IssueDependencyRepository issueDependencyRepository,
      IssueService issueService,
      IssueRunService issueRunService,
      IssueWorkStore workStore,
      IssueHarnessController harnessController,
      IssueControllerProperties properties,
      Clock clock) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueActivityRepository =
        Objects.requireNonNull(issueActivityRepository, "issueActivityRepository");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.issueDependencyRepository =
        Objects.requireNonNull(issueDependencyRepository, "issueDependencyRepository");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.issueRunService = Objects.requireNonNull(issueRunService, "issueRunService");
    this.workStore = Objects.requireNonNull(workStore, "workStore");
    this.harnessController = Objects.requireNonNull(harnessController, "harnessController");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
  }

  /** 调谐一次已领取的 work。 */
  @Transactional
  public IssueReconcileOutcome reconcile(ClaimedIssueWork claim) {
    validateClaim(claim);
    Instant now = clock.instant();

    LockedIssue locked = lockIssue(claim.getIssueId());
    if (locked == null) {
      return IssueReconcileOutcome.SKIPPED_CONVERGED;
    }
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(claim.getIssueId());
    IssueRun latestRun = activeRun != null ? activeRun : lockLatestRun(claim.getIssueId());

    Instant leaseBase = claim.getLeaseUntil().isAfter(now) ? claim.getLeaseUntil() : now;
    workStore.renewLease(
        claim.getIssueId(),
        claim.getLeaseToken(),
        now,
        leaseBase.plus(properties.getLeaseDuration()));

    Issue issue = locked.issue();
    Project project = locked.project();
    if (issue.getStatus() == IssueStatus.CANCELED) {
      return cancel(issue, activeRun, latestRun, claim, now);
    }
    if (issue.isArchived()
        || issue.getStatus() == IssueStatus.BACKLOG
        || issue.getStatus() == IssueStatus.DONE) {
      requireNoActiveRun(activeRun);
      complete(claim, now);
      return IssueReconcileOutcome.SKIPPED_CONVERGED;
    }
    if (issue.getStatus() == IssueStatus.BLOCKED) {
      requireNoActiveRun(activeRun);
      complete(claim, now);
      return IssueReconcileOutcome.WAITING_HUMAN_RECOVERY;
    }
    if (issue.getStatus() == IssueStatus.TODO) {
      requireNoActiveRun(activeRun);
      return reconcileTodo(project, issue, claim, now);
    }
    if (activeRun != null) {
      return reconcileActive(project, issue, activeRun, claim, now);
    }
    return reconcileWithoutActiveRun(project, issue, latestRun, claim, now);
  }

  private LockedIssue lockIssue(UUID issueId) {
    Issue reference = issueRepository.getById(issueId);
    if (reference == null) {
      return null;
    }
    // 与 IssueRunServiceImpl 等业务层保持同一锁模式（project FOR UPDATE -> issue FOR UPDATE）：
    // 同 Project 的业务决定串行化，避免先取 FOR SHARE 再在事务内升级为 FOR UPDATE 造成锁升级死锁。
    Project project = projectRepository.lockById(reference.getProjectId());
    if (project == null) {
      return null;
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null || !issue.getProjectId().equals(project.getId())) {
      return null;
    }
    return new LockedIssue(project, issue);
  }

  private IssueRun lockLatestRun(UUID issueId) {
    IssueRun latest = issueRunRepository.findLatestByIssueId(issueId);
    return latest == null ? null : issueRunRepository.lockById(latest.getId());
  }

  private IssueReconcileOutcome cancel(
      Issue issue, IssueRun activeRun, IssueRun latestRun, ClaimedIssueWork claim, Instant now) {
    IssueRun runToStop = activeRun;
    if (activeRun != null) {
      issueRunService.failRun(activeRun.getId(), IssueRunStatus.CANCELLED, CANCEL_REASON);
    } else if (latestRun != null && latestRun.getStatus() == IssueRunStatus.CANCELLED) {
      runToStop = latestRun;
    }
    if (runToStop != null) {
      harnessController.stopAfterCommit(issue.getId(), runToStop.getAgentName());
    }
    complete(claim, now);
    return IssueReconcileOutcome.SKIPPED_CONVERGED;
  }

  private IssueReconcileOutcome reconcileTodo(
      Project project, Issue issue, ClaimedIssueWork claim, Instant now) {
    if (project.isArchived()) {
      defer(claim, now, properties.getBlockedDelay());
      return IssueReconcileOutcome.DEFERRED_ARCHIVED;
    }
    if (issueService.isBlocked(issue.getId())) {
      defer(claim, now, properties.getBlockedDelay());
      return IssueReconcileOutcome.DEFERRED_BLOCKED;
    }
    if (isBlank(issue.getAssigneeAgentName())) {
      complete(claim, now);
      return IssueReconcileOutcome.NO_ASSIGNEE;
    }
    IssueRun newRun =
        issueRunService.startExecutorRun(
            issue.getId(),
            issue.getAssigneeAgentName(),
            now.plus(properties.getRunTimeout()),
            properties.getMaxContinuations());
    harnessController.bootstrapIfAvailable(project, issue, newRun);
    defer(claim, now, properties.getActiveDelay());
    return IssueReconcileOutcome.EXECUTOR_STARTED;
  }

  private IssueReconcileOutcome reconcileActive(
      Project project, Issue issue, IssueRun run, ClaimedIssueWork claim, Instant now) {
    requireCoherentActiveRun(issue, run);
    if (run.getDeadline() != null && !now.isBefore(run.getDeadline())) {
      return failAndWake(
          run,
          IssueRunStatus.FAILED,
          DEADLINE_REASON,
          claim,
          now,
          IssueReconcileOutcome.RUN_DEADLINE_EXCEEDED);
    }

    Inspection inspection = harnessController.inspect(issue, run);
    if (inspection.status() == InspectionStatus.MISSING_SESSION) {
      harnessController.bootstrap(project, issue, run);
      defer(claim, now, properties.getActiveDelay());
      return IssueReconcileOutcome.SESSION_BOOTSTRAPPED;
    }
    if (inspection.status() == InspectionStatus.MISSING_THREAD
        || inspection.status() == InspectionStatus.UNKNOWN) {
      return failAndWake(
          run,
          IssueRunStatus.UNKNOWN,
          HARNESS_UNKNOWN_REASON,
          claim,
          now,
          IssueReconcileOutcome.RUN_UNKNOWN_HARNESS);
    }
    if (inspection.status() == InspectionStatus.PROCESSING) {
      defer(claim, now, properties.getActiveDelay());
      return IssueReconcileOutcome.RUN_PROCESSING;
    }

    ThreadSnapshot snapshot = inspection.requireQuiescentSnapshot();
    if (snapshot.thread().yoloEnabled() != project.isYoloEnabled()) {
      harnessController.alignThreadYolo(
          snapshot.thread().id(), snapshot.thread().version(), project.isYoloEnabled());
      defer(claim, now, properties.getActiveDelay());
      return IssueReconcileOutcome.YOLO_ALIGNED;
    }

    List<IssueActivity> pendingActivities =
        issueActivityRepository.listPage(
            issue.getId(), run.getObservedActivitySequence(), ACTIVITY_SCAN_LIMIT);
    boolean scanComplete = pendingActivities.size() < ACTIVITY_SCAN_LIMIT;
    IssueActivity activityToDeliver = null;
    long maxSeenSequence = run.getObservedActivitySequence();
    for (IssueActivity activity : pendingActivities) {
      maxSeenSequence = Math.max(maxSeenSequence, activity.getSequence());
      if (isTargetedToRun(activity, run)) {
        activityToDeliver = activity;
        break;
      }
    }

    if (run.isExecutor() && run.getStatus() == IssueRunStatus.RUNNING) {
      QualifiedSubmission submission =
          harnessController.findQualifiedSubmission(run, snapshot, activityToDeliver != null);
      if (submission != null) {
        String terminalActionId = "submit:" + run.getId() + ":" + submission.finalEntryId();
        issueRunService.completeExecutorRun(
            run.getId(), terminalActionId, submission.summary(), null);
        defer(claim, now, properties.getActiveDelay());
        return IssueReconcileOutcome.EXECUTOR_SUBMITTED;
      }
    }

    if (activityToDeliver != null) {
      harnessController.deliverActivity(
          issue, run, activityToDeliver, inspection.agentSession(), snapshot);
      run.setObservedActivitySequence(activityToDeliver.getSequence());
      if (run.getStatus() == IssueRunStatus.WAITING_HUMAN) {
        run.setStatus(IssueRunStatus.RUNNING);
        run.setWaitingReason(null);
      }
      updateRun(run, "Failed to record issue activity delivery");
      if (activityToDeliver.getSequence() < maxSeenSequence) {
        rescheduleNow(claim, now);
      } else {
        defer(claim, now, properties.getActiveDelay());
      }
      return IssueReconcileOutcome.ACTIVITY_DELIVERED;
    }

    if (maxSeenSequence > run.getObservedActivitySequence()) {
      run.setObservedActivitySequence(maxSeenSequence);
      updateRun(run, "Failed to advance observed activity sequence");
    }

    if (!scanComplete) {
      // 有界窗口已被完整检视但尚未到达事实流末端：已推进的游标只覆盖真正读过的窗口，立即接着扫描，
      // 绝不提前发继续或跳过窗口之后的定向输入。
      rescheduleNow(claim, now);
      return IssueReconcileOutcome.ACTIVITY_SCAN_RESCHEDULED;
    }

    if (run.getStatus() == IssueRunStatus.WAITING_HUMAN) {
      if (run.getDeadline() == null) {
        complete(claim, now);
      } else {
        deferUntil(claim, now, run.getDeadline());
      }
      return IssueReconcileOutcome.WAITING_FOR_HUMAN;
    }

    if (run.getContinuationCount() < run.getMaxContinuations()) {
      harnessController.sendSystemContinuation(issue, run, inspection.agentSession(), snapshot);
      run.setContinuationCount(run.getContinuationCount() + 1);
      updateRun(run, "Failed to record Harness continuation");
      defer(claim, now, properties.getActiveDelay());
      return IssueReconcileOutcome.SYSTEM_CONTINUATION_SENT;
    }

    return failAndWake(
        run,
        IssueRunStatus.FAILED,
        BUDGET_REASON,
        claim,
        now,
        IssueReconcileOutcome.BUDGET_EXHAUSTED);
  }

  private IssueReconcileOutcome reconcileWithoutActiveRun(
      Project project, Issue issue, IssueRun latestRun, ClaimedIssueWork claim, Instant now) {
    if (issue.getStatus() == IssueStatus.IN_REVIEW) {
      return reconcileReview(project, issue, latestRun, claim, now);
    }
    if (issue.getStatus() == IssueStatus.IN_PROGRESS) {
      return reconcileProgress(project, issue, latestRun, claim, now);
    }
    complete(claim, now);
    return IssueReconcileOutcome.SKIPPED_CONVERGED;
  }

  private IssueReconcileOutcome reconcileReview(
      Project project, Issue issue, IssueRun latestRun, ClaimedIssueWork claim, Instant now) {
    if (isBlank(issue.getReviewerAgentName())) {
      complete(claim, now);
      return IssueReconcileOutcome.WAITING_HUMAN_REVIEW;
    }
    if (issue.getReviewerAgentName().equals(issue.getAssigneeAgentName())) {
      complete(claim, now);
      return IssueReconcileOutcome.WAITING_HUMAN_REVIEW;
    }
    if (isFailed(latestRun)) {
      requireRole(latestRun, IssueRunRole.REVIEWER);
      if (hasRetryActivity(issue, latestRun)) {
        if (project.isArchived()) {
          defer(claim, now, properties.getBlockedDelay());
          return IssueReconcileOutcome.DEFERRED_ARCHIVED;
        }
        IssueRun newRun =
            issueRunService.startReviewerRun(
                issue.getId(),
                issue.getReviewerAgentName(),
                now.plus(properties.getRunTimeout()),
                properties.getMaxContinuations());
        harnessController.bootstrapIfAvailable(project, issue, newRun);
        defer(claim, now, properties.getActiveDelay());
        return IssueReconcileOutcome.REVIEWER_RETRY_STARTED;
      }
      complete(claim, now);
      return IssueReconcileOutcome.CONVERGED_FAILED;
    }
    if (latestRun != null
        && latestRun.getRole() == IssueRunRole.EXECUTOR
        && latestRun.getStatus() == IssueRunStatus.COMPLETED
        && latestRun.getOutcome() == IssueRunOutcome.SUBMITTED) {
      if (project.isArchived()) {
        defer(claim, now, properties.getBlockedDelay());
        return IssueReconcileOutcome.DEFERRED_ARCHIVED;
      }
      IssueRun newRun =
          issueRunService.startReviewerRun(
              issue.getId(),
              issue.getReviewerAgentName(),
              now.plus(properties.getRunTimeout()),
              properties.getMaxContinuations());
      harnessController.bootstrapIfAvailable(project, issue, newRun);
      defer(claim, now, properties.getActiveDelay());
      return IssueReconcileOutcome.REVIEWER_STARTED;
    }
    complete(claim, now);
    return IssueReconcileOutcome.SKIPPED_CONVERGED;
  }

  private IssueReconcileOutcome reconcileProgress(
      Project project, Issue issue, IssueRun latestRun, ClaimedIssueWork claim, Instant now) {
    if (!isFailed(latestRun)) {
      complete(claim, now);
      return IssueReconcileOutcome.SKIPPED_CONVERGED;
    }
    requireRole(latestRun, IssueRunRole.EXECUTOR);
    if (!hasRetryActivity(issue, latestRun)) {
      complete(claim, now);
      return IssueReconcileOutcome.CONVERGED_FAILED;
    }
    if (project.isArchived()) {
      defer(claim, now, properties.getBlockedDelay());
      return IssueReconcileOutcome.DEFERRED_ARCHIVED;
    }
    if (issueService.isBlocked(issue.getId())) {
      defer(claim, now, properties.getBlockedDelay());
      return IssueReconcileOutcome.DEFERRED_BLOCKED;
    }
    if (isBlank(issue.getAssigneeAgentName())) {
      complete(claim, now);
      return IssueReconcileOutcome.NO_ASSIGNEE;
    }
    IssueRun newRun =
        issueRunService.startExecutorRun(
            issue.getId(),
            issue.getAssigneeAgentName(),
            now.plus(properties.getRunTimeout()),
            properties.getMaxContinuations());
    harnessController.bootstrapIfAvailable(project, issue, newRun);
    defer(claim, now, properties.getActiveDelay());
    return IssueReconcileOutcome.RETRY_RUN_STARTED;
  }

  private IssueReconcileOutcome failAndWake(
      IssueRun run,
      IssueRunStatus status,
      String reason,
      ClaimedIssueWork claim,
      Instant now,
      IssueReconcileOutcome outcome) {
    issueRunService.failRun(run.getId(), status, reason);
    Issue issue = issueRepository.getById(run.getIssueId());
    if (issue != null) {
      harnessController.stopAfterCommit(issue.getId(), run.getAgentName());
    }
    rescheduleNow(claim, now);
    return outcome;
  }

  private void updateRun(IssueRun run, String message) {
    if (!issueRunRepository.updateById(run, run.getVersion())) {
      throw new IllegalStateException(message);
    }
  }

  private boolean isTargetedToRun(IssueActivity activity, IssueRun run) {
    if (activity.getKind() == IssueActivityKind.COMMENT) {
      return false;
    }
    if (activity.getTargetRole() != null && activity.getTargetRole() != run.getRole()) {
      return false;
    }
    if (activity.getRunId() != null && !activity.getRunId().equals(run.getId())) {
      return false;
    }
    return true;
  }

  private boolean hasRetryActivity(Issue issue, IssueRun run) {
    return issueActivityRepository.existsAfterSequenceAndKind(
        issue.getId(), run.getObservedActivitySequence(), IssueActivityKind.RETRY);
  }

  private void complete(ClaimedIssueWork claim, Instant now) {
    workStore.completeWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now);
  }

  private void defer(ClaimedIssueWork claim, Instant now, Duration delay) {
    workStore.rescheduleWork(
        claim.getIssueId(),
        claim.getLeaseToken(),
        claim.getClaimedWakeVersion(),
        now,
        now.plus(delay));
  }

  private void rescheduleNow(ClaimedIssueWork claim, Instant now) {
    workStore.rescheduleWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now, now);
  }

  private void deferUntil(ClaimedIssueWork claim, Instant now, Instant dueAt) {
    workStore.rescheduleWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now, dueAt);
  }

  private void requireNoActiveRun(IssueRun activeRun) {
    if (activeRun != null) {
      throw new IllegalStateException("Issue status conflicts with an active IssueRun");
    }
  }

  private void requireCoherentActiveRun(Issue issue, IssueRun run) {
    IssueRunRole expectedRole =
        issue.getStatus() == IssueStatus.IN_PROGRESS
            ? IssueRunRole.EXECUTOR
            : IssueRunRole.REVIEWER;
    if (run.getRole() != expectedRole) {
      throw new IllegalStateException("Issue status conflicts with the active IssueRun role");
    }
  }

  private void requireRole(IssueRun run, IssueRunRole role) {
    if (run.getRole() != role) {
      throw new IllegalStateException("Issue status conflicts with the latest IssueRun role");
    }
  }

  private boolean isFailed(IssueRun run) {
    return run != null
        && (run.getStatus() == IssueRunStatus.FAILED || run.getStatus() == IssueRunStatus.UNKNOWN);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private void validateClaim(ClaimedIssueWork claim) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(claim.getIssueId(), "claim.issueId");
    Objects.requireNonNull(claim.getLeaseToken(), "claim.leaseToken");
    Objects.requireNonNull(claim.getLeaseUntil(), "claim.leaseUntil");
    if (claim.getLeaseToken().isBlank()) {
      throw new IllegalArgumentException("claim.leaseToken must not be blank");
    }
    if (claim.getClaimedWakeVersion() <= 0) {
      throw new IllegalArgumentException("claim.claimedWakeVersion must be positive");
    }
  }

  private record LockedIssue(Project project, Issue issue) {}
}
