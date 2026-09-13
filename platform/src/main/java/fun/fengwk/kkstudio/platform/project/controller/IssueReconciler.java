package fun.fengwk.kkstudio.platform.project.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.Inspection;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.InspectionStatus;
import fun.fengwk.kkstudio.platform.project.model.ClaimedControllerWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 确定性 Issue Controller 状态机。
 *
 * <p>每次调用只执行一个有界动作。锁序固定为 Project (FOR SHARE) -&gt; Issue (FOR UPDATE) -&gt; IssueRun (FOR UPDATE)
 * -&gt; issue_controller_work；租约 fencing 失败直接中止事务。
 */
@Service
public class IssueReconciler {

  private static final String DEADLINE_REASON = "Run deadline exceeded";
  private static final String HARNESS_UNKNOWN_REASON = "Harness state is unknown";
  private static final String BUDGET_REASON = "Continuation budget exhausted";
  private static final String CANCEL_REASON = "Issue was canceled";

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueInputRepository issueInputRepository;
  private final IssueService issueService;
  private final IssueControllerWorkStore workStore;
  private final IssueHarnessController harnessController;
  private final IssueControllerProperties properties;
  private final Clock clock;

  @Autowired
  public IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueInputRepository issueInputRepository,
      IssueService issueService,
      IssueControllerWorkStore workStore,
      IssueHarnessController harnessController,
      IssueControllerProperties properties) {
    this(
        projectRepository,
        issueRepository,
        issueRunRepository,
        issueInputRepository,
        issueService,
        workStore,
        harnessController,
        properties,
        Clock.systemUTC());
  }

  IssueReconciler(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueInputRepository issueInputRepository,
      IssueService issueService,
      IssueControllerWorkStore workStore,
      IssueHarnessController harnessController,
      IssueControllerProperties properties,
      Clock clock) {
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
    this.issueInputRepository =
        Objects.requireNonNull(issueInputRepository, "issueInputRepository");
    this.issueService = Objects.requireNonNull(issueService, "issueService");
    this.workStore = Objects.requireNonNull(workStore, "workStore");
    this.harnessController = Objects.requireNonNull(harnessController, "harnessController");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = HarnessStoreTime.millisecondClock(Objects.requireNonNull(clock, "clock"));
  }

  /** 调谐一次已领取的 controller work。 */
  @Transactional
  public IssueReconcileOutcome reconcile(ClaimedControllerWork claim) {
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
    Project project = projectRepository.lockForShare(reference.getProjectId());
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
      Issue issue,
      IssueRun activeRun,
      IssueRun latestRun,
      ClaimedControllerWork claim,
      Instant now) {
    IssueRun runToStop = activeRun;
    if (activeRun != null) {
      transitionRun(activeRun, IssueRunStatus.CANCELLED, CANCEL_REASON, now);
    } else if (latestRun != null && latestRun.getStatus() == IssueRunStatus.CANCELLED) {
      runToStop = latestRun;
    }
    if (runToStop != null) {
      harnessController.stopAfterCommit(runToStop);
    }
    complete(claim, now);
    return IssueReconcileOutcome.SKIPPED_CONVERGED;
  }

  private IssueReconcileOutcome reconcileTodo(
      Project project, Issue issue, ClaimedControllerWork claim, Instant now) {
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
    startAgentRun(
        issue,
        IssueRunRole.EXECUTOR,
        issue.getAssigneeAgentName(),
        null,
        IssueStatus.IN_PROGRESS,
        claim,
        now);
    return IssueReconcileOutcome.EXECUTOR_STARTED;
  }

  private IssueReconcileOutcome reconcileActive(
      Project project, Issue issue, IssueRun run, ClaimedControllerWork claim, Instant now) {
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

    Inspection inspection = harnessController.inspect(run);
    if (inspection.status() == InspectionStatus.MISSING_SESSION) {
      harnessController.bootstrap(issue, run);
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

    IssueInput input =
        issueInputRepository.findFirstAfterSequence(issue.getId(), run.getObservedInputSequence());
    if (input != null) {
      harnessController.sendUserContinuation(issue, run, input, inspection);
      run.setObservedSpecRevision(issue.getSpecRevision());
      run.setObservedInputSequence(input.getSequence());
      if (run.getStatus() == IssueRunStatus.WAITING_HUMAN) {
        run.setStatus(IssueRunStatus.RUNNING);
        run.setWaitingReason(null);
      }
      updateRun(run, "Failed to record issue input delivery");
      if (input.getSequence() < issue.getInputSequence()) {
        rescheduleNow(claim, now);
      } else {
        defer(claim, now, properties.getActiveDelay());
      }
      return IssueReconcileOutcome.USER_CONTINUATION_SENT;
    }
    if (run.getStatus() == IssueRunStatus.WAITING_HUMAN) {
      harnessController.deliverAttention(project, issue, run);
      if (run.getDeadline() == null) {
        complete(claim, now);
      } else {
        deferUntil(claim, now, run.getDeadline());
      }
      return IssueReconcileOutcome.WAITING_FOR_HUMAN;
    }
    if (run.getContinuationCount() < run.getMaxContinuations()) {
      harnessController.sendSystemContinuation(issue, run, inspection);
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
      Project project, Issue issue, IssueRun latestRun, ClaimedControllerWork claim, Instant now) {
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
      Project project, Issue issue, IssueRun latestRun, ClaimedControllerWork claim, Instant now) {
    if (isBlank(issue.getReviewerAgentName())) {
      complete(claim, now);
      return IssueReconcileOutcome.WAITING_HUMAN_REVIEW;
    }
    if (isFailed(latestRun)) {
      requireRole(latestRun, IssueRunRole.REVIEWER);
      if (hasRetryInput(issue, latestRun)) {
        if (project.isArchived()) {
          defer(claim, now, properties.getBlockedDelay());
          return IssueReconcileOutcome.DEFERRED_ARCHIVED;
        }
        startAgentRun(
            issue,
            IssueRunRole.REVIEWER,
            issue.getReviewerAgentName(),
            latestRun.getSubmissionRunId(),
            null,
            claim,
            now);
        return IssueReconcileOutcome.REVIEWER_RETRY_STARTED;
      }
      harnessController.deliverAttention(project, issue, latestRun);
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
      startAgentRun(
          issue,
          IssueRunRole.REVIEWER,
          issue.getReviewerAgentName(),
          latestRun.getId(),
          null,
          claim,
          now);
      return IssueReconcileOutcome.REVIEWER_STARTED;
    }
    complete(claim, now);
    return IssueReconcileOutcome.SKIPPED_CONVERGED;
  }

  private IssueReconcileOutcome reconcileProgress(
      Project project, Issue issue, IssueRun latestRun, ClaimedControllerWork claim, Instant now) {
    if (!isFailed(latestRun)) {
      complete(claim, now);
      return IssueReconcileOutcome.SKIPPED_CONVERGED;
    }
    requireRole(latestRun, IssueRunRole.EXECUTOR);
    if (!hasRetryInput(issue, latestRun)) {
      harnessController.deliverAttention(project, issue, latestRun);
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
    startAgentRun(
        issue, IssueRunRole.EXECUTOR, issue.getAssigneeAgentName(), null, null, claim, now);
    return IssueReconcileOutcome.RETRY_RUN_STARTED;
  }

  private void startAgentRun(
      Issue issue,
      IssueRunRole role,
      String agentName,
      UUID submissionRunId,
      IssueStatus issueStatus,
      ClaimedControllerWork claim,
      Instant now) {
    IssueRun run =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issue.getId())
            .ordinal(issueRunRepository.allocateNextOrdinal(issue.getId()))
            .role(role)
            .actorType(IssueRunActorType.AGENT)
            .agentName(agentName)
            .submissionRunId(submissionRunId)
            .status(IssueRunStatus.RUNNING)
            .observedSpecRevision(issue.getSpecRevision())
            .observedInputSequence(issue.getInputSequence())
            .continuationCount(0)
            .maxContinuations(properties.getMaxContinuations())
            .deadline(now.plus(properties.getRunTimeout()))
            .build();
    if (!issueRunRepository.create(run)) {
      throw new IllegalStateException("Failed to create IssueRun");
    }
    if (issueStatus != null) {
      issue.setStatus(issueStatus);
      if (!issueRepository.updateById(issue, issue.getVersion())) {
        throw new IllegalStateException("Failed to update Issue status");
      }
    }
    harnessController.bootstrapIfAvailable(issue, run);
    defer(claim, now, properties.getActiveDelay());
  }

  private IssueReconcileOutcome failAndWake(
      IssueRun run,
      IssueRunStatus status,
      String reason,
      ClaimedControllerWork claim,
      Instant now,
      IssueReconcileOutcome outcome) {
    transitionRun(run, status, reason, now);
    harnessController.stopAfterCommit(run);
    rescheduleNow(claim, now);
    return outcome;
  }

  private void transitionRun(
      IssueRun run, IssueRunStatus status, String waitingReason, Instant completedAt) {
    run.setStatus(status);
    run.setWaitingReason(waitingReason);
    run.setCompletedAt(completedAt);
    updateRun(run, "Failed to transition IssueRun");
  }

  private void updateRun(IssueRun run, String message) {
    if (!issueRunRepository.updateById(run, run.getVersion())) {
      throw new IllegalStateException(message);
    }
  }

  private boolean hasRetryInput(Issue issue, IssueRun run) {
    return issueInputRepository.findFirstByKindAfterSequence(
            issue.getId(), IssueInputKind.RETRY, run.getObservedInputSequence())
        != null;
  }

  private void complete(ClaimedControllerWork claim, Instant now) {
    workStore.completeWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now);
  }

  private void defer(ClaimedControllerWork claim, Instant now, Duration delay) {
    workStore.rescheduleWork(
        claim.getIssueId(),
        claim.getLeaseToken(),
        claim.getClaimedWakeVersion(),
        now,
        now.plus(delay));
  }

  private void rescheduleNow(ClaimedControllerWork claim, Instant now) {
    workStore.rescheduleWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now, now);
  }

  private void deferUntil(ClaimedControllerWork claim, Instant now, Instant dueAt) {
    workStore.rescheduleWork(
        claim.getIssueId(), claim.getLeaseToken(), claim.getClaimedWakeVersion(), now, dueAt);
  }

  private void requireNoActiveRun(IssueRun activeRun) {
    if (activeRun != null) {
      throw new IllegalStateException("Issue status conflicts with an active IssueRun");
    }
  }

  private void requireCoherentActiveRun(Issue issue, IssueRun run) {
    if (run.getActorType() != IssueRunActorType.AGENT) {
      throw new IllegalStateException("An active IssueRun must be owned by an Agent");
    }
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

  private void validateClaim(ClaimedControllerWork claim) {
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
