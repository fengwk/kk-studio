package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

    assertEquals(List.of("INIT", "WORK"), snapshot.getReferencedStateCodes());
    // 验证快照的权威字段，防止引入额外内容投影。
    assertEquals(
        Set.of("project", "issues", "referencedStateCodes"),
        Arrays.stream(ProjectSnapshotDTO.class.getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet()));
  }

  @Test
  void testReferencedStatesIncludeArchivedAndBlockedWithoutArchivedDetails() {
    // 两类 Issue 的状态与恢复目标必须合并；归档内容不映射，也不读取其 Run 或 Thread。
    when(projectService.getProject(projectId)).thenReturn(Project.builder().id(projectId).build());
    Issue active = referenceIssue(projectId, "BLOCKED", "WORK", false);
    Issue activeInit = referenceIssue(projectId, "INIT", null, false);
    Issue archived = referenceIssue(projectId, "BLOCKED", "REVIEW", true);
    Issue archivedDone = referenceIssue(projectId, "DONE", null, true);
    Issue archivedDuplicate = referenceIssue(projectId, "BLOCKED", "WORK", true);
    when(issueService.listIssues(projectId, false)).thenReturn(List.of(active, activeInit));
    when(issueService.listIssues(projectId, true))
        .thenReturn(List.of(archivedDone, archivedDuplicate, archived));
    when(issueRunService.listRuns(active.getId())).thenReturn(List.of());
    when(issueRunService.listRuns(activeInit.getId())).thenReturn(List.of());

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertEquals(
        List.of("BLOCKED", "DONE", "INIT", "REVIEW", "WORK"), snapshot.getReferencedStateCodes());
    assertEquals(
        Set.of(active.getId().toString(), activeInit.getId().toString()),
        snapshot.getIssues().stream()
            .map(item -> item.getIssue().getId())
            .collect(Collectors.toSet()));
    var json = ObjectMapperHolder.getInstance().valueToTree(snapshot);
    assertEquals(
        ObjectMapperHolder.getInstance()
            .valueToTree(List.of("BLOCKED", "DONE", "INIT", "REVIEW", "WORK")),
        json.get("referencedStateCodes"));
    verify(issueRunService).listRuns(active.getId());
    verify(issueRunService).listRuns(activeInit.getId());
    verifyNoMoreInteractions(issueRunService);
    verify(issueService).listIssues(projectId, false);
    verify(issueService).listIssues(projectId, true);
    verifyNoMoreInteractions(issueService);
  }

  @Test
  void testEmptyProjectProvidesEmptyReferenceArray() {
    // 没有 Issue 时仍提供真实非 null 数组，而非缺失字段的兼容默认值。
    when(projectService.getProject(projectId)).thenReturn(Project.builder().id(projectId).build());
    when(issueService.listIssues(projectId, false)).thenReturn(List.of());
    when(issueService.listIssues(projectId, true)).thenReturn(List.of());

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertEquals(List.of(), snapshot.getReferencedStateCodes());
    assertEquals(List.of(), snapshot.getIssues());
    var json = ObjectMapperHolder.getInstance().valueToTree(snapshot);
    assertTrue(json.get("referencedStateCodes").isArray());
    assertEquals(0, json.get("referencedStateCodes").size());
    verifyNoInteractions(issueRunService);
  }

  @Test
  void testFailClosedOnForeignArchivedIssue() {
    // 归档查询也必须验证项目归属，且失败时不得读取任何 Run。
    when(projectService.getProject(projectId)).thenReturn(Project.builder().id(projectId).build());
    when(issueService.listIssues(projectId, false))
        .thenReturn(List.of(referenceIssue(projectId, "INIT", null, false)));
    when(issueService.listIssues(projectId, true))
        .thenReturn(List.of(referenceIssue(UUID.randomUUID(), "BLOCKED", "WORK", true)));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> assembler.assemble(projectId));

    assertTrue(ex.getMessage().contains("Foreign issue"));
    verifyNoInteractions(issueRunService);
  }

  private Issue referenceIssue(
      UUID ownerProjectId, String state, String blockedFromState, boolean archived) {
    return Issue.builder()
        .id(UUID.randomUUID())
        .projectId(ownerProjectId)
        .number(1L)
        .title("Issue")
        .state(state)
        .blockedFromState(blockedFromState)
        .blockReason(blockedFromState == null ? null : "Blocked")
        .archivedAt(archived ? now : null)
        .createdAt(now)
        .updatedAt(now)
        .build();
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
