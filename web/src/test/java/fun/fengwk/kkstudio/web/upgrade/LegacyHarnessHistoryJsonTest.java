package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link LegacyHarnessHistoryJson} 的定向转换回归：旧 8 键 tool metadata 只补显式 null binding，receipt / 数值 /
 * 其它节点 原样；provider replay 只改 affinity wire，format 与 payload 不变；已知第一方 CUSTOM / CUSTOM_MESSAGE 与
 * SET_CONTRIBUTOR_STATE 命令按 producer 语义校验后逐字节原样；所有失败都是固定安全消息。
 */
class LegacyHarnessHistoryJsonTest {

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();
  private static final ProviderReplayStateJsonCodec REPLAY_CODEC =
      new ProviderReplayStateJsonCodec();
  private static final ThreadCommandPayloadJsonCodec COMMAND_CODEC =
      new ThreadCommandPayloadJsonCodec();
  private static final ProjectRunScopeJsonCodec RUN_SCOPE_CODEC = new ProjectRunScopeJsonCodec();

  /** 冻结 DEV 源 writer（commit 8126841e1）离线产出的已知第一方 wire；见类尾 provenance 注释。 */
  private static final String LEGACY_GOAL_COMPLETE = "legacy-custom-goal-progress-complete.json";

  private static final String LEGACY_GOAL_BLOCKED = "legacy-custom-goal-progress-blocked.json";
  private static final String LEGACY_RUN_ACTIVE = "legacy-custom-project-run-active.json";
  private static final String LEGACY_RUN_CLOSED = "legacy-custom-project-run-closed.json";
  private static final String LEGACY_CORE_MESSAGE = "legacy-custom-core-message.json";
  private static final String LEGACY_STATE_COMMAND_GOAL =
      "legacy-command-set-contributor-state-goal.json";
  private static final String LEGACY_STATE_COMMAND_RUN =
      "legacy-command-set-contributor-state-run.json";
  private static final String LEGACY_USER_COMMAND = "legacy-command-user-message.json";

  private static final String LEGACY_TOOL_RESULT = "legacy-tool-result-message.json";
  private static final String LEGACY_SYNTHETIC_TOOL_RESULT =
      "legacy-synthetic-tool-result-message.json";
  private static final String LEGACY_TOOL_RESULT_WITH_RECEIPT =
      "legacy-tool-result-message-with-receipt.json";
  private static final String SETTLED_TOOL_RESULT =
      "settled-tool-result-message-with-null-binding.json";
  private static final String CURRENT_USER_MESSAGE = "current-user-message.json";
  private static final String CURRENT_ROOT_ENTRY = "current-root-entry.json";
  private static final String LEGACY_REPLAY = "legacy-provider-replay-openai-response.json";
  private static final String ANTHROPIC_REPLAY = "other-provider-replay-anthropic.json";
  private static final String CURRENT_REPLAY = "current-provider-replay-openai-responses.json";

  /** 三种真实旧 tool metadata（成功 / 合成 history-cut / 人工输入回执）都只被补上显式 {@code "binding":null}，其余节点逐字段相同。 */
  @ParameterizedTest
  @ValueSource(
      strings = {LEGACY_TOOL_RESULT, LEGACY_SYNTHETIC_TOOL_RESULT, LEGACY_TOOL_RESULT_WITH_RECEIPT})
  void addsOnlyAnExplicitNullBindingToLegacyToolResultMetadata(String fixture) {
    String legacy = fixture(fixture);
    // 旧 8 键形态本来就不能通过当前 codec：这正是本条目标结构缺口。
    assertThrows(
        IllegalArgumentException.class, () -> ENTRY_CODEC.decode(EntryType.MESSAGE, legacy));

    String upgraded = LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, legacy);
    MessagePayload payload = (MessagePayload) ENTRY_CODEC.decode(EntryType.MESSAGE, upgraded);
    ToolResultMetadata metadata = payload.toolResultMetadata();
    assertNull(metadata.binding());

