package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ReviewDecision;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 验证 IssueService 核心业务规则： 包含 Issue 编号单调分配、七态状态机跃迁（含 BLOCKED / RECOVER）、Activity 事实流幂等追加、
 * 审查打回窗口计数语义以及 isBlocked 投影。
 */
class IssueServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;
  @Autowired private IssueActivityRepository issueActivityRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueWorkStore workStore;

  @Test
  void testCreateIssueNumberAllocationAndWorkRequest() {
    // 测试意图：验证创建 Issue 时的状态前置约束、单调编号分配、初始 Activity 追加及 TODO 状态唤醒 Work。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Issue Test Project", "Desc", true, 3);
    UUID projectId = project.getId();

    // 初始状态只允许 BACKLOG 或 TODO
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.createIssue(
                projectId, "Invalid", "Desc", agentName, null, IssueStatus.IN_PROGRESS));

    // 执行者与审查者不能为同一 Agent
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.createIssue(
                projectId, "Invalid", "Desc", agentName, agentName, IssueStatus.TODO));

    // 创建 BACKLOG issue
    Issue issue1 =
        issueService.createIssue(
            projectId, "Issue 1", "Desc 1", agentName, null, IssueStatus.BACKLOG);
    assertEquals(1L, issue1.getNumber());
    assertEquals(IssueStatus.BACKLOG, issue1.getStatus());
    assertEquals(0L, issue1.getVersion());

    // 初始 SPEC_CHANGE activity 已落库
    List<IssueActivity> activities1 = issueService.listActivities(issue1.getId());
    assertEquals(1, activities1.size());
    assertEquals(IssueActivityKind.SPEC_CHANGE, activities1.get(0).getKind());

    // 创建 TODO issue，必须分配编号 2 并触发 WorkStore 请求
    Issue issue2 =
        issueService.createIssue(projectId, "Issue 2", "Desc 2", agentName, null, IssueStatus.TODO);
    assertEquals(2L, issue2.getNumber());
    assertEquals(IssueStatus.TODO, issue2.getStatus());
    assertNotNull(workStore.getWork(issue2.getId()), "TODO issue creation must request work");
  }

  @Test
  void testUpdateIssueAndCas() {
    // 测试意图：验证修改 Issue 要求/指派的 CAS 版本检查、Activity 事实流追加、可编辑状态限制与同名 Agent 拒绝。
    String agent1 = createTestAgent();
    String agent2 = createTestAgent();
    Project project = projectService.createProject("Update Test Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Orig", "Desc", agent1, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // CAS 版本冲突
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.updateIssue(issueId, 999L, "New", "Desc", agent1, null));

    // 同名 Agent 拒绝
    assertThrows(
        AiValidationException.class,
        () -> issueService.updateIssue(issueId, 0L, "New", "Desc", agent1, agent1));

    // 修改 spec 字段（title 改变）应当递增版本并追加 SPEC_CHANGE Activity
    Issue updated1 = issueService.updateIssue(issueId, 0L, "New Title", "Desc", agent1, null);
    assertEquals("New Title", updated1.getTitle());
    assertEquals(1L, updated1.getVersion());

    List<IssueActivity> activities = issueService.listActivities(issueId);
    assertEquals(2, activities.size());
    assertEquals(IssueActivityKind.SPEC_CHANGE, activities.get(1).getKind());

    // 无实质字段变化不产生新 Activity
    Issue updated2 = issueService.updateIssue(issueId, 1L, "New Title", "Desc", agent1, null);
    assertEquals(2L, updated2.getVersion());
    assertEquals(2, issueService.listActivities(issueId).size());

    // 修改分配人 agent2
    Issue updated3 = issueService.updateIssue(issueId, 2L, "New Title", "Desc", agent2, null);
    assertEquals(3L, updated3.getVersion());
    assertEquals(3, issueService.listActivities(issueId).size());
  }

  @Test
  void testStatusTransitionsAndReopen() {
    // 测试意图：验证 BACKLOG/TODO 互相跃迁、非法直接跃迁拒绝、DONE 重开到 TODO 及 RECOVERY Activity 记录。
    String agent = createTestAgent();
    Project project = projectService.createProject("Status Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Title", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    // BACKLOG -> TODO
    Issue toTodo = issueService.setStatus(issueId, 0L, IssueStatus.TODO);
    assertEquals(IssueStatus.TODO, toTodo.getStatus());
    assertNotNull(workStore.getWork(issueId));

    // TODO -> BACKLOG
    Issue toBacklog = issueService.setStatus(issueId, 1L, IssueStatus.BACKLOG);
    assertEquals(IssueStatus.BACKLOG, toBacklog.getStatus());

    // 非法直接跃迁：BACKLOG -> DONE 或 BACKLOG -> IN_PROGRESS
    assertThrows(
        AiValidationException.class, () -> issueService.setStatus(issueId, 2L, IssueStatus.DONE));
    assertThrows(
        AiValidationException.class,
        () -> issueService.setStatus(issueId, 2L, IssueStatus.IN_PROGRESS));

    // 通过直接更新 DB 模拟 issue 达到终态 DONE
    Issue inDb = issueRepository.getById(issueId);
    inDb.setStatus(IssueStatus.DONE);
    issueRepository.updateById(inDb, inDb.getVersion());

    // Reopen 明确将 DONE -> TODO 并追加 RECOVERY Activity
    Issue reopened = issueService.setStatus(issueId, inDb.getVersion() + 1, IssueStatus.TODO);
    assertEquals(IssueStatus.TODO, reopened.getStatus());

    List<IssueActivity> activities = issueService.listActivities(issueId);
    boolean hasRecovery =
        activities.stream().anyMatch(a -> a.getKind() == IssueActivityKind.RECOVERY);
    assertTrue(hasRecovery);
  }

  @Test
  void testCancelIssue() {
    // 测试意图：验证 Issue 取消动作、终态再次取消拒绝以及唤醒工作。
    String agent = createTestAgent();
    Project project = projectService.createProject("Cancel Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 成功取消
    Issue cancelled = issueService.cancelIssue(issueId, 0L, "Cancelled by user");
    assertEquals(IssueStatus.CANCELED, cancelled.getStatus());
    assertNotNull(workStore.getWork(issueId));

    // 取消已经终态的 Issue 应抛出校验异常
    assertThrows(
        AiValidationException.class,
        () -> issueService.cancelIssue(issueId, cancelled.getVersion(), "Again"));
  }

  @Test
  void testRecoverAndBlockIssue() {
    // 测试意图：验证 blockIssue 显式阻塞、BLOCKED 状态不可归档、recoverIssue 分别恢复到 TODO 与 BACKLOG。
    String agent = createTestAgent();
    Project project = projectService.createProject("Block Recover Proj", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 显式阻塞
    Issue blocked = issueService.blockIssue(issueId, 0L, "Need human intervention");
    assertEquals(IssueStatus.BLOCKED, blocked.getStatus());
    assertFalse(blocked.canBeArchived());

    // BLOCKED 不能直接归档
    assertThrows(
        AiValidationException.class,
        () -> issueService.archiveIssue(issueId, blocked.getVersion()));

    // 人工恢复到 TODO（继续要求）
    Issue recoveredTodo =
        issueService.recoverIssue(issueId, blocked.getVersion(), false, "Requirements verified");
    assertEquals(IssueStatus.TODO, recoveredTodo.getStatus());
    assertNotNull(workStore.getWork(issueId));

    // 再次阻塞
    Issue blockedAgain =
        issueService.blockIssue(issueId, recoveredTodo.getVersion(), "Need redesign");
    assertEquals(IssueStatus.BLOCKED, blockedAgain.getStatus());

    // 人工恢复到 BACKLOG（先改要求）
    Issue recoveredBacklog =
        issueService.recoverIssue(issueId, blockedAgain.getVersion(), true, "Need spec rewrite");
    assertEquals(IssueStatus.BACKLOG, recoveredBacklog.getStatus());

    // 对非 BLOCKED Issue 执行 recover 抛异常
    assertThrows(
        AiValidationException.class,
        () -> issueService.recoverIssue(issueId, recoveredBacklog.getVersion(), false, "Fail"));
  }

  @Test
  void testArchiveAndUnarchiveIssue() {
    // 测试意图：验证非终态禁止归档、终态归档/解归档以及幂等性。
    String agent = createTestAgent();
    Project project = projectService.createProject("Archive Issue Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 非终态（TODO）Issue 禁止归档
    assertThrows(AiValidationException.class, () -> issueService.archiveIssue(issueId, 0L));

    // 转换为 CANCELED 终态后再归档
    issueService.cancelIssue(issueId, 0L, "reason");
    Issue archived = issueService.archiveIssue(issueId, 1L);
    assertTrue(archived.isArchived());
    assertNotNull(archived.getArchivedAt());

    // 重复归档直接返回（幂等）
    Issue reArchived = issueService.archiveIssue(issueId, 2L);
    assertTrue(reArchived.isArchived());

    // CAS 冲突测试
    assertThrows(AiVersionConflictException.class, () -> issueService.archiveIssue(issueId, 999L));

    // 解归档
    Issue unarchived = issueService.unarchiveIssue(issueId, 2L);
    assertFalse(unarchived.isArchived());
    assertNull(unarchived.getArchivedAt());

    // 重复解归档直接返回（幂等）
    Issue reUnarchived = issueService.unarchiveIssue(issueId, 3L);
    assertFalse(reUnarchived.isArchived());

    // CAS 冲突测试
    assertThrows(
        AiVersionConflictException.class, () -> issueService.unarchiveIssue(issueId, 999L));
  }

  @Test
  void testAppendActivityIdempotencyAndWakeRules() {
    // 测试意图：验证 Activity 事实流追加时的幂等键去重、COMMENT 不唤醒 Work 而 HUMAN_INPUT 唤醒 Work 规则。
    String agent = createTestAgent();
    Project project = projectService.createProject("Activity Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Title", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    // 追加 COMMENT（无目标角色）不应唤醒 Work
    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Comment text")
            .idempotencyKey("comment-1")
            .build());
    assertNull(workStore.getWork(issueId));

    // 追加 HUMAN_INPUT 必须唤醒 Work
    IssueActivity input1 =
        issueService.appendActivity(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.HUMAN_INPUT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("First input")
                .idempotencyKey("key-1")
                .build());
    assertEquals(3L, input1.getSequence()); // initial SPEC_CHANGE(1), COMMENT(2), HUMAN_INPUT(3)
    assertEquals("First input", input1.getBody());
    assertNotNull(workStore.getWork(issueId));

    // 相同幂等键再次追加，必须幂等返回既有记录
    IssueActivity input1Repeat =
        issueService.appendActivity(
            IssueActivity.builder()
                .issueId(issueId)
                .kind(IssueActivityKind.HUMAN_INPUT)
                .actorType(IssueActivityActorType.HUMAN)
                .body("First input repeat")
                .idempotencyKey("key-1")
                .build());
    assertEquals(3L, input1Repeat.getSequence());
    assertEquals("First input", input1Repeat.getBody());

    // 分页查询 listActivitiesPage
    List<IssueActivity> page = issueService.listActivitiesPage(issueId, 1L, 10);
    assertEquals(2, page.size());
    assertEquals(2L, page.get(0).getSequence());
    assertEquals(3L, page.get(1).getSequence());
  }

  @Test
  void testRejectionCountingWindowSemantics() {
    // 测试意图：验证只有当前区间内对不同 submissionRunId 作出的 REQUEST_CHANGES 计入打回数，
    // 且 RECOVERY 或 SPEC_CHANGE 会开启新的打回计数区间。
    String agent = createTestAgent();
    String reviewer = createTestAgent();
    Project project = projectService.createProject("Rejection Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Title", "Desc", agent, reviewer, IssueStatus.TODO);
    UUID issueId = issue.getId();

    assertEquals(0L, issueService.countRejections(issueId));

    IssueRun run1 =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agent)
            .status(IssueRunStatus.FAILED)
            .waitingReason("failed")
            .completedAt(Instant.now())
            .observedActivitySequence(1L)
            .continuationCount(0)
            .maxContinuations(10)
            .version(0L)
            .build();
    issueRunRepository.create(run1);

    IssueRun run2 =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(2L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agent)
            .status(IssueRunStatus.FAILED)
            .waitingReason("failed")
            .completedAt(Instant.now())
            .observedActivitySequence(1L)
            .continuationCount(0)
            .maxContinuations(10)
            .version(0L)
            .build();
    issueRunRepository.create(run2);

    // 第一次正式打回（提交 run1）
    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.AGENT)
            .actorAgentName(reviewer)
            .decision(ReviewDecision.REQUEST_CHANGES)
            .submissionRunId(run1.getId())
            .body("Reject run 1")
            .idempotencyKey("rej-1")
            .build());
    assertEquals(1L, issueService.countRejections(issueId));

    // 重复同一提交 run1 的打回不重复计入打回次数
    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.HUMAN)
            .decision(ReviewDecision.REQUEST_CHANGES)
            .submissionRunId(run1.getId())
            .body("Duplicate reject run 1")
            .idempotencyKey("rej-1-dup")
            .build());
    assertEquals(1L, issueService.countRejections(issueId));

    // 第二次正式打回（不同提交 run2）
    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.AGENT)
            .actorAgentName(reviewer)
            .decision(ReviewDecision.REQUEST_CHANGES)
            .submissionRunId(run2.getId())
            .body("Reject run 2")
            .idempotencyKey("rej-2")
            .build());
    assertEquals(2L, issueService.countRejections(issueId));

    // 人工阻塞并恢复开启新区间
    issueService.blockIssue(issueId, 0L, "Threshold reached");
    issueService.recoverIssue(issueId, 1L, false, "Recovered by human");

    // 恢复后打回计数重置为 0
    assertEquals(0L, issueService.countRejections(issueId));

    // 新区间内的打回
    IssueRun run3 =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(3L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agent)
            .status(IssueRunStatus.FAILED)
            .waitingReason("failed")
            .completedAt(Instant.now())
            .observedActivitySequence(1L)
            .continuationCount(0)
            .maxContinuations(10)
            .version(0L)
            .build();
    issueRunRepository.create(run3);

    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.REVIEW_DECISION)
            .actorType(IssueActivityActorType.AGENT)
            .actorAgentName(reviewer)
            .decision(ReviewDecision.REQUEST_CHANGES)
            .submissionRunId(run3.getId())
            .body("Reject run 3")
            .idempotencyKey("rej-3")
            .build());
    assertEquals(1L, issueService.countRejections(issueId));
  }

  @Test
  void testIsBlockedProjection() {
    // 测试意图：验证同项目 Issue 依赖关系下的 isBlocked 业务投影：依赖项非 DONE（如 TODO 或 CANCELED）均为 blocked。
    String agent = createTestAgent();
    Project project = projectService.createProject("Blocked Project", "Desc", true, 3);
    UUID projectId = project.getId();

    Issue target =
        issueService.createIssue(projectId, "Target", "Desc", agent, null, IssueStatus.TODO);
    Issue depA =
        issueService.createIssue(projectId, "Dep A", "Desc", agent, null, IssueStatus.TODO);

    // 无依赖时为 false
    assertFalse(issueService.isBlocked(target.getId()));

    // 添加依赖：target 依赖 depA
    issueDependencyRepository.addDependency(
        IssueDependency.builder()
            .issueId(target.getId())
            .dependsOnIssueId(depA.getId())
            .projectId(projectId)
            .build());

    // depA 处于 TODO 状态，target 被阻塞
    assertTrue(issueService.isBlocked(target.getId()));

    // depA 变更为 DONE，target 解除阻塞
    depA.setStatus(IssueStatus.DONE);
    issueRepository.updateById(depA, depA.getVersion());
    assertFalse(issueService.isBlocked(target.getId()));

    // depA 变更为 CANCELED（非 DONE），依设计规则仍算未满足（被阻塞）
    depA.setStatus(IssueStatus.CANCELED);
    issueRepository.updateById(depA, depA.getVersion() + 1);
    assertTrue(issueService.isBlocked(target.getId()));
  }

  @Test
  void testCancelIssueWithActiveRunAndCasConflict() {
    // 测试意图：验证取消 Issue 时若存在活动 Run，活动 Run 会被连带标记为 CANCELLED。
    String agent = createTestAgent();
    Project project = projectService.createProject("Cancel Run Project", "Desc", true, 3);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 手动创建一条 active run
    IssueRun activeRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName(agent)
            .status(IssueRunStatus.RUNNING)
            .observedActivitySequence(0L)
            .continuationCount(0)
            .maxContinuations(10)
            .version(0L)
            .build();
    issueRunRepository.create(activeRun);

    // CAS 冲突测试
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.cancelIssue(issueId, 999L, "conflict"));

    // 取消 Issue，关联的 activeRun 应自动被标记为 CANCELLED
    Issue cancelled = issueService.cancelIssue(issueId, 0L, "User abort");
    assertEquals(IssueStatus.CANCELED, cancelled.getStatus());

    IssueRun cancelledRun = issueRunRepository.getById(activeRun.getId());
    assertEquals(IssueRunStatus.CANCELLED, cancelledRun.getStatus());
    assertEquals("User abort", cancelledRun.getWaitingReason());
    assertNotNull(cancelledRun.getCompletedAt());
  }

  @Test
  void testListAndGetIssuesByNumber() {
    // 测试意图：验证按项目与编号查询 Issue 及按归档状态过滤列表。
    String agent = createTestAgent();
    Project project = projectService.createProject("List Issue Project", "Desc", true, 3);
    UUID projectId = project.getId();

    Issue i1 = issueService.createIssue(projectId, "Issue A", "D", agent, null, IssueStatus.TODO);
    Issue i2 = issueService.createIssue(projectId, "Issue B", "D", agent, null, IssueStatus.TODO);
    issueService.cancelIssue(i2.getId(), 0L, "reason");
    issueService.archiveIssue(i2.getId(), 1L);

    // getIssueByProjectAndNumber
    Issue byNum = issueService.getIssueByProjectAndNumber(projectId, 1L);
    assertEquals(i1.getId(), byNum.getId());

    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.getIssueByProjectAndNumber(projectId, 999L));

    // listIssues
    List<Issue> activeOnly = issueService.listIssues(projectId, false);
    assertEquals(1, activeOnly.size());
    assertEquals(i1.getId(), activeOnly.get(0).getId());

    List<Issue> all = issueService.listIssues(projectId, true);
    assertEquals(2, all.size());
  }

  @Test
  void testRemainingValidationPaths() {
    // 测试意图：验证不存在 ID、已归档 Issue 修改拒绝等各业务边界的异常防御。
    String agent = createTestAgent();
    Project project = projectService.createProject("Remain Val Proj", "Desc", true, 3);
    UUID projectId = project.getId();

    // createIssue title blank
    assertThrows(
        AiValidationException.class,
        () -> issueService.createIssue(projectId, null, "Desc", agent, null, IssueStatus.TODO));

    // getIssue not found
    assertThrows(AiResourceNotFoundException.class, () -> issueService.getIssue(UUID.randomUUID()));

    // cancelIssue not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.cancelIssue(UUID.randomUUID(), 0L, "reason"));

    // archiveIssue / unarchiveIssue not found
    assertThrows(
        AiResourceNotFoundException.class, () -> issueService.archiveIssue(UUID.randomUUID(), 0L));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.unarchiveIssue(UUID.randomUUID(), 0L));

    // appendActivity not found
    assertThrows(
        AiResourceNotFoundException.class,
        () ->
            issueService.appendActivity(
                IssueActivity.builder()
                    .issueId(UUID.randomUUID())
                    .kind(IssueActivityKind.COMMENT)
                    .actorType(IssueActivityActorType.HUMAN)
                    .body("b")
                    .build()));

    // removeDependency target not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.removeDependency(UUID.randomUUID(), UUID.randomUUID(), 0L));

    // archived issue validations
    Issue issue = issueService.createIssue(projectId, "T", "D", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();
    issueService.cancelIssue(issueId, 0L, "cancel");
    issueService.archiveIssue(issueId, 1L);

    // updateIssue on archived issue
    assertThrows(
        AiValidationException.class,
        () -> issueService.updateIssue(issueId, 2L, "New", "D", agent, null));

    // setStatus on archived issue
    assertThrows(
        AiValidationException.class, () -> issueService.setStatus(issueId, 2L, IssueStatus.TODO));
  }
}
