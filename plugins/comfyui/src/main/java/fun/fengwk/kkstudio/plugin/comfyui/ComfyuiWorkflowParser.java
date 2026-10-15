package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code workflows} 普通配置的唯一严格解析与校验入口。
 *
 * <p>外层文本与每个 {@code workflowJson/argsSchemaJson} 都必须是严格 JSON：重复键、尾随 token、非
 * object、超深嵌套与非法结构都收敛为固定去敏错误，绝不回显原始 JSON 片段。校验完成后产出不可变的 {@link ComfyuiWorkflow} 快照，热更新只替换快照，不改动已冻结
 * Run 的解释。
 */
final class ComfyuiWorkflowParser {

  private static final Set<String> RESOURCE_REFERENCE_TYPES = Set.of("resourceReference");

  private ComfyuiWorkflowParser() {}

  static List<ComfyuiWorkflow> parse(JsonNode workflows) {
    if (!(workflows instanceof ArrayNode array)) {
      throw new IllegalArgumentException("workflows must be a JSON array");
    }
    List<ComfyuiWorkflow> parsed = new ArrayList<>(array.size());
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < array.size(); index++) {
      String path = "workflows[" + index + "]";
      ObjectNode item = object(array.get(index), path);
      String id = text(item, "id", path);
      if (!ids.add(id)) {
        throw new IllegalArgumentException("workflow id must be unique: " + id);
      }
      ComfyuiWorkflow workflow = parseWorkflow(item, path, id);
      // Canvas 业务校验（args schema/输出槽位/引用策略）必须与 enabled 无关地先完成：非法定义在配置期失败，不等到运行期装配。
      workflow.definition();
      if (workflow.strategy() == ComfyuiWorkflow.Strategy.H3) {
        H3WorkflowBuilder.validatePreset(workflow);
      }
      parsed.add(workflow);
    }
    return List.copyOf(parsed);
  }

  private static ComfyuiWorkflow parseWorkflow(ObjectNode item, String path, String id) {
    ObjectNode graph = parseGraph(text(item, "workflowJson", path), id);
    JsonObject argsSchema = parseArgsSchema(text(item, "argsSchemaJson", path), id);
    List<ComfyuiWorkflow.ParameterBinding> parameterBindings =
        parseParameterBindings(required(item, "parameterBindings", path), path);
    List<ComfyuiWorkflow.MediaBinding> mediaBindings =
        parseMediaBindings(required(item, "mediaBindings", path), path);
    List<ComfyuiWorkflow.OutputSelector> outputs =
        parseOutputs(required(item, "outputs", path), path);
    ComfyuiWorkflow.ReferencePolicy referencePolicy =
        parseReferencePolicy(required(item, "referencePolicy", path), path);

    requireDeclaredArguments(argsSchema, parameterBindings, id);
    for (ComfyuiWorkflow.ParameterBinding binding : parameterBindings) {
      ComfyuiGraph.requireTarget(
          graph, binding.nodeId(), binding.input(), "workflow " + id + " argument binding");
    }
    for (ComfyuiWorkflow.MediaBinding binding : mediaBindings) {
      ComfyuiGraph.requireTarget(
          graph, binding.nodeId(), binding.input(), "workflow " + id + " media binding");
    }
    for (ComfyuiWorkflow.OutputSelector selector : outputs) {
      requireSelectorTarget(graph, selector, id);
    }
    return new ComfyuiWorkflow(
        id,
        text(item, "description", path),
        strategy(text(item, "strategy", path), path),
        graph,
        argsSchema,
        parameterBindings,
        mediaBindings,
        outputs,
        referencePolicy);
  }

  private static ObjectNode parseGraph(String workflowJson, String id) {
    JsonNode node = JsonValues.readTree(workflowJson);
    if (!(node instanceof ObjectNode graph)) {
      throw new IllegalArgumentException("workflow " + id + " workflowJson must be a JSON object");
    }
    ComfyuiGraph.validate(graph);
    return graph;
  }

  private static JsonObject parseArgsSchema(String argsSchemaJson, String id) {
    JsonObject schema;
    try {
      schema = CanvasJson.parseObject(argsSchemaJson);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "workflow " + id + " argsSchemaJson must be a strict JSON args schema");
    }
    return schema;
  }

  /** 绑定参数必须是 argsSchema 已声明的顶层参数，且不能是 {@code resourceReference}：媒体只能经 mediaBindings 进入图。 */
  private static void requireDeclaredArguments(
      JsonObject argsSchema, List<ComfyuiWorkflow.ParameterBinding> bindings, String id) {
    JsonObject properties = properties(argsSchema);
    for (ComfyuiWorkflow.ParameterBinding binding : bindings) {
      var declared = properties.values().get(binding.argument());
      if (declared == null) {
        throw new IllegalArgumentException(
            "workflow " + id + " binds undeclared argument: " + binding.argument());
      }
      if (declared instanceof JsonObject object
          && object.values().get("type") instanceof JsonText type
          && RESOURCE_REFERENCE_TYPES.contains(type.value())) {
        throw new IllegalArgumentException(
            "workflow "
                + id
                + " must bind resource references through mediaBindings: "
                + binding.argument());
      }
    }
  }

  private static JsonObject properties(JsonObject argsSchema) {
    if (argsSchema.values().get("properties") instanceof JsonObject properties) {
      return properties;
    }
    throw new IllegalArgumentException("argsSchemaJson must declare a properties object");
  }

  private static void requireSelectorTarget(
      ObjectNode graph, ComfyuiWorkflow.OutputSelector selector, String id) {
    ComfyuiGraph.requireNode(graph, selector.nodeId(), "workflow " + id + " output");
  }

  private static List<ComfyuiWorkflow.ParameterBinding> parseParameterBindings(
      JsonNode value, String path) {
    ArrayNode array = array(value, path + ".parameterBindings");
    List<ComfyuiWorkflow.ParameterBinding> bindings = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      String itemPath = path + ".parameterBindings[" + index + "]";
      ObjectNode item = object(array.get(index), itemPath);
      bindings.add(
          new ComfyuiWorkflow.ParameterBinding(
              text(item, "argument", itemPath),
              text(item, "nodeId", itemPath),
              text(item, "input", itemPath)));
    }
    return bindings;
  }

  private static List<ComfyuiWorkflow.MediaBinding> parseMediaBindings(
      JsonNode value, String path) {
    ArrayNode array = array(value, path + ".mediaBindings");
    List<ComfyuiWorkflow.MediaBinding> bindings = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      String itemPath = path + ".mediaBindings[" + index + "]";
      ObjectNode item = object(array.get(index), itemPath);
      bindings.add(
          new ComfyuiWorkflow.MediaBinding(
              integer(item, "referenceIndex", itemPath),
              text(item, "nodeId", itemPath),
              text(item, "input", itemPath)));
    }
    return bindings;
  }

  private static List<ComfyuiWorkflow.OutputSelector> parseOutputs(JsonNode value, String path) {
    ArrayNode array = array(value, path + ".outputs");
    List<ComfyuiWorkflow.OutputSelector> outputs = new ArrayList<>(array.size());
    for (int index = 0; index < array.size(); index++) {
      String itemPath = path + ".outputs[" + index + "]";
      ObjectNode item = object(array.get(index), itemPath);
      JsonNode name = item.get("name");
      outputs.add(
          new ComfyuiWorkflow.OutputSelector(
              name == null ? null : text(item, "name", itemPath),
              kind(text(item, "kind", itemPath), itemPath),
              text(item, "nodeId", itemPath),
              field(text(item, "field", itemPath), itemPath),
              integer(item, "index", itemPath)));
    }
    return outputs;
  }

  private static ComfyuiWorkflow.ReferencePolicy parseReferencePolicy(JsonNode value, String path) {
    String policyPath = path + ".referencePolicy";
    ObjectNode policy = object(value, policyPath);
    ArrayNode allowedKinds = array(required(policy, "allowedKinds", policyPath), policyPath);
    Set<CanvasResourceKind> kinds = new LinkedHashSet<>();
    for (int index = 0; index < allowedKinds.size(); index++) {
      kinds.add(
          kind(
              textValue(allowedKinds.get(index), policyPath + ".allowedKinds[" + index + "]"),
              policyPath));
    }
    Map<CanvasResourceKind, Integer> byKind = new LinkedHashMap<>();
    ArrayNode countByKind = array(required(policy, "maxCountByKind", policyPath), policyPath);
    for (int index = 0; index < countByKind.size(); index++) {
      String itemPath = policyPath + ".maxCountByKind[" + index + "]";
      ObjectNode item = object(countByKind.get(index), itemPath);
      CanvasResourceKind kind = kind(text(item, "kind", itemPath), itemPath);
      int maxCount = integer(item, "maxCount", itemPath);
      if (byKind.put(kind, maxCount) != null) {
        throw new IllegalArgumentException(itemPath + " must not repeat a kind");
      }
    }
    return new ComfyuiWorkflow.ReferencePolicy(
        kinds, integer(policy, "maxCount", policyPath), byKind);
  }

  private static ComfyuiWorkflow.Strategy strategy(String value, String path) {
    try {
      return ComfyuiWorkflow.Strategy.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(path + ".strategy must be DIRECT or H3");
    }
  }

  private static CanvasResourceKind kind(String value, String path) {
    CanvasResourceKind kind;
    try {
      kind = CanvasResourceKind.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(path + " declares an unsupported resource kind");
    }
    if (kind == CanvasResourceKind.TEXT) {
      throw new IllegalArgumentException(path + " must not declare TEXT references");
    }
    return kind;
  }

  private static ComfyuiWorkflow.OutputField field(String value, String path) {
    try {
      return ComfyuiWorkflow.OutputField.fromJson(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(path + " declares an unsupported output field");
    }
  }

  private static JsonNode required(ObjectNode node, String field, String path) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(path + "." + field + " is required");
    }
    return value;
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (!(value instanceof ObjectNode object)) {
      throw new IllegalArgumentException(path + " must be a JSON object");
    }
    return object;
  }

  private static String textValue(JsonNode value, String path) {
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(path + " must be non-blank text");
    }
    return value.textValue();
  }

  private static ArrayNode array(JsonNode value, String path) {
    if (!(value instanceof ArrayNode array)) {
      throw new IllegalArgumentException(path + " must be a JSON array");
    }
    return array;
  }

  private static String text(ObjectNode node, String field, String path) {
    JsonNode value = required(node, field, path);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(path + "." + field + " must be non-blank text");
    }
    return value.textValue();
  }

  private static int integer(ObjectNode node, String field, String path) {
    JsonNode value = required(node, field, path);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException(path + "." + field + " must be an integer");
    }
    return value.intValue();
  }
}
