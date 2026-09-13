package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
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
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueControllerWorkStore;
import fun.fengwk.kkstudio.platform.project.service.impl.IssueServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 IssueService 对锁后 CAS 丢失及多步写入失败的无副作用处理。 */
class IssueServiceDefensiveUnitTest {

  @Test
  void testMutationCasFailuresReportLatestVersion() {
    // Spec 更新在锁后 CAS 失败时必须重新读取并报告最新版本。
    Fixture update = new Fixture();
    UUID updateId = UUID.randomUUID();
    Issue updateCurrent = issue(updateId, IssueStatus.TODO, 0L, false);
    when(update.issues.getById(updateId))
        .thenReturn(updateCurrent, issue(updateId, IssueStatus.TODO, 8L, false));
    when(update.issues.lockById(updateId)).thenReturn(updateCurrent);
    when(update.issues.updateById(any(Issue.class), eq(0L))).thenReturn(false);
    AiVersionConflictException updateConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> update.service.updateIssue(updateId, 0L, "Changed", null, "agent", null));
    assertEquals("8", updateConflict.actualVersion());

    // 显式状态迁移也必须检查 UPDATE 影响行数。
    Fixture status = new Fixture();
    UUID statusId = UUID.randomUUID();
    Issue statusCurrent = issue(statusId, IssueStatus.BACKLOG, 2L, false);
    when(status.issues.getById(statusId))
        .thenReturn(statusCurrent, issue(statusId, IssueStatus.BACKLOG, 3L, false));
    when(status.issues.lockById(statusId)).thenReturn(statusCurrent);
    when(status.issues.updateById(any(Issue.class), eq(2L))).thenReturn(false);
    AiVersionConflictException statusConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> status.service.setStatus(statusId, 2L, IssueStatus.TODO));
    assertEquals("3", statusConflict.actualVersion());

    // 归档 CAS 失败不能返回已经在内存中修改过的对象。
    Fixture archive = new Fixture();
    UUID archiveId = UUID.randomUUID();
    Issue archiveCurrent = issue(archiveId, IssueStatus.DONE, 4L, false);
    when(archive.issues.getById(archiveId))
        .thenReturn(archiveCurrent, issue(archiveId, IssueStatus.DONE, 5L, false));
    when(archive.issues.lockById(archiveId)).thenReturn(archiveCurrent);
    when(archive.issues.updateById(any(Issue.class), eq(4L))).thenReturn(false);
    AiVersionConflictException archiveConflict =
        assertThrows(
            AiVersionConflictException.class, () -> archive.service.archiveIssue(archiveId, 4L));
    assertEquals("5", archiveConflict.actualVersion());

    // 解归档失败后的行可能已消失，actualVersion 必须稳定回退到 -1。
    Fixture unarchive = new Fixture();
    UUID unarchiveId = UUID.randomUUID();
    Issue unarchiveCurrent = issue(unarchiveId, IssueStatus.CANCELED, 6L, true);
    when(unarchive.issues.getById(unarchiveId)).thenReturn(unarchiveCurrent, (Issue) null);
    when(unarchive.issues.lockById(unarchiveId)).thenReturn(unarchiveCurrent);
    when(unarchive.issues.updateById(any(Issue.class), eq(6L))).thenReturn(false);
    AiVersionConflictException unarchiveConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> unarchive.service.unarchiveIssue(unarchiveId, 6L));
    assertEquals("-1", unarchiveConflict.actualVersion());
  }

  @Test
  void testCancelStopsWhenActiveRunUpdateFails() {
    Fixture fixture = new Fixture();
    UUID issueId = UUID.randomUUID();
    Issue current = issue(issueId, IssueStatus.IN_PROGRESS, 0L, false);
    IssueRun activeRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .build();
    when(fixture.issues.getById(issueId)).thenReturn(current);
    when(fixture.issues.lockById(issueId)).thenReturn(current);
    when(fixture.runs.lockActiveByIssueId(issueId)).thenReturn(activeRun);
    when(fixture.runs.updateById(activeRun, 0L)).thenReturn(false);

    // Run CAS 失败后必须立即中止，不能继续把 Issue 标记为 CANCELED。
    assertEquals(
        "Failed to cancel active run",
        assertThrows(
                AiValidationException.class,
                () -> fixture.service.cancelIssue(issueId, 0L, "cancel"))
            .getMessage());
    verify(fixture.issues, never()).updateById(any(Issue.class), anyLong());
    verify(fixture.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  @Test
  void testDependencyAndInputWritesCheckAffectedRows() {
    Fixture dependency = new Fixture();
    UUID issueId = UUID.randomUUID();
    UUID dependsOnId = UUID.randomUUID();
    Issue target = issue(issueId, IssueStatus.TODO, 0L, false);
    Issue dependsOn = issue(dependsOnId, IssueStatus.DONE, 0L, false);
    when(dependency.issues.getById(issueId)).thenReturn(target);
    when(dependency.projects.lockById(target.getProjectId()))
        .thenReturn(Project.builder().id(target.getProjectId()).build());
    when(dependency.issues.lockById(any(UUID.class)))
        .thenAnswer(invocation -> invocation.getArgument(0).equals(issueId) ? target : dependsOn);
    when(dependency.dependencies.listByIssueId(issueId)).thenReturn(List.of());
    when(dependency.dependencies.checkHasPath(dependsOnId, issueId)).thenReturn(false);
    when(dependency.dependencies.addDependency(any())).thenReturn(false);

    // Dependency INSERT 未影响一行时，不得递增 specRevision 或唤醒 Controller。
    assertEquals(
        "Failed to add dependency",
        assertThrows(
                AiValidationException.class,
                () -> dependency.service.addDependency(issueId, dependsOnId, 0L))
            .getMessage());
    verify(dependency.issues, never()).updateById(any(Issue.class), anyLong());
    verify(dependency.workStore, never()).requestWork(eq(issueId), any(Instant.class));

    Fixture input = new Fixture();
    Issue inputIssue = issue(issueId, IssueStatus.TODO, 0L, false);
    when(input.issues.getById(issueId)).thenReturn(inputIssue);
    when(input.issues.lockById(issueId)).thenReturn(inputIssue);
    when(input.issues.incrementInputSequence(issueId)).thenReturn(1L);
    when(input.inputs.append(any(IssueInput.class))).thenReturn(false);

    // Input INSERT 失败必须在请求工作前抛错。
    assertEquals(
        "Failed to append issue input",
        assertThrows(
                AiValidationException.class,
                () -> input.service.appendInput(issueId, IssueInputKind.HUMAN, "question", null))
            .getMessage());
    verify(input.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  private static Issue issue(UUID id, IssueStatus status, long version, boolean archived) {
    return Issue.builder()
        .id(id)
        .projectId(new UUID(0L, 1L))
        .number(1L)
        .title("Title")
        .description("")
        .status(status)
        .assigneeAgentName("agent")
        .reviewerAgentName(null)
        .version(version)
        .specRevision(1L)
        .inputSequence(0L)
        .archivedAt(archived ? Instant.now() : null)
        .build();
  }

  private static final class Fixture {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);
    private final IssueDependencyRepository dependencies = mock(IssueDependencyRepository.class);
    private final IssueInputRepository inputs = mock(IssueInputRepository.class);
    private final IssueRunRepository runs = mock(IssueRunRepository.class);
    private final IssueControllerWorkStore workStore = mock(IssueControllerWorkStore.class);
    private final IssueServiceImpl service =
        new IssueServiceImpl(projects, issues, dependencies, inputs, runs, workStore);
  }
}
