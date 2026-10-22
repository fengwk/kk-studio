package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNull;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;

import java.util.Objects;

/**
 * ComfyUI API 格式图的唯一结构门禁与写入入口。
 *
 * <p>图是 {@code {nodeId: {class_type, inputs}}} 的封闭对象：每个 node 必须声明非空 {@code class_type} 与 {@code
 * inputs} object，且整图嵌套不超过 {@link #MAX_DEPTH}。绑定写入只能改已存在的 node/input，绝不新增节点或输入键，因此请求无法注入任意图结构。
 */
final class ComfyuiGraph {

  /** 图嵌套深度上限，与 Canvas args 的严格模型保持一致。 */
  static final int MAX_DEPTH = 16;

  private static final int MAX_NODE_ID_CHARS = 64;

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private ComfyuiGraph() {}

  /** 结构校验：非空节点集合、合法节点 id、非空 class_type 与 inputs object、有界嵌套深度。 */
  static void validate(ObjectNode graph) {
    Objects.requireNonNull(graph, "graph");
    if (graph.isEmpty()) {
      throw new IllegalArgumentException("workflow graph must declare at least one node");
    }
    requireDepth(graph, 1);
    for (String nodeId : graph.propertyNames()) {
      requireNodeId(nodeId);
      ObjectNode node = node(graph.get(nodeId), nodeId);
      JsonNode classType = node.get("class_type");
      if (classType == null || !classType.isTextual() || classType.textValue().isBlank()) {
        throw new IllegalArgumentException("graph node " + nodeId + " must declare class_type");
      }
      if (!(node.get("inputs") instanceof ObjectNode)) {
        throw new IllegalArgumentException("graph node " + nodeId + " must declare inputs object");
      }
    }
  }

  /** 输出 selector 只要求节点存在：{@code field} 是远端 history 的输出字段，不是图的输入。 */
  static void requireNode(ObjectNode graph, String nodeId, String describe) {
    if (!(graph.path(nodeId) instanceof ObjectNode node)) {
      throw new IllegalArgumentException(describe + " target node does not exist: " + nodeId);
    }
    if (!(node.get("inputs") instanceof ObjectNode)) {
      throw new IllegalArgumentException(describe + " target node has no inputs: " + nodeId);
    }
  }

  /** 绑定目标必须已存在于冻结图中：节点存在、inputs 是 object 且已声明该输入键。 */
  static void requireTarget(ObjectNode graph, String nodeId, String input, String describe) {
    if (!(graph.path(nodeId) instanceof ObjectNode node)) {
      throw new IllegalArgumentException(describe + " target node does not exist: " + nodeId);
    }
    if (!(node.get("inputs") instanceof ObjectNode inputs)) {
      throw new IllegalArgumentException(describe + " target node has no inputs: " + nodeId);
    }
    if (!inputs.has(input)) {
      throw new IllegalArgumentException(
          describe + " target input does not exist: " + nodeId + "." + input);
    }
  }

  /** 只用给定值覆盖已存在的 node/input，绝不新增结构。 */
  static void write(ObjectNode graph, String nodeId, String input, JsonNode value) {
    requireTarget(graph, nodeId, input, "binding");
    ((ObjectNode) graph.get(nodeId).get("inputs")).set(input, value);
  }

  /** 文本值节点，供绑定写入已存在的输入。 */
  static JsonNode textNode(String value) {
    return MAPPER.getNodeFactory().textNode(value);
  }

  /** 已注册 schema 的合法 args 值到图的 JSON 值：resourceReference 之外的标量与容器都可安全搬运。 */
  static JsonNode toJson(CanvasJson value) {
    if (value instanceof JsonObject object) {
      ObjectNode node = MAPPER.createObjectNode();
      for (var entry : object.values().entrySet()) {
        node.set(entry.getKey(), toJson(entry.getValue()));
      }
      return node;
    }
    if (value instanceof JsonArray array) {
      ArrayNode node = MAPPER.createArrayNode();
      for (CanvasJson item : array.values()) {
        node.add(toJson(item));
      }
      return node;
    }
    if (value instanceof JsonText text) {
      return MAPPER.getNodeFactory().textNode(text.value());
    }
    if (value instanceof JsonNumber number) {
      return MAPPER.getNodeFactory().numberNode(number.value());
    }
    if (value instanceof JsonBool bool) {
      return MAPPER.getNodeFactory().booleanNode(bool.value());
    }
    if (value instanceof JsonNull) {
      return MAPPER.getNodeFactory().nullNode();
    }
    throw new IllegalArgumentException(
        "unsupported argument value: " + value.getClass().getSimpleName());
  }

  private static void requireNodeId(String nodeId) {
    if (nodeId.isBlank() || nodeId.length() > MAX_NODE_ID_CHARS) {
      throw new IllegalArgumentException("graph node id must be non-blank and bounded");
    }
    for (int i = 0; i < nodeId.length(); i++) {
      char c = nodeId.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':')) {
        throw new IllegalArgumentException("graph node id contains unsupported characters");
      }
    }
  }

  private static ObjectNode node(JsonNode value, String nodeId) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException("graph node " + nodeId + " must be a JSON object");
    }
    return object;
  }

  private static void requireDepth(JsonNode node, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("workflow graph must not nest deeper than " + MAX_DEPTH);
    }
    if (node instanceof ObjectNode object) {
      for (String name : object.propertyNames()) {
        requireDepth(object.get(name), depth + 1);
      }
    } else if (node instanceof ArrayNode array) {
      for (JsonNode item : array) {
        requireDepth(item, depth + 1);
      }
    }
  }
}
