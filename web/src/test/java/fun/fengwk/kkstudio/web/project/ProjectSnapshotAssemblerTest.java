package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 验证 ProjectSnapshotAssembler 的权威组装与 fail-closed 跨实体隔离校验。 */
class ProjectSnapshotAssemblerTest {

  private ProjectService projectService;
  private IssueService issueService;
  private IssueRunService issueRunService;
  private HarnessOwnerQueryService harnessOwnerQueryService;
  private ProjectDtoMapper mapper;
  private ProjectSnapshotAssembler assembler;

  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    harnessOwnerQueryService = mock(HarnessOwnerQueryService.class);
    mapper = new ProjectDtoMapper();
    assembler =
        new ProjectSnapshotAssembler(
            projectService, issueService, issueRunService, harnessOwnerQueryService, mapper);
  }

  @Test
  void testAssembleSuccessful() {
    // 测试意图：验证完整 snapshot 组装：Issues 排序、blocked 判定、activeRun、coordinator 映射
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .coordinatorAgentName("coord")
            .nextIssueNumber(3L)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.getProject(projectId)).thenReturn(project);

    UUID issue1Id = UUID.randomUUID();
    UUID issue2Id = UUID.randomUUID();
    Issue issue1 =
        Issue.builder()
            .id(issue1Id)
            .projectId(projectId)
            .number(2L)
            .title("Issue 2")
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    Issue issue2 =
        Issue.builder()
            .id(issue2Id)
            .projectId(projectId)
            .number(1L)
            .title("Issue 1")
            .status(IssueStatus.IN_PROGRESS)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(issue1, issue2));

    when(issueService.isBlocked(issue1Id)).thenReturn(true);
    when(issueService.isBlocked(issue2Id)).thenReturn(false);

    IssueRun run =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issue2Id)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueRunService.getActiveRun(issue1Id)).thenReturn(null);
    when(issueRunService.getLatestRun(issue1Id)).thenReturn(null);
    when(issueRunService.getActiveRun(issue2Id)).thenReturn(run);

    IssueDependency dep =
        IssueDependency.builder()
            .issueId(issue1Id)
            .dependsOnIssueId(issue2Id)
            .projectId(projectId)
            .createdAt(now)
            .build();
    when(issueService.listProjectDependencies(projectId)).thenReturn(List.of(dep));

    UUID sessionId = UUID.randomUUID();
    ProjectSession ps =
        ProjectSession.builder().projectId(projectId).sessionId(sessionId).createdAt(now).build();
    when(projectService.getCoordinatorSession(projectId)).thenReturn(ps);

    HarnessSessionSummaryDTO sessionSummary = new HarnessSessionSummaryDTO();
    sessionSummary.setSessionId(sessionId.toString().toLowerCase());
    when(harnessOwnerQueryService.listProjectSessions(projectId))
        .thenReturn(List.of(sessionSummary));

    HarnessThreadSummaryDTO threadSummary = new HarnessThreadSummaryDTO();
    threadSummary.setThreadId(UUID.randomUUID().toString().toLowerCase());
    when(harnessOwnerQueryService.listThreadSummaries(sessionId))
        .thenReturn(List.of(threadSummary));

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertNotNull(snapshot);
    assertEquals("Proj", snapshot.getProject().getTitle());
    // 验证按 number 排序：issue 1 先于 issue 2
    assertEquals(2, snapshot.getIssues().size());
    assertEquals("1", snapshot.getIssues().get(0).getIssue().getNumber());
    assertFalse(snapshot.getIssues().get(0).getBlocked());
    assertNotNull(snapshot.getIssues().get(0).getCurrentOrLatestRun());
    assertEquals("2", snapshot.getIssues().get(1).getIssue().getNumber());
    assertTrue(snapshot.getIssues().get(1).getBlocked());
    assertNull(snapshot.getIssues().get(1).getCurrentOrLatestRun());

    assertEquals(1, snapshot.getDependencies().size());
    assertEquals(sessionId.toString().toLowerCase(), snapshot.getCoordinatorSessionId());
    assertNotNull(snapshot.getCoordinatorSession());
    assertNotNull(snapshot.getCoordinatorThread());
  }

  @Test
  void testProjectNotFoundFails() {
    // 测试意图：项目不存在时抛出 404
    when(projectService.getProject(projectId)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> assembler.assemble(projectId));
  }

  @Test
  void testFailClosedOnForeignIssue() {
    // 测试意图：若查询结果中混入了非本项目 Issue，必须 fail-closed 抛异常
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.getProject(projectId)).thenReturn(project);

    Issue foreignIssue =
        Issue.builder()
            .id(UUID.randomUUID())
            .projectId(UUID.randomUUID()) // 属于另外一个项目
            .number(1L)
            .title("Foreign")
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(foreignIssue));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));
    assertTrue(ex.getMessage().contains("Foreign issue"));
  }

  @Test
  void testFailClosedOnForeignDependency() {
    // 测试意图：若依赖边出现 foreign projectId，抛异常
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.getProject(projectId)).thenReturn(project);
    when(issueService.listIssues(projectId, false)).thenReturn(List.of());

    IssueDependency foreignDep =
        IssueDependency.builder()
            .issueId(UUID.randomUUID())
            .dependsOnIssueId(UUID.randomUUID())
            .projectId(UUID.randomUUID()) // foreign
            .createdAt(now)
            .build();
    when(issueService.listProjectDependencies(projectId)).thenReturn(List.of(foreignDep));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));
    assertTrue(ex.getMessage().contains("Foreign dependency"));
  }

  @Test
  void testFailClosedOnForeignRun() {
    // 测试意图：若 IssueRun 的 issueId 与当前 Issue 不一致，抛异常
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.getProject(projectId)).thenReturn(project);

    UUID issueId = UUID.randomUUID();
    Issue issue =
        Issue.builder()
            .id(issueId)
            .projectId(projectId)
            .number(1L)
            .title("Issue")
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(issue));
    when(issueService.listProjectDependencies(projectId)).thenReturn(List.of());

    IssueRun foreignRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(UUID.randomUUID()) // 不等于 issueId
            .ordinal(1L)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueRunService.getActiveRun(issueId)).thenReturn(foreignRun);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));
    assertTrue(ex.getMessage().contains("Foreign run"));
  }
}
