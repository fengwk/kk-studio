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
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.List;
import java.util.UUID;

/**
 * 验证 IssueService 核心业务规则： 包含 Issue 编号单调分配、specRevision/inputSequence 变更边界、状态机合法/非法跃迁、输入幂等流及
 * isBlocked 投影。
 */
class IssueServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueService issueService;
  @Autowired private ProjectService projectService;
  @Autowired private IssueRepository issueRepository;
  @Autowired private IssueDependencyRepository issueDependencyRepository;
  @Autowired private IssueInputRepository issueInputRepository;
  @Autowired private IssueRunRepository issueRunRepository;
  @Autowired private IssueControllerWorkStore controllerWorkStore;

  @Test
  void testCreateIssueNumberAllocationAndWorkRequest() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Issue Test Project", "Desc", agentName);
    UUID projectId = project.getId();

    // 初始状态只允许 BACKLOG 或 TODO
    assertThrows(
        AiValidationException.class,
        () ->
            issueService.createIssue(
                projectId, "Invalid", "Desc", agentName, null, IssueStatus.IN_PROGRESS));

    // 创建 BACKLOG issue
    Issue issue1 =
        issueService.createIssue(
            projectId, "Issue 1", "Desc 1", agentName, null, IssueStatus.BACKLOG);
    assertEquals(1L, issue1.getNumber());
    assertEquals(IssueStatus.BACKLOG, issue1.getStatus());
    assertEquals(1L, issue1.getSpecRevision());
    assertEquals(0L, issue1.getInputSequence());

    // 创建 TODO issue，必须分配编号 2 并触发 ControllerWork 请求
    Issue issue2 =
        issueService.createIssue(projectId, "Issue 2", "Desc 2", agentName, null, IssueStatus.TODO);
    assertEquals(2L, issue2.getNumber());
    assertEquals(IssueStatus.TODO, issue2.getStatus());
    assertNotNull(
        controllerWorkStore.getWork(issue2.getId()),
        "TODO issue creation must request controller work");
  }

  @Test
  void testUpdateIssueSpecRevisionAndCas() {
    String agent1 = createTestAgent();
    String agent2 = createTestAgent();
    Project project = projectService.createProject("Update Test Project", "Desc", agent1);
    Issue issue =
        issueService.createIssue(project.getId(), "Orig", "Desc", agent1, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // CAS 版本冲突
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.updateIssue(issueId, 999L, "New", "Desc", agent1, null));

    // 修改 spec 字段（title 改变）应当递增 specRevision
    Issue updated1 = issueService.updateIssue(issueId, 0L, "New Title", "Desc", agent1, null);
    assertEquals("New Title", updated1.getTitle());
    assertEquals(2L, updated1.getSpecRevision());
    assertEquals(1L, updated1.getVersion());

    // 无实质字段变化不递增 specRevision
    Issue updated2 = issueService.updateIssue(issueId, 1L, "New Title", "Desc", agent1, null);
    assertEquals(2L, updated2.getSpecRevision());
    assertEquals(2L, updated2.getVersion());

    // 修改分配人 agent2 应当递增 specRevision
    Issue updated3 = issueService.updateIssue(issueId, 2L, "New Title", "Desc", agent2, null);
    assertEquals(3L, updated3.getSpecRevision());
    assertEquals(3L, updated3.getVersion());
  }

  @Test
  void testStatusTransitionsAndReopen() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Status Project", "Desc", agent);
    Issue issue =
        issueService.createIssue(
            project.getId(), "Title", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID issueId = issue.getId();

    // BACKLOG -> TODO
    Issue toTodo = issueService.setStatus(issueId, 0L, IssueStatus.TODO);
    assertEquals(IssueStatus.TODO, toTodo.getStatus());
    assertNotNull(controllerWorkStore.getWork(issueId));

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

    // Reopen 明确将 DONE -> TODO 并递增 specRevision
    long prevSpecRev = inDb.getSpecRevision();
    Issue reopened = issueService.setStatus(issueId, inDb.getVersion() + 1, IssueStatus.TODO);
    assertEquals(IssueStatus.TODO, reopened.getStatus());
    assertEquals(prevSpecRev + 1, reopened.getSpecRevision());
  }

  @Test
  void testCancelIssue() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Cancel Project", "Desc", agent);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 成功取消
    Issue cancelled = issueService.cancelIssue(issueId, 0L, "Cancelled by user");
    assertEquals(IssueStatus.CANCELED, cancelled.getStatus());
    assertNotNull(controllerWorkStore.getWork(issueId));

    // 取消已经终态的 Issue 应抛出校验异常
    assertThrows(
        AiValidationException.class,
        () -> issueService.cancelIssue(issueId, cancelled.getVersion(), "Again"));
  }

  @Test
  void testArchiveAndUnarchiveIssue() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Archive Issue Project", "Desc", agent);
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
  void testAppendInputSequenceAndIdempotency() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Input Project", "Desc", agent);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // 空 body 校验异常
    assertThrows(
        AiValidationException.class,
        () -> issueService.appendInput(issueId, IssueInputKind.HUMAN, "  ", "key-1"));

    // 首次追加输入，序号递增为 1
    IssueInput input1 =
        issueService.appendInput(issueId, IssueInputKind.HUMAN, "First input", "key-1");
    assertEquals(1L, input1.getSequence());
    assertEquals("First input", input1.getBody());
    assertEquals("key-1", input1.getIdempotencyKey());

    // 相同幂等键再次追加，必须幂等返回 input1，且序号不增加
    IssueInput input1Repeat =
        issueService.appendInput(issueId, IssueInputKind.HUMAN, "First input repeat", "key-1");
    assertEquals(1L, input1Repeat.getSequence());
    assertEquals("First input", input1Repeat.getBody());

    // 新幂等键输入，序号递增为 2
    IssueInput input2 =
        issueService.appendInput(issueId, IssueInputKind.HUMAN, "Second input", "key-2");
    assertEquals(2L, input2.getSequence());

    // 校验 issue 表上的 input_sequence 游标已递增为 2
    Issue refreshed = issueService.getIssue(issueId);
    assertEquals(2L, refreshed.getInputSequence());

    List<IssueInput> inputs = issueService.listInputs(issueId);
    assertEquals(2, inputs.size());
  }

  @Test
  void testIsBlockedProjection() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Blocked Project", "Desc", agent);
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

    // depA 变更为 CANCELED（非 DONE），依 RFC 3.3 规则仍算未满足（被阻塞）
    depA.setStatus(IssueStatus.CANCELED);
    issueRepository.updateById(depA, depA.getVersion() + 1);
    assertTrue(issueService.isBlocked(target.getId()));
  }

  @Test
  void testCancelIssueWithActiveRunAndCasConflict() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Cancel Run Project", "Desc", agent);
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
            .actorType(IssueRunActorType.AGENT)
            .agentName(agent)
            .status(IssueRunStatus.RUNNING)
            .observedSpecRevision(1L)
            .observedInputSequence(0L)
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
    String agent = createTestAgent();
    Project project = projectService.createProject("List Issue Project", "Desc", agent);
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
  void testCreateAndUpdateIssueValidationsAndCas() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Validation Project", "Desc", agent);
    UUID projectId = project.getId();

    // createIssue validations
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.createIssue(UUID.randomUUID(), "T", "D", agent, null, IssueStatus.TODO));

    projectService.archiveProject(projectId, 0L);
    assertThrows(
        AiValidationException.class,
        () -> issueService.createIssue(projectId, "T", "D", agent, null, IssueStatus.TODO));
    projectService.unarchiveProject(projectId, 1L);

    Issue issue =
        issueService.createIssue(projectId, "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    // updateIssue title blank
    assertThrows(
        AiValidationException.class,
        () -> issueService.updateIssue(issueId, 0L, "  ", "Desc", agent, null));

    // updateIssue not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.updateIssue(UUID.randomUUID(), 0L, "T", "Desc", agent, null));

    // updateIssue in terminal status
    issueService.cancelIssue(issueId, 0L, "cancel");
    assertThrows(
        AiValidationException.class,
        () -> issueService.updateIssue(issueId, 1L, "T", "Desc", agent, null));

    // setStatus same status returns current
    Issue canceledIssue = issueService.getIssue(issueId);
    Issue sameStatus =
        issueService.setStatus(issueId, canceledIssue.getVersion(), IssueStatus.CANCELED);
    assertEquals(canceledIssue.getVersion(), sameStatus.getVersion());

    // setStatus not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.setStatus(UUID.randomUUID(), 0L, IssueStatus.TODO));

    // setStatus version conflict
    assertThrows(
        AiVersionConflictException.class,
        () -> issueService.setStatus(issueId, 999L, IssueStatus.TODO));
  }

  @Test
  void testIssueRepositoryDirectAndListAfterSequence() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Input Seq Project", "Desc", agent);
    Issue issue =
        issueService.createIssue(project.getId(), "Title", "Desc", agent, null, IssueStatus.TODO);
    UUID issueId = issue.getId();

    issueService.appendInput(issueId, IssueInputKind.HUMAN, "Input 1", "k1");
    issueService.appendInput(issueId, IssueInputKind.HUMAN, "Input 2", "k2");
    issueService.appendInput(issueId, IssueInputKind.HUMAN, "Input 3", "k3");

    List<IssueInput> afterSeq1 = issueInputRepository.listAfterSequence(issueId, 1L);
    assertEquals(2, afterSeq1.size());
    assertEquals(2L, afterSeq1.get(0).getSequence());
    assertEquals(3L, afterSeq1.get(1).getSequence());

    // 使用不挂接 input 且无 controller_work 的独立 BACKLOG issue 测试 repository deleteById
    Issue cleanIssue =
        issueService.createIssue(
            project.getId(), "Clean", "Desc", agent, null, IssueStatus.BACKLOG);
    UUID cleanId = cleanIssue.getId();
    assertFalse(issueRepository.deleteById(cleanId, 999L));
    assertTrue(issueRepository.deleteById(cleanId, cleanIssue.getVersion()));
    assertNull(issueRepository.getById(cleanId));
  }

  @Test
  void testRemainingValidationPaths() {
    String agent = createTestAgent();
    Project project = projectService.createProject("Remain Val Proj", "Desc", agent);
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

    // appendInput not found
    assertThrows(
        AiResourceNotFoundException.class,
        () -> issueService.appendInput(UUID.randomUUID(), IssueInputKind.HUMAN, "b", "k"));

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
