package fun.fengwk.kkstudio.harness.infra.postgresql;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL realtime NOTIFY envelope 的严格 deterministic JSON codec。 */
public final class RealtimeNotificationCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          // 自由 JSON number 以 BigDecimal 读回，避免 1e309 -> "Infinity"、1e-324 -> 0.0 的精度/类型失真。
          .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final Set<String> EVENT_FIELDS = orderedSet("kind", "originNodeId", "event");
  private static final Set<String> RESYNC_FIELDS =
      orderedSet("kind", "originNodeId", "threadId", "reason");

  private final RealtimeEventJsonCodec eventCodec;

  public RealtimeNotificationCodec() {
    this(new RealtimeEventJsonCodec());
  }

  public RealtimeNotificationCodec(RealtimeEventJsonCodec eventCodec) {
    this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
  }

  /** 编码 canonical EVENT envelope，并标记产生该事件的节点。 */
  public String encodeEvent(UUID originNodeId, RealtimeEvent event) {
    Envelope.Event envelope = new Envelope.Event(originNodeId, event);
    ObjectNode node = NODES.objectNode();
    node.put("kind", Kind.EVENT.name());
    node.put("originNodeId", envelope.originNodeId().toString());
    node.set("event", eventCodec.encodeNode(envelope.event()));
    return write(node);
  }

  /** 编码 canonical RESYNC envelope，并标记判断该 Thread 需要恢复的节点。 */
  public String encodeResync(UUID originNodeId, UUID threadId, String reason) {
    Envelope.Resync resync = new Envelope.Resync(originNodeId, threadId, reason);
    ObjectNode node = NODES.objectNode();
    node.put("kind", Kind.RESYNC.name());
    node.put("originNodeId", resync.originNodeId().toString());
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
    } catch (JacksonException error) {
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
      envelope =
          new Envelope.Event(
              requiredCanonicalUuid(node, "originNodeId", "notification"),
              eventCodec.decodeNode(eventNode));
    } else {
      requireExactFields(node, RESYNC_FIELDS, "notification");
      envelope =
          new Envelope.Resync(
              requiredCanonicalUuid(node, "originNodeId", "notification"),
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
      return encodeEvent(event.originNodeId(), event.event());
    }
    if (envelope instanceof Envelope.Resync resync) {
      return encodeResync(resync.originNodeId(), resync.threadId(), resync.reason());
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
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(path + "." + field + " must be a string");
    }
    return value.stringValue();
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
    Iterator<String> names = node.propertyNames().iterator();
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
    } catch (JacksonException error) {
      throw new IllegalArgumentException("cannot encode realtime notification JSON", error);
    }
  }

  private enum Kind {
    EVENT,
    RESYNC
  }

  /** 严格 envelope 只允许 EVENT 与 RESYNC，且每条消息都必须携带产生它的节点身份。 */
  public sealed interface Envelope permits Envelope.Event, Envelope.Resync {

    /** 产生该 envelope 的节点；接收方据此丢弃自身回环消息。 */
    UUID originNodeId();

    /** 包含完整 canonical {@link RealtimeEvent} 的 live event。 */
    record Event(UUID originNodeId, RealtimeEvent event) implements Envelope {
      public Event {
        Objects.requireNonNull(originNodeId, "originNodeId");
        Objects.requireNonNull(event, "event");
      }
    }

    /** 指示指定 Thread 必须从 durable snapshot 恢复。 */
    record Resync(UUID originNodeId, UUID threadId, String reason) implements Envelope {
      public Resync {
        Objects.requireNonNull(originNodeId, "originNodeId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
          throw new IllegalArgumentException("reason must not be blank");
        }
      }
    }
  }
}
