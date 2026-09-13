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
 * 事件通道帧的严格 JSON codec：所有帧（客户端与服务端）都带 {@code version:1}；客户端帧手工字段校验（duplicate/trailing/unknown/
 * missing/wrong-type 全部拒绝），服务端帧确定性编码。
 *
 * <p>客户端帧 {@code {version:1, type:'subscribe'|'unsubscribe', resource}} 字段集精确；Thread/Canvas
 * resource 带 canonical UUID id，Projects/Cloud Files 是无 id 的全局 resource。服务端帧 {@code
 * subscribed{resource,cursor}} / {@code event{resource,name,data}} / {@code resync{resource}} /
 * {@code heartbeat} / {@code error{code,message[,resource]}}。游标是 canonical 非负十进制字符串（{@code
 * 0|[1-9][0-9]*}，不超 bigint）。
 */
final class EventFrameCodec {

  static final String INVALID_FRAME = "INVALID_FRAME";
  static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  static final String BACKPRESSURE = "BACKPRESSURE";
  static final String SEND_FAILED = "SEND_FAILED";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> CLIENT_FRAME_FIELDS = orderedSet("version", "type", "resource");
  private static final Set<String> RESOURCE_FIELDS = orderedSet("kind", "id");
  private static final Set<String> GLOBAL_RESOURCE_FIELDS = orderedSet("kind");

