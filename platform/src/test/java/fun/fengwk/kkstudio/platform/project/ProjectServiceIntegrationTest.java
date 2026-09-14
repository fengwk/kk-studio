package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueControllerWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 验证 ProjectService 及底层 PostgreSQL ProjectRepository 的核心业务行为： 包含项目创建、CAS 乐观锁更新、归档/解归档、并发单调 Issue
 * 编号分配及 Coordinator Session 绑定。
 */
class ProjectServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectService projectService;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private ProjectSessionRepository projectSessionRepository;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueControllerWorkRepository issueControllerWorkRepository;
  @MockitoBean private HarnessStore harnessStore;

  @BeforeEach
  void setUpHarnessStore() {
    if (harnessStore != null) {
      HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
      when(harnessStore.transaction(any()))
          .thenAnswer(
              inv -> {
                Function<HarnessStore.Transaction, ?> fn = inv.getArgument(0);
                return fn.apply(tx);
              });
      when(tx.lockSessionForUpdate(any()))
          .thenAnswer(
              inv -> {
                UUID sessionId = inv.getArgument(0);
                Integer count =
                    jdbcTemplate.queryForObject(
                        "select count(*) from harness_session where id = ?",
                        Integer.class,
                        sessionId);
                if (count != null && count > 0) {
                  Session s = new Session(sessionId, "mock", Instant.now());
                  return Optional.of(s);
                }
                return Optional.empty();
              });
      when(tx.listThreadsBySession(any())).thenReturn(List.of());
      doAnswer(
              inv -> {
                UUID sessionId = inv.getArgument(0);
                jdbcTemplate.update("delete from harness_session where id = ?", sessionId);
                return null;
              })
          .when(tx)
          .deleteSession(any());
    }
  }

  @Test
  void testCreateProjectSuccessAndValidation() {
    String agentName = createTestAgent();

    // 标题空白或 Coordinator 为空时应抛出校验异常
    assertThrows(
        AiValidationException.class, () -> projectService.createProject("  ", "desc", agentName));
    assertThrows(
        AiValidationException.class, () -> projectService.createProject("title", "desc", "  "));

    // 成功创建项目
    Project project =
        projectService.createProject("Project Alpha", "Initial description", agentName);
    assertNotNull(project.getId());
    assertEquals("Project Alpha", project.getTitle());
    assertEquals("Initial description", project.getDescription());
    assertEquals(agentName, project.getCoordinatorAgentName());
    assertEquals(1L, project.getNextIssueNumber());
    assertEquals(0L, project.getVersion());
    assertNull(project.getArchivedAt());
    assertFalse(project.isArchived());
    assertNotNull(project.getCreatedAt());
    assertNotNull(project.getUpdatedAt());
  }

  @Test
  void testUpdateProjectCasAndArchiveCheck() {
    String agentName1 = createTestAgent();
    String agentName2 = createTestAgent();
    Project project = projectService.createProject("Project Beta", "Desc", agentName1);
    UUID id = project.getId();

    // 期望版本不匹配时抛出版本冲突异常
    assertThrows(
        AiVersionConflictException.class,
        () -> projectService.updateProject(id, 999L, "New Title", "New Desc", agentName2));

    // 正常 CAS 更新
    Project updated = projectService.updateProject(id, 0L, "New Title", "New Desc", agentName2);
    assertEquals("New Title", updated.getTitle());
    assertEquals("New Desc", updated.getDescription());
    assertEquals(agentName2, updated.getCoordinatorAgentName());
    assertEquals(1L, updated.getVersion());

    // 归档后禁止修改
    projectService.archiveProject(id, 1L);
    assertThrows(
        AiValidationException.class,
        () -> projectService.updateProject(id, 2L, "Should Fail", "Desc", agentName2));
  }

  @Test
  void testArchiveAndUnarchiveProject() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Project Gamma", "Desc", agentName);
    UUID id = project.getId();

    // 首次归档设置 archivedAt 并递增版本
    Project archived = projectService.archiveProject(id, 0L);
    assertTrue(archived.isArchived());
    assertNotNull(archived.getArchivedAt());
    assertEquals(1L, archived.getVersion());

    // 重复归档直接返回当前对象（幂等）
    Project reArchived = projectService.archiveProject(id, 1L);
    assertTrue(reArchived.isArchived());

    // 解归档清除 archivedAt
    Project unarchived = projectService.unarchiveProject(id, 1L);
    assertFalse(unarchived.isArchived());
    assertNull(unarchived.getArchivedAt());
    assertEquals(2L, unarchived.getVersion());

    // 重复解归档直接返回当前对象
    Project reUnarchived = projectService.unarchiveProject(id, 2L);
    assertFalse(reUnarchived.isArchived());

    // CAS 冲突测试
    assertThrows(AiVersionConflictException.class, () -> projectService.archiveProject(id, 999L));
    projectService.archiveProject(id, 2L);
    assertThrows(AiVersionConflictException.class, () -> projectService.unarchiveProject(id, 999L));
  }

  @Test
  void testGetAndListProjects() {
    String agentName = createTestAgent();
    Project p1 = projectService.createProject("Active 1", "D1", agentName);
    Project p2 = projectService.createProject("Active 2", "D2", agentName);
    Project p3 = projectService.createProject("Archived 1", "D3", agentName);
    projectService.archiveProject(p3.getId(), 0L);

    // 不包含已归档
    List<Project> activeList = projectService.listProjects(false);
    Set<UUID> activeIds = activeList.stream().map(Project::getId).collect(Collectors.toSet());
    assertTrue(activeIds.contains(p1.getId()));
    assertTrue(activeIds.contains(p2.getId()));
    assertFalse(activeIds.contains(p3.getId()));

    // 包含已归档
    List<Project> allList = projectService.listProjects(true);
    Set<UUID> allIds = allList.stream().map(Project::getId).collect(Collectors.toSet());
    assertTrue(allIds.contains(p1.getId()));
    assertTrue(allIds.contains(p2.getId()));
    assertTrue(allIds.contains(p3.getId()));

    // 查不存在的项目抛出 404
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.getProject(UUID.randomUUID()));
  }

  @Test
  void testConcurrentAllocateNextIssueNumber() throws InterruptedException {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Project Number Allocator", "Desc", agentName);
    UUID projectId = project.getId();

    int threadCount = 10;
    Set<Long> allocatedNumbers = Collections.synchronizedSet(new HashSet<>());
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                startGate.await();
                long num = projectRepository.allocateNextIssueNumber(projectId);
                allocatedNumbers.add(num);
              } catch (Exception e) {
                // 记录异常
              } finally {
                doneGate.countDown();
              }
            });
      }

      startGate.countDown();
      assertTrue(doneGate.await(10, TimeUnit.SECONDS), "All threads must finish in time");
    } finally {
      executor.shutdownNow();
    }

    // 10 个并发线程必须分配出严格唯一的 1 到 10
    assertEquals(
        threadCount, allocatedNumbers.size(), "All allocated issue numbers must be distinct");
    for (long i = 1; i <= threadCount; i++) {
      assertTrue(allocatedNumbers.contains(i), "Must contain issue number " + i);
    }

    Project refreshed = projectService.getProject(projectId);
    assertEquals(threadCount + 1L, refreshed.getNextIssueNumber());
  }

  @Test
  void testCoordinatorSessionBindingAndLookup() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Project Session Test", "Desc", agentName);
    UUID projectId = project.getId();
    UUID sessionId = createHarnessSession();

    // 绑定 Coordinator Session（通过 repository 直接建立关系以测试服务查询）
    assertTrue(projectSessionRepository.bindSession(projectId, sessionId));

    // 双向查找
    ProjectSession byProj = projectService.getCoordinatorSession(projectId);
    assertNotNull(byProj);
    assertEquals(projectId, byProj.getProjectId());
    assertEquals(sessionId, byProj.getSessionId());

    ProjectSession bySession = projectService.findProjectSession(sessionId);
    assertNotNull(bySession);
    assertEquals(projectId, bySession.getProjectId());

    // 删除关联边
    assertTrue(projectSessionRepository.deleteByProjectId(projectId));
    assertNull(projectService.getCoordinatorSession(projectId));
  }

  @Test
  void testProjectValidationBoundariesAndErrors() {
    String agentName = createTestAgent();
    // Description > 65536 bytes
    String oversizedDesc = "d".repeat(65537);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject("Title", oversizedDesc, agentName));

    // Title > 255 chars
    String oversizedTitle = "t".repeat(256);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject(oversizedTitle, "desc", agentName));

    // Optional oversized blank description (e.g. 70000 spaces) 前置拒绝且不落库
    String oversizedBlankDesc = " ".repeat(70000);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject("Valid Title", oversizedBlankDesc, agentName));

    // Not found errors
    UUID nonExistent = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class,
        () -> projectService.updateProject(nonExistent, 0L, "T", "D", agentName));
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.archiveProject(nonExistent, 0L));
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.unarchiveProject(nonExistent, 0L));
  }

  @Test
  void testRepositoryDirectOperations() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Repo Test", "Desc", agentName);
    UUID id = project.getId();

    Project locked = projectRepository.lockById(id);
    assertNotNull(locked);
    assertEquals(id, locked.getId());

    assertFalse(projectRepository.deleteById(id, 999L));
    assertTrue(projectRepository.deleteById(id, 0L));
    assertNull(projectRepository.getById(id));
  }

  @Test
  void testDeleteProjectCasAndNotFound() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Delete CAS Test", "Desc", agentName);
    UUID projectId = project.getId();

    // 测试意图：验证删除不存在的 Project 抛出 AiResourceNotFoundException
    UUID notFoundId = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.deleteProject(notFoundId, 0L));

    // 测试意图：验证 expectedVersion 负数抛出校验异常
    assertThrows(AiValidationException.class, () -> projectService.deleteProject(projectId, -1L));

    // 测试意图：验证 expectedVersion CAS 冲突拒绝删除，且项目未被删除
    assertThrows(
        AiVersionConflictException.class, () -> projectService.deleteProject(projectId, 100L));
    assertNotNull(projectService.getProject(projectId));
  }

  @Test
  void testDeleteProjectRefusesActiveOrUnknownRuns() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Active Run Delete Guard", "Desc", agentName);
    UUID projectId = project.getId();

    Issue issue =
        issueService.createIssue(
            projectId, "Issue with run", "Desc", agentName, null, IssueStatus.TODO);

    // 启动一个 RUNNING 的 run
    IssueRun activeRun =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(60), 5);
    assertEquals(IssueRunStatus.RUNNING, activeRun.getStatus());

    // 测试意图：存在 RUNNING 状态的 run 时，必须明确拒绝删除，且无数据被孤立清理
    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> projectService.deleteProject(projectId, 0L));
    assertTrue(ex.getMessage().contains("Cannot delete project with active or unknown runs"));
    assertNotNull(projectService.getProject(projectId));

    // 将 run 设为 UNKNOWN
    issueRunService.failRun(activeRun.getId(), IssueRunStatus.UNKNOWN, "uncertain network failure");

    // 测试意图：存在 UNKNOWN 状态的 run 时，必须明确拒绝删除
    AiValidationException exUnknown =
        assertThrows(
            AiValidationException.class, () -> projectService.deleteProject(projectId, 0L));
    assertTrue(
        exUnknown.getMessage().contains("Cannot delete project with active or unknown runs"));
    assertNotNull(projectService.getProject(projectId));
  }

  @Test
  void testDeleteProjectFullSuccessCleansAllOrphans() {
    String agentName = createTestAgent();
    Project project = projectService.createProject("Full Deep Delete Proj", "Desc", agentName);
    UUID projectId = project.getId();

    // 绑定 Coordinator session
    UUID coordSessionId = createHarnessSession();
    projectSessionRepository.bindSession(projectId, coordSessionId);

    // 创建两个 Issue 并建立依赖关系
    Issue issue1 =
        issueService.createIssue(projectId, "Issue 1", "Desc", agentName, null, IssueStatus.TODO);
    Issue issue2 =
        issueService.createIssue(projectId, "Issue 2", "Desc", agentName, null, IssueStatus.TODO);
    issueService.addDependency(issue2.getId(), issue1.getId(), issue2.getVersion());

    // 追加 issue input
    issueService.appendInput(issue1.getId(), IssueInputKind.HUMAN, "test input", "key-1");

    // 创建 controller work
    issueControllerWorkRepository.requestWork(issue1.getId(), Instant.now());

    // 启动 run 并将其终态置为 FAILED
    IssueRun run =
        issueRunService.startExecutorRun(
            issue1.getId(), agentName, Instant.now().plusSeconds(60), 5);
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "intentional failure");

    // 测试意图：验证深删除完整成功，按设计顺序删除各表记录并清理 Harness Session，不留任何孤儿数据
    projectService.deleteProject(projectId, 0L);

    // 验证 Project 事实被删除
    assertThrows(AiResourceNotFoundException.class, () -> projectService.getProject(projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project where id = ?", Integer.class, projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from session_owner where project_id = ?", Integer.class, projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from issue where project_id = ?", Integer.class, projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from issue_dependency where project_id = ?",
            Integer.class,
            projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from issue_input where issue_id = ?", Integer.class, issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from issue_run where issue_id = ?", Integer.class, issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from issue_controller_work where issue_id = ?",
            Integer.class,
            issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, coordSessionId));
  }
}
