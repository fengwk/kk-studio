package fun.fengwk.kkstudio.share.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

/** 验证 Project/Issue 传输 DTO 的契约约束： 包含 nullable 显式序列化、decimal string 字段类型、未知字段拒绝以及 JSON 编解码。 */
class ProjectDtoContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "ProjectDTO.archivedAt",
    "CreateProjectRequestDTO.description",
    "UpdateProjectRequestDTO.description",
    "ProjectCommandRequestDTO.threadId",
    "ProjectCommandRequestDTO.expectedHeadEntryId",
    "ProjectCommandRequestDTO.expectedNextCommandSequence",
    "IssueDTO.assigneeAgentName",
    "IssueDTO.reviewerAgentName",
    "IssueDTO.archivedAt",
    "CreateIssueRequestDTO.description",
    "CreateIssueRequestDTO.assigneeAgentName",
    "CreateIssueRequestDTO.reviewerAgentName",
    "CreateIssueRequestDTO.initialStatus",
    "UpdateIssueRequestDTO.description",
    "UpdateIssueRequestDTO.assigneeAgentName",
    "UpdateIssueRequestDTO.reviewerAgentName",
    "IssueInputDTO.idempotencyKey",
    "AppendIssueInputRequestDTO.idempotencyKey",
    "IssueRunSummaryDTO.agentName",
    "IssueRunSummaryDTO.submissionRunId",
    "IssueRunSummaryDTO.outcome",
    "IssueRunSummaryDTO.waitingReason",
    "IssueRunSummaryDTO.createdAt",
    "IssueRunSummaryDTO.completedAt",
    "IssueRunDTO.agentName",
    "IssueRunDTO.submissionRunId",
    "IssueRunDTO.outcome",
    "IssueRunDTO.deadline",
    "IssueRunDTO.waitingReason",
    "IssueRunDTO.result",
    "IssueRunDTO.terminalActionId",
    "IssueRunDTO.completedAt",
    "IssueRunDTO.sessionId",
    "ReviewIssueRequestDTO.verification",
    "ReviewIssueRequestDTO.terminalActionId",
    "ReviewIssueRequestDTO.observedSpecRevision",
    "ReviewIssueRequestDTO.observedInputSequence",
    "CancelIssueRequestDTO.reason",
    "ProjectIssueSnapshotDTO.currentOrLatestRun",
    "ProjectSnapshotDTO.coordinatorSessionId",
    "ProjectSnapshotDTO.coordinatorSession",
    "ProjectSnapshotDTO.coordinatorThread",
    "IssueDetailDTO.currentRun",
    "IssueDetailDTO.latestRun"
  };

  private static final String[] DECIMAL_STRING_FIELDS = {
    "ProjectDTO.nextIssueNumber",
    "ProjectDTO.version",
    "UpdateProjectRequestDTO.expectedVersion",
    "ProjectArchiveRequestDTO.expectedVersion",
    "ProjectUnarchiveRequestDTO.expectedVersion",
    "IssueDTO.number",
    "IssueDTO.version",
    "IssueDTO.specRevision",
    "IssueDTO.inputSequence",
    "UpdateIssueRequestDTO.expectedVersion",
    "ChangeIssueStatusRequestDTO.expectedVersion",
    "AddIssueDependencyRequestDTO.expectedVersion",
    "IssueInputDTO.sequence",
    "IssueRunSummaryDTO.ordinal",
    "IssueRunDTO.ordinal",
    "IssueRunDTO.observedSpecRevision",
    "IssueRunDTO.observedInputSequence",
    "IssueRunDTO.version",
    "CancelIssueRequestDTO.expectedVersion",
    "ArchiveIssueRequestDTO.expectedVersion",
    "UnarchiveIssueRequestDTO.expectedVersion"
  };

  @Test
  void requiredNullableFieldsAreAlwaysIncluded() throws Exception {
    // 测试意图：验证 nullable 字段声明 @JsonInclude(ALWAYS)，保证前端 TS 解码时字段确定性存在为 null 而非缺失
    for (String fieldPath : REQUIRED_NULLABLE_FIELDS) {
      Field f = resolveField(fieldPath);
      JsonInclude include = f.getAnnotation(JsonInclude.class);
      assertNotNull(include, fieldPath + " must declare @JsonInclude");
      assertEquals(
          JsonInclude.Include.ALWAYS, include.value(), fieldPath + " must explicitly emit null");
    }
  }

  @Test
  void decimalFieldsAreWireStrings() throws Exception {
    // 测试意图：验证所有 long 类型数据库列在 wire DTO 上均表示为规范 String，防止前端 JS 64-bit float 精度截断
    for (String fieldPath : DECIMAL_STRING_FIELDS) {
      Field f = resolveField(fieldPath);
      assertEquals(String.class, f.getType(), fieldPath + " must be a String");
    }
  }

  @Test
  void unknownFieldsAreRejectedInRequests() {
    // 测试意图：验证严格协议设计，直接调用 rejectUnknownField 抛出 IllegalArgumentException
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreateProjectRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpdateProjectRequestDTO().rejectUnknownField("unknownField", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectArchiveRequestDTO().rejectUnknownField("unknownField", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectUnarchiveRequestDTO().rejectUnknownField("unknownField", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectCommandRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CreateIssueRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChangeIssueStatusRequestDTO().rejectUnknownField("unknownField", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AddIssueDependencyRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AppendIssueInputRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReviewIssueRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CancelIssueRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RetryIssueRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ArchiveIssueRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UnarchiveIssueRequestDTO().rejectUnknownField("extra", "junk"));
  }

  @Test
  void roundTripSerializationPreservesNulls() throws Exception {
    // 测试意图：验证序列化时 nullable 属性正确输出为 null，而非被省略
    ProjectDTO project =
        ProjectDTO.builder()
            .id("11111111-1111-1111-1111-111111111111")
            .title("Title")
            .description("Desc")
            .coordinatorAgentName("Coord")
            .nextIssueNumber("1")
            .version("0")
            .archivedAt(null)
            .createdAt("2026-09-13T10:00:00Z")
            .updatedAt("2026-09-13T10:00:00Z")
            .build();

    String json = objectMapper.writeValueAsString(project);
    assertTrue(json.contains("\"archivedAt\":null"));

    ProjectDTO read = objectMapper.readValue(json, ProjectDTO.class);
    assertEquals(project.getId(), read.getId());
    assertNull(read.getArchivedAt());

    ProjectSnapshotDTO snapshot =
        ProjectSnapshotDTO.builder()
            .project(project)
            .issues(List.of())
            .dependencies(List.of())
            .coordinatorSessionId(null)
            .coordinatorSession(null)
            .coordinatorThread(null)
            .build();

    String snapJson = objectMapper.writeValueAsString(snapshot);
    assertTrue(snapJson.contains("\"coordinatorSessionId\":null"));
    assertTrue(snapJson.contains("\"coordinatorSession\":null"));
    assertTrue(snapJson.contains("\"coordinatorThread\":null"));
  }

  private static Field resolveField(String fieldPath) throws Exception {
    String[] parts = fieldPath.split("\\.");
    Class<?> clazz = Class.forName("fun.fengwk.kkstudio.share.project." + parts[0]);
    return clazz.getDeclaredField(parts[1]);
  }
}
