package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
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
    mapper = new ProjectDtoMapper();
    assembler = new ProjectSnapshotAssembler(projectService, issueService, issueRunService, mapper);
  }

  @Test
  void testAssembleSuccessful() {
    // 测试意图：验证完整 snapshot 组装：Issues 排序、blocked 判定、activeRun 映射
    Project project =
        Project.builder()
            .id(projectId)
            .title("Proj")
            .yoloEnabled(false)
            .maxReviewRejections(3)
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

    // 打回次数必须来自 IssueService 的当前审查窗口权威计数，assembler 不得自行推导
    when(issueService.countRejections(issue1Id)).thenReturn(2L);
    when(issueService.countRejections(issue2Id)).thenReturn(0L);

    IssueRun run =
        IssueRun.builder()
            .id(UUID.randomUUID())
            .issueId(issue2Id)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
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

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertNotNull(snapshot);
    assertEquals("Proj", snapshot.getProject().getTitle());
    // 验证按 number 排序：issue 1 先于 issue 2
    assertEquals(2, snapshot.getIssues().size());
    assertEquals("1", snapshot.getIssues().get(0).getIssue().getNumber());
    assertFalse(snapshot.getIssues().get(0).getBlocked());
    assertNotNull(snapshot.getIssues().get(0).getCurrentOrLatestRun());
    // 未被 block 的 issue 打回次数为 0，但仍显式输出，保证前端解码字段确定性存在
    assertEquals("0", snapshot.getIssues().get(0).getReviewRejectionCount());
    assertEquals("2", snapshot.getIssues().get(1).getIssue().getNumber());
    assertTrue(snapshot.getIssues().get(1).getBlocked());
    // blocked issue 打回次数为服务端权威计数，BLOCKED 卡片据此展示 current / maxReviewRejections
    assertEquals("2", snapshot.getIssues().get(1).getReviewRejectionCount());
    assertNull(snapshot.getIssues().get(1).getCurrentOrLatestRun());
    verify(issueService).countRejections(issue1Id);
    verify(issueService).countRejections(issue2Id);

    assertEquals(1, snapshot.getDependencies().size());
    assertEquals(
        dep.getIssueId().toString().toLowerCase(), snapshot.getDependencies().get(0).getIssueId());
    assertEquals(
        dep.getDependsOnIssueId().toString().toLowerCase(),
        snapshot.getDependencies().get(0).getDependsOnIssueId());

    // 验证 ProjectSnapshotDTO 权威聚合只包含 project, issues, dependencies，彻底无 coordinator 关联
    assertEquals(
        Set.of("project", "issues", "dependencies"),
        Arrays.stream(ProjectSnapshotDTO.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet()));
  }

  @Test
  void testProjectNotFoundFails() {
    // 测试意图：项目不存在时抛出 404
    when(projectService.getProject(projectId)).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> assembler.assemble(projectId));
  }

  @Test
  void testFailClosedOnForeignIssue() {
    // 测试意图：若查询结果中混入了非本项目 Issue，必须 fail-closed 抛异常，且不得对该 foreign issue 发起打回次数查询
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
            .status(IssueStatus.TODO)
            .version(0L)
            .createdAt(now)
            .updatedAt(now)
            .build();
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(foreignIssue));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));
    assertTrue(ex.getMessage().contains("Foreign issue"));
    // 归属校验先于任何计数查询，foreign issue 的权威数据不会被读取，杜绝跨项目泄漏
    verify(issueService, never()).countRejections(foreignIssueId);
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
