package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.ModelAttemptFailureProjection;
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 映射测试：7 类 command 的严格字段规则与 canonical payload JSON、严格 decimal 解析、全部快照状态派生
 * （IDLE/CONTINUATION_DUE/MODEL_&lt;status&gt;/TOOL_&lt;status&gt;/APPLYING）与
 * create/head/stop/approval 幂等请求字段。
 */
class HarnessRuntimeWebMapperTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();

  private static final String THREAD_ID = idText(1);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  /** 稳定地把任意测试用客户端幂等键映射为 canonical UUID string（相同输入映射相同输出）。 */
  private static final Map<String, String> CLIENT_IDS = new HashMap<>();

  private static String clientId(String value) {
    return CLIENT_IDS.computeIfAbsent(value, ignored -> idText(CLIENT_IDS.size() + 100));
  }

  private static HarnessThreadCommandCreateDTO command(String type, String clientCommandId) {
    HarnessThreadCommandCreateDTO dto = new HarnessThreadCommandCreateDTO();
    dto.setType(type);
    dto.setClientCommandId(clientCommandId == null ? null : clientId(clientCommandId));
    return dto;
  }

  private static HarnessThreadCommandBatchDTO batch(HarnessThreadCommandCreateDTO... commands) {
    HarnessThreadCommandBatchDTO dto = new HarnessThreadCommandBatchDTO();
    dto.setExpectedHeadEntryId(idText(3));
    dto.setExpectedNextCommandSequence("4");
    dto.setCommands(List.of(commands));
    return dto;
  }

  private static EnvironmentBindingDTO bindingDto(String name, String workspacePath) {
    EnvironmentBindingDTO dto = new EnvironmentBindingDTO();
    dto.setName(name);
    dto.setWorkspacePath(workspacePath);
    return dto;
  }

  private static HarnessUserMessageContentDTO content(String type) {
    HarnessUserMessageContentDTO dto = new HarnessUserMessageContentDTO();
    dto.setType(type);
    return dto;
  }

  @Test
  void mapsAllSevenCommandTypesToCanonicalPayloadJson() {
    HarnessUserMessageContentDTO userText = content("TEXT");
    userText.setText("hello");
    HarnessThreadCommandCreateDTO user = command("USER_MESSAGE", "c-user");
    user.setContents(List.of(userText));

    HarnessThreadCommandCreateDTO custom = command("CUSTOM_MESSAGE", "c-custom");
    custom.setContent("rules");
    custom.setRole("SYSTEM");

    HarnessThreadCommandCreateDTO agent = command("SET_AGENT", "c-agent");
    agent.setAgentName("default-assistant");

    HarnessThreadCommandCreateDTO model = command("SET_MODEL", "c-model");
    HarnessModelSelectionDTO selection = new HarnessModelSelectionDTO();
    selection.setProviderName("openai");
    selection.setModelName("gpt-5");
    selection.setVariant("default");
    model.setModel(selection);

    HarnessThreadCommandCreateDTO tools = command("SET_ACTIVE_TOOLS", "c-tools");
    tools.setActiveTools(List.of("web_search", "code_interpreter"));

    HarnessThreadCommandCreateDTO yolo = command("SET_YOLO", "c-yolo");
    yolo.setYoloEnabled(true);

    HarnessThreadCommandCreateDTO environment = command("SET_ENVIRONMENT", "c-env");
    environment.setEnvironment(bindingDto("123e4567-e89b-12d3-a456-426614174000", "."));

    ThreadCommandBatch batch =
        HarnessRuntimeWebMapper.toCommandBatch(
            THREAD_ID, batch(user, custom, agent, model, tools, yolo, environment));

    assertEquals(7, batch.commands().size());
    assertEquals(id(1), batch.threadId());
    assertEquals(id(3), batch.expectedHeadEntryId());
    assertEquals(4L, batch.expectedNextCommandSequence());
    assertExactPayload(
        batch,
        0,
        ThreadCommandType.USER_MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");
    assertExactPayload(
        batch,
        1,
        ThreadCommandType.CUSTOM_MESSAGE,
        "{\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"rules\"}]}}");
    assertExactPayload(
        batch, 2, ThreadCommandType.SET_AGENT, "{\"agentName\":\"default-assistant\"}");
    assertExactPayload(
        batch,
        3,
        ThreadCommandType.SET_MODEL,
        "{\"model\":{\"providerName\":\"openai\",\"modelName\":\"gpt-5\",\"variant\":\"default\"}}");
    assertExactPayload(
        batch,
        4,
        ThreadCommandType.SET_ACTIVE_TOOLS,
        "{\"activeTools\":[\"web_search\",\"code_interpreter\"]}");
    assertExactPayload(batch, 5, ThreadCommandType.SET_YOLO, "{\"yoloEnabled\":true}");
    assertExactPayload(
        batch,
        6,
        ThreadCommandType.SET_ENVIRONMENT,
        "{\"environment\":{\"name\":\"123e4567-e89b-12d3-a456-426614174000\",\"workspacePath\":\".\"}}");
  }

  @Test
  void rejectsUserMessageContentShorthandAndRequiresStructuredContents() {
    // 旧 content shorthand：USER_MESSAGE 确定性拒绝（CUSTOM_MESSAGE 仍使用 content）。
    HarnessThreadCommandCreateDTO viaContent = command("USER_MESSAGE", "c-content");
    viaContent.setContent("hello");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(viaContent)));

    // 完全没有 contents：拒绝。
    HarnessThreadCommandCreateDTO missing = command("USER_MESSAGE", "c-missing");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(missing)));

    // 唯一合法形态：非空 contents 列表。
    HarnessUserMessageContentDTO text = content("TEXT");
    text.setText("hello");
    HarnessThreadCommandCreateDTO structured = command("USER_MESSAGE", "c-structured-only");
    structured.setContents(List.of(text));
    ThreadCommandBatch mapped =
        HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(structured));
    assertExactPayload(
        mapped,
        0,
        ThreadCommandType.USER_MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");
  }

  @Test
  void mapsAllStructuredUserMessageContentsInOrder() {
    HarnessUserMessageContentDTO text = content("TEXT");
    text.setText("animate this");
    HarnessUserMessageContentDTO attachment = content("ATTACHMENT");
    attachment.setUploadId(idText(1));
    HarnessThreadCommandCreateDTO user = command("USER_MESSAGE", "c-structured");
    user.setContents(List.of(text, attachment));

    ThreadCommandBatch mapped = HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(user));

    assertExactRequestPayload(
        mapped,
        0,
        ThreadCommandType.USER_MESSAGE,
        "{\"message\":{\"role\":\"USER\",\"contents\":["
            + "{\"type\":\"text\",\"text\":\"animate this\"},"
            + "{\"type\":\"attachment\",\"uploadId\":\""
            + idText(1)
            + "\"}]}}");
  }

  @Test
  void rejectsEmptyNullOrInvalidStructuredUserMessages() {
    HarnessThreadCommandCreateDTO empty = command("USER_MESSAGE", "c-empty");
    empty.setContents(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(empty)));

    HarnessThreadCommandCreateDTO nullContents = command("USER_MESSAGE", "c-null");
    nullContents.setContents(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(nullContents)));

    HarnessUserMessageContentDTO tool = content("TOOL");
    HarnessThreadCommandCreateDTO hiddenType = command("USER_MESSAGE", "c-tool");
    hiddenType.setContents(List.of(tool));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(hiddenType)));

    HarnessUserMessageContentDTO malformedUuid = content("ATTACHMENT");
    malformedUuid.setUploadId("not-a-uuid");
    HarnessThreadCommandCreateDTO malformed = command("USER_MESSAGE", "c-malformed-upload");
    malformed.setContents(List.of(malformedUuid));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(malformed)));

    HarnessUserMessageContentDTO attachmentWithText = content("ATTACHMENT");
    attachmentWithText.setUploadId(idText(1));
    attachmentWithText.setText("forbidden");
    HarnessThreadCommandCreateDTO forbiddenAttachment = command("USER_MESSAGE", "c-field");
    forbiddenAttachment.setContents(List.of(attachmentWithText));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(forbiddenAttachment)));

    HarnessUserMessageContentDTO textWithUpload = content("TEXT");
    textWithUpload.setText("hello");
    textWithUpload.setUploadId(idText(1));
    HarnessThreadCommandCreateDTO forbiddenText = command("USER_MESSAGE", "c-field-2");
    forbiddenText.setContents(List.of(textWithUpload));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(forbiddenText)));
  }

  @Test
  void setEnvironmentAcceptsNullToClearAndMapsToNullEnvironmentName() {
    HarnessThreadCommandCreateDTO environment = command("SET_ENVIRONMENT", "c-env-clear");
    ThreadCommandBatch batch =
        HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(environment));
    assertExactPayload(batch, 0, ThreadCommandType.SET_ENVIRONMENT, "{\"environment\":null}");
  }

  @Test
  void rejectsForbiddenFieldsPerDiscriminator() {
    HarnessUserMessageContentDTO userText = content("TEXT");
    userText.setText("hello");
    HarnessThreadCommandCreateDTO userWithSettings = command("USER_MESSAGE", "c-1");
    userWithSettings.setContents(List.of(userText));
    userWithSettings.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(userWithSettings)));

    HarnessThreadCommandCreateDTO userWithRole = command("USER_MESSAGE", "c-2");
    userWithRole.setContents(List.of(userText));
    userWithRole.setRole("user");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(userWithRole)));

    HarnessThreadCommandCreateDTO agentWithContent = command("SET_AGENT", "c-3");
    agentWithContent.setAgentName("default-assistant");
    agentWithContent.setContent("forbidden");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(agentWithContent)));

    HarnessThreadCommandCreateDTO yoloWithEnvironment = command("SET_YOLO", "c-4");
    yoloWithEnvironment.setYoloEnabled(false);
    yoloWithEnvironment.setEnvironment(bindingDto("123e4567-e89b-12d3-a456-426614174000", "."));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(yoloWithEnvironment)));
  }

  @Test
  void rejectsMissingRequiredFieldsPerDiscriminator() {
    HarnessThreadCommandCreateDTO noContent = command("USER_MESSAGE", "c-1");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(noContent)));

    HarnessThreadCommandCreateDTO customWithoutRole = command("CUSTOM_MESSAGE", "c-2");
    customWithoutRole.setContent("rules");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(customWithoutRole)));

    HarnessThreadCommandCreateDTO customBadRole = command("CUSTOM_MESSAGE", "c-3");
    customBadRole.setContent("rules");
    customBadRole.setRole("assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(customBadRole)));

    HarnessThreadCommandCreateDTO noModel = command("SET_MODEL", "c-4");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(noModel)));

    HarnessThreadCommandCreateDTO noClientId = command("SET_YOLO", null);
    noClientId.setYoloEnabled(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(noClientId)));

    HarnessThreadCommandCreateDTO unknownType = command("RENAME_THREAD", "c-5");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(unknownType)));
  }

  @Test
  void rejectsStrictUuidAndDecimalViolations() {
    assertThrows(
        IllegalArgumentException.class, () -> HarnessRuntimeWebMapper.parseUuid("0", "threadId"));
    assertThrows(
        IllegalArgumentException.class, () -> HarnessRuntimeWebMapper.parseUuid("1", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parseUuid("not-a-uuid", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeWebMapper.parseUuid("00000000-0000-0000-0000-000000000001 ", "threadId"));
    assertEquals(id(1), HarnessRuntimeWebMapper.parseUuid(idText(1), "threadId"));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveDecimal("0", "expectedNextCommandSequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveDecimal("01", "expectedNextCommandSequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveDecimal("-1", "expectedNextCommandSequence"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveDecimal("abc", "expectedNextCommandSequence"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeWebMapper.parsePositiveDecimal(
                "99999999999999999999", "expectedNextCommandSequence"));
    assertEquals(
        1L, HarnessRuntimeWebMapper.parsePositiveDecimal("1", "expectedNextCommandSequence"));

    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parseNonNegativeDecimal("-1", "expectedRevision"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parseNonNegativeDecimal("01", "expectedRevision"));
    assertEquals(0L, HarnessRuntimeWebMapper.parseNonNegativeDecimal("0", "expectedRevision"));
    assertEquals(3L, HarnessRuntimeWebMapper.parseNonNegativeDecimal("3", "expectedRevision"));
  }

  @Test
  void derivesIdleAndContinuationDueStatuses() {
    assertThreadStatus(HarnessRuntimeTestFixtures.idleSnapshot(), "IDLE", false);
    assertThreadStatus(
        HarnessRuntimeTestFixtures.continuationDueSnapshot(), "CONTINUATION_DUE", true);
  }

  @Test
  void derivesModelAndApplyingStatuses() {
    ModelInvocation running = model(ModelInvocationStatus.RUNNING, null);
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry()));
    ThreadSnapshot active =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(id(2)),
            path,
            List.of(),
            running,
            List.of(),
            List.of());
    assertThreadStatus(active, "MODEL_RUNNING", true);

    ModelInvocation terminal = model(ModelInvocationStatus.SUCCEEDED, null);
    ThreadSnapshot pending =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(id(2)),
            path,
            List.of(),
            terminal,
            List.of(),
            List.of());
    assertThreadStatus(pending, "APPLYING", true);
  }

  @Test
  void derivesToolStatusFromWaitingApprovalSibling() {
    ModelInvocation succeeded = model(ModelInvocationStatus.SUCCEEDED, id(4));
    when(succeeded.result()).thenReturn(HarnessRuntimeTestFixtures.toolCallResponse());
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry(),
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.assistantEntry()));
    ThreadSnapshot snapshot =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(id(4)),
            path,
            List.of(),
            succeeded,
            List.of(HarnessRuntimeTestFixtures.waitingApprovalTool()),
            List.of());
    assertThreadStatus(snapshot, "TOOL_WAITING_APPROVAL", true);
  }

  @Test
  void mapsSnapshotToExactDtoJson() throws Exception {
    ModelInvocation succeeded = model(ModelInvocationStatus.SUCCEEDED, id(4));
    when(succeeded.result()).thenReturn(HarnessRuntimeTestFixtures.toolCallResponse());
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry(),
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.assistantEntry()));
    ThreadSnapshot snapshot =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(id(4)),
            path,
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            succeeded,
            List.of(HarnessRuntimeTestFixtures.waitingApprovalTool()),
            List.of());

    HarnessThreadSnapshotDTO dto = HarnessRuntimeWebMapper.toSnapshotDto(snapshot);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));

    assertEquals("3", json.path("revision").asText());
    JsonNode thread = json.path("thread");
    assertEquals(idText(1), thread.path("threadId").asText());
    assertEquals(idText(1), thread.path("sessionId").asText());
    assertEquals(idText(4), thread.path("headEntryId").asText());
    assertTrue(thread.path("yoloEnabled").asBoolean());
    assertEquals(4, thread.path("nextCommandSequence").asLong());
    assertEquals("3", thread.path("revision").asText());
    assertEquals("TOOL_WAITING_APPROVAL", thread.path("status").asText());
    assertTrue(thread.path("processing").asBoolean());
    assertEquals("env-1", thread.path("branchSettings").path("environment").path("name").asText());
    assertEquals(
        ".", thread.path("branchSettings").path("environment").path("workspacePath").asText());
    assertEquals("default-assistant", thread.path("branchSettings").path("agentName").asText());
    assertEquals("gpt-5", thread.path("branchSettings").path("model").path("modelName").asText());
    assertEquals("web_search", thread.path("branchSettings").path("activeTools").get(0).asText());

    assertEquals(4, json.path("entries").size());
    assertEquals("ROOT", json.path("entries").get(0).path("entryType").asText());
    assertEquals(idText(1), json.path("entries").get(0).path("entryId").asText());
    assertEquals(idText(1), json.path("entries").get(0).path("sessionId").asText());
    assertTrue(json.path("entries").get(0).path("parentEntryId").isNull());
    assertEquals("TURN_START", json.path("entries").get(1).path("entryType").asText());
    assertEquals("MESSAGE", json.path("entries").get(2).path("entryType").asText());
    assertTrue(
        json.path("entries")
            .get(3)
            .path("payloadJson")
            .asText()
            .contains("\"role\":\"ASSISTANT\""));

    assertEquals(1, json.path("queuedCommands").size());
    assertEquals("QUEUED", json.path("queuedCommands").get(0).path("state").asText());
    assertEquals("USER_MESSAGE", json.path("queuedCommands").get(0).path("type").asText());
    assertEquals(idText(50), json.path("queuedCommands").get(0).path("clientCommandId").asText());
    assertTrue(
        json.path("queuedCommands")
            .get(0)
            .path("payloadJson")
            .asText()
            .contains("\"text\":\"hello\""));

    assertEquals(idText(10), json.path("modelInvocation").path("id").asText());
    assertEquals("SUCCEEDED", json.path("modelInvocation").path("status").asText());
    assertEquals(idText(4), json.path("modelInvocation").path("resultEntryId").asText());
    assertTrue(json.path("modelInvocation").path("resultJson").asText().contains("\"toolCalls\""));

    assertEquals(1, json.path("toolInvocations").size());
    JsonNode tool = json.path("toolInvocations").get(0);
    assertEquals(idText(100), tool.path("id").asText());
    assertEquals(idText(10), tool.path("modelInvocationId").asText());
    assertEquals(idText(4), tool.path("assistantEntryId").asText());
    assertEquals(0, tool.path("ordinal").asInt());
    assertEquals("WAITING_APPROVAL", tool.path("status").asText());
    assertEquals("call-1", tool.path("toolCallId").asText());
    assertEquals("web_search", tool.path("toolName").asText());
    assertEquals("1.0", tool.path("toolVersion").asText());
    assertEquals("web_search", tool.path("rendererKey").asText());
    assertEquals("PLATFORM", tool.path("toolType").asText());
    assertTrue(tool.path("environment").isNull());
    assertEquals("{}", tool.path("argumentsJson").asText());
    assertTrue(tool.path("approvalJson").asText().contains("\"required\":true"));
    assertTrue(tool.path("approvalJson").asText().contains("\"decision\":null"));
  }

  @Test
  void mapsAttemptFailureSequenceAsACanonicalDecimalString() throws Exception {
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.turnStartEntry()));
    ModelAttemptFailureProjection failure =
        new ModelAttemptFailureProjection(
            id(10),
            id(2),
            id(2),
            1,
            Long.MAX_VALUE,
            "partial answer",
            "partial thinking",
            new ModelInvocationError(ProviderErrorKind.TRANSIENT, "provider unavailable"),
            HarnessRuntimeTestFixtures.NOW,
            HarnessRuntimeTestFixtures.NOW.plusSeconds(1));
    ThreadSnapshot snapshot =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(id(2)),
            path,
            List.of(),
            model(ModelInvocationStatus.READY, null),
            List.of(),
            List.of(failure));

    JsonNode json =
        MAPPER.readTree(MAPPER.writeValueAsString(HarnessRuntimeWebMapper.toSnapshotDto(snapshot)));
    JsonNode sequence = json.path("modelAttemptFailures").get(0).path("sequence");

    assertTrue(sequence.isTextual());
    assertEquals(Long.toString(Long.MAX_VALUE), sequence.asText());
  }

  @Test
  void mapsIdleSnapshotWithProcessingFalseAndEmptyLists() throws Exception {
    HarnessThreadSnapshotDTO dto =
        HarnessRuntimeWebMapper.toSnapshotDto(HarnessRuntimeTestFixtures.idleSnapshot());
    dto.getThread().getBranchSettings().setEnvironment(null);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));
    assertEquals("IDLE", json.path("thread").path("status").asText());
    assertFalse(json.path("thread").path("processing").asBoolean());
    assertTrue(json.path("thread").path("branchSettings").path("environment").isNull());
    assertTrue(json.path("entries").get(0).path("parentEntryId").isNull());
    assertTrue(json.path("modelInvocation").isNull());
    assertTrue(json.path("toolInvocations").isArray());
    assertEquals(0, json.path("toolInvocations").size());
    assertEquals(1, json.path("entries").size());
  }

  @Test
  void mapsCreateHeadStopAndApprovalRequestFields() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    settings.setEnvironment(null);
    settings.setAgentName("default-assistant");
    HarnessModelSelectionDTO selection = new HarnessModelSelectionDTO();
    selection.setProviderName("openai");
    selection.setModelName("gpt-5");
    selection.setVariant("default");
    settings.setModel(selection);
    settings.setActiveTools(List.of("web_search"));
    create.setBranchSettings(settings);
    create.setYoloEnabled(false);

    CreateThreadCommand created = HarnessRuntimeWebMapper.toCreateThreadCommand(create);
    assertNull(created.branchSettings().environment());
    assertEquals("default-assistant", created.branchSettings().agentName());
    assertEquals("gpt-5", created.branchSettings().model().modelName());
    assertEquals(List.of("web_search"), created.branchSettings().activeTools());
    assertFalse(created.yoloEnabled());

    HarnessThreadHeadUpdateDTO head = new HarnessThreadHeadUpdateDTO();
    head.setTargetEntryId(idText(2));
    head.setExpectedRevision("3");
    MoveHeadCommand move = HarnessRuntimeWebMapper.toMoveHeadCommand(THREAD_ID, head);
    assertEquals(id(1), move.threadId());
    assertEquals(id(2), move.targetEntryId());
    assertEquals(3L, move.expectedRevision());

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setStopRequestId(idText(9));
    stop.setExpectedRevision("3");
    StopCommand stopCommand = HarnessRuntimeWebMapper.toStopCommand(THREAD_ID, stop);
    assertEquals(id(1), stopCommand.threadId());
    assertEquals(id(9), stopCommand.stopRequestId());
    assertEquals(3L, stopCommand.expectedRevision());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecision("ALLOW");
    approval.setDecisionId(idText(1));
    approval.setActor("alice");
    approval.setReason("looks safe");
    ToolApprovalCommand allow =
        HarnessRuntimeWebMapper.toToolApprovalCommand(THREAD_ID, idText(100), approval);
    assertEquals(id(1), allow.threadId());
    assertEquals(id(100), allow.toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, allow.decision());
    assertEquals(id(1), allow.decisionId());
    assertEquals("alice", allow.actor());
    assertEquals("looks safe", allow.reason());

    approval.setDecision("DENY");
    assertEquals(
        ToolApprovalDecision.DENIED,
        HarnessRuntimeWebMapper.toToolApprovalCommand(THREAD_ID, idText(100), approval).decision());

    approval.setDecision("MAYBE");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toToolApprovalCommand(THREAD_ID, idText(100), approval));
  }

  @Test
  void rejectsInvalidBranchSettingsAndBatchShape() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    create.setBranchSettings(new HarnessBranchSettingsDTO());
    create.setYoloEnabled(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCreateThreadCommand(create));

    HarnessThreadCommandBatchDTO empty = new HarnessThreadCommandBatchDTO();
    empty.setExpectedHeadEntryId("3");
    empty.setExpectedNextCommandSequence("4");
    empty.setCommands(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, empty));

    HarnessThreadCommandCreateDTO duplicate = command("SET_YOLO", "same-id");
    duplicate.setYoloEnabled(true);
    HarnessThreadCommandCreateDTO duplicateAgain = command("SET_AGENT", "same-id");
    duplicateAgain.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, batch(duplicate, duplicateAgain)));

    HarnessThreadCommandBatchDTO nullCursor = new HarnessThreadCommandBatchDTO();
    nullCursor.setExpectedHeadEntryId("3");
    nullCursor.setCommands(List.of(duplicate));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch(THREAD_ID, nullCursor));
  }

  private static ModelInvocation model(ModelInvocationStatus status, UUID resultEntryId) {
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.id()).thenReturn(id(10));
    when(model.threadId()).thenReturn(id(1));
    when(model.turnStartEntryId()).thenReturn(id(2));
    when(model.basisHeadEntryId()).thenReturn(id(2));
    when(model.status()).thenReturn(status);
    when(model.attempt()).thenReturn(1);
    when(model.resultEntryId()).thenReturn(resultEntryId);
    when(model.streamCheckpoint()).thenReturn(null);
    when(model.result()).thenReturn(null);
    when(model.error()).thenReturn(null);
    when(model.createdAt()).thenReturn(HarnessRuntimeTestFixtures.NOW);
    when(model.updatedAt()).thenReturn(HarnessRuntimeTestFixtures.NOW);
    return model;
  }

  private static void assertThreadStatus(
      ThreadSnapshot snapshot, String status, boolean processing) {
    HarnessThreadDTO dto = HarnessRuntimeWebMapper.toThreadDto(snapshot);
    assertEquals(status, dto.getStatus());
    assertEquals(processing, dto.getProcessing());
  }

  private static void assertExactPayload(
      ThreadCommandBatch batch, int index, ThreadCommandType type, String expectedJson) {
    assertEquals(type, batch.commands().get(index).payload().type());
    assertEquals(expectedJson, COMMAND_PAYLOADS.encode(batch.commands().get(index).payload()));
  }

  /** 请求形态断言：瞬时 ATTACHMENT 内容只能按 raw request 编码（durable codec 会拒绝）。 */
  private static void assertExactRequestPayload(
      ThreadCommandBatch batch, int index, ThreadCommandType type, String expectedJson) {
    ThreadCommandPayload payload = batch.commands().get(index).payload();
    assertEquals(type, payload.type());
    assertEquals(expectedJson, COMMAND_PAYLOADS.encodeRequest(payload));
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        batch.commands().get(index).requestHash(),
        "requestHash must cover the raw request form with ordered contents/uploadId");
  }
}
