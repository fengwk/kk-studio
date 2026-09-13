package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueControllerWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.impl.ProjectServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 ProjectService 对持久层异常返回和数据库约束冲突的防御性处理。 */
class ProjectServiceDefensiveUnitTest {

  private ProjectServiceImpl createService(
      ProjectRepository projectRepository,
      ProjectSessionRepository sessionRepository,
      IssueRepository issueRepository,
      IssueDependencyRepository issueDependencyRepository,
      IssueInputRepository issueInputRepository,
      IssueRunRepository issueRunRepository,
      IssueControllerWorkRepository issueControllerWorkRepository,
      SessionDeletionOrchestrator sessionDeletionOrchestrator) {
    return new ProjectServiceImpl(
        projectRepository,
        sessionRepository,
        issueRepository,
        issueDependencyRepository,
        issueInputRepository,
        issueRunRepository,
        issueControllerWorkRepository,
        sessionDeletionOrchestrator);
  }

  private ProjectServiceImpl createService(
      ProjectRepository projectRepository, ProjectSessionRepository sessionRepository) {
    return createService(
        projectRepository,
        sessionRepository,
        mock(IssueRepository.class),
        mock(IssueDependencyRepository.class),
        mock(IssueInputRepository.class),
        mock(IssueRunRepository.class),
        mock(IssueControllerWorkRepository.class),
        mock(SessionDeletionOrchestrator.class));
  }

  @Test
  void testCreateAndBindFalseResultsFailFast() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectSessionRepository sessionRepository = mock(ProjectSessionRepository.class);
    ProjectServiceImpl service = createService(projectRepository, sessionRepository);

