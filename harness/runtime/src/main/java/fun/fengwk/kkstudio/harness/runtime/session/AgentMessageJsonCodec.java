package fun.fengwk.kkstudio.harness.runtime.session;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 共享的 {@link AgentMessage} 与 8 类 {@link AgentMessageContent} 严格、确定性 JSON codec。
 *
 * <p>字段固定且顺序确定；content 通过 {@code type} discriminator 区分。codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON
 * null；trailing token（共享 {@link ObjectMapper} 启用 {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用 {@link
 * JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）；未知枚举；未知 content discriminator；raw {@code
 * argumentsJson} / {@code detailsJson} 非单一 JSON object；raw {@code json} 非单一 JSON value；{@code
 * tool_result} 嵌套 {@code tool_call} 或 {@code tool_result}。encode 端同样严格校验 raw JSON 字段。
 *
 * <p>String API 与 node API 都用于组合 codec（如 Entry payload / Thread command payload codec）。
 */
public final class AgentMessageJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> MESSAGE_FIELDS = orderedSet("role", "contents");
  private static final Set<String> TEXT_FIELDS = orderedSet("type", "text");
  private static final Set<String> IMAGE_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> AUDIO_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> THINKING_FIELDS = orderedSet("type", "text");
  private static final Set<String> JSON_FIELDS = orderedSet("type", "json");
  private static final Set<String> TOOL_CALL_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "argumentsJson");
  private static final Set<String> TOOL_RESULT_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "contents", "error", "detailsJson");
  private static final Set<String> ARTIFACT_FIELDS =
      orderedSet("type", "artifactId", "mediaType", "preview");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public AgentMessageJsonCodec() {}

  /** 把 {@link AgentMessage} 编码为 canonical JSON 文本。 */
  public String encode(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    return write(encodeNode(message));
  }

  /** 把 {@link AgentMessage} 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(AgentMessage message) {
    Objects.requireNonNull(message, "message");
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    for (AgentMessageContent content : message.contents()) {
      contents.add(encodeContent(content));
    }
    return node;
  }

  /** 把 canonical JSON 文本解码为 {@link AgentMessage}；任何非法结构抛 {@link IllegalArgumentException}。 */
  public AgentMessage decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed agent message JSON", error);
    }
    if (root == null) {
      throw new IllegalArgumentException("malformed agent message JSON");
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码 {@link AgentMessage}。 */
  public AgentMessage decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    ObjectNode node = requireObject(value, "message");
    requireExactFields(node, MESSAGE_FIELDS, "message");
    AgentMessageRole role = readEnum(AgentMessageRole.class, text(node, "role"), "message.role");
    ArrayNode contentsNode = requireArray(node.get("contents"), "message.contents");
    List<AgentMessageContent> contents = new ArrayList<>(contentsNode.size());
    for (JsonNode element : contentsNode) {
      contents.add(decodeContent(element));
    }
    return new AgentMessage(role, contents);
  }

  // ---------- Content encoders & decoders ----------

  private static ObjectNode encodeContent(AgentMessageContent content) {
    ObjectNode node = NODES.objectNode();
    switch (content) {
      case TextMessageContent value -> {
        node.put("type", "text");
        node.put("text", value.text());
      }
      case ImageMessageContent value -> {
        node.put("type", "image");
        node.put("mediaType", value.mediaType());
        node.put("source", value.source());
      }
      case AudioMessageContent value -> {
        node.put("type", "audio");
        node.put("mediaType", value.mediaType());
        node.put("source", value.source());
      }
      case ThinkingMessageContent value -> {
        node.put("type", "thinking");
        node.put("text", value.text());
      }
      case JsonMessageContent value -> {
        // Encode 端也必须严格校验 raw JSON value。
        validateStrictJsonValue(value.json(), "content.json");
        node.put("type", "json");
        node.put("json", value.json());
      }
      case ToolCallMessageContent value -> {
        validateStrictJsonObject(value.argumentsJson(), "content.argumentsJson");
        node.put("type", "tool_call");
        node.put("toolCallId", value.toolCallId());
        node.put("toolName", value.toolName());
        node.put("argumentsJson", value.argumentsJson());
      }
      case ToolResultMessageContent value -> {
        validateStrictJsonObject(value.detailsJson(), "content.detailsJson");
        node.put("type", "tool_result");
        node.put("toolCallId", value.toolCallId());
        node.put("toolName", value.toolName());
        ArrayNode nested = node.putArray("contents");
        for (AgentMessageContent child : value.contents()) {
          if (child instanceof ToolCallMessageContent
              || child instanceof ToolResultMessageContent) {
            throw new IllegalArgumentException(
                "tool result contents cannot nest tool call or tool result");
          }
          nested.add(encodeContent(child));
        }
        node.put("error", value.error());
        node.put("detailsJson", value.detailsJson());
      }
      case ArtifactMessageContent value -> {
        node.put("type", "artifact");
        node.put("artifactId", value.artifactId());
        node.put("mediaType", value.mediaType());
        if (value.preview() == null) {
          node.putNull("preview");
        } else {
          node.put("preview", value.preview());
        }
      }
    }
    return node;
  }

  private static AgentMessageContent decodeContent(JsonNode value) {
    ObjectNode node = requireObject(value, "content");
    JsonNode typeNode = node.get("type");
    if (typeNode == null || !typeNode.isTextual()) {
      throw new IllegalArgumentException("content.type must be text");
    }
    String type = typeNode.textValue();
    return switch (type) {
      case "text" -> {
        requireExactFields(node, TEXT_FIELDS, "content");
        // TextMessageContent.text 域约束仅 non-null，decode 必须允许空字符串。
        yield new TextMessageContent(requiredTextAllowEmpty(node, "text", "content"));
      }
      case "image" -> {
        requireExactFields(node, IMAGE_FIELDS, "content");
        yield new ImageMessageContent(
            requiredText(node, "mediaType", "content"), requiredText(node, "source", "content"));
      }
      case "audio" -> {
        requireExactFields(node, AUDIO_FIELDS, "content");
        yield new AudioMessageContent(
            requiredText(node, "mediaType", "content"), requiredText(node, "source", "content"));
      }
      case "thinking" -> {
        requireExactFields(node, THINKING_FIELDS, "content");
        yield new ThinkingMessageContent(requiredTextAllowEmpty(node, "text", "content"));
      }
      case "json" -> {
        requireExactFields(node, JSON_FIELDS, "content");
        String rawJson = requiredStrictJsonValueString(node, "json", "content");
        yield new JsonMessageContent(rawJson);
      }
      case "tool_call" -> {
        requireExactFields(node, TOOL_CALL_FIELDS, "content");
        yield new ToolCallMessageContent(
            requiredText(node, "toolCallId", "content"),
            requiredText(node, "toolName", "content"),
            requiredStrictJsonObjectString(node, "argumentsJson", "content"));
      }
      case "tool_result" -> {
        requireExactFields(node, TOOL_RESULT_FIELDS, "content");
        ArrayNode contentsArray = requireArray(node.get("contents"), "content.contents");
        List<AgentMessageContent> nestedContents = new ArrayList<>(contentsArray.size());
        for (JsonNode element : contentsArray) {
          AgentMessageContent nested = decodeContent(element);
          if (nested instanceof ToolCallMessageContent
              || nested instanceof ToolResultMessageContent) {
            throw new IllegalArgumentException(
                "tool result contents cannot nest tool call or tool result");
          }
          nestedContents.add(nested);
        }
        yield new ToolResultMessageContent(
            requiredText(node, "toolCallId", "content"),
            requiredText(node, "toolName", "content"),
            nestedContents,
            requiredBoolean(node, "error", "content"),
            requiredStrictJsonObjectString(node, "detailsJson", "content"));
      }
      case "artifact" -> {
        requireExactFields(node, ARTIFACT_FIELDS, "content");
        JsonNode previewNode = node.get("preview");
        if (!previewNode.isTextual() && !previewNode.isNull()) {
          throw new IllegalArgumentException("content.preview must be text or null");
        }
        String preview = previewNode.isNull() ? null : previewNode.textValue();
        yield new ArtifactMessageContent(
            requiredText(node, "artifactId", "content"),
            requiredText(node, "mediaType", "content"),
            preview);
      }
      default -> throw new IllegalArgumentException("unknown agent message content type: " + type);
    };
  }

  // ---------- Raw-JSON strict validation (shared by encode & decode) ----------

  private static void validateStrictJsonObject(String raw, String name) {
    JsonNode parsed = parseStrict(raw, name);
    if (!parsed.isObject()) {
      throw new IllegalArgumentException(name + " must be a JSON object");
    }
  }

  private static void validateStrictJsonValue(String raw, String name) {
    parseStrict(raw, name);
  }

  private static String requiredStrictJsonObjectString(
      ObjectNode node, String field, String context) {
    String raw = requiredText(node, field, context);
    validateStrictJsonObject(raw, context + "." + field);
    return raw;
  }

  private static String requiredStrictJsonValueString(
      ObjectNode node, String field, String context) {
    String raw = requiredText(node, field, context);
    validateStrictJsonValue(raw, context + "." + field);
    return raw;
  }

  private static JsonNode parseStrict(String raw, String name) {
    JsonNode parsed;
    try {
      parsed = MAPPER.readTree(raw);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(name + " must contain JSON", error);
    }
    return parsed;
  }

  // ---------- Generic helpers ----------

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode agent message JSON", error);
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

  private static boolean requiredBoolean(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isBoolean()) {
      throw new IllegalArgumentException(context + "." + field + " must be boolean");
    }
    return value.booleanValue();
  }

  /** 与 {@link #requiredText} 类似，但允许空字符串（仅 non-null 约束，如 {@link TextMessageContent#text}）。 */
  private static String requiredTextAllowEmpty(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
  }

  private static <E extends Enum<E>> E readEnum(Class<E> kind, String name, String context) {
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
