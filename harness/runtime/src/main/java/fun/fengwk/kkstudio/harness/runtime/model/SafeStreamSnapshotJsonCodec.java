package fun.fengwk.kkstudio.harness.runtime.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Strict canonical JSON codec for {@link SafeStreamSnapshot}.
 *
 * <p>字段集合固定为 {@code {text, thinking, sequence}}；任意多余字段、缺失字段、显式 null（除 empty string 允许）一律抛 {@link
 * IllegalArgumentException}；trailing token 与 duplicate field 启用 mapper 严格模式拒绝。空字符串是合法内容 （用于纯
 * thinking 或纯 text 流）。
 */
public final class SafeStreamSnapshotJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> SNAPSHOT_FIELDS;

  static {
    Set<String> fields = new LinkedHashSet<>();
    fields.add("text");
    fields.add("thinking");
    fields.add("sequence");
    SNAPSHOT_FIELDS = Set.copyOf(fields);
  }

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public SafeStreamSnapshotJsonCodec() {}

  public String encode(SafeStreamSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    return write(encodeNode(snapshot));
  }

  public ObjectNode encodeNode(SafeStreamSnapshot snapshot) {
    if (snapshot == null) {
      throw new IllegalArgumentException("snapshot must not be null");
    }
    ObjectNode node = NODES.objectNode();
    node.put("text", snapshot.text());
    node.put("thinking", snapshot.thinking());
    node.put("sequence", snapshot.sequence());
    return node;
  }

  public SafeStreamSnapshot decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed safe stream snapshot JSON", error);
    }
    return decodeNode(root);
  }

  public SafeStreamSnapshot decodeNode(JsonNode value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    if (!(value instanceof ObjectNode node)) {
      throw new IllegalArgumentException("safe stream snapshot must be a JSON object");
    }
    requireExactFields(node, SNAPSHOT_FIELDS, "safe stream snapshot");
    String text = requiredTextAllowEmpty(node, "text", "safe stream snapshot");
    String thinking = requiredTextAllowEmpty(node, "thinking", "safe stream snapshot");
    return new SafeStreamSnapshot(text, thinking, requiredNonNegativeLong(node, "sequence"));
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String context) {
    Set<String> actual = new LinkedHashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          context + " must contain exactly " + expected + " but was " + actual);
    }
  }

  private static String requiredTextAllowEmpty(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be a string");
    }
    return value.textValue();
  }

  private static long requiredNonNegativeLong(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new IllegalArgumentException(
          "safe stream snapshot." + field + " must be a non-negative long");
    }
    return value.longValue();
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode safe stream snapshot JSON", error);
    }
  }
}
