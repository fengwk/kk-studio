package fun.fengwk.kkstudio.harness.runtime.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.math.BigDecimal;
import java.util.List;

/** history Entry payload codec：标准形态、严格边界拒绝与老字段拒绝。 */
class HistoryEntryPayloadJsonCodecTest {

  private static final String ENV = "123e4567-e89b-12d3-a456-426614174000";
  private static final HistoryEntryPayloadJsonCodec CODEC = new HistoryEntryPayloadJsonCodec();

  @Test
  void roundTripsAllSevenPayloadTypes() {
    BranchSettings settings = settings(ENV);
    EntryPayload root = new RootPayload(settings);
    EntryPayload turnStart = new TurnStartPayload(TurnStartReason.INPUT, settings);
    EntryPayload user = new MessagePayload(user("hello"), null, null);
    EntryPayload assistant =
        new MessagePayload(assistant("answer"), metadata(ProviderStopReason.COMPLETED), null);
    EntryPayload tool =
        new MessagePayload(
            toolMessage("call-1"),
            null,
            new ToolResultMetadata(2L, "call-1", 0, ToolResultStatus.SUCCEEDED, false, null));
    EntryPayload custom =
        new CustomMessagePayload(
            CustomMessagePayload.CORE_PLUGIN_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            system("system"),
            CustomMessagePayload.CORE_DETAILS_JSON);
    EntryPayload customEntry = new CustomEntryPayload("com.example.goal", "goal", 2, "{\"s\":1}");
    EntryPayload error = new AssistantErrorPayload(new AssistantError("MODEL_FAILED", "down"));
    EntryPayload aborted =
        new AssistantAbortedPayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial"))));
    EntryPayload turnEnd =
        new TurnEndPayload(7L, TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, "stop-1");

    for (EntryPayload payload :
        List.of(
            root, turnStart, user, assistant, tool, custom, customEntry, error, aborted, turnEnd)) {
      assertEquals(payload, CODEC.decode(payload.type(), CODEC.encode(payload)));
      assertEquals(payload, CODEC.decodeNode(payload.type(), CODEC.encodeNode(payload)));
    }
  }

  @Test
  void roundTripsSyntheticToolResultAndNullableSettings() {
    EntryPayload tool =
        new MessagePayload(
            toolMessage("call-1"),
            null,
            new ToolResultMetadata(
                2L, "call-1", 1, ToolResultStatus.UNKNOWN, true, ToolResultReason.HISTORY_CUT));
    EntryPayload root = new RootPayload(settings(null));
    EntryPayload completedEnd = new TurnEndPayload(7L, TurnEndOutcome.COMPLETED, false, null, null);

    assertEquals(tool, CODEC.decode(EntryType.MESSAGE, CODEC.encode(tool)));
    assertEquals(root, CODEC.decode(EntryType.ROOT, CODEC.encode(root)));
    assertEquals(completedEnd, CODEC.decode(EntryType.TURN_END, CODEC.encode(completedEnd)));
  }

