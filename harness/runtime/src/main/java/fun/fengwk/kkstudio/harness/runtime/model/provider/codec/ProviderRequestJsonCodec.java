package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@link ProviderRequest} 的严格、确定性 JSON codec。顶层严格字段为 {@code model}、{@code variant}、{@code
 * outputTokens}、{@code messages}、{@code tools}、{@code
 * cacheControl}；每个嵌套层同样要求精确字段集合，并拒绝未知/缺失/类型错误的值。
 *
 * <p>原始 JSON 字符串（{@code json}、{@code argumentsJson}、{@code detailsJson}、{@code
 * inputSchemaJson}）原样保留； enum {@code Set} 字段按 enum name 排序，使输出在跨 JVM 时保持 deterministic。
 *
 * <p>本 codec 服务于 durable 路径（Model invocation request 持久化 / 重放）：媒体块（IMAGE/DOCUMENT/AUDIO/VIDEO）携带
 * attempt-only 的 presigned source，encode/decode 一律确定性拒绝；{@code resource} 块是唯一允许的 durable 媒体引用。有效
 * attempt 请求（含媒体块）只存在于内存，由 provider adapter 直接发出，绝不经过本 codec 重序列化。
 */
public final class ProviderRequestJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Comparator<Enum<?>> ENUM_NAME_COMPARATOR = Comparator.comparing(Enum::name);

  /** 共享 model/variant 子树 codec，确保 wire 与 {@code ModelDescriptorJsonCodec} 单一权威实现一致。 */
  private static final ModelDescriptorJsonCodec SHARED_MODEL_CODEC = new ModelDescriptorJsonCodec();

  private static final Set<String> REQUEST_FIELDS =
      orderedSet("model", "variant", "outputTokens", "messages", "tools", "cacheControl");
  private static final Set<String> CACHE_CONTROL_FIELDS =
      orderedSet("retention", "affinityKey", "breakpoints");
  private static final Set<String> MESSAGE_FIELDS = orderedSet("role", "contents");
  private static final Set<String> TOOL_DEFINITION_FIELDS =
      orderedSet("name", "description", "inputSchemaJson");
  private static final Set<String> TOOL_CALL_FIELDS = orderedSet("id", "name", "argumentsJson");

  private static final Set<String> TEXT_BLOCK_FIELDS = orderedSet("type", "text");
  private static final Set<String> THINKING_BLOCK_FIELDS = orderedSet("type", "thinking");
  private static final Set<String> JSON_BLOCK_FIELDS = orderedSet("type", "json");
  private static final Set<String> TOOL_CALL_BLOCK_FIELDS = orderedSet("type", "toolCall");
  private static final Set<String> TOOL_RESULT_BLOCK_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "contents", "error", "detailsJson");
  private static final Set<String> RESOURCE_BLOCK_FIELDS =
      orderedSet("type", "blobId", "name", "preview");

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
    node.put("outputTokens", request.outputTokens());
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
    int outputTokens = positiveInt(node, "outputTokens");
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
    ProviderCacheControl cacheControl = decodeCacheControlNode(node.get("cacheControl"));
    return new ProviderRequest(model, variant, outputTokens, messageList, toolList, cacheControl);
  }

  /** 编码 {@link ProviderCacheControl} 子树，供紧凑 {@code ModelRequestSpec} 复用同一 wire。 */
  public ObjectNode encodeCacheControlNode(ProviderCacheControl control) {
    Objects.requireNonNull(control, "control");
    return encodeCacheControl(control);
  }

  /** 解码 {@link ProviderCacheControl} 子树，供紧凑 {@code ModelRequestSpec} 复用同一 wire。 */
  public ProviderCacheControl decodeCacheControlNode(JsonNode value) {
    return decodeCacheControl(value);
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
    if (content instanceof ProviderImageBlock
        || content instanceof ProviderDocumentBlock
        || content instanceof ProviderAudioBlock
        || content instanceof ProviderVideoBlock) {
      throw new IllegalArgumentException(
          content.getClass().getSimpleName()
              + " is transient (provider attempt projection only) and must never be persisted;"
              + " durable media references use resource blocks");
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
    if (content instanceof ProviderResourceBlock value) {
      ObjectNode node = NODES.objectNode();
      node.put("type", "resource");
      node.put("blobId", value.blobId().toString());
      node.put("name", value.name());
      node.put("preview", value.preview());
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
      case "resource" -> {
        requireFields(node, RESOURCE_BLOCK_FIELDS, "resource content block");
        yield new ProviderResourceBlock(
            canonicalUuid(node, "blobId"), text(node, "name"), textAllowEmpty(node, "preview"));
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

  // ---------- 工具方法 ----------

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

  /** 必填文本但允许空字符串（如 resource preview 的 empty 形态）。 */
  private static String textAllowEmpty(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  /** 必填 canonical UUID 文本字段（{@code UUID.fromString} 往返一致）。 */
  private static UUID canonicalUuid(ObjectNode node, String field) {
    String value = text(node, field);
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
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

  private static int positiveInt(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer");
    }
    return value.intValue();
  }

  private static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be boolean");
    }
    return value.booleanValue();
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
