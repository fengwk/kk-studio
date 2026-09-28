package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueEvidence;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueService.StageBudgetView;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentThreadDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueEvidenceDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO;
import fun.fengwk.kkstudio.share.project.IssueStageBudgetDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowDTO;
import fun.fengwk.kkstudio.share.project.ProjectWorkflowStateDTO;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 验证 ProjectDtoMapper 的严格双向映射与格式校验。 */
class ProjectDtoMapperTest {

  private final ProjectDtoMapper mapper = new ProjectDtoMapper();
  private final ProjectWorkflowJsonCodec codec = new ProjectWorkflowJsonCodec();

  @Test
  void testParseUuidValidAndInvalid() {
    // 测试意图：验证有效 UUID 返回小写规范对象，非法 UUID 抛出 IllegalArgumentException
    UUID expected = UUID.randomUUID();
    assertEquals(expected, ProjectDtoMapper.parseUuid(expected.toString(), "id"));
    assertEquals(expected, ProjectDtoMapper.parseUuid(expected.toString().toUpperCase(), "id"));

    assertThrows(IllegalArgumentException.class, () -> ProjectDtoMapper.parseUuid(null, "id"));
    assertThrows(IllegalArgumentException.class, () -> ProjectDtoMapper.parseUuid("", "id"));
    IllegalArgumentException invalidUuid =
        assertThrows(
            IllegalArgumentException.class, () -> ProjectDtoMapper.parseUuid("invalid-uuid", "id"));
    assertEquals("id must be a valid UUID string", invalidUuid.getMessage());
    assertNull(invalidUuid.getCause());
  }

