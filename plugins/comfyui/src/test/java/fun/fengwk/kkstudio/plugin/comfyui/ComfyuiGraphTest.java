package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNull;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.function.CanvasJson.JsonText;
import fun.fengwk.kkstudio.common.json.JsonValues;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 图的唯一结构门禁与写入入口：结构校验、绑定写入绝不新增结构、args 值搬运不失真。 */
class ComfyuiGraphTest {

  @Test
  void validateRequiresBoundedClassTypeAndInputsForEveryNode() {
    assertThrows(IllegalArgumentException.class, () -> ComfyuiGraph.validate(graph("{}")));
    assertThrows(IllegalArgumentException.class, () -> ComfyuiGraph.validate(graph("{\"1\":[]}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.validate(graph("{\"1\":{\"inputs\":{}}}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.validate(graph("{\"1\":{\"class_type\":\" \",\"inputs\":{}}}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.validate(graph("{\"1\":{\"class_type\":\"A\",\"inputs\":1}}")));
    // 节点 id 必须非空白、有界且只用安全字符。
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.validate(graph("{\"\":{\"class_type\":\"A\",\"inputs\":{}}}")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ComfyuiGraph.validate(
                graph("{\"" + "a".repeat(65) + "\":{\"class_type\":\"A\",\"inputs\":{}}}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.validate(graph("{\"a b\":{\"class_type\":\"A\",\"inputs\":{}}}")));
    // 合法最小图与安全字符集内的 id 都接受。
    ComfyuiGraph.validate(
        graph("{\"load-image_1.2:3\":{\"class_type\":\"LoadImage\",\"inputs\":{}}}"));
  }

  @Test
  void validateRejectsNestingDeeperThanTheSafeDepth() {
    StringBuilder nested = new StringBuilder();
    for (int i = 0; i < ComfyuiGraph.MAX_DEPTH; i++) {
      nested.append("{\"a\":");
    }
    nested.append("1").append("}".repeat(ComfyuiGraph.MAX_DEPTH));
    String tooDeep = "{\"1\":{\"class_type\":\"A\",\"inputs\":{\"value\":" + nested + "}}}";
    assertThrows(IllegalArgumentException.class, () -> ComfyuiGraph.validate(graph(tooDeep)));
  }

  @Test
  void requireNodeAndRequireTargetReportMissingNodesAndInputs() {
    ObjectNode graph =
        graph(
            "{\"1\":{\"class_type\":\"A\",\"inputs\":{\"value\":\"\"}},\"2\":{\"class_type\":\"B\"}}");
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiGraph.requireNode(graph, "404", "test"));
    assertThrows(
        IllegalArgumentException.class, () -> ComfyuiGraph.requireNode(graph, "2", "test"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.requireTarget(graph, "2", "value", "test"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.requireTarget(graph, "1", "other", "test"));
    ComfyuiGraph.requireTarget(graph, "1", "value", "test");
  }

  /** 写入只能覆盖已存在的 node/input：目标缺失时图必须逐字节保持原样。 */
  @Test
  void writeNeverAddsStructureToTheFrozenGraph() {
    ObjectNode graph = graph("{\"1\":{\"class_type\":\"A\",\"inputs\":{\"value\":\"old\"}}}");
    String before = JsonValues.write(graph);

    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.write(graph, "2", "value", ComfyuiGraph.textNode("new")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ComfyuiGraph.write(graph, "1", "missing", ComfyuiGraph.textNode("new")));
    assertEquals(before, JsonValues.write(graph));

    ComfyuiGraph.write(graph, "1", "value", ComfyuiGraph.textNode("new"));
    assertEquals("new", graph.path("1").path("inputs").path("value").asText());
    assertEquals(1, graph.size());
  }

  /** 已注册 schema 的合法 args 原样搬运：long/decimal 不经 double，容器与标量都保留类型。 */
  @Test
  void toJsonPreservesScalarAndContainerTypes() {
    Map<String, CanvasJson> values = new LinkedHashMap<>();
    values.put("text", new JsonText("hello"));
    values.put("number", new JsonNumber(new BigDecimal("0.1")));
    values.put("huge", new JsonNumber(new BigDecimal("1e1000")));
    values.put("flag", new JsonBool(true));
    values.put("none", new JsonNull());
    values.put("array", new JsonArray(List.of(new JsonText("a"), new JsonNumber(BigDecimal.ONE))));
    values.put("object", new JsonObject(Map.of("inner", new JsonBool(false))));

    ObjectNode converted = (ObjectNode) ComfyuiGraph.toJson(new JsonObject(values));

    assertEquals("hello", converted.path("text").asText());
    assertEquals(new BigDecimal("0.1"), converted.path("number").decimalValue());
    assertEquals(new BigDecimal("1e1000"), converted.path("huge").decimalValue());
    assertTrue(converted.path("flag").booleanValue());
    assertTrue(converted.path("none").isNull());
    assertTrue(converted.path("array").isArray());
    assertEquals(1, converted.path("array").get(1).asInt());
    assertFalse(converted.path("object").path("inner").booleanValue());
  }

  private static ObjectNode graph(String json) {
    JsonNode node = JsonValues.readTree(json);
    return (ObjectNode) node;
  }
}
