package fun.fengwk.kkstudio.web.events;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 事件通道帧的严格 JSON codec：客户端帧手工字段校验（duplicate/trailing/unknown/missing/wrong-type 全部拒绝）， 服务端帧确定性编码。
 *
 * <p>资源 id 必须是 canonical UUID（{@code UUID.fromString} 往返一致）；游标是 canonical 非负十进制字符串 （{@code
 * 0|[1-9][0-9]*}，不超 bigint）。
 */
final class EventFrameCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> CLIENT_FRAME_FIELDS = orderedSet("version", "type", "resource");
  private static final Set<String> RESOURCE_FIELDS = orderedSet("kind", "id");

  private static final String THREAD = ResourceKind.THREAD.name().toLowerCase();
  private static final String CANVAS = ResourceKind.CANVAS.name().toLowerCase();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private final RealtimeEventJsonCodec realtimeCodec;

  EventFrameCodec(RealtimeEventJsonCodec realtimeCodec) {
    this.realtimeCodec = Objects.requireNonNull(realtimeCodec, "realtimeCodec");
  }

  /** 客户端帧。 */
  record ClientFrame(Type type, ResourceKey resource) {
    enum Type {
      SUBSCRIBE,
      UNSUBSCRIBE
    }
  }

  /** 解析客户端帧；非法 JSON 或字段抛 {@link IllegalArgumentException}。 */
  ClientFrame decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed client frame JSON", error);
    }
    ObjectNode node = requireObject(root, "frame");
    requireExactFields(node, CLIENT_FRAME_FIELDS, "frame");
    JsonNode version = node.get("version");
    if (version == null || !version.isIntegralNumber() || version.asLong() != 1L) {
      throw new IllegalArgumentException("frame.version must be the integer 1");
    }
    String typeName = requiredText(node, "type", "frame");
    ClientFrame.Type type;
    try {
      type = ClientFrame.Type.valueOf(typeName.toUpperCase());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "frame.type must be subscribe or unsubscribe: " + typeName);
    }
    return new ClientFrame(type, parseResource(node.get("resource")));
  }

  /** 编码 {@code subscribed} ack 帧。 */
  String subscribed(ResourceKey resource, long cursor) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "subscribed");
    node.set("resource", resourceNode(resource));
    node.put("cursor", Long.toString(cursor));
    return write(node);
  }

  /** 编码 {@code event} 帧（{@code name} 为 revision/realtime/version）。 */
  String event(ResourceKey resource, Signal signal) {
    Objects.requireNonNull(signal, "signal");
    ObjectNode node = NODES.objectNode();
    node.put("type", "event");
    node.set("resource", resourceNode(resource));
    if (signal instanceof Signal.Revision revision) {
      node.put("name", "revision");
      node.put("revision", revision.revision());
    } else if (signal instanceof Signal.Realtime realtime) {
      node.put("name", "realtime");
      node.set("payload", realtimeCodec.encodeNode(realtime.event()));
    } else if (signal instanceof Signal.Version version) {
      node.put("name", "version");
      node.put("version", Long.toString(version.version()));
    } else if (signal instanceof Signal.Resync) {
      throw new IllegalArgumentException("resync signal is not an event frame");
    } else {
      throw new IllegalArgumentException("unsupported signal: " + signal.getClass().getName());
    }
    return write(node);
  }

  /** 编码 {@code resync} 帧。 */
  String resync(ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "resync");
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  /** 编码 {@code error} 帧。 */
  String error(String message) {
    ObjectNode node = NODES.objectNode();
    node.put("type", "error");
    node.put("message", message);
    return write(node);
  }

  /** 解析 canonical UUID（{@code UUID.fromString} 往返一致）。 */
  static UUID parseUuid(String value, String field) {
    Objects.requireNonNull(field, "field");
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value, error);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID: " + value);
    }
    return parsed;
  }

  private static ResourceKey parseResource(JsonNode value) {
    ObjectNode node = requireObject(value, "frame.resource");
    requireExactFields(node, RESOURCE_FIELDS, "frame.resource");
    String kindName = requiredText(node, "kind", "frame.resource");
    ResourceKind kind;
    if (THREAD.equals(kindName)) {
      kind = ResourceKind.THREAD;
    } else if (CANVAS.equals(kindName)) {
      kind = ResourceKind.CANVAS;
    } else {
      throw new IllegalArgumentException(
          "frame.resource.kind must be thread or canvas: " + kindName);
    }
    return new ResourceKey(
        kind, parseUuid(requiredText(node, "id", "frame.resource"), "frame.resource.id"));
  }

  private static ObjectNode resourceNode(ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    node.put("kind", resource.kind() == ResourceKind.THREAD ? THREAD : CANVAS);
    node.put("id", resource.id().toString());
    return node;
  }

  private static ObjectNode requireObject(JsonNode value, String field) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(context + "." + field + " must be a string");
    }
    return value.textValue();
  }

  private static void requireExactFields(ObjectNode node, Set<String> fields, String context) {
    if (node.size() != fields.size()) {
      throw new IllegalArgumentException(context + " must contain exactly " + fields + " fields");
    }
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!fields.contains(name)) {
                throw new IllegalArgumentException(context + " has unknown field: " + name);
              }
            });
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode event frame", error);
    }
  }

  /** 保持声明顺序的错误消息与校验语义。 */
  private static Set<String> orderedSet(String... values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
