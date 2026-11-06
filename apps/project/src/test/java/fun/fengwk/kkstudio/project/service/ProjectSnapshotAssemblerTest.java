package fun.fengwk.kkstudio.project.service;

import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertNotFound;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;

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
  private IssueEditingService issueEditingService;
  private IssueRunService issueRunService;
  private ProjectDtoMapper mapper;
  private ProjectSnapshotAssembler assembler;

  private final UUID projectId = UUID.randomUUID();
  private final Instant now = Instant.now();
  private final JsonMapper jsonMapper = httpMapper();

  @BeforeEach
  void setUp() {
    projectService = mock(ProjectService.class);
    issueEditingService = mock(IssueEditingService.class);
    issueRunService = mock(IssueRunService.class);
    mapper = new ProjectDtoMapper(new ProjectWorkflowJsonCodec(), jsonMapper);
    assembler =
        new ProjectSnapshotAssembler(projectService, issueEditingService, issueRunService, mapper);
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
    when(issueEditingService.listIssues(projectId)).thenReturn(List.of(issue1, issue2));

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
    when(issueEditingService.listAgentThreads(issue2Id))
        .thenReturn(List.of(new IssueAgentThread(issue2Id, "coder", UUID.randomUUID(), threadId)));

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
  void testReferencedStatesIncludeEveryIssueStateAndBlockedSource() {
    // 每个 Issue 的当前状态与阻塞恢复目标都必须进入引用集合，且不重复。
    when(projectService.getProject(projectId)).thenReturn(Project.builder().id(projectId).build());
    Issue active = referenceIssue(projectId, "BLOCKED", "WORK");
    Issue activeInit = referenceIssue(projectId, "INIT", null);
    Issue blockedFromReview = referenceIssue(projectId, "BLOCKED", "REVIEW");
    Issue done = referenceIssue(projectId, "DONE", null);
    Issue blockedFromWork = referenceIssue(projectId, "BLOCKED", "WORK");
    when(issueEditingService.listIssues(projectId))
        .thenReturn(List.of(active, activeInit, blockedFromReview, done, blockedFromWork));
    when(issueRunService.listRuns(active.getId())).thenReturn(List.of());
    when(issueRunService.listRuns(activeInit.getId())).thenReturn(List.of());
    when(issueRunService.listRuns(blockedFromReview.getId())).thenReturn(List.of());
    when(issueRunService.listRuns(done.getId())).thenReturn(List.of());
    when(issueRunService.listRuns(blockedFromWork.getId())).thenReturn(List.of());

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertEquals(
        List.of("BLOCKED", "DONE", "INIT", "REVIEW", "WORK"), snapshot.getReferencedStateCodes());
    assertEquals(5, snapshot.getIssues().size());
    var json = jsonMapper.valueToTree(snapshot);
    assertEquals(
        jsonMapper.valueToTree(List.of("BLOCKED", "DONE", "INIT", "REVIEW", "WORK")),
        json.get("referencedStateCodes"));
  }

  @Test
  void testEmptyProjectProvidesEmptyReferenceArray() {
    // 没有 Issue 时仍提供真实非 null 数组，而非缺失字段的兼容默认值。
    when(projectService.getProject(projectId)).thenReturn(Project.builder().id(projectId).build());
    when(issueEditingService.listIssues(projectId)).thenReturn(List.of());

    ProjectSnapshotDTO snapshot = assembler.assemble(projectId);

    assertEquals(List.of(), snapshot.getReferencedStateCodes());
    assertEquals(List.of(), snapshot.getIssues());
    var json = jsonMapper.valueToTree(snapshot);
    assertTrue(json.get("referencedStateCodes").isArray());
    assertEquals(0, json.get("referencedStateCodes").size());
    verifyNoInteractions(issueRunService);
  }

  private Issue referenceIssue(UUID ownerProjectId, String state, String blockedFromState) {
    return Issue.builder()
        .id(UUID.randomUUID())
        .projectId(ownerProjectId)
        .number(1L)
        .title("Issue")
        .state(state)
        .blockedFromState(blockedFromState)
        .blockReason(blockedFromState == null ? null : "Blocked")
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  @Test
  void testProjectNotFoundFails() {
    // 测试意图：项目不存在时抛出 404
    when(projectService.getProject(projectId)).thenReturn(null);
    assertNotFound(() -> assembler.assemble(projectId));
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
    when(issueEditingService.listIssues(projectId)).thenReturn(List.of(foreignIssue));

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
    when(issueEditingService.listIssues(projectId)).thenReturn(List.of(issue));

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

  private static JsonMapper httpMapper() {
    SimpleModule longToString = new SimpleModule("LongToStringModule");
    longToString.addSerializer(Long.class, new ToStringSerializer(Long.class));
    longToString.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    return JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
        .disable(DateTimeFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
        .changeDefaultPropertyInclusion(
            inclusion ->
                inclusion
                    .withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .addModule(longToString)
        .build();
  }
}
