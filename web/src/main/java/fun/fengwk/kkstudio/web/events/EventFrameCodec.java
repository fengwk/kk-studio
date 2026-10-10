package fun.fengwk.kkstudio.web.events;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
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
 * 事件通道帧的严格 JSON codec：所有帧（客户端与服务端）都带 {@code version:2}；客户端帧手工字段校验（duplicate/trailing/unknown/
 * missing/wrong-type 全部拒绝），服务端帧确定性编码。不再接受逻辑 {@code version:1}，也不保留兼容 decoder。
 *
 * <p>客户端帧是两种明确变体：resource 帧 {@code {version:2, type:'subscribe'|'unsubscribe',
 * resource}}，字段集精确；shell 帧 {@code {version:2, type:'shell.command',
 * command:<TerminalCommand>}}，嵌套命令直接复用 {@link TerminalControlCodec}。浏览器绝不填写 route/owner/lease。
 *
 * <p>服务端 resource 帧 {@code subscribed{resource,cursor}} / {@code event{resource,name,data}} /
 * {@code resync{resource}} 语义不变；shell 帧为 {@code {version:2, type:'shell.event',
 * event:<TerminalEvent>}}，同样复用 {@link TerminalControlCodec}。连接级 {@code heartbeat} 与 {@code
 * error{code,message[,resource]}} 字段与旧版一致，仅版本升为 2。游标是 canonical 非负十进制字符串（{@code 0|[1-9][0-9]*}，不超
 * bigint）；提示型 resource（Projects/Tree/Interactions/Environments）无持久游标，ack 恒为 0。
 */
final class EventFrameCodec {

  static final String INVALID_FRAME = "INVALID_FRAME";
  static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";
  static final String BACKPRESSURE = "BACKPRESSURE";
  static final String SEND_FAILED = "SEND_FAILED";

  static final int VERSION = 2;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final Set<String> RESOURCE_CLIENT_FRAME_FIELDS =
      orderedSet("version", "type", "resource");
  private static final Set<String> SHELL_CLIENT_FRAME_FIELDS =
      orderedSet("version", "type", "command");
  private static final Set<String> RESOURCE_FIELDS = orderedSet("kind", "id");
  private static final Set<String> GLOBAL_RESOURCE_FIELDS = orderedSet("kind");

  private static final String THREAD = ResourceKind.THREAD.name().toLowerCase();
  private static final String CANVAS = ResourceKind.CANVAS.name().toLowerCase();
  private static final String PROJECTS = ResourceKind.PROJECTS.name().toLowerCase();
  private static final String TREE = ResourceKind.TREE.name().toLowerCase();
  private static final String INTERACTIONS = ResourceKind.INTERACTIONS.name().toLowerCase();
  private static final String ENVIRONMENTS = ResourceKind.ENVIRONMENTS.name().toLowerCase();

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  private final RealtimeEventJsonCodec realtimeCodec;
  private final TerminalControlCodec terminalCodec = new TerminalControlCodec();

  EventFrameCodec(RealtimeEventJsonCodec realtimeCodec) {
    this.realtimeCodec = Objects.requireNonNull(realtimeCodec, "realtimeCodec");
  }

  /** 客户端帧；resource 与 shell 两种明确变体，不存在其它形态。 */
  sealed interface ClientFrame permits ResourceFrame, ShellCommand {}

  /** resource 订阅/退订帧。 */
  record ResourceFrame(Type type, ResourceKey resource) implements ClientFrame {
    enum Type {
      SUBSCRIBE,
      UNSUBSCRIBE
    }
  }

  /** shell 控制帧；命令已由共享控制 codec 严格解码。 */
  record ShellCommand(TerminalCommand command) implements ClientFrame {
    ShellCommand {
      Objects.requireNonNull(command, "command");
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
    requireVersionTwo(node, "frame");
    String typeName = requiredText(node, "type", "frame");
    if ("subscribe".equals(typeName) || "unsubscribe".equals(typeName)) {
      requireExactFields(node, RESOURCE_CLIENT_FRAME_FIELDS, "frame");
      ResourceFrame.Type type =
          "subscribe".equals(typeName)
              ? ResourceFrame.Type.SUBSCRIBE
              : ResourceFrame.Type.UNSUBSCRIBE;
      return new ResourceFrame(type, parseResource(node.get("resource")));
    }
    if ("shell.command".equals(typeName)) {
      requireExactFields(node, SHELL_CLIENT_FRAME_FIELDS, "frame");
      ObjectNode commandNode = requireObject(node.get("command"), "frame.command");
      TerminalCommand command;
      try {
        command = terminalCodec.decodeCommand(write(commandNode));
      } catch (IllegalArgumentException error) {
        // 不保留 cause：控制 codec 异常可能回显非法值/原 payload。
        throw new IllegalArgumentException("frame.command must be a valid TerminalCommand");
      }
      return new ShellCommand(command);
    }
    // 只接受精确小写；toUpperCase 归一化会放行 SUBSCRIBE/Subscribe 等非 canonical 值。
    throw new IllegalArgumentException(
        "frame.type must be subscribe, unsubscribe or shell.command");
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
    ObjectNode node = base("subscribed");
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
    ObjectNode node = base("event");
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

  /** 编码 {@code shell.event} 帧：嵌套事件直接复用共享控制 codec。 */
  String shellEvent(TerminalEvent event) {
    Objects.requireNonNull(event, "event");
    ObjectNode node = base("shell.event");
    node.set("event", parseNode(terminalCodec.encodeEvent(event), "event"));
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
    ObjectNode node = base("resync");
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  /** 编码连接级 {@code heartbeat} 帧；不绑定资源、不携带游标。 */
  String heartbeat() {
    return write(base("heartbeat"));
  }

  /** 编码 {@code error} 帧（连接级，不含 resource）。 */
  String error(String code, String message) {
    ObjectNode node = base("error");
    node.put("code", code);
    node.put("message", message);
    return write(node);
  }

  /** 编码 {@code error} 帧（资源级，附带 resource 供客户端定位）。 */
  String error(String code, String message, ResourceKey resource) {
    ObjectNode node = base("error");
    node.put("code", code);
    node.put("message", message);
    node.set("resource", resourceNode(resource));
    return write(node);
  }

  private static ObjectNode base(String type) {
    ObjectNode node = NODES.objectNode();
    node.put("version", VERSION);
    node.put("type", type);
    return node;
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

  private static void requireVersionTwo(ObjectNode node, String context) {
    JsonNode version = node.get("version");
    // 数值必须精确等于 integer 2；asLong() 会接受溢出整数的截断结果（如 2^64），isInt() 限定 int 范围。
    if (version == null || !version.isInt() || version.asInt() != VERSION) {
      throw new IllegalArgumentException(context + ".version must be the integer " + VERSION);
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

  private static ObjectNode parseNode(String json, String field) {
    try {
      return requireObject(MAPPER.readTree(json), field);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
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

  private static String write(JsonNode node) {
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
