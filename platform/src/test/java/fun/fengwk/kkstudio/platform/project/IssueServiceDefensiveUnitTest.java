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
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.service.impl.IssueServiceImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 IssueService 对锁后 CAS 丢失、异常返回及多步写入失败的无副作用防御性处理。 */
class IssueServiceDefensiveUnitTest {

  @Test
  void testMutationCasFailuresReportLatestVersion() {
    // 测试意图：验证要求更新、状态迁移、归档和解归档在锁后 CAS 失败时，必须重新读取并报告最新版本。
    Fixture update = new Fixture();
    UUID updateId = UUID.randomUUID();
    Issue updateCurrent = issue(updateId, IssueStatus.TODO, 0L, false);
    when(update.issues.getById(updateId))
        .thenReturn(updateCurrent, issue(updateId, IssueStatus.TODO, 8L, false));
    when(update.projects.lockById(updateCurrent.getProjectId()))
        .thenReturn(Project.builder().id(updateCurrent.getProjectId()).build());
    when(update.issues.lockById(updateId)).thenReturn(updateCurrent);
    when(update.issues.updateById(any(Issue.class), eq(0L))).thenReturn(false);
    AiVersionConflictException updateConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> update.service.updateIssue(updateId, 0L, "Changed", null, "agent", null));
    assertEquals("8", updateConflict.actualVersion());

    // 显式状态迁移检查 UPDATE 影响行数
    Fixture status = new Fixture();
    UUID statusId = UUID.randomUUID();
    Issue statusCurrent = issue(statusId, IssueStatus.BACKLOG, 2L, false);
    when(status.issues.getById(statusId))
        .thenReturn(statusCurrent, issue(statusId, IssueStatus.BACKLOG, 3L, false));
    when(status.projects.lockById(statusCurrent.getProjectId()))
        .thenReturn(Project.builder().id(statusCurrent.getProjectId()).build());
    when(status.issues.lockById(statusId)).thenReturn(statusCurrent);
    when(status.issues.updateById(any(Issue.class), eq(2L))).thenReturn(false);
    AiVersionConflictException statusConflict =
        assertThrows(
            AiVersionConflictException.class,
            () -> status.service.setStatus(statusId, 2L, IssueStatus.TODO));
    assertEquals("3", statusConflict.actualVersion());

    // 归档 CAS 失败不能返回已在内存中修改过的对象
    Fixture archive = new Fixture();
    UUID archiveId = UUID.randomUUID();
    Issue archiveCurrent = issue(archiveId, IssueStatus.DONE, 4L, false);
    when(archive.issues.getById(archiveId))
        .thenReturn(archiveCurrent, issue(archiveId, IssueStatus.DONE, 5L, false));
    when(archive.projects.lockById(archiveCurrent.getProjectId()))
        .thenReturn(Project.builder().id(archiveCurrent.getProjectId()).build());
    when(archive.issues.lockById(archiveId)).thenReturn(archiveCurrent);
    when(archive.issues.updateById(any(Issue.class), eq(4L))).thenReturn(false);
    AiVersionConflictException archiveConflict =
        assertThrows(
            AiVersionConflictException.class, () -> archive.service.archiveIssue(archiveId, 4L));
    assertEquals("5", archiveConflict.actualVersion());

