package fun.fengwk.kkstudio.harness.model.provider.codec;

import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.array;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.bool;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.decodeNullableText;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.nonNegativeLong;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.object;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.orderedSet;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.requireFields;
import static fun.fengwk.kkstudio.harness.model.codec.JsonCodecSupport.text;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.HarnessJson;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.RequestBodyPricingSnapshot;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallDiagnostic;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ProviderResponse} 的严格、确定性 JSON codec。顶层严格字段为有序 {@code blocks}、{@code stopReason}、{@code
 * usage}、{@code requestId}、{@code serviceTier}、{@code rawUsageJson}、{@code toolCallDiagnostics} 与
 * {@code decodeDurationMillis}（Harness 观测计时，可空但字段必须存在：{@code null}
 * 显式表达“无可信流计时”，缺失即拒绝）；每个嵌套层都要求精确字段集合，并以 {@link IllegalArgumentException} 拒绝未知/缺失/类型错误的值。
 *
 * <p>{@code rawUsageJson} 原样保留。
 */
public final class ProviderResponseJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = HarnessJson.STRICT;

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> RESPONSE_FIELDS =
      orderedSet(
          "blocks",
          "stopReason",
          "usage",
          "requestId",
          "serviceTier",
          "rawUsageJson",
          "toolCallDiagnostics",
          "decodeDurationMillis");
  private static final Set<String> RESPONSE_OPTIONAL_FIELDS = orderedSet("requestBodyPricing");
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
          "cacheWriteByTtl",
          "reasoningTokens",
          "providerTotalTokens");

  public ProviderResponseJsonCodec() {}

  public String encode(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(response));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("cannot encode provider response", exception);
    }
  }

  public JsonNode encodeNode(ProviderResponse response) {
    Objects.requireNonNull(response, "response");
    ObjectNode node = NODES.objectNode();
    ArrayNode blocks = node.putArray("blocks");
    for (ProviderContentBlock block : response.blocks()) {
      blocks.add(encodeBlock(block));
    }
    node.put("stopReason", response.stopReason().name());
    node.set("usage", encodeUsage(response.usage()));
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
    if (response.decodeDurationMillis() == null) {
      node.putNull("decodeDurationMillis");
    } else {
      node.put("decodeDurationMillis", response.decodeDurationMillis());
    }
    if (response.requestBodyPricing() != null) {
      ObjectNode pricing = node.putObject("requestBodyPricing");
      for (Map.Entry<String, Boolean> entry : response.requestBodyPricing().matches().entrySet()) {
        pricing.put(entry.getKey(), entry.getValue());
      }
    }
    return node;
  }

  public ProviderResponse decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("malformed provider response", exception);
    }
  }

  public ProviderResponse decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = object(value, "response");
    requireFields(node, RESPONSE_FIELDS, RESPONSE_OPTIONAL_FIELDS, "response");
    ArrayNode encodedBlocks = array(node.get("blocks"), "blocks");
    List<ProviderContentBlock> blocks = new ArrayList<>(encodedBlocks.size());
    for (JsonNode item : encodedBlocks) {
      blocks.add(decodeBlock(item));
    }
    GenerationStopReason stopReason;
    try {
      stopReason = GenerationStopReason.valueOf(text(node, "stopReason"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown generation stop reason", exception);
    }
    ModelUsage usage = decodeUsage(node.get("usage"));
    String requestId = decodeNullableText(node, "requestId");
    String serviceTier = decodeNullableText(node, "serviceTier");
    String rawUsageJson = jsonContainerText(node, "rawUsageJson");
    ArrayNode diagnostics = array(node.get("toolCallDiagnostics"), "toolCallDiagnostics");
    List<ProviderToolCallDiagnostic> diagnosticList = new ArrayList<>(diagnostics.size());
    for (JsonNode item : diagnostics) {
      diagnosticList.add(decodeToolCallDiagnostic(item));
    }
    Long decodeDurationMillis = nullableNonNegativeLong(node, "decodeDurationMillis");
    RequestBodyPricingSnapshot requestBodyPricing = decodeRequestBodyPricing(node);
    return new ProviderResponse(
        blocks,
        stopReason,
        usage,
        requestId,
        serviceTier,
        rawUsageJson,
        diagnosticList,
        decodeDurationMillis,
        requestBodyPricing);
  }

  private RequestBodyPricingSnapshot decodeRequestBodyPricing(ObjectNode node) {
    JsonNode value = node.get("requestBodyPricing");
    if (value == null || value.isNull()) {
      return null;
    }
    ObjectNode pricing = object(value, "requestBodyPricing");
    Map<String, Boolean> matches = new LinkedHashMap<>();
    for (String fingerprint : pricing.propertyNames()) {
      JsonNode matched = pricing.get(fingerprint);
      if (matched == null || !matched.isBoolean()) {
        throw new IllegalArgumentException("requestBodyPricing values must be boolean");
      }
      matches.put(fingerprint, matched.booleanValue());
    }
    return new RequestBodyPricingSnapshot(matches);
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

  private ObjectNode encodeBlock(ProviderContentBlock block) {
    ObjectNode node = NODES.objectNode();
    if (block instanceof ProviderTextBlock value) {
      node.put("type", "text");
      node.put("text", value.text());
      encodeBlockData(node, value.providerBlockData());
    } else if (block instanceof ProviderThinkingBlock value) {
      node.put("type", "thinking");
      node.put("thinking", value.thinking());
      encodeBlockData(node, value.providerBlockData());
    } else if (block instanceof ProviderRedactedThinkingBlock value) {
      node.put("type", "redacted_thinking");
      node.put("data", value.data());
      encodeBlockData(node, value.providerBlockData());
    } else if (block instanceof ProviderToolCallBlock value) {
      node.put("type", "tool_call");
      node.set("toolCall", encodeToolCall(value.toolCall()));
      encodeBlockData(node, value.providerBlockData());
    } else if (block instanceof ProviderOpaqueBlock value) {
      node.put("type", "provider_native");
      node.put("blockType", value.blockType());
      encodeBlockData(node, value.providerBlockData());
    } else {
      throw new IllegalArgumentException(
          "unsupported provider response block: " + block.getClass());
    }
    return node;
  }

  private ProviderContentBlock decodeBlock(JsonNode value) {
    ObjectNode node = object(value, "block");
    String type = text(node, "type");
    ProviderBlockData blockData = decodeBlockData(node);
    switch (type) {
      case "text" -> {
        requireBlockFields(node, orderedSet("type", "text"), "text block");
        return new ProviderTextBlock(text(node, "text"), blockData);
      }
      case "thinking" -> {
        requireBlockFields(node, orderedSet("type", "thinking"), "thinking block");
        return new ProviderThinkingBlock(text(node, "thinking"), blockData);
      }
      case "redacted_thinking" -> {
        requireBlockFields(node, orderedSet("type", "data"), "redacted thinking block");
        return new ProviderRedactedThinkingBlock(text(node, "data"), blockData);
      }
      case "tool_call" -> {
        requireBlockFields(node, orderedSet("type", "toolCall"), "tool call block");
        return new ProviderToolCallBlock(decodeToolCall(node.get("toolCall")), blockData);
      }
      case "provider_native" -> {
        requireBlockFields(node, orderedSet("type", "blockType"), "native block");
        if (blockData == null) {
          throw new IllegalArgumentException("native block requires providerBlockData");
        }
        return new ProviderOpaqueBlock(text(node, "blockType"), blockData);
      }
      default -> throw new IllegalArgumentException(
          "unknown provider response block type: " + type);
    }
  }

  private void encodeBlockData(ObjectNode node, ProviderBlockData data) {
    if (data == null) {
      return;
    }
    ObjectNode encoded = node.putObject("providerBlockData");
    encoded.put("protocol", data.protocol().wireValue());
    ObjectNode affinity = encoded.putObject("affinity");
    affinity.put("providerType", data.affinity().providerType().wireValue());
    affinity.put("providerName", data.affinity().providerName());
    affinity.put("modelId", data.affinity().modelId());
    putNullableText(encoded, "signature", data.signature());
    putNullableText(encoded, "encryptedContent", data.encryptedContent());
    putNullableText(encoded, "itemId", data.itemId());
    putNullableText(encoded, "itemType", data.itemType());
    putNullableText(encoded, "contentType", data.contentType());
    encoded.put("refusal", data.refusal());
    encoded.set("nativeFields", data.nativeFields());
  }

  private ProviderBlockData decodeBlockData(ObjectNode node) {
    JsonNode value = node.get("providerBlockData");
    if (value == null) {
      return null;
    }
    ObjectNode encoded = object(value, "providerBlockData");
    requireFields(
        encoded,
        orderedSet(
            "protocol",
            "affinity",
            "signature",
            "encryptedContent",
            "itemId",
            "itemType",
            "contentType",
            "refusal",
            "nativeFields"),
        Set.of(),
        "providerBlockData");
    ObjectNode affinityNode = object(encoded.get("affinity"), "providerBlockData.affinity");
    requireFields(
        affinityNode, orderedSet("providerType", "providerName", "modelId"), Set.of(), "affinity");
    return new ProviderBlockData(
        ProviderReplayFormat.fromWireValue(text(encoded, "protocol")),
        new ProviderReplayAffinity(
            ProviderType.fromWireValue(text(affinityNode, "providerType")),
            text(affinityNode, "providerName"),
            text(affinityNode, "modelId")),
        decodeNullableText(encoded, "signature"),
        decodeNullableText(encoded, "encryptedContent"),
        decodeNullableText(encoded, "itemId"),
        decodeNullableText(encoded, "itemType"),
        decodeNullableText(encoded, "contentType"),
        bool(encoded, "refusal"),
        encoded.get("nativeFields"));
  }

  private void putNullableText(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private void requireBlockFields(ObjectNode node, Set<String> fields, String label) {
    Set<String> optional = new LinkedHashSet<>(fields);
    optional.add("providerBlockData");
    requireFields(node, fields, optional, label);
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
    ObjectNode ttlNode = node.putObject("cacheWriteByTtl");
    for (Map.Entry<Long, Long> entry : usage.cacheWriteByTtl().entrySet()) {
      ttlNode.put(entry.getKey().toString(), entry.getValue());
    }
    putNullableLong(node, "reasoningTokens", usage.reasoningTokens());
    putNullableLong(node, "providerTotalTokens", usage.providerTotalTokens());
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
        decodeTtlTokens(node.get("cacheWriteByTtl")),
        nullableNonNegativeLong(node, "reasoningTokens"),
        nullableNonNegativeLong(node, "providerTotalTokens"));
  }

  private static Map<Long, Long> decodeTtlTokens(JsonNode value) {
    ObjectNode node = object(value, "cacheWriteByTtl");
    Map<Long, Long> tokens = new LinkedHashMap<>();
    for (String key : node.propertyNames()) {
      long ttl;
      try {
        ttl = Long.parseLong(key);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("cacheWriteByTtl keys must be positive integer seconds");
      }
      if (ttl <= 0) {
        throw new IllegalArgumentException("cacheWriteByTtl keys must be positive integer seconds");
      }
      tokens.put(ttl, nonNegativeLong(node, key));
    }
    return Map.copyOf(tokens);
  }

  private static void putNullableLong(ObjectNode node, String field, Long value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
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
      // Jackson 3 空白输入返回 MissingNode（Jackson 2 返回 null），两者都必须判为缺少 JSON。
      if (parsed == null || parsed.isMissingNode()) {
        throw new IllegalArgumentException(field + " must contain JSON");
      }
      return parsed;
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(field + " must contain JSON", exception);
    }
  }

  /** 可空非负整数：显式 null 归一化为 null；字段存在性由字段集合校验保证，其他类型或负值一律拒绝。 */
  private static Long nullableNonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
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
}
