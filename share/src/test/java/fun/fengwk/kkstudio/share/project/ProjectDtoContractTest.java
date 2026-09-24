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
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 验证 Project/Issue 传输 DTO 的契约约束：精确字段集、nullable 显式序列化、decimal string 字段类型、未知字段拒绝以及 JSON 编解码。 */
class ProjectDtoContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String[] REQUIRED_NULLABLE_FIELDS = {
    "ProjectDTO.archivedAt",
    "CreateProjectRequestDTO.description",
    "CreateProjectRequestDTO.yoloEnabled",
    "CreateProjectRequestDTO.maxReviewRejections",
    "UpdateProjectRequestDTO.title",
    "UpdateProjectRequestDTO.description",
    "UpdateProjectRequestDTO.yoloEnabled",
    "UpdateProjectRequestDTO.maxReviewRejections",
    "ProjectIssueSnapshotDTO.currentOrLatestRun",
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
    "IssueActivityDTO.actorAgentName",
    "IssueActivityDTO.targetRole",
    "IssueActivityDTO.runId",
    "IssueActivityDTO.submissionRunId",
    "IssueActivityDTO.decision",
    "IssueActivityDTO.idempotencyKey",
    "IssueDetailDTO.nextActivityCursor",
    "IssueDetailDTO.currentRun",
    "IssueDetailDTO.latestRun",
    "IssueEvidenceDTO.name",
    "IssueEvidenceDTO.runId",
    "IssueRunSummaryDTO.agentName",
    "IssueRunSummaryDTO.submissionRunId",
    "IssueRunSummaryDTO.outcome",
    "IssueRunSummaryDTO.waitingReason",
    "IssueRunSummaryDTO.createdAt",
    "IssueRunSummaryDTO.completedAt",
    "IssueRunDTO.agentName",
    "IssueRunDTO.agentSessionId",
    "IssueRunDTO.sessionId",
    "IssueRunDTO.submissionRunId",
    "IssueRunDTO.outcome",
    "IssueRunDTO.deadline",
    "IssueRunDTO.waitingReason",
    "IssueRunDTO.result",
    "IssueRunDTO.terminalActionId",
    "IssueRunDTO.completedAt",
    "AppendIssueActivityRequestDTO.kind",
    "AppendIssueActivityRequestDTO.targetRole",
    "AppendIssueActivityRequestDTO.idempotencyKey",
    "CancelIssueRequestDTO.reason",
    "RecoverIssueRequestDTO.comment",
    "ReviewIssueRequestDTO.idempotencyKey"
  };

  private static final String[] DECIMAL_STRING_FIELDS = {
    "ProjectDTO.maxReviewRejections",
    "ProjectDTO.nextIssueNumber",
    "ProjectDTO.version",
    "UpdateProjectRequestDTO.expectedVersion",
    "ProjectArchiveRequestDTO.expectedVersion",
    "ProjectUnarchiveRequestDTO.expectedVersion",
    "IssueDTO.number",
    "IssueDTO.version",
    "ProjectIssueSnapshotDTO.reviewRejectionCount",
    "UpdateIssueRequestDTO.expectedVersion",
    "ChangeIssueStatusRequestDTO.expectedVersion",
    "BlockIssueRequestDTO.expectedVersion",
    "RecoverIssueRequestDTO.expectedVersion",
    "AddIssueDependencyRequestDTO.expectedVersion",
    "IssueActivityDTO.sequence",
    "IssueDetailDTO.nextActivityCursor",
    "IssueRunSummaryDTO.ordinal",
    "IssueRunDTO.ordinal",
    "IssueRunDTO.observedActivitySequence",
    "IssueRunDTO.version",
    "CancelIssueRequestDTO.expectedVersion",
    "ArchiveIssueRequestDTO.expectedVersion",
    "UnarchiveIssueRequestDTO.expectedVersion"
  };

  @Test
  void exactFieldSetsMatchCurrentContract() {
    // 测试意图：精确断言各个 DTO 的实例字段集合等于期望集合，确保已删除字段被彻底移除、新字段全部落地且无意外冗余字段
    assertEquals(
        Set.of(
            "id",
            "title",
            "description",
            "yoloEnabled",
            "maxReviewRejections",
            "nextIssueNumber",
            "version",
            "archivedAt",
            "createdAt",
            "updatedAt"),
        getInstanceFieldNames(ProjectDTO.class));

    assertEquals(
        Set.of("title", "description", "yoloEnabled", "maxReviewRejections"),
        getInstanceFieldNames(CreateProjectRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "title", "description", "yoloEnabled", "maxReviewRejections"),
        getInstanceFieldNames(UpdateProjectRequestDTO.class));

    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(ProjectArchiveRequestDTO.class));
    assertEquals(
        Set.of("expectedVersion"), getInstanceFieldNames(ProjectUnarchiveRequestDTO.class));

    assertEquals(
        Set.of("project", "issues", "dependencies"),
        getInstanceFieldNames(ProjectSnapshotDTO.class));
    assertEquals(
        Set.of("issue", "blocked", "reviewRejectionCount", "currentOrLatestRun"),
        getInstanceFieldNames(ProjectIssueSnapshotDTO.class));

    assertEquals(
        Set.of(
            "id",
            "projectId",
            "number",
            "title",
            "description",
            "status",
            "assigneeAgentName",
            "reviewerAgentName",
            "version",
            "archivedAt",
            "createdAt",
            "updatedAt"),
        getInstanceFieldNames(IssueDTO.class));

    assertEquals(
        Set.of("title", "description", "assigneeAgentName", "reviewerAgentName", "initialStatus"),
        getInstanceFieldNames(CreateIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "title", "description", "assigneeAgentName", "reviewerAgentName"),
        getInstanceFieldNames(UpdateIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "status"),
        getInstanceFieldNames(ChangeIssueStatusRequestDTO.class));
    assertEquals(
        Set.of("expectedVersion", "reason"), getInstanceFieldNames(BlockIssueRequestDTO.class));
    assertEquals(
        Set.of("expectedVersion", "toBacklog", "comment"),
        getInstanceFieldNames(RecoverIssueRequestDTO.class));
    assertEquals(
        Set.of("dependsOnIssueId", "expectedVersion"),
        getInstanceFieldNames(AddIssueDependencyRequestDTO.class));
    assertEquals(
        Set.of("issueId", "dependsOnIssueId", "projectId", "createdAt"),
        getInstanceFieldNames(IssueDependencyDTO.class));

    assertEquals(
        Set.of("kind", "body", "targetRole", "idempotencyKey"),
        getInstanceFieldNames(AppendIssueActivityRequestDTO.class));
    assertEquals(
        Set.of("decision", "reason", "idempotencyKey"),
        getInstanceFieldNames(ReviewIssueRequestDTO.class));
    assertEquals(
        Set.of("expectedVersion", "reason"), getInstanceFieldNames(CancelIssueRequestDTO.class));
    assertEquals(Set.of("idempotencyKey"), getInstanceFieldNames(RetryIssueRequestDTO.class));
    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(ArchiveIssueRequestDTO.class));
    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(UnarchiveIssueRequestDTO.class));

    assertEquals(
        Set.of(
            "issueId",
            "sequence",
            "kind",
            "actorType",
            "actorAgentName",
            "targetRole",
            "runId",
            "submissionRunId",
            "decision",
            "body",
            "idempotencyKey",
            "createdAt"),
        getInstanceFieldNames(IssueActivityDTO.class));

    assertEquals(
        Set.of("id", "issueId", "agentName", "role", "sessionId", "branchId", "createdAt"),
        getInstanceFieldNames(IssueAgentSessionDTO.class));

    assertEquals(
        Set.of(
            "issue",
            "blocked",
            "dependencies",
            "sessions",
            "activities",
            "evidence",
            "nextActivityCursor",
            "runs",
            "currentRun",
            "latestRun"),
        getInstanceFieldNames(IssueDetailDTO.class));

    assertEquals(
        Set.of("issueId", "blobId", "uri", "origin", "name", "runId", "publishedAt"),
        getInstanceFieldNames(IssueEvidenceDTO.class));
    assertEquals(Set.of("uploadId"), getInstanceFieldNames(AddIssueEvidenceRequestDTO.class));

    assertEquals(
        Set.of(
            "id",
            "issueId",
            "ordinal",
            "role",
            "agentName",
            "agentSessionId",
            "sessionId",
            "submissionRunId",
            "status",
            "outcome",
            "observedActivitySequence",
            "continuationCount",
            "maxContinuations",
            "deadline",
            "waitingReason",
            "result",
            "terminalActionId",
            "version",
            "createdAt",
            "updatedAt",
            "completedAt"),
        getInstanceFieldNames(IssueRunDTO.class));

    assertEquals(
        Set.of(
            "id",
            "issueId",
            "ordinal",
            "role",
            "agentName",
            "submissionRunId",
            "status",
            "outcome",
            "waitingReason",
            "createdAt",
            "completedAt"),
        getInstanceFieldNames(IssueRunSummaryDTO.class));
  }

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
    // 测试意图：验证所有 long/decimal 类型列在 wire DTO 上均表示为规范 String，防止前端 JS 64-bit float 精度截断
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
        () -> new CreateIssueRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UpdateIssueRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChangeIssueStatusRequestDTO().rejectUnknownField("unknownField", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BlockIssueRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoverIssueRequestDTO().rejectUnknownField("unexpected", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AddIssueDependencyRequestDTO().rejectUnknownField("extra", "junk"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AppendIssueActivityRequestDTO().rejectUnknownField("extra", "junk"));
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
    assertThrows(
        IllegalArgumentException.class,
        () -> new AddIssueEvidenceRequestDTO().rejectUnknownField("extra", "junk"));
  }

  @Test
  void unknownFieldsAreRejectedDuringJacksonDeserialization() {
    // 测试意图：验证 Jackson 反序列化包含未知属性的 JSON 时会触发 @JsonAnySetter 拒绝
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", CreateProjectRequestDTO.class));
    assertThrows(
        Exception.class,
        () ->
            objectMapper.readValue("{\"unknownField\":123}", AppendIssueActivityRequestDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", BlockIssueRequestDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", RecoverIssueRequestDTO.class));
    assertThrows(
        Exception.class, () -> objectMapper.readValue("{\"unknownField\":123}", ProjectDTO.class));
    assertThrows(
        Exception.class, () -> objectMapper.readValue("{\"unknownField\":123}", IssueDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", IssueActivityDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", IssueAgentSessionDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", IssueEvidenceDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unknownField\":123}", AddIssueEvidenceRequestDTO.class));
  }

  @Test
  void roundTripSerializationPreservesNulls() throws Exception {
    // 测试意图：验证序列化时 nullable 属性正确输出为 null，而非被省略，反序列化可完整还原
    ProjectDTO project =
        ProjectDTO.builder()
            .id("11111111-1111-1111-1111-111111111111")
            .title("Title")
            .description("Desc")
            .yoloEnabled(true)
            .maxReviewRejections("3")
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
    assertEquals("3", read.getMaxReviewRejections());
    assertEquals(Boolean.TRUE, read.getYoloEnabled());

    IssueDTO issue =
        IssueDTO.builder()
            .id("22222222-2222-2222-2222-222222222222")
            .projectId(project.getId())
            .number("1")
            .title("Issue Title")
            .description("Issue Desc")
            .status("TODO")
            .assigneeAgentName(null)
            .reviewerAgentName(null)
            .version("0")
            .archivedAt(null)
            .createdAt("2026-09-13T10:00:00Z")
            .updatedAt("2026-09-13T10:00:00Z")
            .build();
    IssueEvidenceDTO humanEvidence =
        IssueEvidenceDTO.builder()
            .issueId(issue.getId())
            .blobId("33333333-3333-3333-3333-333333333333")
            .uri("kkstudio:/resources/33333333-3333-3333-3333-333333333333")
            .origin("HUMAN")
            .name("report.txt")
            .runId(null)
            .publishedAt("2026-09-13T10:00:00Z")
            .build();
    String evidenceJson = objectMapper.writeValueAsString(humanEvidence);
    // 人工证据没有发布 Run：nullable 字段必须显式为 null 而不是缺失，前端解码形态才确定
    assertTrue(evidenceJson.contains("\"runId\":null"));
    IssueEvidenceDTO readEvidence = objectMapper.readValue(evidenceJson, IssueEvidenceDTO.class);
    assertNull(readEvidence.getRunId());
    assertEquals("report.txt", readEvidence.getName());
    assertEquals("HUMAN", readEvidence.getOrigin());

    String issueJson = objectMapper.writeValueAsString(issue);
    assertTrue(issueJson.contains("\"assigneeAgentName\":null"));
    assertTrue(issueJson.contains("\"reviewerAgentName\":null"));
    assertTrue(issueJson.contains("\"archivedAt\":null"));

    IssueDTO readIssue = objectMapper.readValue(issueJson, IssueDTO.class);
    assertEquals(issue.getId(), readIssue.getId());
    assertNull(readIssue.getAssigneeAgentName());
    assertNull(readIssue.getReviewerAgentName());
    assertNull(readIssue.getArchivedAt());

    ProjectSnapshotDTO snapshot =
        ProjectSnapshotDTO.builder()
            .project(project)
            .issues(
                List.of(
                    ProjectIssueSnapshotDTO.builder()
                        .issue(issue)
                        .blocked(false)
                        .reviewRejectionCount("2")
                        .currentOrLatestRun(null)
                        .build()))
            .dependencies(List.of())
            .build();

    String snapJson = objectMapper.writeValueAsString(snapshot);
    assertTrue(snapJson.contains("\"currentOrLatestRun\":null"));
    // 打回次数以 decimal string 出现在 wire 上，前端 BLOCKED 卡片可直接读取 current / maxReviewRejections
    assertTrue(snapJson.contains("\"reviewRejectionCount\":\"2\""));
    ProjectSnapshotDTO readSnapshot = objectMapper.readValue(snapJson, ProjectSnapshotDTO.class);
    assertEquals(1, readSnapshot.getIssues().size());
    assertEquals("2", readSnapshot.getIssues().get(0).getReviewRejectionCount());
    assertNull(readSnapshot.getIssues().get(0).getCurrentOrLatestRun());
    // 字段声明 @JsonInclude(ALWAYS)，null 也必须显式输出，保证严格解码器不会因字段缺失失败
    String snapJsonWithNullCount =
        objectMapper.writeValueAsString(
            ProjectSnapshotDTO.builder()
                .project(project)
                .issues(
                    List.of(
                        ProjectIssueSnapshotDTO.builder()
                            .issue(issue)
                            .blocked(false)
                            .currentOrLatestRun(null)
                            .build()))
                .dependencies(List.of())
                .build());
    assertTrue(snapJsonWithNullCount.contains("\"reviewRejectionCount\":null"));

    IssueDetailDTO detail =
        IssueDetailDTO.builder()
            .issue(issue)
            .blocked(false)
            .dependencies(List.of())
            .sessions(List.of())
            .activities(List.of())
            .nextActivityCursor(null)
            .runs(List.of())
            .currentRun(null)
            .latestRun(null)
            .build();
    String detailJson = objectMapper.writeValueAsString(detail);
    assertTrue(detailJson.contains("\"nextActivityCursor\":null"));
    assertTrue(detailJson.contains("\"currentRun\":null"));
    assertTrue(detailJson.contains("\"latestRun\":null"));

    IssueDetailDTO readDetail = objectMapper.readValue(detailJson, IssueDetailDTO.class);
    assertNull(readDetail.getNextActivityCursor());
    assertNull(readDetail.getCurrentRun());
    assertNull(readDetail.getLatestRun());

    IssueActivityDTO activity =
        IssueActivityDTO.builder()
            .issueId(issue.getId())
            .sequence("1")
            .kind("COMMENT")
            .actorType("HUMAN")
            .actorAgentName(null)
            .targetRole(null)
            .runId(null)
            .submissionRunId(null)
            .decision(null)
            .body("comment body")
            .idempotencyKey(null)
            .createdAt("2026-09-13T10:00:00Z")
            .build();
    String actJson = objectMapper.writeValueAsString(activity);
    assertTrue(actJson.contains("\"actorAgentName\":null"));
    assertTrue(actJson.contains("\"targetRole\":null"));
    assertTrue(actJson.contains("\"runId\":null"));
    assertTrue(actJson.contains("\"submissionRunId\":null"));
    assertTrue(actJson.contains("\"decision\":null"));
    assertTrue(actJson.contains("\"idempotencyKey\":null"));

    IssueActivityDTO readAct = objectMapper.readValue(actJson, IssueActivityDTO.class);
    assertNull(readAct.getActorAgentName());
    assertNull(readAct.getTargetRole());

    IssueRunDTO run =
        IssueRunDTO.builder()
            .id("33333333-3333-3333-3333-333333333333")
            .issueId(issue.getId())
            .ordinal("1")
            .role("EXECUTOR")
            .agentName(null)
            .agentSessionId(null)
            .sessionId(null)
            .submissionRunId(null)
            .status("RUNNING")
            .outcome(null)
            .observedActivitySequence("0")
            .continuationCount(0)
            .maxContinuations(3)
            .deadline(null)
            .waitingReason(null)
            .result(null)
            .terminalActionId(null)
            .version("1")
            .createdAt("2026-09-13T10:00:00Z")
            .updatedAt("2026-09-13T10:00:00Z")
            .completedAt(null)
            .build();
    String runJson = objectMapper.writeValueAsString(run);
    assertTrue(runJson.contains("\"agentName\":null"));
    assertTrue(runJson.contains("\"agentSessionId\":null"));
    assertTrue(runJson.contains("\"sessionId\":null"));
    assertTrue(runJson.contains("\"submissionRunId\":null"));
    assertTrue(runJson.contains("\"outcome\":null"));
    assertTrue(runJson.contains("\"deadline\":null"));
    assertTrue(runJson.contains("\"waitingReason\":null"));
    assertTrue(runJson.contains("\"result\":null"));
    assertTrue(runJson.contains("\"terminalActionId\":null"));
    assertTrue(runJson.contains("\"completedAt\":null"));

    IssueRunDTO readRun = objectMapper.readValue(runJson, IssueRunDTO.class);
    assertNull(readRun.getAgentName());
    assertNull(readRun.getAgentSessionId());
    assertNull(readRun.getSessionId());
    assertNull(readRun.getOutcome());
  }

  private static Set<String> getInstanceFieldNames(Class<?> clazz) {
    return Arrays.stream(clazz.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  private static Field resolveField(String fieldPath) throws Exception {
    String[] parts = fieldPath.split("\\.");
    Class<?> clazz = Class.forName("fun.fengwk.kkstudio.share.project." + parts[0]);
    return clazz.getDeclaredField(parts[1]);
  }
}
