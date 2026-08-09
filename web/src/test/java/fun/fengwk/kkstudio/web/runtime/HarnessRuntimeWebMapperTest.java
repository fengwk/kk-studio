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
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
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

import java.util.List;

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

  private static HarnessThreadCommandCreateDTO command(String type, String clientCommandId) {
    HarnessThreadCommandCreateDTO dto = new HarnessThreadCommandCreateDTO();
    dto.setType(type);
    dto.setClientCommandId(clientCommandId);
    return dto;
  }

  private static HarnessThreadCommandBatchDTO batch(HarnessThreadCommandCreateDTO... commands) {
    HarnessThreadCommandBatchDTO dto = new HarnessThreadCommandBatchDTO();
    dto.setExpectedHeadEntryId("3");
    dto.setExpectedNextCommandSequence("4");
    dto.setCommands(List.of(commands));
    return dto;
  }

  @Test
  void mapsAllSevenCommandTypesToCanonicalPayloadJson() {
    HarnessThreadCommandCreateDTO user = command("USER_MESSAGE", "c-user");
    user.setContent("hello");

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
    environment.setEnvironmentName("123e4567-e89b-12d3-a456-426614174000");

    ThreadCommandBatch batch =
        HarnessRuntimeWebMapper.toCommandBatch(
            "1", batch(user, custom, agent, model, tools, yolo, environment));

    assertEquals(7, batch.commands().size());
    assertEquals(1L, batch.threadId());
    assertEquals(3L, batch.expectedHeadEntryId());
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
        "{\"environmentName\":\"123e4567-e89b-12d3-a456-426614174000\"}");
  }

  @Test
  void setEnvironmentAcceptsNullToClearAndMapsToNullEnvironmentName() {
    HarnessThreadCommandCreateDTO environment = command("SET_ENVIRONMENT", "c-env-clear");
    ThreadCommandBatch batch = HarnessRuntimeWebMapper.toCommandBatch("1", batch(environment));
    assertExactPayload(batch, 0, ThreadCommandType.SET_ENVIRONMENT, "{\"environmentName\":null}");
  }

  @Test
  void rejectsForbiddenFieldsPerDiscriminator() {
    HarnessThreadCommandCreateDTO userWithSettings = command("USER_MESSAGE", "c-1");
    userWithSettings.setContent("hello");
    userWithSettings.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(userWithSettings)));

    HarnessThreadCommandCreateDTO userWithRole = command("USER_MESSAGE", "c-2");
    userWithRole.setContent("hello");
    userWithRole.setRole("user");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(userWithRole)));

    HarnessThreadCommandCreateDTO agentWithContent = command("SET_AGENT", "c-3");
    agentWithContent.setAgentName("default-assistant");
    agentWithContent.setContent("forbidden");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(agentWithContent)));

    HarnessThreadCommandCreateDTO yoloWithEnvironment = command("SET_YOLO", "c-4");
    yoloWithEnvironment.setYoloEnabled(false);
    yoloWithEnvironment.setEnvironmentName("123e4567-e89b-12d3-a456-426614174000");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(yoloWithEnvironment)));
  }

  @Test
  void rejectsMissingRequiredFieldsPerDiscriminator() {
    HarnessThreadCommandCreateDTO noContent = command("USER_MESSAGE", "c-1");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(noContent)));

    HarnessThreadCommandCreateDTO customWithoutRole = command("CUSTOM_MESSAGE", "c-2");
    customWithoutRole.setContent("rules");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(customWithoutRole)));

    HarnessThreadCommandCreateDTO customBadRole = command("CUSTOM_MESSAGE", "c-3");
    customBadRole.setContent("rules");
    customBadRole.setRole("assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(customBadRole)));

    HarnessThreadCommandCreateDTO noModel = command("SET_MODEL", "c-4");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(noModel)));

    HarnessThreadCommandCreateDTO noClientId = command("SET_YOLO", null);
    noClientId.setYoloEnabled(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(noClientId)));

    HarnessThreadCommandCreateDTO unknownType = command("RENAME_THREAD", "c-5");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(unknownType)));
  }

  @Test
  void rejectsStrictDecimalViolations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveId("0", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveId("01", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveId("-1", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveId("abc", "threadId"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.parsePositiveId("99999999999999999999", "threadId"));
    assertEquals(1L, HarnessRuntimeWebMapper.parsePositiveId("1", "threadId"));

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
            HarnessRuntimeTestFixtures.thread(2), path, List.of(), running, List.of());
    assertThreadStatus(active, "MODEL_RUNNING", true);

    ModelInvocation terminal = model(ModelInvocationStatus.SUCCEEDED, null);
    ThreadSnapshot pending =
        new ThreadSnapshot(
            HarnessRuntimeTestFixtures.thread(2), path, List.of(), terminal, List.of());
    assertThreadStatus(pending, "APPLYING", true);
  }

  @Test
  void derivesToolStatusFromWaitingApprovalSibling() {
    ModelInvocation succeeded = model(ModelInvocationStatus.SUCCEEDED, 4L);
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
            HarnessRuntimeTestFixtures.thread(4),
            path,
            List.of(),
            succeeded,
            List.of(HarnessRuntimeTestFixtures.waitingApprovalTool()));
    assertThreadStatus(snapshot, "TOOL_WAITING_APPROVAL", true);
  }

  @Test
  void mapsSnapshotToExactDtoJson() throws Exception {
    ModelInvocation succeeded = model(ModelInvocationStatus.SUCCEEDED, 4L);
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
            HarnessRuntimeTestFixtures.thread(4),
            path,
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            succeeded,
            List.of(HarnessRuntimeTestFixtures.waitingApprovalTool()));

    HarnessThreadSnapshotDTO dto = HarnessRuntimeWebMapper.toSnapshotDto(snapshot);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));

    assertEquals("3", json.path("revision").asText());
    JsonNode thread = json.path("thread");
    assertEquals("1", thread.path("threadId").asText());
    assertEquals("1", thread.path("sessionId").asText());
    assertEquals("4", thread.path("headEntryId").asText());
    assertTrue(thread.path("yoloEnabled").asBoolean());
    assertEquals(4, thread.path("nextCommandSequence").asLong());
    assertEquals("3", thread.path("revision").asText());
    assertEquals("TOOL_WAITING_APPROVAL", thread.path("status").asText());
    assertTrue(thread.path("processing").asBoolean());
    assertEquals("env-1", thread.path("branchSettings").path("environmentName").asText());
    assertEquals("default-assistant", thread.path("branchSettings").path("agentName").asText());
    assertEquals("gpt-5", thread.path("branchSettings").path("model").path("modelName").asText());
    assertEquals("web_search", thread.path("branchSettings").path("activeTools").get(0).asText());

    assertEquals(4, json.path("entries").size());
    assertEquals("ROOT", json.path("entries").get(0).path("entryType").asText());
    assertEquals("1", json.path("entries").get(0).path("entryId").asText());
    assertEquals("1", json.path("entries").get(0).path("sessionId").asText());
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
    assertEquals("client-1", json.path("queuedCommands").get(0).path("clientCommandId").asText());
    assertTrue(
        json.path("queuedCommands")
            .get(0)
            .path("payloadJson")
            .asText()
            .contains("\"text\":\"hello\""));

    assertEquals("10", json.path("modelInvocation").path("id").asText());
    assertEquals("SUCCEEDED", json.path("modelInvocation").path("status").asText());
    assertEquals("4", json.path("modelInvocation").path("resultEntryId").asText());
    assertTrue(json.path("modelInvocation").path("resultJson").asText().contains("\"toolCalls\""));

    assertEquals(1, json.path("toolInvocations").size());
    JsonNode tool = json.path("toolInvocations").get(0);
    assertEquals("100", tool.path("id").asText());
    assertEquals("10", tool.path("modelInvocationId").asText());
    assertEquals("4", tool.path("assistantEntryId").asText());
    assertEquals(0, tool.path("ordinal").asInt());
    assertEquals("WAITING_APPROVAL", tool.path("status").asText());
    assertEquals("call-1", tool.path("toolCallId").asText());
    assertEquals("web_search", tool.path("toolName").asText());
    assertEquals("1.0", tool.path("toolVersion").asText());
    assertEquals("web_search", tool.path("rendererKey").asText());
    assertEquals("PLATFORM", tool.path("toolType").asText());
    assertTrue(tool.path("environmentName").isNull());
    assertEquals("{}", tool.path("argumentsJson").asText());
    assertTrue(tool.path("approvalJson").asText().contains("\"required\":true"));
    assertTrue(tool.path("approvalJson").asText().contains("\"decision\":null"));
  }

  @Test
  void mapsIdleSnapshotWithProcessingFalseAndEmptyLists() throws Exception {
    HarnessThreadSnapshotDTO dto =
        HarnessRuntimeWebMapper.toSnapshotDto(HarnessRuntimeTestFixtures.idleSnapshot());
    dto.getThread().getBranchSettings().setEnvironmentName(null);
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));
    assertEquals("IDLE", json.path("thread").path("status").asText());
    assertFalse(json.path("thread").path("processing").asBoolean());
    assertTrue(json.path("thread").path("branchSettings").path("environmentName").isNull());
    assertTrue(json.path("entries").get(0).path("parentEntryId").isNull());
    assertTrue(json.path("modelInvocation").isNull());
    assertTrue(json.path("toolInvocations").isArray());
    assertEquals(0, json.path("toolInvocations").size());
    assertEquals(1, json.path("entries").size());
  }

  @Test
  void mapsCreateHeadStopAndApprovalRequestFields() {
    HarnessThreadCreateDTO create = new HarnessThreadCreateDTO();
    create.setTitle("new chat");
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    settings.setEnvironmentName(null);
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
    assertEquals("new chat", created.title());
    assertNull(created.branchSettings().environmentName());
    assertEquals("default-assistant", created.branchSettings().agentName());
    assertEquals("gpt-5", created.branchSettings().model().modelName());
    assertEquals(List.of("web_search"), created.branchSettings().activeTools());
    assertFalse(created.yoloEnabled());

    HarnessThreadHeadUpdateDTO head = new HarnessThreadHeadUpdateDTO();
    head.setTargetEntryId("2");
    head.setExpectedRevision("3");
    MoveHeadCommand move = HarnessRuntimeWebMapper.toMoveHeadCommand("1", head);
    assertEquals(1L, move.threadId());
    assertEquals(2L, move.targetEntryId());
    assertEquals(3L, move.expectedRevision());

    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setStopRequestId("stop-1");
    stop.setExpectedRevision("3");
    StopCommand stopCommand = HarnessRuntimeWebMapper.toStopCommand("1", stop);
    assertEquals(1L, stopCommand.threadId());
    assertEquals("stop-1", stopCommand.stopRequestId());
    assertEquals(3L, stopCommand.expectedRevision());

    HarnessToolApprovalDTO approval = new HarnessToolApprovalDTO();
    approval.setDecision("ALLOW");
    approval.setDecisionId("decision-1");
    approval.setActor("alice");
    approval.setReason("looks safe");
    ToolApprovalCommand allow = HarnessRuntimeWebMapper.toToolApprovalCommand("1", "100", approval);
    assertEquals(1L, allow.threadId());
    assertEquals(100L, allow.toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, allow.decision());
    assertEquals("decision-1", allow.decisionId());
    assertEquals("alice", allow.actor());
    assertEquals("looks safe", allow.reason());

    approval.setDecision("DENY");
    assertEquals(
        ToolApprovalDecision.DENIED,
        HarnessRuntimeWebMapper.toToolApprovalCommand("1", "100", approval).decision());

    approval.setDecision("MAYBE");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toToolApprovalCommand("1", "100", approval));
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
        IllegalArgumentException.class, () -> HarnessRuntimeWebMapper.toCommandBatch("1", empty));

    HarnessThreadCommandCreateDTO duplicate = command("SET_YOLO", "same-id");
    duplicate.setYoloEnabled(true);
    HarnessThreadCommandCreateDTO duplicateAgain = command("SET_AGENT", "same-id");
    duplicateAgain.setAgentName("default-assistant");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", batch(duplicate, duplicateAgain)));

    HarnessThreadCommandBatchDTO nullCursor = new HarnessThreadCommandBatchDTO();
    nullCursor.setExpectedHeadEntryId("3");
    nullCursor.setCommands(List.of(duplicate));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toCommandBatch("1", nullCursor));
  }

  private static ModelInvocation model(ModelInvocationStatus status, Long resultEntryId) {
    ModelInvocation model = mock(ModelInvocation.class);
    when(model.id()).thenReturn(10L);
    when(model.threadId()).thenReturn(1L);
    when(model.turnStartEntryId()).thenReturn(2L);
    when(model.basisHeadEntryId()).thenReturn(2L);
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
}
