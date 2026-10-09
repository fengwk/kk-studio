package fun.fengwk.kkstudio.harness.runtime.history;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** history Entry payload codec：标准形态、严格边界拒绝与老字段拒绝。 */
class HistoryEntryPayloadJsonCodecTest {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  private static final String UUID_2 = "00000000-0000-0000-0000-000000000002";
  private static final String UUID_7 = "00000000-0000-0000-0000-000000000007";
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
            new ToolResultMetadata(
                new UUID(0L, 41L),
                id(2L),
                "call-1",
                0,
                ToolResultStatus.SUCCEEDED,
                false,
                null,
                null));
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
                null,
                id(2L),
                "call-1",
                1,
                ToolResultStatus.UNKNOWN,
                true,
                ToolResultReason.HISTORY_CUT,
                null));
    EntryPayload root = new RootPayload(settings());
    EntryPayload completedEnd =
        new TurnEndPayload(id(7L), TurnEndOutcome.COMPLETED, false, null, null);

    assertEquals(tool, CODEC.decode(EntryType.MESSAGE, CODEC.encode(tool)));
    assertEquals(root, CODEC.decode(EntryType.ROOT, CODEC.encode(root)));
    assertEquals(completedEnd, CODEC.decode(EntryType.TURN_END, CODEC.encode(completedEnd)));
  }

  /**
   * 意图：ASSISTANT metadata 的流式生成计时可选可空——带计时精确往返（canonical 字段序），旧历史缺失该字段仍可解码为 null，且重编码与旧 JSON
   * 逐字一致（不引入版本别名）。
   */
  @Test
  void roundTripsAssistantMetadataStreamTimingAndAcceptsLegacyShape() {
    String legacy =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"answer\"}]},"
            + "\"assistantMetadata\":{\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":1,"
            + "\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,"
            + "\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2}},"
            + "\"toolResultMetadata\":null}";
    MessagePayload legacyPayload = (MessagePayload) CODEC.decode(EntryType.MESSAGE, legacy);
    assertNull(legacyPayload.assistantMetadata().decodeDurationMillis());
    assertEquals(legacy, CODEC.encode(legacyPayload));

    MessagePayload timed =
        new MessagePayload(
            assistant("answer"), metadata(GenerationStopReason.COMPLETE, 1234L), null);
    assertEquals(
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"answer\"}]},"
            + "\"assistantMetadata\":{\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":1,"
            + "\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,"
            + "\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2},"
            + "\"decodeDurationMillis\":1234},\"toolResultMetadata\":null}",
        CODEC.encode(timed));
    assertEquals(timed, CODEC.decode(EntryType.MESSAGE, CODEC.encode(timed)));
  }

  /** 测试意图：人工输入回执随结果迁入 Entry 元数据后必须可无损往返，并作为 runtime 元数据显式编码（不写进业务 details）。 */
  @Test
  void roundTripsAnsweredToolResultReceiptInsideMetadata() {
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            new UUID(0L, 31L),
            new UUID(0L, 7L),
            "call-1",
            0,
            ToolResultStatus.SUCCEEDED,
            false,
            null,
            new ToolInputReceipt(
                new UUID(0L, 21L), "alice", Instant.parse("2026-09-27T00:00:00Z")));
    MessagePayload payload =
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        "call-1",
                        "ask_user",
                        "ask_user",
                        List.of(new TextMessageContent("{\"answers\":[[\"a\"]]}")),
                        false,
                        "{\"answers\":[[\"a\"]]}"))),
            null,
            metadata);

    String encoded = CODEC.encode(payload);

    assertTrue(
        encoded.contains(
            "\"inputReceipt\":{\"submissionId\":\"00000000-0000-0000-0000-000000000015\""));
    assertTrue(encoded.contains("\"invocationId\":\"00000000-0000-0000-0000-00000000001f\""));
    assertEquals(payload, CODEC.decode(EntryType.MESSAGE, encoded));
    // 没有回执的元数据仍然编码为显式 null，并保持往返。
    MessagePayload withoutReceipt =
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.TOOL,
                List.of(
                    new ToolResultMessageContent(
                        "call-1",
                        "bash",
                        "bash",
                        List.of(new TextMessageContent("ok")),
                        false,
                        "{}"))),
            null,
            new ToolResultMetadata(
                new UUID(0L, 41L),
                new UUID(0L, 7L),
                "call-1",
                0,
                ToolResultStatus.SUCCEEDED,
                false,
                null,
                null));
    assertEquals(withoutReceipt, CODEC.decode(EntryType.MESSAGE, CODEC.encode(withoutReceipt)));
    assertTrue(CODEC.encode(withoutReceipt).contains("\"inputReceipt\":null"));
  }

  /** 意图：decodeDurationMillis 的严格边界——非负整数与显式 null 合法，负值、非整数、非数字类型与未知字段一律拒绝。 */
  @Test
  void rejectsAssistantMetadataDecodeDurationViolations() {
    String prefix =
        "{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]},"
            + "\"assistantMetadata\":";
    String suffix = ",\"toolResultMetadata\":null}";
    String base =
        "{\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":1,\"outputTokens\":1,"
            + "\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteLongTokens\":0,"
            + "\"reasoningTokens\":0,\"providerTotalTokens\":2}";

    assertEquals(
        0L,
        ((MessagePayload)
                CODEC.decode(
                    EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":0}" + suffix))
            .assistantMetadata()
            .decodeDurationMillis());
    assertNull(
        ((MessagePayload)
                CODEC.decode(
                    EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":null}" + suffix))
            .assistantMetadata()
            .decodeDurationMillis());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":-1}" + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":1.5}" + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":\"12\"}" + suffix));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE, prefix + base + ",\"decodeDurationMillis\":true}" + suffix));
    // 可选字段不放松未知字段拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.MESSAGE, prefix + base + ",\"extra\":1}" + suffix));
    // 超出 long 范围的整数同样被拒绝（不静默截断）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.MESSAGE,
                prefix + base + ",\"decodeDurationMillis\":9223372036854775808}" + suffix));
  }

  @Test
  void encodesCanonicalFieldOrder() {
    assertEquals(
        "{\"settings\":{\"agentName\":\"coding\",\"model\":{"
            + "\"providerName\":\"anthropic\",\"modelName\":\"claude-sonnet\",\"variant\":\"default\"}"
            + ",\"environmentName\":null,\"goal\":null}}",
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
    // 纯摘要组装没有真实模型用量，metadata 必须显式为 null 而不是被伪造。
    CompactionPayload payload = new CompactionPayload("structured summary", null);

    assertEquals(
        "{\"summaryText\":\"structured summary\",\"assistantMetadata\":null}",
        CODEC.encode(payload));
    assertEquals(payload, CODEC.decode(EntryType.COMPACTION, CODEC.encode(payload)));
    assertEquals(payload, CODEC.decodeNode(EntryType.COMPACTION, CODEC.encodeNode(payload)));
  }

  @Test
  void roundTripsCompactionPayloadWithOriginalProviderMetadata() {
    // 正式模型输出的压缩结果把原始 stopReason/usage/decodeDuration metadata 一起 durable。
    CompactionPayload payload =
        new CompactionPayload("structured summary", metadata(GenerationStopReason.COMPLETE, 42L));

    assertEquals(payload, CODEC.decode(EntryType.COMPACTION, CODEC.encode(payload)));
    assertEquals(payload, CODEC.decodeNode(EntryType.COMPACTION, CODEC.encodeNode(payload)));
  }

  @Test
  void roundTripsCompactionStartMetadataInsideTurnStart() {
    // Compaction 的全部执行事实只在 TURN_START 冻结一次（测试完整 run group 往返）。
    CompactionStart start =
        new CompactionStart(
            CompactionPhase.TURN_PREFIX,
            CompactionTrigger.MANUAL,
            settings().model(),
            2048L,
            id(50L),
            id(51L),
            id(4L),
            id(3L),
            id(2L));
    TurnStartPayload payload =
        new TurnStartPayload(
            TurnStartReason.COMPACTION, settings(), OWNER_THREAD_ID, 4096, 1024, start);

    assertEquals(payload, CODEC.decode(EntryType.TURN_START, CODEC.encode(payload)));
    assertEquals(payload, CODEC.decodeNode(EntryType.TURN_START, CODEC.encodeNode(payload)));

    // pending compaction start（run group 全 null）往返
    CompactionStart pending =
        CompactionStart.pending(
            CompactionPhase.TURN_PREFIX, CompactionTrigger.MANUAL, id(4L), id(3L), id(2L));
    TurnStartPayload pendingPayload =
        new TurnStartPayload(
            TurnStartReason.COMPACTION, settings(), OWNER_THREAD_ID, 4096, 1024, pending);

    assertEquals(pendingPayload, CODEC.decode(EntryType.TURN_START, CODEC.encode(pendingPayload)));
    assertEquals(
        pendingPayload, CODEC.decodeNode(EntryType.TURN_START, CODEC.encodeNode(pendingPayload)));
  }

  @Test
  void compactionCodecRejectsUnknownAndWrongTypedFields() {
    String canonical = "{\"summaryText\":\"summary\",\"assistantMetadata\":null}";
    assertEquals(
        new CompactionPayload("summary", null), CODEC.decode(EntryType.COMPACTION, canonical));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.COMPACTION,
                "{\"summaryText\":\"summary\",\"assistantMetadata\":null,\"tokensBefore\":500}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.COMPACTION, "{\"summaryText\":5,\"assistantMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.COMPACTION, "{\"summaryText\":\" \",\"assistantMetadata\":null}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.COMPACTION,
                "{\"summaryText\":\"a\",\"summaryText\":\"b\",\"assistantMetadata\":null}"));
    // 最终 shape 必须显式声明 assistantMetadata：缺失即旧/损坏数据，确定性拒绝而不是猜测。
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.COMPACTION, "{\"summaryText\":\"summary\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.COMPACTION, "{\"summaryText\":\"summary\",\"assistantMetadata\":\"x\"}"));
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
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":\" \"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":\"env/a\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"}"
                    + ",\"environmentName\":5}}"));
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
        () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{" + base + "}}"));
    // 缺 text / 非对象 / 非 UUID id 拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{" + base + ",\"goal\":{\"id\":\"" + id(700L) + "\"}}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{" + base + ",\"goal\":\"x\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{" + base + ",\"goal\":{\"id\":\"not-a-uuid\",\"text\":\"x\"}}}"));
  }

  @Test
  void rejectsUnknownMissingAndWrongTypedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.ROOT, "{\"settings\":{},\"extra\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CODEC.decode(
                EntryType.ROOT,
                "{\"settings\":{\"agentName\":\"a\",\"model\":{"
                    + "\"providerName\":\"p\",\"modelName\":\"m\",\"variant\":\"v\"},"
                    + "\"environmentName\":null,\"goal\":null},\"subagentContext\":null}"));
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
                    + "\"cacheWriteLongTokens\":0,\"reasoningTokens\":0,\"providerTotalTokens\":2}},"
                    + "\"toolResultMetadata\":null}"));
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

  /**
   * 测试意图：NOTIFICATION Entry 四字段严格往返，canonical 字段序固定为 {@code
   * {notificationId,kind,sourceThreadId,message}}，message 只允许 USER 角色。
   */
  @Test
  void roundTripsNotificationPayloadWithStrictFieldOrder() {
    NotificationPayload payload =
        new NotificationPayload(
            new UUID(0L, 21L),
            NotificationKind.SUBAGENT_RESULT,
            new UUID(0L, 7L),
            user("child finished"));
    String json = CODEC.encode(payload);
    assertEquals(
        "{\"notificationId\":\"00000000-0000-0000-0000-000000000015\","
            + "\"kind\":\"SUBAGENT_RESULT\","
            + "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\","
            + "\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\","
            + "\"text\":\"child finished\"}]}}",
        json);
    assertEquals(payload, CODEC.decode(EntryType.NOTIFICATION, json));
    assertEquals(payload, CODEC.decodeNode(EntryType.NOTIFICATION, CODEC.encodeNode(payload)));
    // 通知类型是受限枚举：TASK_BUDGET 同样精确编码/解码，不落回自由文本。
    NotificationPayload budget =
        new NotificationPayload(
            new UUID(0L, 22L), NotificationKind.TASK_BUDGET, new UUID(0L, 7L), user("budget"));
    assertEquals(budget, CODEC.decode(EntryType.NOTIFICATION, CODEC.encode(budget)));
  }

  /** 测试意图：非 USER 角色的 NOTIFICATION 必须被拒绝，禁止把 assistant/tool 消息伪装成系统通知。 */
  @Test
  void rejectsNotificationWithNonUserMessageRole() {
    String assistantMessage =
        "{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"x\"}]}";
    assertThrows(
        IllegalArgumentException.class,
        () -> CODEC.decode(EntryType.NOTIFICATION, notificationJson(assistantMessage)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NotificationPayload(
                new UUID(0L, 21L),
                NotificationKind.SUBAGENT_RESULT,
                new UUID(0L, 7L),
                assistant("x")));
  }

  /** 测试意图：NOTIFICATION 四字段全必填；缺失、未知、kind 越界与类型错误都必须严格拒绝且不丢字段。 */
  @Test
  void rejectsNotificationMissingUnknownOrWrongTypedFields() {
    String message = "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"n\"}]}";
    String id21 = "\"notificationId\":\"00000000-0000-0000-0000-000000000015\"";
    String kind = "\"kind\":\"SUBAGENT_RESULT\"";
    String id7 = "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\"";
    String msg = "\"message\":" + message;

    for (String json :
        List.of(
            "{" + kind + "," + id7 + "," + msg + "}", // 缺 notificationId
            "{" + id21 + "," + id7 + "," + msg + "}", // 缺 kind
            "{" + id21 + "," + kind + "," + msg + "}", // 缺 sourceThreadId
            "{" + id21 + "," + kind + "," + id7 + "}", // 缺 message
            "{" + id21 + "," + kind + "," + id7 + "," + msg + ",\"extra\":1}", // 未知字段
            "{" + id21 + ",\"kind\":\"BOGUS\"," + id7 + "," + msg + "}", // kind 越界
            "{" + id21 + ",\"kind\":\"subagent_result\"," + id7 + "," + msg + "}", // kind 大小写不放松
            "{\"notificationId\":\"not-a-uuid\"," + kind + "," + id7 + "," + msg + "}",
            "{" + id21 + "," + kind + ",\"sourceThreadId\":\"not-a-uuid\"," + msg + "}",
            "{" + id21 + "," + kind + "," + id7 + ",\"message\":5}",
            "{" + id21 + "," + kind + "," + id7 + ",\"message\":\"text\"}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> CODEC.decode(EntryType.NOTIFICATION, json),
          "NOTIFICATION must reject: " + json);
    }
  }

  private static String notificationJson(String messageJson) {
    return "{\"notificationId\":\"00000000-0000-0000-0000-000000000015\","
        + "\"kind\":\"SUBAGENT_RESULT\","
        + "\"sourceThreadId\":\"00000000-0000-0000-0000-000000000007\","
        + "\"message\":"
        + messageJson
        + "}";
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
    return new AssistantMessageMetadata(reason, usage);
  }

  private static AssistantMessageMetadata metadata(
      GenerationStopReason reason, Long decodeDurationMillis) {
    AssistantMessageMetadata base = metadata(reason);
    return new AssistantMessageMetadata(base.stopReason(), base.usage(), decodeDurationMillis);
  }

  private static BranchSettings settings() {
    return new BranchSettings(
        "coding", new ModelSelection("anthropic", "claude-sonnet", "default"), null);
  }
}