  @Test
  void encodesCanonicalFieldOrder() {
    assertEquals(
        "{\"settings\":{\"environmentId\":null,\"agentName\":\"coding\",\"model\":{"
            + "\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"},"
            + "\"thinkingLevel\":\"high\",\"activeTools\":[\"read\",\"grep\"]}}",
        CODEC.encode(new RootPayload(settings(null))));
    assertEquals(
        "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
            + "\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"sys\"}]},"
            + "\"details\":{}}",
        CODEC.encode(
            new CustomMessagePayload(
                CustomMessagePayload.CORE_PLUGIN_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                system("sys"),
                CustomMessagePayload.CORE_DETAILS_JSON)));
    assertEquals(
        "{\"pluginId\":\"com.example.goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
            + "\"data\":{\"state\":\"open\"}}",
        CODEC.encode(
            new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"open\"}")));
    assertEquals(
        "{\"turnStartEntryId\":\"7\",\"outcome\":\"COMPLETED\",\"continueModel\":true,"
            + "\"reason\":null,\"closeRequestId\":null}",
        CODEC.encode(new TurnEndPayload(7L, TurnEndOutcome.COMPLETED, true, null, null)));
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
            + "\"assistantMetadata\":null,\"toolResultMetadata\":null}",
        CODEC.encode(new MessagePayload(user("hi"), null, null)));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"environmentId\":null,\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]},"
                    + "\"settings\":{\"environmentId\":null,\"agentName\":\"b\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{}} {}"));
  }

  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{},\"extra\":1}"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "{}"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "[]"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "{\"settings\":\"x\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":5,\"settings\":{\"environmentId\":null,\"agentName\":\"a\","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":null,\"agentName\":\"a\","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":{}}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":null,\"agentName\":\"a\","
                    + "\"model\":[\"p\"],\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":null,\"agentName\":5,"
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":null,\"agentName\":\"a\","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[5]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":\"not-a-uuid\","
                    + "\"agentName\":\"a\",\"model\":{\"providerName\":\"p\",\"modelName\":\"m\","
                    + "\"variant\":\"v\"},\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":5,"
                    + "\"agentName\":\"a\",\"model\":{\"providerName\":\"p\",\"modelName\":\"m\","
                    + "\"variant\":\"v\"},\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentId\":null,\"agentName\":\" \","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                "{\"message\":{},\"assistantMetadata\":null,\"toolResultMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
                    + "\"assistantMetadata\":\"x\",\"toolResultMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
                    + "\"assistantMetadata\":null,\"toolResultMetadata\":5}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(EntryType.ASSISTANT_ERROR, "{\"error\":{\"code\":5,\"message\":\"m\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ASSISTANT_ERROR,
                "{\"error\":{\"code\":\"lowercase\",\"message\":\"m\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ASSISTANT_ERROR, "{\"error\":{\"code\":\"CODE\",\"message\":\" m\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.ASSISTANT_ERROR, "{\"error\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                "{\"turnStartEntryId\":\"7\",\"outcome\":\"COMPLETED\",\"continueModel\":\"yes\",\"reason\":null,\"closeRequestId\":null}"));
  }

  @Test
  void rejectsUnknownEnumsAndInvalidCanonicalIds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.TURN_START, rootSettingsWith("{\"reason\":\"FOO\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"7\",\"outcome\":\"FOO\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"0\",\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"-1\",\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"abc\",\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"007\",\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":7,\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith(
                    "{\"turnStartEntryId\":\"7\",\"outcome\":\"COMPLETED\",\"continueModel\":true,\"reason\":5,")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith(
                    "{\"turnStartEntryId\":\"7\",\"outcome\":\"COMPLETED\",\"continueModel\":true,\"reason\":null,\"closeRequestId\":5}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                "{\"turnStartEntryId\":\"7\",\"outcome\":\"COMPLETED\",\"continueModel\":true,"
                    + "\"reason\":null,\"closeRequestId\":\" close\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
                    + "\"assistantMetadata\":{\"stopReason\":\"FOO\",\"usage\":{\"inputTokens\":1,"
                    + "\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,"
                    + "\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},"
                    + "\"cost\":{\"currency\":\"USD\",\"input\":\"1\",\"output\":\"1\","
                    + "\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\","
                    + "\"reasoning\":\"0\",\"total\":\"2\"}},\"toolResultMetadata\":null}"));
  }

  @Test
  void rejectsToolResultMetadataViolations() {
    String toolMessageJson =
        "{\"message\":{\"role\":\"TOOL\",\"contents\":[{\"type\":\"tool_result\",\"toolCallId\":\"call-1\","
            + "\"toolName\":\"read\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}],\"error\":false,"
            + "\"detailsJson\":\"{}\"}]},\"assistantMetadata\":null,";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"0\",\"toolCallId\":\"call-1\",\"ordinal\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":-1,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":0,\"status\":\"FOO\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":0,\"status\":\"SUCCEEDED\",\"synthetic\":true,\"reason\":\"HISTORY_CUT\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-9\",\"ordinal\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"007\",\"toolCallId\":\"call-1\",\"ordinal\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":5}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":99999999999999,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"2\",\"toolCallId\":\"call-1\",\"ordinal\":\"x\",\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.MESSAGE, toolMessageJson + "\"toolResultMetadata\":[]}"));
  }

  @Test
  void rejectsOldTurnSettingsAndOldEnvironmentName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
                    + "\"turnSettings\":{\"agentName\":\"a\",\"yoloEnabled\":true},"
                    + "\"assistantMetadata\":null,\"toolResultMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"environmentName\":\"env-1\",\"agentName\":\"a\","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}"));
  }

  @Test
  void rejectsWrongTypeDispatchAndMalformedJson() {
    String rootJson = CODEC.encode(new RootPayload(settings(null)));
    String messageJson = CODEC.encode(new MessagePayload(user("hi"), null, null));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.MESSAGE, rootJson));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, messageJson));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "{"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "null"));
    assertThrows(IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, ""));
    assertThrows(NullPointerException.class, () -> CODEC.encode(null));
    assertThrows(NullPointerException.class, () -> CODEC.encodeNode(null));
    assertThrows(NullPointerException.class, () -> CODEC.decode(null, rootJson));
    assertThrows(NullPointerException.class, () -> CODEC.decode(EntryType.ROOT, null));
    assertThrows(
        NullPointerException.class,
        () -> CODEC.decodeNode(null, HistoryValueCodecs.NODES.objectNode()));
  }

  @Test
  void rejectsMalformedCustomEntryShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":{},\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.CUSTOM, "{\"pluginId\":\"goal\",\"customType\":\"goal\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"Goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":0,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":-1,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":\"1\","
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"pluginId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":null}"));
  }

  @Test
  void rejectsMalformedCustomMessageShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"Message\","
                    + "\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"pluginId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"SYSTEM\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{},\"old\":1}"));
  }

  @Test
  void rejectsAssistantMetadataViolations() {
    String prefix =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
            + "\"assistantMetadata\":";
    String suffix = ",\"toolResultMetadata\":null}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix + "{\"stopReason\":\"COMPLETED\",\"usage\":{},\"cost\":{}}" + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":-1,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":{\"currency\":\"USD\",\"input\":\"1\",\"output\":\"1\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"2\"}}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":99999999999999999999,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":{\"currency\":\"USD\",\"input\":\"1\",\"output\":\"1\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"2\"}}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":\"x\",\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":{\"currency\":\"USD\",\"input\":\"1\",\"output\":\"1\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"2\"}}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":5}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":{\"currency\":\"USD\",\"input\":1,\"output\":\"1\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"2\"}}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},\"cost\":{\"currency\":\"USD\",\"input\":\"1.2.3\",\"output\":\"1\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteLong\":\"0\",\"reasoning\":\"0\",\"total\":\"2\"}}"
                    + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix
                    + "{\"stopReason\":\"COMPLETED\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2}}"
                    + suffix));
  }

  private static String rootSettingsWith(String reasonField) {
    return reasonField
        + "\"settings\":{\"environmentId\":null,\"agentName\":\"a\","
        + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
        + "\"thinkingLevel\":\"h\",\"activeTools\":[]}}";
  }

  private static String turnEndWith(String prefix) {
    return prefix + "\"continueModel\":true,\"reason\":null,\"closeRequestId\":null}";
  }

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
                toolCallId, "read", List.of(new TextMessageContent("ok")), false, "{}")));
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

  private static BranchSettings settings(String environmentId) {
    return new BranchSettings(
        environmentId == null ? null : new EnvironmentId(environmentId),
        "coding",
        new ModelSelection("anthropic", "claude-sonnet", "default"),
        "high",
        List.of("read", "grep"));
  }
}
