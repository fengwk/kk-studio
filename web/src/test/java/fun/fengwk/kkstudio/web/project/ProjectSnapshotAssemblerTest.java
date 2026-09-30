package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.convention4j.common.json.jackson.ObjectMapperHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueRunService;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 验证 ProjectSnapshotAssembler 的权威组装与 fail-closed 跨实体隔离校验。 */
class ProjectSnapshotAssemblerTest {

  private ProjectService projectService;
  private IssueService issueService;
  private IssueRunService issueRunService;
  private ProjectDtoMapper mapper;
  private ProjectSnapshotAssembler assembler;

  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    issueService = mock(IssueService.class);
    issueRunService = mock(IssueRunService.class);
    mapper = new ProjectDtoMapper(new ProjectWorkflowJsonCodec(), ObjectMapperHolder.getInstance());
    assembler = new ProjectSnapshotAssembler(projectService, issueService, issueRunService, mapper);
  }

  @Test
  void testAssembleSuccessful() {
    // 测试意图：验证完整 snapshot 组装：Issues 排序、activeRun 映射与 agent 绑定解析
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .yoloEnabled(false)
            .nextIssueNumber(3L)
            .version(1L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(projectService.getProject(projectId)).thenReturn(project);

    UUID issue1Id = UUID.randomUUID();
    UUID issue2Id = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    Issue issue1 =
        Issue.builder()
            .id(issue1Id)
            .projectId(projectId)
            .number(2L)
            .title("Issue 2")
            .state("INIT")
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
            .state("WORK")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(issue1, issue2));

    IssueRun run =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issue2Id)
            .ordinal(1L)
            .state("WORK")
            .threadId(threadId)
            .status(IssueRunStatus.RUNNING)
            .version(0L)
            .startedAt(now)
            .build();
    when(issueRunService.listRuns(issue1Id)).thenReturn(List.of());
    when(issueRunService.listRuns(issue2Id)).thenReturn(List.of(run));
    when(issueService.listAgentThreads(issue2Id))
        .thenReturn(List.of(new IssueAgentThread(issue2Id, "coder", threadId)));

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertNotNull(snapshot);
    assertEquals("Proj", snapshot.getProject().getTitle());
    // 验证按 number 排序：issue 1 先于 issue 2
    assertEquals(2, snapshot.getIssues().size());
    assertEquals("1", snapshot.getIssues().get(0).getIssue().getNumber());
    assertNotNull(snapshot.getIssues().get(0).getCurrentOrLatestRun());
    assertEquals("coder", snapshot.getIssues().get(0).getCurrentOrLatestRun().getAgentName());
    assertEquals("2", snapshot.getIssues().get(1).getIssue().getNumber());
    assertNull(snapshot.getIssues().get(1).getCurrentOrLatestRun());

    // 验证 ProjectSnapshotDTO 权威聚合只包含 project 与 issues
    assertEquals(
        Set.of("project", "issues"),
        Arrays.stream(ProjectSnapshotDTO.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet()));
  }

  @Test
  void testProjectNotFoundFails() {
    // 测试意图：项目不存在时抛出 404
    when(projectService.getProject(projectId)).thenReturn(null);
    assertThrows(ProjectNotFoundException.class, () -> assembler.assemble(projectId));
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

    UUID foreignIssueId = UUID.randomUUID();
    Issue foreignIssue =
        Issue.builder()
            .id(foreignIssueId)
            .projectId(UUID.randomUUID()) // 属于另外一个项目
            .number(1L)
            .title("Foreign")
            .state("INIT")
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
            .state("INIT")
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(issue));

    IssueRun foreignRun =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(UUID.randomUUID()) // 不等于 issueId
            .ordinal(1L)
            .version(0L)
            .startedAt(now)
            .build();
    when(issueRunService.listRuns(issueId)).thenReturn(List.of(foreignRun));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));
    assertTrue(ex.getMessage().contains("Foreign run"));
  }
}
