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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link LegacyHarnessHistoryJson} 的定向转换回归：旧 8 键 tool metadata 只补显式 null binding，receipt / 数值 /
 * 其它节点 原样；provider replay 只改 affinity wire，format 与 payload 不变；所有失败都是固定安全消息。
 */
class LegacyHarnessHistoryJsonTest {

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();
  private static final ProviderReplayStateJsonCodec REPLAY_CODEC =
      new ProviderReplayStateJsonCodec();

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

  /** CUSTOM / CUSTOM_MESSAGE 的自由 data 需要 producer 语义验证；null 类型固定报错。 */
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
        LegacyHarnessHistoryJson.ENTRY_CUSTOM_UNSUPPORTED,
        assertThrows(
                IllegalArgumentException.class,
                () -> LegacyHarnessHistoryJson.entryPayload(EntryType.CUSTOM_MESSAGE, root))
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
}
