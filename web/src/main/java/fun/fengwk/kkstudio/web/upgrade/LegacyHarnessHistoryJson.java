package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;

import java.util.HashSet;
import java.util.Set;

/**
 * 一次性离线升级工具专用的历史 JSON 纯转换：只改写两处精确路径，其余字节的语义原样保留。
 *
 * <ul>
 *   <li>{@link #entryPayload(EntryType, String)}：旧 {@code MESSAGE} 的 {@code toolResultMetadata} 是缺
 *       {@code binding} 的精确 8 键形态，只补一个显式 {@code "binding":null}，如实表达「源历史没有冻结绑定」，绝不按当前函数 / 工具名或
 *       epoch 猜测定义、来源或插件，也不伪造可执行绑定。
 *   <li>{@link #providerReplayState(String)}：旧 {@code affinity.providerType} 的 wire 值 {@code
 *       "openai_response"} 改名为当前的 {@code "openai_responses"}；顶层 format 与 opaque payload
 *       一律不动，绝不做全局字符串替换。
 * </ul>
 *
 * <p>转换结果一律经目标真实 codec（{@link HistoryEntryPayloadJsonCodec} / {@link
 * ProviderReplayStateJsonCodec}）校验为当前 目标结构；绝不借 encode 重建或裁剪其它字段。{@code CUSTOM} / {@code
 * CUSTOM_MESSAGE} 的自由 data 另需按 producer 语义判定， 这里明确拒绝。所有失败都是不含底层 cause 或原始输入的固定安全 {@link
 * IllegalArgumentException}。
 *
 * <p>web 组合根禁止直接依赖 {@code harness.common}，因此这里就地启用与 {@code JsonValues} 相同的三个严格精度开关。
 */
final class LegacyHarnessHistoryJson {

  static final String ENTRY_TYPE_REQUIRED = "legacy history entry type is required";
  static final String ENTRY_CUSTOM_UNSUPPORTED =
      "legacy custom entry payloads are not upgradable offline";
  static final String ENTRY_MALFORMED = "legacy history entry payload is not valid strict JSON";
  static final String ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY =
      "legacy tool result metadata is not the exact pre-binding shape";
  static final String ENTRY_TARGET_REJECTED =
      "upgraded history entry payload is rejected by the current codec";
  static final String REPLAY_MALFORMED = "legacy provider replay state is not valid strict JSON";
  static final String REPLAY_UNKNOWN_PROVIDER_TYPE =
      "legacy provider replay state carries an unknown provider type";
  static final String REPLAY_TARGET_REJECTED =
      "provider replay state is rejected by the current codec";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // 自由 JSON number 以 BigDecimal 读回，避免 1e309 -> Infinity、1e-324 -> 0.0 的精度/类型失真。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private static final HistoryEntryPayloadJsonCodec ENTRY_CODEC =
      new HistoryEntryPayloadJsonCodec();

  private static final ProviderReplayStateJsonCodec REPLAY_CODEC =
      new ProviderReplayStateJsonCodec();

  /** 旧 OpenAI Responses provider 的 wire 值；当前目标值为 {@link ProviderType#OPENAI_RESPONSES}。 */
  private static final String LEGACY_OPENAI_RESPONSE_WIRE = "openai_response";

  /** 旧 {@code toolResultMetadata} 的冻结 8 键；缺少或多出任何键都不是可安全升级的精确旧源形态。 */
  private static final Set<String> LEGACY_TOOL_RESULT_METADATA_FIELDS =
      Set.of(
          "assistantEntryId",
          "toolCallId",
          "callIndex",
          "status",
          "synthetic",
          "reason",
          "inputReceipt",
          "invocationId");

  private LegacyHarnessHistoryJson() {}

