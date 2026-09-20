package fun.fengwk.kkstudio.share.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    // 意图：未选择 Environment 与 planning 失败都是真实状态，字段必须显式发射 null 而不是缺席。
    for (String fieldName : List.of("environmentName", "planningError")) {
      JsonInclude include =
          HarnessModelRequestDebugDTO.class
              .getDeclaredField(fieldName)
              .getAnnotation(JsonInclude.class);
      assertNotNull(include, fieldName);
      assertEquals(JsonInclude.Include.ALWAYS, include.value(), fieldName);
    }
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
