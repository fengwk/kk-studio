package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;

/** args schema 是插件声明与核心校验之间唯一契约：schema 本身与 args 都必须 fail closed。 */
class CanvasFunctionArgsSchemaTest {

  private static final String CROP_SCHEMA =
      """
      {"type":"object","description":"crop","additionalProperties":false,
       "required":["source","x","y","width","height"],
       "properties":{
         "source":{"type":"resourceReference"},
         "x":{"type":"integer","minimum":0},
         "y":{"type":"integer","minimum":0},
         "width":{"type":"integer","minimum":1},
         "height":{"type":"integer","minimum":1}}
      }
      """;

  @Test
  void acceptsSupportedSchemaSubsetAndFillsDefaultsInDeclarationOrder() {
    JsonObject schema =
        schema(
            """
            {"type":"object","additionalProperties":false,"required":["prompt"],
             "properties":{
               "prompt":{"type":"string"},
               "ratio":{"type":"string","enum":["16:9","1:1"],"default":"16:9"},
               "duration":{"type":"integer","minimum":1,"maximum":15,"default":5},
               "references":{"type":"array","minItems":0,"maxItems":2,
                 "items":{"type":"resourceReference"}}}}
            """);
    assertDoesNotThrow(() -> CanvasFunctionArgsSchema.validate(schema));

    JsonObject normalized =
        CanvasFunctionArgsSchema.normalize(
            schema(
                """
                {"prompt":"p",
                 "references":[{"type":"resource","nodeId":"00000000-0000-0000-0000-000000000001","index":0}]}
                """),
            schema,
            "args");
    assertEquals(
        "{\"prompt\":\"p\",\"ratio\":\"16:9\",\"duration\":5,"
            + "\"references\":[{\"type\":\"resource\","
            + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":0}]}",
        normalized.write());
  }

  @Test
  void rejectsMalformedSchemas() {
    for (String schema :
        new String[] {
          "{\"type\":\"string\",\"additionalProperties\":false,\"properties\":{}}",
          "{\"type\":\"object\",\"properties\":{}}",
          "{\"type\":\"object\",\"additionalProperties\":true,\"properties\":{}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{},\"unknown\":1}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{},\"required\":[\"a\"]}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{},\"required\":[\"a\",\"a\"]}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"datetime\"}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"object\"}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"array\"}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"array\",\"maxItems\":33,"
              + "\"items\":{\"type\":\"string\"}}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":1,"
              + "\"items\":{\"type\":\"string\"}}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"array\","
              + "\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"string\",\"minItems\":1}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"string\",\"minimum\":1}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"string\",\"enum\":[]}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"string\",\"enum\":[\"x\",\"x\"]}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"array\",\"enum\":[\"x\"],"
              + "\"items\":{\"type\":\"string\"}}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"resourceReference\",\"minimum\":1}}}",
          "{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],"
              + "\"properties\":{\"a\":{\"type\":\"integer\",\"default\":\"x\"}}}",
          CROP_SCHEMA.replace("\"source\":{\"type\":\"resourceReference\"}", "\"source\":\"x\"")
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> CanvasFunctionArgsSchema.validate(schema(schema)),
          schema);
    }
    assertThrows(NullPointerException.class, () -> CanvasFunctionArgsSchema.validate(null));
  }

  @Test
  void rejectsUnknownRequiredAndMistypedArgs() {
    JsonObject schema = schema(CROP_SCHEMA);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"x\":0,\"y\":0,\"width\":1,\"height\":1}"), schema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema(
                    "{\"source\":{\"type\":\"resource\","
                        + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":0},"
                        + "\"x\":0,\"y\":0,\"width\":1,\"height\":1,\"extra\":1}"),
                schema,
                "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema(
                    "{\"source\":{\"type\":\"resource\","
                        + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":0},"
                        + "\"x\":1.5,\"y\":0,\"width\":1,\"height\":1}"),
                schema,
                "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema(
                    "{\"source\":{\"type\":\"resource\","
                        + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":0},"
                        + "\"x\":-1,\"y\":0,\"width\":1,\"height\":1}"),
                schema,
                "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"x\":1,\"y\":0,\"width\":1,\"height\":1}"), schema, "args"));
  }

  @Test
  void rejectsMalformedResourceReferencesAndArrayBoundsAndEnum() {
    JsonObject referenceSchema =
        schema(
            """
            {"type":"object","additionalProperties":false,"required":["source"],
             "properties":{"source":{"type":"resourceReference"}}}
            """);
    for (String args :
        new String[] {
          "{\"source\":{\"type\":\"resource\"}}",
          "{\"source\":{\"type\":\"image\",\"nodeId\":"
              + "\"00000000-0000-0000-0000-000000000001\",\"index\":0}}",
          "{\"source\":{\"type\":\"resource\",\"nodeId\":\"nope\",\"index\":0}}",
          "{\"source\":{\"type\":\"resource\","
              + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":-1}}",
          "{\"source\":{\"type\":\"resource\","
              + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\",\"index\":1.5}}",
          "{\"source\":{\"type\":\"resource\","
              + "\"nodeId\":\"00000000-0000-0000-0000-000000000001\"}}",
          "{\"source\":[]}"
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> CanvasFunctionArgsSchema.normalize(schema(args), referenceSchema, "args"),
          args);
    }

    JsonObject arraySchema =
        schema(
            """
            {"type":"object","additionalProperties":false,"required":["items"],
             "properties":{"items":{"type":"array","minItems":1,"maxItems":2,
               "items":{"type":"string"}}}}
            """);
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(schema("{\"items\":[]}"), arraySchema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"items\":[\"a\",\"b\",\"c\"]}"), arraySchema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"items\":[\"a\",1]}"), arraySchema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(schema("{\"items\":\"a\"}"), arraySchema, "args"));

    JsonObject enumSchema =
        schema(
            """
            {"type":"object","additionalProperties":false,"required":["ratio"],
             "properties":{"ratio":{"type":"string","enum":["1:1","16:9"]}}}
            """);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(schema("{\"ratio\":\"4:3\"}"), enumSchema, "args"));
    assertDoesNotThrow(
        () ->
            CanvasFunctionArgsSchema.normalize(schema("{\"ratio\":\"16:9\"}"), enumSchema, "args"));

    JsonObject booleanAndNumberSchema =
        schema(
            """
            {"type":"object","additionalProperties":false,"required":["flag","scale"],
             "properties":{"flag":{"type":"boolean"},"scale":{"type":"number","minimum":0.5,
               "maximum":1.5}}}
            """);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"flag\":\"true\",\"scale\":1}"), booleanAndNumberSchema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"flag\":true,\"scale\":2}"), booleanAndNumberSchema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionArgsSchema.normalize(
                schema("{\"flag\":true,\"scale\":0.1}"), booleanAndNumberSchema, "args"));
    assertEquals(
        "{\"flag\":true,\"scale\":1}",
        CanvasFunctionArgsSchema.normalize(
                schema("{\"flag\":true,\"scale\":1.0}"), booleanAndNumberSchema, "args")
            .write());
  }

  @Test
  void rejectsNonObjectAndNullArgs() {
    JsonObject schema = schema(CROP_SCHEMA);
    assertThrows(
        NullPointerException.class, () -> CanvasFunctionArgsSchema.normalize(null, schema, "args"));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.validate(JsonObject.empty()));
  }

  private static JsonObject schema(String json) {
    return CanvasJson.parseObject(json);
  }
}
