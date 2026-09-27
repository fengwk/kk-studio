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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Project wire DTO 契约测试：精确字段集、nullable 显式序列化、未知字段拒绝以及关键聚合 DTO 的 JSON 编解码回环。 */
class ProjectDtoContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String[] REQUIRED_ALWAYS_FIELDS = {
    "ProjectDTO.archivedAt",
    "ProjectWorkflowStateDTO.agent",
    "ProjectWorkflowStateDTO.environment",
    "ProjectWorkflowStateDTO.instructions",
    "ProjectWorkflowStateDTO.maxRuns",
    "ProjectIssueSnapshotDTO.currentOrLatestRun",
    "IssueDTO.blockedFromState",
    "IssueDTO.blockReason",
    "IssueDTO.pauseReason",
    "IssueDTO.pauseDetail",
    "IssueDTO.archivedAt",
    "IssueDetailDTO.nextActivityCursor",
    "IssueDetailDTO.currentRun",
    "IssueDetailDTO.latestRun",
    "IssueActivityDTO.actorAgentName",
    "IssueActivityDTO.runId",
    "IssueActivityDTO.body",
    "IssueRunDTO.agentName",
    "IssueRunDTO.endEntryId",
    "IssueRunDTO.finalAnswerEntryId",
    "IssueRunDTO.nextState",
    "IssueRunDTO.error",
    "IssueRunDTO.endedAt",
    "IssueRunSummaryDTO.agentName",
    "IssueRunSummaryDTO.endedAt",
    "IssueEvidenceDTO.actorAgentName",
    "IssueEvidenceDTO.runId",
    "CreateProjectRequestDTO.description",
    "CreateProjectRequestDTO.yoloEnabled",
    "UpdateProjectRequestDTO.title",
    "UpdateProjectRequestDTO.description",
    "CreateIssueRequestDTO.description",
    "UpdateIssueRequestDTO.title",
    "UpdateIssueRequestDTO.description",
    "PauseIssueRequestDTO.detail",
    "StopIssueRequestDTO.detail",
    "AppendIssueActivityRequestDTO.kind"
  };

  private static final String[] DECIMAL_STRING_FIELDS = {
    "ProjectDTO.nextIssueNumber",
    "ProjectDTO.version",
    "IssueDTO.number",
    "IssueDTO.version",
    "IssueActivityDTO.sequence",
    "IssueDetailDTO.nextActivityCursor",
    "IssueRunDTO.ordinal",
    "IssueRunDTO.observedActivitySequence",
    "IssueRunDTO.remainingExecutionMs",
    "IssueRunDTO.version",
    "IssueRunSummaryDTO.ordinal",
    "IssueStageBudgetDTO.budgetAfterOrdinal",
    "IssueStageBudgetDTO.usedRuns",
    "IssueStageBudgetDTO.remainingRuns",
    "UpdateProjectRequestDTO.expectedVersion",
    "UpdateProjectWorkflowRequestDTO.expectedVersion",
    "UpdateProjectYoloRequestDTO.expectedVersion",
    "ProjectVersionRequestDTO.expectedVersion",
    "UpdateIssueRequestDTO.expectedVersion",
    "TransitionIssueRequestDTO.expectedVersion",
    "BlockIssueRequestDTO.expectedVersion",
    "RecoverIssueRequestDTO.expectedVersion",
    "PauseIssueRequestDTO.expectedVersion",
    "ResumeIssueRequestDTO.expectedVersion",
    "ReopenIssueRequestDTO.expectedVersion",
    "ResetStageBudgetRequestDTO.expectedVersion",
    "StopIssueRequestDTO.expectedVersion",
    "ArchiveIssueRequestDTO.expectedVersion",
    "UnarchiveIssueRequestDTO.expectedVersion",
    "AppendIssueActivityRequestDTO.expectedVersion"
  };

  /** 测试意图：精确验证全包 32 个 DTO 的实例字段集合，确保无多余冗余字段，且所有遗留字段被彻底移除。 */
  @Test
  void exactFieldSetsMatchCurrentContract() {
    // Response DTOs
    assertEquals(
        Set.of(
            "id",
            "title",
            "description",
            "workflow",
            "yoloEnabled",
            "nextIssueNumber",
            "version",
            "archivedAt",
            "createdAt",
            "updatedAt"),
        getInstanceFieldNames(ProjectDTO.class));

    assertEquals(Set.of("states"), getInstanceFieldNames(ProjectWorkflowDTO.class));

    assertEquals(
        Set.of(
            "state", "name", "agent", "environment", "instructions", "maxRuns", "enabled", "next"),
        getInstanceFieldNames(ProjectWorkflowStateDTO.class));

    assertEquals(Set.of("project", "issues"), getInstanceFieldNames(ProjectSnapshotDTO.class));

    assertEquals(
        Set.of("issue", "currentOrLatestRun"),
        getInstanceFieldNames(ProjectIssueSnapshotDTO.class));

    assertEquals(
        Set.of(
            "id",
            "projectId",
            "number",
            "title",
            "description",
            "state",
            "blockedFromState",
            "blockReason",
            "pauseReason",
            "pauseDetail",
            "version",
            "archivedAt",
            "createdAt",
            "updatedAt"),
        getInstanceFieldNames(IssueDTO.class));

    assertEquals(
        Set.of(
            "issue",
            "activities",
            "nextActivityCursor",
            "runs",
            "currentRun",
            "latestRun",
            "stageBudgets",
            "agentThreads"),
        getInstanceFieldNames(IssueDetailDTO.class));

    assertEquals(
        Set.of(
            "issueId",
            "sequence",
            "kind",
            "actorType",
            "actorAgentName",
            "runId",
            "body",
            "data",
            "createdAt"),
        getInstanceFieldNames(IssueActivityDTO.class));

    assertEquals(
        Set.of(
            "id",
            "issueId",
            "ordinal",
            "state",
            "agentName",
            "sessionId",
            "threadId",
            "status",
            "startEntryId",
            "endEntryId",
            "finalAnswerEntryId",
            "nextState",
            "observedActivitySequence",
            "remainingExecutionMs",
            "error",
            "version",
            "startedAt",
            "endedAt"),
        getInstanceFieldNames(IssueRunDTO.class));

    assertEquals(
        Set.of("id", "issueId", "ordinal", "state", "status", "agentName", "startedAt", "endedAt"),
        getInstanceFieldNames(IssueRunSummaryDTO.class));

    assertEquals(
        Set.of("state", "maxRuns", "budgetAfterOrdinal", "usedRuns", "remainingRuns"),
        getInstanceFieldNames(IssueStageBudgetDTO.class));

    assertEquals(
        Set.of("issueId", "agentName", "threadId"),
        getInstanceFieldNames(IssueAgentThreadDTO.class));

    assertEquals(
        Set.of("issueId", "blobId", "uri", "name", "actorAgentName", "runId", "createdAt"),
        getInstanceFieldNames(IssueEvidenceDTO.class));

    // Request DTOs
    assertEquals(
        Set.of("title", "description", "yoloEnabled"),
        getInstanceFieldNames(CreateProjectRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "title", "description"),
        getInstanceFieldNames(UpdateProjectRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "workflow"),
        getInstanceFieldNames(UpdateProjectWorkflowRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "yoloEnabled"),
        getInstanceFieldNames(UpdateProjectYoloRequestDTO.class));

    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(ProjectVersionRequestDTO.class));

    assertEquals(
        Set.of("title", "description"), getInstanceFieldNames(CreateIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "title", "description"),
        getInstanceFieldNames(UpdateIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "toState"),
        getInstanceFieldNames(TransitionIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "reason"),
        getInstanceFieldNames(BlockIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey"),
        getInstanceFieldNames(RecoverIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "reason", "detail"),
        getInstanceFieldNames(PauseIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey"),
        getInstanceFieldNames(ResumeIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey"),
        getInstanceFieldNames(ReopenIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "state", "maxRuns"),
        getInstanceFieldNames(ResetStageBudgetRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "detail"),
        getInstanceFieldNames(StopIssueRequestDTO.class));

    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(ArchiveIssueRequestDTO.class));

    assertEquals(Set.of("expectedVersion"), getInstanceFieldNames(UnarchiveIssueRequestDTO.class));

    assertEquals(
        Set.of("expectedVersion", "requestKey", "kind", "body"),
        getInstanceFieldNames(AppendIssueActivityRequestDTO.class));

    assertEquals(Set.of("uploadId"), getInstanceFieldNames(AddIssueEvidenceRequestDTO.class));
  }

  /** 测试意图：验证必须显式发射 null 的字段声明了 @JsonInclude(ALWAYS)。 */
  @Test
  void requiredNullableFieldsAreAlwaysIncluded() throws Exception {
    for (String fieldPath : REQUIRED_ALWAYS_FIELDS) {
      Field f = resolveField(fieldPath);
      JsonInclude include = f.getAnnotation(JsonInclude.class);
      assertNotNull(include, fieldPath + " must declare @JsonInclude");
      assertEquals(
          JsonInclude.Include.ALWAYS, include.value(), fieldPath + " must explicitly emit null");
    }
  }

  /** 测试意图：验证计数、版本和游标等字段在 wire DTO 上均保持 String 类型，避免 64 位浮点精度截断。 */
  @Test
  void decimalFieldsAreWireStrings() throws Exception {
    for (String fieldPath : DECIMAL_STRING_FIELDS) {
      Field f = resolveField(fieldPath);
      assertEquals(String.class, f.getType(), fieldPath + " must be a String");
    }
  }

  /** 测试意图：验证请求 DTO 与响应 DTO 通过 @JsonAnySetter 严格拒绝未知字段。 */
  @Test
  void unknownFieldsAreRejectedInRequestAndResponse() {
    // 验证请求 DTO 拒绝未知字段
    CreateProjectRequestDTO request = new CreateProjectRequestDTO();
    IllegalArgumentException reqEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> request.rejectUnknownField("unknownField", "val"));
    assertEquals("Unknown request field", reqEx.getMessage());

    // 验证响应 DTO 拒绝未知字段
    ProjectDTO response = new ProjectDTO();
    IllegalArgumentException respEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> response.rejectUnknownField("unknownField", "val"));
    assertEquals("Unknown response field", respEx.getMessage());
  }

  /** 测试意图：验证 Jackson 反序列化包含未知属性的 JSON 时会触发 @JsonAnySetter 拒绝。 */
  @Test
  void unknownFieldsAreRejectedDuringJacksonDeserialization() {
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unexpected\":123}", CreateProjectRequestDTO.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue("{\"unexpected\":123}", TransitionIssueRequestDTO.class));
    assertThrows(
        Exception.class, () -> objectMapper.readValue("{\"unexpected\":123}", ProjectDTO.class));
    assertThrows(
        Exception.class, () -> objectMapper.readValue("{\"unexpected\":123}", IssueDTO.class));
  }

  /** 测试意图：验证 ProjectWorkflowDTO 的完整 JSON 编解码回环，包含工作流状态列表与可空字段。 */
  @Test
  void projectWorkflowDtoRoundTrip() throws Exception {
    ProjectWorkflowStateDTO state1 =
        ProjectWorkflowStateDTO.builder()
            .state("BUILD")
            .name("Build State")
            .agent("engineer")
            .environment("standard")
            .instructions("build something")
            .maxRuns("5")
            .enabled(true)
            .next(List.of("REVIEW"))
            .build();
    ProjectWorkflowStateDTO state2 =
        ProjectWorkflowStateDTO.builder()
            .state("REVIEW")
            .name("Review State")
            .agent(null)
            .environment(null)
            .instructions(null)
            .maxRuns(null)
            .enabled(false)
            .next(List.of())
            .build();
    ProjectWorkflowDTO workflow =
        ProjectWorkflowDTO.builder().states(List.of(state1, state2)).build();

    String json = objectMapper.writeValueAsString(workflow);
    // @ALWAYS 字段为 null 时必须显式包含
    assertTrue(json.contains("\"agent\":null"));
    assertTrue(json.contains("\"environment\":null"));
    assertTrue(json.contains("\"instructions\":null"));
    assertTrue(json.contains("\"maxRuns\":null"));

    ProjectWorkflowDTO deserialized = objectMapper.readValue(json, ProjectWorkflowDTO.class);
    assertEquals(2, deserialized.getStates().size());
    ProjectWorkflowStateDTO readState1 = deserialized.getStates().get(0);
    assertEquals("BUILD", readState1.getState());
    assertEquals("engineer", readState1.getAgent());
    assertEquals(Boolean.TRUE, readState1.getEnabled());
    assertEquals(List.of("REVIEW"), readState1.getNext());

    ProjectWorkflowStateDTO readState2 = deserialized.getStates().get(1);
    assertEquals("REVIEW", readState2.getState());
    assertNull(readState2.getAgent());
    assertNull(readState2.getEnvironment());
    assertNull(readState2.getInstructions());
    assertNull(readState2.getMaxRuns());
    assertEquals(Boolean.FALSE, readState2.getEnabled());
  }

  /**
   * 测试意图：验证 IssueDetailDTO 复杂聚合的 JSON 编解码回环，包含 activities、runs、budgets、threads 及 untyped data 字段。
   */
  @Test
  void issueDetailDtoRoundTrip() throws Exception {
    IssueDTO issue =
        IssueDTO.builder()
            .id("11111111-1111-1111-1111-111111111111")
            .projectId("22222222-2222-2222-2222-222222222222")
            .number("1")
            .title("Issue 1")
            .description("Description")
            .state("TODO")
            .blockedFromState(null)
            .blockReason(null)
            .pauseReason(null)
            .pauseDetail(null)
            .version("0")
            .archivedAt(null)
            .createdAt("2026-09-27T10:00:00Z")
            .updatedAt("2026-09-27T10:00:00Z")
            .build();

    IssueActivityDTO activity =
        IssueActivityDTO.builder()
            .issueId(issue.getId())
            .sequence("1")
            .kind("COMMENT")
            .actorType("HUMAN")
            .actorAgentName(null)
            .runId(null)
            .body("comment body")
            .data(Map.of("key", "value", "count", 42))
            .createdAt("2026-09-27T10:00:00Z")
            .build();

    IssueRunDTO run =
        IssueRunDTO.builder()
            .id("33333333-3333-3333-3333-333333333333")
            .issueId(issue.getId())
            .ordinal("1")
            .state("BUILD")
            .agentName(null)
            .sessionId("sess-1")
            .threadId("th-1")
            .status("RUNNING")
            .startEntryId("entry-start")
            .endEntryId(null)
            .finalAnswerEntryId(null)
            .nextState(null)
            .observedActivitySequence("0")
            .remainingExecutionMs("300000")
            .error(null)
            .version("1")
            .startedAt("2026-09-27T10:00:00Z")
            .endedAt(null)
            .build();

    IssueStageBudgetDTO budget =
        IssueStageBudgetDTO.builder()
            .state("BUILD")
            .maxRuns(3)
            .budgetAfterOrdinal("0")
            .usedRuns("1")
            .remainingRuns("2")
            .build();

    IssueAgentThreadDTO thread =
        IssueAgentThreadDTO.builder()
            .issueId(issue.getId())
            .agentName("engineer")
            .threadId("th-1")
            .build();

    IssueDetailDTO detail =
        IssueDetailDTO.builder()
            .issue(issue)
            .activities(List.of(activity))
            .nextActivityCursor(null)
            .runs(List.of(run))
            .currentRun(run)
            .latestRun(run)
            .stageBudgets(List.of(budget))
            .agentThreads(List.of(thread))
            .build();

    String json = objectMapper.writeValueAsString(detail);

    // 验证 @ALWAYS nullable 字段显式发射 null
    assertTrue(json.contains("\"blockedFromState\":null"));
    assertTrue(json.contains("\"blockReason\":null"));
    assertTrue(json.contains("\"pauseReason\":null"));
    assertTrue(json.contains("\"pauseDetail\":null"));
    assertTrue(json.contains("\"archivedAt\":null"));
    assertTrue(json.contains("\"nextActivityCursor\":null"));
    assertTrue(json.contains("\"actorAgentName\":null"));
    assertTrue(json.contains("\"runId\":null"));
    assertTrue(json.contains("\"endEntryId\":null"));
    assertTrue(json.contains("\"finalAnswerEntryId\":null"));
    assertTrue(json.contains("\"nextState\":null"));
    assertTrue(json.contains("\"error\":null"));
    assertTrue(json.contains("\"endedAt\":null"));

    IssueDetailDTO readDetail = objectMapper.readValue(json, IssueDetailDTO.class);
    assertEquals(issue.getId(), readDetail.getIssue().getId());
    assertNull(readDetail.getNextActivityCursor());
    assertEquals(1, readDetail.getActivities().size());
    assertEquals("comment body", readDetail.getActivities().get(0).getBody());
    assertNotNull(readDetail.getActivities().get(0).getData());
    assertEquals(1, readDetail.getRuns().size());
    assertEquals("BUILD", readDetail.getRuns().get(0).getState());
    assertEquals(1, readDetail.getStageBudgets().size());
    assertEquals(Integer.valueOf(3), readDetail.getStageBudgets().get(0).getMaxRuns());
    assertEquals(1, readDetail.getAgentThreads().size());
    assertEquals("engineer", readDetail.getAgentThreads().get(0).getAgentName());
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
