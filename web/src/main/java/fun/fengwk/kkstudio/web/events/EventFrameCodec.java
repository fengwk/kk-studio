package fun.fengwk.kkstudio.web.events;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUse;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseView;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 事件通道 v2 帧的严格 JSON codec：所有帧（客户端与服务端）都带 {@code version:2}；客户端帧手工字段校验（duplicate/trailing/
 * unknown/missing/wrong-type 全部拒绝），服务端帧确定性编码。
 *
 * <p>客户端帧 {@code {version:2,type:'subscribe'|'unsubscribe',resource}} 与 {@code
 * {version:2,type:'ide-use',documentId,revision,views}} 字段集精确。服务端帧 {@code connected} / {@code
 * subscribed{resource,cursor}} / {@code event{resource,name,data}} / {@code resync{resource}} /
 * {@code heartbeat} / {@code ide-use-accepted{revision}} / {@code
 * error{code,message[,resource]}}。游标是 canonical 非负十进制字符串（{@code 0|[1-9][0-9]*}，不超 bigint）；revision
 * 与 bindingEpoch 是 canonical 正整数且不超过 {@link Long#MAX_VALUE}。
 */
final class EventFrameCodec {

  static final String INVALID_FRAME = "INVALID_FRAME";
  static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  static final String BACKPRESSURE = "BACKPRESSURE";
  static final String SEND_FAILED = "SEND_FAILED";
  static final String IDE_USE_REJECTED = "IDE_USE_REJECTED";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> SUBSCRIPTION_FIELDS = orderedSet("version", "type", "resource");
  private static final Set<String> IDE_USE_FIELDS =
      orderedSet("version", "type", "documentId", "revision", "views");
  private static final Set<String> IDE_USE_VIEW_FIELDS =
      orderedSet("environmentId", "viewId", "claimId", "bindingEpoch");
  private static final Set<String> IDE_USE_VIEW_REQUIRED_FIELDS =
      orderedSet("environmentId", "viewId", "claimId");
  private static final Set<String> RESOURCE_FIELDS = orderedSet("kind", "id");
  private static final Set<String> GLOBAL_RESOURCE_FIELDS = orderedSet("kind");

  private static final String THREAD = ResourceKind.THREAD.name().toLowerCase();
  private static final String CANVAS = ResourceKind.CANVAS.name().toLowerCase();
  private static final String PROJECTS = ResourceKind.PROJECTS.name().toLowerCase();
  private static final String TREE = ResourceKind.TREE.name().toLowerCase();
  private static final String INTERACTIONS = ResourceKind.INTERACTIONS.name().toLowerCase();
  private static final String ENVIRONMENTS = ResourceKind.ENVIRONMENTS.name().toLowerCase();

  private final RealtimeEventJsonCodec realtimeCodec;

  EventFrameCodec(RealtimeEventJsonCodec realtimeCodec) {
    this.realtimeCodec = Objects.requireNonNull(realtimeCodec, "realtimeCodec");
  }

  /** 客户端帧：订阅帧或完整 IDE 使用列表帧。 */
  sealed interface ClientFrame permits SubscriptionFrame, IdeUseFrame {}

  enum SubscriptionType {
    SUBSCRIBE,
    UNSUBSCRIBE
  }

  record SubscriptionFrame(SubscriptionType type, ResourceKey resource) implements ClientFrame {}

  record IdeUseFrame(IdeBrowserUse use) implements ClientFrame {}

  /** 解析客户端帧；非法 JSON 或字段抛 {@link IllegalArgumentException}。 */
  ClientFrame decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("malformed client frame JSON");
    }
    ObjectNode node = requireObject(root, "frame");
    requireVersionTwo(node, "frame");
    String typeName = requiredText(node, "type", "frame");
    return switch (typeName) {
      case "subscribe" -> {
        requireExactFields(node, SUBSCRIPTION_FIELDS, "frame");
        yield new SubscriptionFrame(
            SubscriptionType.SUBSCRIBE, parseResource(node.get("resource")));
      }
      case "unsubscribe" -> {
        requireExactFields(node, SUBSCRIPTION_FIELDS, "frame");
        yield new SubscriptionFrame(
            SubscriptionType.UNSUBSCRIBE, parseResource(node.get("resource")));
      }
      case "ide-use" -> {
        requireExactFields(node, IDE_USE_FIELDS, "frame");
        yield new IdeUseFrame(parseIdeUse(node));
      }
      default -> throw new IllegalArgumentException(
          "frame.type must be subscribe, unsubscribe, or ide-use");
    };
  }

  /** 编码连接建立后的第一条 {@code connected} 帧；携带本地事件节点与本次物理连接身份。 */
  String connected(UUID eventNodeId, UUID connectionId) {
    Objects.requireNonNull(eventNodeId, "eventNodeId");
    Objects.requireNonNull(connectionId, "connectionId");
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "connected");
    node.put("eventNodeId", eventNodeId.toString());
    node.put("connectionId", connectionId.toString());
    return write(node);
  }

  /** 编码 {@code ide-use-accepted} ack；只回显已接受的 revision。 */
  String ideUseAccepted(long revision) {
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive");
    }
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "ide-use-accepted");
    node.put("revision", Long.toString(revision));
    return write(node);
  }

  /** 编码 {@code subscribed} ack 帧（{@code cursor} 为 canonical 非负十进制）。 */
  String subscribed(ResourceKey resource, long cursor) {
    if (cursor < 0) {
      throw new IllegalArgumentException("cursor must be non-negative");
    }
    // 提示型资源没有持久游标：全局资源与按 key 提示的 Tree 资源 ack 恒为 0。
    if (cursor != 0 && isHintResource(resource.kind())) {
      throw new IllegalArgumentException("hint resource cursor must be zero");
    }
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "subscribed");
    node.set("resource", resourceNode(resource));
    node.put("cursor", Long.toString(cursor));
    return write(node);
  }

  /**
   * 编码 {@code event} 帧：统一 {@code {version,type,resource,name,data}}；前进事件额外携带 canonical {@code
   * cursor}（本次前进到的值），realtime 事件的 {@code data} 为 realtime codec JSON 对象。
   *
   * <p>前进事件的 {@code name} 与 {@code data} 字段名跟随资源坐标系：Thread 是 {@code version}，Canvas 是 {@code
   * revision}（与 {@code canvas_document.revision} 同名，不保留 version 别名）。
   */
  String event(ResourceKey resource, Signal signal) {
    Objects.requireNonNull(signal, "signal");
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "event");
    node.set("resource", resourceNode(resource));
    if (signal instanceof Signal.Version version) {
      String coordinate = coordinateName(resource);
      node.put("name", coordinate);
      node.put("cursor", version.version());
      node.set("data", dataNode(coordinate, version.version()));
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
    } else if (signal instanceof Signal.TreeChanged) {
      if (resource.kind() != ResourceKind.TREE) {
        throw new IllegalArgumentException("tree change signal requires the tree resource");
      }
      // 根 id 已在 resource 中，事件 data 无需重复携带。
      node.put("name", "changed");
      node.set("data", NODES.objectNode());
    } else if (signal instanceof Signal.InteractionsChanged interactionsChanged) {
      if (resource.kind() != ResourceKind.INTERACTIONS) {
        throw new IllegalArgumentException(
            "interactions change signal requires the interactions resource");
      }
      node.put("name", "changed");
      ObjectNode data = NODES.objectNode();
      data.put("rootThreadId", interactionsChanged.rootThreadId().toString());
      node.set("data", data);
    } else if (signal instanceof Signal.EnvironmentChanged) {
      if (resource.kind() != ResourceKind.ENVIRONMENTS) {
        throw new IllegalArgumentException(
            "environment change signal requires the environments resource");
      }
      node.put("name", "changed");
      node.set("data", NODES.objectNode());
    } else if (signal instanceof Signal.Resync) {
      throw new IllegalArgumentException("resync signal is not an event frame");
    } else {
      throw new IllegalArgumentException("unsupported signal: " + signal.getClass().getName());
    }
    return write(node);
  }

  /** 前进事件在 wire 上的坐标名：Thread 用 {@code version}，Canvas 用 {@code revision}。 */
  private static String coordinateName(ResourceKey resource) {
    return switch (resource.kind()) {
      case THREAD -> "version";
      case CANVAS -> "revision";
      default -> throw new IllegalArgumentException("version signal requires a versioned resource");
    };
  }

  /** 编码 {@code resync} 帧。 */
  String resync(ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "resync");
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  /** 编码连接级 {@code heartbeat} 帧；不绑定资源、不携带游标。 */
  String heartbeat() {
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "heartbeat");
    return write(node);
  }

  /** 编码 {@code error} 帧（连接级，不含 resource）。 */
  String error(String code, String message) {
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
    node.put("type", "error");
    node.put("code", code);
    node.put("message", message);
    return write(node);
  }

  /** 编码 {@code error} 帧（资源级，附带 resource 供客户端定位）。 */
  String error(String code, String message, ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    node.put("version", 2);
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

  /** 解析 canonical UUID，并去掉共同解析器回显的原始值。 */
  static UUID parseUuid(String value, String field) {
    try {
      return Uuids.parseCanonical(value, field);
    } catch (IllegalArgumentException error) {
      String message =
          value == null ? field + " must not be null" : field + " must be a canonical UUID";
      throw new IllegalArgumentException(message);
    }
  }

  private static void requireVersionTwo(ObjectNode node, String context) {
    JsonNode version = node.get("version");
    // 数值必须精确等于 integer 2；asLong() 会接受溢出整数的截断结果，isInt() 限定 int 范围。
    if (version == null || !version.isInt() || version.asInt() != 2) {
      throw new IllegalArgumentException(context + ".version must be the integer 2");
    }
  }

  private static IdeBrowserUse parseIdeUse(ObjectNode node) {
    JsonNode viewsNode = node.get("views");
    if (viewsNode == null || !viewsNode.isArray()) {
      throw new IllegalArgumentException("frame.views must be a JSON array");
    }
    if (viewsNode.size() > IdeBrowserUse.MAX_VIEWS) {
      throw new IllegalArgumentException(
          "frame.views must contain at most " + IdeBrowserUse.MAX_VIEWS + " entries");
    }
    List<IdeBrowserUseView> views = new ArrayList<>(viewsNode.size());
    for (JsonNode item : viewsNode) {
      ObjectNode view = requireObject(item, "frame.views[]");
      requireFields(view, IDE_USE_VIEW_FIELDS, IDE_USE_VIEW_REQUIRED_FIELDS, "frame.views[]");
      String environmentId = requiredText(view, "environmentId", "frame.views[]");
      String viewId = requiredText(view, "viewId", "frame.views[]");
      String claimId = requiredText(view, "claimId", "frame.views[]");
      String bindingEpoch =
          view.has("bindingEpoch") ? requiredText(view, "bindingEpoch", "frame.views[]") : null;
      try {
        views.add(IdeBrowserUseView.parse(environmentId, viewId, claimId, bindingEpoch));
      } catch (RuntimeException error) {
        // 不回显非法输入：EnvironmentId 等解析异常可能携带原始字符串。
        throw new IllegalArgumentException("frame.views[] is invalid", error);
      }
    }
    String documentId = requiredText(node, "documentId", "frame");
    String revision = requiredText(node, "revision", "frame");
    try {
      return IdeBrowserUse.parse(documentId, revision, views);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("frame ide-use is invalid", error);
    }
  }

  private static void requireFields(
      ObjectNode node, Set<String> allowed, Set<String> required, String context) {
    for (String name : node.propertyNames()) {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException(context + " has an unknown field");
      }
    }
    for (String name : required) {
      if (!node.has(name)) {
        throw new IllegalArgumentException(context + " is missing field " + name);
      }
    }
  }

  private static ResourceKey parseResource(JsonNode value) {
    ObjectNode node = requireObject(value, "frame.resource");
    String kindName = requiredText(node, "kind", "frame.resource");
    if (THREAD.equals(kindName)) {
      return new ResourceKey(ResourceKind.THREAD, resourceId(node));
    }
    if (CANVAS.equals(kindName)) {
      return new ResourceKey(ResourceKind.CANVAS, resourceId(node));
    }
    if (TREE.equals(kindName)) {
      return new ResourceKey(ResourceKind.TREE, resourceId(node));
    }
    if (PROJECTS.equals(kindName)) {
      requireExactFields(node, GLOBAL_RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(ResourceKind.PROJECTS, null);
    }
    if (INTERACTIONS.equals(kindName)) {
      requireExactFields(node, GLOBAL_RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(ResourceKind.INTERACTIONS, null);
    }
    if (ENVIRONMENTS.equals(kindName)) {
      requireExactFields(node, GLOBAL_RESOURCE_FIELDS, "frame.resource");
      return new ResourceKey(ResourceKind.ENVIRONMENTS, null);
    }
    throw new IllegalArgumentException(
        "frame.resource.kind must be thread, canvas, tree, projects, interactions, or environments");
  }

  private static UUID resourceId(ObjectNode node) {
    requireExactFields(node, RESOURCE_FIELDS, "frame.resource");
    return parseUuid(requiredText(node, "id", "frame.resource"), "frame.resource.id");
  }

  private static ObjectNode resourceNode(ResourceKey resource) {
    ObjectNode node = NODES.objectNode();
    switch (resource.kind()) {
      case THREAD -> node.put("kind", THREAD);
      case CANVAS -> node.put("kind", CANVAS);
      case TREE -> node.put("kind", TREE);
      case PROJECTS -> node.put("kind", PROJECTS);
      case INTERACTIONS -> node.put("kind", INTERACTIONS);
      case ENVIRONMENTS -> node.put("kind", ENVIRONMENTS);
    }
    if (resource.id() != null) {
      node.put("id", resource.id().toString());
    }
    return node;
  }

  /** 提示型（无持久游标）资源：ack cursor 恒为 0，只提示回读。 */
  private static boolean isHintResource(ResourceKind kind) {
    return kind == ResourceKind.PROJECTS
        || kind == ResourceKind.TREE
        || kind == ResourceKind.INTERACTIONS
        || kind == ResourceKind.ENVIRONMENTS;
  }

  private static ObjectNode requireObject(JsonNode value, String field) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return (ObjectNode) value;
  }

  private static String requiredText(ObjectNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(context + "." + field + " must be a string");
    }
    return value.stringValue();
  }

  private static void requireExactFields(ObjectNode node, Set<String> fields, String context) {
    if (node.size() != fields.size()) {
      throw new IllegalArgumentException(context + " must contain exactly " + fields + " fields");
    }
    for (String name : node.propertyNames()) {
      if (!fields.contains(name)) {
        throw new IllegalArgumentException(context + " has an unknown field");
      }
    }
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode event frame", error);
    }
  }

  /** 保持声明顺序的错误消息与校验语义。 */
  private static Set<String> orderedSet(String... values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