  @Test
  void testParseNonNegativeLongValidAndInvalid() {
    // 测试意图：验证非负十进制 long 字符串解析，拒绝前导零/负数/小数/非数字/溢出
    assertEquals(0L, ProjectDtoMapper.parseNonNegativeLong("0", "version"));
    assertEquals(12345L, ProjectDtoMapper.parseNonNegativeLong("12345", "version"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDtoMapper.parseNonNegativeLong(null, "version"));
    assertThrows(
        IllegalArgumentException.class, () -> ProjectDtoMapper.parseNonNegativeLong("", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDtoMapper.parseNonNegativeLong("-1", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDtoMapper.parseNonNegativeLong("012", "version"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDtoMapper.parseNonNegativeLong("1.5", "version"));
    IllegalArgumentException overflow =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDtoMapper.parseNonNegativeLong("9999999999999999999999999999", "version"));
    assertEquals("version exceeds long range", overflow.getMessage());
    assertNull(overflow.getCause());
  }

  @Test
  void testProjectMapping() {
    // 测试意图：验证 Project 实体到 ProjectDTO 的字段正确映射
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    ProjectWorkflow workflow =
        new ProjectWorkflow(
            List.of(
                new ProjectWorkflowState(
                    ProjectStateCode.of("INIT"),
                    "Initial",
                    null,
                    null,
                    null,
                    null,
                    true,
                    List.of(ProjectStateCode.of("DONE"))),
                new ProjectWorkflowState(
                    ProjectStateCode.of("BLOCKED"),
                    "Blocked",
                    null,
                    null,
                    null,
                    null,
                    true,
                    List.of()),
                new ProjectWorkflowState(
                    ProjectStateCode.of("DONE"),
                    "Completed",
                    null,
                    null,
                    null,
                    null,
                    true,
                    List.of())));

    Project project =
        Project.builder()
            .id(id)
            .title("Title")
            .description("Desc")
            .workflowJson(codec.encode(workflow))
            .yoloEnabled(true)
            .nextIssueNumber(10L)
            .version(2L)
            .archivedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .build();

    ProjectDTO dto = mapper.toDto(project);
    assertEquals(id.toString().toLowerCase(), dto.getId());
    assertEquals("Title", dto.getTitle());
    assertEquals("Desc", dto.getDescription());
    assertNotNull(dto.getWorkflow());
    assertEquals(3, dto.getWorkflow().getStates().size());
    assertEquals(Boolean.TRUE, dto.getYoloEnabled());
    assertEquals("10", dto.getNextIssueNumber());
    assertEquals("2", dto.getVersion());
    assertEquals(now.toString(), dto.getArchivedAt());
    assertEquals(now.toString(), dto.getCreatedAt());
  }

  @Test
  void testWorkflowRoundTrip() {
    // 测试意图：验证 ProjectWorkflowDTO 与 ProjectWorkflow 双向转换
    ProjectWorkflowStateDTO init =
        ProjectWorkflowStateDTO.builder()
            .state("INIT")
            .name("Start")
            .enabled(true)
            .next(List.of("WORK"))
            .build();
    ProjectWorkflowStateDTO work =
        ProjectWorkflowStateDTO.builder()
            .state("WORK")
            .name("Working")
            .agent("coder")
            .maxRuns("5")
            .enabled(true)
            .next(List.of("DONE"))
            .build();
    ProjectWorkflowStateDTO blocked =
        ProjectWorkflowStateDTO.builder()
            .state("BLOCKED")
            .name("Blocked")
            .enabled(true)
            .next(List.of())
            .build();
    ProjectWorkflowStateDTO done =
        ProjectWorkflowStateDTO.builder()
            .state("DONE")
            .name("Done")
            .enabled(true)
            .next(List.of())
            .build();

    ProjectWorkflowDTO dto =
        ProjectWorkflowDTO.builder().states(List.of(init, work, blocked, done)).build();
    ProjectWorkflow model = mapper.toWorkflow(dto);
    assertEquals(4, model.states().size());

    ProjectWorkflowDTO mappedBack = mapper.toWorkflowDto(model);
    assertEquals(4, mappedBack.getStates().size());
    assertEquals("5", mappedBack.getStates().get(1).getMaxRuns());
    assertEquals("coder", mappedBack.getStates().get(1).getAgent());
  }

  @Test
  void testMaxRunsRejectsValuesOutsideIntRange() {
    // 测试意图：maxRuns 的领域类型是 Integer，超过 int 范围时禁止 long 截断，必须拒绝。
    IllegalArgumentException overflow =
        assertThrows(
            IllegalArgumentException.class, () -> mapper.toWorkflow(maxRunsWorkflow("2147483648")));
    assertTrue(overflow.getMessage().contains("maxRuns"));
    assertTrue(
        overflow.getMessage().contains("int") || overflow.getMessage().contains("range"),
        () -> "truncation must be rejected as a range error, but was: " + overflow.getMessage());

    IllegalArgumentException beyondLong =
        assertThrows(
            IllegalArgumentException.class,
            () -> mapper.toWorkflow(maxRunsWorkflow("9223372036854775808")));
    assertTrue(beyondLong.getMessage().contains("maxRuns"));

    IllegalArgumentException negative =
        assertThrows(
            IllegalArgumentException.class, () -> mapper.toWorkflow(maxRunsWorkflow("-1")));
    assertTrue(negative.getMessage().contains("maxRuns"));

    ProjectWorkflow accepted = mapper.toWorkflow(maxRunsWorkflow("2147483647"));
    assertEquals(Integer.MAX_VALUE, accepted.states().get(1).maxRuns());
  }

  private static ProjectWorkflowDTO maxRunsWorkflow(String maxRuns) {
    ProjectWorkflowStateDTO init =
        ProjectWorkflowStateDTO.builder()
            .state("INIT")
            .name("Start")
            .enabled(true)
            .next(List.of("WORK"))
            .build();
    ProjectWorkflowStateDTO work =
        ProjectWorkflowStateDTO.builder()
            .state("WORK")
            .name("Working")
            .agent("coder")
            .maxRuns(maxRuns)
            .enabled(true)
            .next(List.of("DONE"))
            .build();
    ProjectWorkflowStateDTO blocked =
        ProjectWorkflowStateDTO.builder()
            .state("BLOCKED")
            .name("Blocked")
            .enabled(true)
            .next(List.of())
            .build();
    ProjectWorkflowStateDTO done =
        ProjectWorkflowStateDTO.builder()
            .state("DONE")
            .name("Done")
            .enabled(true)
            .next(List.of())
            .build();
    return ProjectWorkflowDTO.builder().states(List.of(init, work, blocked, done)).build();
  }

  @Test
  void testIssueMapping() {
    // 测试意图：验证 Issue 实体到 IssueDTO 的正确映射
    UUID id = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.now();
    Issue issue =
        Issue.builder()
            .id(id)
            .projectId(projectId)
            .number(1L)
            .title("Issue 1")
            .description("Issue Desc")
            .state("BLOCKED")
            .blockedFromState("WORK")
            .blockReason("Waiting for review")
            .pauseReason("USER")
            .pauseDetail("User paused execution")
            .version(3L)
            .archivedAt(null)
            .createdAt(now)
            .updatedAt(now)
            .build();

    IssueDTO dto = mapper.toDto(issue);
    assertEquals(id.toString().toLowerCase(), dto.getId());
    assertEquals(projectId.toString().toLowerCase(), dto.getProjectId());
    assertEquals("1", dto.getNumber());
    assertEquals("BLOCKED", dto.getState());
    assertEquals("WORK", dto.getBlockedFromState());
    assertEquals("Waiting for review", dto.getBlockReason());
    assertEquals("USER", dto.getPauseReason());
    assertEquals("User paused execution", dto.getPauseDetail());
    assertEquals("3", dto.getVersion());
    assertNull(dto.getArchivedAt());
  }

  @Test
  void testActivityMapping() {
    // 测试意图：验证 IssueActivity 映射及 data JSON 解码
    UUID issueId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(5L)
            .kind(IssueActivityKind.COMMENT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Input body")
            .data("{\"action\":\"test\",\"count\":42}")
            .idempotencyKey("idem-key")
            .createdAt(now)
            .build();

    IssueActivityDTO dto = mapper.toDto(activity);
    assertEquals(issueId.toString().toLowerCase(), dto.getIssueId());
    assertEquals("5", dto.getSequence());
    assertEquals("COMMENT", dto.getKind());
    assertEquals("HUMAN", dto.getActorType());
    assertNull(dto.getActorAgentName());
    assertNull(dto.getRunId());
    assertEquals("Input body", dto.getBody());
    assertTrue(dto.getData() instanceof Map);
    assertEquals(now.toString(), dto.getCreatedAt());
  }

  @Test
  void testEvidenceMapping() {
    // 测试意图：验证 IssueEvidence 映射为 IssueEvidenceDTO
    UUID issueId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueEvidence evidence =
        IssueEvidence.builder()
            .issueId(issueId)
            .blobId(blobId)
            .name("evidence.txt")
            .actorAgentName("coder")
            .runId(runId)
            .createdAt(now)
            .build();

    IssueEvidenceDTO dto = mapper.toDto(evidence);
    assertEquals(issueId.toString().toLowerCase(), dto.getIssueId());
    assertEquals(blobId.toString().toLowerCase(), dto.getBlobId());
    assertEquals("kkstudio:/resources/" + blobId.toString().toLowerCase(), dto.getUri());
    assertEquals("evidence.txt", dto.getName());
    assertEquals("coder", dto.getActorAgentName());
    assertEquals(runId.toString().toLowerCase(), dto.getRunId());
    assertEquals(now.toString(), dto.getCreatedAt());
  }

  @Test
  void testAgentThreadMapping() {
    // 测试意图：验证 IssueAgentThread 映射为 IssueAgentThreadDTO
    UUID issueId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    IssueAgentThread thread = new IssueAgentThread(issueId, "coder", threadId);

    IssueAgentThreadDTO dto = mapper.toDto(thread);
    assertEquals(issueId.toString().toLowerCase(), dto.getIssueId());
    assertEquals("coder", dto.getAgentName());
    assertEquals(threadId.toString().toLowerCase(), dto.getThreadId());
  }

  @Test
  void testStageBudgetMapping() {
    // 测试意图：验证 StageBudgetView 映射为 IssueStageBudgetDTO
    StageBudgetView view = new StageBudgetView("WORK", 5, 2L, 1L, 4L);
    IssueStageBudgetDTO dto = mapper.toDto(view);
    assertEquals("WORK", dto.getState());
    assertEquals(5, dto.getMaxRuns());
    assertEquals("2", dto.getBudgetAfterOrdinal());
    assertEquals("1", dto.getUsedRuns());
    assertEquals("4", dto.getRemainingRuns());
  }

  @Test
  void testRunMapping() {
    // 测试意图：验证 IssueRunSummaryDTO 与 IssueRunDTO 映射
    assertNull(mapper.toSummaryDto(null));
    assertNull(mapper.toDto((IssueRun) null));

    UUID runId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID startEntryId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(1L)
            .state("WORK")
            .sessionId(sessionId)
            .threadId(threadId)
            .status(IssueRunStatus.RUNNING)
            .startEntryId(startEntryId)
            .observedActivitySequence(2L)
            .remainingExecutionMs(60000L)
            .version(4L)
            .startedAt(now)
            .build();

    IssueRunSummaryDTO summary = mapper.toSummaryDto(run, "coder");
    assertNotNull(summary);
    assertEquals("1", summary.getOrdinal());
    assertEquals("RUNNING", summary.getStatus());
    assertEquals("WORK", summary.getState());
    assertEquals("coder", summary.getAgentName());

    IssueRunDTO detail = mapper.toDto(run, "coder");
    assertNotNull(detail);
    assertEquals("coder", detail.getAgentName());
    assertEquals(sessionId.toString().toLowerCase(), detail.getSessionId());
    assertEquals(threadId.toString().toLowerCase(), detail.getThreadId());
    assertEquals("4", detail.getVersion());
    assertEquals("2", detail.getObservedActivitySequence());
    assertEquals("60000", detail.getRemainingExecutionMs());
  }
}
