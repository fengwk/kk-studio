package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.impl.ProjectServiceImpl;

import java.time.Instant;
import java.util.UUID;

/** 验证 ProjectService 对持久层异常返回和数据库约束冲突的防御性处理。 */
class ProjectServiceDefensiveUnitTest {

  @Test
  void testCreateAndBindFalseResultsFailFast() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectSessionRepository sessionRepository = mock(ProjectSessionRepository.class);
    ProjectServiceImpl service = new ProjectServiceImpl(projectRepository, sessionRepository);

    // Repository 的布尔返回值必须被检查，禁止把未落库的对象当作成功结果。
    when(projectRepository.create(any(Project.class))).thenReturn(false);
    assertEquals(
        "Failed to create project",
        assertThrows(
                AiValidationException.class,
                () -> service.createProject("Title", null, "coordinator"))
            .getMessage());

    UUID projectId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(projectRepository.lockById(projectId)).thenReturn(project(projectId, 0L, false));
    when(sessionRepository.bindSession(projectId, sessionId)).thenReturn(false);
    assertEquals(
        "Failed to bind coordinator session",
        assertThrows(
                AiValidationException.class,
                () -> service.bindCoordinatorSession(projectId, sessionId))
            .getMessage());
  }

  @Test
  void testMutationCasFailuresReportLatestVersion() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectServiceImpl service =
        new ProjectServiceImpl(projectRepository, mock(ProjectSessionRepository.class));

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
  void testKnownOwnerConstraintIsTranslatedWithoutDatabaseDetails() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectSessionRepository sessionRepository = mock(ProjectSessionRepository.class);
    ProjectServiceImpl service = new ProjectServiceImpl(projectRepository, sessionRepository);
    UUID projectId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(projectRepository.lockById(projectId)).thenReturn(project(projectId, 0L, false));
    when(sessionRepository.bindSession(projectId, sessionId))
        .thenThrow(constraintViolation("chk_harness_session_single_owner"));

    // 已知单归属冲突必须转换成稳定领域错误，且不保留数据库异常 cause。
    AiValidationException exception =
        assertThrows(
            AiValidationException.class,
            () -> service.bindCoordinatorSession(projectId, sessionId));
    assertEquals("Session is already owned by another entity", exception.getMessage());
    assertNull(exception.getCause());
  }

  @Test
  void testUnknownOwnerConstraintIsPropagated() {
    ProjectRepository projectRepository = mock(ProjectRepository.class);
    ProjectSessionRepository sessionRepository = mock(ProjectSessionRepository.class);
    ProjectServiceImpl service = new ProjectServiceImpl(projectRepository, sessionRepository);
    UUID projectId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(projectRepository.lockById(projectId)).thenReturn(project(projectId, 0L, false));
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("unrelated constraint");
    when(sessionRepository.bindSession(projectId, sessionId)).thenThrow(original);

    // 未识别的数据库错误不能被误报为 session 冲突。
    assertSame(
        original,
        assertThrows(
            DataIntegrityViolationException.class,
            () -> service.bindCoordinatorSession(projectId, sessionId)));
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
