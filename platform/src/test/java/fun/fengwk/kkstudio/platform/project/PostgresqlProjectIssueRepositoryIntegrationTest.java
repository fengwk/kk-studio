package fun.fengwk.kkstudio.platform.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueWork;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Project / Issue 仓储实现层聚焦集成测试。 覆盖 Project, Issue, Dependency, Run, Activity, AgentSession 与 Work
 * 仓储的 CRUD、版本 CAS、 幂等追加、审查窗口计数与租约围栏语义。
 */
class PostgresqlProjectIssueRepositoryIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectRepository projectRepository;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;
  @Autowired private IssueAgentSessionRepository issueAgentSessionRepository;
  @Autowired private IssueWorkRepository issueWorkRepository;

  @Test
  void testProjectCrudAndCas() {
    UUID projectId = UUID.randomUUID();
    Project project =
        Project.builder()
            .id(projectId)
            .title("Core Platform")
            .description("Core Platform Description")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build();

    // Create
    assertThat(projectRepository.create(project)).isTrue();

    // Read back
    Project loaded = projectRepository.getById(projectId);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getTitle()).isEqualTo("Core Platform");
    assertThat(loaded.getDescription()).isEqualTo("Core Platform Description");
    assertThat(loaded.isYoloEnabled()).isTrue();
    assertThat(loaded.getMaxReviewRejections()).isEqualTo(3);
    assertThat(loaded.getNextIssueNumber()).isEqualTo(1L);
    assertThat(loaded.getVersion()).isEqualTo(0L);
    assertThat(loaded.getArchivedAt()).isNull();

    // Locks
    assertThat(projectRepository.lockById(projectId)).isNotNull();
    assertThat(projectRepository.lockForShare(projectId)).isNotNull();
    assertThat(projectRepository.lockForKeyShare(projectId)).isNotNull();

    // Allocate next issue number
    assertThat(projectRepository.allocateNextIssueNumber(projectId)).isEqualTo(1L);
    assertThat(projectRepository.allocateNextIssueNumber(projectId)).isEqualTo(2L);

    // CAS Update
    loaded.setTitle("Updated Title");
    loaded.setYoloEnabled(false);
    loaded.setMaxReviewRejections(5);
    assertThat(projectRepository.updateById(loaded, 999L)).isFalse();
    assertThat(projectRepository.updateById(loaded, 0L)).isTrue();

    Project updated = projectRepository.getById(projectId);
    assertThat(updated.getTitle()).isEqualTo("Updated Title");
    assertThat(updated.isYoloEnabled()).isFalse();
    assertThat(updated.getMaxReviewRejections()).isEqualTo(5);
    assertThat(updated.getVersion()).isEqualTo(1L);

    // Archive
    Instant now = Instant.now();
    assertThat(projectRepository.updateArchivedAt(projectId, now, 0L)).isFalse();
    assertThat(projectRepository.updateArchivedAt(projectId, now, 1L)).isTrue();

    assertThat(projectRepository.listByArchived(true))
        .extracting(Project::getId)
        .contains(projectId);
    assertThat(projectRepository.listByArchived(false))
        .extracting(Project::getId)
        .doesNotContain(projectId);

    // Delete with CAS
    assertThat(projectRepository.deleteById(projectId, 1L)).isFalse();
    assertThat(projectRepository.deleteById(projectId, 2L)).isTrue();
    assertThat(projectRepository.getById(projectId)).isNull();
  }

  @Test
  void testIssueCrudAndCas() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Issue Test Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    String assignee = createTestAgent();
    String reviewer = createTestAgent();

    UUID issueId = UUID.randomUUID();
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Task 1")
            .description("Implement feature 1")
            .status(IssueStatus.BACKLOG)
            .assigneeAgentName(assignee)
            .reviewerAgentName(reviewer)
            .build();

    // Create
    assertThat(issueRepository.create(issue)).isTrue();

    // Read back
    Issue loaded = issueRepository.getById(issueId);
    assertThat(loaded).isNotNull();
    assertThat(loaded.getProjectId()).isEqualTo(projectId);
    assertThat(loaded.getNumber()).isEqualTo(1L);
    assertThat(loaded.getTitle()).isEqualTo("Task 1");
    assertThat(loaded.getStatus()).isEqualTo(IssueStatus.BACKLOG);
    assertThat(loaded.getAssigneeAgentName()).isEqualTo(assignee);
    assertThat(loaded.getReviewerAgentName()).isEqualTo(reviewer);
    assertThat(loaded.getVersion()).isEqualTo(0L);

    assertThat(issueRepository.getByProjectAndNumber(projectId, 1L)).isNotNull();
    assertThat(issueRepository.lockById(issueId)).isNotNull();
    assertThat(issueRepository.listByProjectId(projectId)).hasSize(1);

    // CAS Update
    loaded.setTitle("Task 1 Updated");
    loaded.setStatus(IssueStatus.TODO);
    assertThat(issueRepository.updateById(loaded, 999L)).isFalse();
    assertThat(issueRepository.updateById(loaded, 0L)).isTrue();

    Issue updated = issueRepository.getById(issueId);
    assertThat(updated.getTitle()).isEqualTo("Task 1 Updated");
    assertThat(updated.getStatus()).isEqualTo(IssueStatus.TODO);
    assertThat(updated.getVersion()).isEqualTo(1L);

    // Archive check
    assertThat(issueRepository.listByProjectIdAndArchived(projectId, false)).hasSize(1);
    assertThat(issueRepository.listByProjectIdAndArchived(projectId, true)).isEmpty();

    // Delete with CAS
    assertThat(issueRepository.deleteById(issueId, 0L)).isFalse();
    assertThat(issueRepository.deleteById(issueId, 1L)).isTrue();
    assertThat(issueRepository.getById(issueId)).isNull();
  }

  @Test
  void testIssueDependencyAndPathCheck() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Dependency Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    UUID issue1Id = UUID.randomUUID();
    UUID issue2Id = UUID.randomUUID();
    UUID issue3Id = UUID.randomUUID();

    issueRepository.create(
        Issue.builder()
            .id(issue1Id)
            .projectId(projectId)
            .number(1L)
            .title("Issue 1")
            .description("Dependency fixture issue 1")
            .status(IssueStatus.TODO)
            .build());
    issueRepository.create(
        Issue.builder()
            .id(issue2Id)
            .projectId(projectId)
            .number(2L)
            .title("Issue 2")
            .description("Dependency fixture issue 2")
            .status(IssueStatus.TODO)
            .build());
    issueRepository.create(
        Issue.builder()
            .id(issue3Id)
            .projectId(projectId)
            .number(3L)
            .title("Issue 3")
            .description("Dependency fixture issue 3")
            .status(IssueStatus.TODO)
            .build());

    // Add dependencies: issue2 depends on issue1 (2 -> 1), issue3 depends on issue2 (3 -> 2)
    IssueDependency dep21 =
        IssueDependency.builder()
            .issueId(issue2Id)
            .dependsOnIssueId(issue1Id)
            .projectId(projectId)
            .build();
    IssueDependency dep32 =
        IssueDependency.builder()
            .issueId(issue3Id)
            .dependsOnIssueId(issue2Id)
            .projectId(projectId)
            .build();

    assertThat(issueDependencyRepository.addDependency(dep21)).isTrue();
    assertThat(dep21.getCreatedAt()).isNotNull();
    assertThat(issueDependencyRepository.addDependency(dep32)).isTrue();

    // Query dependencies
    assertThat(issueDependencyRepository.listByIssueId(issue2Id))
        .extracting(IssueDependency::getDependsOnIssueId)
        .containsExactly(issue1Id);
    assertThat(issueDependencyRepository.listByDependsOnIssueId(issue1Id))
        .extracting(IssueDependency::getIssueId)
        .containsExactly(issue2Id);
    assertThat(issueDependencyRepository.listByProjectId(projectId)).hasSize(2);

    // Path check (transitive reachability)
    assertThat(issueDependencyRepository.checkHasPath(issue3Id, issue1Id)).isTrue();
    assertThat(issueDependencyRepository.checkHasPath(issue1Id, issue3Id)).isFalse();

    // Self-loop rejection by DB check constraint
    IssueDependency selfLoop =
        IssueDependency.builder()
            .issueId(issue1Id)
            .dependsOnIssueId(issue1Id)
            .projectId(projectId)
            .build();
    assertThatThrownBy(() -> issueDependencyRepository.addDependency(selfLoop))
        .isInstanceOf(DataAccessException.class);

    // Remove dependency
    assertThat(issueDependencyRepository.removeDependency(issue2Id, issue1Id)).isTrue();
    assertThat(issueDependencyRepository.checkHasPath(issue3Id, issue1Id)).isFalse();

    // Delete by project
    assertThat(issueDependencyRepository.deleteByProjectId(projectId)).isEqualTo(1);
    assertThat(issueDependencyRepository.listByProjectId(projectId)).isEmpty();
  }

  @Test
  void testIssueRunLifecycleAndCas() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Run Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    String agentName = createTestAgent();
    UUID issueId = UUID.randomUUID();
    issueRepository.create(
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Run Issue")
            .description("Run lifecycle fixture issue")
            .status(IssueStatus.IN_PROGRESS)
            .build());

    // 1. Create EXECUTOR run
    UUID run1Id = UUID.randomUUID();
    IssueRun run1 =
        IssueRun.builder()
            .id(run1Id)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agentName)
            .status(IssueRunStatus.RUNNING)
            .observedActivitySequence(0L)
            .continuationCount(0)
            .maxContinuations(10)
            .deadline(Instant.now().plusSeconds(3600))
            .build();

    assertThat(issueRunRepository.create(run1)).isTrue();
    assertThat(issueRunRepository.findActiveByIssueId(issueId)).isNotNull();
    assertThat(issueRunRepository.lockActiveByIssueId(issueId)).isNotNull();
    assertThat(issueRunRepository.allocateNextOrdinal(issueId)).isEqualTo(2L);

    // 2. Complete EXECUTOR run with SUBMITTED outcome
    run1.setStatus(IssueRunStatus.COMPLETED);
    run1.setOutcome(IssueRunOutcome.SUBMITTED);
    run1.setResult("{\"summary\":\"done\"}");
    run1.setTerminalActionId("action-executor-1");
    run1.setCompletedAt(Instant.now());

    assertThat(issueRunRepository.updateById(run1, 999L)).isFalse();
    assertThat(issueRunRepository.updateById(run1, 0L)).isTrue();

    assertThat(issueRunRepository.findByTerminalActionId("action-executor-1")).isNotNull();
    assertThat(issueRunRepository.findActiveByIssueId(issueId)).isNull();

    // 3. Create REVIEWER run targeting run1
    UUID run2Id = UUID.randomUUID();
    IssueRun run2 =
        IssueRun.builder()
            .id(run2Id)
            .issueId(issueId)
            .ordinal(2L)
            .role(IssueRunRole.REVIEWER)
            .agentName(agentName)
            .submissionRunId(run1Id)
            .status(IssueRunStatus.RUNNING)
            .observedActivitySequence(1L)
            .continuationCount(0)
            .maxContinuations(10)
            .deadline(Instant.now().plusSeconds(3600))
            .build();

    assertThat(issueRunRepository.create(run2)).isTrue();
    assertThat(issueRunRepository.findLatestByIssueId(issueId).getId()).isEqualTo(run2Id);

    List<IssueRun> runs = issueRunRepository.listByIssueId(issueId);
    assertThat(runs).hasSize(2);
    assertThat(runs.get(0).getId()).isEqualTo(run1Id);
    assertThat(runs.get(1).getId()).isEqualTo(run2Id);
  }

  @Test
  void testIssueActivityIdempotentAppendAndReviewWindow() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Activity Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    String agentName = createTestAgent();
    UUID issueId = UUID.randomUUID();
    issueRepository.create(
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Activity Issue")
            .description("Activity fact stream fixture issue")
            .status(IssueStatus.IN_PROGRESS)
            .build());

    // 1. Monotonic sequence allocation
    IssueActivity act1 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.HUMAN_INPUT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Initial requirement specification")
                .build());
    assertThat(act1.getSequence()).isEqualTo(1L);
    assertThat(act1.getCreatedAt()).isNotNull();

    IssueActivity act2 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.INSTRUCTION)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Please execute task")
                .build());
    assertThat(act2.getSequence()).isEqualTo(2L);

    // 2. Idempotent append with same idempotency key
    IssueActivity act3 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.COMMENT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("First comment")
                .idempotencyKey("idem-act-1")
                .build());
    assertThat(act3.getSequence()).isEqualTo(3L);
    assertThat(act3.getBody()).isEqualTo("First comment");

    // Repeat with same key but different body
    IssueActivity act3Repeat =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.COMMENT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Attempted second comment with same key")
                .idempotencyKey("idem-act-1")
                .build());
    assertThat(act3Repeat.getSequence()).isEqualTo(3L);
    assertThat(act3Repeat.getBody()).isEqualTo("First comment");

    assertThat(issueActivityRepository.listByIssueId(issueId)).hasSize(3);
    assertThat(issueActivityRepository.findByIssueIdAndIdempotencyKey(issueId, "idem-act-1"))
        .isNotNull();

    // 3. Review window and countRejectionsSince distinct submission calculation
    assertThat(issueActivityRepository.findReviewWindowStartSequence(issueId)).isEqualTo(0L);

    // Create two runs to serve as submissionRunId
    UUID subRun1 = UUID.randomUUID();
    UUID subRun2 = UUID.randomUUID();
    issueRunRepository.create(
        IssueRun.builder()
            .id(subRun1)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agentName)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .result("{\"ok\":true}")
            .terminalActionId("term-1")
            .completedAt(Instant.now())
            .build());
    issueRunRepository.create(
        IssueRun.builder()
            .id(subRun2)
            .issueId(issueId)
            .ordinal(2L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agentName)
            .status(IssueRunStatus.COMPLETED)
            .outcome(IssueRunOutcome.SUBMITTED)
            .result("{\"ok\":true}")
            .terminalActionId("term-2")
            .completedAt(Instant.now())
            .build());

    // Rejection 1 on subRun1 by AGENT
    IssueActivity rej1 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.REVIEW_DECISION)
                .actorType(IssueActivityActorType.AGENT)
                .actorAgentName(agentName)
                .submissionRunId(subRun1)
                .decision(ReviewDecision.REQUEST_CHANGES)
                .body("Changes requested on submission 1")
                .build());
    assertThat(rej1.getSequence()).isEqualTo(4L);

    // Rejection 2 on SAME subRun1 by AGENT (should not increase distinct submission rejection
    // count)
    IssueActivity rej2 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.REVIEW_DECISION)
                .actorType(IssueActivityActorType.AGENT)
                .actorAgentName(agentName)
                .submissionRunId(subRun1)
                .decision(ReviewDecision.REQUEST_CHANGES)
                .body("Another rejection note on submission 1")
                .build());
    assertThat(rej2.getSequence()).isEqualTo(5L);

    // Rejection 3 on subRun2 by HUMAN
    IssueActivity rej3 =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.REVIEW_DECISION)
                .actorType(IssueActivityActorType.HUMAN)
                .submissionRunId(subRun2)
                .decision(ReviewDecision.REQUEST_CHANGES)
                .body("Human changes requested on submission 2")
                .build());
    assertThat(rej3.getSequence()).isEqualTo(6L);

    // Total distinct submission rejections since 0 should be 2 (subRun1 and subRun2)
    assertThat(issueActivityRepository.countRejectionsSince(issueId, 0L)).isEqualTo(2L);
    // By actor type
    assertThat(
            issueActivityRepository.countRejectionsSince(issueId, 0L, IssueActivityActorType.AGENT))
        .isEqualTo(1L);
    assertThat(
            issueActivityRepository.countRejectionsSince(issueId, 0L, IssueActivityActorType.HUMAN))
        .isEqualTo(1L);

    // Append RECOVERY activity -> starts review window
    IssueActivity rec =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.RECOVERY)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Unblocked manually")
                .build());
    assertThat(rec.getSequence()).isEqualTo(7L);
    assertThat(issueActivityRepository.findReviewWindowStartSequence(issueId)).isEqualTo(7L);

    // Rejections after window start sequence 7 should now be 0
    assertThat(issueActivityRepository.countRejectionsSince(issueId, 7L)).isEqualTo(0L);

    // Append SPEC_CHANGE activity -> updates review window start
    IssueActivity specChange =
        issueActivityRepository.appendOrGet(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.SPEC_CHANGE)
                .actorType(IssueActivityActorType.HUMAN)
                .body("Updated specifications")
                .build());
    assertThat(specChange.getSequence()).isEqualTo(8L);
    assertThat(issueActivityRepository.findReviewWindowStartSequence(issueId)).isEqualTo(8L);

    // New rejection on subRun2 after window start
    issueActivityRepository.appendOrGet(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.HUMAN)
            .submissionRunId(subRun2)
            .decision(ReviewDecision.REQUEST_CHANGES)
            .body("Rejection after spec change")
            .build());

    assertThat(issueActivityRepository.countRejectionsSince(issueId, 8L)).isEqualTo(1L);
    assertThat(issueActivityRepository.existsByIssueIdAndKind(issueId, IssueActivityKind.RECOVERY))
        .isTrue();
    assertThat(issueActivityRepository.existsByIssueIdAndKind(issueId, IssueActivityKind.RETRY))
        .isFalse();
    assertThat(
            issueActivityRepository.existsAfterSequenceAndKind(
                issueId, 7L, IssueActivityKind.SPEC_CHANGE))
        .isTrue();
    assertThat(
            issueActivityRepository.existsAfterSequenceAndKind(
                issueId, 8L, IssueActivityKind.SPEC_CHANGE))
        .isFalse();

    List<IssueActivity> paged = issueActivityRepository.listPage(issueId, 7L, 10);
    assertThat(paged).hasSize(2);
    assertThat(paged.get(0).getSequence()).isEqualTo(8L);
    assertThat(paged.get(0).getKind()).isEqualTo(IssueActivityKind.SPEC_CHANGE);
    assertThat(paged.get(1).getSequence()).isEqualTo(9L);
    assertThat(paged.get(1).getKind()).isEqualTo(IssueActivityKind.REVIEW_DECISION);

    List<IssueActivity> limited = issueActivityRepository.listPage(issueId, 7L, 1);
    assertThat(limited).hasSize(1);
    assertThat(limited.get(0).getSequence()).isEqualTo(8L);

    assertThat(issueActivityRepository.listPage(issueId, 9L, 10)).isEmpty();
  }

  @Test
  void testIssueAgentSessionBindOrGet() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Session Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    String agentName = createTestAgent();
    UUID issueId = UUID.randomUUID();
    issueRepository.create(
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Session Issue")
            .description("Agent session fixture issue")
            .status(IssueStatus.TODO)
            .build());

    UUID sessionId1 = createHarnessSession();
    UUID threadId1 = createHarnessThread(sessionId1);
    UUID bindingId1 = UUID.randomUUID();

    IssueAgentSession binding1 =
        IssueAgentSession.builder()
            .id(bindingId1)
            .issueId(issueId)
            .agentName(agentName)
            .sessionId(sessionId1)
            .threadId(threadId1)
            .build();

    // 1. First bind
    IssueAgentSession bound1 = issueAgentSessionRepository.bindOrGet(binding1);
    assertThat(bound1).isNotNull();
    assertThat(bound1.getId()).isEqualTo(bindingId1);
    assertThat(bound1.getIssueId()).isEqualTo(issueId);
    assertThat(bound1.getAgentName()).isEqualTo(agentName);
    assertThat(bound1.getSessionId()).isEqualTo(sessionId1);
    assertThat(bound1.getThreadId()).isEqualTo(threadId1);

    // 2. Repeat bind with same (issueId, agentName) but different session/thread/id
    UUID sessionId2 = createHarnessSession();
    UUID threadId2 = createHarnessThread(sessionId2);
    UUID bindingId2 = UUID.randomUUID();

    IssueAgentSession binding2 =
        IssueAgentSession.builder()
            .id(bindingId2)
            .issueId(issueId)
            .agentName(agentName)
            .sessionId(sessionId2)
            .threadId(threadId2)
            .build();

    IssueAgentSession bound2 = issueAgentSessionRepository.bindOrGet(binding2);
    assertThat(bound2).isNotNull();
    assertThat(bound2.getId()).isEqualTo(bindingId1);
    assertThat(bound2.getSessionId()).isEqualTo(sessionId1);

    // Lookups
    assertThat(issueAgentSessionRepository.getById(bindingId1)).isNotNull();
    assertThat(issueAgentSessionRepository.findByIssueIdAndAgentName(issueId, agentName))
        .isNotNull();
    assertThat(issueAgentSessionRepository.findBySessionId(sessionId1)).isNotNull();
    assertThat(issueAgentSessionRepository.findByThreadId(threadId1)).isNotNull();

    // Delete
    assertThat(issueAgentSessionRepository.deleteById(bindingId1)).isTrue();
    assertThat(issueAgentSessionRepository.getById(bindingId1)).isNull();
  }

  @Test
  void testIssueWorkClaimRenewAndFencing() {
    UUID projectId = UUID.randomUUID();
    projectRepository.create(
        Project.builder()
            .id(projectId)
            .title("Work Project")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
            .build());

    UUID issueId = UUID.randomUUID();
    issueRepository.create(
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Work Issue")
            .description("Work claim fixture issue")
            .status(IssueStatus.TODO)
            .build());

    // 1. Request work -> upsert wake version 1
    Instant due1 = Instant.now().minusSeconds(10);
    IssueWork work1 = issueWorkRepository.requestWork(issueId, due1);
    assertThat(work1.getWakeVersion()).isEqualTo(1L);

    // 2. Request work again -> wake version 2
    IssueWork work2 = issueWorkRepository.requestWork(issueId, due1);
    assertThat(work2.getWakeVersion()).isEqualTo(2L);

    // 3. Claim work
    String leaseToken = "lease-token-" + UUID.randomUUID();
    Instant now = Instant.now();
    Instant leaseUntil = now.plusSeconds(30);
    IssueWork claimed = issueWorkRepository.claimNext(now, leaseToken, leaseUntil);
    assertThat(claimed).isNotNull();
    assertThat(claimed.getIssueId()).isEqualTo(issueId);
    assertThat(claimed.getWakeVersion()).isEqualTo(2L);
    assertThat(claimed.getLeaseToken()).isEqualTo(leaseToken);

    // 4. Concurrently claiming while lease is active should return null
    IssueWork secondClaim = issueWorkRepository.claimNext(now, "other-token", leaseUntil);
    assertThat(secondClaim).isNull();

    // 5. Renew lease
    Instant newLeaseUntil = now.plusSeconds(60);
    assertThat(issueWorkRepository.renewLease(issueId, "wrong-token", now, newLeaseUntil))
        .isFalse();
    assertThat(issueWorkRepository.renewLease(issueId, leaseToken, now, newLeaseUntil)).isTrue();

    // 6. Request new work while lease is active -> wake version becomes 3
    issueWorkRepository.requestWork(issueId, now);

    // 7. deleteIfWakeMatches with claimed version 2 should fail because wake version is 3
    assertThat(issueWorkRepository.deleteIfWakeMatches(issueId, leaseToken, 2L, now)).isFalse();

    // 8. clearLeaseIfWakeNewer with claimed version 2 should succeed (3 > 2) and clear lease
    assertThat(issueWorkRepository.clearLeaseIfWakeNewer(issueId, leaseToken, 2L, now)).isTrue();
    IssueWork cleared = issueWorkRepository.getById(issueId);
    assertThat(cleared.getLeaseToken()).isNull();

    // 9. Now claimable again
    String leaseToken2 = "lease-token-2-" + UUID.randomUUID();
    IssueWork claimed2 = issueWorkRepository.claimNext(now, leaseToken2, leaseUntil);
    assertThat(claimed2).isNotNull();
    assertThat(claimed2.getWakeVersion()).isEqualTo(3L);

    // 10. Reschedule
    Instant futureDue = now.plusSeconds(120);
    assertThat(issueWorkRepository.reschedule(issueId, leaseToken2, 3L, now, futureDue)).isTrue();
    IssueWork rescheduled = issueWorkRepository.getById(issueId);
    assertThat(rescheduled.getLeaseToken()).isNull();

    // 11. Delete by issue id
    assertThat(issueWorkRepository.deleteByIssueId(issueId)).isEqualTo(1);
    assertThat(issueWorkRepository.getById(issueId)).isNull();
  }
}
