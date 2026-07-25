package fun.fengwk.kkstudio.harness.model.provider.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Strict, deterministic JSON codec for {@link ProviderRequest}. Top-level strict fields are {@code
 * model}, {@code variant}, {@code messages}, {@code tools}, {@code cacheControl}; every nested
 * layer also requires an exact field set and rejects unknown/missing/wrong-type values.
 *
 * <p>Raw JSON strings ({@code json}, {@code argumentsJson}, {@code detailsJson}, {@code
 * inputSchemaJson}) are preserved verbatim; {@link BigDecimal} values are emitted as {@code
 * toPlainString()} strings; enum {@code Set} fields are sorted by enum name so output is
 * deterministic across JVMs.
 */
public final class ProviderRequestJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Comparator<Enum<?>> ENUM_NAME_COMPARATOR = Comparator.comparing(Enum::name);

  /** 共享 model/variant 子树 codec，确保 wire 与 {@code ModelDescriptorJsonCodec} 单一权威实现一致。 */
  private static final ModelDescriptorJsonCodec SHARED_MODEL_CODEC = new ModelDescriptorJsonCodec();

  private static final Set<String> REQUEST_FIELDS =
      orderedSet("model", "variant", "messages", "tools", "cacheControl");
  private static final Set<String> CACHE_CONTROL_FIELDS =
      orderedSet("retention", "affinityKey", "breakpoints");
  private static final Set<String> MESSAGE_FIELDS = orderedSet("role", "contents");
  private static final Set<String> TOOL_DEFINITION_FIELDS =
      orderedSet("name", "description", "inputSchemaJson");
  private static final Set<String> TOOL_CALL_FIELDS = orderedSet("id", "name", "argumentsJson");

  private static final Set<String> TEXT_BLOCK_FIELDS = orderedSet("type", "text");
  private static final Set<String> IMAGE_BLOCK_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> AUDIO_BLOCK_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> VIDEO_BLOCK_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> THINKING_BLOCK_FIELDS = orderedSet("type", "thinking");
  private static final Set<String> JSON_BLOCK_FIELDS = orderedSet("type", "json");
  private static final Set<String> TOOL_CALL_BLOCK_FIELDS = orderedSet("type", "toolCall");
  private static final Set<String> TOOL_RESULT_BLOCK_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "contents", "error", "detailsJson");

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public ProviderRequestJsonCodec() {}

  public String encode(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(request));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode provider request", exception);
    }
  }

  public JsonNode encodeNode(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    ObjectNode node = NODES.objectNode();
    node.set("model", SHARED_MODEL_CODEC.encodeDescriptorNode(request.model()));
    node.set("variant", SHARED_MODEL_CODEC.encodeVariantNode(request.variant()));
    ArrayNode messages = node.putArray("messages");
    for (ProviderMessage message : request.messages()) {
      messages.add(encodeMessage(message));
    }
    ArrayNode tools = node.putArray("tools");
    for (ProviderToolDefinition tool : request.tools()) {
      tools.add(encodeToolDefinition(tool));
    }
    node.set("cacheControl", encodeCacheControl(request.cacheControl()));
    return node;
  }

  public ProviderRequest decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed provider request", exception);
    }
  }

  public ProviderRequest decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = object(value, "request");
    requireFields(node, REQUEST_FIELDS, "request");
    ModelDescriptor model = SHARED_MODEL_CODEC.decodeDescriptorNode(node.get("model"));
    ModelVariant variant = SHARED_MODEL_CODEC.decodeVariantNode(node.get("variant"));
    ArrayNode messages = array(node.get("messages"), "messages");
    List<ProviderMessage> messageList = new ArrayList<>(messages.size());
    for (JsonNode item : messages) {
      messageList.add(decodeMessage(item));
    }
    ArrayNode tools = array(node.get("tools"), "tools");
    List<ProviderToolDefinition> toolList = new ArrayList<>(tools.size());
    for (JsonNode item : tools) {
      toolList.add(decodeToolDefinition(item));
    }
    ProviderCacheControl cacheControl = decodeCacheControl(node.get("cacheControl"));
    return new ProviderRequest(model, variant, messageList, toolList, cacheControl);
  }

  // ---------- ProviderCacheControl ----------

  private ObjectNode encodeCacheControl(ProviderCacheControl control) {
    ObjectNode node = NODES.objectNode();
    node.put("retention", control.retention().name());
    if (control.affinityKey() == null) {
      node.putNull("affinityKey");
    } else {
      node.put("affinityKey", control.affinityKey());
    }
    ArrayNode breakpoints = node.putArray("breakpoints");
    for (PromptCacheBreakpoint breakpoint : sortedEnums(control.breakpoints(), "breakpoints")) {
      breakpoints.add(breakpoint.name());
    }
    return node;
  }

  private ProviderCacheControl decodeCacheControl(JsonNode value) {
    ObjectNode node = object(value, "cacheControl");
    requireFields(node, CACHE_CONTROL_FIELDS, "cacheControl");
    PromptCacheRetention retention;
    try {
      retention = PromptCacheRetention.valueOf(text(node, "retention"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown prompt cache retention", exception);
    }
    String affinityKey = decodeNullableText(node, "affinityKey");
    Set<PromptCacheBreakpoint> breakpoints =
        decodeEnumSet(node.get("breakpoints"), PromptCacheBreakpoint.class, "breakpoints");
    if (retention == PromptCacheRetention.NONE) {
      if (affinityKey != null) {
        throw new IllegalArgumentException("affinityKey must be null when retention is NONE");
      }
      if (!breakpoints.isEmpty()) {
        throw new IllegalArgumentException("breakpoints must be empty when retention is NONE");
      }
      return ProviderCacheControl.none();
    }
    if (affinityKey == null || affinityKey.isBlank()) {
      throw new IllegalArgumentException(
          "affinityKey must be non-blank when retention is " + retention);
    }
    if (breakpoints.isEmpty()) {
      return ProviderCacheControl.affinity(retention, affinityKey);
    }
    return ProviderCacheControl.breakpoints(retention, affinityKey, breakpoints);
  }

  // ---------- ProviderMessage ----------

  private ObjectNode encodeMessage(ProviderMessage message) {
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    for (ProviderContentBlock content : message.contents()) {
      contents.add(encodeContentBlock(content));
    }
    return node;
  }

  private ProviderMessage decodeMessage(JsonNode value) {
    ObjectNode node = object(value, "message");
    requireFields(node, MESSAGE_FIELDS, "message");
    ProviderMessageRole role;
    try {
      role = ProviderMessageRole.valueOf(text(node, "role"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown provider message role", exception);
    }
    ArrayNode contents = array(node.get("contents"), "contents");
    List<ProviderContentBlock> list = new ArrayList<>(contents.size());
    for (JsonNode item : contents) {
      list.add(decodeContentBlock(item));
    }
    return new ProviderMessage(role, list);
  }

  // ---------- ProviderToolDefinition ----------

  private ObjectNode encodeToolDefinition(ProviderToolDefinition tool) {
    ObjectNode node = NODES.objectNode();
    node.put("name", tool.name());
    node.put("description", tool.description());
    node.put("inputSchemaJson", requireJsonObject(tool.inputSchemaJson(), "inputSchemaJson"));
    return node;
  }

  private ProviderToolDefinition decodeToolDefinition(JsonNode value) {
    ObjectNode node = object(value, "tool");
    requireFields(node, TOOL_DEFINITION_FIELDS, "tool");
    return new ProviderToolDefinition(
        text(node, "name"), text(node, "description"), jsonObjectText(node, "inputSchemaJson"));
  }

  // ---------- ProviderContentBlock ----------

  private ObjectNode encodeContentBlock(ProviderContentBlock content) {
    if (content instanceof ProviderTextBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "text");
      node.put("text", value.text());
      return node;
    }
    if (content instanceof ProviderImageBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "image");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (content instanceof ProviderAudioBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "audio");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (content instanceof ProviderVideoBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "video");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
      return node;
    }
    if (content instanceof ProviderThinkingBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "thinking");
      node.put("thinking", value.thinking());
      return node;
    }
    if (content instanceof ProviderJsonBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "json");
      node.put("json", requireJson(value.json(), "json"));
      return node;
    }
    if (content instanceof ProviderToolCallBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "tool_call");
      node.set("toolCall", encodeToolCall(value.toolCall()));
      return node;
    }
    if (content instanceof ProviderToolResultBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "tool_result");
      node.put("toolCallId", value.toolCallId());
      node.put("toolName", value.toolName());
      ArrayNode contents = node.putArray("contents");
      for (ProviderContentBlock item : value.contents()) {
        contents.add(encodeContentBlock(item));
      }
      node.put("error", value.error());
      node.put("detailsJson", requireJsonObject(value.detailsJson(), "detailsJson"));
      return node;
    }
    throw new IllegalArgumentException("unsupported provider content block: " + content.getClass());
  }

  private ProviderContentBlock decodeContentBlock(JsonNode value) {
    ObjectNode node = object(value, "content");
    if (!node.has("type")) {
      throw new IllegalArgumentException("content block must have type field");
    }
    JsonNode typeNode = node.get("type");
    if (!typeNode.isTextual()) {
      throw new IllegalArgumentException("content block type must be text");
    }
    String type = typeNode.textValue();
    return switch (type) {
      case "text" -> {
        requireFields(node, TEXT_BLOCK_FIELDS, "text content block");
        yield new ProviderTextBlock(text(node, "text"));
      }
      case "image" -> {
        requireFields(node, IMAGE_BLOCK_FIELDS, "image content block");
        yield new ProviderImageBlock(text(node, "mediaType"), text(node, "source"));
      }
      case "audio" -> {
        requireFields(node, AUDIO_BLOCK_FIELDS, "audio content block");
        yield new ProviderAudioBlock(text(node, "mediaType"), text(node, "source"));
      }
      case "video" -> {
        requireFields(node, VIDEO_BLOCK_FIELDS, "video content block");
        yield new ProviderVideoBlock(text(node, "mediaType"), text(node, "source"));
      }
      case "thinking" -> {
        requireFields(node, THINKING_BLOCK_FIELDS, "thinking content block");
        yield new ProviderThinkingBlock(text(node, "thinking"));
      }
      case "json" -> {
        requireFields(node, JSON_BLOCK_FIELDS, "json content block");
        yield new ProviderJsonBlock(jsonText(node, "json"));
      }
      case "tool_call" -> {
        requireFields(node, TOOL_CALL_BLOCK_FIELDS, "tool_call content block");
        yield new ProviderToolCallBlock(decodeToolCall(node.get("toolCall")));
      }
      case "tool_result" -> {
        requireFields(node, TOOL_RESULT_BLOCK_FIELDS, "tool_result content block");
        ArrayNode contents = array(node.get("contents"), "contents");
        List<ProviderContentBlock> nested = new ArrayList<>(contents.size());
        for (JsonNode item : contents) {
          nested.add(decodeContentBlock(item));
        }
        yield new ProviderToolResultBlock(
            text(node, "toolCallId"),
            text(node, "toolName"),
            nested,
            bool(node, "error"),
            jsonObjectText(node, "detailsJson"));
      }
      default -> throw new IllegalArgumentException("unknown provider content block type: " + type);
    };
  }

  // ---------- ProviderToolCall ----------

  private ObjectNode encodeToolCall(ProviderToolCall call) {
    ObjectNode node = NODES.objectNode();
    node.put("id", call.id());
    node.put("name", call.name());
    node.put("argumentsJson", requireJsonObject(call.argumentsJson(), "argumentsJson"));
    return node;
  }

  private ProviderToolCall decodeToolCall(JsonNode value) {
    ObjectNode node = object(value, "toolCall");
    requireFields(node, TOOL_CALL_FIELDS, "toolCall");
    return new ProviderToolCall(
        text(node, "id"), text(node, "name"), jsonObjectText(node, "argumentsJson"));
  }

  // ---------- helpers ----------

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

  private static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static long positiveLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    long parsed = value.longValue();
    if (parsed <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return parsed;
  }

  private static void encodeNullableInt(ObjectNode node, String field, Integer value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static void encodeNullableDouble(ObjectNode node, String field, Double value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private static Integer decodeNullableInt(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be integer or null");
    }
    return value.intValue();
  }

  private static Double decodeNullableDouble(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new IllegalArgumentException(field + " must be number or null");
    }
    double parsed = value.doubleValue();
    if (!Double.isFinite(parsed)) {
      throw new IllegalArgumentException(field + " must be finite");
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

  private static String jsonText(ObjectNode node, String field) {
    String value = text(node, field);
    parseJson(value, field);
    return value;
  }

  private static String jsonObjectText(ObjectNode node, String field) {
    String value = text(node, field);
    JsonNode parsed = parseJson(value, field);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException(field + " must contain a JSON object");
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

  private static String requireJson(String value, String field) {
    parseJson(value, field);
    return value;
  }

  private static String requireJsonObject(String value, String field) {
    JsonNode parsed = parseJson(value, field);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException(field + " must contain a JSON object");
    }
    return value;
  }

  private static <E extends Enum<E>> Set<E> decodeEnumSet(
      JsonNode value, Class<E> elementType, String field) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(field + " must be an array");
    }
    Set<E> result = new TreeSet<>(ENUM_NAME_COMPARATOR);
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        throw new IllegalArgumentException(field + " must contain only enum name strings");
      }
      try {
        result.add(Enum.valueOf(elementType, item.textValue()));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(
            "unknown " + elementType.getSimpleName() + " value: " + item.textValue(), exception);
      }
    }
    return result;
  }

  private static <E extends Enum<E>> Set<E> sortedEnums(Set<E> source, String field) {
    Objects.requireNonNull(source, field);
    Set<E> copy = new TreeSet<>(ENUM_NAME_COMPARATOR);
    copy.addAll(source);
    return copy;
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String name) {
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          "unexpected fields for " + name + ": " + actual + " (expected " + expected + ")");
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
