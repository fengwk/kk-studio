package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ProviderResponse} 的严格、确定性 JSON codec。顶层严格字段为 {@code text}、{@code thinking}、{@code
 * toolCalls}、 {@code stopReason}、{@code usage}、{@code cost}、{@code requestId}、{@code
 * serviceTier}、{@code rawUsageJson}、{@code toolCallDiagnostics}，另有可选的 {@code
 * decodeDurationMillis}（Harness 观测计时；缺失或 null 表示无可信流计时，仅为兼容既有 durable 行而可选）；每个嵌套层都要求精确字段集合，并以
 * {@link IllegalArgumentException} 拒绝未知/缺失/类型错误的值。
 *
 * <p>{@code rawUsageJson} 原样保留；{@link ModelCost} 中的 {@link BigDecimal} 字段以 {@code toPlainString()}
 * 字符串输出。
 */
public final class ProviderResponseJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> RESPONSE_FIELDS =
      orderedSet(
          "text",
          "thinking",
          "toolCalls",
          "stopReason",
          "usage",
          "cost",
          "requestId",
          "serviceTier",
          "rawUsageJson",
          "toolCallDiagnostics");
  private static final Set<String> RESPONSE_OPTIONAL_FIELDS = orderedSet("decodeDurationMillis");
  private static final Set<String> TOOL_CALL_FIELDS =
      orderedSet("id", "name", "argumentsJson", "historyAction");
  private static final Set<String> DIAGNOSTIC_FIELDS =
      orderedSet("callIndex", "id", "name", "partialArguments", "message");
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

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ProviderResponseJsonCodec() {}

  public String encode(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(response));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode provider response", exception);
    }
  }

  public JsonNode encodeNode(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    ObjectNode node = NODES.objectNode();
    node.put("text", response.text());
    node.put("thinking", response.thinking());
    ArrayNode toolCalls = node.putArray("toolCalls");
    for (ProviderToolCall toolCall : response.toolCalls()) {
      toolCalls.add(encodeToolCall(toolCall));
    }
    node.put("stopReason", response.stopReason().name());
    node.set("usage", encodeUsage(response.usage()));
    node.set("cost", encodeCost(response.cost()));
    if (response.requestId() == null) {
      node.putNull("requestId");
    } else {
      node.put("requestId", response.requestId());
    }
    if (response.serviceTier() == null) {
      node.putNull("serviceTier");
    } else {
      node.put("serviceTier", response.serviceTier());
    }
    node.put("rawUsageJson", requireJsonContainer(response.rawUsageJson(), "rawUsageJson"));
    ArrayNode diagnostics = node.putArray("toolCallDiagnostics");
    for (ProviderToolCallDiagnostic diagnostic : response.toolCallDiagnostics()) {
      diagnostics.add(encodeToolCallDiagnostic(diagnostic));
    }
    if (response.decodeDurationMillis() != null) {
      node.put("decodeDurationMillis", response.decodeDurationMillis());
    }
    return node;
  }

  public ProviderResponse decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed provider response", exception);
    }
  }

  public ProviderResponse decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = object(value, "response");
    requireFields(node, RESPONSE_FIELDS, RESPONSE_OPTIONAL_FIELDS, "response");
    String text = text(node, "text");
    String thinking = text(node, "thinking");
    ArrayNode toolCalls = array(node.get("toolCalls"), "toolCalls");
    List<ProviderToolCall> toolCallList = new ArrayList<>(toolCalls.size());
    for (JsonNode item : toolCalls) {
      toolCallList.add(decodeToolCall(item));
    }
    GenerationStopReason stopReason;
    try {
      stopReason = GenerationStopReason.valueOf(text(node, "stopReason"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown generation stop reason", exception);
    }
    ModelUsage usage = decodeUsage(node.get("usage"));
    ModelCost cost = decodeCost(node.get("cost"));
    String requestId = decodeNullableText(node, "requestId");
    String serviceTier = decodeNullableText(node, "serviceTier");
    String rawUsageJson = jsonContainerText(node, "rawUsageJson");
    ArrayNode diagnostics = array(node.get("toolCallDiagnostics"), "toolCallDiagnostics");
    List<ProviderToolCallDiagnostic> diagnosticList = new ArrayList<>(diagnostics.size());
    for (JsonNode item : diagnostics) {
      diagnosticList.add(decodeToolCallDiagnostic(item));
    }
    Long decodeDurationMillis = optionalNonNegativeLong(node, "decodeDurationMillis");
    return new ProviderResponse(
        text,
        thinking,
        toolCallList,
        stopReason,
        usage,
        cost,
        requestId,
        serviceTier,
        rawUsageJson,
        diagnosticList,
        decodeDurationMillis);
  }

  private ObjectNode encodeToolCallDiagnostic(ProviderToolCallDiagnostic diagnostic) {
    ObjectNode node = NODES.objectNode();
    node.put("callIndex", diagnostic.callIndex());
    if (diagnostic.id() == null) {
      node.putNull("id");
    } else {
      node.put("id", diagnostic.id());
    }
    if (diagnostic.name() == null) {
      node.putNull("name");
    } else {
      node.put("name", diagnostic.name());
    }
    node.put("partialArguments", diagnostic.partialArguments());
    node.put("message", diagnostic.message());
    return node;
  }

  private ProviderToolCallDiagnostic decodeToolCallDiagnostic(JsonNode value) {
    ObjectNode node = object(value, "toolCallDiagnostic");
    requireFields(node, DIAGNOSTIC_FIELDS, "toolCallDiagnostic");
    long callIndex = nonNegativeLong(node, "callIndex");
    if (callIndex > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("callIndex overflow: " + callIndex);
    }
    String id = decodeNullableText(node, "id");
    String name = decodeNullableText(node, "name");
    String partialArguments = text(node, "partialArguments");
    String message = text(node, "message");
    return new ProviderToolCallDiagnostic((int) callIndex, id, name, partialArguments, message);
  }

  private ObjectNode encodeToolCall(ProviderToolCall call) {
    ObjectNode node = NODES.objectNode();
    node.put("id", call.id());
    node.put("name", call.name());
    node.put("argumentsJson", requireJsonObject(call.argumentsJson(), "argumentsJson"));
    if (call.historyAction() == null) {
      node.putNull("historyAction");
    } else {
      node.put("historyAction", call.historyAction());
    }
    return node;
  }

  private ProviderToolCall decodeToolCall(JsonNode value) {
    ObjectNode node = object(value, "toolCall");
    requireFields(node, TOOL_CALL_FIELDS, "toolCall");
    return new ProviderToolCall(
        text(node, "id"),
        text(node, "name"),
        jsonObjectText(node, "argumentsJson"),
        decodeNullableText(node, "historyAction"));
  }

  private ObjectNode encodeUsage(ModelUsage usage) {
    ObjectNode node = NODES.objectNode();
    node.put("inputTokens", usage.inputTokens());
    node.put("outputTokens", usage.outputTokens());
    node.put("cacheReadTokens", usage.cacheReadTokens());
    node.put("cacheWriteTokens", usage.cacheWriteTokens());
    node.put("cacheWriteLongTokens", usage.cacheWriteLongTokens());
    node.put("reasoningTokens", usage.reasoningTokens());
    node.put("providerTotalTokens", usage.providerTotalTokens());
    return node;
  }

  private ModelUsage decodeUsage(JsonNode value) {
    ObjectNode node = object(value, "usage");
    requireFields(node, USAGE_FIELDS, "usage");
    return new ModelUsage(
        nonNegativeLong(node, "inputTokens"),
        nonNegativeLong(node, "outputTokens"),
        nonNegativeLong(node, "cacheReadTokens"),
        nonNegativeLong(node, "cacheWriteTokens"),
        nonNegativeLong(node, "cacheWriteLongTokens"),
        nonNegativeLong(node, "reasoningTokens"),
        nonNegativeLong(node, "providerTotalTokens"));
  }

  private ObjectNode encodeCost(ModelCost cost) {
    ObjectNode node = NODES.objectNode();
    node.put("currency", cost.currency());
    node.put("input", cost.input().toPlainString());
    node.put("output", cost.output().toPlainString());
    node.put("cacheRead", cost.cacheRead().toPlainString());
    node.put("cacheWrite", cost.cacheWrite().toPlainString());
    node.put("cacheWriteLong", cost.cacheWriteLong().toPlainString());
    node.put("reasoning", cost.reasoning().toPlainString());
    node.put("total", cost.total().toPlainString());
    return node;
  }

  private ModelCost decodeCost(JsonNode value) {
    ObjectNode node = object(value, "cost");
    requireFields(node, COST_FIELDS, "cost");
    return new ModelCost(
        text(node, "currency"),
        decimal(node, "input"),
        decimal(node, "output"),
        decimal(node, "cacheRead"),
        decimal(node, "cacheWrite"),
        decimal(node, "cacheWriteLong"),
        decimal(node, "reasoning"),
        decimal(node, "total"));
  }

  private static ObjectNode object(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  private static ArrayNode array(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
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

  private static String decodeNullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text or null");
    }
    return value.textValue();
  }

  private static long nonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    long parsed = value.longValue();
    if (parsed < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    return parsed;
  }

  private static BigDecimal decimal(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    try {
      return new BigDecimal(value.textValue());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(field + " must be a decimal", exception);
    }
  }

  private static String jsonObjectText(ObjectNode node, String field) {
    String value = text(node, field);
    return requireJsonObject(value, field);
  }

  private static String jsonContainerText(ObjectNode node, String field) {
    String value = text(node, field);
    return requireJsonContainer(value, field);
  }

  private static String requireJsonObject(String value, String field) {
    JsonNode parsed = parseJson(value, field);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException(field + " must contain a JSON object");
    }
    return value;
  }

  private static String requireJsonContainer(String value, String field) {
    JsonNode parsed = parseJson(value, field);
    if (!parsed.isObject() && !parsed.isArray()) {
      throw new IllegalArgumentException(field + " must contain a JSON object or array");
    }
    return value;
  }

  private static JsonNode parseJson(String value, String field) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(value);
      if (parsed == null) {
        throw new IllegalArgumentException(field + " must contain JSON");
      }
      return parsed;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(field + " must contain JSON", exception);
    }
  }

  /** 可空非负整数：缺失或 null 归一化为 null；其他类型或负值一律拒绝。 */
  private static Long optionalNonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be a non-negative integer or null");
    }
    long parsed = value.longValue();
    if (parsed < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer or null");
    }
    return parsed;
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String name) {
    requireFields(node, expected, Set.of(), name);
  }

  /**
   * 严格字段集合校验：{@code required} 必须全部存在，且实际字段不得超出 {@code required} 与 {@code optional}
   * 的并集；未知字段、缺失必填字段一律拒绝。
   */
  private static void requireFields(
      ObjectNode node, Set<String> required, Set<String> optional, String name) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    Set<String> allowed = new HashSet<>(required);
    allowed.addAll(optional);
    if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
      throw new IllegalArgumentException(
          "unexpected fields for " + name + ": " + actual + " (expected " + required + ")");
    }
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    for (String value : values) {
      set.add(value);
    }
    return set;
  }
}
