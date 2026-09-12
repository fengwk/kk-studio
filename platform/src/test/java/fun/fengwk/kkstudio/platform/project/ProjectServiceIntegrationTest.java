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
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 验证 ProjectService 及底层 PostgreSQL ProjectRepository 的核心业务行为： 包含项目创建、CAS 乐观锁更新、归档/解归档、并发单调 Issue
 * 编号分配及 Coordinator Session 绑定。
 */
class ProjectServiceIntegrationTest extends ProjectTestSupport {

  @Autowired private ProjectService projectService;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private ProjectSessionRepository projectSessionRepository;

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

    // 绑定 Coordinator Session
    projectService.bindCoordinatorSession(projectId, sessionId);

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

    // 不存在的项目绑定 coordinator 会报错
    UUID nonExistent = UUID.randomUUID();
    assertThrows(
        AiResourceNotFoundException.class,
        () -> projectService.bindCoordinatorSession(nonExistent, sessionId));
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
}