    // Repository 的布尔返回值必须被检查，禁止把未落库的对象当作成功结果。
    when(projectRepository.create(any(Project.class))).thenReturn(false);
    assertEquals(
        "Failed to create project",
        assertThrows(
                AiValidationException.class,
                () -> service.createProject("Title", null, "coordinator"))
            .getMessage());
  }

  @Test
  void testMutationCasFailuresReportLatestVersion() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectServiceImpl service =
        createService(projectRepository, mock(ProjectSessionRepository.class));

    // 锁后 UPDATE 仍可能因底层 CAS 失败；服务必须报告重新读取到的版本。
    UUID updateId = UUID.randomUUID();
    when(projectRepository.lockById(updateId)).thenReturn(project(updateId, 0L, false));
    when(projectRepository.updateById(any(Project.class), eq(0L))).thenReturn(false);
    when(projectRepository.getById(updateId)).thenReturn(project(updateId, 7L, false));
    AiVersionConflictException updateConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> service.updateProject(updateId, 0L, "New", null, "coordinator"));
    assertEquals("0", updateConflict.expectedVersion());
    assertEquals("7", updateConflict.actualVersion());

    UUID archiveId = UUID.randomUUID();
    when(projectRepository.lockById(archiveId)).thenReturn(project(archiveId, 2L, false));
    when(projectRepository.updateArchivedAt(eq(archiveId), any(Instant.class), eq(2L)))
        .thenReturn(false);
    when(projectRepository.getById(archiveId)).thenReturn(project(archiveId, 3L, false));
    AiVersionConflictException archiveConflict =
        assertThrows(AiVersionConflictException.class, () -> service.archiveProject(archiveId, 2L));
    assertEquals("3", archiveConflict.actualVersion());

    UUID unarchiveId = UUID.randomUUID();
    when(projectRepository.lockById(unarchiveId)).thenReturn(project(unarchiveId, 4L, true));
    when(projectRepository.updateArchivedAt(unarchiveId, null, 4L)).thenReturn(false);
    when(projectRepository.getById(unarchiveId)).thenReturn(null);
    AiVersionConflictException unarchiveConflict =
        assertThrows(
            AiVersionConflictException.class, () -> service.unarchiveProject(unarchiveId, 4L));
    assertEquals("-1", unarchiveConflict.actualVersion());
  }

  @Test
  void testDeleteProjectDefensiveValidationBranches() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectSessionRepository sessionRepository = mock(ProjectSessionRepository.class);
    IssueRepository issueRepository = mock(IssueRepository.class);
    IssueDependencyRepository issueDependencyRepository = mock(IssueDependencyRepository.class);
    IssueInputRepository issueInputRepository = mock(IssueInputRepository.class);
    IssueRunRepository issueRunRepository = mock(IssueRunRepository.class);
    IssueControllerWorkRepository issueControllerWorkRepository =
        mock(IssueControllerWorkRepository.class);
    SessionDeletionOrchestrator sessionDeletionOrchestrator =
        mock(SessionDeletionOrchestrator.class);

    ProjectServiceImpl service =
        createService(
            projectRepository,
            sessionRepository,
            issueRepository,
            issueDependencyRepository,
            issueInputRepository,
            issueRunRepository,
            issueControllerWorkRepository,
            sessionDeletionOrchestrator);

    UUID projectId = UUID.randomUUID();
    Project proj = project(projectId, 0L, false);
    when(projectRepository.lockById(projectId)).thenReturn(proj);

    UUID issueId = UUID.randomUUID();
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Issue")
            .description("")
            .status(IssueStatus.TODO)
            .version(0L)
            .build();
    when(issueRepository.listByProjectId(projectId)).thenReturn(List.of(issue));

    // 分支 1: locked issue 为空
    when(issueRepository.lockById(issueId)).thenReturn(null);
    assertThrows(AiValidationException.class, () -> service.deleteProject(projectId, 0L));

    // 分支 2: locked issue 所属 projectId 不匹配
    Issue foreignIssue =
        Issue.builder()
            .id(issueId)
            .projectId(UUID.randomUUID())
            .number(1L)
            .title("Foreign Issue")
            .description("")
            .status(IssueStatus.TODO)
            .version(0L)
            .build();
    when(issueRepository.lockById(issueId)).thenReturn(foreignIssue);
    assertThrows(AiValidationException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 normal locked issue
    when(issueRepository.lockById(issueId)).thenReturn(issue);

    // 分支 3: locked run 为空
    UUID executorRunId = UUID.randomUUID();
    IssueRun executorRun =
        IssueRun.builder()
            .id(executorRunId)
            .issueId(issueId)
            .status(IssueRunStatus.COMPLETED)
            .submissionRunId(null)
            .agentName("agent")
            .version(0L)
            .build();
    UUID reviewerRunId = UUID.randomUUID();
    IssueRun reviewerRun =
        IssueRun.builder()
            .id(reviewerRunId)
            .issueId(issueId)
            .status(IssueRunStatus.COMPLETED)
            .submissionRunId(executorRunId)
            .agentName("reviewer-agent")
            .version(0L)
            .build();

    when(issueRunRepository.listByIssueId(issueId)).thenReturn(List.of(executorRun, reviewerRun));
    when(issueRunRepository.lockById(executorRunId)).thenReturn(null);
    assertThrows(AiValidationException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 normal locked run
    when(issueRunRepository.lockById(executorRunId)).thenReturn(executorRun);
    when(issueRunRepository.lockById(reviewerRunId)).thenReturn(reviewerRun);

    // 分支 4a: reviewer run 删除 CAS 失败
    when(issueRunRepository.deleteById(reviewerRunId, 0L)).thenReturn(false);
    assertThrows(AiVersionConflictException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 reviewer run 删除成功，测试分支 4b: executor run 删除 CAS 失败
    when(issueRunRepository.deleteById(reviewerRunId, 0L)).thenReturn(true);
    when(issueRunRepository.deleteById(executorRunId, 0L)).thenReturn(false);
    assertThrows(AiVersionConflictException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 normal run delete
    when(issueRunRepository.deleteById(executorRunId, 0L)).thenReturn(true);

    // 分支 5: input 数量不匹配
    IssueInput input =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(1L)
            .kind(IssueInputKind.HUMAN)
            .body("input")
            .idempotencyKey("k")
            .build();
    when(issueInputRepository.listByIssueId(issueId)).thenReturn(List.of(input));
    when(issueInputRepository.deleteByIssueId(issueId)).thenReturn(0);
    assertThrows(AiValidationException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 normal input delete
    when(issueInputRepository.deleteByIssueId(issueId)).thenReturn(1);

    // 分支 6: dependency 数量不匹配
    IssueDependency dep =
        IssueDependency.builder()
            .projectId(projectId)
            .issueId(issueId)
            .dependsOnIssueId(UUID.randomUUID())
            .build();
    when(issueDependencyRepository.listByProjectId(projectId)).thenReturn(List.of(dep));
    when(issueDependencyRepository.deleteByProjectId(projectId)).thenReturn(0);
    assertThrows(AiValidationException.class, () -> service.deleteProject(projectId, 0L));

    // 恢复 normal dependency delete
    when(issueDependencyRepository.deleteByProjectId(projectId)).thenReturn(1);

    // 分支 7: project 删除 CAS 失败
    when(projectRepository.deleteById(projectId, 0L)).thenReturn(false);
    when(projectRepository.getById(projectId)).thenReturn(project(projectId, 1L, false));
    assertThrows(AiVersionConflictException.class, () -> service.deleteProject(projectId, 0L));
  }

  private static Project project(UUID id, long version, boolean archived) {
    return Project.builder()
        .id(id)
        .title("Title")
        .description("")
        .coordinatorAgentName("coordinator")
        .nextIssueNumber(1L)
        .version(version)
        .archivedAt(archived ? Instant.now() : null)
        .build();
  }

  private static DataIntegrityViolationException constraintViolation(String constraint) {
    ServerErrorMessage serverError =
        new ServerErrorMessage("SERROR\0C23505\0n" + constraint + "\0\0");
    return new DataIntegrityViolationException(
        "Database write failed", new PSQLException(serverError));
  }
}