  private static final String THREAD = ResourceKind.THREAD.name().toLowerCase();
  private static final String CANVAS = ResourceKind.CANVAS.name().toLowerCase();
  private static final String PROJECTS = ResourceKind.PROJECTS.name().toLowerCase();
  private static final String CLOUD_FILES = "cloud-files";

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
      throw new IllegalArgumentException("malformed client frame JSON");
    }
    ObjectNode node = requireObject(root, "frame");
    requireExactFields(node, CLIENT_FRAME_FIELDS, "frame");
    requireVersionOne(node, "frame");
    String typeName = requiredText(node, "type", "frame");
    ClientFrame.Type type;
    if ("subscribe".equals(typeName)) {
      type = ClientFrame.Type.SUBSCRIBE;
    } else if ("unsubscribe".equals(typeName)) {
      type = ClientFrame.Type.UNSUBSCRIBE;
    } else {
      // 只接受精确小写；toUpperCase 归一化会放行 SUBSCRIBE/Subscribe 等非 canonical 值。
      throw new IllegalArgumentException("frame.type must be subscribe or unsubscribe");
    }
    return new ClientFrame(type, parseResource(node.get("resource")));
  }

  /** 编码 {@code subscribed} ack 帧（{@code cursor} 为 canonical 非负十进制）。 */
  String subscribed(ResourceKey resource, long cursor) {
    if (cursor < 0) {
      throw new IllegalArgumentException("cursor must be non-negative");
    }
    if ((resource.kind() == ResourceKind.PROJECTS || resource.kind() == ResourceKind.CLOUD_FILES)
        && cursor != 0) {
      throw new IllegalArgumentException("global resource cursor must be zero");
    }
    ObjectNode node = NODES.objectNode();
    node.put("version", 1);
    node.put("type", "subscribed");
    node.set("resource", resourceNode(resource));
    node.put("cursor", Long.toString(cursor));
    return write(node);
  }

  /**
   * 编码 {@code event} 帧：统一 {@code {version,type,resource,name,data}}；version 事件额外携带 canonical {@code
   * cursor}（本次前进到的值），realtime 事件的 {@code data} 为 realtime codec JSON 对象。
   */
  String event(ResourceKey resource, Signal signal) {
    Objects.requireNonNull(signal, "signal");
    ObjectNode node = NODES.objectNode();
    node.put("version", 1);
    node.put("type", "event");
    node.set("resource", resourceNode(resource));
    if (signal instanceof Signal.Version version) {
      if (resource.kind() != ResourceKind.THREAD && resource.kind() != ResourceKind.CANVAS) {
        throw new IllegalArgumentException("version signal requires a versioned resource");
      }
      node.put("name", "version");
      node.put("cursor", version.version());
      node.set("data", dataNode("version", version.version()));
    } else if (signal instanceof Signal.Realtime realtime) {
      if (resource.kind() != ResourceKind.THREAD) {
        throw new IllegalArgumentException("realtime signal requires a thread resource");
      }
      node.put("name", "realtime");
      node.set("data", realtimeCodec.encodeNode(realtime.event()));
    } else if (signal instanceof Signal.ProjectChanged projectChanged) {
      if (resource.kind() != ResourceKind.PROJECTS) {
        throw new IllegalArgumentException("project change signal requires the projects resource");
      }
      node.put("name", "changed");
      ObjectNode data = NODES.objectNode();
      data.put("projectId", projectChanged.projectId().toString());
      node.set("data", data);
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
    node.put("version", 1);
    node.put("type", "resync");
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  /** 编码连接级 {@code heartbeat} 帧；不绑定资源、不携带游标。 */
  String heartbeat() {
    ObjectNode node = NODES.objectNode();
    node.put("version", 1);
    node.put("type", "heartbeat");
    return write(node);
  }

  /** 编码 {@code error} 帧（连接级，不含 resource）。 */
  String error(String code, String message) {
    ObjectNode node = NODES.objectNode();
    node.put("version", 1);
    node.put("type", "error");
    node.put("code", code);
    node.put("message", message);
    return write(node);
  }

  /** 编码 {@code error} 帧（资源级，附带 resource 供客户端定位）。 */
  String error(String code, String message, ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    node.put("version", 1);
    node.put("type", "error");
    node.put("code", code);
    node.put("message", message);
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  private static ObjectNode dataNode(String field, String canonicalValue) {
    ObjectNode node = NODES.objectNode();
    node.put(field, canonicalValue);
    return node;
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
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
    return parsed;
  }

  private static void requireVersionOne(ObjectNode node, String context) {
    JsonNode version = node.get("version");
    // 数值必须精确等于 integer 1；asLong() 会接受溢出整数的截断结果（如 2^64），isInt() 限定 int 范围。
    if (version == null || !version.isInt() || version.asInt() != 1) {
      throw new IllegalArgumentException(context + ".version must be the integer 1");
    }
  }

  private static ResourceKey parseResource(JsonNode value) {
    ObjectNode node = requireObject(value, "frame.resource");
    String kindName = requiredText(node, "kind", "frame.resource");
    if (THREAD.equals(kindName)) {
      requireExactFields(node, RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(
          ResourceKind.THREAD,
          parseUuid(requiredText(node, "id", "frame.resource"), "frame.resource.id"));
    }
    if (CANVAS.equals(kindName)) {
      requireExactFields(node, RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(
          ResourceKind.CANVAS,
          parseUuid(requiredText(node, "id", "frame.resource"), "frame.resource.id"));
    }
    if (PROJECTS.equals(kindName)) {
      requireExactFields(node, GLOBAL_RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(ResourceKind.PROJECTS, null);
    }
    if (CLOUD_FILES.equals(kindName)) {
      requireExactFields(node, GLOBAL_RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(ResourceKind.CLOUD_FILES, null);
    }
    throw new IllegalArgumentException(
        "frame.resource.kind must be thread, canvas, projects, or cloud-files");
  }

  private static ObjectNode resourceNode(ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    switch (resource.kind()) {
      case THREAD -> node.put("kind", THREAD);
      case CANVAS -> node.put("kind", CANVAS);
      case PROJECTS -> node.put("kind", PROJECTS);
      case CLOUD_FILES -> node.put("kind", CLOUD_FILES);
    }
    if (resource.id() != null) {
      node.put("id", resource.id().toString());
    }
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
                throw new IllegalArgumentException(context + " has an unknown field");
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
