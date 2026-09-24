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
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
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
 * 编号分配及深删除孤儿清理。
 */
class ProjectServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectService projectService;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private IssueService issueService;
  @Autowired private IssueRunService issueRunService;
  @Autowired private IssueWorkStore issueWorkStore;
  @Autowired private IssueAgentSessionRepository issueAgentSessionRepository;
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
                jdbcTemplate.update("delete from harness_thread where session_id = ?", sessionId);
                jdbcTemplate.update("delete from harness_entry where session_id = ?", sessionId);
                jdbcTemplate.update("delete from harness_session where id = ?", sessionId);
                return null;
              })
          .when(tx)
          .deleteSession(any());
    }
  }

  @Test
  void testCreateProjectSuccessAndValidation() {
    // 测试意图：验证创建项目时的字段校验边界、默认值分配以及成功落库后的完整字段一致性。
    assertThrows(
        AiValidationException.class, () -> projectService.createProject("  ", "desc", true, 3));

    // 默认 maxReviewRejections 传入非正数时自动回退为默认值 3
    Project project = projectService.createProject("Project Alpha", "Initial description", true, 0);
    assertNotNull(project.getId());
    assertEquals("Project Alpha", project.getTitle());
    assertEquals("Initial description", project.getDescription());
    assertTrue(project.isYoloEnabled());
    assertEquals(3, project.getMaxReviewRejections());
    assertEquals(1L, project.getNextIssueNumber());
    assertEquals(0L, project.getVersion());
    assertNull(project.getArchivedAt());
    assertFalse(project.isArchived());
    assertNotNull(project.getCreatedAt());
    assertNotNull(project.getUpdatedAt());
  }

  @Test
  void testUpdateProjectCasAndArchiveCheck() {
    // 测试意图：验证 CAS 乐观锁版本检查、字段更新（包含 YOLO 和阈值）、正整数约束及归档后禁止修改。
    Project project = projectService.createProject("Project Beta", "Desc", true, 3);
    UUID id = project.getId();

    // 期望版本不匹配时抛出版本冲突异常
    assertThrows(
        AiVersionConflictException.class,
        () -> projectService.updateProject(id, 999L, "New Title", "New Desc", false, 5));

    // maxReviewRejections 必须为正整数
    assertThrows(
        AiValidationException.class,
        () -> projectService.updateProject(id, 0L, "New Title", "New Desc", false, 0));

    // 正常 CAS 更新
    Project updated = projectService.updateProject(id, 0L, "New Title", "New Desc", false, 5);
    assertEquals("New Title", updated.getTitle());
    assertEquals("New Desc", updated.getDescription());
    assertFalse(updated.isYoloEnabled());
    assertEquals(5, updated.getMaxReviewRejections());
    assertEquals(1L, updated.getVersion());

    // 归档后禁止修改
    projectService.archiveProject(id, 1L);
    assertThrows(
        AiValidationException.class,
        () -> projectService.updateProject(id, 2L, "Should Fail", "Desc", true, 3));
  }

  @Test
  void testUpdateProjectYoloRejectedWhileProjectHasActiveRun() {
    // 测试意图：YOLO 是 Run 启动策略，工作 Branch 的实际授权是启动时的快照。只要同一 Project 下任一
    // Issue 仍有活动 Run（RUNNING / WAITING_HUMAN），修改 YOLO 必须整体拒绝且不留下任何已改字段，
    // 否则会出现“界面已关闭、工具仍按旧快照免审批”的假安全信号；同值 YOLO、其它字段与阈值更新不受
    // 约束，Run 进入终态后放行，其它 Project 的活动 Run 不影响本 Project。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Yolo Guard", "Desc", true, 3);
    UUID projectId = project.getId();

    Issue issueWithRun =
        issueService.createIssue(projectId, "Issue A", "Desc", agentName, null, IssueStatus.TODO);
    Issue issueWithoutRun =
        issueService.createIssue(projectId, "Issue B", "Desc", agentName, null, IssueStatus.TODO);

    IssueRun run =
        issueRunService.startExecutorRun(
            issueWithRun.getId(), agentName, Instant.now().plusSeconds(60), 5);
    assertEquals(IssueRunStatus.RUNNING, run.getStatus());

    // RUNNING 时拒绝修改 YOLO，同一请求中的其它字段一并拒绝：版本与全部字段保持原样
    AiValidationException runningRejected =
        assertThrows(
            AiValidationException.class,
            () -> projectService.updateProject(projectId, 0L, "Renamed", "New Desc", false, 5));
    assertTrue(runningRejected.getMessage().contains("yoloEnabled"));
    Project afterRunningReject = projectService.getProject(projectId);
    assertTrue(afterRunningReject.isYoloEnabled());
    assertEquals("Yolo Guard", afterRunningReject.getTitle());
    assertEquals("Desc", afterRunningReject.getDescription());
    assertEquals(3, afterRunningReject.getMaxReviewRejections());
    assertEquals(0L, afterRunningReject.getVersion());

    // 同值 YOLO 与其它字段更新正常生效；活动 Run 按 Project 判定，与它在哪个 Issue 上无关
    Project sameValueUpdate =
        projectService.updateProject(projectId, 0L, "Yolo Guard Renamed", "New Desc", true, 5);
    assertTrue(sameValueUpdate.isYoloEnabled());
    assertEquals("Yolo Guard Renamed", sameValueUpdate.getTitle());
    assertEquals("New Desc", sameValueUpdate.getDescription());
    assertEquals(5, sameValueUpdate.getMaxReviewRejections());
    assertEquals(1L, sameValueUpdate.getVersion());
    assertNotNull(issueService.getIssue(issueWithoutRun.getId()));

    // WAITING_HUMAN 的运行同样算活动 Run：等待人工回答期间不能先关掉 YOLO
    IssueRun waiting = issueRunService.requestInput(run.getId(), "Need clarification", null);
    assertEquals(IssueRunStatus.WAITING_HUMAN, waiting.getStatus());
    assertThrows(
        AiValidationException.class,
        () -> projectService.updateProject(projectId, 1L, null, null, false, null));

    // Run 进入终态后放行
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "stopped by human");
    Project toggled = projectService.updateProject(projectId, 1L, null, null, false, null);
    assertFalse(toggled.isYoloEnabled());
    assertEquals(2L, toggled.getVersion());

    // 其它 Project 的活动 Run 不阻塞本 Project 的 YOLO 修改
    String otherAgentName = createTestAgent();
    Project otherProject = projectService.createProject("Other Project", "Desc", true, 3);
    Issue otherIssue =
        issueService.createIssue(
            otherProject.getId(), "Other Issue", "Desc", otherAgentName, null, IssueStatus.TODO);
    issueRunService.startExecutorRun(
        otherIssue.getId(), otherAgentName, Instant.now().plusSeconds(60), 5);
    Project toggledAgain = projectService.updateProject(projectId, 2L, null, null, true, null);
    assertTrue(toggledAgain.isYoloEnabled());
    assertTrue(projectService.getProject(otherProject.getId()).isYoloEnabled());
  }

  @Test
  void testArchiveAndUnarchiveProject() {
    // 测试意图：验证项目的归档（标记 archivedAt 并递增版本）、幂等重复归档/解归档及 CAS 版本防并发。
    Project project = projectService.createProject("Project Gamma", "Desc", true, 3);
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
    // 测试意图：验证根据 ID 查询项目、按归档状态过滤列表及不存在 ID 返回 404 错误。
    Project p1 = projectService.createProject("Active 1", "D1", true, 3);
    Project p2 = projectService.createProject("Active 2", "D2", false, 3);
    Project p3 = projectService.createProject("Archived 1", "D3", true, 3);
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
    // 测试意图：验证高并发下 allocateNextIssueNumber 能严格单调、唯一地分配 Issue 编号，绝不出现重号。
    Project project = projectService.createProject("Project Number Allocator", "Desc", true, 3);
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
              } catch (Exception ignored) {
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
  void testProjectValidationBoundariesAndErrors() {
    // 测试意图：验证超长描述、超长标题、超长空白内容以及不存在实体的防御性拒绝。
    // Description > 65536 bytes
    String oversizedDesc = "d".repeat(65537);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject("Title", oversizedDesc, true, 3));

    // Title > 255 chars
    String oversizedTitle = "t".repeat(256);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject(oversizedTitle, "desc", true, 3));

    // Optional oversized blank description (e.g. 70000 spaces) 前置拒绝且不落库
    String oversizedBlankDesc = " ".repeat(70000);
    assertThrows(
        AiValidationException.class,
        () -> projectService.createProject("Valid Title", oversizedBlankDesc, true, 3));

    // Not found errors
    UUID nonExistent = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class,
        () -> projectService.updateProject(nonExistent, 0L, "T", "D", true, 3));
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.archiveProject(nonExistent, 0L));
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.unarchiveProject(nonExistent, 0L));
  }

  @Test
  void testRepositoryDirectOperations() {
    // 测试意图：验证底层仓储行级锁 lockById 与基于版本号的物理 deleteById 行为。
    Project project = projectService.createProject("Repo Test", "Desc", true, 3);
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
    // 测试意图：验证删除不存在的项目抛 404、负数版本拒绝、CAS 冲突拒绝且保证数据完整性。
    Project project = projectService.createProject("Delete CAS Test", "Desc", true, 3);
    UUID projectId = project.getId();

    UUID notFoundId = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class, () -> projectService.deleteProject(notFoundId, 0L));

    assertThrows(AiValidationException.class, () -> projectService.deleteProject(projectId, -1L));

    assertThrows(
        AiVersionConflictException.class, () -> projectService.deleteProject(projectId, 100L));
    assertNotNull(projectService.getProject(projectId));
  }

  @Test
  void testDeleteProjectRefusesActiveOrUnknownRuns() {
    // 测试意图：存在活动中（RUNNING）或状态不确定的（UNKNOWN）Issue Run 时明确拒绝删除 Project。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Active Run Delete Guard", "Desc", true, 3);
    UUID projectId = project.getId();

    Issue issue =
        issueService.createIssue(
            projectId, "Issue with run", "Desc", agentName, null, IssueStatus.TODO);

    // 启动一个 RUNNING 的 run
    IssueRun activeRun =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(60), 5);
    assertEquals(IssueRunStatus.RUNNING, activeRun.getStatus());

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> projectService.deleteProject(projectId, 0L));
    assertTrue(ex.getMessage().contains("Cannot delete project with active or unknown runs"));
    assertNotNull(projectService.getProject(projectId));

    // 将 run 设为 UNKNOWN
    issueRunService.failRun(activeRun.getId(), IssueRunStatus.UNKNOWN, "uncertain network failure");

    AiValidationException exUnknown =
        assertThrows(
            AiValidationException.class, () -> projectService.deleteProject(projectId, 0L));
    assertTrue(
        exUnknown.getMessage().contains("Cannot delete project with active or unknown runs"));
    assertNotNull(projectService.getProject(projectId));
  }

  @Test
  void testDeleteProjectFullSuccessCleansAllOrphans() {
    // 测试意图：验证深删除完整成功，按设计顺序删除各表记录并清理关联 Session，不留任何孤儿数据。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Full Deep Delete Proj", "Desc", true, 3);
    UUID projectId = project.getId();

    // 创建两个 Issue 并建立依赖关系
    Issue issue1 =
        issueService.createIssue(projectId, "Issue 1", "Desc", agentName, null, IssueStatus.TODO);
    Issue issue2 =
        issueService.createIssue(projectId, "Issue 2", "Desc", agentName, null, IssueStatus.TODO);
    issueService.addDependency(issue2.getId(), issue1.getId(), issue2.getVersion());

    // 建立 IssueAgentSession 并绑定 session_owner
    UUID sessionId = createHarnessSession();
    UUID threadId = createHarnessThread(sessionId);
    IssueAgentSession agentSession =
        issueAgentSessionRepository.bindOrGet(
            IssueAgentSession.builder()
                .id(UUID.randomUUID())
                .issueId(issue1.getId())
                .agentName(agentName)
                .sessionId(sessionId)
                .threadId(threadId)
                .build());
    createSessionOwnerForIssueAgentSession(sessionId, agentSession.getId());

    // 追加 issue activity
    issueService.appendActivity(
        IssueActivity.builder()
            .issueId(issue1.getId())
            .kind(IssueActivityKind.HUMAN_INPUT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("test input")
            .idempotencyKey("key-1")
            .build());

    // 请求 issue work
    issueWorkStore.requestWork(issue1.getId(), Instant.now());

    // 启动 run 并将其终态置为 FAILED
    IssueRun run =
        issueRunService.startExecutorRun(
            issue1.getId(), agentName, Instant.now().plusSeconds(60), 5);
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "intentional failure");

    // 执行深删除
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
            "select count(*) from project_issue where project_id = ?", Integer.class, projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project_issue_dependency where project_id = ?",
            Integer.class,
            projectId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project_issue_activity where issue_id = ?",
            Integer.class,
            issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project_issue_run where issue_id = ?",
            Integer.class,
            issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project_issue_agent_session where issue_id = ?",
            Integer.class,
            issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from project_issue_work where issue_id = ?",
            Integer.class,
            issue1.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from session_owner where issue_agent_session_id = ?",
            Integer.class,
            agentSession.getId()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, sessionId));
  }

  @Test
  void testUpdateProjectYoloRejectedWhileHarnessInvocationInFlight() {
    // 测试意图：YOLO 是 Issue Agent Branch 的工作策略快照。IssueRun 进入终态后 Thread 上仍可能残留未收尾的
    // 模型/工具调用并继续产生外部副作用，此时必须与活动 Run 一样拒绝切换 YOLO，且拒绝时不落地任何字段；
    // 只有该 Project 的 Branch 调用全部收尾后才放行，其它 Project 的在途调用不影响本 Project。
    String agentName = createTestAgent();
    Project project = projectService.createProject("Harness Guard", "Desc", true, 3);
    UUID projectId = project.getId();
    Issue issue =
        issueService.createIssue(projectId, "Issue A", "Desc", agentName, null, IssueStatus.TODO);

    // IssueRun 已终态：Run 层面不再阻塞 YOLO 修改
    IssueRun run =
        issueRunService.startExecutorRun(
            issue.getId(), agentName, Instant.now().plusSeconds(60), 5);
    issueRunService.failRun(run.getId(), IssueRunStatus.FAILED, "stopped by human");

    UUID sessionId = createHarnessSession();
    UUID threadId = createHarnessThread(sessionId);
    UUID rootEntryId = threadHeadEntryId(threadId);
    issueAgentSessionRepository.bindOrGet(
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(issue.getId())
            .agentName(agentName)
            .sessionId(sessionId)
            .threadId(threadId)
            .build());

    // 未收尾 ModelInvocation（RUNNING）拒绝切换，且同请求的其它字段与版本保持原样
    UUID runningModelInvocationId = insertModelInvocation(threadId, rootEntryId, "RUNNING");
    AiValidationException modelRejected =
        assertThrows(
            AiValidationException.class,
            () -> projectService.updateProject(projectId, 0L, "Renamed", "New Desc", false, 5));
    assertTrue(modelRejected.getMessage().contains("yoloEnabled"));
    Project afterModelReject = projectService.getProject(projectId);
    assertTrue(afterModelReject.isYoloEnabled());
    assertEquals("Harness Guard", afterModelReject.getTitle());
    assertEquals(0L, afterModelReject.getVersion());

    // 模型调用收尾后放行
    updateModelInvocationStatus(runningModelInvocationId, "SUCCEEDED");
    Project afterModelTerminal =
        projectService.updateProject(projectId, 0L, "Renamed", "New Desc", false, 5);
    assertFalse(afterModelTerminal.isYoloEnabled());

    // 所属 ModelInvocation 已终态、但 ToolInvocation 仍未收尾（WAITING_APPROVAL）同样拒绝
    UUID turnStartEntryId = insertTurnStartEntry(sessionId, rootEntryId);
    UUID terminalModelInvocationId = insertModelInvocation(threadId, turnStartEntryId, "SUCCEEDED");
    UUID waitingToolInvocationId =
        insertToolInvocation(terminalModelInvocationId, rootEntryId, "WAITING_APPROVAL");
    assertThrows(
        AiValidationException.class,
        () ->
            projectService.updateProject(
                projectId, afterModelTerminal.getVersion(), null, null, true, null));
    assertFalse(projectService.getProject(projectId).isYoloEnabled());

    // 工具调用收尾后放行
    updateToolInvocationStatus(waitingToolInvocationId, "UNKNOWN");
    Project afterToolTerminal =
        projectService.updateProject(
            projectId, afterModelTerminal.getVersion(), null, null, true, null);
    assertTrue(afterToolTerminal.isYoloEnabled());

    // 其它 Project 的在途调用不阻塞本 Project
    String otherAgentName = createTestAgent();
    Project otherProject = projectService.createProject("Other Harness Project", "Desc", true, 3);
    Issue otherIssue =
        issueService.createIssue(
            otherProject.getId(), "Other Issue", "Desc", otherAgentName, null, IssueStatus.TODO);
    IssueRun otherRun =
        issueRunService.startExecutorRun(
            otherIssue.getId(), otherAgentName, Instant.now().plusSeconds(60), 5);
    issueRunService.failRun(otherRun.getId(), IssueRunStatus.FAILED, "stopped by human");
    UUID otherSessionId = createHarnessSession();
    UUID otherThreadId = createHarnessThread(otherSessionId);
    issueAgentSessionRepository.bindOrGet(
        IssueAgentSession.builder()
            .id(UUID.randomUUID())
            .issueId(otherIssue.getId())
            .agentName(otherAgentName)
            .sessionId(otherSessionId)
            .threadId(otherThreadId)
            .build());
    insertModelInvocation(otherThreadId, threadHeadEntryId(otherThreadId), "DISPATCHING");

    Project unaffected =
        projectService.updateProject(
            projectId, afterToolTerminal.getVersion(), null, null, false, null);
    assertFalse(unaffected.isYoloEnabled());
  }

  private UUID threadHeadEntryId(UUID threadId) {
    return jdbcTemplate.queryForObject(
        "select head_entry_id from harness_thread where id = ?", UUID.class, threadId);
  }

  private UUID insertTurnStartEntry(UUID sessionId, UUID parentEntryId) {
    UUID entryId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'TURN_START', '{}'::jsonb, current_timestamp)",
        entryId,
        sessionId,
        parentEntryId);
    return entryId;
  }

  private UUID insertModelInvocation(UUID threadId, UUID turnStartEntryId, String status) {
    UUID invocationId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_model_invocation (id, thread_id, turn_start_entry_id,"
            + " request_head_entry_id, request_spec, status, attempt, failed_attempts, created_at,"
            + " updated_at) values (?, ?, ?, ?, '{}'::jsonb, ?, 0, '[]'::jsonb, current_timestamp,"
            + " current_timestamp)",
        invocationId,
        threadId,
        turnStartEntryId,
        turnStartEntryId,
        status);
    return invocationId;
  }

  private void updateModelInvocationStatus(UUID invocationId, String status) {
    jdbcTemplate.update(
        "update harness_model_invocation set status = ?, updated_at = current_timestamp"
            + " where id = ?",
        status,
        invocationId);
  }

  private UUID insertToolInvocation(UUID modelInvocationId, UUID assistantEntryId, String status) {
    UUID invocationId = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id,"
            + " call_index, call, status, attempt, effects, created_at, updated_at) values"
            + " (?, ?, ?, 0, '{}'::jsonb, ?, 0, '{\"version\": 1, \"customEntries\": []}'::jsonb,"
            + " current_timestamp, current_timestamp)",
        invocationId,
        modelInvocationId,
        assistantEntryId,
        status);
    return invocationId;
  }

  private void updateToolInvocationStatus(UUID invocationId, String status) {
    jdbcTemplate.update(
        "update harness_tool_invocation set status = ?, updated_at = current_timestamp where id = ?",
        status,
        invocationId);
  }
}
