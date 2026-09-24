package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** history Entry payload codec：标准形态、严格边界拒绝与老字段拒绝。 */
class HistoryEntryPayloadJsonCodecTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final String UUID_2 = "00000000-0000-0000-0000-000000000002";
  private static final String UUID_7 = "00000000-0000-0000-0000-000000000007";
  private static final String UUID_10 = "00000000-0000-0000-0000-00000000000a";
  private static final String UUID_11 = "00000000-0000-0000-0000-00000000000b";
  private static final String UUID_0 = "00000000-0000-0000-0000-000000000000";
  private static final HistoryEntryPayloadJsonCodec CODEC = new HistoryEntryPayloadJsonCodec();

  @Test
  void roundTripsAllPayloadTypes() {
    BranchSettings settings = settings();
    EntryPayload root = new RootPayload(settings);
    EntryPayload turnStart = new TurnStartPayload(TurnStartReason.INPUT, settings, OWNER_THREAD_ID);
    EntryPayload user =
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(
                    new TextMessageContent("hello"),
                    ResourceMessageContent.media(
                        UUID.fromString("0fb32eb4-2635-46ed-8e2e-4a4c3f5e1d01"), "reference.mp4"))),
            null,
            null);
    EntryPayload assistant =
        new MessagePayload(assistant("answer"), metadata(GenerationStopReason.COMPLETE), null);
    EntryPayload tool =
        new MessagePayload(
            toolMessage("call-1"),
            null,
            new ToolResultMetadata(id(2L), "call-1", 0, ToolResultStatus.SUCCEEDED, false, null));
    EntryPayload custom =
        new CustomMessagePayload(
            CustomMessagePayload.CORE_CONTRIBUTOR_ID,
            CustomMessagePayload.CORE_CUSTOM_TYPE,
            CustomMessagePayload.CORE_RENDERER_KEY,
            user("system"),
            CustomMessagePayload.CORE_DETAILS_JSON);
    EntryPayload customEntry = new CustomEntryPayload("com.example.goal", "goal", 2, "{\"s\":1}");
    EntryPayload attemptFailure =
        new ModelAttemptFailurePayload(
            new ModelAttemptSnapshot(1, 7, "partial", "thinking"),
            new AssistantError("TRANSIENT", "down"),
            Instant.parse("2026-01-01T00:00:02Z"));
    EntryPayload error =
        new AssistantErrorPayload(
            new AssistantError("MODEL_FAILED", "down"),
            new ModelAttemptSnapshot(2, 9, "final partial", ""));
    EntryPayload aborted =
        new AssistantAbortedPayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial"))));
    EntryPayload turnEnd =
        new TurnEndPayload(id(7L), TurnEndOutcome.STOPPED, false, TurnEndReason.USER_STOP, id(1L));

    for (EntryPayload payload :
        List.of(
            root,
            turnStart,
            user,
            assistant,
            tool,
            custom,
            customEntry,
            attemptFailure,
            error,
            aborted,
            turnEnd)) {
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
                id(2L), "call-1", 1, ToolResultStatus.UNKNOWN, true, ToolResultReason.HISTORY_CUT));
    EntryPayload root = new RootPayload(settings());
    EntryPayload subagentRoot =
        new RootPayload(settings(), new SubagentContext(id(11L), id(10L), id(12L), 2));
    EntryPayload completedEnd =
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, false, null, null);

    assertEquals(tool, CODEC.decode(EntryType.MESSAGE, CODEC.encode(tool)));
    assertEquals(root, CODEC.decode(EntryType.ROOT, CODEC.encode(root)));
    assertEquals(subagentRoot, CODEC.decode(EntryType.ROOT, CODEC.encode(subagentRoot)));
    assertEquals(completedEnd, CODEC.decode(EntryType.TURN_END, CODEC.encode(completedEnd)));
  }

  @Test
  void encodesCanonicalFieldOrder() {
    assertEquals(
        "{\"settings\":{\"agentName\":\"coding\",\"model\":{"
            + "\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}"
            + ",\"environmentName\":null,\"goal\":null},\"subagentContext\":null}",
        CODEC.encode(new RootPayload(settings())));
    assertEquals(
        "{\"reason\":\"INPUT\",\"settings\":{\"agentName\":\"coding\",\"model\":{"
            + "\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}"
            + ",\"environmentName\":null,\"goal\":null},"
            + "\"ownerThreadId\":\""
            + OWNER_THREAD_ID
            + "\",\"contextWindow\":4096,\"maxOutputTokens\":1024,\"compaction\":null}",
        CODEC.encode(
            new TurnStartPayload(
                TurnStartReason.INPUT, settings(), OWNER_THREAD_ID, 4096, 1024, null)));
    assertEquals(
        "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
            + "\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"sys\"}]},"
            + "\"details\":{}}",
        CODEC.encode(
            new CustomMessagePayload(
                CustomMessagePayload.CORE_CONTRIBUTOR_ID,
                CustomMessagePayload.CORE_CUSTOM_TYPE,
                CustomMessagePayload.CORE_RENDERER_KEY,
                user("sys"),
                CustomMessagePayload.CORE_DETAILS_JSON)));
    assertEquals(
        "{\"contributorId\":\"com.example.goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
            + "\"data\":{\"state\":\"open\"}}",
        CODEC.encode(
            new CustomEntryPayload("com.example.goal", "goal", 1, "{\"state\":\"open\"}")));
    assertEquals(
        "{\"turnStartEntryId\":\""
            + UUID_7
            + "\",\"outcome\":\"COMPLETED\",\"continueModel\":true,"
            + "\"reason\":null,\"closeRequestId\":null}",
        CODEC.encode(new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, true, null, null)));
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
            + "\"assistantMetadata\":null,\"toolResultMetadata\":null}",
        CODEC.encode(new MessagePayload(user("hi"), null, null)));
    assertEquals(
        "{\"attempt\":{\"attempt\":1,\"sequence\":7,\"text\":\"partial\",\"thinking\":\"\"},"
            + "\"error\":{\"code\":\"TRANSIENT\",\"message\":\"down\"},"
            + "\"retryAt\":\"2026-01-01T00:00:02Z\"}",
        CODEC.encode(
            new ModelAttemptFailurePayload(
                new ModelAttemptSnapshot(1, 7, "partial", ""),
                new AssistantError("TRANSIENT", "down"),
                Instant.parse("2026-01-01T00:00:02Z"))));
    assertEquals(
        "{\"error\":{\"code\":\"MODEL_FAILED\",\"message\":\"down\"},"
            + "\"attempt\":{\"attempt\":2,\"sequence\":9,\"text\":\"final\",\"thinking\":\"\"}}",
        CODEC.encode(
            new AssistantErrorPayload(
                new AssistantError("MODEL_FAILED", "down"),
                new ModelAttemptSnapshot(2, 9, "final", ""))));
  }

  @Test
  void roundTripsMinimalCompactionPayload() {
    // Durable result 只保存 summaryText；phase/trigger/cut 位于 enclosing TURN_START。
    CompactionPayload payload = new CompactionPayload("structured summary");

    assertEquals("{\"summaryText\":\"structured summary\"}", CODEC.encode(payload));
    assertEquals(payload, CODEC.decode(EntryType.COMPACTION, CODEC.encode(payload)));
    assertEquals(payload, CODEC.decodeNode(EntryType.COMPACTION, CODEC.encodeNode(payload)));
  }

  @Test
  void roundTripsCompactionStartMetadataInsideTurnStart() {
    // Compaction 的全部执行事实只在 TURN_START 冻结一次。
    CompactionStart start =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.MANUAL,
            settings().model(),
            id(4L),
            id(3L),
            id(2L));
    TurnStartPayload payload =
        new TurnStartPayload(
            TurnStartReason.COMPACTION, settings(), OWNER_THREAD_ID, 4096, 1024, start);

    assertEquals(payload, CODEC.decode(EntryType.TURN_START, CODEC.encode(payload)));
    assertEquals(payload, CODEC.decodeNode(EntryType.TURN_START, CODEC.encodeNode(payload)));
  }

  @Test
  void compactionCodecRejectsUnknownAndWrongTypedFields() {
    String canonical = "{\"summaryText\":\"summary\"}";
    assertEquals(new CompactionPayload("summary"), CODEC.decode(EntryType.COMPACTION, canonical));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.COMPACTION, "{\"summaryText\":\"summary\",\"tokensBefore\":500}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.COMPACTION, "{\"summaryText\":5}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.COMPACTION, "{\"summaryText\":\" \"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.COMPACTION, "{\"summaryText\":\"a\",\"summaryText\":\"b\"}"));
  }

  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"environmentName\":null,"
                    + "\"settings\":{\"agentName\":\"b\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"environmentName\":null}}"));
    assertThrows(
        IllegalArgumentException.class, () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{}} {}"));
  }

  /** 测试意图：三字段 branch settings 是唯一 durable 形态——旧的两字段 shape 与缺失 environmentName 必须严格拒绝。 */
  @Test
  void rejectsLegacyBranchSettingsShapeWithoutEnvironmentName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}},"
                    + "\"subagentContext\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":\" \"},\"subagentContext\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":\"env/a\"},\"subagentContext\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":5},\"subagentContext\":null}"));
    // 正例：canonical 环境名往返，且 null 与文本都会被精确保留。
    assertEquals(
        new RootPayload(settings()),
        CODEC.decode(EntryType.ROOT, CODEC.encode(new RootPayload(settings()))));
    BranchSettings withEnvironment = settings().withEnvironmentName("local");
    assertEquals("local", withEnvironment.environmentName());
    assertEquals(
        new RootPayload(withEnvironment),
        CODEC.decode(EntryType.ROOT, CODEC.encode(new RootPayload(withEnvironment))));
  }

  /** 测试意图：goal 属于 settings 完整快照——null 与 {id,text} 精确往返；缺失字段、缺 text、非对象、非法 UUID 都确定性拒绝。 */
  @Test
  void roundTripsGoalSettingAndRejectsMalformedShapes() {
    BranchSettings withGoal = settings().withGoal(new GoalSetting(id(700L), "ship it"));
    assertEquals(
        new RootPayload(withGoal),
        CODEC.decode(EntryType.ROOT, CODEC.encode(new RootPayload(withGoal))));
    assertEquals(
        new RootPayload(settings()),
        CODEC.decode(EntryType.ROOT, CODEC.encode(new RootPayload(settings()))));

    String base =
        "\"agentName\":\"a\",\"model\":{\"providerName\":\"p\",\"modelName\":\"m\","
            + "\"variant\":\"v\"},\"environmentName\":null";
    // 缺失 goal 字段（旧 shape）拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(EntryType.ROOT, "{\"settings\":{" + base + "},\"subagentContext\":null}"));
    // 缺 text / 非对象 / 非 UUID id 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{"
                    + base
                    + ",\"goal\":{\"id\":\""
                    + id(700L)
                    + "\"}},\"subagentContext\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{" + base + ",\"goal\":\"x\"},\"subagentContext\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{"
                    + base
                    + ",\"goal\":{\"id\":\"not-a-uuid\",\"text\":\"x\"}},"
                    + "\"subagentContext\":null}"));
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
                "{\"reason\":5,\"settings\":{\"agentName\":\"a\","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"agentName\":\"a\","
                    + "\"model\":[\"p\"]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"agentName\":5,"
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}"));
    // 旧 workspacePath 字段已整体删除：携带它（无论字符串还是数字）一律严格拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"workspacePath\":\"../outside\","
                    + "\"agentName\":\"a\",\"model\":{\"providerName\":\"p\",\"modelName\":\"m\","
                    + "\"variant\":\"v\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"workspacePath\":5,"
                    + "\"agentName\":\"a\",\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_START,
                "{\"reason\":\"INPUT\",\"settings\":{\"agentName\":\" \","
                    + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}"));
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
                "{\"turnStartEntryId\":\""
                    + UUID_7
                    + "\",\"outcome\":\"COMPLETED\","
                    + "\"continueModel\":\"yes\",\"reason\":null,\"closeRequestId\":null}"));
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
                turnEndWith("{\"turnStartEntryId\":\"" + UUID_7 + "\",\"outcome\":\"FOO\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith("{\"turnStartEntryId\":\"not-a-uuid\",\"outcome\":\"COMPLETED\",")));
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
                turnEndWith("{\"turnStartEntryId\":7,\"outcome\":\"COMPLETED\",")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith(
                    "{\"turnStartEntryId\":\""
                        + UUID_7
                        + "\",\"outcome\":\"COMPLETED\","
                        + "\"continueModel\":true,\"reason\":5,")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                turnEndWith(
                    "{\"turnStartEntryId\":\""
                        + UUID_7
                        + "\",\"outcome\":\"COMPLETED\","
                        + "\"continueModel\":true,\"reason\":null,\"closeRequestId\":5}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.TURN_END,
                "{\"turnStartEntryId\":\""
                    + UUID_7
                    + "\",\"outcome\":\"COMPLETED\",\"continueModel\":true,"
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
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_0
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":-1,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":0,\"status\":\"FOO\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":0,\"status\":\"SUCCEEDED\",\"synthetic\":true,\"reason\":\"HISTORY_CUT\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-9\",\"callIndex\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\"00000000-0000-0000-0000-000000000007\",\"toolCallId\":\"call-1\",\"callIndex\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":0,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":5}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":99999999999999,\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                toolMessageJson
                    + "\"toolResultMetadata\":{\"assistantEntryId\":\""
                    + UUID_2
                    + "\",\"toolCallId\":\"call-1\",\"callIndex\":\"x\",\"status\":\"SUCCEEDED\",\"synthetic\":false,\"reason\":null}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.MESSAGE, toolMessageJson + "\"toolResultMetadata\":[]}"));
  }

  @Test
  void rejectsWrongTypeDispatchAndMalformedJson() {
    String rootJson = CODEC.encode(new RootPayload(settings()));
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
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":{},\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(EntryType.CUSTOM, "{\"contributorId\":\"goal\",\"customType\":\"goal\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"Goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":0,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":-1,"
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":\"1\","
                    + "\"data\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM,
                "{\"contributorId\":\"goal\",\"customType\":\"goal\",\"schemaVersion\":1,"
                    + "\"data\":null}"));
  }

  @Test
  void rejectsMalformedCustomMessageShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"Message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.CUSTOM_MESSAGE,
                "{\"contributorId\":\"core\",\"customType\":\"message\",\"rendererKey\":\"message\","
                    + "\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"s\"}]},"
                    + "\"details\":{},\"unexpected\":1}"));
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
        + "\"settings\":{\"agentName\":\"a\","
        + "\"model\":{\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}";
  }

  private static String turnEndWith(String prefix) {
    return prefix + "\"continueModel\":true,\"reason\":null,\"closeRequestId\":null}";
  }

  private static AgentMessage user(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
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

  private static BranchSettings settings() {
    return new BranchSettings(
        "coding", new ModelSelection("anthropic", "claude-sonnet", "default"), null);
  }
}
