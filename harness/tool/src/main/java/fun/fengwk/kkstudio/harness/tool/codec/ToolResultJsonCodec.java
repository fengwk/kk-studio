package fun.fengwk.kkstudio.harness.tool.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict persistence codec for final and partial Tool results. */
public final class ToolResultJsonCodec {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private ToolResultJsonCodec() {}

  /** Canonical object tree used by durable ToolInvocation result persistence. */
  public static JsonNode encodeNode(ToolResult result) {
    Objects.requireNonNull(result, "result");
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("toolCallId", result.toolCallId());
    ArrayNode contents = node.putArray("contents");
    result.contents().forEach(content -> contents.add(encodeContent(content)));
    node.put("error", result.error());
    node.set("details", readObject(result.detailsJson(), "detailsJson"));
    return node;
  }

  public static String encode(ToolResult result) {
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(result));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("cannot encode tool result", exception);
    }
  }

  public static ToolResult decode(String resultJson) {
    try {
      JsonNode parsed = OBJECT_MAPPER.readTree(resultJson);
      if (!(parsed instanceof ObjectNode node)) {
        throw new IllegalArgumentException("tool result must be an object");
      }
      requireFields(node, "toolCallId", "contents", "error", "details");
      ArrayNode array = requireArray(node.get("contents"), "contents");
      List<ToolContent> contents = new ArrayList<>(array.size());
      for (JsonNode content : array) {
        contents.add(decodeContent(content));
      }
      return new ToolResult(
          text(node, "toolCallId"),
          contents,
          bool(node, "error"),
          OBJECT_MAPPER.writeValueAsString(requireObject(node.get("details"), "details")),
          false);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("malformed tool result", exception);
    }
  }

  private static ObjectNode encodeContent(ToolContent content) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    if (content instanceof TextToolContent value) {
      node.put("type", "text");
      node.put("text", value.text());
    } else if (content instanceof JsonToolContent value) {
      node.put("type", "json");
      node.set("json", readObjectOrValue(value.json(), "json"));
    } else if (content instanceof ArtifactToolContent value) {
      node.put("type", "artifact");
      node.put("artifactId", value.artifact().artifactId());
      node.put("mediaType", value.artifact().mediaType());
      node.put("sizeBytes", value.artifact().sizeBytes());
    } else {
      throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
    return node;
  }

  private static ToolContent decodeContent(JsonNode value) {
    ObjectNode node = requireObject(value, "content");
    String type = text(node, "type");
    return switch (type) {
      case "text" -> {
        requireFields(node, "type", "text");
        yield new TextToolContent(textAllowEmpty(node, "text"));
      }
      case "json" -> {
        requireFields(node, "type", "json");
        try {
          yield new JsonToolContent(OBJECT_MAPPER.writeValueAsString(node.get("json")));
        } catch (JsonProcessingException exception) {
          throw new IllegalArgumentException("cannot decode JSON content", exception);
        }
      }
      case "artifact" -> {
        requireFields(node, "type", "artifactId", "mediaType", "sizeBytes");
        JsonNode size = node.get("sizeBytes");
        if (!size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() < 0) {
          throw new IllegalArgumentException("sizeBytes must be non-negative integer");
        }
        yield new ArtifactToolContent(
            new ArtifactRef(text(node, "artifactId"), text(node, "mediaType"), size.longValue()));
      }
      default -> throw new IllegalArgumentException("unknown tool content type: " + type);
    };
  }

  private static JsonNode readObject(String json, String name) {
    JsonNode node = readObjectOrValue(json, name);
    if (!node.isObject()) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return node;
  }

  private static JsonNode readObjectOrValue(String json, String name) {
    try {
      return OBJECT_MAPPER.readTree(json);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(name + " is invalid JSON", exception);
    }
  }

  private static ObjectNode requireObject(JsonNode value, String name) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(name + " must be an object");
    }
    return object;
  }

  private static ArrayNode requireArray(JsonNode value, String name) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(name + " must be an array");
    }
    return array;
  }

  private static String text(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(name + " must be non-blank text");
    }
    return value.textValue();
  }

  private static String textAllowEmpty(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(name + " must be text");
    }
    return value.textValue();
  }

  private static boolean bool(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException(name + " must be boolean");
    }
    return value.booleanValue();
  }

  private static void requireFields(ObjectNode node, String... names) {
    if (node.size() != names.length) {
      throw new IllegalArgumentException("unexpected tool result fields");
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("missing tool result field: " + name);
      }
    }
  }
}