  /**
   * 升级一条旧 Entry payload 文本；只有旧 {@code MESSAGE} 的精确 8 键 tool metadata 会被补上显式 {@code "binding":null}。
   * 目标已合法且无需变化的输入原样返回，但仍经真实 codec 校验。
   */
  static String entryPayload(EntryType type, String json) {
    if (type == null) {
      throw new IllegalArgumentException(ENTRY_TYPE_REQUIRED);
    }
    if (type == EntryType.CUSTOM || type == EntryType.CUSTOM_MESSAGE) {
      throw new IllegalArgumentException(ENTRY_CUSTOM_UNSUPPORTED);
    }
    JsonNode root = parse(json, ENTRY_MALFORMED);
    if (!(root instanceof ObjectNode rootObject)) {
      throw new IllegalArgumentException(ENTRY_MALFORMED);
    }
    String upgraded = json;
    if (type == EntryType.MESSAGE) {
      JsonNode metadata = rootObject.get("toolResultMetadata");
      if (metadata != null && !metadata.isNull()) {
        upgraded = addExplicitNullBinding(rootObject, metadata);
      }
    }
    try {
      ENTRY_CODEC.decode(type, upgraded);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(ENTRY_TARGET_REJECTED);
    }
    return upgraded;
  }

  /**
   * 升级一条旧 provider replay state 文本；只有 {@code affinity.providerType} 的旧 wire 值会改名为当前值。SQL NULL 输入保持
   * null；其它已知 provider、顶层 format 与 payload 一律原样返回，但都经真实 codec 校验。
   */
  static String providerReplayState(String json) {
    if (json == null) {
      return null;
    }
    JsonNode root = parse(json, REPLAY_MALFORMED);
    ObjectNode affinity = affinityObject(root);
    String wire = providerTypeWire(affinity);
    String upgraded = json;
    if (LEGACY_OPENAI_RESPONSE_WIRE.equals(wire)) {
      affinity.put("providerType", ProviderType.OPENAI_RESPONSES.wireValue());
      upgraded = write(root);
    } else {
      requireKnownProviderType(wire);
    }
    try {
      REPLAY_CODEC.decode(upgraded);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(REPLAY_TARGET_REJECTED);
    }
    return upgraded;
  }

  /** 只接受旧 metadata 的精确 8 键形态；已带 binding（哪怕 null）或键集不符都判定为不可静默覆盖的历史。 */
  private static String addExplicitNullBinding(JsonNode root, JsonNode metadata) {
    if (!(metadata instanceof ObjectNode object)) {
      throw new IllegalArgumentException(ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY);
    }
    Set<String> names = new HashSet<>();
    object.propertyNames().forEach(names::add);
    if (!names.equals(LEGACY_TOOL_RESULT_METADATA_FIELDS)) {
      throw new IllegalArgumentException(ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY);
    }
    object.putNull("binding");
    return write(root);
  }

  private static ObjectNode affinityObject(JsonNode root) {
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException(REPLAY_MALFORMED);
    }
    JsonNode affinity = object.get("affinity");
    if (!(affinity instanceof ObjectNode affinityObject)) {
      throw new IllegalArgumentException(REPLAY_MALFORMED);
    }
    return affinityObject;
  }

  private static String providerTypeWire(ObjectNode affinity) {
    JsonNode providerType = affinity.get("providerType");
    if (providerType == null || !providerType.isString()) {
      throw new IllegalArgumentException(REPLAY_MALFORMED);
    }
    return providerType.stringValue();
  }

  private static void requireKnownProviderType(String wire) {
    try {
      ProviderType.fromWireValue(wire);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(REPLAY_UNKNOWN_PROVIDER_TYPE);
    }
  }

  private static JsonNode parse(String json, String malformedMessage) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(malformedMessage);
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      // Jackson 3 空白输入返回 MissingNode；严格重复键 / 尾随 token / 畸形结构在 readTree 即失败。
      if (root == null || root.isMissingNode()) {
        throw new IllegalArgumentException(malformedMessage);
      }
      return root;
    } catch (JacksonException error) {
      throw new IllegalArgumentException(malformedMessage);
    }
  }

  private static String write(JsonNode root) {
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot write upgraded legacy history JSON");
    }
  }
}
