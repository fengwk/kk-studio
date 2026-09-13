package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
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
import java.time.ZoneOffset;
import java.util.UUID;

class IssueReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueRunRepository issueRunRepository;
  private IssueInputRepository issueInputRepository;
  private IssueService issueService;
  private IssueControllerWorkStore workStore;
  private IssueHarnessController harnessController;
  private IssueControllerProperties properties;
  private IssueReconciler reconciler;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueRunRepository = mock(IssueRunRepository.class);
    issueInputRepository = mock(IssueInputRepository.class);
    issueService = mock(IssueService.class);
    workStore = mock(IssueControllerWorkStore.class);
    harnessController = mock(IssueHarnessController.class);
    properties = new IssueControllerProperties();
    properties.setActiveDelay(Duration.ofSeconds(2));
    properties.setBlockedDelay(Duration.ofSeconds(20));
    reconciler =
        new IssueReconciler(
            projectRepository,
            issueRepository,
            issueRunRepository,
            issueInputRepository,
            issueService,
            workStore,
            harnessController,
            properties,
            CLOCK);
  }

  @Test
  void reconcileLocksInCanonicalOrderAndRejectsStaleLeaseWithoutSideEffects() {
    // 测试意图：验证锁序固定，且 fencing 异常传播以触发事务回滚。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    doThrow(new AiValidationException("controller_work", "Lease expired"))
        .when(workStore)
        .renewLease(any(), any(), any(), any());

    assertThrows(AiValidationException.class, () -> reconciler.reconcile(fixture.claim()));

    InOrder order = inOrder(projectRepository, issueRepository, issueRunRepository, workStore);
    order.verify(projectRepository).lockForShare(fixture.project().getId());
    order.verify(issueRepository).lockById(fixture.issue().getId());
    order.verify(issueRunRepository).lockActiveByIssueId(fixture.issue().getId());
    order.verify(workStore).renewLease(any(), any(), any(), any());
    verify(issueRunRepository, never()).create(any());
    verify(harnessController, never()).bootstrapIfAvailable(any(), any());
  }

  @Test
  void reconcileMissingHierarchySkipsWithoutClaimMutation() {
    // 测试意图：Issue/Project 在 claim 后已删除或层级重校验失败时，不接触已不再可安全归属的 work。
    ClaimedControllerWork missingClaim =
        ClaimedControllerWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken("lease")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(missingClaim));

    Fixture missingProject = fixture(IssueStatus.TODO, null);
    when(projectRepository.lockForShare(missingProject.project().getId())).thenReturn(null);
    assertEquals(
        IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(missingProject.claim()));

    Fixture missingLockedIssue = fixture(IssueStatus.TODO, null);
    when(issueRepository.lockById(missingLockedIssue.issue().getId())).thenReturn(null);
    assertEquals(
        IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(missingLockedIssue.claim()));
  }

  @Test
  void reconcileSkipsTerminalAndArchivedIssuesButRejectsImpossibleActiveRun() {
    // 测试意图：验证收敛状态完成 work，同时拒绝状态与 active run 冲突的损坏数据。
    Fixture done = fixture(IssueStatus.DONE, null);
    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(done.claim()));
    verify(workStore).completeWork(done.issue().getId(), "lease", 7L, NOW);

    Fixture archived = fixture(IssueStatus.IN_PROGRESS, null);
    archived.issue().setArchivedAt(NOW.minusSeconds(1));
    IssueRun active = run(archived.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.RUNNING);
    when(issueRunRepository.lockActiveByIssueId(archived.issue().getId())).thenReturn(active);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(archived.claim()));
  }

  @Test
  void reconcileCanceledRunTransitionsAndStopsAfterCommit() {
    // 测试意图：验证取消 Issue 对活跃 run 做 CAS 终结并登记 Harness stop。
    Fixture fixture = fixture(IssueStatus.CANCELED, null);
    IssueRun run = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.RUNNING);
    when(issueRunRepository.lockActiveByIssueId(fixture.issue().getId())).thenReturn(run);
    when(issueRunRepository.updateById(run, 0L)).thenReturn(true);

    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(fixture.claim()));

    assertEquals(IssueRunStatus.CANCELLED, run.getStatus());
    assertEquals(NOW, run.getCompletedAt());
    verify(harnessController).stopAfterCommit(run);
  }

  @Test
  void reconcileCanceledRunIsIdempotentForLatestCancelledRun() {
    // 测试意图：崩溃恢复后，已转为 CANCELLED 的最新 Run 仍会 best-effort stop，但不重复 CAS。
    Fixture fixture = fixture(IssueStatus.CANCELED, null);
    IssueRun cancelled = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.CANCELLED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(cancelled);
    when(issueRunRepository.lockById(cancelled.getId())).thenReturn(cancelled);

    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(fixture.claim()));

    verify(issueRunRepository, never()).updateById(any(), anyLong());
    verify(harnessController).stopAfterCommit(cancelled);
  }

  @Test
  void reconcileTodoDefersArchivedAndBlockedProjects() {
    // 测试意图：验证 TODO 在归档或依赖阻塞时不创建 run。
    Fixture archived = fixture(IssueStatus.TODO, null);
    archived.project().setArchivedAt(NOW);
    assertEquals(IssueReconcileOutcome.DEFERRED_ARCHIVED, reconciler.reconcile(archived.claim()));

    Fixture blocked = fixture(IssueStatus.TODO, null);
    when(issueService.isBlocked(blocked.issue().getId())).thenReturn(true);
    assertEquals(IssueReconcileOutcome.DEFERRED_BLOCKED, reconciler.reconcile(blocked.claim()));

    verify(issueRunRepository, never()).create(any());
  }

  @Test
  void reconcileTodoWithoutAssigneeConverges() {
    // 测试意图：验证无 assignee 的 TODO 不生成错误 run。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    fixture.issue().setAssigneeAgentName(null);

    assertEquals(IssueReconcileOutcome.NO_ASSIGNEE, reconciler.reconcile(fixture.claim()));

    verify(issueRunRepository, never()).create(any());
  }

  @Test
  void reconcileTodoAtomicallyStartsExecutor() {
    // 测试意图：验证 executor、Issue 状态、Session 和后续 work 在同一动作中创建。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    when(issueRunRepository.allocateNextOrdinal(fixture.issue().getId())).thenReturn(3L);
    when(issueRunRepository.create(any())).thenReturn(true);
    when(issueRepository.updateById(fixture.issue(), 0L)).thenReturn(true);

    assertEquals(IssueReconcileOutcome.EXECUTOR_STARTED, reconciler.reconcile(fixture.claim()));

    ArgumentCaptor<IssueRun> runCaptor = ArgumentCaptor.forClass(IssueRun.class);
    verify(issueRunRepository).create(runCaptor.capture());
    IssueRun run = runCaptor.getValue();
    assertEquals(IssueRunRole.EXECUTOR, run.getRole());
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());
    assertEquals(3L, run.getOrdinal());
    assertEquals(IssueStatus.IN_PROGRESS, fixture.issue().getStatus());
    verify(harnessController).bootstrapIfAvailable(fixture.issue(), run);
  }

  @Test
  void reconcileActiveBootstrapsMissingSession() {
    // 测试意图：验证已存在 active run 缺 Session 时仅补建 Session。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    when(harnessController.inspect(fixture.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.MISSING_SESSION, null));

    assertEquals(IssueReconcileOutcome.SESSION_BOOTSTRAPPED, reconciler.reconcile(fixture.claim()));

    verify(harnessController).bootstrap(fixture.issue(), fixture.activeRun());
  }

  @Test
  void reconcileActiveMapsDeadlineAndHarnessUnknownThenImmediatelyRequeues() {
    // 测试意图：验证不可恢复终止原因映射和“迁移后下一 claim attention”单动作边界。
    Fixture deadline = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    deadline.activeRun().setDeadline(NOW);
    when(issueRunRepository.updateById(deadline.activeRun(), 0L)).thenReturn(true);
    assertEquals(
        IssueReconcileOutcome.RUN_DEADLINE_EXCEEDED, reconciler.reconcile(deadline.claim()));
    verify(workStore).rescheduleWork(deadline.issue().getId(), "lease", 7L, NOW, NOW);

    Fixture unknown = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    when(harnessController.inspect(unknown.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.UNKNOWN, null));
    when(issueRunRepository.updateById(unknown.activeRun(), 0L)).thenReturn(true);
    assertEquals(IssueReconcileOutcome.RUN_UNKNOWN_HARNESS, reconciler.reconcile(unknown.claim()));
    verify(harnessController).stopAfterCommit(unknown.activeRun());
  }

  @Test
  void reconcileActiveProcessingDefers() {
    // 测试意图：验证 Harness 正在处理时不发送 continuation。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    when(harnessController.inspect(fixture.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.PROCESSING, mockThreadSnapshot()));

    assertEquals(IssueReconcileOutcome.RUN_PROCESSING, reconciler.reconcile(fixture.claim()));

    verify(harnessController, never()).sendSystemContinuation(any(), any(), any());
  }

  @Test
  void reconcileConsumesExactlyOneInputAndResumesWaitingHuman() {
    // 测试意图：验证每次只消费首个未观察输入，并以该 sequence 推进游标。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.issue().setInputSequence(9L);
    fixture.activeRun().setStatus(IssueRunStatus.WAITING_HUMAN);
    fixture.activeRun().setObservedInputSequence(3L);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, mockThreadSnapshot());
    IssueInput input = input(fixture.issue(), 4L, IssueInputKind.HUMAN);
    when(harnessController.inspect(fixture.activeRun())).thenReturn(inspection);
    when(issueInputRepository.findFirstAfterSequence(fixture.issue().getId(), 3L))
        .thenReturn(input);
    when(issueRunRepository.updateById(fixture.activeRun(), 0L)).thenReturn(true);

    assertEquals(
        IssueReconcileOutcome.USER_CONTINUATION_SENT, reconciler.reconcile(fixture.claim()));

    verify(harnessController)
        .sendUserContinuation(fixture.issue(), fixture.activeRun(), input, inspection);
    assertEquals(4L, fixture.activeRun().getObservedInputSequence());
    assertEquals(IssueRunStatus.RUNNING, fixture.activeRun().getStatus());
    verify(workStore).rescheduleWork(fixture.issue().getId(), "lease", 7L, NOW, NOW);
  }

  @Test
  void reconcileFinalInputDefersAndWaitingWithoutDeadlineConverges() {
    // 测试意图：消费最后一个 input 后走 active delay；兼容无 deadline 的 WAITING_HUMAN Run 并完成 work。
    Fixture finalInput = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    finalInput.issue().setInputSequence(1L);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, mockThreadSnapshot());
    when(harnessController.inspect(finalInput.activeRun())).thenReturn(inspection);
    when(issueInputRepository.findFirstAfterSequence(finalInput.issue().getId(), 0L))
        .thenReturn(input(finalInput.issue(), 1L, IssueInputKind.HUMAN));
    when(issueRunRepository.updateById(finalInput.activeRun(), 0L)).thenReturn(true);

    assertEquals(
        IssueReconcileOutcome.USER_CONTINUATION_SENT, reconciler.reconcile(finalInput.claim()));
    verify(workStore)
        .rescheduleWork(
            finalInput.issue().getId(), "lease", 7L, NOW, NOW.plus(properties.getActiveDelay()));

    Fixture waiting = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    waiting.activeRun().setStatus(IssueRunStatus.WAITING_HUMAN);
    waiting.activeRun().setDeadline(null);
    when(harnessController.inspect(waiting.activeRun())).thenReturn(inspection);
    assertEquals(IssueReconcileOutcome.WAITING_FOR_HUMAN, reconciler.reconcile(waiting.claim()));
    verify(workStore).completeWork(waiting.issue().getId(), "lease", 7L, NOW);
  }

  @Test
  void reconcileWaitingHumanDeliversAttentionAndConverges() {
    // 测试意图：验证静止 WAITING_HUMAN 不被自动 steering，并通知已有 Coordinator。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.activeRun().setStatus(IssueRunStatus.WAITING_HUMAN);
    when(harnessController.inspect(fixture.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.QUIESCENT, mockThreadSnapshot()));

    assertEquals(IssueReconcileOutcome.WAITING_FOR_HUMAN, reconciler.reconcile(fixture.claim()));

    verify(harnessController)
        .deliverAttention(fixture.project(), fixture.issue(), fixture.activeRun());
    verify(harnessController, never()).sendSystemContinuation(any(), any(), any());
    verify(workStore)
        .rescheduleWork(
            fixture.issue().getId(), "lease", 7L, NOW, fixture.activeRun().getDeadline());
  }

  @Test
  void reconcileQuiescentSendsSteeringOrFailsAtBudget() {
    // 测试意图：验证 continuation 预算的两个边界分支。
    Fixture available = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, mockThreadSnapshot());
    when(harnessController.inspect(available.activeRun())).thenReturn(inspection);
    when(issueRunRepository.updateById(available.activeRun(), 0L)).thenReturn(true);
    assertEquals(
        IssueReconcileOutcome.SYSTEM_CONTINUATION_SENT, reconciler.reconcile(available.claim()));
    assertEquals(1, available.activeRun().getContinuationCount());

    Fixture exhausted = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    exhausted.activeRun().setContinuationCount(exhausted.activeRun().getMaxContinuations());
    when(harnessController.inspect(exhausted.activeRun())).thenReturn(inspection);
    when(issueRunRepository.updateById(exhausted.activeRun(), 0L)).thenReturn(true);
    assertEquals(IssueReconcileOutcome.BUDGET_EXHAUSTED, reconciler.reconcile(exhausted.claim()));
    assertEquals(IssueRunStatus.FAILED, exhausted.activeRun().getStatus());
  }

  @Test
  void reconcileReviewStartsReviewerAndSupportsExplicitRetry() {
    // 测试意图：验证 SUBMITTED executor 启动 reviewer，失败 reviewer 仅由新 RETRY 输入重启。
    Fixture start = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun submitted = run(start.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.COMPLETED);
    submitted.setOutcome(IssueRunOutcome.SUBMITTED);
    when(issueRunRepository.findLatestByIssueId(start.issue().getId())).thenReturn(submitted);
    when(issueRunRepository.lockById(submitted.getId())).thenReturn(submitted);
    when(issueRunRepository.allocateNextOrdinal(start.issue().getId())).thenReturn(2L);
    when(issueRunRepository.create(any())).thenReturn(true);
    assertEquals(IssueReconcileOutcome.REVIEWER_STARTED, reconciler.reconcile(start.claim()));

    Fixture retry = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun failed = run(retry.issue(), IssueRunRole.REVIEWER, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(retry.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueInputRepository.findFirstByKindAfterSequence(
            retry.issue().getId(), IssueInputKind.RETRY, 0L))
        .thenReturn(input(retry.issue(), 1L, IssueInputKind.RETRY));
    when(issueRunRepository.allocateNextOrdinal(retry.issue().getId())).thenReturn(2L);
    when(issueRunRepository.create(any())).thenReturn(true);
    assertEquals(IssueReconcileOutcome.REVIEWER_RETRY_STARTED, reconciler.reconcile(retry.claim()));
  }

  @Test
  void reconcileReviewCoversHumanArchivedAndNonSubmittedConvergence() {
    // 测试意图：覆盖人工 Review、失败无 retry、归档重试、归档新 Review 与非 SUBMITTED 历史的确定性收敛。
    Fixture human = fixture(IssueStatus.IN_REVIEW, null);
    human.issue().setReviewerAgentName(null);
    assertEquals(IssueReconcileOutcome.WAITING_HUMAN_REVIEW, reconciler.reconcile(human.claim()));

    Fixture failed = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun failedReviewer = run(failed.issue(), IssueRunRole.REVIEWER, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(failed.issue().getId())).thenReturn(failedReviewer);
    when(issueRunRepository.lockById(failedReviewer.getId())).thenReturn(failedReviewer);
    assertEquals(IssueReconcileOutcome.CONVERGED_FAILED, reconciler.reconcile(failed.claim()));

    Fixture archivedRetry = fixture(IssueStatus.IN_REVIEW, null);
    archivedRetry.project().setArchivedAt(NOW);
    IssueRun retryReviewer =
        run(archivedRetry.issue(), IssueRunRole.REVIEWER, IssueRunStatus.UNKNOWN);
    when(issueRunRepository.findLatestByIssueId(archivedRetry.issue().getId()))
        .thenReturn(retryReviewer);
    when(issueRunRepository.lockById(retryReviewer.getId())).thenReturn(retryReviewer);
    when(issueInputRepository.findFirstByKindAfterSequence(
            archivedRetry.issue().getId(), IssueInputKind.RETRY, 0L))
        .thenReturn(input(archivedRetry.issue(), 1L, IssueInputKind.RETRY));
    assertEquals(
        IssueReconcileOutcome.DEFERRED_ARCHIVED, reconciler.reconcile(archivedRetry.claim()));

    Fixture archivedReview = fixture(IssueStatus.IN_REVIEW, null);
    archivedReview.project().setArchivedAt(NOW);
    IssueRun submission =
        run(archivedReview.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.COMPLETED);
    submission.setOutcome(IssueRunOutcome.SUBMITTED);
    when(issueRunRepository.findLatestByIssueId(archivedReview.issue().getId()))
        .thenReturn(submission);
    when(issueRunRepository.lockById(submission.getId())).thenReturn(submission);
    assertEquals(
        IssueReconcileOutcome.DEFERRED_ARCHIVED, reconciler.reconcile(archivedReview.claim()));

    Fixture nonSubmitted = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun irrelevant =
        run(nonSubmitted.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.COMPLETED);
    when(issueRunRepository.findLatestByIssueId(nonSubmitted.issue().getId()))
        .thenReturn(irrelevant);
    when(issueRunRepository.lockById(irrelevant.getId())).thenReturn(irrelevant);
    assertEquals(
        IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(nonSubmitted.claim()));
  }

  @Test
  void reconcileFailedRunWithoutRetryDeliversAttentionWithoutCreatingSession() {
    // 测试意图：验证失败收敛只走既有 Coordinator 边界，不创建 Project Session。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);

    assertEquals(IssueReconcileOutcome.CONVERGED_FAILED, reconciler.reconcile(fixture.claim()));

    verify(harnessController).deliverAttention(fixture.project(), fixture.issue(), failed);
    verify(harnessController, never()).bootstrapIfAvailable(any(), any());
  }

  @Test
  void reconcileFailedExecutorRequiresRetryAndUnblockedProject() {
    // 测试意图：验证 executor retry 仍遵守依赖与归档门禁。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.UNKNOWN);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueInputRepository.findFirstByKindAfterSequence(
            fixture.issue().getId(), IssueInputKind.RETRY, 0L))
        .thenReturn(input(fixture.issue(), 1L, IssueInputKind.RETRY));
    when(issueService.isBlocked(fixture.issue().getId())).thenReturn(true);

    assertEquals(IssueReconcileOutcome.DEFERRED_BLOCKED, reconciler.reconcile(fixture.claim()));

    verify(issueRunRepository, never()).create(any());
  }

  @Test
  void reconcileFailedExecutorCoversArchivedMissingAssigneeAndRetryStart() {
    // 测试意图：显式 RETRY 仍受归档/assignee 门禁，门禁通过后创建同角色新 ordinal。
    Fixture archived = failedExecutorWithRetry();
    archived.project().setArchivedAt(NOW);
    assertEquals(IssueReconcileOutcome.DEFERRED_ARCHIVED, reconciler.reconcile(archived.claim()));

    Fixture missingAssignee = failedExecutorWithRetry();
    missingAssignee.issue().setAssigneeAgentName(null);
    assertEquals(IssueReconcileOutcome.NO_ASSIGNEE, reconciler.reconcile(missingAssignee.claim()));

    Fixture retry = failedExecutorWithRetry();
    when(issueRunRepository.allocateNextOrdinal(retry.issue().getId())).thenReturn(2L);
    when(issueRunRepository.create(any())).thenReturn(true);
    assertEquals(IssueReconcileOutcome.RETRY_RUN_STARTED, reconciler.reconcile(retry.claim()));
    verify(harnessController).bootstrapIfAvailable(eq(retry.issue()), any());
  }

  @Test
  void reconcileRejectsCorruptRunShapesAndMutationFailures() {
    // 测试意图：损坏 actor/role 与所有关键 CAS 失败都必须立即抛出，禁止继续 bootstrap 或完成 work。
    Fixture humanActor = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    humanActor.activeRun().setActorType(IssueRunActorType.HUMAN);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(humanActor.claim()));

    Fixture wrongActiveRole = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.REVIEWER);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(wrongActiveRole.claim()));

    Fixture wrongLatestRole = failedExecutorWithRetry();
    IssueRun reviewer = run(wrongLatestRole.issue(), IssueRunRole.REVIEWER, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(wrongLatestRole.issue().getId()))
        .thenReturn(reviewer);
    when(issueRunRepository.lockById(reviewer.getId())).thenReturn(reviewer);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(wrongLatestRole.claim()));

    Fixture createFailure = fixture(IssueStatus.TODO, null);
    when(issueRunRepository.allocateNextOrdinal(createFailure.issue().getId())).thenReturn(1L);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(createFailure.claim()));

    Fixture issueCasFailure = fixture(IssueStatus.TODO, null);
    when(issueRunRepository.allocateNextOrdinal(issueCasFailure.issue().getId())).thenReturn(1L);
    when(issueRunRepository.create(any())).thenReturn(true);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(issueCasFailure.claim()));

    Fixture runCasFailure = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, mockThreadSnapshot());
    when(harnessController.inspect(runCasFailure.activeRun())).thenReturn(inspection);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(runCasFailure.claim()));
  }

  @Test
  void reconcileValidatesClaimShape() {
    // 测试意图：controller 的公开事务边界拒绝 null、空 token、非正 wake 和缺失 lease。
    assertThrows(NullPointerException.class, () -> reconciler.reconcile(null));
    ClaimedControllerWork missingLease =
        ClaimedControllerWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken(" ")
            .build();
    assertThrows(NullPointerException.class, () -> reconciler.reconcile(missingLease));
    ClaimedControllerWork blankToken =
        ClaimedControllerWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken(" ")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(blankToken));
    ClaimedControllerWork invalidWake =
        ClaimedControllerWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(0L)
            .leaseToken("lease")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(invalidWake));
  }

  private Fixture fixture(IssueStatus status, IssueRunRole activeRole) {
    Project project =
        Project.builder()
            .id(UUID.randomUUID())
            .title("Project")
            .coordinatorAgentName("coordinator")
            .version(0L)
            .build();
    Issue issue =
        Issue.builder()
            .id(UUID.randomUUID())
            .projectId(project.getId())
            .number(1L)
            .title("Issue")
            .status(status)
            .assigneeAgentName("executor")
            .reviewerAgentName("reviewer")
            .version(0L)
            .specRevision(2L)
            .inputSequence(0L)
            .build();
    ClaimedControllerWork claim =
        ClaimedControllerWork.builder()
            .issueId(issue.getId())
            .claimedWakeVersion(7L)
            .leaseToken("lease")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    IssueRun activeRun = activeRole == null ? null : run(issue, activeRole, IssueRunStatus.RUNNING);
    when(issueRepository.getById(issue.getId())).thenReturn(issue);
    when(projectRepository.lockForShare(project.getId())).thenReturn(project);
    when(issueRepository.lockById(issue.getId())).thenReturn(issue);
    when(issueRunRepository.lockActiveByIssueId(issue.getId())).thenReturn(activeRun);
    return new Fixture(project, issue, activeRun, claim);
  }

  private Fixture failedExecutorWithRetry() {
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueInputRepository.findFirstByKindAfterSequence(
            fixture.issue().getId(), IssueInputKind.RETRY, 0L))
        .thenReturn(input(fixture.issue(), 1L, IssueInputKind.RETRY));
    return fixture;
  }

  private IssueRun run(Issue issue, IssueRunRole role, IssueRunStatus status) {
    return IssueRun.builder()
        .id(UUID.randomUUID())
        .issueId(issue.getId())
        .ordinal(1L)
        .role(role)
        .actorType(IssueRunActorType.AGENT)
        .agentName("agent")
        .status(status)
        .observedSpecRevision(0L)
        .observedInputSequence(0L)
        .continuationCount(0)
        .maxContinuations(3)
        .deadline(NOW.plusSeconds(600))
        .version(0L)
        .build();
  }

  private IssueInput input(Issue issue, long sequence, IssueInputKind kind) {
    return IssueInput.builder()
        .issueId(issue.getId())
        .sequence(sequence)
        .kind(kind)
        .body("input")
        .build();
  }

  private ThreadSnapshot mockThreadSnapshot() {
    return mock(ThreadSnapshot.class);
  }

  private record Fixture(
      Project project, Issue issue, IssueRun activeRun, ClaimedControllerWork claim) {}
}
