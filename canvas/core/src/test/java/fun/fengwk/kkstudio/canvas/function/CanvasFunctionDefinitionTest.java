package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 函数定义以 name/description/argsSchema 为身份，不再假设 model、provider 或 prompt 参数存在。 */
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
  void rejectsBlankDescriptionInvalidSchemaAndUnsupportedOutputKind() {
    JsonObject schema = schema();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                " ",
                schema,
                CanvasResourceKind.IMAGE,
                new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                CanvasJson.parseObject("{\"type\":\"string\"}"),
                CanvasResourceKind.IMAGE,
                new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                schema,
                CanvasResourceKind.AUDIO,
                new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of())));
    assertThrows(
        NullPointerException.class,
        () ->
            new CanvasFunctionDefinition(
                "image.crop",
                "Crop",
                null,
                CanvasResourceKind.IMAGE,
                new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of())));
  }

  @Test
  void exposesDeclaredNameDescriptionSchemaAndLimits() {
    CanvasFunctionDefinition definition = definition("image.crop");
    assertEquals("image.crop", definition.name());
    assertEquals("Crop", definition.description());
    assertEquals(CanvasResourceKind.IMAGE, definition.outputKind());
    assertEquals(schema(), definition.argsSchema());
  }

  static JsonObject schema() {
    return CanvasJson.parseObject(
        "{\"type\":\"object\",\"description\":\"crop\",\"properties\":{},\"required\":[],"
            + "\"additionalProperties\":false}");
  }

  private static CanvasFunctionDefinition definition(String name) {
    return new CanvasFunctionDefinition(
        name,
        "Crop",
        schema(),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }
}
