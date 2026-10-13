package fun.fengwk.kkstudio.harness.runtime.tool;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** {@link ToolInvocationError} 的严格 canonical JSON codec。 */
public final class ToolInvocationErrorJsonCodec {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final Set<String> FIELDS = Set.of("kind", "message");

  public String encode(ToolInvocationError error) {
    Objects.requireNonNull(error, "error");
    try {
      return OBJECT_MAPPER.writeValueAsString(encodeNode(error));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("cannot encode tool invocation error", exception);
    }
  }

  public JsonNode encodeNode(ToolInvocationError error) {
    Objects.requireNonNull(error, "error");
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", error.kind());
    node.put("message", error.message());
    return node;
  }

  public ToolInvocationError decode(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return decodeNode(OBJECT_MAPPER.readTree(json));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("malformed tool invocation error", exception);
    }
  }

  public ToolInvocationError decodeNode(JsonNode value) {
    if (!(Objects.requireNonNull(value, "value") instanceof ObjectNode node)) {
      throw new IllegalArgumentException("tool invocation error must be an object");
    }
    Set<String> fields = new HashSet<>();
    node.propertyNames().forEach(fields::add);
    if (!fields.equals(FIELDS)) {
      throw new IllegalArgumentException("unexpected tool invocation error fields: " + fields);
    }
    return new ToolInvocationError(text(node, "kind"), text(node, "message"));
  }

  private static String text(ObjectNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(name + " must be text");
    }
    return value.stringValue();
  }
}
