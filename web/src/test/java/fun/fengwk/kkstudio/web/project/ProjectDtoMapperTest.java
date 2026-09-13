package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueDependency;
import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunOutcome;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunStatus;
import fun.fengwk.kkstudio.platform.project.model.IssueStatus;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.share.project.IssueDTO;
import fun.fengwk.kkstudio.share.project.IssueDependencyDTO;
import fun.fengwk.kkstudio.share.project.IssueInputDTO;
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
    assertThrows(
        IllegalArgumentException.class, () -> ProjectDtoMapper.parseUuid("invalid-uuid", "id"));
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
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDtoMapper.parseNonNegativeLong("9999999999999999999999999999", "version"));
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
            .coordinatorAgentName("coord")
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
    assertEquals("coord", dto.getCoordinatorAgentName());
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
            .specRevision(1L)
            .inputSequence(2L)
            .archivedAt(null)
            .createdAt(now)
            .updatedAt(now)
            .build();

    IssueDTO dto = mapper.toDto(issue);
    assertEquals(id.toString().toLowerCase(), dto.getId());
    assertEquals(projectId.toString().toLowerCase(), dto.getProjectId());
    assertEquals("1", dto.getNumber());
    assertEquals("TODO", dto.getStatus());
    assertEquals("3", dto.getVersion());
    assertNull(dto.getArchivedAt());
  }

  @Test
  void testDependencyAndInputMapping() {
    // 测试意图：验证 IssueDependency 和 IssueInput 映射
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

    IssueInput input =
        IssueInput.builder()
            .issueId(issueId)
            .sequence(5L)
            .kind(IssueInputKind.HUMAN)
            .body("Input body")
            .idempotencyKey("idem-key")
            .createdAt(now)
            .build();
    IssueInputDTO inputDto = mapper.toDto(input);
    assertEquals("5", inputDto.getSequence());
    assertEquals("HUMAN", inputDto.getKind());
    assertEquals("Input body", inputDto.getBody());
    assertEquals("idem-key", inputDto.getIdempotencyKey());
  }

  @Test
  void testRunMapping() {
    // 测试意图：验证 IssueRunSummaryDTO 与 IssueRunDTO 映射
    assertNull(mapper.toSummaryDto(null));
    assertNull(mapper.toDetailDto(null, null));

    UUID runId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();

    IssueRun run =
        IssueRun.builder()
            .id(runId)
            .issueId(issueId)
            .ordinal(1L)
            .role(IssueRunRole.EXECUTOR)
            .actorType(IssueRunActorType.AGENT)
            .agentName("coder")
            .submissionRunId(null)
            .status(IssueRunStatus.WAITING_HUMAN)
            .outcome(IssueRunOutcome.CHANGES_REQUESTED)
            .observedSpecRevision(1L)
            .observedInputSequence(2L)
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

    IssueRunDTO detail = mapper.toDetailDto(run, sessionId);
    assertNotNull(detail);
    assertEquals(sessionId.toString().toLowerCase(), detail.getSessionId());
    assertEquals("4", detail.getVersion());
    assertEquals("action-1", detail.getTerminalActionId());
  }
}
