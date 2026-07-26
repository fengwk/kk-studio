package fun.fengwk.kkstudio.harness.runtime.entry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 最终 5 类 Runtime Entry payload 的严格、确定性 JSON codec。
 *
 * <p>直接对应 {@link EntryType}，仅支持 {@link RootEntryPayload} / {@link RuntimeConfigSnapshot} / {@link
 * MessageEntryPayload} / {@link CustomMessageEntryPayload} / {@link AssistantErrorEntryPayload}；其它
 * {@link EntryPayload} 实现显式拒绝。
 *
 * <p>codec 边界拒绝：未知 / 缺失 / 错误类型 / 显式 JSON null（除规定 optional 字段）；trailing token（共享 {@link
 * ObjectMapper} 启用 {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}）；duplicate field（启用
 * {@link JsonParser.Feature#STRICT_DUPLICATE_DETECTION}）；未知枚举；未知 content discriminator；raw {@code
 * argumentsJson} / {@code detailsJson} 非单一 JSON object；raw {@code json} 非单一 JSON value；{@code
 * tool_result} 嵌套 {@code tool_call} 或 {@code tool_result}。字段顺序固定；{@link BigDecimal} 字段以 {@code
 * toPlainString()} 字符串输出；list 顺序保留。
 *
 * <p>{@link RuntimeConfigSnapshot} 子树直接委派 {@link RuntimeConfigJsonCodec} 的 node API；{@code
 * ASSISTANT_ERROR} 的 {@code error} 子树直接委派 {@link ModelInvocationErrorJsonCodec} 的 node API。
 */
public final class RuntimeEntryPayloadJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> MESSAGE_FIELDS = orderedSet("message", "assistantMetadata");
  private static final Set<String> CUSTOM_MESSAGE_FIELDS = orderedSet("message");
  private static final Set<String> ASSISTANT_ERROR_FIELDS = orderedSet("error");
  private static final Set<String> ROOT_FIELDS = orderedSet();

  private static final Set<String> TEXT_CONTENT_FIELDS = orderedSet("type", "text");
  private static final Set<String> IMAGE_CONTENT_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> AUDIO_CONTENT_FIELDS = orderedSet("type", "mediaType", "source");
  private static final Set<String> THINKING_CONTENT_FIELDS = orderedSet("type", "text");
  private static final Set<String> JSON_CONTENT_FIELDS = orderedSet("type", "json");
  private static final Set<String> TOOL_CALL_CONTENT_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "argumentsJson");
  private static final Set<String> TOOL_RESULT_CONTENT_FIELDS =
      orderedSet("type", "toolCallId", "toolName", "contents", "error", "detailsJson");
  private static final Set<String> ARTIFACT_CONTENT_FIELDS =
      orderedSet("type", "artifactId", "mediaType", "preview");

  private static final Set<String> MESSAGE_INNER_FIELDS = orderedSet("role", "contents");
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

  private static final RuntimeConfigJsonCodec CONFIG_CODEC = new RuntimeConfigJsonCodec();
  private static final ModelInvocationErrorJsonCodec ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public RuntimeEntryPayloadJsonCodec() {}

  /** 把 {@link EntryPayload} 编码为 canonical JSON 文本；仅支持最终 8 类，其它实现显式拒绝。 */
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
    if (payload instanceof RuntimeConfigSnapshot snapshot) {
      return CONFIG_CODEC.encodeNode(snapshot);
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
    return decodeNode(type, root);
  }

  /** 从任意 {@link JsonNode} 按 {@link EntryType} 解码 payload。 */
  public EntryPayload decodeNode(EntryType type, JsonNode value) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(value, "value");
    return switch (type) {
      case ROOT -> decodeRoot(value);
      case RUNTIME_CONFIG -> CONFIG_CODEC.decodeNode(value);
      case MESSAGE -> decodeMessagePayload(value);
      case CUSTOM_MESSAGE -> decodeCustomMessagePayload(value);
      case ASSISTANT_ERROR -> decodeAssistantError(value);
    };
  }

  // ---------- Encoders ----------

  private static ObjectNode encodeMessagePayload(MessageEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", encodeMessage(value.message()));
    if (value.assistantMetadata() == null) {
      node.putNull("assistantMetadata");
    } else {
      node.set("assistantMetadata", encodeAssistantMetadata(value.assistantMetadata()));
    }
    return node;
  }

  private static ObjectNode encodeCustomMessagePayload(CustomMessageEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("message", encodeMessage(value.message()));
    return node;
  }

  private static ObjectNode encodeAssistantError(AssistantErrorEntryPayload value) {
    ObjectNode node = NODES.objectNode();
    node.set("error", ERROR_CODEC.encodeNode(value.error()));
    return node;
  }

  // ---------- Decoders ----------

  private static RootEntryPayload decodeRoot(JsonNode value) {
    ObjectNode node = requireObject(value, "ROOT");
    requireExactFields(node, ROOT_FIELDS, "ROOT");
    return new RootEntryPayload();
  }

  private static MessageEntryPayload decodeMessagePayload(JsonNode value) {
    ObjectNode node = requireObject(value, "MESSAGE");
    requireExactFields(node, MESSAGE_FIELDS, "MESSAGE");
    AgentMessage message = decodeMessage(node.get("message"));
    JsonNode metadataNode = node.get("assistantMetadata");
    if (metadataNode.isNull()) {
      return new MessageEntryPayload(message, null);
    }
    AssistantMessageMetadata metadata = decodeAssistantMetadata(metadataNode);
    return new MessageEntryPayload(message, metadata);
  }

  private static CustomMessageEntryPayload decodeCustomMessagePayload(JsonNode value) {
    ObjectNode node = requireObject(value, "CUSTOM_MESSAGE");
    requireExactFields(node, CUSTOM_MESSAGE_FIELDS, "CUSTOM_MESSAGE");
    return new CustomMessageEntryPayload(decodeMessage(node.get("message")));
  }

  private static AssistantErrorEntryPayload decodeAssistantError(JsonNode value) {
    ObjectNode node = requireObject(value, "ASSISTANT_ERROR");
    requireExactFields(node, ASSISTANT_ERROR_FIELDS, "ASSISTANT_ERROR");
    ModelInvocationError error = ERROR_CODEC.decodeNode(node.get("error"));
    return new AssistantErrorEntryPayload(error);
  }

  // ---------- AgentMessage / content encoders & decoders ----------

  private static ObjectNode encodeMessage(AgentMessage message) {
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    for (AgentMessageContent content : message.contents()) {
      contents.add(encodeContent(content));
    }
    return node;
  }

  private static AgentMessage decodeMessage(JsonNode value) {
    ObjectNode node = requireObject(value, "message");
    requireExactFields(node, MESSAGE_INNER_FIELDS, "message");
    AgentMessageRole role = readEnum(AgentMessageRole.class, text(node, "role"), "message.role");
    ArrayNode contentsNode = requireArray(node.get("contents"), "message.contents");
    List<AgentMessageContent> contents = new ArrayList<>(contentsNode.size());
    for (JsonNode element : contentsNode) {
      contents.add(decodeContent(element));
    }
    return new AgentMessage(role, contents);
  }

  private static ObjectNode encodeContent(AgentMessageContent content) {
    ObjectNode node = NODES.objectNode();
    if (content instanceof TextMessageContent value) {
      node.put("type", "text");
      node.put("text", value.text());
    } else if (content instanceof ImageMessageContent value) {
      node.put("type", "image");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
    } else if (content instanceof AudioMessageContent value) {
      node.put("type", "audio");
      node.put("mediaType", value.mediaType());
      node.put("source", value.source());
    } else if (content instanceof ThinkingMessageContent value) {
      node.put("type", "thinking");
      node.put("text", value.text());
    } else if (content instanceof JsonMessageContent value) {
      // Encode 端也必须严格校验 raw JSON value。
      validateStrictJsonValue(value.json(), "content.json");
      node.put("type", "json");
      node.put("json", value.json());
    } else if (content instanceof ToolCallMessageContent value) {
      validateStrictJsonObject(value.argumentsJson(), "content.argumentsJson");
      node.put("type", "tool_call");
      node.put("toolCallId", value.toolCallId());
      node.put("toolName", value.toolName());
      node.put("argumentsJson", value.argumentsJson());
    } else if (content instanceof ToolResultMessageContent value) {
      validateStrictJsonObject(value.detailsJson(), "content.detailsJson");
      node.put("type", "tool_result");
      node.put("toolCallId", value.toolCallId());
      node.put("toolName", value.toolName());
      ArrayNode nested = node.putArray("contents");
      for (AgentMessageContent child : value.contents()) {
        if (child instanceof ToolCallMessageContent || child instanceof ToolResultMessageContent) {
          throw new IllegalArgumentException(
              "tool result contents cannot nest tool call or tool result");
        }
        nested.add(encodeContent(child));
      }
      node.put("error", value.error());
      node.put("detailsJson", value.detailsJson());
    } else if (content instanceof ArtifactMessageContent value) {
      node.put("type", "artifact");
      node.put("artifactId", value.artifactId());
      node.put("mediaType", value.mediaType());
      if (value.preview() == null) {
        node.putNull("preview");
      } else {
        node.put("preview", value.preview());
      }
    } else {
      throw new IllegalArgumentException(
          "unsupported agent message content: " + content.getClass().getName());
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
        requireExactFields(node, TEXT_CONTENT_FIELDS, "content");
        // TextMessageContent.text 域约束仅 non-null，decode 必须允许空字符串。
        yield new TextMessageContent(requiredTextAllowEmpty(node, "text", "content"));
      }
      case "image" -> {
        requireExactFields(node, IMAGE_CONTENT_FIELDS, "content");
        yield new ImageMessageContent(
            requiredText(node, "mediaType", "content"), requiredText(node, "source", "content"));
      }
      case "audio" -> {
        requireExactFields(node, AUDIO_CONTENT_FIELDS, "content");
        yield new AudioMessageContent(
            requiredText(node, "mediaType", "content"), requiredText(node, "source", "content"));
      }
      case "thinking" -> {
        requireExactFields(node, THINKING_CONTENT_FIELDS, "content");
        yield new ThinkingMessageContent(requiredTextAllowEmpty(node, "text", "content"));
      }
      case "json" -> {
        requireExactFields(node, JSON_CONTENT_FIELDS, "content");
        String rawJson = requiredStrictJsonValueString(node, "json", "content");
        yield new JsonMessageContent(rawJson);
      }
      case "tool_call" -> {
        requireExactFields(node, TOOL_CALL_CONTENT_FIELDS, "content");
        yield new ToolCallMessageContent(
            requiredText(node, "toolCallId", "content"),
            requiredText(node, "toolName", "content"),
            requiredStrictJsonObjectString(node, "argumentsJson", "content"));
      }
      case "tool_result" -> {
        requireExactFields(node, TOOL_RESULT_CONTENT_FIELDS, "content");
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
        requireExactFields(node, ARTIFACT_CONTENT_FIELDS, "content");
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
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    String text = value.textValue();
    if (text.isBlank()) {
      throw new IllegalArgumentException(context + "." + field + " must not be blank");
    }
    return text;
  }

  /** 与 {@link #requiredText} 类似，但允许空字符串（仅 non-null 约束，如 {@link TextMessageContent#text}）。 */
  private static String requiredTextAllowEmpty(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be text");
    }
    return value.textValue();
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
    try {
      return Enum.valueOf(kind, name);
    } catch (IllegalArgumentException | NullPointerException error) {
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
