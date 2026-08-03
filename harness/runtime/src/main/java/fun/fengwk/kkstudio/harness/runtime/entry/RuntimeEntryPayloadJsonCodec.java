package fun.fengwk.kkstudio.harness.runtime.entry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 最终 7 类 Runtime Entry payload 的严格、确定性 JSON codec：{@link RootEntryPayload} / {@link
 * TurnStartEntryPayload} / {@link MessageEntryPayload} / {@link CustomMessageEntryPayload} / {@link
 * AssistantErrorEntryPayload} / {@link AssistantAbortedEntryPayload} / {@link TurnEndEntryPayload}。
 * 直接对应 {@link EntryType}；其它 {@link EntryPayload} 实现显式拒绝。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）；未知枚举；raw {@code argumentsJson} / {@code
 * detailsJson} 非单一 JSON object；raw {@code json} 非单一 JSON value；{@code tool_result} 嵌套 {@code
 * tool_call} 或 {@code tool_result}；{@link AssistantAbortedEntryPayload} 必须仅含 text/thinking content
 * 且不能全为空，否则进入取消 barrier 而非空 aborted turn。字段顺序固定；{@link BigDecimal} 字段以 {@code toPlainString()}
 * 字符串输出；list 顺序保留。branch settings 的 {@code environmentId} 是可空 canonical 小写 UUID 文本，作为 Environment
 * route identity；display name 不进入 durable 协议。
 *
 * <p>{@code ASSISTANT_ERROR} 的 {@code error} 子树直接委派 {@link ModelInvocationErrorJsonCodec} 的 node
 * API。{@code message} 子树委派 {@link AgentMessageJsonCodec}，AgentMessage 与 content 的严格编码/解码规则 由该共享
 * codec 负责，本 codec 不再持有重复实现。
 */
public final class RuntimeEntryPayloadJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> TURN_START_FIELDS = orderedSet("reason", "settings");
  private static final Set<String> TURN_END_FIELDS =
      orderedSet("turnStartEntryId", "outcome", "continueModel", "reason", "closeRequestId");
  private static final Set<String> BRANCH_SETTINGS_FIELDS =
      orderedSet("environmentId", "agentName", "model", "thinkingLevel", "activeTools");
  private static final Set<String> MODEL_SELECTION_FIELDS =
      orderedSet("providerName", "modelName", "variant");
  private static final Set<String> MESSAGE_FIELDS =
      orderedSet("message", "turnSettings", "assistantMetadata");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS = orderedSet("message", "turnSettings");
  private static final Set<String> ASSISTANT_ERROR_FIELDS = orderedSet("error");
  private static final Set<String> ASSISTANT_ABORTED_FIELDS = orderedSet("message");
  private static final Set<String> ROOT_FIELDS = orderedSet();

  private static final Set<String> TURN_SETTINGS_FIELDS = orderedSet("agentName", "yoloEnabled");
  private static final Set<String> METADATA_FIELDS = orderedSet("stopReason", "usage", "cost");
  private static final Set<String> USAGE_FIELDS =
      orderedSet(
          "inputTokens",
          "outputTokens",
          "cacheReadTokens",
          "cacheWriteTokens",
          "cacheWriteLongTokens",
          "reasoningTokens",
          "providerTotalTokens");
  private static final Set<String> COST_FIELDS =
      orderedSet(
          "currency",
          "input",
          "output",
          "cacheRead",
          "cacheWrite",
          "cacheWriteLong",
          "reasoning",
          "total");

  private static final AgentMessageJsonCodec MESSAGE_CODEC = new AgentMessageJsonCodec();
  private static final ModelInvocationErrorJsonCodec ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public RuntimeEntryPayloadJsonCodec() {}

  /** 把 {@link EntryPayload} 编码为 canonical JSON 文本；仅支持最终 7 类 Runtime Entry payload， 其它实现显式拒绝。 */
  public String encode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    return write(encodeNode(payload));
  }

  /** 把 {@link EntryPayload} 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(EntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload instanceof RootEntryPayload) {
      return NODES.objectNode();
    }
    if (payload instanceof TurnStartEntryPayload value) {
      return encodeTurnStart(value);
    }
    if (payload instanceof MessageEntryPayload value) {
      return encodeMessagePayload(value);
    }
    if (payload instanceof CustomMessageEntryPayload value) {
      return encodeCustomMessagePayload(value);
    }
    if (payload instanceof AssistantErrorEntryPayload value) {
      return encodeAssistantError(value);
    }
    if (payload instanceof AssistantAbortedEntryPayload value) {
      return encodeAssistantAborted(value);
    }
    if (payload instanceof TurnEndEntryPayload value) {
      return encodeTurnEnd(value);
    }
    throw new IllegalArgumentException(
        "unsupported entry payload: " + payload.getClass().getName());
  }

  /**
   * 把 canonical JSON 文本按指定 {@link EntryType} 解码为对应 payload；任何非法结构抛 {@link
   * IllegalArgumentException}。
   */
  public EntryPayload decode(EntryType type, String json) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed " + type.name() + " entry payload JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed " + type.name() + " entry payload JSON");
    }
    return decodeNode(type, root);
  }

  /** 从任意 {@link JsonNode} 按 {@link EntryType} 解码 payload。 */
  public EntryPayload decodeNode(EntryType type, JsonNode value) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    return switch (type) {
      case ROOT -> decodeRoot(value);
      case TURN_START -> decodeTurnStart(value);
      case MESSAGE -> decodeMessagePayload(value);
      case CUSTOM_MESSAGE -> decodeCustomMessagePayload(value);
      case ASSISTANT_ERROR -> decodeAssistantError(value);
      case ASSISTANT_ABORTED -> decodeAssistantAborted(value);
      case TURN_END -> decodeTurnEnd(value);
    };
  }

  // ---------- Encoders ----------

  private static ObjectNode encodeTurnStart(TurnStartEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("reason", value.reason().name());
    node.set("settings", encodeBranchSettings(value.settings()));
    return node;
  }

  private static ObjectNode encodeMessagePayload(MessageEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    if (value.turnSettings() == null) {
      node.putNull("turnSettings");
    } else {
      node.set("turnSettings", encodeTurnSettings(value.turnSettings()));
    }
    if (value.assistantMetadata() == null) {
      node.putNull("assistantMetadata");
    } else {
      node.set("assistantMetadata", encodeAssistantMetadata(value.assistantMetadata()));
    }
    return node;
  }

  private static ObjectNode encodeCustomMessagePayload(CustomMessageEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    node.set("turnSettings", encodeTurnSettings(value.turnSettings()));
    return node;
  }

  private static ObjectNode encodeAssistantError(AssistantErrorEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("error", ERROR_CODEC.encodeNode(value.error()));
    return node;
  }

  private static ObjectNode encodeAssistantAborted(AssistantAbortedEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", MESSAGE_CODEC.encodeNode(value.message()));
    return node;
  }

  private static ObjectNode encodeTurnEnd(TurnEndEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.put("turnStartEntryId", Long.toString(value.turnStartEntryId()));
    node.put("outcome", value.outcome().name());
    node.put("continueModel", value.continueModel());
    if (value.reason() == null) {
      node.putNull("reason");
    } else {
      node.put("reason", value.reason());
    }
    if (value.closeRequestId() == null) {
      node.putNull("closeRequestId");
    } else {
      node.put("closeRequestId", value.closeRequestId());
    }
    return node;
  }

  private static ObjectNode encodeBranchSettings(BranchSettings settings) {
    ObjectNode node = NODES.objectNode();
    if (settings.environmentId() == null) {
      node.putNull("environmentId");
    } else {
      node.put("environmentId", settings.environmentId().value());
    }
    node.put("agentName", settings.agentName());
    node.set("model", encodeModelSelection(settings.model()));
    node.put("thinkingLevel", settings.thinkingLevel());
    ArrayNode activeTools = node.putArray("activeTools");
    for (String activeTool : settings.activeTools()) {
      activeTools.add(activeTool);
    }
    return node;
  }

  private static ObjectNode encodeModelSelection(ModelSelection selection) {
    ObjectNode node = NODES.objectNode();
    node.put("providerName", selection.providerName());
    node.put("modelName", selection.modelName());
    node.put("variant", selection.variant());
    return node;
  }

  // ---------- Decoders ----------

  private static RootEntryPayload decodeRoot(JsonNode value) {
    ObjectNode node = requireObject(value, "ROOT");
    requireExactFields(node, ROOT_FIELDS, "ROOT");
    return new RootEntryPayload();
  }

  private static TurnStartEntryPayload decodeTurnStart(JsonNode value) {
    ObjectNode node = requireObject(value, "TURN_START");
    requireExactFields(node, TURN_START_FIELDS, "TURN_START");
    return new TurnStartEntryPayload(
        readEnum(TurnStartReason.class, text(node, "reason"), "TURN_START.reason"),
        decodeBranchSettings(node.get("settings")));
  }

  private static MessageEntryPayload decodeMessagePayload(JsonNode value) {
    ObjectNode node = requireObject(value, "MESSAGE");
    requireExactFields(node, MESSAGE_FIELDS, "MESSAGE");
    AgentMessage message = MESSAGE_CODEC.decodeNode(node.get("message"));
    TurnSettings turnSettings = decodeNullableTurnSettings(node.get("turnSettings"));
    JsonNode metadataNode = node.get("assistantMetadata");
    if (metadataNode.isNull()) {
      return new MessageEntryPayload(message, turnSettings, null);
    }
    AssistantMessageMetadata metadata = decodeAssistantMetadata(metadataNode);
    return new MessageEntryPayload(message, turnSettings, metadata);
  }

  private static CustomMessageEntryPayload decodeCustomMessagePayload(JsonNode value) {
    ObjectNode node = requireObject(value, "CUSTOM_MESSAGE");
    requireExactFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessageEntryPayload(
        MESSAGE_CODEC.decodeNode(node.get("message")),
        decodeTurnSettings(node.get("turnSettings")));
  }

  private static AssistantErrorEntryPayload decodeAssistantError(JsonNode value) {
    ObjectNode node = requireObject(value, "ASSISTANT_ERROR");
    requireExactFields(node, ASSISTANT_ERROR_FIELDS, "ASSISTANT_ERROR");
    ModelInvocationError error = ERROR_CODEC.decodeNode(node.get("error"));
    return new AssistantErrorEntryPayload(error);
  }

  private static AssistantAbortedEntryPayload decodeAssistantAborted(JsonNode value) {
    ObjectNode node = requireObject(value, "ASSISTANT_ABORTED");
    requireExactFields(node, ASSISTANT_ABORTED_FIELDS, "ASSISTANT_ABORTED");
    AgentMessage message = MESSAGE_CODEC.decodeNode(node.get("message"));
    return new AssistantAbortedEntryPayload(message);
  }

  private static TurnEndEntryPayload decodeTurnEnd(JsonNode value) {
    ObjectNode node = requireObject(value, "TURN_END");
    requireExactFields(node, TURN_END_FIELDS, "TURN_END");
    return new TurnEndEntryPayload(
        requiredPositiveId(node, "turnStartEntryId", "TURN_END"),
        readEnum(TurnEndOutcome.class, text(node, "outcome"), "TURN_END.outcome"),
        requiredBoolean(node, "continueModel", "TURN_END"),
        nullableCanonicalText(node, "reason", "TURN_END"),
        nullableCanonicalText(node, "closeRequestId", "TURN_END"));
  }

  private static BranchSettings decodeBranchSettings(JsonNode value) {
    ObjectNode node = requireObject(value, "TURN_START.settings");
    requireExactFields(node, BRANCH_SETTINGS_FIELDS, "TURN_START.settings");
    return new BranchSettings(
        nullableEnvironmentId(node, "environmentId", "TURN_START.settings"),
        canonicalText(node, "agentName", "TURN_START.settings"),
        decodeModelSelection(node.get("model")),
        canonicalText(node, "thinkingLevel", "TURN_START.settings"),
        decodeActiveTools(node.get("activeTools")));
  }

  private static ModelSelection decodeModelSelection(JsonNode value) {
    ObjectNode node = requireObject(value, "TURN_START.settings.model");
    requireExactFields(node, MODEL_SELECTION_FIELDS, "TURN_START.settings.model");
    return new ModelSelection(
        canonicalText(node, "providerName", "TURN_START.settings.model"),
        canonicalText(node, "modelName", "TURN_START.settings.model"),
        canonicalText(node, "variant", "TURN_START.settings.model"));
  }

  private static List<String> decodeActiveTools(JsonNode value) {
    ArrayNode node = requireArray(value, "TURN_START.settings.activeTools");
    List<String> activeTools = new ArrayList<>(node.size());
    for (JsonNode activeTool : node) {
      if (!activeTool.isTextual()) {
        throw new IllegalArgumentException("TURN_START.settings.activeTools elements must be text");
      }
      activeTools.add(
          canonicalName(activeTool.textValue(), "TURN_START.settings.activeTools element"));
    }
    return activeTools;
  }

  private static ObjectNode encodeTurnSettings(TurnSettings settings) {
    ObjectNode node = NODES.objectNode();
    node.put("agentName", settings.agentName());
    node.put("yoloEnabled", settings.yoloEnabled());
    return node;
  }

  private static TurnSettings decodeTurnSettings(JsonNode value) {
    ObjectNode node = requireObject(value, "turnSettings");
    requireExactFields(node, TURN_SETTINGS_FIELDS, "turnSettings");
    return new TurnSettings(
        requiredText(node, "agentName", "turnSettings"),
        requiredBoolean(node, "yoloEnabled", "turnSettings"));
  }

  private static TurnSettings decodeNullableTurnSettings(JsonNode value) {
    return value == null || value.isNull() ? null : decodeTurnSettings(value);
  }

  // ---------- Assistant metadata encoders & decoders ----------

  private static ObjectNode encodeAssistantMetadata(AssistantMessageMetadata metadata) {
    ObjectNode node = NODES.objectNode();
    node.put("stopReason", metadata.stopReason().name());
    ModelUsage usage = metadata.usage();
    ObjectNode usageNode = node.putObject("usage");
    usageNode.put("inputTokens", usage.inputTokens());
    usageNode.put("outputTokens", usage.outputTokens());
    usageNode.put("cacheReadTokens", usage.cacheReadTokens());
    usageNode.put("cacheWriteTokens", usage.cacheWriteTokens());
    usageNode.put("cacheWriteLongTokens", usage.cacheWriteLongTokens());
    usageNode.put("reasoningTokens", usage.reasoningTokens());
    usageNode.put("providerTotalTokens", usage.providerTotalTokens());
    ModelCost cost = metadata.cost();
    ObjectNode costNode = node.putObject("cost");
    costNode.put("currency", cost.currency());
    costNode.put("input", cost.input().toPlainString());
    costNode.put("output", cost.output().toPlainString());
    costNode.put("cacheRead", cost.cacheRead().toPlainString());
    costNode.put("cacheWrite", cost.cacheWrite().toPlainString());
    costNode.put("cacheWriteLong", cost.cacheWriteLong().toPlainString());
    costNode.put("reasoning", cost.reasoning().toPlainString());
    costNode.put("total", cost.total().toPlainString());
    return node;
  }

  private static AssistantMessageMetadata decodeAssistantMetadata(JsonNode value) {
    ObjectNode node = requireObject(value, "assistantMetadata");
    requireExactFields(node, METADATA_FIELDS, "assistantMetadata");
    ProviderStopReason stopReason =
        readEnum(
            ProviderStopReason.class, text(node, "stopReason"), "assistantMetadata.stopReason");
    ObjectNode usageNode = requireObject(node.get("usage"), "assistantMetadata.usage");
    requireExactFields(usageNode, USAGE_FIELDS, "assistantMetadata.usage");
    ModelUsage usage =
        new ModelUsage(
            requiredNonNegativeLong(usageNode, "inputTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "outputTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "cacheReadTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "cacheWriteTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "cacheWriteLongTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "reasoningTokens", "assistantMetadata.usage"),
            requiredNonNegativeLong(usageNode, "providerTotalTokens", "assistantMetadata.usage"));
    ObjectNode costNode = requireObject(node.get("cost"), "assistantMetadata.cost");
    requireExactFields(costNode, COST_FIELDS, "assistantMetadata.cost");
    ModelCost cost =
        new ModelCost(
            requiredText(costNode, "currency", "assistantMetadata.cost"),
            requiredDecimal(costNode, "input", "assistantMetadata.cost"),
            requiredDecimal(costNode, "output", "assistantMetadata.cost"),
            requiredDecimal(costNode, "cacheRead", "assistantMetadata.cost"),
            requiredDecimal(costNode, "cacheWrite", "assistantMetadata.cost"),
            requiredDecimal(costNode, "cacheWriteLong", "assistantMetadata.cost"),
            requiredDecimal(costNode, "reasoning", "assistantMetadata.cost"),
            requiredDecimal(costNode, "total", "assistantMetadata.cost"));
    return new AssistantMessageMetadata(stopReason, usage, cost);
  }

  // ---------- Generic helpers ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode runtime entry payload JSON", error);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  private static ArrayNode requireArray(JsonNode value, String context) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(context + " must be a JSON array");
    }
    return array;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    return requiredText(value, field, context);
  }

  private static String requiredText(JsonNode value, String field, String context) {
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    String text = value.textValue();
    if (text.isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must not be blank");
    }
    return text;
  }

  private static String canonicalText(ObjectNode node, String field, String context) {
    return canonicalName(requiredText(node, field, context), context + "." + field);
  }

  private static String nullableCanonicalText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return canonicalName(value.textValue(), context + "." + field);
  }

  private static EnvironmentId nullableEnvironmentId(
      ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    return new EnvironmentId(value.textValue());
  }

  private static String canonicalName(String value, String context) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(context + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(context + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(context + " must be <= 128 characters");
    }
    return value;
  }

  private static long requiredPositiveId(ObjectNode node, String field, String context) {
    String text = requiredText(node, field, context);
    long value;
    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a decimal string", error);
    }
    if (value <= 0 || !Long.toString(value).equals(text)) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical positive decimal string");
    }
    return value;
  }

  private static long requiredNonNegativeLong(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.longValue();
  }

  private static boolean requiredBoolean(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static BigDecimal requiredDecimal(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    try {
      return new BigDecimal(value.textValue());
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a decimal string", error);
    }
  }

  private static <E extends Enum<E>> E readEnum(Class<E> kind, String name, String context) {
    if (name == null) {
      throw new IllegalArgumentException(
          context
              + " must be one of "
              + kind.getEnumConstants().length
              + " "
              + kind.getSimpleName()
              + " values: null");
    }
    try {
      return Enum.valueOf(kind, name);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context
              + " must be one of "
              + kind.getEnumConstants().length
              + " "
              + kind.getSimpleName()
              + " values: "
              + name,
          error);
    }
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new LinkedHashSet<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      actual.add(names.next());
    }
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          context + " unexpected fields: " + actual + " (expected " + expected + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}
