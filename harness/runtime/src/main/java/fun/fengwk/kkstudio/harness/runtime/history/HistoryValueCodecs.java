package fun.fengwk.kkstudio.harness.runtime.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * history 包内部的严格 value codec：BranchSettings / ModelSelection / AssistantMessageMetadata 以及它们 需要的通用
 * JSON 原语。仅供 {@link HistoryEntryPayloadJsonCodec} 组合使用，不复制 AgentMessage codec。
 */
final class HistoryValueCodecs {

  static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> BRANCH_SETTINGS_FIELDS =
      orderedSet("environment", "agentName", "model");
  private static final Set<String> ENVIRONMENT_BINDING_FIELDS = orderedSet("name", "workspacePath");
  private static final Set<String> MODEL_SELECTION_FIELDS =
      orderedSet("providerName", "modelName", "variant");
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

  /** canonical 小写 dotted/dashed 标识符（contributorId / customType / rendererKey）的最大字符数。 */
  static final int MAX_IDENTIFIER_CHARS = 64;

  private static final Pattern CANONICAL_IDENTIFIER =
      Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private HistoryValueCodecs() {}

  // ---------- BranchSettings ----------

  static ObjectNode encodeBranchSettings(BranchSettings settings) {
    ObjectNode node = NODES.objectNode();
    if (settings.environment() == null) {
      node.putNull("environment");
    } else {
      node.set("environment", encodeEnvironmentBinding(settings.environment()));
    }
    node.put("agentName", settings.agentName());
    node.set("model", encodeModelSelection(settings.model()));
    return node;
  }

  static ObjectNode encodeEnvironmentBinding(EnvironmentBinding binding) {
    ObjectNode node = NODES.objectNode();
    node.put("name", binding.environmentName().value());
    node.put("workspacePath", binding.workspacePath());
    return node;
  }

  static BranchSettings decodeBranchSettings(JsonNode value, String context) {
    ObjectNode node = requireObject(value, context);
    requireExactFields(node, BRANCH_SETTINGS_FIELDS, context);
    return new BranchSettings(
        nullableEnvironmentBinding(node, "environment", context),
        requiredText(node, "agentName", context),
        decodeModelSelection(node.get("model"), context + ".model"));
  }

  /** 读取可空完整 Environment binding 对象；null 表示未绑定。 */
  static EnvironmentBinding nullableEnvironmentBinding(
      ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    ObjectNode binding = requireObject(value, context + "." + field);
    requireExactFields(binding, ENVIRONMENT_BINDING_FIELDS, context + "." + field);
    return new EnvironmentBinding(
        new EnvironmentName(requiredText(binding, "name", context + "." + field)),
        requiredText(binding, "workspacePath", context + "." + field));
  }

  // ---------- ModelSelection ----------

  static ObjectNode encodeModelSelection(ModelSelection selection) {
    ObjectNode node = NODES.objectNode();
    node.put("providerName", selection.providerName());
    node.put("modelName", selection.modelName());
    node.put("variant", selection.variant());
    return node;
  }

  static ModelSelection decodeModelSelection(JsonNode value, String context) {
    ObjectNode node = requireObject(value, context);
    requireExactFields(node, MODEL_SELECTION_FIELDS, context);
    return new ModelSelection(
        requiredText(node, "providerName", context),
        requiredText(node, "modelName", context),
        requiredText(node, "variant", context));
  }

  // ---------- AssistantMessageMetadata ----------

  static ObjectNode encodeAssistantMetadata(AssistantMessageMetadata metadata) {
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

  static AssistantMessageMetadata decodeAssistantMetadata(JsonNode value) {
    ObjectNode node = requireObject(value, "assistantMetadata");
    requireExactFields(node, METADATA_FIELDS, "assistantMetadata");
    GenerationStopReason stopReason =
        readEnum(
            GenerationStopReason.class, text(node, "stopReason"), "assistantMetadata.stopReason");
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

  // ---------- 通用 JSON 工具方法 ----------

  static ObjectNode requireObject(JsonNode value, String context) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return object;
  }

  static ArrayNode requireArray(JsonNode value, String context) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(context + " must be a JSON array");
    }
    return array;
  }

  static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    return requiredText(value, field, context);
  }

  static String requiredText(JsonNode value, String field, String context) {
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    String text = value.textValue();
    if (text.isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must not be blank");
    }
    return text;
  }

  static boolean requiredBoolean(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  static long requiredNonNegativeLong(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.longValue();
  }

  static int requiredNonNegativeInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw new IllegalArgumentException(context + "." + field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  static int requiredPositiveInt(ObjectNode node, String field, String context) {
    int value = requiredNonNegativeInt(node, field, context);
    if (value <= 0) {
      throw new IllegalArgumentException(context + "." + field + " must be positive");
    }
    return value;
  }

  static Integer nullablePositiveInt(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a positive integer or null");
    }
    return value.intValue();
  }

  static BigDecimal requiredDecimal(ObjectNode node, String field, String context) {
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

  static <E extends Enum<E>> E readEnum(Class<E> kind, String name, String context) {
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

  static <E extends Enum<E>> E nullableEnum(
      Class<E> kind, ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    return readEnum(kind, text(node, field), context);
  }

  static String nullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    return value.isNull() ? null : text(node, field);
  }

  /**
   * 校验 canonical 小写 dotted/dashed 标识符（contributorId / customType / rendererKey）：非 null、小写字母数字段以 单个
   * {@code .} 或 {@code -} 分隔、无前导/尾随/连续分隔符、长度不超过 {@link #MAX_IDENTIFIER_CHARS}。
   */
  static String requireCanonicalIdentifier(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.length() > MAX_IDENTIFIER_CHARS || !CANONICAL_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field
              + " must be a lowercase dotted/dashed identifier of at most "
              + MAX_IDENTIFIER_CHARS
              + " chars: "
              + value);
    }
    return value;
  }

  static UUID requiredPositiveId(ObjectNode node, String field, String context) {
    String text = requiredText(node, field, context);
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string", error);
    }
  }

  static UUID nullablePositiveId(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text or null");
    }
    try {
      return UUID.fromString(value.textValue());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          context + "." + field + " must be a canonical UUID string", error);
    }
  }

  static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
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

  static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return Set.copyOf(set);
  }
}
