package fun.fengwk.kkstudio.harness.runtime.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;

/** append-only Run Journal 中的一条不可变事件。 */
public record RunEvent(
    long id, long runId, long sequence, RunEventType type, String payloadJson, Instant createdAt) {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public RunEvent {
    if (id <= 0 || runId <= 0 || sequence <= 0) {
      throw new IllegalArgumentException("event ids and sequence must be positive");
    }
    type = Objects.requireNonNull(type, "type");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    validatePayload(payloadJson);
  }

  private static void validatePayload(String payloadJson) {
    try {
      JsonNode payload = OBJECT_MAPPER.readTree(payloadJson);
      if (payload == null
          || !payload.isObject()
          || !payload.has("schemaVersion")
          || !payload.get("schemaVersion").canConvertToInt()
          || payload.get("schemaVersion").intValue() <= 0) {
        throw new IllegalArgumentException("run event payload must contain positive schemaVersion");
      }
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed run event payload", error);
    }
  }
}
