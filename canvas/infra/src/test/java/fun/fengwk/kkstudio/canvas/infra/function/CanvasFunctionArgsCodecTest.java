package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Function args 严格 JSON 解析、Schema 校验、默认值补齐与 canonical 编码验证。 */
class CanvasFunctionArgsCodecTest {

  private final CanvasFunctionArgsCodec codec = new CanvasFunctionArgsCodec();

  /** 严格校验 args，补齐默认值并按 schema 声明顺序生成 canonical JsonObject，且 round-trip 一致。 */
  @Test
  void decodesValidArgsWithDefaultsAndRoundTrips() {
    CanvasFunctionDefinition definition = definition();
    String json =
        """
        {
          "prompt": "a cinematic video",
          "source": {
            "type": "resource",
            "nodeId": "00000000-0000-0000-0000-00000000000a",
            "index": 0
          }
        }
        """;

    JsonObject decoded = codec.decode(json, definition);
    assertEquals(new JsonText("a cinematic video"), decoded.values().get("prompt"));
    assertEquals(new JsonText("16:9"), decoded.values().get("ratio"));
    assertEquals(new JsonNumber(BigDecimal.valueOf(5)), decoded.values().get("duration"));

    String encoded = codec.encode(decoded);
    assertEquals(
        "{\"prompt\":\"a cinematic video\",\"ratio\":\"16:9\",\"duration\":5,"
            + "\"source\":{\"type\":\"resource\","
            + "\"nodeId\":\"00000000-0000-0000-0000-00000000000a\",\"index\":0}}",
        encoded);

    JsonObject reDecoded = codec.decode(encoded, definition);
    assertEquals(decoded, reDecoded);
  }

  /** 空输入、非对象 JSON、未知字段、缺失必填字段与错误类型都必须被严格拒绝。 */
  @Test
  void rejectsInvalidAndNonStrictShapes() {
    CanvasFunctionDefinition definition = definition();

    assertThrows(IllegalArgumentException.class, () -> codec.decode(null, definition));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("", definition));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("   ", definition));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("[]", definition));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("123", definition));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("not-json", definition));

    // 未知字段
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video",
                  "source": {"type":"resource","nodeId":"00000000-0000-0000-0000-00000000000a","index":0},
                  "extra": 123
                }
                """,
                definition));

    // 缺失必填字段 prompt
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "source": {"type":"resource","nodeId":"00000000-0000-0000-0000-00000000000a","index":0}
                }
                """,
                definition));

    // 缺失必填字段 source
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video"
                }
                """,
                definition));

    // 错误类型：duration 应为整数
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video",
                  "source": {"type":"resource","nodeId":"00000000-0000-0000-0000-00000000000a","index":0},
                  "duration": "five"
                }
                """,
                definition));

    // 越界值：duration minimum=1, maximum=15
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video",
                  "source": {"type":"resource","nodeId":"00000000-0000-0000-0000-00000000000a","index":0},
                  "duration": 20
                }
                """,
                definition));

    // 非法 enum 值
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video",
                  "source": {"type":"resource","nodeId":"00000000-0000-0000-0000-00000000000a","index":0},
                  "ratio": "4:3"
                }
                """,
                definition));

    // 损坏的 resourceReference
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                """
                {
                  "prompt": "video",
                  "source": {"type":"image","nodeId":"00000000-0000-0000-0000-00000000000a","index":0}
                }
                """,
                definition));
  }

  private static CanvasFunctionDefinition definition() {
    JsonObject schema =
        CanvasJson.parseObject(
            """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["prompt", "source"],
              "properties": {
                "prompt": { "type": "string" },
                "ratio": { "type": "string", "enum": ["16:9", "1:1"], "default": "16:9" },
                "duration": { "type": "integer", "minimum": 1, "maximum": 15, "default": 5 },
                "source": { "type": "resourceReference" }
              }
            }
            """);
    return new CanvasFunctionDefinition(
        "video.generate",
        "Video generator",
        schema,
        CanvasResourceKind.VIDEO,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }
}
