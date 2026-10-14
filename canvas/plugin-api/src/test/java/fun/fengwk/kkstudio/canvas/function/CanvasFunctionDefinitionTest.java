package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 函数定义以 name/description/argsSchema/outputs 为身份，不再假设 model、provider 或 prompt 参数存在。 */
class CanvasFunctionDefinitionTest {

  @Test
  void acceptsCanonicalLowercaseFunctionTokens() {
    assertDoesNotThrow(() -> definition("image.crop"));
    assertDoesNotThrow(() -> definition("minimax-h3-ref2va"));
    assertDoesNotThrow(() -> definition("seedance2.0_vip"));
  }

  @Test
  void rejectsEmptySegmentsUppercaseWhitespaceAndEdgeSeparators() {
    for (String name :
        List.of(
            ".seedance2",
            "seedance2.",
            "seedance..2",
            "seedance__2",
            "seedance--2",
            "Seedance2",
            "seedance 2",
            " seedance2",
            "seedance2 ")) {
      assertThrows(IllegalArgumentException.class, () -> definition(name), name);
    }
  }

  @Test
  void rejectsBlankDescriptionAndInvalidSchema() {
    JsonObject schema = schema();
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionDefinition("image.crop", " ", schema, singleImagePlan(), policy()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                CanvasJson.parseObject("{\"type\":\"string\"}"),
                singleImagePlan(),
                policy()));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionDefinition("image.crop", "Crop", null, singleImagePlan(), policy()));
  }

  /** 输出计划必须有界且自洽：至少一个槽位、不超过上限、显式名称不得重复；单输出函数仍可用 {@code of} 声明默认名。 */
  @Test
  void rejectsUnboundedOrInconsistentOutputPlans() {
    JsonObject schema = schema();
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionDefinition("image.crop", "Crop", schema, List.of(), policy()));
    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionDefinition("image.crop", "Crop", schema, null, policy()));
    List<CanvasFunctionOutputSpec> withNull = new ArrayList<>();
    withNull.add(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE));
    withNull.add(null);
    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionDefinition("image.crop", "Crop", schema, withNull, policy()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                schema,
                plan(CanvasFunctionOutputSpec.MAX_OUTPUTS + 1),
                policy()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                schema,
                List.of(
                    CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "same.png"),
                    CanvasFunctionOutputSpec.named(CanvasResourceKind.VIDEO, "same.png")),
                policy()));
    assertThrows(
        NullPointerException.class,
        () -> new CanvasFunctionDefinition("image.crop", "Crop", schema, singleImagePlan(), null));
  }

  /** 输出计划暴露类型与名称推导：显式名优先，缺省名由节点名加类型后缀生成。 */
  @Test
  void exposesDeclaredNameDescriptionSchemaAndOutputPlan() {
    CanvasFunctionDefinition definition = definition("image.crop");
    assertEquals("image.crop", definition.name());
    assertEquals("Crop", definition.description());
    assertEquals(
        List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)), definition.outputs());
    assertEquals(schema(), definition.argsSchema());
    assertEquals(CanvasResourceKind.IMAGE, definition.outputs().get(0).kind());
    assertNull(definition.outputs().get(0).name());

    CanvasFunctionDefinition multi =
        new CanvasFunctionDefinition(
            "fake.report",
            "Report",
            schema(),
            List.of(
                CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "report.txt"),
                CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "chart.png")),
            policy());
    assertEquals("report.txt", multi.outputs().get(0).name());
    assertEquals("chart.png", multi.outputs().get(1).name());

    CanvasFunctionOutputSpec derived = CanvasFunctionOutputSpec.of(CanvasResourceKind.VIDEO);
    assertEquals("node.mp4", derived.resolveName("node"));
    assertEquals(
        "report.txt",
        CanvasFunctionOutputSpec.named(CanvasResourceKind.TEXT, "report.txt")
            .resolveName("ignored"));
  }

  /** 资源名必须是去除首尾空白的非空文本，且不超过资源名长度上限。 */
  @Test
  void rejectsNonCanonicalOutputNames() {
    assertThrows(NullPointerException.class, () -> CanvasFunctionOutputSpec.of(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, " a.png"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionOutputSpec.named(
                CanvasResourceKind.IMAGE,
                "x".repeat(CanvasFunctionOutputSpec.MAX_NAME_LENGTH + 1)));
    assertEquals(
        CanvasFunctionOutputSpec.MAX_NAME_LENGTH,
        CanvasFunctionOutputSpec.named(
                CanvasResourceKind.IMAGE, "x".repeat(CanvasFunctionOutputSpec.MAX_NAME_LENGTH))
            .name()
            .length());
  }

  /** 超长节点名推导出的资源名仍必须满足数据库长度上限。 */
  @Test
  void derivedNamesStayWithinResourceNameLimit() {
    String derived =
        CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)
            .resolveName("n".repeat(CanvasFunctionOutputSpec.MAX_NAME_LENGTH));
    assertEquals(CanvasFunctionOutputSpec.MAX_NAME_LENGTH, derived.length());
    assertEquals(".png", derived.substring(derived.length() - 4));
  }

  static JsonObject schema() {
    return CanvasJson.parseObject(
        "{\"type\":\"object\",\"description\":\"crop\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":false}");
  }

  static CanvasFunctionReferencePolicy policy() {
    return new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of());
  }

  static List<CanvasFunctionOutputSpec> singleImagePlan() {
    return List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE));
  }

  private static List<CanvasFunctionOutputSpec> plan(int slots) {
    List<CanvasFunctionOutputSpec> outputs = new ArrayList<>();
    for (int index = 0; index < slots; index++) {
      outputs.add(
          CanvasFunctionOutputSpec.named(CanvasResourceKind.IMAGE, "out-" + index + ".png"));
    }
    return outputs;
  }

  private static CanvasFunctionDefinition definition(String name) {
    return CanvasFunctionDefinition.of(name, "Crop", schema(), CanvasResourceKind.IMAGE, policy());
  }
}
