package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 冻结工作流普通配置的严格解析边界：非法 JSON、预算、绑定与 selector 规则、H3 预置不变量。 */
class ComfyuiWorkflowConfigTest {

  private static final String VALID_GRAPH =
      "{\"1\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"\"}},"
          + "\"2\":{\"class_type\":\"SaveVideo\",\"inputs\":{\"filename_prefix\":\"out\"}}}";

  /** 0 引用工作流仍必须声明非空 allowedKinds：媒体的「不接受」由上限 0 表达。 */
  private static final String ZERO_REFERENCE_POLICY =
      "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":0,"
          + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":0}]}";

  private static final String VALID_ARGS =
      "{\"type\":\"object\",\"additionalProperties\":false,"
          + "\"properties\":{\"prompt\":{\"type\":\"string\"}},\"required\":[\"prompt\"]}";

  // ---------------------------------------------------------------- 严格 JSON 与预算

  @Test
  void rejectsGraphJsonThatIsNotAStrictBoundedObject() {
    assertThrows(IllegalArgumentException.class, () -> parseGraph("[]"));
    assertThrows(IllegalArgumentException.class, () -> parseGraph("{}"));
    assertThrows(IllegalArgumentException.class, () -> parseGraph("{\"1\":1}"));
    // 重复键、尾随 token 与截断都在外层严格读入时收敛为固定错误。
    assertThrows(
        IllegalArgumentException.class,
        () -> parseGraph("{\"1\":{\"class_type\":\"A\",\"inputs\":{}},\"1\":{}}"));
    assertThrows(IllegalArgumentException.class, () -> parseGraph(VALID_GRAPH + "{\"3\":{}}"));
    assertThrows(IllegalArgumentException.class, () -> parseGraph("{\"1\":{\"class_type\":\"A\""));
    // 每个 node 必须声明非空 class_type 与 inputs object。
    assertThrows(
        IllegalArgumentException.class,
        () -> parseGraph("{\"1\":{\"class_type\":\" \",\"inputs\":{}}}"));
    assertThrows(
        IllegalArgumentException.class, () -> parseGraph("{\"1\":{\"class_type\":\"A\"}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> parseGraph("{\"1\":{\"class_type\":\"A\",\"inputs\":[]}}"));
    // 节点 id 有界字符集，不能借配置注入任意键。
    assertThrows(
        IllegalArgumentException.class,
        () -> parseGraph("{\"a b\":{\"class_type\":\"A\",\"inputs\":{}}}"));
  }

  @Test
  void rejectsGraphThatExceedsTheSafeNestingDepth() {
    StringBuilder nested = new StringBuilder();
    for (int i = 0; i <= ComfyuiGraph.MAX_DEPTH; i++) {
      nested.append("{\"a\":");
    }
    nested.append("1");
    nested.append("}".repeat(ComfyuiGraph.MAX_DEPTH + 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> parseGraph("{\"1\":{\"class_type\":\"A\",\"inputs\":{\"value\":" + nested + "}}}"));
  }

  @Test
  void rejectsArgsSchemaJsonBeyondTheStrictJsonBudget() {
    String oversized =
        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":\""
            + "x".repeat(CanvasJson.MAX_LENGTH)
            + "\"}}}";
    assertThrows(IllegalArgumentException.class, () -> settingsJson(argsSchemaWorkflow(oversized)));
  }

  /** 全部配置仍受 {@link PluginConfig#MAX_VALUES_UTF8_BYTES} 约束：插件永远看不到超预算修订。 */
  @Test
  void configurationValuesBeyondThePlatformBudgetNeverReachTheParser() {
    String oversized =
        "{\"enabled\":false,\"pad\":\"" + "x".repeat(PluginConfig.MAX_VALUES_UTF8_BYTES) + "\"}";
    assertTrue(
        oversized.getBytes(StandardCharsets.UTF_8).length > PluginConfig.MAX_VALUES_UTF8_BYTES);
    assertThrows(IllegalArgumentException.class, () -> new PluginConfig(1L, oversized));
  }

  // ---------------------------------------------------------------- 绑定与 selector 规则

  @Test
  void rejectsDuplicateWorkflowIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                "["
                    + ComfyuiWorkflowFixtures.directWorkflow("same-id")
                    + ","
                    + ComfyuiWorkflowFixtures.directWorkflow("same-id")
                    + "]"));
  }

  @Test
  void rejectsBindingsThatTargetMissingNodesOrInputs() {
    // 目标节点不存在。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        "[{\"argument\":\"prompt\",\"nodeId\":\"404\",\"input\":\"value\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
    // 目标 input 不存在：绑定绝不能新增图结构。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"missing\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        ComfyuiWorkflowFixtures.DIRECT_PARAMETER_BINDINGS,
                        "[{\"referenceIndex\":0,\"nodeId\":\"1\",\"input\":\"missing\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
  }

  @Test
  void rejectsUndeclaredArgumentsAndResourceReferenceBindings() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        "[{\"argument\":\"undeclared\",\"nodeId\":\"2\",\"input\":\"value\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
    // 媒体只能经 mediaBindings 进入图，参数绑定不得指向 resourceReference。
    String referenceArgs =
        "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":"
            + "{\"media\":{\"type\":\"resourceReference\"}},\"required\":[\"media\"]}";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.workflow(
                        "direct",
                        "DIRECT",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        referenceArgs,
                        "[{\"argument\":\"media\",\"nodeId\":\"1\",\"input\":\"image\"}]",
                        "[]",
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                        ZERO_REFERENCE_POLICY))));
  }

  @Test
  void rejectsRepeatedWritesToTheSameNodeInput() {
    // 同一 node/input 被参数与媒体绑定同时写入。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        "[{\"argument\":\"prompt\",\"nodeId\":\"1\",\"input\":\"image\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
    // 同一参数被绑定两次。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow(
                        "direct",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"},"
                            + "{\"argument\":\"prompt\",\"nodeId\":\"3\",\"input\":\"value\"}]",
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS))));
  }

  @Test
  void requiresMediaReferenceIndexesToBeExactlyZeroToN() {
    // 非连续（只有 1）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                mediaBindingWorkflow(
                    "[{\"referenceIndex\":1,\"nodeId\":\"1\",\"input\":\"image\"}]")));
    // 重复索引。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                mediaBindingWorkflow(
                    "[{\"referenceIndex\":0,\"nodeId\":\"1\",\"input\":\"image\"},"
                        + "{\"referenceIndex\":0,\"nodeId\":\"3\",\"input\":\"value\"}]")));
    // 负数索引。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                mediaBindingWorkflow(
                    "[{\"referenceIndex\":-1,\"nodeId\":\"1\",\"input\":\"image\"}]")));
    // 0..1 连续且目标不重复时合法。
    ComfyuiSettings settings =
        settingsJson(
            mediaBindingWorkflow(
                "[{\"referenceIndex\":0,\"nodeId\":\"1\",\"input\":\"image\"},"
                    + "{\"referenceIndex\":1,\"nodeId\":\"3\",\"input\":\"value\"}]"));
    assertEquals(
        List.of(0, 1),
        settings.workflows().get(0).mediaBindings().stream()
            .map(ComfyuiWorkflow.MediaBinding::referenceIndex)
            .toList());
  }

  @Test
  void acceptsZeroReferenceDirectWorkflows() {
    ComfyuiSettings settings =
        settingsJson(
            ComfyuiWorkflowFixtures.singleWorkflowArray(
                ComfyuiWorkflowFixtures.workflow(
                    "direct-zero",
                    "DIRECT",
                    ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                    ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                    "[{\"argument\":\"prompt\",\"nodeId\":\"2\",\"input\":\"value\"}]",
                    "[]",
                    ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                    ZERO_REFERENCE_POLICY)));
    ComfyuiWorkflow workflow = settings.workflows().get(0);
    assertTrue(workflow.mediaBindings().isEmpty());
    assertEquals(0, workflow.referencePolicy().maxCount());
    assertEquals(1, workflow.definition().outputs().size());
  }

  @Test
  void rejectsOutputSelectorsThatBreakKindFieldSlotOrIndexRules() {
    // field 与 kind 不匹配。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"IMAGE\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]")));
    // 不支持的 history 字段。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"files\",\"index\":0}]")));
    // TEXT 不是可输出/可引用的媒体类型。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"TEXT\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]")));
    // 负 index。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":-1}]")));
    // 重复 slot。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"name\":\"a\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0},"
                        + "{\"name\":\"b\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]")));
    // 多槽必须全部具名。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0},"
                        + "{\"name\":\"b\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":1}]")));
    // selector 节点必须存在于冻结图中。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                outputWorkflow(
                    "[{\"kind\":\"VIDEO\",\"nodeId\":\"404\",\"field\":\"videos\",\"index\":0}]")));
  }

  @Test
  void acceptsMultipleNamedSelectorsInFrozenOrder() {
    ComfyuiSettings settings =
        settingsJson(
            outputWorkflow(
                "[{\"name\":\"preview\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":1},"
                    + "{\"name\":\"final\",\"kind\":\"VIDEO\",\"nodeId\":\"4\",\"field\":\"videos\",\"index\":0}]"));
    assertEquals(
        List.of("preview", "final"),
        settings.workflows().get(0).definition().outputs().stream()
            .map(output -> output.name())
            .toList());
  }

  @Test
  void rejectsInvalidReferencePolicies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.workflow(
                        "direct",
                        "DIRECT",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                        ComfyuiWorkflowFixtures.DIRECT_PARAMETER_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                        "{\"allowedKinds\":[\"TEXT\"],\"maxCount\":1,"
                            + "\"maxCountByKind\":[{\"kind\":\"TEXT\",\"maxCount\":1}]}"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.workflow(
                        "direct",
                        "DIRECT",
                        ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                        ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
                        ComfyuiWorkflowFixtures.DIRECT_PARAMETER_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_MEDIA_BINDINGS,
                        ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                        "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":2,"
                            + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":1},"
                            + "{\"kind\":\"IMAGE\",\"maxCount\":2}]}"))));
  }

  // ---------------------------------------------------------------- H3 预置不变量

  @Test
  void acceptsTheDefaultH3PresetAndKeepsItsCanvasIdentity() {
    ComfyuiSettings settings = settingsJson(ComfyuiWorkflowFixtures.h3WorkflowsArray());
    ComfyuiWorkflow h3 = settings.workflows().get(0);
    assertEquals(H3WorkflowBuilder.WORKFLOW_ID, h3.id());
    assertEquals(ComfyuiWorkflow.Strategy.H3, h3.strategy());
    assertEquals(1, h3.outputs().size());
    assertNull(h3.outputs().get(0).name());
    assertEquals("92", h3.outputs().get(0).nodeId());
    assertEquals(12, h3.referencePolicy().maxCount());
    assertEquals(9, h3.referencePolicy().maxCountByKind().get(CanvasResourceKind.IMAGE));
    assertEquals(3, h3.referencePolicy().maxCountByKind().get(CanvasResourceKind.VIDEO));
    assertEquals(3, h3.referencePolicy().maxCountByKind().get(CanvasResourceKind.AUDIO));
  }

  @Test
  void rejectsH3PresetsThatDriftFromTheSupportedSemantics() {
    // 非预置 ID 不得使用 H3 策略。
    assertThrows(
        IllegalArgumentException.class, () -> settingsJson(h3Array(mutate("id", "other-h3"))));
    // argsSchema / 引用策略 / 固有绑定 / 媒体绑定都必须与已支持语义一致。
    assertThrows(
        IllegalArgumentException.class,
        () -> settingsJson(h3Array(mutate("argsSchemaJson", VALID_ARGS))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                h3Array(
                    mutate(
                        "referencePolicy",
                        "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":1,"
                            + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":1}]}"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> settingsJson(h3Array(mutate("parameterBindings", "[]"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                h3Array(
                    mutate(
                        "mediaBindings",
                        "[{\"referenceIndex\":0,\"nodeId\":\"92\",\"input\":\"video\"}]"))));
    // 输出必须是单一匿名 VIDEO 槽。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                h3Array(
                    mutate(
                        "outputs",
                        "[{\"name\":\"video\",\"kind\":\"VIDEO\",\"nodeId\":\"92\","
                            + "\"field\":\"videos\",\"index\":0}]"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            settingsJson(
                h3Array(
                    mutate(
                        "outputs",
                        "[{\"kind\":\"AUDIO\",\"nodeId\":\"92\",\"field\":\"audio\",\"index\":0}]"))));
  }

  @Test
  void rejectsH3PresetsWhoseGraphBreaksTheOptimizationOrLengthChain() {
    // 样本节点必须已删除；模板现在是外部配置，已知模板校验失败在配置边界收敛为去敏 IllegalArgumentException。
    ObjectNode withSample =
        ComfyuiWorkflowFixtures.h3WithAddedNode(
            "137", "{\"class_type\":\"SaveImage\",\"inputs\":{}}");
    IllegalArgumentException templateFailure =
        assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(withSample)));
    assertEquals(
        "workflow minimax-h3-ref2va workflowJson must preserve the supported H3 template",
        templateFailure.getMessage());
    // 优化链 / 帧长表达式不能被改写。
    ObjectNode brokenChain = ComfyuiWorkflowFixtures.h3WithInput("154", "model", "[\"999\",0]");
    assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(brokenChain)));
    ObjectNode brokenExpression = ComfyuiWorkflowFixtures.h3WithInput("131", "expression", "\"1\"");
    assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(brokenExpression)));
    // 所选输出节点必须是完整 SaveVideo。
    ObjectNode brokenSave =
        ComfyuiWorkflowFixtures.h3WithGraphField("92", "class_type", "\"SaveImage\"");
    assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(brokenSave)));
    ObjectNode missingPrefix = ComfyuiWorkflowFixtures.h3WithoutInput("92", "filename_prefix");
    assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(missingPrefix)));
  }

  /** H3 构图从保留区无条件追加动态媒体节点：静态模板占用这些 id 必须在配置期被拒绝，而不是等已付出 Prompt/上传代价。 */
  @Test
  void rejectsH3TemplatesThatOccupyTheReservedDynamicNodeRange() {
    ObjectNode atBoundary =
        ComfyuiWorkflowFixtures.h3WithAddedNode(
            "1000", "{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"x\"}}");
    ObjectNode farAbove =
        ComfyuiWorkflowFixtures.h3WithAddedNode(
            "100000000000000000000",
            "{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"x\"}}");
    for (ObjectNode preset : List.of(atBoundary, farAbove)) {
      IllegalArgumentException failure =
          assertThrows(IllegalArgumentException.class, () -> settingsJson(h3Array(preset)));
      assertEquals(
          "workflow minimax-h3-ref2va workflowJson must preserve the supported H3 template",
          failure.getMessage());
    }
    // 边界之下仍可用：保留区只从 1000 的规范十进制 id 开始。
    ObjectNode belowBoundary =
        ComfyuiWorkflowFixtures.h3WithAddedNode(
            "999", "{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"x\"}}");
    assertTrue(settingsJson(h3Array(belowBoundary)).workflows().get(0).graph().has("999"));
  }

  /** 保留区只约束 H3：DIRECT 工作流可以自由声明并使用 1000 及更大的规范十进制节点 id。 */
  @Test
  void acceptsDirectWorkflowsThatUseTheH3ReservedDynamicNodeIds() {
    String graph =
        """
        {
          "1": {"class_type": "PrimitiveString", "inputs": {"value": "placeholder"}},
          "1000": {"class_type": "SaveVideo", "inputs": {"filename_prefix": "out", "videos": ["1", 0]}}
        }
        """;
    ComfyuiSettings settings =
        settingsJson(
            ComfyuiWorkflowFixtures.singleWorkflowArray(
                ComfyuiWorkflowFixtures.directWorkflow(
                    "direct-1000",
                    graph,
                    "[{\"argument\":\"prompt\",\"nodeId\":\"1000\",\"input\":\"filename_prefix\"}]",
                    "[]",
                    "[{\"kind\":\"VIDEO\",\"nodeId\":\"1000\",\"field\":\"videos\",\"index\":0}]")));
    assertEquals("1000", settings.workflows().get(0).outputs().get(0).nodeId());
    assertTrue(settings.workflows().get(0).graph().has("1000"));
  }

  /** 冻结图只在构造时深拷贝并只以副本暴露：外部初始图与每次 accessor 副本的深层原地改写都不影响快照与定义。 */
  @Test
  void frozenGraphNeverLeaksMutatingCallers() {
    ObjectNode initialGraph =
        (ObjectNode) JsonValues.readTree(ComfyuiWorkflowFixtures.DIRECT_GRAPH);
    ComfyuiWorkflow workflow =
        new ComfyuiWorkflow(
            "direct",
            "test workflow",
            ComfyuiWorkflow.Strategy.DIRECT,
            initialGraph,
            CanvasJson.parseObject(ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA),
            List.of(),
            List.of(),
            List.of(
                new ComfyuiWorkflow.OutputSelector(
                    null, CanvasResourceKind.VIDEO, "4", ComfyuiWorkflow.OutputField.VIDEOS, 0)),
            new ComfyuiWorkflow.ReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 0, Map.of()));

    mutateGraph(initialGraph);
    assertSnapshotUnchanged(workflow);

    mutateGraph(workflow.graph());
    assertSnapshotUnchanged(workflow);
  }

  private static void assertSnapshotUnchanged(ComfyuiWorkflow workflow) {
    assertEquals(4, workflow.graph().size());
    assertEquals("placeholder", workflow.graph().path("2").path("inputs").path("value").asText());
    assertEquals("direct", workflow.definition().name());
    assertEquals(1, workflow.definition().outputs().size());
  }

  /** 深层原地改写：改绑定目标值并追加一个节点，模拟调用方对"自己拿到的图"做任意修改。 */
  private static void mutateGraph(ObjectNode graph) {
    ((ObjectNode) graph.get("2").get("inputs")).put("value", "leaked");
    graph.set(
        "1000",
        (ObjectNode)
            JsonValues.readTree(
                "{\"class_type\":\"PrimitiveString\",\"inputs\":{\"value\":\"leaked\"}}"));
  }

  /** H3 selector 不被锁死为 92：图内任意已验证的合法 SaveVideo 节点都可作为输出与构图目标。 */
  @Test
  void acceptsH3SelectorThatPointsToAnotherValidatedSaveVideoNode() {
    ObjectNode preset = ComfyuiWorkflowFixtures.h3WithSaveVideoNode("900");
    ComfyuiSettings settings = settingsJson(h3Array(preset));
    ComfyuiWorkflow h3 = settings.workflows().get(0);
    assertEquals("900", h3.outputs().get(0).nodeId());
    assertEquals(0, h3.outputs().get(0).index());
    assertTrue(h3.graph().has("900"));
  }

  /** 图内其它节点不是 selector 目标时不被解释：输出只取已声明 selector 的节点。 */
  @Test
  void ignoresNodesThatAreNotDeclaredSelectorTargets() {
    ObjectNode preset =
        ComfyuiWorkflowFixtures.h3WithAddedNode(
            "901", "{\"class_type\":\"SaveVideo\",\"inputs\":{}}");
    ComfyuiSettings settings = settingsJson(h3Array(preset));
    assertEquals("92", settings.workflows().get(0).outputs().get(0).nodeId());
  }

  // ---------------------------------------------------------------- helpers

  private static ComfyuiSettings settingsJson(String workflowsArrayJson) {
    return ComfyuiSettings.parse(ComfyuiWorkflowFixtures.settingsJson(true, workflowsArrayJson));
  }

  private static void parseGraph(String graph) {
    settingsJson(
        ComfyuiWorkflowFixtures.singleWorkflowArray(
            ComfyuiWorkflowFixtures.directWorkflow(
                "direct", graph, "[]", "[]", ComfyuiWorkflowFixtures.DIRECT_OUTPUTS)));
  }

  private static String argsSchemaWorkflow(String argsSchemaJson) {
    return ComfyuiWorkflowFixtures.singleWorkflowArray(
        ComfyuiWorkflowFixtures.workflow(
            "direct",
            "DIRECT",
            VALID_GRAPH,
            argsSchemaJson,
            "[]",
            "[]",
            ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
            ComfyuiWorkflowFixtures.IMAGE_POLICY));
  }

  private static String outputWorkflow(String outputs) {
    return ComfyuiWorkflowFixtures.singleWorkflowArray(
        ComfyuiWorkflowFixtures.workflow(
            "direct",
            "DIRECT",
            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
            ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
            "[]",
            "[]",
            outputs,
            ZERO_REFERENCE_POLICY));
  }

  private static String mediaBindingWorkflow(String mediaBindings) {
    return ComfyuiWorkflowFixtures.singleWorkflowArray(
        ComfyuiWorkflowFixtures.workflow(
            "direct",
            "DIRECT",
            ComfyuiWorkflowFixtures.DIRECT_GRAPH,
            ComfyuiWorkflowFixtures.DIRECT_ARGS_SCHEMA,
            "[]",
            mediaBindings,
            ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
            "{\"allowedKinds\":[\"IMAGE\"],\"maxCount\":2,"
                + "\"maxCountByKind\":[{\"kind\":\"IMAGE\",\"maxCount\":2}]}"));
  }

  private static String h3Array(ObjectNode preset) {
    return ComfyuiWorkflowFixtures.h3Array(preset);
  }

  private static ObjectNode mutate(String field, String value) {
    ObjectNode preset = ComfyuiWorkflowFixtures.h3Preset();
    preset.set(field, JsonValues.readTree(value));
    return preset;
  }
}
