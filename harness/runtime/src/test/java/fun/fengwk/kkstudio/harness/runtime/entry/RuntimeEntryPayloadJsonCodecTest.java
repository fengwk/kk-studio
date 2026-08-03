package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.math.BigDecimal;
import java.util.List;

/** Strict Entry codec coverage for turn references and role-specific payloads. */
class RuntimeEntryPayloadJsonCodecTest {

  private static final TurnSettings SETTINGS = new TurnSettings("agent", true);
  private static final BranchSettings BRANCH_SETTINGS =
      new BranchSettings(
          new EnvironmentId("123e4567-e89b-12d3-a456-426614174000"),
          "coding",
          new ModelSelection("anthropic", "claude-sonnet", "default"),
          "high",
          List.of("read", "grep"));
  private final RuntimeEntryPayloadJsonCodec codec = new RuntimeEntryPayloadJsonCodec();

  @Test
  void roundTripsUserAndCustomReferences() {
    MessageEntryPayload user =
        new MessageEntryPayload(message(AgentMessageRole.USER, "hello"), SETTINGS, null);
    CustomMessageEntryPayload custom =
        new CustomMessageEntryPayload(message(AgentMessageRole.SYSTEM, "system"), SETTINGS);

    assertEquals(user, codec.decode(EntryType.MESSAGE, codec.encode(user)));
    assertEquals(custom, codec.decode(EntryType.CUSTOM_MESSAGE, codec.encode(custom)));
    assertTrue(codec.encode(user).contains("\"turnSettings\""));
  }

  @Test
  void roundTripsAssistantWithoutARequestReference() {
    MessageEntryPayload assistant =
        new MessageEntryPayload(
            message(AgentMessageRole.ASSISTANT, "answer"),
            null,
            metadata(ProviderStopReason.COMPLETED));

    assertEquals(assistant, codec.decode(EntryType.MESSAGE, codec.encode(assistant)));
    assertTrue(codec.encode(assistant).contains("\"turnSettings\":null"));
  }

  @Test
  void rejectsMissingOrUnknownReferenceFieldsAndWrongRoles() {
    String encoded =
        codec.encode(
            new MessageEntryPayload(message(AgentMessageRole.USER, "hello"), SETTINGS, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.MESSAGE, encoded.replace("\"yoloEnabled\":true", "\"extra\":true")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.MESSAGE, "{\"message\":{},\"assistantMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustomMessageEntryPayload(
                message(AgentMessageRole.ASSISTANT, "assistant"), SETTINGS));
  }

  @Test
  void roundTripsTurnBoundariesInDesignFieldOrder() {
    TurnStartEntryPayload start = new TurnStartEntryPayload(TurnStartReason.INPUT, BRANCH_SETTINGS);
    TurnEndEntryPayload end =
        new TurnEndEntryPayload(123L, TurnEndOutcome.COMPLETED, true, null, null);
    String expectedStart =
        "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\",\"agentName\":\"coding\","
            + "\"model\":{\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"},"
            + "\"thinkingLevel\":\"high\",\"activeTools\":[\"read\",\"grep\"]}}";
    String expectedEnd =
        "{\"turnStartEntryId\":\"123\",\"outcome\":\"COMPLETED\",\"continueModel\":true,"
            + "\"reason\":null,\"closeRequestId\":null}";

