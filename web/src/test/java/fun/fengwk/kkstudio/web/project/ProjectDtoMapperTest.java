package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueActivity;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.share.project.IssueActivityDTO;
import fun.fengwk.kkstudio.share.project.IssueAgentSessionDTO;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.IssueRunDTO;
import fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO;
import fun.fengwk.kkstudio.share.project.ProjectDTO;

import java.time.Instant;
import java.util.UUID;

/** 验证 ProjectDtoMapper 的严格双向映射与格式校验。 */
class ProjectDtoMapperTest {

  private final ProjectDtoMapper mapper = new ProjectDtoMapper();

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
    Project project =
        Project.builder()
            .id(id)
            .title("Title")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections(3)
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
    assertEquals(Boolean.TRUE, dto.getYoloEnabled());
    assertEquals("3", dto.getMaxReviewRejections());
    assertEquals("10", dto.getNextIssueNumber());
    assertEquals("2", dto.getVersion());
    assertEquals(now.toString(), dto.getArchivedAt());
    assertEquals(now.toString(), dto.getCreatedAt());
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
            .status(IssueStatus.TODO)
            .assigneeAgentName("assignee")
            .reviewerAgentName("reviewer")
            .version(3L)
            .archivedAt(null)
            .createdAt(now)
            .updatedAt(now)
            .build();

    IssueDTO dto = mapper.toDto(issue);
    assertEquals(id.toString().toLowerCase(), dto.getId());
    assertEquals(projectId.toString().toLowerCase(), dto.getProjectId());
    assertEquals("1", dto.getNumber());
    assertEquals("TODO", dto.getStatus());
    assertEquals("assignee", dto.getAssigneeAgentName());
    assertEquals("reviewer", dto.getReviewerAgentName());
    assertEquals("3", dto.getVersion());
    assertNull(dto.getArchivedAt());
  }

  @Test
  void testDependencyAndActivityMapping() {
    // 测试意图：验证 IssueDependency 和 IssueActivity 映射
    UUID issueId = UUID.randomUUID();
    UUID depId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueDependency dep =
        IssueDependency.builder()
            .issueId(issueId)
            .dependsOnIssueId(depId)
            .projectId(projectId)
            .createdAt(now)
            .build();
    IssueDependencyDTO depDto = mapper.toDto(dep);
    assertEquals(issueId.toString().toLowerCase(), depDto.getIssueId());
    assertEquals(depId.toString().toLowerCase(), depDto.getDependsOnIssueId());

    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issueId)
            .sequence(5L)
            .kind(IssueActivityKind.HUMAN_INPUT)
            .actorType(IssueActivityActorType.HUMAN)
            .body("Input body")
            .idempotencyKey("idem-key")
            .createdAt(now)
            .build();
    IssueActivityDTO activityDto = mapper.toDto(activity);
    assertEquals(issueId.toString().toLowerCase(), activityDto.getIssueId());
    assertEquals("5", activityDto.getSequence());
    assertEquals("HUMAN_INPUT", activityDto.getKind());
    assertEquals("HUMAN", activityDto.getActorType());
    assertNull(activityDto.getActorAgentName());
    assertNull(activityDto.getTargetRole());
    assertNull(activityDto.getRunId());
    assertNull(activityDto.getSubmissionRunId());
    assertNull(activityDto.getDecision());
    assertEquals("Input body", activityDto.getBody());
    assertEquals("idem-key", activityDto.getIdempotencyKey());
    assertEquals(now.toString(), activityDto.getCreatedAt());
  }

  @Test
  void testAgentSessionMapping() {
    // 测试意图：验证 IssueAgentSession 映射到 IssueAgentSessionDTO 及其 null 保护
    assertNull(mapper.toDto((IssueAgentSession) null, "EXECUTOR"));

    UUID bindingId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(bindingId)
            .issueId(issueId)
            .agentName("coder")
            .sessionId(sessionId)
            .threadId(threadId)
            .createdAt(now)
            .updatedAt(now)
            .build();

    IssueAgentSessionDTO dto = mapper.toDto(agentSession, "EXECUTOR");
    assertNotNull(dto);
    assertEquals(bindingId.toString().toLowerCase(), dto.getId());
    assertEquals(issueId.toString().toLowerCase(), dto.getIssueId());
    assertEquals("coder", dto.getAgentName());
    assertEquals("EXECUTOR", dto.getRole());
    assertEquals(sessionId.toString().toLowerCase(), dto.getSessionId());
    assertEquals(threadId.toString().toLowerCase(), dto.getBranchId());
    assertEquals(now.toString(), dto.getCreatedAt());
  }

  @Test
  void testRunMapping() {
    // 测试意图：验证 IssueRunSummaryDTO 与 IssueRunDTO 映射
    assertNull(mapper.toSummaryDto(null));
    assertNull(mapper.toDetailDto(null, null));

    UUID runId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID agentSessionId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .agentName("coder")
            .submissionRunId(null)
            .status(IssueRunStatus.WAITING_HUMAN)
            .outcome(IssueRunOutcome.CHANGES_REQUESTED)
            .observedActivitySequence(2L)
            .continuationCount(0)
            .maxContinuations(3)
            .deadline(now.plusSeconds(3600))
            .waitingReason("Waiting for review")
            .result("result text")
            .terminalActionId("action-1")
            .version(4L)
            .createdAt(now)
            .updatedAt(now)
            .completedAt(null)
            .build();

    IssueRunSummaryDTO summary = mapper.toSummaryDto(run);
    assertNotNull(summary);
    assertEquals("1", summary.getOrdinal());
    assertEquals("EXECUTOR", summary.getRole());
    assertEquals("WAITING_HUMAN", summary.getStatus());
    assertEquals("CHANGES_REQUESTED", summary.getOutcome());
    assertEquals("Waiting for review", summary.getWaitingReason());

    IssueAgentSession agentSession =
        IssueAgentSession.builder()
            .id(agentSessionId)
            .issueId(issueId)
            .agentName("coder")
            .sessionId(sessionId)
            .threadId(UUID.randomUUID())
            .createdAt(now)
            .build();

    IssueRunDTO detail = mapper.toDetailDto(run, agentSession);
    assertNotNull(detail);
    assertEquals(agentSessionId.toString().toLowerCase(), detail.getAgentSessionId());
    assertEquals(sessionId.toString().toLowerCase(), detail.getSessionId());
    assertEquals("coder", detail.getAgentName());
    assertEquals("4", detail.getVersion());
    assertEquals("action-1", detail.getTerminalActionId());
    assertEquals("2", detail.getObservedActivitySequence());
    assertEquals(0, detail.getContinuationCount());
    assertEquals(3, detail.getMaxContinuations());

    // detail null agentSession 保护
    IssueRunDTO detailWithoutSession = mapper.toDetailDto(run, null);
    assertNotNull(detailWithoutSession);
    assertNull(detailWithoutSession.getAgentSessionId());
    assertNull(detailWithoutSession.getSessionId());
  }
}