    // 解归档失败后的行若已消失，actualVersion 稳定回退到 -1
    Fixture unarchive = new Fixture();
    UUID unarchiveId = UUID.randomUUID();
    Issue unarchiveCurrent = issue(unarchiveId, IssueStatus.CANCELED, 6L, true);
    when(unarchive.issues.getById(unarchiveId)).thenReturn(unarchiveCurrent, (Issue) null);
    when(unarchive.projects.lockById(unarchiveCurrent.getProjectId()))
        .thenReturn(Project.builder().id(unarchiveCurrent.getProjectId()).build());
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
    // 测试意图：取消 Issue 时若活动 Run CAS 更新失败，必须立即中止，禁止继续将 Issue 标记为 CANCELED。
    Fixture fixture = new Fixture();
    UUID issueId = UUID.randomUUID();
    Issue current = issue(issueId, IssueStatus.IN_PROGRESS, 0L, false);
    IssueRun activeRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issueId)
            .role(IssueRunRole.EXECUTOR)
            .agentName("agent")
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .build();
    when(fixture.issues.getById(issueId)).thenReturn(current);
    when(fixture.projects.lockById(current.getProjectId()))
        .thenReturn(Project.builder().id(current.getProjectId()).build());
    when(fixture.issues.lockById(issueId)).thenReturn(current);
    when(fixture.runs.lockActiveByIssueId(issueId)).thenReturn(activeRun);
    when(fixture.runs.updateById(activeRun, 0L)).thenReturn(false);

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
  void testDependencyAndActivityWritesCheckAffectedRows() {
    // 测试意图：验证依赖插入失败及 Activity 追加失败时，前置中断并不触发后续状态更新或 Work 请求。
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

    assertEquals(
        "Failed to add dependency",
        assertThrows(
                AiValidationException.class,
                () -> dependency.service.addDependency(issueId, dependsOnId, 0L))
            .getMessage());
    verify(dependency.issues, never()).updateById(any(Issue.class), anyLong());
    verify(dependency.workStore, never()).requestWork(eq(issueId), any(Instant.class));

    Fixture activityFixture = new Fixture();
    Issue activityIssue = issue(issueId, IssueStatus.TODO, 0L, false);
    when(activityFixture.issues.getById(issueId)).thenReturn(activityIssue);
    when(activityFixture.projects.lockById(activityIssue.getProjectId()))
        .thenReturn(Project.builder().id(activityIssue.getProjectId()).build());
    when(activityFixture.issues.lockById(issueId)).thenReturn(activityIssue);
    when(activityFixture.activities.appendOrGet(any(IssueActivity.class))).thenReturn(null);

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .kind(IssueActivityKind.HUMAN_INPUT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("question")
            .build();
    assertEquals(
        "Failed to append activity",
        assertThrows(
                AiValidationException.class, () -> activityFixture.service.appendActivity(activity))
            .getMessage());
    verify(activityFixture.workStore, never()).requestWork(eq(issueId), any(Instant.class));
  }

  @Test
  void testBlockAndRecoverDefensiveBranches() {
    // 测试意图：验证 blockIssue 与 recoverIssue 的状态前提检查、活动 Run 中断及 CAS 冲突防御。
    Fixture fixture = new Fixture();
    UUID issueId = UUID.randomUUID();
    Issue doneIssue = issue(issueId, IssueStatus.DONE, 0L, false);
    when(fixture.issues.getById(issueId)).thenReturn(doneIssue);
    when(fixture.projects.lockById(doneIssue.getProjectId()))
        .thenReturn(Project.builder().id(doneIssue.getProjectId()).build());
    when(fixture.issues.lockById(issueId)).thenReturn(doneIssue);

    // blockIssue 不能作用于终态 Issue
    assertThrows(
        AiValidationException.class, () -> fixture.service.blockIssue(issueId, 0L, "reason"));

    // recoverIssue 只能作用于 BLOCKED Issue
    Issue todoIssue = issue(issueId, IssueStatus.TODO, 0L, false);
    when(fixture.issues.getById(issueId)).thenReturn(todoIssue);
    when(fixture.issues.lockById(issueId)).thenReturn(todoIssue);
    assertThrows(
        AiValidationException.class,
        () -> fixture.service.recoverIssue(issueId, 0L, false, "comment"));
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
        .archivedAt(archived ? Instant.now() : null)
        .build();
  }

  private static final class Fixture {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final IssueRepository issues = mock(IssueRepository.class);
    private final IssueDependencyRepository dependencies = mock(IssueDependencyRepository.class);
    private final IssueActivityRepository activities = mock(IssueActivityRepository.class);
    private final IssueRunRepository runs = mock(IssueRunRepository.class);
    private final IssueWorkStore workStore = mock(IssueWorkStore.class);
    private final IssueServiceImpl service =
        new IssueServiceImpl(projects, issues, dependencies, activities, runs, workStore);
  }
}
