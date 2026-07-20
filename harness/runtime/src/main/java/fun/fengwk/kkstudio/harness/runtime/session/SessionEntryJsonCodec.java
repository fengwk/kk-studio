package fun.fengwk.kkstudio.harness.runtime.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Session Entry payload 的唯一 JSON 边界；未知字段、类型或坏嵌套内容一律拒绝。 */
public final class SessionEntryJsonCodec {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  public String encode(SessionEntryPayload payload) {
    Objects.requireNonNull(payload, "payload");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodePayload(payload));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode session entry payload", exception);
    }
  }

  public SessionEntryPayload decode(SessionEntryType type, String payloadJson) {
    Objects.requireNonNull(type, "type");
    try {
      return decodePayload(type, object(OBJECT_MAPPER.readTree(payloadJson), "payload"));
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("malformed " + type.value() + " payload", exception);
    }
  }

  private ObjectNode encodePayload(SessionEntryPayload payload) {
    ObjectNode node = NODES.objectNode();
    if (payload instanceof RootEntryPayload) {
      // 根 Entry 无字段。
    } else if (payload instanceof MessageEntryPayload value) {
      node.set("message", encodeMessage(value.message()));
      if (value.assistantMetadata() == null) {
        node.putNull("assistantMetadata");
      } else {
        node.set("assistantMetadata", encodeAssistantMetadata(value.assistantMetadata()));
      }
    } else if (payload instanceof AgentChangeEntryPayload value) {
      node.put("agentDefinitionId", value.agentDefinitionId());
      node.put("agentName", value.agentName());
    } else if (payload instanceof CompactionEntryPayload value) {
      node.put("summary", value.summary());
      node.put("firstKeptEntryId", value.firstKeptEntryId());
      node.put("tokensBefore", value.tokensBefore());
      node.put("detailsJson", value.detailsJson());
    } else if (payload instanceof BranchSummaryEntryPayload value) {
      node.put("summary", value.summary());
    } else if (payload instanceof CustomEntryPayload value) {
      node.put("name", value.name());
      node.put("dataJson", value.dataJson());
    } else if (payload instanceof CustomMessageEntryPayload value) {
      node.set("message", encodeMessage(value.message()));
    } else if (payload instanceof LabelEntryPayload value) {
      node.put("label", value.label());
    } else {
      throw new IllegalArgumentException("unknown session entry payload: " + payload.getClass());
    }
    return node;
  }

  private SessionEntryPayload decodePayload(SessionEntryType type, ObjectNode node) {
    return switch (type) {
      case ROOT -> {
        fields(node);
        yield new RootEntryPayload();
      }
      case MESSAGE -> {
        fields(node, "message", "assistantMetadata");
        JsonNode metadata = node.get("assistantMetadata");
        if (metadata == null) {
          throw new IllegalArgumentException("assistantMetadata must be present");
        }
        yield new MessageEntryPayload(
            decodeMessage(node.get("message")),
            metadata.isNull() ? null : decodeAssistantMetadata(metadata));
      }
      case AGENT_CHANGE -> {
        fields(node, "agentDefinitionId", "agentName");
        yield new AgentChangeEntryPayload(
            positiveLong(node, "agentDefinitionId"), text(node, "agentName"));
      }
      case COMPACTION -> {
        fields(node, "summary", "firstKeptEntryId", "tokensBefore", "detailsJson");
        yield new CompactionEntryPayload(
            text(node, "summary"),
            positiveLong(node, "firstKeptEntryId"),
            nonNegativeInt(node, "tokensBefore"),
            jsonObjectText(node, "detailsJson"));
      }
      case BRANCH_SUMMARY -> {
        fields(node, "summary");
        yield new BranchSummaryEntryPayload(text(node, "summary"));
      }
      case CUSTOM -> {
        fields(node, "name", "dataJson");
        yield new CustomEntryPayload(text(node, "name"), jsonObjectText(node, "dataJson"));
      }
      case CUSTOM_MESSAGE -> {
        fields(node, "message");
        yield new CustomMessageEntryPayload(decodeMessage(node.get("message")));
      }
      case LABEL -> {
        fields(node, "label");
        yield new LabelEntryPayload(text(node, "label"));
      }
    };
  }

  private ObjectNode encodeAssistantMetadata(AssistantMessageMetadata metadata) {
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

  private AssistantMessageMetadata decodeAssistantMetadata(JsonNode value) {
    ObjectNode node = object(value, "assistantMetadata");
    fields(node, "stopReason", "usage", "cost");
    ProviderStopReason stopReason;
    try {
      stopReason = ProviderStopReason.valueOf(text(node, "stopReason"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown provider stop reason", exception);
    }
    ObjectNode usage = object(node.get("usage"), "usage");
    fields(
        usage,
        "inputTokens",
        "outputTokens",
        "cacheReadTokens",
        "cacheWriteTokens",
        "cacheWriteLongTokens",
        "reasoningTokens",
        "providerTotalTokens");
    ModelUsage modelUsage =
        new ModelUsage(
            nonNegativeLong(usage, "inputTokens"),
            nonNegativeLong(usage, "outputTokens"),
            nonNegativeLong(usage, "cacheReadTokens"),
            nonNegativeLong(usage, "cacheWriteTokens"),
            nonNegativeLong(usage, "cacheWriteLongTokens"),
            nonNegativeLong(usage, "reasoningTokens"),
            nonNegativeLong(usage, "providerTotalTokens"));
    ObjectNode cost = object(node.get("cost"), "cost");
    fields(
        cost,
        "currency",
        "input",
        "output",
        "cacheRead",
        "cacheWrite",
        "cacheWriteLong",
        "reasoning",
        "total");
    ModelCost modelCost =
        new ModelCost(
            text(cost, "currency"),
            decimal(cost, "input"),
            decimal(cost, "output"),
            decimal(cost, "cacheRead"),
            decimal(cost, "cacheWrite"),
            decimal(cost, "cacheWriteLong"),
            decimal(cost, "reasoning"),
            decimal(cost, "total"));
    return new AssistantMessageMetadata(stopReason, modelUsage, modelCost);
  }

  private ObjectNode encodeMessage(AgentMessage message) {
    ObjectNode node = NODES.objectNode();
    node.put("role", message.role().name());
    ArrayNode contents = node.putArray("contents");
    message.contents().forEach(content -> contents.add(encodeContent(content)));
    return node;
  }

  private AgentMessage decodeMessage(JsonNode value) {
    ObjectNode node = object(value, "message");
    fields(node, "role", "contents");
    AgentMessageRole role;
    try {
      role = AgentMessageRole.valueOf(text(node, "role"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unknown agent message role", exception);
    }
    ArrayNode contents = array(node.get("contents"), "contents");
    List<AgentMessageContent> result = new ArrayList<>(contents.size());
    for (JsonNode content : contents) {
      result.add(decodeContent(content));
    }
    return new AgentMessage(role, result);
  }

  private ObjectNode encodeContent(AgentMessageContent content) {
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
      node.put("type", "json");
      node.put("json", value.json());
    } else if (content instanceof ToolCallMessageContent value) {
      node.put("type", "tool_call");
      node.put("toolCallId", value.toolCallId());
      node.put("toolName", value.toolName());
      node.put("argumentsJson", value.argumentsJson());
    } else if (content instanceof ToolResultMessageContent value) {
      node.put("type", "tool_result");
      node.put("toolCallId", value.toolCallId());
      node.put("toolName", value.toolName());
      ArrayNode contents = node.putArray("contents");
      value.contents().forEach(item -> contents.add(encodeContent(item)));
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
      throw new IllegalArgumentException("unknown agent message content: " + content.getClass());
    }
    return node;
  }

  private AgentMessageContent decodeContent(JsonNode value) {
    ObjectNode node = object(value, "content");
    String type = text(node, "type");
    return switch (type) {
      case "text" -> {
        fields(node, "type", "text");
        yield new TextMessageContent(text(node, "text"));
      }
      case "image" -> {
        fields(node, "type", "mediaType", "source");
        yield new ImageMessageContent(text(node, "mediaType"), text(node, "source"));
      }
      case "audio" -> {
        fields(node, "type", "mediaType", "source");
        yield new AudioMessageContent(text(node, "mediaType"), text(node, "source"));
      }
      case "thinking" -> {
        fields(node, "type", "text");
        yield new ThinkingMessageContent(text(node, "text"));
      }
      case "json" -> {
        fields(node, "type", "json");
        yield new JsonMessageContent(jsonText(node, "json"));
      }
      case "tool_call" -> {
        fields(node, "type", "toolCallId", "toolName", "argumentsJson");
        yield new ToolCallMessageContent(
            text(node, "toolCallId"),
            text(node, "toolName"),
            jsonObjectText(node, "argumentsJson"));
      }
      case "tool_result" -> {
        fields(node, "type", "toolCallId", "toolName", "contents", "error", "detailsJson");
        ArrayNode contents = array(node.get("contents"), "contents");
        List<AgentMessageContent> result = new ArrayList<>(contents.size());
        for (JsonNode item : contents) {
          AgentMessageContent content = decodeContent(item);
          if (content instanceof ToolCallMessageContent
              || content instanceof ToolResultMessageContent) {
            throw new IllegalArgumentException(
                "tool result contents cannot nest tool calls or results");
          }
          result.add(content);
        }
        yield new ToolResultMessageContent(
            text(node, "toolCallId"),
            text(node, "toolName"),
            result,
            bool(node, "error"),
            jsonObjectText(node, "detailsJson"));
      }
      case "artifact" -> {
        fields(node, "type", "artifactId", "mediaType", "preview");
        JsonNode preview = node.get("preview");
        if (!preview.isTextual() && !preview.isNull()) {
          throw new IllegalArgumentException("preview must be text or null");
        }
        yield new ArtifactMessageContent(
            text(node, "artifactId"),
            text(node, "mediaType"),
            preview.isNull() ? null : preview.textValue());
      }
      default -> throw new IllegalArgumentException("unknown agent message content type: " + type);
    };
  }

  private ArrayNode encodeStrings(List<String> values) {
    ArrayNode array = NODES.arrayNode();
    values.forEach(array::add);
    return array;
  }

  private List<String> strings(JsonNode value, String name) {
    ArrayNode array = array(value, name);
    List<String> values = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isTextual()) {
        throw new IllegalArgumentException(name + " must contain only strings");
      }
      values.add(item.textValue());
    }
    return values;
  }

  private String jsonText(ObjectNode node, String field) {
    String value = text(node, field);
    try {
      OBJECT_MAPPER.readTree(value);
      return value;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(field + " must contain JSON", exception);
    }
  }

  private String jsonObjectText(ObjectNode node, String field) {
    String value = jsonText(node, field);
    try {
      if (!OBJECT_MAPPER.readTree(value).isObject()) {
        throw new IllegalArgumentException(field + " must contain a JSON object");
      }
      return value;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(field + " must contain JSON", exception);
    }
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
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static boolean bool(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(field + " must be boolean");
    }
    return value.booleanValue();
  }

  private static int nonNegativeInt(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.canConvertToInt()
        || !value.isIntegralNumber()
        || value.intValue() < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    return value.intValue();
  }

  private static long nonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.canConvertToLong()
        || !value.isIntegralNumber()
        || value.longValue() < 0) {
      throw new IllegalArgumentException(field + " must be a non-negative integer");
    }
    return value.longValue();
  }

  private static long positiveLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.canConvertToLong()
        || !value.isIntegralNumber()
        || value.longValue() <= 0) {
      throw new IllegalArgumentException(field + " must be a positive integer");
    }
    return value.longValue();
  }

  private static BigDecimal decimal(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    try {
      return new BigDecimal(value.textValue());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(field + " must be a decimal", exception);
    }
  }

  private static void fields(ObjectNode node, String... expected) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    Set<String> expectedNames = Set.of(expected);
    if (!names.equals(expectedNames)) {
      throw new IllegalArgumentException("unexpected payload fields: " + names);
    }
  }
}
