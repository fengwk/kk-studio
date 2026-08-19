package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** history payload record 的不变量：role/metadata 矩阵、synthetic tool result 与 turn end。 */
class HistoryEntryPayloadTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final ToolResultMetadata TOOL_METADATA =
      new ToolResultMetadata(id(2L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null);

  @Test
  void messagePayloadEnforcesRoleMetadataMatrix() {
    assertEquals(
        new MessagePayload(user("hello"), null, null),
        new MessagePayload(user("hello"), null, null));
    assertEquals(
        new MessagePayload(assistant("answer"), metadata(GenerationStopReason.COMPLETE), null),
        new MessagePayload(assistant("answer"), metadata(GenerationStopReason.COMPLETE), null));
    assertEquals(
        new MessagePayload(toolMessage("call-1"), null, TOOL_METADATA),
        new MessagePayload(toolMessage("call-1"), null, TOOL_METADATA));

    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagePayload(user("hello"), metadata(GenerationStopReason.COMPLETE), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagePayload(user("hello"), null, TOOL_METADATA));
    assertThrows(
        IllegalArgumentException.class, () -> new MessagePayload(assistant("answer"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                assistant("answer"), metadata(GenerationStopReason.COMPLETE), TOOL_METADATA));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagePayload(toolMessage("call-1"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                toolMessage("call-1"), metadata(GenerationStopReason.COMPLETE), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                toolMessage("call-1"), metadata(GenerationStopReason.COMPLETE), TOOL_METADATA));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                toolMessage("call-1"),
                null,
                new ToolResultMetadata(
                    id(2L), "call-9", 0, ToolResultStatus.SUCCEEDED, false, null)));
    assertThrows(
        IllegalArgumentException.class, () -> new MessagePayload(system("system"), null, null));
    assertThrows(NullPointerException.class, () -> new MessagePayload(null, null, null));
  }

  @Test
  void assistantMessagesAllowAnyStopReasonWithOrWithoutToolCallsButEnforceUniqueIds() {
    AgentMessage withToolCall =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "read", "read", "{}")));
    AgentMessage withoutToolCall = assistant("answer");

    // generation stop reason 与 tool call 存在性正交：COMPLETE 可以有或没有 calls。
    assertEquals(
        AgentMessageRole.ASSISTANT,
        new MessagePayload(withToolCall, metadata(GenerationStopReason.COMPLETE), null)
            .message()
            .role());
    assertEquals(
        new MessagePayload(withoutToolCall, metadata(GenerationStopReason.COMPLETE), null),
        new MessagePayload(withoutToolCall, metadata(GenerationStopReason.COMPLETE), null));
    assertEquals(
        AgentMessageRole.ASSISTANT,
        new MessagePayload(withToolCall, metadata(GenerationStopReason.LENGTH), null)
            .message()
            .role());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "read", "read", "{}"),
                        new ToolCallMessageContent("call-1", "grep", "grep", "{}"))),
                metadata(GenerationStopReason.COMPLETE),
                null));
    assertEquals(
        AgentMessageRole.ASSISTANT,
        new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "read", "read", "{}"),
                        new ToolCallMessageContent("call-2", "grep", "grep", "{}"))),
                metadata(GenerationStopReason.COMPLETE),
                null)
            .message()
            .role());
  }

  @Test
  void customMessagePayloadRestrictsRolesAndRequiresCoreMetadataShape() {
    AgentMessage system = system("s");
    AgentMessage user = user("u");
    assertEquals(
        system,
        new CustomMessagePayload(
                CustomMessagePayload.CORE_PLUGIN_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                system,
                CustomMessagePayload.CORE_DETAILS_JSON)
            .message());
    assertEquals(
        AgentMessageRole.USER,
        new CustomMessagePayload("com.example", "goal", "goal-renderer", user, "{\"priority\":1}")
            .message()
            .role());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustomMessagePayload(
                CustomMessagePayload.CORE_PLUGIN_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                assistant("a"),
                CustomMessagePayload.CORE_DETAILS_JSON));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustomMessagePayload(
                CustomMessagePayload.CORE_PLUGIN_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                toolMessage("c"),
                CustomMessagePayload.CORE_DETAILS_JSON));
    assertThrows(
        NullPointerException.class,
        () ->
            new CustomMessagePayload(
                CustomMessagePayload.CORE_PLUGIN_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                null,
                CustomMessagePayload.CORE_DETAILS_JSON));
  }

  @Test
  void customEntryPayloadValidatesIdentifiersSchemaVersionAndCanonicalDataJson() {
    CustomEntryPayload valid =
        new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"open\"}");
    assertEquals("com.example.goal", valid.pluginId());
    assertEquals("goal", valid.customType());
    assertEquals(1, valid.schemaVersion());
    assertEquals("{\"state\":\"open\"}", valid.dataJson());
    assertEquals(EntryType.CUSTOM, valid.type());

    assertThrows(NullPointerException.class, () -> new CustomEntryPayload(null, "goal", 1, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("Goal", "goal", 1, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "Goal", 1, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "goal", 0, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "goal", -1, "{}"));
    assertThrows(NullPointerException.class, () -> new CustomEntryPayload("goal", "goal", 1, null));
    // dataJson 必须是 bounded canonical JSON object：非 JSON / 非 object / 非 canonical / 超长都拒绝。
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "goal", 1, "[]"));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "goal", 1, "\"x\""));
    assertThrows(
        IllegalArgumentException.class, () -> new CustomEntryPayload("goal", "goal", 1, "{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomEntryPayload("goal", "goal", 1, "{\"a\":1} {\"b\":2}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomEntryPayload("goal", "goal", 1, "{\"a\":1,\"a\":2}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomEntryPayload("goal", "goal", 1, "{\"a\": 1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CustomEntryPayload(
                "goal",
                "goal",
                1,
                "{\"a\":\"" + "x".repeat(CustomEntryPayload.MAX_DATA_JSON_CHARS) + "\"}"));
  }

  @Test
  void customMessagePayloadRejectsNonCanonicalDetailsJson() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessagePayload("core", "message", "message", system("s"), "{\"a\": 1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessagePayload("core", "message", "message", system("s"), "[]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessagePayload("core", "message", "message", system("s"), ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessagePayload("Core", "message", "message", system("s"), "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CustomMessagePayload("core", "message", "Message", system("s"), "{}"));
  }

  @Test
  void rootAndTurnStartPayloadsRequireNonNullValues() {
    assertThrows(NullPointerException.class, () -> new RootPayload(null));
    assertThrows(
        NullPointerException.class, () -> new TurnStartPayload(null, SETTINGS, OWNER_THREAD_ID));
    assertThrows(
        NullPointerException.class,
        () -> new TurnStartPayload(TurnStartReason.INPUT, null, OWNER_THREAD_ID));
    assertThrows(
        NullPointerException.class,
        () -> new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, null));
    assertEquals(SETTINGS, new RootPayload(SETTINGS).settings());
    assertEquals(
        TurnStartReason.INPUT,
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID).reason());
    assertEquals(
        4096,
        new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID, 4096)
            .contextWindow());
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID, -1));
  }

  @Test
  void assistantErrorAndPayloadValidateCodeAndMessage() {
    AssistantError error = new AssistantError("MODEL_FAILED", "provider unavailable");
    assertEquals(error, new AssistantErrorPayload(error, null).error());
    assertThrows(NullPointerException.class, () -> new AssistantErrorPayload(null, null));
    assertThrows(NullPointerException.class, () -> new AssistantError(null, "m"));
    assertThrows(NullPointerException.class, () -> new AssistantError("CODE", null));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("lowercase", "m"));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("A-B", "m"));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("1A", "m"));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("A".repeat(65), "m"));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("CODE", " "));
    assertThrows(IllegalArgumentException.class, () -> new AssistantError("CODE", " padded "));
    assertThrows(
        IllegalArgumentException.class, () -> new AssistantError("CODE", "m".repeat(2049)));
  }

  @Test
  void assistantAbortedPayloadAcceptsOnlyMeaningfulTextAndThinking() {
    assertEquals(
        AgentMessageRole.ASSISTANT,
        new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent("partial"), new ThinkingMessageContent("t"))))
            .message()
            .role());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AssistantAbortedPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("x")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AssistantAbortedPayload(new AgentMessage(AgentMessageRole.ASSISTANT, List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new ToolCallMessageContent("c", "t", "t", "{}")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent(""), new ThinkingMessageContent("")))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new JsonMessageContent("{}")))));
    assertThrows(NullPointerException.class, () -> new AssistantAbortedPayload(null));
  }

  @Test
  void toolResultMetadataValidatesSyntheticRuleAndCanonicalValues() {
    ToolResultMetadata synthetic =
        new ToolResultMetadata(
            id(2L), "call-1", 1, ToolResultStatus.UNKNOWN, true, ToolResultReason.HISTORY_CUT);
    assertEquals(ToolResultReason.HISTORY_CUT, synthetic.reason());
    assertTrue(synthetic.synthetic());

    assertThrows(
        NullPointerException.class,
        () -> new ToolResultMetadata(null, "call-1", 0, ToolResultStatus.SUCCEEDED, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResultMetadata(id(2L), " ", 0, ToolResultStatus.SUCCEEDED, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResultMetadata(id(2L), " call", 0, ToolResultStatus.SUCCEEDED, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolResultMetadata(
                id(2L), "c".repeat(257), 0, ToolResultStatus.SUCCEEDED, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolResultMetadata(id(2L), "call-1", -1, ToolResultStatus.SUCCEEDED, false, null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolResultMetadata(id(2L), "call-1", 0, null, false, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolResultMetadata(
                id(2L),
                "call-1",
                0,
                ToolResultStatus.SUCCEEDED,
                true,
                ToolResultReason.HISTORY_CUT));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolResultMetadata(id(2L), "call-1", 0, ToolResultStatus.UNKNOWN, true, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolResultMetadata(
                id(2L),
                "call-1",
                0,
                ToolResultStatus.UNKNOWN,
                false,
                ToolResultReason.HISTORY_CUT));
  }

  @Test
  void turnEndPayloadEnforcesOutcomeMatrix() {
    assertEquals(
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, true, null, null),
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, true, null, null));
    assertEquals(
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, false, null, null),
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, false, null, null));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.COMPLETED, false, TurnEndReason.CANCELLED, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, false, null, id(8L)));

    assertEquals(
        new TurnEndPayload(id(7L), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
        new TurnEndPayload(id(7L), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndPayload(id(7L), TurnEndOutcome.FAILED, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.FAILED, false, TurnEndReason.USER_STOP, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.FAILED, false, TurnEndReason.CANCELLED, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.FAILED, true, TurnEndReason.TURN_FAILED, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, id(8L)));

    assertEquals(
        new TurnEndPayload(id(7L), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(8L)),
        new TurnEndPayload(id(7L), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(8L)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.STOPPED, false, TurnEndReason.CANCELLED, id(8L)));
    assertThrows(
        NullPointerException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.STOPPED, true, TurnEndReason.USER_STOP, id(8L)));

    assertEquals(
        new TurnEndPayload(
            id(7L), TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
        new TurnEndPayload(
            id(7L), TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null));
    assertEquals(
        new TurnEndPayload(
            id(7L), TurnEndOutcome.CANCELLED, false, TurnEndReason.CANCELLED, id(8L)),
        new TurnEndPayload(
            id(7L), TurnEndOutcome.CANCELLED, false, TurnEndReason.CANCELLED, id(8L)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TurnEndPayload(id(7L), TurnEndOutcome.CANCELLED, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.CANCELLED, false, TurnEndReason.USER_STOP, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TurnEndPayload(
                id(7L), TurnEndOutcome.CANCELLED, true, TurnEndReason.HISTORY_CUT, null));

    assertThrows(
        NullPointerException.class,
        () -> new TurnEndPayload(null, TurnEndOutcome.COMPLETED, false, null, null));
    assertThrows(
        NullPointerException.class, () -> new TurnEndPayload(id(7L), null, false, null, null));
  }

  @Test
  void entryValidatesIdsParentsAndNonNullFields() {
    assertEquals(
        EntryType.ROOT,
        new Entry(id(1L), id(1L), null, new RootPayload(SETTINGS), TIME).payload().type());
    assertEquals(
        EntryType.TURN_START,
        new Entry(
                id(2L),
                id(1L),
                id(1L),
                new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
                TIME)
            .payload()
            .type());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Entry(id(1L), id(1L), id(2L), new RootPayload(SETTINGS), TIME));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Entry(
                id(2L),
                id(1L),
                null,
                new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, OWNER_THREAD_ID),
                TIME));
    assertThrows(NullPointerException.class, () -> new Entry(id(1L), id(1L), null, null, TIME));
    assertThrows(
        NullPointerException.class,
        () -> new Entry(id(1L), id(1L), null, new RootPayload(SETTINGS), null));
  }

  @Test
  void entryTypeClassifiesRoot() {
    assertTrue(EntryType.ROOT.isRoot());
    assertTrue(!EntryType.TURN_START.isRoot());
    assertTrue(!EntryType.MESSAGE.isRoot());
  }

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          null,
          "coding",
          new ModelSelection("anthropic", "claude-sonnet", "default"),
          List.of("read"));
  private static final Instant TIME = Instant.ofEpochSecond(1000L);

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage system(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage assistant(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage toolMessage(String toolCallId) {
    return new AgentMessage(
        AgentMessageRole.TOOL,
        List.of(
            new ToolResultMessageContent(
                toolCallId, "read", "read", List.of(new TextMessageContent("ok")), false, "{}")));
  }

  private static AssistantMessageMetadata metadata(GenerationStopReason reason) {
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
