package fun.fengwk.kkstudio.harness.runtime.thread;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

/** ThreadEvent payload JSON 编码器。 */
public final class ThreadEventPayloads {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ThreadEventPayloads() {}

  public static String of(Object... fields) {
    if (fields.length % 2 != 0) {
      throw new IllegalArgumentException("event fields must be key/value pairs");
    }
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    for (int i = 0; i < fields.length; i += 2) {
      String name = (String) fields[i];
      Object value = fields[i + 1];
      if (value == null) {
        node.putNull(name);
      } else if (value instanceof String string) {
        node.put(name, string);
      } else if (value instanceof Integer integer) {
        node.put(name, integer);
      } else if (value instanceof Long longValue) {
        // Bigint identifiers in ThreadEvent payloads are decimal strings.
        node.put(name, Long.toString(longValue));
      } else if (value instanceof Boolean bool) {
        node.put(name, bool);
      } else if (value instanceof Instant instant) {
        node.put(name, instant.toString());
      } else if (value instanceof Map<?, ?> map) {
        node.set(name, OBJECT_MAPPER.valueToTree(map));
      } else {
        node.set(name, OBJECT_MAPPER.valueToTree(value));
      }
    }
    node.put("schemaVersion", 1);
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode thread event payload", error);
    }
  }
}
