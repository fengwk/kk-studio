package fun.fengwk.kkstudio.platform.project.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.Inspection;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.InspectionStatus;
import fun.fengwk.kkstudio.platform.project.controller.IssueHarnessController.QualifiedSubmission;
import fun.fengwk.kkstudio.platform.project.model.ClaimedIssueWork;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class IssueReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueRunRepository issueRunRepository;
  private IssueActivityRepository issueActivityRepository;
  private IssueAgentSessionRepository issueAgentSessionRepository;
  private IssueDependencyRepository issueDependencyRepository;
  private IssueService issueService;
  private IssueRunService issueRunService;
  private IssueWorkStore workStore;
  private IssueHarnessController harnessController;
  private IssueControllerProperties properties;
  private IssueReconciler reconciler;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    issueRunRepository = mock(IssueRunRepository.class);
    issueActivityRepository = mock(IssueActivityRepository.class);
    issueAgentSessionRepository = mock(IssueAgentSessionRepository.class);
    issueDependencyRepository = mock(IssueDependencyRepository.class);
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    workStore = mock(IssueWorkStore.class);
    harnessController = mock(IssueHarnessController.class);
    properties = new IssueControllerProperties();
    properties.setActiveDelay(Duration.ofSeconds(2));
    properties.setBlockedDelay(Duration.ofSeconds(20));
    reconciler =
        new IssueReconciler(
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
            CLOCK);
  }

  @Test
  void reconcileLocksInCanonicalOrderAndRejectsStaleLeaseWithoutSideEffects() {
    // 测试意图：验证锁序固定（project FOR UPDATE -> issue FOR UPDATE -> active run -> work），
    // 且 fencing 异常传播以触发事务回滚；锁模式必须与业务层一致，否则会退化出锁升级死锁。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    doThrow(new AiValidationException("work", "Lease expired"))
        .when(workStore)
        .renewLease(any(), any(), any(), any());

    assertThrows(AiValidationException.class, () -> reconciler.reconcile(fixture.claim()));

    InOrder order = inOrder(projectRepository, issueRepository, issueRunRepository, workStore);
    order.verify(projectRepository).lockById(fixture.project().getId());
    order.verify(issueRepository).lockById(fixture.issue().getId());
    order.verify(issueRunRepository).lockActiveByIssueId(fixture.issue().getId());
    order.verify(workStore).renewLease(any(), any(), any(), any());
    verify(projectRepository, never()).lockForShare(any());
    verify(issueRunService, never()).startExecutorRun(any(), any(), any(), anyInt());
    verify(harnessController, never()).bootstrapIfAvailable(any(), any(), any());
  }

  @Test
  void reconcileMissingHierarchySkipsWithoutClaimMutation() {
    // 测试意图：Issue/Project 在 claim 后已删除或层级重校验失败时，不接触已不再可安全归属的 work。
    ClaimedIssueWork missingClaim =
        ClaimedIssueWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken("lease")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(missingClaim));

    Fixture missingProject = fixture(IssueStatus.TODO, null);
    when(projectRepository.lockById(missingProject.project().getId())).thenReturn(null);
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
  void reconcileBlockedIssueCompletesWorkWaitingHumanRecovery() {
    // 测试意图：验证 BLOCKED 状态直接完成 work 且不设置自唤醒调度。
    Fixture blocked = fixture(IssueStatus.BLOCKED, null);
    assertEquals(
        IssueReconcileOutcome.WAITING_HUMAN_RECOVERY, reconciler.reconcile(blocked.claim()));
    verify(workStore).completeWork(blocked.issue().getId(), "lease", 7L, NOW);
  }

  @Test
  void reconcileCanceledRunTransitionsAndStopsAfterCommit() {
    // 测试意图：验证取消 Issue 对活跃 run 终结并登记 Harness stop。
    Fixture fixture = fixture(IssueStatus.CANCELED, null);
    IssueRun run = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.RUNNING);
    when(issueRunRepository.lockActiveByIssueId(fixture.issue().getId())).thenReturn(run);

    assertEquals(IssueReconcileOutcome.SKIPPED_CONVERGED, reconciler.reconcile(fixture.claim()));

    verify(issueRunService).failRun(run.getId(), IssueRunStatus.CANCELLED, "Issue was canceled");
    verify(harnessController).stopAfterCommit(fixture.issue().getId(), run.getAgentName());
    verify(workStore).completeWork(fixture.issue().getId(), "lease", 7L, NOW);
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

    verify(issueRunService, never()).startExecutorRun(any(), any(), any(), anyInt());
  }

  @Test
  void reconcileTodoWithoutAssigneeConverges() {
    // 测试意图：验证无 assignee 的 TODO 不生成错误 run。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    fixture.issue().setAssigneeAgentName(null);

    assertEquals(IssueReconcileOutcome.NO_ASSIGNEE, reconciler.reconcile(fixture.claim()));

    verify(issueRunService, never()).startExecutorRun(any(), any(), any(), anyInt());
  }

  @Test
  void reconcileTodoAtomicallyStartsExecutor() {
    // 测试意图：验证 executor 启动委托给 IssueRunService。
    Fixture fixture = fixture(IssueStatus.TODO, null);
    IssueRun run = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.RUNNING);
    when(issueRunService.startExecutorRun(
            eq(fixture.issue().getId()),
            eq(fixture.issue().getAssigneeAgentName()),
            any(),
            eq(properties.getMaxContinuations())))
        .thenReturn(run);

    assertEquals(IssueReconcileOutcome.EXECUTOR_STARTED, reconciler.reconcile(fixture.claim()));

    verify(harnessController).bootstrapIfAvailable(fixture.project(), fixture.issue(), run);
  }

  @Test
  void reconcileActiveBootstrapsMissingSession() {
    // 测试意图：验证已存在 active run 缺 Session 时仅补建 Session。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.MISSING_SESSION, null, null));

    assertEquals(IssueReconcileOutcome.SESSION_BOOTSTRAPPED, reconciler.reconcile(fixture.claim()));

    verify(harnessController).bootstrap(fixture.project(), fixture.issue(), fixture.activeRun());
  }

  @Test
  void reconcileActiveMapsDeadlineAndHarnessUnknownThenImmediatelyRequeues() {
    // 测试意图：验证不可恢复终止原因映射并立即重新排队。
    Fixture deadline = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    deadline.activeRun().setDeadline(NOW);
    assertEquals(
        IssueReconcileOutcome.RUN_DEADLINE_EXCEEDED, reconciler.reconcile(deadline.claim()));
    verify(issueRunService)
        .failRun(deadline.activeRun().getId(), IssueRunStatus.FAILED, "Run deadline exceeded");
    verify(workStore).rescheduleWork(deadline.issue().getId(), "lease", 7L, NOW, NOW);

    Fixture unknown = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    when(harnessController.inspect(unknown.issue(), unknown.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.UNKNOWN, null, null));
    assertEquals(IssueReconcileOutcome.RUN_UNKNOWN_HARNESS, reconciler.reconcile(unknown.claim()));
    verify(issueRunService)
        .failRun(unknown.activeRun().getId(), IssueRunStatus.UNKNOWN, "Harness state is unknown");
  }

  @Test
  void reconcileActiveProcessingDefers() {
    // 测试意图：验证 Harness 正在处理时不发送 continuation。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.PROCESSING, null, snapshot));

    assertEquals(IssueReconcileOutcome.RUN_PROCESSING, reconciler.reconcile(fixture.claim()));

    verify(harnessController, never()).sendSystemContinuation(any(), any(), any(), any());
  }

  @Test
  void reconcileActiveAlignsYoloWhenThreadPolicyDiffers() {
    // 测试意图：验证在投递前对齐 Thread YOLO 设置以匹配 Project。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.project().setYoloEnabled(true);
    ThreadSnapshot snapshot = mockThreadSnapshot(false);
    Inspection inspection =
        new Inspection(InspectionStatus.QUIESCENT, mockAgentSession(fixture), snapshot);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun())).thenReturn(inspection);

    assertEquals(IssueReconcileOutcome.YOLO_ALIGNED, reconciler.reconcile(fixture.claim()));
    verify(harnessController)
        .alignThreadYolo(snapshot.thread().id(), snapshot.thread().version(), true);
  }

  @Test
  void reconcileActiveExecutorSubmitsOnQualifiedTurn() {
    // 测试意图：验证在静止且无待投递 activity 时，合规的 final turn 会触发 completeExecutorRun。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.project().setYoloEnabled(true);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    Inspection inspection =
        new Inspection(InspectionStatus.QUIESCENT, mockAgentSession(fixture), snapshot);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun())).thenReturn(inspection);
    when(issueActivityRepository.listPage(fixture.issue().getId(), 0L, 200)).thenReturn(List.of());

    UUID turnEndId = UUID.randomUUID();
    when(harnessController.findQualifiedSubmission(fixture.activeRun(), snapshot, false))
        .thenReturn(new QualifiedSubmission(turnEndId, "Done text"));

    assertEquals(IssueReconcileOutcome.EXECUTOR_SUBMITTED, reconciler.reconcile(fixture.claim()));
    verify(issueRunService)
        .completeExecutorRun(
            fixture.activeRun().getId(),
            "submit:" + fixture.activeRun().getId() + ":" + turnEndId,
            "Done text",
            null);
  }

  @Test
  void reconcileConsumesActivityAndResumesWaitingHuman() {
    // 测试意图：验证消费未观察的 activity，推进游标，并恢复 WAITING_HUMAN 状态。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.project().setYoloEnabled(true);
    fixture.activeRun().setStatus(IssueRunStatus.WAITING_HUMAN);
    fixture.activeRun().setObservedActivitySequence(3L);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    IssueAgentSession session = mockAgentSession(fixture);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, session, snapshot);
    IssueActivity activity =
        IssueActivity.builder()
            .issueId(fixture.issue().getId())
            .sequence(4L)
            .kind(IssueActivityKind.HUMAN_INPUT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Input body")
            .build();
    when(harnessController.inspect(fixture.issue(), fixture.activeRun())).thenReturn(inspection);
    when(issueActivityRepository.listPage(fixture.issue().getId(), 3L, 200))
        .thenReturn(List.of(activity));
    when(issueRunRepository.updateById(fixture.activeRun(), 0L)).thenReturn(true);

    assertEquals(IssueReconcileOutcome.ACTIVITY_DELIVERED, reconciler.reconcile(fixture.claim()));

    verify(harnessController)
        .deliverActivity(fixture.issue(), fixture.activeRun(), activity, session, snapshot);
    assertEquals(4L, fixture.activeRun().getObservedActivitySequence());
    assertEquals(IssueRunStatus.RUNNING, fixture.activeRun().getStatus());
  }

  @Test
  void reconcileWaitingHumanWithoutDeadlineConverges() {
    // 测试意图：无 deadline 的 WAITING_HUMAN 完成 work。
    Fixture waiting = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    waiting.project().setYoloEnabled(true);
    waiting.activeRun().setStatus(IssueRunStatus.WAITING_HUMAN);
    waiting.activeRun().setDeadline(null);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    Inspection inspection =
        new Inspection(InspectionStatus.QUIESCENT, mockAgentSession(waiting), snapshot);
    when(harnessController.inspect(waiting.issue(), waiting.activeRun())).thenReturn(inspection);
    when(issueActivityRepository.listPage(waiting.issue().getId(), 0L, 200)).thenReturn(List.of());

    assertEquals(IssueReconcileOutcome.WAITING_FOR_HUMAN, reconciler.reconcile(waiting.claim()));
    verify(workStore).completeWork(waiting.issue().getId(), "lease", 7L, NOW);
  }

  @Test
  void reconcileQuiescentSendsSteeringOrFailsAtBudget() {
    // 测试意图：验证 continuation 预算的两个边界分支。
    Fixture available = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    available.project().setYoloEnabled(true);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    IssueAgentSession session = mockAgentSession(available);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, session, snapshot);
    when(harnessController.inspect(available.issue(), available.activeRun()))
        .thenReturn(inspection);
    when(issueActivityRepository.listPage(available.issue().getId(), 0L, 200))
        .thenReturn(List.of());
    when(issueRunRepository.updateById(available.activeRun(), 0L)).thenReturn(true);

    assertEquals(
        IssueReconcileOutcome.SYSTEM_CONTINUATION_SENT, reconciler.reconcile(available.claim()));
    assertEquals(1, available.activeRun().getContinuationCount());

    Fixture exhausted = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    exhausted.project().setYoloEnabled(true);
    exhausted.activeRun().setContinuationCount(exhausted.activeRun().getMaxContinuations());
    when(harnessController.inspect(exhausted.issue(), exhausted.activeRun()))
        .thenReturn(new Inspection(InspectionStatus.QUIESCENT, session, snapshot));
    when(issueActivityRepository.listPage(exhausted.issue().getId(), 0L, 200))
        .thenReturn(List.of());

    assertEquals(IssueReconcileOutcome.BUDGET_EXHAUSTED, reconciler.reconcile(exhausted.claim()));
    verify(issueRunService)
        .failRun(
            exhausted.activeRun().getId(), IssueRunStatus.FAILED, "Continuation budget exhausted");
  }

  @Test
  void reconcileReviewStartsReviewerAndSupportsExplicitRetry() {
    // 测试意图：验证 SUBMITTED executor 启动 reviewer，失败 reviewer 仅由新 RETRY 输入重启。
    Fixture start = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun submitted = run(start.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.COMPLETED);
    submitted.setOutcome(IssueRunOutcome.SUBMITTED);
    when(issueRunRepository.findLatestByIssueId(start.issue().getId())).thenReturn(submitted);
    when(issueRunRepository.lockById(submitted.getId())).thenReturn(submitted);
    when(issueRunService.startReviewerRun(any(), any(), any(), anyInt()))
        .thenReturn(run(start.issue(), IssueRunRole.REVIEWER, IssueRunStatus.RUNNING));

    assertEquals(IssueReconcileOutcome.REVIEWER_STARTED, reconciler.reconcile(start.claim()));

    Fixture retry = fixture(IssueStatus.IN_REVIEW, null);
    IssueRun failed = run(retry.issue(), IssueRunRole.REVIEWER, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(retry.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueActivityRepository.existsAfterSequenceAndKind(
            retry.issue().getId(), 0L, IssueActivityKind.RETRY))
        .thenReturn(true);
    when(issueRunService.startReviewerRun(any(), any(), any(), anyInt()))
        .thenReturn(run(retry.issue(), IssueRunRole.REVIEWER, IssueRunStatus.RUNNING));

    assertEquals(IssueReconcileOutcome.REVIEWER_RETRY_STARTED, reconciler.reconcile(retry.claim()));
  }

  @Test
  void reconcileReviewCoversHumanAndRefusesSameAssigneeReviewer() {
    // 测试意图：验证人工 Review 与 Assignee==Reviewer 时的自动审查拒绝。
    Fixture human = fixture(IssueStatus.IN_REVIEW, null);
    human.issue().setReviewerAgentName(null);
    assertEquals(IssueReconcileOutcome.WAITING_HUMAN_REVIEW, reconciler.reconcile(human.claim()));

    Fixture sameAgent = fixture(IssueStatus.IN_REVIEW, null);
    sameAgent.issue().setReviewerAgentName("executor-agent");
    sameAgent.issue().setAssigneeAgentName("executor-agent");
    assertEquals(
        IssueReconcileOutcome.WAITING_HUMAN_REVIEW, reconciler.reconcile(sameAgent.claim()));
  }

  @Test
  void reconcileFailedExecutorRequiresRetryAndUnblockedProject() {
    // 测试意图：验证 executor retry 遵守依赖门禁。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueActivityRepository.existsAfterSequenceAndKind(
            fixture.issue().getId(), 0L, IssueActivityKind.RETRY))
        .thenReturn(true);
    when(issueService.isBlocked(fixture.issue().getId())).thenReturn(true);

    assertEquals(IssueReconcileOutcome.DEFERRED_BLOCKED, reconciler.reconcile(fixture.claim()));
    verify(issueRunService, never()).startExecutorRun(any(), any(), any(), anyInt());
  }

  @Test
  void reconcileFailedExecutorCoversRetryStart() {
    // 测试意图：验证失败 executor 在新 RETRY activity 驱动下重新启动 run。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueActivityRepository.existsAfterSequenceAndKind(
            fixture.issue().getId(), 0L, IssueActivityKind.RETRY))
        .thenReturn(true);
    when(issueRunService.startExecutorRun(any(), any(), any(), anyInt()))
        .thenReturn(run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.RUNNING));

    assertEquals(IssueReconcileOutcome.RETRY_RUN_STARTED, reconciler.reconcile(fixture.claim()));
  }

  @Test
  void reconcileRejectsCorruptRunShapes() {
    // 测试意图：损坏 actor/role 与所有关键 CAS 失败都必须立即抛出。
    Fixture wrongActiveRole = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.REVIEWER);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(wrongActiveRole.claim()));

    Fixture wrongLatestRole = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun reviewer = run(wrongLatestRole.issue(), IssueRunRole.REVIEWER, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(wrongLatestRole.issue().getId()))
        .thenReturn(reviewer);
    when(issueRunRepository.lockById(reviewer.getId())).thenReturn(reviewer);
    assertThrows(IllegalStateException.class, () -> reconciler.reconcile(wrongLatestRole.claim()));
  }

  @Test
  void reconcileActiveFullWindowAdvancesCursorAndReschedulesImmediately() {
    // 测试意图：有界扫描窗口读满（200条非定向活动）时，推进已检视游标并立即重新排队，绝不发送 continuation。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.project().setYoloEnabled(true);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    IssueAgentSession session = mockAgentSession(fixture);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, session, snapshot);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun())).thenReturn(inspection);

    List<IssueActivity> fullWindow = new ArrayList<>(200);
    for (long i = 1; i <= 200; i++) {
      fullWindow.add(
          IssueActivity.builder()
              .issueId(fixture.issue().getId())
              .sequence(i)
              .kind(IssueActivityKind.COMMENT)
              .actorType(IssueActivityActorType.HUMAN)
              .body("Comment " + i)
              .build());
    }
    when(issueActivityRepository.listPage(fixture.issue().getId(), 0L, 200)).thenReturn(fullWindow);
    when(issueRunRepository.updateById(fixture.activeRun(), 0L)).thenReturn(true);

    assertEquals(
        IssueReconcileOutcome.ACTIVITY_SCAN_RESCHEDULED, reconciler.reconcile(fixture.claim()));

    assertEquals(200L, fixture.activeRun().getObservedActivitySequence());
    verify(issueRunRepository).updateById(fixture.activeRun(), 0L);
    verify(workStore).rescheduleWork(fixture.issue().getId(), "lease", 7L, NOW, NOW);
    verify(harnessController, never()).sendSystemContinuation(any(), any(), any(), any());
    verify(issueRunService, never()).completeExecutorRun(any(), any(), any(), any());
  }

  @Test
  void reconcileActivePartialWindowAdvancesCursorAndPreservesQuiescentBehaviour() {
    // 测试意图：有界扫描窗口未读满（部分非定向活动）时，推进游标并保持原有的 continuation 派发行为。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, IssueRunRole.EXECUTOR);
    fixture.project().setYoloEnabled(true);
    ThreadSnapshot snapshot = mockThreadSnapshot(true);
    IssueAgentSession session = mockAgentSession(fixture);
    Inspection inspection = new Inspection(InspectionStatus.QUIESCENT, session, snapshot);
    when(harnessController.inspect(fixture.issue(), fixture.activeRun())).thenReturn(inspection);

    List<IssueActivity> partialWindow =
        List.of(
            IssueActivity.builder()
                .issueId(fixture.issue().getId())
                .sequence(1L)
                .kind(IssueActivityKind.COMMENT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Comment 1")
                .build(),
            IssueActivity.builder()
                .issueId(fixture.issue().getId())
                .sequence(2L)
                .kind(IssueActivityKind.COMMENT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Comment 2")
                .build());
    when(issueActivityRepository.listPage(fixture.issue().getId(), 0L, 200))
        .thenReturn(partialWindow);
    when(issueRunRepository.updateById(fixture.activeRun(), 0L)).thenReturn(true);

    assertEquals(
        IssueReconcileOutcome.SYSTEM_CONTINUATION_SENT, reconciler.reconcile(fixture.claim()));

    assertEquals(2L, fixture.activeRun().getObservedActivitySequence());
    assertEquals(1, fixture.activeRun().getContinuationCount());
    verify(harnessController)
        .sendSystemContinuation(fixture.issue(), fixture.activeRun(), session, snapshot);
    verify(workStore)
        .rescheduleWork(
            fixture.issue().getId(), "lease", 7L, NOW, NOW.plus(properties.getActiveDelay()));
  }

  @Test
  void reconcileFailedRunWithoutRetryActivityConverges() {
    // 测试意图：终态 FAILED run 无新 RETRY activity 时完成 work，不重新调度。
    Fixture fixture = fixture(IssueStatus.IN_PROGRESS, null);
    IssueRun failed = run(fixture.issue(), IssueRunRole.EXECUTOR, IssueRunStatus.FAILED);
    when(issueRunRepository.findLatestByIssueId(fixture.issue().getId())).thenReturn(failed);
    when(issueRunRepository.lockById(failed.getId())).thenReturn(failed);
    when(issueActivityRepository.existsAfterSequenceAndKind(
            fixture.issue().getId(), 0L, IssueActivityKind.RETRY))
        .thenReturn(false);

    assertEquals(IssueReconcileOutcome.CONVERGED_FAILED, reconciler.reconcile(fixture.claim()));
    verify(workStore).completeWork(fixture.issue().getId(), "lease", 7L, NOW);
  }

  @Test
  void reconcileValidatesClaimShape() {
    // 测试意图：controller 的公开事务边界拒绝 null、空 token、非正 wake 和缺失 lease。
    assertThrows(NullPointerException.class, () -> reconciler.reconcile(null));
    ClaimedIssueWork missingLease =
        ClaimedIssueWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken(" ")
            .build();
    assertThrows(NullPointerException.class, () -> reconciler.reconcile(missingLease));
    ClaimedIssueWork blankToken =
        ClaimedIssueWork.builder()
            .issueId(UUID.randomUUID())
            .claimedWakeVersion(1L)
            .leaseToken(" ")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(blankToken));
    ClaimedIssueWork invalidWake =
        ClaimedIssueWork.builder()
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
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .version(0L)
            .build();
    Issue issue =
        Issue.builder()
            .id(UUID.randomUUID())
            .projectId(project.getId())
            .number(1L)
            .title("Issue")
            .status(status)
            .assigneeAgentName("executor-agent")
            .reviewerAgentName("reviewer-agent")
            .version(0L)
            .build();
    ClaimedIssueWork claim =
        ClaimedIssueWork.builder()
            .issueId(issue.getId())
            .claimedWakeVersion(7L)
            .leaseToken("lease")
            .leaseUntil(NOW.plusSeconds(30))
            .build();
    IssueRun activeRun = activeRole == null ? null : run(issue, activeRole, IssueRunStatus.RUNNING);
    when(issueRepository.getById(issue.getId())).thenReturn(issue);
    when(projectRepository.lockById(project.getId())).thenReturn(project);
    when(issueRepository.lockById(issue.getId())).thenReturn(issue);
    when(issueRunRepository.lockActiveByIssueId(issue.getId())).thenReturn(activeRun);
    return new Fixture(project, issue, activeRun, claim);
  }

  private IssueRun run(Issue issue, IssueRunRole role, IssueRunStatus status) {
    return IssueRun.builder()
        .id(UUID.randomUUID())
        .issueId(issue.getId())
        .ordinal(1L)
        .role(role)
        .agentName(role == IssueRunRole.EXECUTOR ? "executor-agent" : "reviewer-agent")
        .status(status)
        .observedActivitySequence(0L)
        .continuationCount(0)
        .maxContinuations(3)
        .deadline(NOW.plusSeconds(600))
        .version(0L)
        .build();
  }

  private ThreadSnapshot mockThreadSnapshot(boolean yoloEnabled) {
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    ThreadState thread =
        new ThreadState(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "0".repeat(64),
            "main",
            yoloEnabled,
            1L,
            0L,
            NOW,
            NOW);
    when(snapshot.thread()).thenReturn(thread);
    return snapshot;
  }

  private IssueAgentSession mockAgentSession(Fixture fixture) {
    return IssueAgentSession.builder()
        .id(UUID.randomUUID())
        .issueId(fixture.issue().getId())
        .agentName(fixture.activeRun().getAgentName())
        .sessionId(UUID.randomUUID())
        .threadId(UUID.randomUUID())
        .build();
  }

  private record Fixture(
      Project project, Issue issue, IssueRun activeRun, ClaimedIssueWork claim) {}
}
