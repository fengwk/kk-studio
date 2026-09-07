package fun.fengwk.kkstudio.harness.runtime.model.provider.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallDiagnostic;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ProviderToolCallDiagnostic} 的严格、确定性 JSON codec。
 *
 * <p>用于物化为 Assistant 历史中的 {@code JsonMessageContent} warning 节点。
 */
public final class ProviderToolCallDiagnosticJsonCodec {

  public static final String TYPE_TAG = "tool_call_diagnostic";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    OBJECT_MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    OBJECT_MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private static final Set<String> FIELDS =
      orderedSet("type", "callIndex", "id", "name", "partialArguments", "message");

  public String encode(ProviderToolCallDiagnostic diagnostic) {
    Objects.requireNonNull(diagnostic, "diagnostic");
    return write(encodeNode(diagnostic));
  }

  public ObjectNode encodeNode(ProviderToolCallDiagnostic diagnostic) {
    Objects.requireNonNull(diagnostic, "diagnostic");
    ObjectNode node = NODES.objectNode();
    node.put("type", TYPE_TAG);
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

  public ProviderToolCallDiagnostic decode(String json) {
    Objects.requireNonNull(json, "json");
    return decodeNode(parse(json));
  }

  public ProviderToolCallDiagnostic decodeNode(JsonNode node) {
    ObjectNode root = object(node, "toolCallDiagnostic");
    requireFields(root, FIELDS, "toolCallDiagnostic");

    String type = text(root, "type");
    if (!TYPE_TAG.equals(type)) {
      throw new IllegalArgumentException(
          "expected diagnostic type " + TYPE_TAG + ", but got " + type);
    }
    long callIndex = nonNegativeLong(root, "callIndex");
    if (callIndex > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("callIndex overflow: " + callIndex);
    }
    String id = decodeNullableText(root, "id");
    String name = decodeNullableText(root, "name");
    String partialArguments = text(root, "partialArguments");
    String message = text(root, "message");

    return new ProviderToolCallDiagnostic((int) callIndex, id, name, partialArguments, message);
  }

  private static JsonNode parse(String json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null) {
        throw new IllegalArgumentException("empty JSON document");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed tool call diagnostic JSON", error);
    }
  }

  private static String write(JsonNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode tool call diagnostic JSON", error);
    }
  }

  private static ObjectNode object(JsonNode value, String context) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  private static String text(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return value.textValue();
  }

  private static String decodeNullableText(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be text or null");
    }
    return value.textValue();
  }

  private static long nonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.canConvertToLong()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    long converted = value.asLong();
    if (converted < 0L) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return converted;
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String context) {
    if (node.size() != expected.size()) {
      throw new IllegalArgumentException(
          context
              + " field count mismatch: expected "
              + expected
              + ", but got "
              + fieldNames(node));
    }
    for (String name : expected) {
      if (!node.has(name)) {
        throw new IllegalArgumentException(context + " missing required field: " + name);
      }
    }
  }

  private static Set<String> fieldNames(ObjectNode node) {
    Set<String> fields = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  private static Set<String> orderedSet(String... values) {
    Set<String> set = new LinkedHashSet<>();
    Collections.addAll(set, values);
    return Collections.unmodifiableSet(set);
  }
}
