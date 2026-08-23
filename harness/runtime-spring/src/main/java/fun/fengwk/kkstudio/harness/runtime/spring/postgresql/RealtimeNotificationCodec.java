package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL realtime NOTIFY envelope 的严格 deterministic JSON codec。 */
public final class RealtimeNotificationCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> EVENT_FIELDS = orderedSet("kind", "event");
  private static final Set<String> RESYNC_FIELDS = orderedSet("kind", "threadId", "reason");

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private final RealtimeEventJsonCodec eventCodec;

  public RealtimeNotificationCodec() {
    this(new RealtimeEventJsonCodec());
  }

  public RealtimeNotificationCodec(RealtimeEventJsonCodec eventCodec) {
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  /** 编码 canonical EVENT envelope。 */
  public String encodeEvent(RealtimeEvent event) {
    Objects.requireNonNull(event, "event");
    ObjectNode node = NODES.objectNode();
    node.put("kind", Kind.EVENT.name());
    node.set("event", eventCodec.encodeNode(event));
    return write(node);
  }

  /** 编码 canonical RESYNC envelope。 */
  public String encodeResync(UUID threadId, String reason) {
    Envelope.Resync resync = new Envelope.Resync(threadId, reason);
    ObjectNode node = NODES.objectNode();
    node.put("kind", Kind.RESYNC.name());
    node.put("threadId", resync.threadId().toString());
    node.put("reason", resync.reason());
    return write(node);
  }

  /** 解码且验证 canonical envelope。任何 malformed、unknown、字段集合不精确或非 canonical payload 都被拒绝。 */
  public Envelope decode(String payload) {
    Objects.requireNonNull(payload, "payload");
    JsonNode root;
    try {
      root = MAPPER.readTree(payload);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed realtime notification JSON", error);
    }
    ObjectNode node = requireObject(root, "notification");
    Kind kind = readKind(requiredText(node, "kind", "notification"));
    Envelope envelope;
    if (kind == Kind.EVENT) {
      requireExactFields(node, EVENT_FIELDS, "notification");
      JsonNode eventNode = node.get("event");
      if (eventNode == null || !eventNode.isObject()) {
        throw new IllegalArgumentException("notification.event must be a JSON object");
      }
      envelope = new Envelope.Event(eventCodec.decodeNode(eventNode));
    } else {
      requireExactFields(node, RESYNC_FIELDS, "notification");
      envelope =
          new Envelope.Resync(
              requiredCanonicalUuid(node, "threadId", "notification"),
              requiredText(node, "reason", "notification"));
    }
    if (!encode(envelope).equals(payload)) {
      throw new IllegalArgumentException("realtime notification must use canonical JSON");
    }
    return envelope;
  }

  private String encode(Envelope envelope) {
    if (envelope instanceof Envelope.Event event) {
      return encodeEvent(event.event());
    }
    if (envelope instanceof Envelope.Resync resync) {
      return encodeResync(resync.threadId(), resync.reason());
    }
    throw new IllegalArgumentException(
        "unsupported realtime notification: " + envelope.getClass().getName());
  }

  private static ObjectNode requireObject(JsonNode value, String path) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(path + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  private static String requiredText(ObjectNode node, String field, String path) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(path + "." + field + " must be a string");
    }
    return value.textValue();
  }

  private static UUID requiredCanonicalUuid(ObjectNode node, String field, String path) {
    String text = requiredText(node, field, path);
    UUID value;
    try {
      value = UUID.fromString(text);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(path + "." + field + " must be a UUID string", error);
    }
    if (!value.toString().equals(text)) {
      throw new IllegalArgumentException(path + "." + field + " must use canonical UUID text");
    }
    return value;
  }

  private static Kind readKind(String value) {
    try {
      return Kind.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unknown notification.kind: " + value, error);
    }
  }

  private static void requireExactFields(ObjectNode node, Set<String> expected, String objectName) {
    Set<String> actual = new LinkedHashSet<>();
    Iterator<String> names = node.fieldNames();
    names.forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw new IllegalArgumentException(
          objectName + " fields must be exactly " + expected + ", actual=" + actual);
    }
  }

  private static Set<String> orderedSet(String... values) {
    return Set.of(values);
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode realtime notification JSON", error);
    }
  }

  private enum Kind {
    EVENT,
    RESYNC
  }

  /** 严格 envelope 只允许 EVENT 与 RESYNC。 */
  public sealed interface Envelope permits Envelope.Event, Envelope.Resync {

    /** 包含完整 canonical {@link RealtimeEvent} 的 live event。 */
    record Event(RealtimeEvent event) implements Envelope {
      public Event {
        Objects.requireNonNull(event, "event");
      }
    }

    /** 指示指定 Thread 必须从 durable snapshot 恢复。 */
    record Resync(UUID threadId, String reason) implements Envelope {
      public Resync {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
          throw new IllegalArgumentException("reason must not be blank");
        }
      }
    }
  }
}
