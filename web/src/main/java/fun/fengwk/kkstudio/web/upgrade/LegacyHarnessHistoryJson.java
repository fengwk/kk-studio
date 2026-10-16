package fun.fengwk.kkstudio.web.upgrade;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
 *   <li>{@link #threadCommandPayload(ThreadCommandType, String)}：只校验旧 typed command 的
 *       payload，业务值一个字节都不改。
 * </ul>
 *
 * <p>{@code CUSTOM} / {@code CUSTOM_MESSAGE} 仅接受已确认的第一方组合：{@code builtin/goal.progress/1} 的精确 4
 * 键终态声明、{@code project/run/1} 经 {@link ProjectRunScopeJsonCodec} 校验的快照，以及 {@code
 * core/message/message} 且 {@code details} 为空对象的消息。其余 producer / type / version 组合一律拒绝。
 *
 * <p>转换结果经目标真实 codec（{@link HistoryEntryPayloadJsonCodec} / {@link ProviderReplayStateJsonCodec} /
 * {@link ThreadCommandPayloadJsonCodec}）校验；不借 encode 重建或裁剪其它字段。 输入与目标结构校验失败只包含固定安全消息，不附带 cause
 * 或原始输入。
 *
 * <p>严格拒绝重复字段与尾随 token；自由 JSON number 按精确十进制保留，不经过 double。
 */
final class LegacyHarnessHistoryJson {

  static final String ENTRY_TYPE_REQUIRED = "legacy history entry type is required";
  static final String ENTRY_CUSTOM_UNSUPPORTED =
      "legacy custom entry payloads are not upgradable offline";
  static final String ENTRY_CUSTOM_DATA_NOT_LEGACY =
      "legacy custom entry data is not the exact known source shape";
  static final String ENTRY_CUSTOM_MESSAGE_UNSUPPORTED =
      "legacy custom message entry payloads are not the known core message shape";
  static final String ENTRY_MALFORMED = "legacy history entry payload is not valid strict JSON";
  static final String ENTRY_TOOL_RESULT_METADATA_NOT_LEGACY =
      "legacy tool result metadata is not the exact pre-binding shape";
  static final String ENTRY_TARGET_REJECTED =
      "upgraded history entry payload is rejected by the current codec";
  static final String COMMAND_TYPE_REQUIRED = "legacy thread command type is required";
  static final String COMMAND_MALFORMED = "legacy thread command payload is not valid strict JSON";
  static final String COMMAND_STATE_NOT_KNOWN =
      "legacy contributor state command is not a known upgradable state";
  static final String COMMAND_TARGET_REJECTED =
      "upgraded thread command payload is rejected by the current codec";
  static final String REPLAY_MALFORMED = "legacy provider replay state is not valid strict JSON";
  static final String REPLAY_UNKNOWN_PROVIDER_TYPE =
      "legacy provider replay state carries an unknown provider type";
  static final String REPLAY_TARGET_REJECTED =
      "provider replay state is rejected by the current codec";
  static final String PROVIDER_TYPE_REQUIRED = "legacy provider type is required";
  static final String PROVIDER_TYPE_UNKNOWN = "legacy provider type is not a known wire value";
  static final String RECEIPT_MALFORMED =
      "legacy stop receipt cancelled inputs are not valid strict JSON";
  static final String RECEIPT_NOT_LEGACY =
      "legacy stop receipt cancelled inputs are not the exact source shape";

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

  private static final ThreadCommandPayloadJsonCodec COMMAND_CODEC =
      new ThreadCommandPayloadJsonCodec();

  private static final ProjectRunScopeJsonCodec RUN_SCOPE_CODEC = new ProjectRunScopeJsonCodec();

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

  /** 旧 stop 回执 {@code cancelled_inputs} 每项的冻结 4 键。 */
  private static final Set<String> CANCELLED_INPUT_FIELDS =
      Set.of("sequence", "idempotencyKey", "type", "payload");

  /** 已知第一方 goal 进度声明的 CUSTOM 组合：{@code builtin/goal.progress}。 */
  private static final String GOAL_PROGRESS_CONTRIBUTOR_ID = "builtin";

  private static final String GOAL_PROGRESS_CUSTOM_TYPE = "goal.progress";

  /** 已知第一方 Issue run scope 的 CUSTOM 组合：{@code project/run}。 */
  private static final String PROJECT_RUN_CONTRIBUTOR_ID = "project";

  private static final String PROJECT_RUN_CUSTOM_TYPE = "run";

  /** 已知第一方 core custom message 的固定组合：{@code core/message/message}。 */
  private static final String CORE_MESSAGE_CONTRIBUTOR_ID = "core";

  private static final String CORE_MESSAGE_CUSTOM_TYPE = "message";

  private static final String CORE_MESSAGE_RENDERER_KEY = "message";

  /** 已知第一方 CUSTOM 组合共享的 schemaVersion。 */
  private static final int KNOWN_SCHEMA_VERSION = 1;

  /** 旧 goal.progress 终态声明的冻结 4 键。 */
  private static final Set<String> GOAL_PROGRESS_FIELDS =
      Set.of("goalId", "status", "reason", "reportedAt");

  /** 旧 goal 终态声明的两个 wire 值（仍然 active 的 {@code "active"} 不是终态声明）。 */
  private static final String GOAL_STATUS_COMPLETE = "complete";

  private static final String GOAL_STATUS_BLOCKED = "blocked";

  private LegacyHarnessHistoryJson() {}

  /**
   * 升级一条旧 Entry payload 文本；只有旧 {@code MESSAGE} 的精确 8 键 tool metadata 会被补上显式 {@code "binding":null}，
   * 已知第一方 {@code CUSTOM} / {@code CUSTOM_MESSAGE} 按 producer 语义校验后原样返回。目标已合法且无需变化的输入原样返回， 但仍经真实
   * codec 校验。
   */
  static String entryPayload(EntryType type, String json) {
    if (type == null) {
      throw new IllegalArgumentException(ENTRY_TYPE_REQUIRED);
    }
    JsonNode root = parse(json, ENTRY_MALFORMED);
    if (!(root instanceof ObjectNode rootObject)) {
      throw new IllegalArgumentException(ENTRY_MALFORMED);
    }
    String upgraded =
        switch (type) {
          case CUSTOM -> {
            requireKnownCustomEntry(rootObject);
            yield json;
          }
          case CUSTOM_MESSAGE -> {
            requireKnownCoreCustomMessage(rootObject);
            yield json;
          }
          case MESSAGE -> {
            JsonNode metadata = rootObject.get("toolResultMetadata");
            yield metadata != null && !metadata.isNull()
                ? addExplicitNullBinding(rootObject, metadata)
                : json;
          }
          default -> json;
        };
    try {
      ENTRY_CODEC.decode(type, upgraded);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(ENTRY_TARGET_REJECTED);
    }
    return upgraded;
  }

  /**
   * 校验一条旧 typed Thread command payload 文本；只对已知第一方 {@code SET_CONTRIBUTOR_STATE} 的 {@code state} 追加
   * producer 语义校验，业务值一个字节都不改，并按目标真实 command codec 校验。
   */
  static String threadCommandPayload(ThreadCommandType type, String json) {
    if (type == null) {
      throw new IllegalArgumentException(COMMAND_TYPE_REQUIRED);
    }
    JsonNode root = parse(json, COMMAND_MALFORMED);
    if (!(root instanceof ObjectNode rootObject)) {
      throw new IllegalArgumentException(COMMAND_MALFORMED);
    }
    if (type == ThreadCommandType.SET_CONTRIBUTOR_STATE) {
      requireKnownContributorState(rootObject);
    }
    try {
      COMMAND_CODEC.decode(type, json);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(COMMAND_TARGET_REJECTED);
    }
    return json;
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
      affinity.put("providerType", providerType(wire));
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

  /**
   * 把旧 provider wire 值归一化为当前值：只有已确认的历史别名 {@code openai_response} 改名为 {@code
   * openai_responses}，其余已知值原样返回，未知值拒绝。
   *
   * <p>这是 provider 别名转换的唯一规则，同时供 agent_provider 列与 replay {@code affinity.providerType}
   * 使用；绝不做全局字符串替换，也不会改写 config 里同名的字面量。
   */
  static String providerType(String wire) {
    if (wire == null || wire.isBlank()) {
      throw new IllegalArgumentException(PROVIDER_TYPE_REQUIRED);
    }
    if (LEGACY_OPENAI_RESPONSE_WIRE.equals(wire)) {
      return ProviderType.OPENAI_RESPONSES.wireValue();
    }
    try {
      return ProviderType.fromWireValue(wire).wireValue();
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(PROVIDER_TYPE_UNKNOWN);
    }
  }

  /**
   * 校验一条旧 stop 回执的 {@code cancelled_inputs} 原文：严格 array，每项精确 4 键，sequence 是可表示的正
   * long、idempotencyKey 是 UUID、type 是合法枚举，嵌套 payload 复用同一 command helper 与其 known state 检查。整个原 JSON
   * 一字不改。
   */
  static String stopReceiptCancelledInputs(String json) {
    JsonNode root = parse(json, RECEIPT_MALFORMED);
    if (!(root instanceof ArrayNode array)) {
      throw new IllegalArgumentException(RECEIPT_MALFORMED);
    }
    for (JsonNode item : array) {
      requireCancelledInput(item);
    }
    return json;
  }

  /** 旧 {@code cancelled_inputs} 单项：键集、sequence 范围、UUID、命令枚举与嵌套 payload 全部按源形态校验。 */
  private static void requireCancelledInput(JsonNode item) {
    if (!(item instanceof ObjectNode object) || !names(object).equals(CANCELLED_INPUT_FIELDS)) {
      throw new IllegalArgumentException(RECEIPT_NOT_LEGACY);
    }
    JsonNode sequence = object.get("sequence");
    if (sequence == null
        || !sequence.isIntegralNumber()
        || !sequence.canConvertToLong()
        || sequence.longValue() <= 0) {
      throw new IllegalArgumentException(RECEIPT_NOT_LEGACY);
    }
    try {
      UUID.fromString(stringValue(object, "idempotencyKey", RECEIPT_NOT_LEGACY));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(RECEIPT_NOT_LEGACY);
    }
    String type = stringValue(object, "type", RECEIPT_NOT_LEGACY);
    ThreadCommandType commandType;
    try {
      commandType = ThreadCommandType.valueOf(type);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(RECEIPT_NOT_LEGACY);
    }
    JsonNode payload = object.get("payload");
    if (!(payload instanceof ObjectNode payloadObject)) {
      throw new IllegalArgumentException(RECEIPT_NOT_LEGACY);
    }
    threadCommandPayload(commandType, write(payloadObject));
  }

  /** 已知 CUSTOM Entry 的 producer 语义校验：先按原始身份字段判定组合，再校验旧 data，未知组合 fail closed。 */
  private static void requireKnownCustomEntry(ObjectNode entry) {
    requireKnownCustom(entry, ENTRY_CUSTOM_UNSUPPORTED, ENTRY_CUSTOM_DATA_NOT_LEGACY);
  }

  /**
   * 已知 {@code SET_CONTRIBUTOR_STATE} 的 {@code state}：外层与 state 键集仍由当前 codec 校验，这里只判 producer 语义。
   */
  private static void requireKnownContributorState(ObjectNode command) {
    JsonNode state = command.get("state");
    if (!(state instanceof ObjectNode stateObject)) {
      throw new IllegalArgumentException(COMMAND_STATE_NOT_KNOWN);
    }
    requireKnownCustom(stateObject, COMMAND_STATE_NOT_KNOWN, COMMAND_STATE_NOT_KNOWN);
  }

  /** 只接受 {@code builtin/goal.progress/1} 与 {@code project/run/1} 两个已知组合及其旧 data 形态。 */
  private static void requireKnownCustom(ObjectNode node, String unsupported, String invalidData) {
    String contributorId = stringValue(node, "contributorId", unsupported);
    String customType = stringValue(node, "customType", unsupported);
    if (!isKnownSchemaVersion(node)) {
      throw new IllegalArgumentException(unsupported);
    }
    JsonNode data = node.get("data");
    if (GOAL_PROGRESS_CONTRIBUTOR_ID.equals(contributorId)
        && GOAL_PROGRESS_CUSTOM_TYPE.equals(customType)) {
      requireGoalProgressData(data, invalidData);
    } else if (PROJECT_RUN_CONTRIBUTOR_ID.equals(contributorId)
        && PROJECT_RUN_CUSTOM_TYPE.equals(customType)) {
      requireProjectRunData(data, invalidData);
    } else {
      throw new IllegalArgumentException(unsupported);
    }
  }

  /**
   * 旧 goal.progress 终态声明 data：精确 4 键、全为非 null string；{@code goalId} 是 UUID、{@code status} 精确为
   * {@code complete}/{@code blocked}、{@code reason} 非 blank、{@code reportedAt} 是可解析
   * Instant。只校验，不改写业务文本。
   */
  private static void requireGoalProgressData(JsonNode data, String message) {
    if (!(data instanceof ObjectNode object) || !names(object).equals(GOAL_PROGRESS_FIELDS)) {
      throw new IllegalArgumentException(message);
    }
    String goalId = stringValue(object, "goalId", message);
    String status = stringValue(object, "status", message);
    String reason = stringValue(object, "reason", message);
    String reportedAt = stringValue(object, "reportedAt", message);
    try {
      UUID.fromString(goalId);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(message);
    }
    if (!GOAL_STATUS_COMPLETE.equals(status) && !GOAL_STATUS_BLOCKED.equals(status)) {
      throw new IllegalArgumentException(message);
    }
    if (reason.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    try {
      Instant.parse(reportedAt);
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException(message);
    }
  }

  /**
   * 旧 {@code project/run/1} data：直接复用公开的 {@link ProjectRunScopeJsonCodec#decode(String)} 校验 13 字段快照
   * （含 issueNumber long 范围），不另写解析器、不重编码存储内容。
   */
  private static void requireProjectRunData(JsonNode data, String message) {
    if (!(data instanceof ObjectNode object)) {
      throw new IllegalArgumentException(message);
    }
    try {
      RUN_SCOPE_CODEC.decode(write(object));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(message);
    }
  }

  /**
   * 旧 {@code CUSTOM_MESSAGE} 只放行 TurnPlanBuilder 固定的 {@code core/message/message} 组合，且 {@code
   * details} 必须是空对象；未知 renderer / 非空 details 都不是可安全升级的旧第一方消息。
   */
  private static void requireKnownCoreCustomMessage(ObjectNode entry) {
    if (!CORE_MESSAGE_CONTRIBUTOR_ID.equals(
            stringValue(entry, "contributorId", ENTRY_CUSTOM_MESSAGE_UNSUPPORTED))
        || !CORE_MESSAGE_CUSTOM_TYPE.equals(
            stringValue(entry, "customType", ENTRY_CUSTOM_MESSAGE_UNSUPPORTED))
        || !CORE_MESSAGE_RENDERER_KEY.equals(
            stringValue(entry, "rendererKey", ENTRY_CUSTOM_MESSAGE_UNSUPPORTED))) {
      throw new IllegalArgumentException(ENTRY_CUSTOM_MESSAGE_UNSUPPORTED);
    }
    JsonNode details = entry.get("details");
    if (!(details instanceof ObjectNode detailsObject) || detailsObject.size() != 0) {
      throw new IllegalArgumentException(ENTRY_CUSTOM_MESSAGE_UNSUPPORTED);
    }
  }

  /** 只接受已知版本的整数字面量，拒绝类型转换和整数截断。 */
  private static boolean isKnownSchemaVersion(ObjectNode node) {
    JsonNode value = node.get("schemaVersion");
    return value != null
        && value.isIntegralNumber()
        && value.canConvertToInt()
        && value.intValue() == KNOWN_SCHEMA_VERSION;
  }

  private static String stringValue(ObjectNode node, String field, String message) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(message);
    }
    return value.stringValue();
  }

  private static Set<String> names(ObjectNode node) {
    Set<String> names = new HashSet<>();
    node.propertyNames().forEach(names::add);
    return names;
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