    assertEquals(expectedStart, codec.encode(start));
    assertEquals(start, codec.decode(EntryType.TURN_START, expectedStart));
    assertEquals(expectedEnd, codec.encode(end));
    assertEquals(end, codec.decode(EntryType.TURN_END, expectedEnd));
  }

  @Test
  void acceptsNullEnvironmentAndCanonicalizesDuplicateActiveTools() {
    TurnStartEntryPayload start =
        new TurnStartEntryPayload(
            TurnStartReason.CONTINUATION,
            new BranchSettings(
                null,
                "coding",
                new ModelSelection("anthropic", "claude-sonnet", "default"),
                "high",
                List.of("read", "read", "grep")));

    assertEquals(
        "{\"reason\":\"CONTINUATION\",\"settings\":{\"environmentId\":null,\"agentName\":\"coding\","
            + "\"model\":{\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"},"
            + "\"thinkingLevel\":\"high\",\"activeTools\":[\"read\",\"grep\"]}}",
        codec.encode(start));

    TurnStartEntryPayload decoded =
        (TurnStartEntryPayload) codec.decode(EntryType.TURN_START, codec.encode(start));
    assertNull(decoded.settings().environmentId());
    assertEquals(List.of("read", "grep"), decoded.settings().activeTools());
  }

  @Test
  void rejectsStrictTurnBoundaryJsonViolations() {
    TurnStartEntryPayload start = new TurnStartEntryPayload(TurnStartReason.INPUT, BRANCH_SETTINGS);
    String startJson = codec.encode(start);
    TurnEndEntryPayload end =
        new TurnEndEntryPayload(123L, TurnEndOutcome.COMPLETED, true, null, null);
    String endJson = codec.encode(end);

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.TURN_START, startJson + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.substring(0, startJson.length() - 1) + ",\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"reason\":\"CONTINUATION\","
                    + "\"settings\":"
                    + startJson.substring(startJson.indexOf("\"settings\"") + 11)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace("\"reason\":\"INPUT\"", "\"reason\":null")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace("\"reason\":\"INPUT\"", "\"reason\":\"UNKNOWN\"")));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.TURN_START, startJson.replace("\"reason\":\"INPUT\",", "")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace(
                    "\"activeTools\":[\"read\",\"grep\"]", "\"activeTools\":\"read\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace(
                    "\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\"",
                    "\"environmentId\":\"123E4567-E89B-12D3-A456-426614174000\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace(
                    "\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\"",
                    "\"environmentName\":\"workspace-A\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_START,
                startJson.replace(
                    "\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\"",
                    "\"environmentId\":123")));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(EntryType.TURN_START, endJson));

    assertThrows(
        IllegalArgumentException.class, () -> codec.decode(EntryType.TURN_END, endJson + " {}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"turnStartEntryId\":\"123\"", "\"turnStartEntryId\":123")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"turnStartEntryId\":\"123\"", "\"turnStartEntryId\":\"0123\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"outcome\":\"COMPLETED\"", "\"outcome\":\"UNKNOWN\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"continueModel\":true", "\"continueModel\":\"true\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"outcome\":\"COMPLETED\"", "\"outcome\":\"FAILED\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END, endJson.replace("\"reason\":null", "\"reason\":\" \"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"closeRequestId\":null", "\"closeRequestId\":\" close\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                endJson.replace("\"closeRequestId\":null", "\"closeRequestId\":false")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                EntryType.TURN_END,
                "{\"turnStartEntryId\":\"123\",\"outcome\":\"COMPLETED\","
                    + "\"continueModel\":true,\"reason\":null,\"reason\":null,\"closeRequestId\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(EntryType.TURN_END, endJson.replace("\"reason\":null,", "")));
  }

  @Test
  void delegatesAgentMessageCodecByteForByte() throws Exception {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new TextMessageContent("answer"),
                new ImageMessageContent("image/png", "data:image/png;base64,AA=="),
                new AudioMessageContent("audio/wav", "https://example.test/audio.wav"),
                new ThinkingMessageContent("reasoning"),
                new JsonMessageContent("[1,{\"ok\":true}]"),
                new ToolCallMessageContent("call-1", "read", "{\"path\":\"README.md\"}"),
                new ArtifactMessageContent("artifact-1", "text/plain", null)));
    MessageEntryPayload payload =
        new MessageEntryPayload(message, null, metadata(ProviderStopReason.TOOL_CALLS));

    String encoded = codec.encode(payload);
    assertEquals(payload, codec.decode(EntryType.MESSAGE, encoded));

    ObjectMapper mapper = new ObjectMapper();
    String messageSubtree = mapper.writeValueAsString(mapper.readTree(encoded).get("message"));
    assertEquals(new AgentMessageJsonCodec().encode(message), messageSubtree);
  }

  private static AgentMessage message(AgentMessageRole role, String text) {
    return new AgentMessage(role, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata(ProviderStopReason reason) {
    ModelUsage usage = new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(2));
    return new AssistantMessageMetadata(reason, usage, cost);
  }
}
