package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.json.JsonValues;

/**
 * 配置测试共用的合法/非法工作流与配置 JSON 构造。
 *
 * <p>默认值只来自 {@link ComfyuiPlugin#CONFIGURATION_SCHEMA}，测试只在其上做精确覆盖，避免在测试里复制第二份默认值。
 */
final class ComfyuiWorkflowFixtures {

  static final String DIRECT_GRAPH =
      """
      {
        "1": {"class_type": "LoadImage", "inputs": {"image": "placeholder"}},
        "2": {"class_type": "PrimitiveString", "inputs": {"value": "placeholder"}},
        "3": {"class_type": "PrimitiveInt", "inputs": {"value": 0}},
        "4": {"class_type": "SaveVideo", "inputs": {"filename_prefix": "out", "videos": ["5", 0]}}
      }
      """;

  static final String DIRECT_ARGS_SCHEMA =
      """
      {
        "type": "object",
        "additionalProperties": false,
        "properties": {"prompt": {"type": "string"}, "duration": {"type": "integer"}},
        "required": ["prompt"]
      }
      """;

  static final String DIRECT_PARAMETER_BINDINGS =
      "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"},"
          + "{\"argument\":\"duration\",\"nodeId\":\"3\",\"input\":\"value\"}]";

  static final String DIRECT_MEDIA_BINDINGS =
      "[{\"referenceIndex\":0,\"nodeId\":\"1\",\"input\":\"image\"}]";

  static final String DIRECT_OUTPUTS =
      "[{\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]";

  static final String IMAGE_POLICY =
      "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":1,"
          + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":1}]}";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private ComfyuiWorkflowFixtures() {}

  static String defaultsJson() {
    return ComfyuiPlugin.CONFIGURATION_SCHEMA.defaultsJson();
  }

  static String directWorkflow(String id) {
    return directWorkflow(
        id, DIRECT_GRAPH, DIRECT_PARAMETER_BINDINGS, DIRECT_MEDIA_BINDINGS, DIRECT_OUTPUTS);
  }

  static String directWorkflow(
      String id, String graph, String parameterBindings, String mediaBindings, String outputs) {
    return workflow(
        id,
        "DIRECT",
        graph,
        DIRECT_ARGS_SCHEMA,
        parameterBindings,
        mediaBindings,
        outputs,
        IMAGE_POLICY);
  }

  static String workflow(
      String id,
      String strategy,
      String graph,
      String argsSchema,
      String parameterBindings,
      String mediaBindings,
      String outputs,
      String referencePolicy) {
    return "{\"id\":"
        + quote(id)
        + ",\"description\":\"test workflow\",\"strategy\":"
        + quote(strategy)
        + ",\"workflowJson\":"
        + quote(graph)
        + ",\"argsSchemaJson\":"
        + quote(argsSchema)
        + ",\"parameterBindings\":"
        + parameterBindings
        + ",\"mediaBindings\":"
        + mediaBindings
        + ",\"outputs\":"
        + outputs
        + ",\"referencePolicy\":"
        + referencePolicy
        + "}";
  }

  /** 把内联 JSON 文本包成 JSON 字符串字面量。 */
  static String quote(String value) {
    return MAPPER.writeValueAsString(value);
  }

  /** 以插件默认配置为基线：只改写 enabled 与 workflows。 */
  static String settingsJson(boolean enabled, String workflowsArrayJson) {
    ObjectNode defaults = (ObjectNode) JsonValues.readTree(defaultsJson());
    defaults.put("enabled", enabled);
    defaults.set("workflows", JsonValues.readTree(workflowsArrayJson));
    return JsonValues.write(defaults);
  }

  /** 以插件默认配置为基线，并按 schema 类型覆盖一个顶层字段。 */
  static String settingsJson(boolean enabled, String workflowsArrayJson, String field, long value) {
    ObjectNode defaults = (ObjectNode) JsonValues.readTree(defaultsJson());
    defaults.put("enabled", enabled);
    defaults.set("workflows", JsonValues.readTree(workflowsArrayJson));
    defaults.put(field, value);
    return JsonValues.write(defaults);
  }

  static String settingsJson(
      boolean enabled, String workflowsArrayJson, String field, String value) {
    ObjectNode defaults = (ObjectNode) JsonValues.readTree(defaultsJson());
    defaults.put("enabled", enabled);
    defaults.set("workflows", JsonValues.readTree(workflowsArrayJson));
    defaults.put(field, value);
    return JsonValues.write(defaults);
  }

  static String h3WorkflowsArray() {
    return JsonValues.readTree(defaultsJson()).get("workflows").toString();
  }

  static String singleWorkflowArray(String workflow) {
    return "[" + workflow + "]";
  }

  /** 取默认 H3 预置工作流并允许就地改写任意字段，用于构造非法预设。 */
  static ObjectNode h3Preset() {
    return (ObjectNode) JsonValues.readTree(h3WorkflowsArray()).get(0).deepCopy();
  }

  static String h3Array(ObjectNode preset) {
    return "[" + JsonValues.write(preset) + "]";
  }

  /** 向预置图追加一个节点定义，用于验证图内多余节点不被解释。 */
  static ObjectNode h3WithAddedNode(String nodeId, String nodeJson) {
    ObjectNode preset = h3Preset();
    ObjectNode graph = h3Graph(preset);
    graph.set(nodeId, JsonValues.readTree(nodeJson));
    return withGraph(preset, graph);
  }

  /** 追加一个完整 SaveVideo 节点并把它设为唯一输出 selector 的目标：selector 不被锁死为 92。 */
  static ObjectNode h3WithSaveVideoNode(String nodeId) {
    ObjectNode preset =
        h3WithAddedNode(
            nodeId,
            "{\"class_type\":\"SaveVideo\",\"inputs\":{\"filename_prefix\":\"other\","
                + "\"video\":[\"130\",0]}}");
    ((ObjectNode) preset.get("outputs").get(0)).put("nodeId", nodeId);
    return preset;
  }

  /** 改写预置图指定节点的输入值。 */
  static ObjectNode h3WithInput(String nodeId, String input, String json) {
    ObjectNode preset = h3Preset();
    ObjectNode graph = h3Graph(preset);
    ((ObjectNode) graph.get(nodeId).get("inputs")).set(input, JsonValues.readTree(json));
    return withGraph(preset, graph);
  }

  /** 改写预置图指定节点的节点级字段。 */
  static ObjectNode h3WithGraphField(String nodeId, String field, String json) {
    ObjectNode preset = h3Preset();
    ObjectNode graph = h3Graph(preset);
    ((ObjectNode) graph.get(nodeId)).set(field, JsonValues.readTree(json));
    return withGraph(preset, graph);
  }

  /** 删除预置图指定节点的一个输入。 */
  static ObjectNode h3WithoutInput(String nodeId, String input) {
    ObjectNode preset = h3Preset();
    ObjectNode graph = h3Graph(preset);
    ((ObjectNode) graph.get(nodeId).get("inputs")).remove(input);
    return withGraph(preset, graph);
  }

  private static ObjectNode h3Graph(ObjectNode preset) {
    return (ObjectNode) JsonValues.readTree(preset.get("workflowJson").textValue());
  }

  private static ObjectNode withGraph(ObjectNode preset, ObjectNode graph) {
    preset.put("workflowJson", JsonValues.write(graph));
    return preset;
  }
}
