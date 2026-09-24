package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.service.impl.IssueRunServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 IssueRunService 在状态围栏、持久层失败与唯一约束冲突下立即终止，且不会执行后续副作用。 */
class IssueRunServiceDefensiveUnitTest {

  private static final UUID PROJECT_ID = new UUID(0L, 1L);
  private static final String EXECUTOR = "executor";
  private static final String REVIEWER = "reviewer";

  @Test
  void testStartRunDefensiveFencesAndWriteFailures() {
    Fixture invalidLimit = new Fixture();
    assertValidation(
        "maxContinuations must not be negative",
        () ->
            invalidLimit.service.startExecutorRun(UUID.randomUUID(), EXECUTOR, Instant.now(), -1));
    assertValidation(
        "maxContinuations must not be negative",
        () ->
            invalidLimit.service.startReviewerRun(UUID.randomUUID(), REVIEWER, Instant.now(), -1));

    Fixture archivedProject = new Fixture();
    Issue archivedProjectIssue = issue(UUID.randomUUID(), IssueStatus.TODO);
    when(archivedProject.issues.getById(archivedProjectIssue.getId()))
        .thenReturn(archivedProjectIssue);
    when(archivedProject.projects.lockById(PROJECT_ID)).thenReturn(project(true));
    assertValidation(
        "Cannot start run in an archived project",
        () ->
            archivedProject.service.startExecutorRun(
                archivedProjectIssue.getId(), EXECUTOR, Instant.now(), 1));

    Fixture active = new Fixture();
    Issue activeIssue = issue(UUID.randomUUID(), IssueStatus.TODO);
    active.stubIssue(activeIssue);
    when(active.runs.lockActiveByIssueId(activeIssue.getId()))
        .thenReturn(run(UUID.randomUUID(), activeIssue.getId(), IssueRunRole.EXECUTOR));
    assertValidation(
        "Cannot start run: an active run already exists for issue",
        () -> active.service.startExecutorRun(activeIssue.getId(), EXECUTOR, Instant.now(), 1));

    Fixture createFailure = new Fixture();
    Issue createIssue = issue(UUID.randomUUID(), IssueStatus.TODO);
    createFailure.stubIssue(createIssue);
    when(createFailure.runs.create(any(IssueRun.class))).thenReturn(false);
    assertValidation(
        "Failed to create executor run",
        () ->
            createFailure.service.startExecutorRun(
                createIssue.getId(), EXECUTOR, Instant.now(), 1));

    Fixture issueUpdateFailure = new Fixture();
    Issue updateIssue = issue(UUID.randomUUID(), IssueStatus.TODO);
    issueUpdateFailure.stubIssue(updateIssue);
    when(issueUpdateFailure.runs.create(any(IssueRun.class))).thenReturn(true);
    when(issueUpdateFailure.issues.updateById(updateIssue, 0L)).thenReturn(false);
    assertValidation(
        "Failed to update issue status to IN_PROGRESS",
        () ->
            issueUpdateFailure.service.startExecutorRun(
                updateIssue.getId(), EXECUTOR, Instant.now(), 1));

    Fixture reviewerCreateFailure = new Fixture();
    Issue reviewIssue = issue(UUID.randomUUID(), IssueStatus.IN_REVIEW);
    IssueRun submission = submittedRun(UUID.randomUUID(), reviewIssue.getId());
    reviewerCreateFailure.stubIssue(reviewIssue);
    when(reviewerCreateFailure.runs.listByIssueId(reviewIssue.getId()))
        .thenReturn(List.of(submission));
    when(reviewerCreateFailure.runs.create(any(IssueRun.class))).thenReturn(false);
    assertValidation(
        "Failed to create reviewer run",
        () ->
            reviewerCreateFailure.service.startReviewerRun(
                reviewIssue.getId(), REVIEWER, Instant.now(), 1));
  }