    JsonNode source = JsonValues.readTree(legacy);
    JsonNode result = JsonValues.readTree(upgraded);
    assertEquals(source.get("message"), result.get("message"));
    assertEquals(source.get("assistantMetadata"), result.get("assistantMetadata"));
    ObjectNode sourceMetadata = (ObjectNode) source.get("toolResultMetadata");
    ObjectNode resultMetadata = (ObjectNode) result.get("toolResultMetadata");
    assertEquals(8, sourceMetadata.size());
    assertEquals(9, resultMetadata.size());
    assertTrue(resultMetadata.get("binding").isNull());
    assertFalse(sourceMetadata.has("binding"));
    resultMetadata.remove("binding");
    assertEquals(sourceMetadata, resultMetadata);
  }

  /** 合成 history-cut 结果经转换后仍是 UNKNOWN + HISTORY_CUT + 无 invocation，绝不把 null 捏造成当前 binding。 */
  @Test
  void keepsSyntheticHistoryCutFactsAndDoesNotInventABinding() {
    String upgraded =
        LegacyHarnessHistoryJson.entryPayload(
            EntryType.MESSAGE, fixture(LEGACY_SYNTHETIC_TOOL_RESULT));
    ToolResultMetadata metadata =
        ((MessagePayload) ENTRY_CODEC.decode(EntryType.MESSAGE, upgraded)).toolResultMetadata();
    assertEquals(ToolResultStatus.UNKNOWN, metadata.status());
    assertTrue(metadata.synthetic());
    assertEquals(ToolResultReason.HISTORY_CUT, metadata.reason());
    assertNull(metadata.invocationId());
    assertNull(metadata.inputReceipt());
    assertNull(metadata.binding());
  }

  /** 失败、取消与人工判定 UNKNOWN 的真实调用同样保留原 invocation，不借当前目录补造 binding。 */
  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "CANCELLED", "UNKNOWN"})
  void preservesEveryOtherRealTerminalStatus(String status) {
    ObjectNode source = (ObjectNode) JsonValues.readTree(fixture(LEGACY_TOOL_RESULT));
    ObjectNode sourceMetadata = (ObjectNode) source.get("toolResultMetadata");
    sourceMetadata.put("status", status);
    String upgraded =
        LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, JsonValues.write(source));
    ToolResultMetadata metadata =
        ((MessagePayload) ENTRY_CODEC.decode(EntryType.MESSAGE, upgraded)).toolResultMetadata();
    assertEquals(ToolResultStatus.valueOf(status), metadata.status());
    assertEquals(
        UUID.fromString(sourceMetadata.get("invocationId").stringValue()), metadata.invocationId());
    assertNull(metadata.binding());
    ObjectNode upgradedMetadata =
        (ObjectNode) JsonValues.readTree(upgraded).get("toolResultMetadata");
    upgradedMetadata.remove("binding");
    assertEquals(sourceMetadata, upgradedMetadata);
  }

  /** 真实人工输入回执被完整保留（不是仅非 null）：提交身份、操作者与接受时间逐字段相同。 */
  @Test
  void preservesTheRealInputReceiptExactly() {
    JsonNode source = JsonValues.readTree(fixture(LEGACY_TOOL_RESULT_WITH_RECEIPT));
    String upgraded =
        LegacyHarnessHistoryJson.entryPayload(
            EntryType.MESSAGE, fixture(LEGACY_TOOL_RESULT_WITH_RECEIPT));
    ToolResultMetadata metadata =
        ((MessagePayload) ENTRY_CODEC.decode(EntryType.MESSAGE, upgraded)).toolResultMetadata();
    ToolInputReceipt receipt = metadata.inputReceipt();
    assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000000c3"), receipt.submissionId());
    assertEquals("operator", receipt.actor());
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), receipt.acceptedAt());
    assertEquals(
        source.get("toolResultMetadata").get("inputReceipt"),
        JsonValues.readTree(upgraded).get("toolResultMetadata").get("inputReceipt"));
  }

  /** 非 MESSAGE 与没有 tool metadata 的 MESSAGE 在目标已合法时原样返回。 */
  @Test
  void returnsAlreadyLegalPayloadsUnchanged() {
    String root = fixture(CURRENT_ROOT_ENTRY);
    assertEquals(root, LegacyHarnessHistoryJson.entryPayload(EntryType.ROOT, root));

    String message = fixture(CURRENT_USER_MESSAGE);
    assertEquals(message, LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, message));
  }

  /** 已带 binding（哪怕显式 null）不是精确旧源形态，绝不静默覆盖；漏键的旧 metadata 同样拒绝。 */
  @Test
  void rejectsMetadataThatIsNotTheExactLegacyShape() {
    String settled = fixture(SETTLED_TOOL_RESULT);
    // 该 9 键文本本身已经是当前合法目标结构，但仍不是可安全升级的旧源。
    assertNotNull(ENTRY_CODEC.decode(EntryType.MESSAGE, settled));
    IllegalArgumentException bindingError =
        assertThrows(
            IllegalArgumentException.class,
            () -> LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, settled));
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY, bindingError.getMessage());
    assertNull(bindingError.getCause());

    ObjectNode incomplete = (ObjectNode) JsonValues.readTree(fixture(LEGACY_TOOL_RESULT));
    ((ObjectNode) incomplete.get("toolResultMetadata")).remove("invocationId");
    IllegalArgumentException missingFieldError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LegacyHarnessHistoryJson.entryPayload(
                    EntryType.MESSAGE, JsonValues.write(incomplete)));
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY,
        missingFieldError.getMessage());
  }

  /** 少了第一方身份字段（或整体非对象）的 CUSTOM / CUSTOM_MESSAGE 依旧是不可升级的旧源；null 类型固定报错。 */
  @Test
  void rejectsUnsupportedEntryTypes() {
    String root = fixture(CURRENT_ROOT_ENTRY);
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM, root))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_MESSAGE_UNSUPPORTED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM_MESSAGE, root))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM, "[1, 2]"))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_TYPE_REQUIRED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(null, root))
            .getMessage());
  }

  /** 畸形入口 JSON（含前缀秘密内容）只收敛为固定安全消息，不回显输入、不携带 cause。 */
  @ParameterizedTest
  @ValueSource(
      strings = {"", "   ", "null", "{", "[]", "[1, 2]", "{\"part\":\"sk-SECRET-DEADBEEF\""})
  void rejectsMalformedEntryJsonWithoutEchoingInput(String malformed) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> LegacyHarnessHistoryJson.entryPayload(EntryType.ROOT, malformed));
    assertEquals(LegacyHarnessHistoryJson.ENTRY_MALFORMED, error.getMessage());
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains("sk-SECRET-DEADBEEF"));
  }

  /** 严格拒绝重复字段与尾随 token，即使内容看起来合法。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    String root = fixture(CURRENT_ROOT_ENTRY);
    String duplicateMetadata =
        fixture(LEGACY_TOOL_RESULT)
            .replace(
                "\"status\": \"SUCCEEDED\",",
                "\"status\": \"SUCCEEDED\", \"status\": \"SUCCEEDED\",");
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, duplicateMetadata))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.ENTRY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.ROOT, root + " 0"))
            .getMessage());
  }

  /** 输入本身合法但目标非法（多余根字段）时收敛为固定 ENTRY_TARGET_REJECTED。 */
  @Test
  void rejectsConvertedEntryRejectedByTheTargetCodec() {
    ObjectNode extra = (ObjectNode) JsonValues.readTree(fixture(LEGACY_TOOL_RESULT));
    extra.put("unexpected", true);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LegacyHarnessHistoryJson.entryPayload(EntryType.MESSAGE, JsonValues.write(extra)));
    assertEquals(LegacyHarnessHistoryJson.ENTRY_TARGET_REJECTED, error.getMessage());
    assertNull(error.getCause());
  }

  // ---------- 已知第一方 CUSTOM / CUSTOM_MESSAGE ----------

  /**
   * 已知 {@code builtin/goal.progress/1} 终态声明：转换器逐字节原样返回（reason 文本不改写），目标 codec 解码后业务字段 与源 wire
   * 逐字段相同。
   */
  @ParameterizedTest
  @ValueSource(strings = {LEGACY_GOAL_COMPLETE, LEGACY_GOAL_BLOCKED})
  void preservesKnownGoalProgressCustomEntryVerbatim(String fixture) {
    String legacy = fixture(fixture);
    String upgraded = LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM, legacy);
    assertEquals(legacy, upgraded);

    CustomEntryPayload payload =
        (CustomEntryPayload) ENTRY_CODEC.decode(EntryType.CUSTOM, upgraded);
    assertEquals("builtin", payload.contributorId());
    assertEquals("goal.progress", payload.customType());
    assertEquals(1, payload.schemaVersion());
    assertEquals(JsonValues.readTree(legacy).get("data"), JsonValues.readTree(payload.dataJson()));
  }

  /** 未知 contributorId / customType / schemaVersion 组合在任何 data 之前就被拒绝。 */
  @Test
  void rejectsUnknownCustomProducersAndVersions() {
    assertUnsupportedCustomEntry(
        entry -> entry.put("contributorId", "other"),
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED);
    assertUnsupportedCustomEntry(
        entry -> entry.put("customType", "goal.other"),
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED);
    assertUnsupportedCustomEntry(
        entry -> entry.put("schemaVersion", 2), LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED);
    assertUnsupportedCustomEntry(
        entry -> entry.put("schemaVersion", "1"),
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED);
    assertUnsupportedCustomEntry(
        entry -> entry.put("contributorId", 5), LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED);
  }

  /** 各种非旧形态的 goal 终态 data（含错误状态 / 大小写、错 UUID / Instant、空白 reason、字段键集与类型）都固定拒绝。 */
  @Test
  void rejectsNonLegacyKnownGoalProgressData() {
    assertNonLegacyCustomData(data -> data.put("status", "active"));
    assertNonLegacyCustomData(data -> data.put("status", "COMPLETE"));
    assertNonLegacyCustomData(data -> data.put("goalId", "not-a-uuid"));
    assertNonLegacyCustomData(data -> data.put("reportedAt", "2026-02-03 04:05:06"));
    assertNonLegacyCustomData(data -> data.put("reason", "   "));
    assertNonLegacyCustomData(data -> data.put("goalId", 5));
    assertNonLegacyCustomData(data -> data.putNull("reason"));
    assertNonLegacyCustomData(data -> data.put("extra", 1));
    assertNonLegacyCustomData(data -> data.remove("reason"));
    assertNonLegacyCustomData(data -> data.remove("reportedAt"));

    ObjectNode entry = (ObjectNode) JsonValues.readTree(fixture(LEGACY_GOAL_COMPLETE));
    entry.putArray("data");
    assertEntryMessage(
        JsonValues.write(entry), LegacyHarnessHistoryJson.ENTRY_CUSTOM_DATA_NOT_LEGACY);
  }

  /** 已知 {@code project/run/1} 快照：active / closed 两态都逐字节原样，目标 codec 解码后业务字段与源 wire 相同。 */
  @ParameterizedTest
  @ValueSource(strings = {LEGACY_RUN_ACTIVE, LEGACY_RUN_CLOSED})
  void preservesKnownProjectRunCustomEntryVerbatim(String fixture) {
    String legacy = fixture(fixture);
    String upgraded = LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM, legacy);
    assertEquals(legacy, upgraded);

    CustomEntryPayload payload =
        (CustomEntryPayload) ENTRY_CODEC.decode(EntryType.CUSTOM, upgraded);
    assertEquals("project", payload.contributorId());
    assertEquals("run", payload.customType());
    assertEquals(1, payload.schemaVersion());
    assertEquals(JsonValues.readTree(legacy).get("data"), JsonValues.readTree(payload.dataJson()));
  }

  /** 复用目标 ProjectRunScopeJsonCodec：active=true/false、nullable 与非空、空数组、long 边界都逐字段读回。 */
  @Test
  void decodesKnownProjectRunScopeAcrossActiveAndClosedVariants() {
    ProjectRunScope active = RUN_SCOPE_CODEC.decode(customData(LEGACY_RUN_ACTIVE));
    assertTrue(active.active());
    assertEquals(9007199254740993L, active.issueNumber());
    assertEquals("Accepted: freeze the issue context for this run", active.issueDescription());
    assertEquals("Write the code, then request a handoff", active.stageInstructions());
    assertEquals(List.of("review", "done"), active.nextStates());

    ProjectRunScope closed = RUN_SCOPE_CODEC.decode(customData(LEGACY_RUN_CLOSED));
    assertFalse(closed.active());
    assertEquals(Long.MAX_VALUE, closed.issueNumber());
    assertNull(closed.issueDescription());
    assertNull(closed.stageInstructions());
    assertTrue(closed.nextStates().isEmpty());
  }

  /** 目标 project codec 的 long 范围校验不允许截断；越界 issueNumber 与错误类型都固定拒绝。 */
  @Test
  void rejectsProjectRunDataOutsideTheTargetContract() {
    ObjectNode beyondLong = (ObjectNode) JsonValues.readTree(fixture(LEGACY_RUN_ACTIVE));
    ((ObjectNode) beyondLong.get("data")).put("issueNumber", new BigInteger("9223372036854775808"));
    assertEntryMessage(
        JsonValues.write(beyondLong), LegacyHarnessHistoryJson.ENTRY_CUSTOM_DATA_NOT_LEGACY);

    assertNonLegacyRunData(data -> data.put("active", "true"));
    assertNonLegacyRunData(data -> data.put("issueNumber", 1.5));
    assertNonLegacyRunData(data -> data.remove("nextStates"));
    assertNonLegacyRunData(data -> data.putArray("nextStates").add(" "));
    assertNonLegacyRunData(data -> data.put("extra", 1));

    ObjectNode arrayData = (ObjectNode) JsonValues.readTree(fixture(LEGACY_RUN_ACTIVE));
    arrayData.putArray("data");
    assertEntryMessage(
        JsonValues.write(arrayData), LegacyHarnessHistoryJson.ENTRY_CUSTOM_DATA_NOT_LEGACY);
  }

  /**
   * TurnPlanBuilder 固定的 {@code core/message/message} CUSTOM_MESSAGE 逐字节原样，message 由当前 entry codec
   * 校验。
   */
  @Test
  void preservesKnownCoreCustomMessageVerbatim() {
    String legacy = fixture(LEGACY_CORE_MESSAGE);
    String upgraded = LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM_MESSAGE, legacy);
    assertEquals(legacy, upgraded);

    CustomMessagePayload payload =
        (CustomMessagePayload) ENTRY_CODEC.decode(EntryType.CUSTOM_MESSAGE, upgraded);
    assertEquals("core", payload.contributorId());
    assertEquals("message", payload.customType());
    assertEquals("message", payload.rendererKey());
    assertEquals("{}", payload.detailsJson());
    // 完整重编码后与源 wire 同构：message 子树的每个字段都没有被改变。
    assertEquals(JsonValues.readTree(legacy), JsonValues.readTree(ENTRY_CODEC.encode(payload)));
  }

  /** 未知 renderer / contributor、非空或缺失 details 都不是可安全升级的旧第一方消息。 */
  @Test
  void rejectsUnknownCustomMessages() {
    assertUnsupportedCustomMessage(message -> message.put("rendererKey", "other"));
    assertUnsupportedCustomMessage(message -> message.put("customType", "other"));
    assertUnsupportedCustomMessage(message -> message.put("contributorId", "other"));
    assertUnsupportedCustomMessage(message -> message.remove("details"));
    assertUnsupportedCustomMessage(message -> message.putNull("details"));
    assertUnsupportedCustomMessage(message -> message.putArray("details"));
    assertUnsupportedCustomMessage(
        message -> ((ObjectNode) message.get("details")).put("priority", 1));
  }

  /** 已知 CUSTOM 组合仍受当前 entry codec 的外层键集约束；重复字段与尾随 token 在严格解析阶段拒绝。 */
  @Test
  void rejectsKnownCustomEntriesRejectedByTheTargetCodec() {
    ObjectNode extra = (ObjectNode) JsonValues.readTree(fixture(LEGACY_GOAL_COMPLETE));
    extra.put("unexpected", true);
    assertEntryMessage(JsonValues.write(extra), LegacyHarnessHistoryJson.ENTRY_TARGET_REJECTED);

    assertEntryMessage(
        fixture(LEGACY_GOAL_COMPLETE)
            .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"schemaVersion\": 1,"),
        LegacyHarnessHistoryJson.ENTRY_MALFORMED);
    assertEntryMessage(
        fixture(LEGACY_GOAL_COMPLETE) + " 0", LegacyHarnessHistoryJson.ENTRY_MALFORMED);
  }

  // ---------- 已知 typed Thread command ----------

  /** 普通 USER_MESSAGE 与两个已知 SET_CONTRIBUTOR_STATE 命令都不改业务值，并经当前 command codec 校验。 */
  @Test
  void passesThroughKnownCommandsVerbatim() {
    String user = fixture(LEGACY_USER_COMMAND);
    assertEquals(
        user, LegacyHarnessHistoryJson.threadCommandPayload(ThreadCommandType.USER_MESSAGE, user));
    assertNotNull(COMMAND_CODEC.decode(ThreadCommandType.USER_MESSAGE, user));

    String goal = fixture(LEGACY_STATE_COMMAND_GOAL);
    assertEquals(
        goal,
        LegacyHarnessHistoryJson.threadCommandPayload(
            ThreadCommandType.SET_CONTRIBUTOR_STATE, goal));
    SetContributorStateCommandPayload goalState =
        (SetContributorStateCommandPayload)
            COMMAND_CODEC.decode(ThreadCommandType.SET_CONTRIBUTOR_STATE, goal);
    assertEquals("builtin", goalState.state().contributorId());
    assertEquals("goal.progress", goalState.state().customType());
    assertEquals(
        JsonValues.readTree(fixture(LEGACY_GOAL_COMPLETE)).get("data"),
        JsonValues.readTree(goalState.state().dataJson()));

    String run = fixture(LEGACY_STATE_COMMAND_RUN);
    assertEquals(
        run,
        LegacyHarnessHistoryJson.threadCommandPayload(
            ThreadCommandType.SET_CONTRIBUTOR_STATE, run));
    SetContributorStateCommandPayload runState =
        (SetContributorStateCommandPayload)
            COMMAND_CODEC.decode(ThreadCommandType.SET_CONTRIBUTOR_STATE, run);
    assertEquals("project", runState.state().contributorId());
    assertTrue(RUN_SCOPE_CODEC.decode(runState.state().dataJson()).active());
  }

  /** 未知 / 损坏的 contributor state 固定拒绝，绝不当成可升级的旧第一方状态放过。 */
  @Test
  void rejectsUnknownOrCorruptContributorStates() {
    assertCommand(
        fixture(LEGACY_STATE_COMMAND_GOAL)
            .replace("\"contributorId\": \"builtin\"", "\"contributorId\": \"other\""),
        LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
    assertCommand(
        fixture(LEGACY_STATE_COMMAND_GOAL).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
    assertCommand(
        fixture(LEGACY_STATE_COMMAND_GOAL)
            .replace("\"status\": \"complete\"", "\"status\": \"active\""),
        LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
    assertCommand(
        fixture(LEGACY_STATE_COMMAND_RUN)
            .replace("\"issueNumber\": 9007199254740993", "\"issueNumber\": 9223372036854775808"),
        LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
    assertCommand("{\"state\":[]}", LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
    assertCommand("{}", LegacyHarnessHistoryJson.COMMAND_STATE_NOT_KNOWN);
  }

  /** null 类型、非对象、重复字段、尾随 token 与当前 codec 的非法 shape 都收敛为固定安全消息。 */
  @Test
  void rejectsMalformedOrTargetInvalidCommands() {
    assertNull(
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.threadCommandPayload(null, "{}"))
            .getCause());
    assertEquals(
        LegacyHarnessHistoryJson.COMMAND_TYPE_REQUIRED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.threadCommandPayload(null, "{}"))
            .getMessage());
    assertCommand("[1, 2]", LegacyHarnessHistoryJson.COMMAND_MALFORMED);
    assertCommand("{\"state\":{},\"state\":{}}", LegacyHarnessHistoryJson.COMMAND_MALFORMED);
    assertCommand(fixture(LEGACY_USER_COMMAND) + " 0", LegacyHarnessHistoryJson.COMMAND_MALFORMED);

    ObjectNode extraState = (ObjectNode) JsonValues.readTree(fixture(LEGACY_STATE_COMMAND_GOAL));
    extraState.put("unexpected", true);
    assertCommand(JsonValues.write(extraState), LegacyHarnessHistoryJson.COMMAND_TARGET_REJECTED);

    ObjectNode extraUser = (ObjectNode) JsonValues.readTree(fixture(LEGACY_USER_COMMAND));
    extraUser.put("unexpected", "sk-SECRET-DEADBEEF");
    IllegalArgumentException targetRejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LegacyHarnessHistoryJson.threadCommandPayload(
                    ThreadCommandType.USER_MESSAGE, JsonValues.write(extraUser)));
    assertEquals(LegacyHarnessHistoryJson.COMMAND_TARGET_REJECTED, targetRejected.getMessage());
    assertNull(targetRejected.getCause());
    assertFalse(targetRejected.getMessage().contains("sk-SECRET-DEADBEEF"));

    assertCommand(
        ThreadCommandType.GOAL, "{\"text\": 5}", LegacyHarnessHistoryJson.COMMAND_TARGET_REJECTED);
  }

  private static void assertUnsupportedCustomEntry(
      Consumer<ObjectNode> mutation, String expectedMessage) {
    ObjectNode entry = (ObjectNode) JsonValues.readTree(fixture(LEGACY_GOAL_COMPLETE));
    mutation.accept(entry);
    assertEntryMessage(JsonValues.write(entry), expectedMessage);
  }

  private static void assertNonLegacyCustomData(Consumer<ObjectNode> mutation) {
    ObjectNode entry = (ObjectNode) JsonValues.readTree(fixture(LEGACY_GOAL_COMPLETE));
    mutation.accept((ObjectNode) entry.get("data"));
    assertEntryMessage(
        JsonValues.write(entry), LegacyHarnessHistoryJson.ENTRY_CUSTOM_DATA_NOT_LEGACY);
  }

  private static void assertNonLegacyRunData(Consumer<ObjectNode> mutation) {
    ObjectNode entry = (ObjectNode) JsonValues.readTree(fixture(LEGACY_RUN_ACTIVE));
    mutation.accept((ObjectNode) entry.get("data"));
    assertEntryMessage(
        JsonValues.write(entry), LegacyHarnessHistoryJson.ENTRY_CUSTOM_DATA_NOT_LEGACY);
  }

  private static void assertUnsupportedCustomMessage(Consumer<ObjectNode> mutation) {
    ObjectNode message = (ObjectNode) JsonValues.readTree(fixture(LEGACY_CORE_MESSAGE));
    mutation.accept(message);
    assertEntryMessage(
        EntryType.CUSTOM_MESSAGE,
        JsonValues.write(message),
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_MESSAGE_UNSUPPORTED);
  }

  private static void assertEntryMessage(String json, String expected) {
    assertEntryMessage(EntryType.CUSTOM, json, expected);
  }

  private static void assertEntryMessage(EntryType type, String json, String expected) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> LegacyHarnessHistoryJson.entryPayload(type, json));
    assertEquals(expected, error.getMessage());
    assertNull(error.getCause());
  }

  private static void assertCommand(String json, String expected) {
    assertCommand(ThreadCommandType.SET_CONTRIBUTOR_STATE, json, expected);
  }

  private static void assertCommand(ThreadCommandType type, String json, String expected) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> LegacyHarnessHistoryJson.threadCommandPayload(type, json));
    assertEquals(expected, error.getMessage());
    assertNull(error.getCause());
  }

  /** 取出已知 CUSTOM fixture 的 {@code data} 子树的 canonical JSON 文本。 */
  private static String customData(String fixture) {
    return JsonValues.write(JsonValues.readTree(fixture(fixture)).get("data"));
  }

  /** provider replay 只改 affinity.providerType；format 与 opaque payload（含同名字串、精确数值）语义不变。 */
  @Test
  void upgradesOnlyTheAffinityProviderTypeOfLegacyReplayState() {
    String legacy = fixture(LEGACY_REPLAY);
    assertThrows(IllegalArgumentException.class, () -> REPLAY_CODEC.decode(legacy));

    String upgraded = LegacyHarnessHistoryJson.providerReplayState(legacy);
    assertNotEquals(legacy, upgraded);
    ProviderReplayState state = REPLAY_CODEC.decode(upgraded);
    assertEquals(ProviderReplayFormat.OPENAI_RESPONSES, state.format());
    assertEquals(ProviderType.OPENAI_RESPONSES, state.affinity().providerType());
    assertEquals("openai", state.affinity().providerName());
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        state.affinity().connectionGenerationId());
    assertEquals("gpt-5", state.affinity().modelId());

    JsonNode source = JsonValues.readTree(legacy);
    JsonNode result = JsonValues.readTree(upgraded);
    assertEquals(source.get("format"), result.get("format"));
    assertEquals(source.get("payload"), result.get("payload"));
    assertEquals("openai_response", source.get("affinity").get("providerType").stringValue());
    assertEquals("openai_responses", result.get("affinity").get("providerType").stringValue());

    // payload 内的同名字串必须原样保留，绝不做全局替换。
    assertTrue(upgraded.contains("openai_response"));
    assertEquals(
        "openai_response", result.get("payload").get("request_spec").get("type").stringValue());

    JsonNode payload = result.get("payload");
    assertEquals(
        new BigInteger("123456789012345678901234567890"),
        payload.get("bigInteger").bigIntegerValue());
    assertEquals(
        BigInteger.valueOf(9007199254740993L),
        payload.get("beyondDoubleInteger").bigIntegerValue());
    assertDecimal("1234567890.123456789", 9, payload.get("longDecimal"));
    assertDecimal("1e-324", 324, payload.get("negativeExponent"));
    assertDecimal("1e309", -309, payload.get("positiveExponent"));
    assertEquals(
        "opaque-abcdefghijklmnopqrstuvwxyz-0123456789", payload.get("opaqueToken").stringValue());
  }

  /** 其它已知 provider 与已经当前的 provider 都原样返回，并且仍能通过真实 codec。 */
  @Test
  void returnsOtherKnownProviderStatesUnchanged() {
    String anthropic = fixture(ANTHROPIC_REPLAY);
    assertEquals(anthropic, LegacyHarnessHistoryJson.providerReplayState(anthropic));
    assertEquals(ProviderType.ANTHROPIC, REPLAY_CODEC.decode(anthropic).affinity().providerType());

    String current = fixture(CURRENT_REPLAY);
    assertEquals(current, LegacyHarnessHistoryJson.providerReplayState(current));
    assertEquals(
        ProviderType.OPENAI_RESPONSES, REPLAY_CODEC.decode(current).affinity().providerType());
  }

  /** SQL NULL 保持 null；JSON 字面量 null / 畸形 / 未知 provider / 目标非法都收敛为固定安全消息。 */
  @Test
  void rejectsMalformedUnknownAndTargetInvalidReplayStates() {
    assertNull(LegacyHarnessHistoryJson.providerReplayState(null));

    String legacy = fixture(LEGACY_REPLAY);
    assertEquals(
        LegacyHarnessHistoryJson.REPLAY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.providerReplayState("null"))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.REPLAY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.providerReplayState(legacy + " 0"))
            .getMessage());
    assertEquals(
        LegacyHarnessHistoryJson.REPLAY_MALFORMED,
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    LegacyHarnessHistoryJson.providerReplayState(
                        legacy.replace(
                            "\"providerType\": \"openai_response\",",
                            "\"providerType\": \"openai_response\",\"providerType\": \"openai_response\",")))
            .getMessage());

    ObjectNode missingProviderType = (ObjectNode) JsonValues.readTree(legacy);
    ((ObjectNode) missingProviderType.get("affinity")).remove("providerType");
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LegacyHarnessHistoryJson.providerReplayState(
                    JsonValues.write(missingProviderType)));
    assertEquals(LegacyHarnessHistoryJson.REPLAY_MALFORMED, missing.getMessage());
    assertNull(missing.getCause());

    IllegalArgumentException unknown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LegacyHarnessHistoryJson.providerReplayState(
                    legacy.replace("\"openai_response\"", "\"openai_response_v2\"")));
    assertEquals(LegacyHarnessHistoryJson.REPLAY_UNKNOWN_PROVIDER_TYPE, unknown.getMessage());
    assertNull(unknown.getCause());

    ObjectNode badPayload = (ObjectNode) JsonValues.readTree(legacy);
    badPayload.put("payload", "sk-SECRET-DEADBEEF");
    IllegalArgumentException targetRejected =
        assertThrows(
            IllegalArgumentException.class,
            () -> LegacyHarnessHistoryJson.providerReplayState(JsonValues.write(badPayload)));
    assertEquals(LegacyHarnessHistoryJson.REPLAY_TARGET_REJECTED, targetRejected.getMessage());
    assertNull(targetRejected.getCause());
    assertFalse(targetRejected.getMessage().contains("sk-SECRET-DEADBEEF"));
  }

  private static void assertDecimal(String expected, int scale, JsonNode actual) {
    BigDecimal value = actual.decimalValue();
    assertEquals(0, value.compareTo(new BigDecimal(expected)));
    assertEquals(scale, value.scale());
  }

  private static String fixture(String name) {
    try (InputStream in = LegacyHarnessHistoryJsonTest.class.getResourceAsStream(name)) {
      assertNotNull(in, "missing fixture " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  // Fixture provenance: the legacy-custom-* / legacy-command-* resources were generated offline by
  // the frozen
  // source writers of the read-only DEV worktree at commit 8126841e1 (identical in the MAIN
  // worktree):
  //   * goal.progress / project run data via a freshly compiled GoalProgressCodec and the source
  //     ProjectRunScopeJsonCodec;
  //   * the CUSTOM / CUSTOM_MESSAGE envelopes and the typed command payloads via the source
  //     HistoryEntryPayloadJsonCodec / ThreadCommandPayloadJsonCodec driven by the source
  //     CustomEntryPayload / CustomMessagePayload / SetContributorStateCommandPayload /
  // UserMessageCommandPayload.
  // They are stored re-indented (2 spaces) like the sibling fixtures; the exact source writer bytes
  // and their
  // sha256 are recorded in the slice NOTES. The current writer was never used to produce them.
}
