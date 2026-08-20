package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.studio.StudioOwner;
import fun.fengwk.kkstudio.core.studio.StudioOwnerType;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO;

import java.util.List;
import java.util.UUID;

/** HTTP mapper 测试：三 target、严格 target union、产品 command shape 与 exact response mapping。 */
class HarnessRuntimeWebMapperTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();
  private static final String THREAD_ID = idText(1);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  @Test
  void mapsOwnerAndAllThreeTargetsToSealedDomainTargetTypes() {
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setType("CANVAS");
    owner.setId(idText(10));
    StudioOwner mappedOwner = HarnessRuntimeWebMapper.toOwner(owner);
    assertEquals(StudioOwnerType.CANVAS, mappedOwner.type());
    assertEquals(id(10), mappedOwner.id());

    AcceptCommandsCommand newSession =
        HarnessRuntimeWebMapper.toAcceptCommandsCommand(
            request(newSessionTarget(), userCommand("new-session")));
    AcceptCommandsCommand entry =
        HarnessRuntimeWebMapper.toAcceptCommandsCommand(
            request(entryTarget(), userCommand("entry")));
    AcceptCommandsCommand thread =
        HarnessRuntimeWebMapper.toAcceptCommandsCommand(
            request(threadTarget(), userCommand("thread")));

    assertInstanceOf(AcceptCommandsTarget.NewSession.class, newSession.target());
    assertInstanceOf(AcceptCommandsTarget.Entry.class, entry.target());
    assertInstanceOf(AcceptCommandsTarget.Thread.class, thread.target());
    assertEquals(1, newSession.commands().size());
    assertEquals(1, entry.commands().size());
    assertEquals(1, thread.commands().size());
  }

  @Test
  void mapsOnlyFixedSetPrefixAndTrailingUserMessageToRawRequestHash() {
    HarnessCommandBatchDTO request = request(threadTarget(), userCommand("user"));
    HarnessCommandCreateDTO environment = command("SET_ENVIRONMENT", "environment");
    environment.setEnvironment(null);
    HarnessCommandCreateDTO agent = command("SET_AGENT", "agent");
    agent.setAgentName("default-assistant");
    HarnessCommandCreateDTO model = command("SET_MODEL", "model");
    model.setModel(modelSelection());
    HarnessCommandCreateDTO tools = command("SET_ACTIVE_TOOLS", "tools");
    tools.setActiveTools(List.of("web_search"));
    request.setCommands(List.of(environment, agent, model, tools, userCommand("user")));

    AcceptCommandsCommand mapped = HarnessRuntimeWebMapper.toAcceptCommandsCommand(request);
    assertEquals(
        List.of(
            ThreadCommandType.SET_ENVIRONMENT,
            ThreadCommandType.SET_AGENT,
            ThreadCommandType.SET_MODEL,
            ThreadCommandType.SET_ACTIVE_TOOLS,
            ThreadCommandType.USER_MESSAGE),
        mapped.commands().stream().map(command -> command.payload().type()).toList());
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}",
        COMMAND_PAYLOADS.encode(mapped.commands().get(4).payload()));
    assertEquals(
        ThreadCommandPayloadJsonCodec.requestHash(mapped.commands().get(4).payload()),
        mapped.commands().get(4).requestHash());
  }

  @Test
  void rejectsCustomMessageWrongOrderAndForbiddenTargetFields() {
    HarnessCommandCreateDTO custom = command("CUSTOM_MESSAGE", "custom");
    custom.setType("CUSTOM_MESSAGE");
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toAcceptCommandsCommand(request(threadTarget(), custom)));

    HarnessCommandCreateDTO user = userCommand("wrong-order");
    HarnessCommandCreateDTO agent = command("SET_AGENT", "wrong-agent");
    agent.setAgentName("default-assistant");
    HarnessCommandBatchDTO wrongOrder = request(threadTarget(), user, agent);
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toAcceptCommandsCommand(wrongOrder));

    HarnessCommandTargetDTO target = newSessionTarget();
    target.setStartEntryId(idText(8));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HarnessRuntimeWebMapper.toAcceptCommandsCommand(
                request(target, userCommand("forbidden"))));
  }

  @Test
  void mapsSnapshotAvailabilityAndExactAcceptedResponseProjection() throws Exception {
    ThreadSnapshot snapshot = HarnessRuntimeTestFixtures.idleSnapshot();
    HarnessThreadSnapshotDTO snapshotDto =
        HarnessRuntimeWebMapper.toSnapshotDto(
            snapshot,
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.NOTHING_TO_COMPACT));
    JsonNode snapshotJson = MAPPER.readTree(MAPPER.writeValueAsString(snapshotDto));
    assertEquals(false, snapshotJson.path("manualCompaction").path("available").asBoolean());
    assertEquals(
        "NOTHING_TO_COMPACT",
        snapshotJson.path("manualCompaction").path("disabledReason").asText());

    AcceptedCommands accepted =
        new AcceptedCommands(
            HarnessRuntimeTestFixtures.session(),
            HarnessRuntimeTestFixtures.rootEntry(),
            HarnessRuntimeTestFixtures.thread(id(1)),
            List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
            true);
    HarnessAcceptedCommandsDTO acceptedDto =
        HarnessRuntimeWebMapper.toAcceptedCommandsDto(accepted, snapshot);
    JsonNode acceptedJson = MAPPER.readTree(MAPPER.writeValueAsString(acceptedDto));
    assertEquals(idText(1), acceptedJson.path("session").path("sessionId").asText());
    assertEquals("ROOT", acceptedJson.path("rootEntry").path("entryType").asText());
    assertEquals(idText(1), acceptedJson.path("thread").path("threadId").asText());
    assertEquals(
        idText(50), acceptedJson.path("acceptedCommands").get(0).path("clientCommandId").asText());
    assertTrue(acceptedJson.path("replayed").asBoolean());
  }

  @Test
  void mapsCompactCommandAndNullableCompactResult() throws Exception {
    HarnessThreadCompactDTO request = new HarnessThreadCompactDTO();
    request.setExpectedRevision("7");
    CompactThreadCommand command =
        HarnessRuntimeWebMapper.toCompactThreadCommand(THREAD_ID, request);
    assertEquals(id(1), command.threadId());
    assertEquals(7L, command.expectedRevision());

    CompactThreadResult result =
        new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null);
    HarnessThreadCompactResultDTO dto =
        HarnessRuntimeWebMapper.toCompactResultDto(
            result, HarnessRuntimeTestFixtures.idleSnapshot());
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));
    assertEquals(idText(1), json.path("thread").path("threadId").asText());
    assertEquals(idText(2), json.path("turnStartEntryId").asText());
    assertTrue(json.has("modelInvocationId"));
    assertNull(dto.getModelInvocationId());
  }

  @Test
  void mapsStopCancellationMessagesAsCanonicalAgentJson() throws Exception {
    StopResult result =
        new StopResult(
            false,
            HarnessRuntimeTestFixtures.thread(id(1)),
            null,
            2,
            List.of(
                new CancelledUserMessage(1L, id(50), List.of(new TextMessageContent("first"))),
                new CancelledUserMessage(
                    2L,
                    id(51),
                    List.of(new ResourceMessageContent(id(70), "report.txt", "preview")))));

    HarnessThreadStopResultDTO dto =
        HarnessRuntimeWebMapper.toStopResultDto(result, HarnessRuntimeTestFixtures.idleSnapshot());
    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(dto));

    assertEquals(2, json.path("cancelledUserMessages").size());
    assertEquals("1", json.path("cancelledUserMessages").get(0).path("sequence").asText());
    assertEquals(
        idText(50), json.path("cancelledUserMessages").get(0).path("clientCommandId").asText());
    assertEquals(
        "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"first\"}]}",
        json.path("cancelledUserMessages").get(0).path("messageJson").asText());
    assertEquals(
        "{\"role\":\"USER\",\"contents\":[{\"type\":\"resource\",\"blobId\":\""
            + idText(70)
            + "\",\"name\":\"report.txt\",\"preview\":\"preview\"}]}",
        json.path("cancelledUserMessages").get(1).path("messageJson").asText());

    HarnessThreadStopResultDTO replay =
        HarnessRuntimeWebMapper.toStopResultDto(
            new StopResult(
                true,
                result.thread(),
                result.stoppedTurnEndEntryId(),
                result.cancelledCommandCount(),
                result.cancelledUserMessages()),
            HarnessRuntimeTestFixtures.idleSnapshot());
    assertEquals("REPLAYED", replay.getStatus());
    assertEquals(dto.getCancelledUserMessages(), replay.getCancelledUserMessages());

    HarnessThreadStopResultDTO empty =
        HarnessRuntimeWebMapper.toStopResultDto(
            new StopResult(false, result.thread(), null, 0, List.of()),
            HarnessRuntimeTestFixtures.idleSnapshot());
    assertTrue(empty.getCancelledUserMessages().isEmpty());
  }

  @Test
  void resourceContentUsesStrictShape() {
    HarnessUserMessageContentDTO resource = new HarnessUserMessageContentDTO();
    resource.setType("RESOURCE");
    resource.setBlobId(idText(70));
    resource.setName("report.txt");
    resource.setPreview(null);
    HarnessCommandCreateDTO command = command("USER_MESSAGE", "resource");
    command.setContents(List.of(resource));

    AcceptCommandsCommand mapped =
        HarnessRuntimeWebMapper.toAcceptCommandsCommand(request(threadTarget(), command));
    UserMessageCommandPayload payload =
        assertInstanceOf(UserMessageCommandPayload.class, mapped.commands().getFirst().payload());
    ResourceMessageContent content =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().getFirst());
    assertEquals(id(70), content.blobId());
    assertEquals("report.txt", content.name());
    assertNull(content.preview());

    resource.setUploadId(idText(71));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toAcceptCommandsCommand(request(threadTarget(), command)));

    HarnessUserMessageContentDTO missingName = new HarnessUserMessageContentDTO();
    missingName.setType("RESOURCE");
    missingName.setBlobId(idText(70));
    HarnessCommandCreateDTO invalid = command("USER_MESSAGE", "missing-name");
    invalid.setContents(List.of(missingName));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toAcceptCommandsCommand(request(threadTarget(), invalid)));

    HarnessUserMessageContentDTO text = new HarnessUserMessageContentDTO();
    text.setType("TEXT");
    text.setText("hello");
    text.setBlobId(idText(70));
    invalid.setContents(List.of(text));
    assertThrows(
        IllegalArgumentException.class,
        () -> HarnessRuntimeWebMapper.toAcceptCommandsCommand(request(threadTarget(), invalid)));
  }

  private static HarnessCommandBatchDTO request(
      HarnessCommandTargetDTO target, HarnessCommandCreateDTO... commands) {
    HarnessCommandBatchDTO request = new HarnessCommandBatchDTO();
    HarnessCommandOwnerDTO owner = new HarnessCommandOwnerDTO();
    owner.setType("CHAT");
    owner.setId(idText(10));
    request.setOwner(owner);
    request.setTarget(target);
    request.setCommands(List.of(commands));
    return request;
  }

  private static HarnessCommandCreateDTO command(String type, String suffix) {
    HarnessCommandCreateDTO command = new HarnessCommandCreateDTO();
    command.setType(type);
    command.setClientCommandId(idText(Math.abs(suffix.hashCode()) + 100));
    return command;
  }

  private static HarnessCommandCreateDTO userCommand(String suffix) {
    HarnessUserMessageContentDTO content = new HarnessUserMessageContentDTO();
    content.setType("TEXT");
    content.setText("hello");
    HarnessCommandCreateDTO command = command("USER_MESSAGE", suffix);
    command.setContents(List.of(content));
    return command;
  }

  private static HarnessCommandTargetDTO newSessionTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("NEW_SESSION");
    target.setSessionId(idText(2));
    target.setThreadId(idText(1));
    target.setRootSettings(branchSettings());
    target.setYoloEnabled(true);
    return target;
  }

  private static HarnessCommandTargetDTO entryTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("ENTRY");
    target.setSessionId(idText(2));
    target.setStartEntryId(idText(3));
    target.setThreadId(idText(1));
    target.setYoloEnabled(false);
    return target;
  }

  private static HarnessCommandTargetDTO threadTarget() {
    HarnessCommandTargetDTO target = new HarnessCommandTargetDTO();
    target.setType("THREAD");
    target.setThreadId(idText(1));
    target.setExpectedHeadEntryId(idText(3));
    target.setExpectedNextCommandSequence("4");
    return target;
  }

  private static HarnessBranchSettingsDTO branchSettings() {
    HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
    settings.setEnvironment(null);
    settings.setAgentName("default-assistant");
    settings.setModel(modelSelection());
    settings.setActiveTools(List.of());
    return settings;
  }

  private static HarnessModelSelectionDTO modelSelection() {
    HarnessModelSelectionDTO selection = new HarnessModelSelectionDTO();
    selection.setProviderName("openai");
    selection.setModelName("gpt-5");
    selection.setVariant("default");
    return selection;
  }
}