  @Test
  void testCompleteExecutorRunStateAndAffectedRowFences() {
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    Fixture terminal = new Fixture();
    Issue terminalIssue = issue(issueId, IssueStatus.IN_PROGRESS);
    IssueRun terminalRun = run(runId, issueId, IssueRunRole.EXECUTOR);
    terminalRun.setStatus(IssueRunStatus.COMPLETED);
    terminal.stubLockChain(terminalIssue, terminalRun);
    assertValidation(
        "Run is already terminal and cannot be submitted",
        () -> terminal.service.completeExecutorRun(runId, "action", "summary", "verification"));

    Fixture wrongRole = new Fixture();
    Issue wrongRoleIssue = issue(issueId, IssueStatus.IN_PROGRESS);
    IssueRun reviewerRun = run(runId, issueId, IssueRunRole.REVIEWER);
    wrongRole.stubLockChain(wrongRoleIssue, reviewerRun);
    assertValidation(
        "Only RUNNING EXECUTOR run can be completed, current role=REVIEWER, status=RUNNING",
        () -> wrongRole.service.completeExecutorRun(runId, "action", "summary", "verification"));

    Fixture wrongIssueStatus = new Fixture();
    Issue todoIssue = issue(issueId, IssueStatus.TODO);
    wrongIssueStatus.stubLockChain(todoIssue, run(runId, issueId, IssueRunRole.EXECUTOR));
    assertValidation(
        "Issue must be in IN_PROGRESS for submit, current status=TODO",
        () ->
            wrongIssueStatus.service.completeExecutorRun(
                runId, "action", "summary", "verification"));

    Fixture runUpdateFailure = new Fixture();
    Issue updateIssue = issue(issueId, IssueStatus.IN_PROGRESS);
    runUpdateFailure.stubLockChain(updateIssue, run(runId, issueId, IssueRunRole.EXECUTOR));
    when(runUpdateFailure.runs.updateById(any(IssueRun.class), eq(0L))).thenReturn(false);
    assertValidation(
        "Failed to update run to COMPLETED",
        () ->
            runUpdateFailure.service.completeExecutorRun(
                runId, "action", "summary", "verification"));
    verify(runUpdateFailure.issues, never()).updateById(any(Issue.class), anyLong());

    Fixture issueUpdateFailure = new Fixture();
    Issue issueWrite = issue(issueId, IssueStatus.IN_PROGRESS);
    issueUpdateFailure.stubLockChain(issueWrite, run(runId, issueId, IssueRunRole.EXECUTOR));
    when(issueUpdateFailure.runs.updateById(any(IssueRun.class), eq(0L))).thenReturn(true);
    when(issueUpdateFailure.issues.updateById(issueWrite, 0L)).thenReturn(false);
    assertValidation(
        "Failed to update issue status to IN_REVIEW",
        () ->
            issueUpdateFailure.service.completeExecutorRun(
                runId, "action", "summary", "verification"));
    verify(issueUpdateFailure.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  @Test
  void testTerminalActionConstraintTranslationAndUnknownPropagation() {
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    Fixture known = new Fixture();
    known.stubLockChain(
        issue(issueId, IssueStatus.IN_PROGRESS), run(runId, issueId, IssueRunRole.EXECUTOR));
    when(known.runs.updateById(any(IssueRun.class), eq(0L)))
        .thenThrow(constraintViolation("uk_issue_run_terminal_action"));
    AiValidationException translated =
        assertThrows(
            AiValidationException.class,
            () -> known.service.completeExecutorRun(runId, "action", "summary", "verification"));
    assertEquals("Terminal action ID conflict", translated.getMessage());
    assertNull(translated.getCause());

    Fixture unknown = new Fixture();
    unknown.stubLockChain(
        issue(issueId, IssueStatus.IN_PROGRESS), run(runId, issueId, IssueRunRole.EXECUTOR));
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("unrelated constraint");
    when(unknown.runs.updateById(any(IssueRun.class), eq(0L))).thenThrow(original);
    assertSame(
        original,
        assertThrows(
            DataIntegrityViolationException.class,
            () -> unknown.service.completeExecutorRun(runId, "action", "summary", "verification")));
  }

  @Test
  void testRequestInputRoleStatusAndWriteFences() {
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    Fixture notRunning = new Fixture();
    Issue inProgress = issue(issueId, IssueStatus.IN_PROGRESS);
    IssueRun waitingRun = run(runId, issueId, IssueRunRole.EXECUTOR);
    waitingRun.setStatus(IssueRunStatus.WAITING_HUMAN);
    notRunning.stubLockChain(inProgress, waitingRun);
    assertValidation(
        "Only RUNNING run can request input, current status=WAITING_HUMAN",
        () -> notRunning.service.requestInput(runId, "question", null));

    Fixture updateFailure = new Fixture();
    updateFailure.stubLockChain(
        issue(issueId, IssueStatus.IN_PROGRESS), run(runId, issueId, IssueRunRole.EXECUTOR));
    when(updateFailure.runs.updateById(any(IssueRun.class), eq(0L))).thenReturn(false);
    assertValidation(
        "Failed to update run to WAITING_HUMAN",
        () -> updateFailure.service.requestInput(runId, "question", null));
    verify(updateFailure.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  @Test
  void testReviewByAgentFences() {
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    Fixture blankAgent = new Fixture();
    assertValidation(
        "reviewerAgentName must not be blank",
        () ->
            blankAgent.service.reviewByAgent(
                runId, "  ", "action", ReviewDecision.APPROVE, "reason"));

    Fixture blankAction = new Fixture();
    assertValidation(
        "terminalActionId must not be blank",
        () ->
            blankAction.service.reviewByAgent(
                runId, REVIEWER, "  ", ReviewDecision.APPROVE, "reason"));

    Fixture wrongStatus = new Fixture();
    Issue todoIssue = issue(issueId, IssueStatus.TODO);
    wrongStatus.stubLockChain(todoIssue, run(runId, issueId, IssueRunRole.REVIEWER));
    assertValidation(
        "Issue must be in IN_REVIEW to be reviewed, current is TODO",
        () ->
            wrongStatus.service.reviewByAgent(
                runId, REVIEWER, "action", ReviewDecision.APPROVE, "reason"));

    Fixture wrongRole = new Fixture();
    Issue reviewIssue = issue(issueId, IssueStatus.IN_REVIEW);
    wrongRole.stubLockChain(reviewIssue, run(runId, issueId, IssueRunRole.EXECUTOR));
    assertValidation(
        "Agent review requires a RUNNING REVIEWER run, found role=EXECUTOR, status=RUNNING",
        () ->
            wrongRole.service.reviewByAgent(
                runId, REVIEWER, "action", ReviewDecision.APPROVE, "reason"));

    Fixture identity = new Fixture();
    Issue identityIssue = issue(issueId, IssueStatus.IN_REVIEW);
    IssueRun identityRun = run(runId, issueId, IssueRunRole.REVIEWER);
    identityRun.setAgentName("another-reviewer");
    identity.stubLockChain(identityIssue, identityRun);
    assertValidation(
        "Reviewer agent identity mismatch",
        () ->
            identity.service.reviewByAgent(
                runId, REVIEWER, "action", ReviewDecision.APPROVE, "reason"));

    Fixture updateFailure = new Fixture();
    Issue updateIssue = issue(issueId, IssueStatus.IN_REVIEW);
    updateFailure.stubLockChain(updateIssue, run(runId, issueId, IssueRunRole.REVIEWER));
    when(updateFailure.runs.updateById(any(IssueRun.class), eq(0L))).thenReturn(false);
    assertValidation(
        "Failed to update reviewer run",
        () ->
            updateFailure.service.reviewByAgent(
                runId, REVIEWER, "action", ReviewDecision.APPROVE, "reason"));
  }

  @Test
  void testReviewByHumanFences() {
    UUID issueId = UUID.randomUUID();

    Fixture oversizedKey = new Fixture();
    assertValidation(
        "idempotencyKey exceeds maximum allowed length of 128 characters",
        () ->
            oversizedKey.service.reviewByHuman(
                issueId, ReviewDecision.APPROVE, "reason", "k".repeat(129)));

    Fixture notInReview = new Fixture();
    Issue todoIssue = issue(issueId, IssueStatus.TODO);
    notInReview.stubIssue(todoIssue);
    assertValidation(
        "Issue must be in IN_REVIEW to be reviewed, current is TODO",
        () -> notInReview.service.reviewByHuman(issueId, ReviewDecision.APPROVE, "reason", "key"));

    Fixture noSubmission = new Fixture();
    Issue reviewIssue = issue(issueId, IssueStatus.IN_REVIEW);
    reviewIssue.setReviewerAgentName(null);
    noSubmission.stubIssue(reviewIssue);
    when(noSubmission.runs.listByIssueId(issueId)).thenReturn(List.of());
    assertValidation(
        "No submitted executor run found to review",
        () -> noSubmission.service.reviewByHuman(issueId, ReviewDecision.APPROVE, "reason", "key"));

    Fixture issueUpdateFailure = humanReviewFixture(issueId);
    when(issueUpdateFailure.issues.updateById(any(Issue.class), eq(0L))).thenReturn(false);
    assertValidation(
        "Failed to update issue status",
        () ->
            issueUpdateFailure.service.reviewByHuman(
                issueId, ReviewDecision.REQUEST_CHANGES, "summary", "key"));
    verify(issueUpdateFailure.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  @Test
  void testFailAndRetryRunFences() {
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    Fixture terminal = new Fixture();
    IssueRun terminalRun = run(runId, issueId, IssueRunRole.EXECUTOR);
    terminalRun.setStatus(IssueRunStatus.FAILED);
    terminal.stubLockChain(issue(issueId, IssueStatus.IN_PROGRESS), terminalRun);
    assertValidation(
        "Run is already terminal and cannot be failed",
        () -> terminal.service.failRun(runId, IssueRunStatus.FAILED, "reason"));

    Fixture invalidTerminalStatus = new Fixture();
    invalidTerminalStatus.stubLockChain(
        issue(issueId, IssueStatus.IN_PROGRESS), run(runId, issueId, IssueRunRole.EXECUTOR));
    assertValidation(
        "Terminal status must be FAILED or UNKNOWN, but was RUNNING",
        () -> invalidTerminalStatus.service.failRun(runId, IssueRunStatus.RUNNING, "reason"));

    Fixture failUpdate = new Fixture();
    failUpdate.stubLockChain(
        issue(issueId, IssueStatus.IN_PROGRESS), run(runId, issueId, IssueRunRole.EXECUTOR));
    when(failUpdate.runs.updateById(any(IssueRun.class), eq(0L))).thenReturn(false);
    assertValidation(
        "Failed to fail run",
        () -> failUpdate.service.failRun(runId, IssueRunStatus.FAILED, "reason"));
    verify(failUpdate.workStore, never()).requestWork(eq(issueId), any(Instant.class));

    Fixture blankKey = new Fixture();
    assertValidation(
        "idempotencyKey must not be blank", () -> blankKey.service.retryRun(issueId, "  "));

    Fixture noLatestRun = new Fixture();
    noLatestRun.stubIssue(issue(issueId, IssueStatus.IN_PROGRESS));
    when(noLatestRun.runs.findLatestByIssueId(issueId)).thenReturn(null);
    assertValidation(
        "Retry is only allowed when the latest run is FAILED or UNKNOWN, but was null",
        () -> noLatestRun.service.retryRun(issueId, "retry"));

    Fixture activeRun = retryFixture(issueId);
    when(activeRun.runs.lockActiveByIssueId(issueId))
        .thenReturn(run(UUID.randomUUID(), issueId, IssueRunRole.EXECUTOR));
    assertValidation(
        "Cannot retry while an active run exists",
        () -> activeRun.service.retryRun(issueId, "retry"));
  }

  private static Fixture humanReviewFixture(UUID issueId) {
    Fixture fixture = new Fixture();
    Issue issue = issue(issueId, IssueStatus.IN_REVIEW);
    issue.setReviewerAgentName(null);
    fixture.stubIssue(issue);
    when(fixture.runs.listByIssueId(issueId))
        .thenReturn(List.of(submittedRun(UUID.randomUUID(), issueId)));
    when(fixture.runs.allocateNextOrdinal(issueId)).thenReturn(2L);
    when(fixture.activities.findReviewWindowStartSequence(issueId)).thenReturn(0L);
    when(fixture.activities.countRejectionsSince(eq(issueId), anyLong())).thenReturn(0L);
    return fixture;
  }

  private static Fixture retryFixture(UUID issueId) {
    Fixture fixture = new Fixture();
    fixture.stubIssue(issue(issueId, IssueStatus.IN_PROGRESS));
    IssueRun latest = run(UUID.randomUUID(), issueId, IssueRunRole.EXECUTOR);
    latest.setStatus(IssueRunStatus.FAILED);
    when(fixture.runs.findLatestByIssueId(issueId)).thenReturn(latest);
    return fixture;
  }

  private static Issue issue(UUID id, IssueStatus status) {
    return Issue.builder()
        .id(id)
        .projectId(PROJECT_ID)
        .number(1L)
        .title("Title")
        .description("")
        .status(status)
        .assigneeAgentName(EXECUTOR)
        .reviewerAgentName(REVIEWER)
        .version(0L)
        .build();
  }

  private static Project project(boolean archived) {
    return Project.builder()
        .id(PROJECT_ID)
        .title("Project")
        .description("")
        .yoloEnabled(true)
        .maxReviewRejections(3)
        .version(0L)
        .archivedAt(archived ? Instant.now() : null)
        .build();
  }

  private static IssueRun run(UUID id, UUID issueId, IssueRunRole role) {
    return IssueRun.builder()
        .id(id)
        .issueId(issueId)
        .ordinal(1L)
        .role(role)
        .agentName(role == IssueRunRole.REVIEWER ? REVIEWER : EXECUTOR)
        .status(IssueRunStatus.RUNNING)
        .observedActivitySequence(0L)
        .continuationCount(0)
        .maxContinuations(1)
        .version(0L)
        .build();
  }

  private static IssueRun submittedRun(UUID id, UUID issueId) {
    IssueRun run = run(id, issueId, IssueRunRole.EXECUTOR);
    run.setStatus(IssueRunStatus.COMPLETED);
    run.setOutcome(IssueRunOutcome.SUBMITTED);
    return run;
  }

  private static void assertValidation(String expectedMessage, Executable action) {
    assertEquals(expectedMessage, assertThrows(AiValidationException.class, action).getMessage());
  }

  private static DataIntegrityViolationException constraintViolation(String constraint) {
    ServerErrorMessage serverError =
        new ServerErrorMessage("SERROR\0C23505\0n" + constraint + "\0\0");
    return new DataIntegrityViolationException(
        "Database write failed", new PSQLException(serverError));
  }

  private static final class Fixture {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);
    private final IssueDependencyRepository dependencies = mock(IssueDependencyRepository.class);
    private final IssueActivityRepository activities = mock(IssueActivityRepository.class);
    private final IssueRunRepository runs = mock(IssueRunRepository.class);
    private final IssueAgentSessionRepository agentSessions =
        mock(IssueAgentSessionRepository.class);
    private final IssueWorkStore workStore = mock(IssueWorkStore.class);
    private final IssueEvidenceService evidenceService = mock(IssueEvidenceService.class);
    private final IssueRunServiceImpl service =
        new IssueRunServiceImpl(
            projects,
            issues,
            dependencies,
            activities,
            runs,
            agentSessions,
            workStore,
            evidenceService,
            new ObjectMapper());

    private void stubIssue(Issue issue) {
      when(issues.getById(issue.getId())).thenReturn(issue);
      when(projects.lockById(issue.getProjectId())).thenReturn(project(false));
      when(issues.lockById(issue.getId())).thenReturn(issue);
      when(dependencies.listByIssueId(issue.getId())).thenReturn(List.of());
    }

    private void stubLockChain(Issue issue, IssueRun run) {
      stubIssue(issue);
      when(runs.getById(run.getId())).thenReturn(run);
      when(runs.lockById(run.getId())).thenReturn(run);
    }
  }
}
