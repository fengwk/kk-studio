package fun.fengwk.kkstudio.share.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Harness Runtime HTTP DTO 契约：严格字段、严格字符串 cursor 与 compact/snapshot 投影。 */
class HarnessRuntimeDtoContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 显式宽松的 Jackson2 mapper：关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}，复现 wire 上真实 mapper（Jackson3 HTTP
   * mapper 与 convention4j 共享 Jackson2 bean）默认忽略未知字段的语义，用来证明请求体的 fail-closed 来自 DTO 注解而非 mapper
   * 默认严格性。
   */
  private static final ObjectMapper LENIENT_MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void commandBatchDtoExposesOwnerTargetAndImmutableCommandDefaults() {
    HarnessCommandBatchDTO batch = new HarnessCommandBatchDTO();
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    batch.setOwner(owner);
    batch.setTarget(target);

    assertEquals(owner, batch.getOwner());
    assertEquals(target, batch.getTarget());
    assertTrue(batch.getCommands().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> batch.getCommands().add(null));
  }

  @Test
  void targetDtoTracksFieldPresenceSoExplicitNullCannotBypassForbiddenChecks() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("THREAD");
    target.setSessionId(null);
    target.setThreadId("00000000-0000-0000-0000-000000000001");
    target.setExpectedHeadEntryId("00000000-0000-0000-0000-000000000002");
    target.setExpectedNextCommandSequence("3");

    assertTrue(target.hasSessionIdField());
    assertNull(target.getSessionId());
    assertEquals("THREAD", target.getType());
    assertEquals("3", target.getExpectedNextCommandSequence());
  }

  @Test
  void branchSettingsTracksRequiredNullableEnvironmentNamePresence() throws Exception {
    HarnessBranchSettingsDTO explicitNull =
        MAPPER.readValue(
            """
            {"agentName":"assistant","model":{"providerName":"p","modelName":"m","variant":"v"},
             "environmentName":null}
            """,
            HarnessBranchSettingsDTO.class);
    HarnessBranchSettingsDTO missing =
        MAPPER.readValue(
            """
            {"agentName":"assistant","model":{"providerName":"p","modelName":"m","variant":"v"}}
            """,
            HarnessBranchSettingsDTO.class);

    assertTrue(explicitNull.hasEnvironmentNameField());
    assertNull(explicitNull.getEnvironmentName());
    assertFalse(missing.hasEnvironmentNameField());
  }

  /**
   * Thread 投影的根 Thread 没有执行父关系：{@code parentThreadId} 是"必需 nullable"字段——HTTP JSON 必须显式输出
   * null，前端据此判定执行树根；若依赖全局省略 null 的默认行为，"字段缺失"就会与"未知/旧版本"混淆。
   */
  @Test
  void threadDtoAlwaysSerializesNullableParentThreadId() throws Exception {
    HarnessThreadDTO root = new HarnessThreadDTO();
    root.setThreadId("00000000-0000-0000-0000-000000000001");
    root.setParentThreadId(null);

    String json = MAPPER.writeValueAsString(root);

    assertTrue(json.contains("\"parentThreadId\":null"), json);
    // HTTP 层全局省略 null 值，因此该字段必须自带 ALWAYS 覆盖，才能在根 Thread 上显式输出 null。
    Field parentThreadId = HarnessThreadDTO.class.getDeclaredField("parentThreadId");
    JsonInclude include = parentThreadId.getAnnotation(JsonInclude.class);
    assertNotNull(include);
    assertEquals(JsonInclude.Include.ALWAYS, include.value());
  }

  @Test
  void commandDtoDoesNotExposeCustomMessagePayloadFields() {
    HarnessCommandCreateDTO command = new HarnessCommandCreateDTO();
    command.setType("USER_MESSAGE");
    command.setIdempotencyKey("00000000-0000-0000-0000-000000000001");
    command.setContents(List.of());

    assertEquals("USER_MESSAGE", command.getType());
    assertEquals("00000000-0000-0000-0000-000000000001", command.getIdempotencyKey());
    assertTrue(command.hasContentsField());
  }

  @Test
  void unknownFieldsAreRejectedAtEveryNewRequestBoundary() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"owner":{},"target":{},"commands":[],"unknown":true}
                """,
                HarnessCommandBatchDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"THREAD","threadId":"00000000-0000-0000-0000-000000000001",
                 "expectedHeadEntryId":"00000000-0000-0000-0000-000000000002",
                 "expectedNextCommandSequence":"3","unknown":true}
                """,
                HarnessCommandTargetDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"SET_MODEL","idempotencyKey":"00000000-0000-0000-0000-000000000001",
                 "model":{"providerName":"p","modelName":"m","variant":"v","unknown":true}}
                """,
                HarnessCommandCreateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"expectedVersion":"0","unknown":true}
                """,
                HarnessThreadCompactDTO.class));
  }

  /**
   * 测试意图：wire 上的真实 mapper（Spring Boot 的 Jackson3 HTTP mapper 与 convention4j 的共享 Jackson2
   * bean）默认都忽略未知 字段，因此请求体的 fail-closed 只能来自 DTO 自己的 {@code @JsonAnySetter}。这里用显式关闭 {@code
   * FAIL_ON_UNKNOWN_PROPERTIES} 的 Jackson2 mapper 复现同样的宽松语义：无注解的对照类型确实静默忽略未知字段，而 {@link
   * HarnessToolApprovalDTO} 仍然拒绝，且拒绝原因就是 DTO 自己抛出的 {@link HarnessRequestFormatException}（而非 mapper
   * 的默认严格性）。
   */
  @Test
  void lenientMapperStillRejectsUnknownToolApprovalFieldViaDtoAnnotation() throws Exception {
    String body =
        """
        {"decision":"ALLOW","decisionId":"00000000-0000-0000-0000-000000000001","operator":"admin"}
        """;

    // 对照：同一个宽松 mapper 对没有 @JsonAnySetter 的类型静默忽略未知字段。
    assertEquals("ALLOW", LENIENT_MAPPER.readValue(body, UnknownFieldTolerantDto.class).decision);

    Exception error =
        assertThrows(
            Exception.class, () -> LENIENT_MAPPER.readValue(body, HarnessToolApprovalDTO.class));
    assertTrue(
        hasCause(error, HarnessRequestFormatException.class),
        "unknown field rejection must originate from the DTO any-setter");
  }

  /**
   * 测试意图：如实固定 decision/decisionId 当前的两层边界。无法强转为 String 的 JSON token（对象/数组）在 DTO 边界即被拒绝； 标量
   * token（数字）仍被 Jackson 强转为 String，没有 {@code requireJsonString} 守卫，其 fail-closed 由 HTTP 解析层保证
   * （{@code HarnessRuntimeRequestMapper} 的 decision 白名单与 canonical UUID 校验，见 web 审批端点测试）。
   */
  @Test
  void toolApprovalDecisionAndDecisionIdPinMalformedTokenBoundary() throws Exception {
    assertThrows(
        Exception.class,
        () ->
            LENIENT_MAPPER.readValue(
                """
                {"decision":{"nested":1},"decisionId":"00000000-0000-0000-0000-000000000001"}
                """,
                HarnessToolApprovalDTO.class));
    assertThrows(
        Exception.class,
        () ->
            LENIENT_MAPPER.readValue(
                """
                {"decision":"ALLOW","decisionId":[1]}
                """,
                HarnessToolApprovalDTO.class));

    HarnessToolApprovalDTO coerced =
        LENIENT_MAPPER.readValue(
            "{\"decision\":42,\"decisionId\":1}", HarnessToolApprovalDTO.class);
    assertEquals("42", coerced.getDecision());
    assertEquals("1", coerced.getDecisionId());
  }

  /** 沿 cause 链判定异常来源，用于区分 DTO 的 @JsonAnySetter 拒绝与 mapper 自身失败。 */
  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  /** 对照类型：不声明 @JsonAnySetter 的普通数据对象，用于证明宽松 mapper 本身并不拒绝未知字段。 */
  static class UnknownFieldTolerantDto {

    private String decision;

    public String getDecision() {
      return decision;
    }

    public void setDecision(String decision) {
      this.decision = decision;
    }
  }

  @Test
  void uuidAndCursorFieldsRejectJsonNumbersInsteadOfCoercingToStrings() {
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"THREAD","threadId":1,
                 "expectedHeadEntryId":"00000000-0000-0000-0000-000000000002",
                 "expectedNextCommandSequence":"3"}
                """,
                HarnessCommandTargetDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"expectedVersion":1}
                """,
                HarnessThreadCompactDTO.class));
  }

  @Test
  void nameUpdateDtoRejectsUnknownFieldsAndNonStringPrimitives() {
    // 意图：rename 请求体严格边界必须由共享 DTO 直接拒绝（未知字段 + 非字符串 JSON primitive），不进入 mapper。
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":"ok","unknown":true}
                """,
                HarnessNameUpdateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":42}
                """, HarnessNameUpdateDTO.class));
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"name":["list"]}
                """,
                HarnessNameUpdateDTO.class));
  }

  @Test
  void nameUpdateDtoAcceptsOnlyTheNameFieldAndRoundsItBack() throws Exception {
    // 意图：唯一的合法 {name} 请求体必须精确 round-trip（长度/空白语义留给 Core 权威）。
    HarnessNameUpdateDTO dto =
        MAPPER.readValue(
            """
            {"name":"  display name  "}
            """,
            HarnessNameUpdateDTO.class);
    assertEquals("  display name  ", dto.getName());
  }

  @Test
  void sessionSummaryPreviewAlwaysEmitsNull() throws Exception {
    // 意图：Session 尚无 USER Entry 时，required-nullable 预览仍必须出现在全局 NON_NULL 的 HTTP wire 中。
    Field preview = HarnessSessionSummaryDTO.class.getDeclaredField("firstMessagePreview");
    JsonInclude include = preview.getAnnotation(JsonInclude.class);

    assertNotNull(include);
    assertEquals(JsonInclude.Include.ALWAYS, include.value());
  }

  @Test
  void snapshotCarriesManualCompactionAvailabilityAndNullableReason() {
    HarnessThreadSnapshotDTO snapshot = new HarnessThreadSnapshotDTO();
    HarnessManualCompactionDTO availability = new HarnessManualCompactionDTO();
    availability.setAvailable(true);
    availability.setDisabledReason(null);
    snapshot.setManualCompaction(availability);

    assertTrue(snapshot.getManualCompaction().getAvailable());
    assertNull(snapshot.getManualCompaction().getDisabledReason());
  }

  @Test
  void toolInvocationDtoExposesFlatEnvironmentId() throws Exception {
    Field environmentId = ToolInvocationDTO.class.getDeclaredField("environmentId");
    assertEquals(String.class, environmentId.getType());
    assertThrows(
        NoSuchFieldException.class, () -> ToolInvocationDTO.class.getDeclaredField("environment"));
  }

  /**
   * 测试意图：GOAL 命令的 text 是 required-nullable 字段——显式 null（清除）与 canonical 文本都必须精确保留，字段缺失可被精确判定， 非字符串
   * text 必须拒绝。
   */
  @Test
  void commandDtoTracksRequiredNullableGoalTextPresence() throws Exception {
    HarnessCommandCreateDTO cleared =
        MAPPER.readValue(
            """
            {"type":"GOAL","idempotencyKey":"00000000-0000-0000-0000-000000000001","text":null}
            """,
            HarnessCommandCreateDTO.class);
    HarnessCommandCreateDTO set =
        MAPPER.readValue(
            """
            {"type":"GOAL","idempotencyKey":"00000000-0000-0000-0000-000000000002",
             "text":"ship the release"}
            """,
            HarnessCommandCreateDTO.class);
    HarnessCommandCreateDTO missing =
        MAPPER.readValue(
            """
            {"type":"GOAL","idempotencyKey":"00000000-0000-0000-0000-000000000003"}
            """,
            HarnessCommandCreateDTO.class);

    assertTrue(cleared.hasTextField());
    assertNull(cleared.getText());
    assertTrue(set.hasTextField());
    assertEquals("ship the release", set.getText());
    assertFalse(missing.hasTextField());
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"type":"GOAL","idempotencyKey":"00000000-0000-0000-0000-000000000004","text":7}
                """,
                HarnessCommandCreateDTO.class));
  }

  /** 测试意图：branch settings 的 goal 是显式 nullable 投影——null（未设置或已清除）与 {id,text} 快照都必须精确往返，未知子字段必须拒绝。 */
  @Test
  void branchSettingsDtoExposesNullableGoalSnapshot() throws Exception {
    HarnessBranchSettingsDTO cleared =
        MAPPER.readValue(
            """
            {"agentName":"assistant","model":{"providerName":"p","modelName":"m","variant":"v"},
             "environmentName":null,"goal":null}
            """,
            HarnessBranchSettingsDTO.class);
    HarnessBranchSettingsDTO set =
        MAPPER.readValue(
            """
            {"agentName":"assistant","model":{"providerName":"p","modelName":"m","variant":"v"},
             "environmentName":null,
             "goal":{"id":"00000000-0000-0000-0000-00000000002a","text":"ship it"}}
            """,
            HarnessBranchSettingsDTO.class);

    assertNull(cleared.getGoal());
    assertNotNull(set.getGoal());
    assertEquals("00000000-0000-0000-0000-00000000002a", set.getGoal().getId());
    assertEquals("ship it", set.getGoal().getText());
    assertThrows(
        Exception.class,
        () ->
            MAPPER.readValue(
                """
                {"agentName":"assistant","model":{"providerName":"p","modelName":"m","variant":"v"},
                 "environmentName":null,
                 "goal":{"id":"00000000-0000-0000-0000-00000000002a","text":"ship it","extra":1}}
                """,
                HarnessBranchSettingsDTO.class));
  }

  @Test
  void branchSettingsDtoExposesNullableEnvironmentName() throws Exception {
    // 三字段完整快照：environmentName 必须是显式 nullable String 字段，且旧 workspacePath 不得回归。
    Field environmentName = HarnessBranchSettingsDTO.class.getDeclaredField("environmentName");
    assertEquals(String.class, environmentName.getType());
    assertThrows(
        NoSuchFieldException.class,
        () -> HarnessBranchSettingsDTO.class.getDeclaredField("workspacePath"));
  }

  @Test
  void systemPromptPreviewIsReplacedByStructuredModelRequestDebug() throws Exception {
    // 意图：展示文本预览被删除，Debug 只暴露结构化投影（预览与冻结请求两种视图）。
    assertThrows(
        ClassNotFoundException.class,
        () ->
            Class.forName(
                HarnessModelSelectionDTO.class.getPackageName()
                    + ".HarnessSystemPromptPreviewDTO"));

    Map<String, Class<?>> expected =
        Map.ofEntries(
            Map.entry("kind", String.class),
            Map.entry("generatedAt", Instant.class),
            Map.entry("model", HarnessModelSelectionDTO.class),
            Map.entry("environmentName", String.class),
            Map.entry("systemInstruction", String.class),
            Map.entry("tools", List.class),
            Map.entry("skills", List.class),
            Map.entry("subagents", List.class),
            Map.entry("cacheControl", HarnessModelRequestDebugDTO.CacheControlDTO.class),
            Map.entry("planningError", String.class),
            Map.entry("frozenInvocation", HarnessModelRequestDebugDTO.FrozenInvocationDTO.class));
    for (Map.Entry<String, Class<?>> entry : expected.entrySet()) {
      assertEquals(
          entry.getValue(),
          HarnessModelRequestDebugDTO.class.getDeclaredField(entry.getKey()).getType(),
          entry.getKey());
    }
  }

  @Test
  void modelRequestDebugMarksRequiredNullableFacts() throws Exception {
    // 意图：未选择 Environment、planning 失败、无活动 Invocation 都是真实状态，字段必须显式发射 null 而不是缺席。
    for (String fieldName :
        List.of("environmentName", "cacheControl", "planningError", "frozenInvocation")) {
      assertRequiredNullable(HarnessModelRequestDebugDTO.class, fieldName);
    }
    // 嵌套事实同理：无固定 requiredEnvironmentId、SENT 工具无过滤原因、未检查/未安装的 commit 与 NONE 缓存都没有 key。
    for (String fieldName : List.of("requiredEnvironmentId", "filterReason")) {
      assertRequiredNullable(HarnessModelRequestDebugDTO.ToolDTO.class, fieldName);
    }
    for (String fieldName : List.of("observedHeadCommit", "installedCommit")) {
      assertRequiredNullable(HarnessModelRequestDebugDTO.SkillDTO.class, fieldName);
    }
    assertRequiredNullable(HarnessModelRequestDebugDTO.CacheControlDTO.class, "affinityKey");
  }

  private static void assertRequiredNullable(Class<?> dtoClass, String fieldName) throws Exception {
    JsonInclude include = dtoClass.getDeclaredField(fieldName).getAnnotation(JsonInclude.class);
    assertNotNull(include, dtoClass.getSimpleName() + "." + fieldName);
    assertEquals(
        JsonInclude.Include.ALWAYS, include.value(), dtoClass.getSimpleName() + "." + fieldName);
  }

  @Test
  void modelRequestDebugProjectsToolAndSkillDeliveryFacts() throws Exception {
    // 意图：Tool 的发送/过滤状态与 EnvironmentSupport、Skill 的交付路径与 commit 都是结构化字段。
    HarnessModelRequestDebugDTO.ToolDTO sent = new HarnessModelRequestDebugDTO.ToolDTO();
    sent.setName("read");
    sent.setDescription("Read a file.");
    sent.setInputSchemaJson("{\"type\":\"object\"}");
    sent.setEnvironmentSupport(EnvironmentSupportDTO.OPTIONAL);
    sent.setProvenance("builtin:read");
    sent.setState("SENT");
    sent.setFilterReason(null);

    HarnessModelRequestDebugDTO.ToolDTO filtered = new HarnessModelRequestDebugDTO.ToolDTO();
    filtered.setName("bash");
    filtered.setEnvironmentSupport(EnvironmentSupportDTO.REQUIRED);
    filtered.setState("FILTERED");
    filtered.setFilterReason("ENVIRONMENT_NOT_SELECTED");

    HarnessModelRequestDebugDTO.SkillDTO skill = new HarnessModelRequestDebugDTO.SkillDTO();
    skill.setPackageName("dev-tools");
    skill.setName("dev");
    skill.setDelivery("LOCAL");
    skill.setPath("/data/skills/dev-tools/dev/SKILL.md");
    skill.setCurrentCommit("0123456789012345678901234567890123456789");
    skill.setInstalledCommit("0123456789012345678901234567890123456789");
    skill.setPromptXml("<skill name=\"dev\"/>");

    HarnessModelRequestDebugDTO debug = new HarnessModelRequestDebugDTO();
    debug.setKind("NEXT_REQUEST_PREVIEW");
    debug.setEnvironmentName(null);
    debug.setPlanningError(null);
    debug.setTools(List.of(sent, filtered));
    debug.setSkills(List.of(skill));

    String json = MAPPER.writeValueAsString(debug);
    assertEquals("NEXT_REQUEST_PREVIEW", debug.getKind());
    assertTrue(json.contains("\"state\":\"FILTERED\""), json);
    assertTrue(json.contains("\"filterReason\":\"ENVIRONMENT_NOT_SELECTED\""), json);
    assertTrue(json.contains("\"environmentSupport\":\"OPTIONAL\""), json);
    assertTrue(json.contains("\"delivery\":\"LOCAL\""), json);
    assertTrue(json.contains("\"environmentName\":null"), json);
    assertTrue(json.contains("\"planningError\":null"), json);
  }

  @Test
  void modelRequestDebugNeverCarriesCredentialsOrBodies() {
    // 意图：Debug 只展示可读 JSON 与去敏事实，绝不含凭据、Authorization 头、存储地址或 Base64 正文。
    List<Class<?>> debugClasses =
        List.of(
            HarnessModelRequestDebugDTO.class,
            HarnessModelRequestDebugDTO.ToolDTO.class,
            HarnessModelRequestDebugDTO.SkillDTO.class,
            HarnessModelRequestDebugDTO.SubagentDTO.class,
            HarnessModelRequestDebugDTO.CacheControlDTO.class,
            HarnessModelRequestDebugDTO.FrozenInvocationDTO.class);
    List<String> forbidden =
        List.of(
            "token",
            "secret",
            "credential",
            "authorization",
            "apikey",
            "password",
            "base64",
            "bucket");
    for (Class<?> debugClass : debugClasses) {
      for (Field field : debugClass.getDeclaredFields()) {
        String normalized = field.getName().toLowerCase();
        for (String forbiddenPart : forbidden) {
          assertFalse(
              normalized.contains(forbiddenPart),
              () -> debugClass.getSimpleName() + " must not expose " + field.getName());
        }
      }
    }
  }
}
