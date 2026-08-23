package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.ReferenceSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Function config strict wire、canonical prompt 与 descriptor 参数验证。 */
class CanvasFunctionConfigCodecTest {

  private static final UUID REFERENCE_NODE =
      UUID.fromString("00000000-0000-0000-0000-00000000000a");

  private final CanvasFunctionConfigCodec codec = new CanvasFunctionConfigCodec(new ObjectMapper());

  /** 相邻文本合并、空文本删除，但重复 mention 仍保留在 prompt 且 manifest 首次出现去重。 */
  @Test
  void canonicalizesPromptAndDeduplicatesManifestOnly() {
    CanvasFunctionConfig config =
        codec.decode(
            """
            {
              "prompt":{"segments":[
                {"type":"TEXT","text":"hello"},
                {"type":"TEXT","text":""},
                {"type":"TEXT","text":" world"},
                {"type":"REFERENCE","nodeId":"00000000-0000-0000-0000-00000000000a","index":0},
                {"type":"REFERENCE","nodeId":"00000000-0000-0000-0000-00000000000a","index":0}
              ]},
              "parameters":{}
            }
            """,
            model());

    assertEquals(
        List.of(
            new TextSegment("hello world"),
            new ReferenceSegment(REFERENCE_NODE, 0),
            new ReferenceSegment(REFERENCE_NODE, 0)),
        config.segments());
    assertEquals(List.of(new ReferenceSegment(REFERENCE_NODE, 0)), codec.uniqueReferences(config));
    assertEquals(Map.of("ratio", "AUTO", "duration", 5), config.parameters());
    assertEquals(config, codec.decode(codec.encode(config), model()));
  }

  /** 所有对象层拒绝未知/null/错误类型/重复字段，REFERENCE id/index 保持 canonical。 */
  @Test
  void rejectsNonStrictShapes() {
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{},"extra":1}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x","nodeId":"1"}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"REFERENCE","nodeId":"01","index":0},
                               {"type":"TEXT","text":"x"}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"REFERENCE","nodeId":"1","index":-1},
                               {"type":"TEXT","text":"x"}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"text","text":"x"}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":null}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x","text":"y"}]},"parameters":{}}
        """);
    assertInvalid("""
        {"prompt":{"segments":[]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"REFERENCE","nodeId":"1","index":0}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"   "}]},"parameters":{}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":null}
        """);
  }

  /** descriptor 拒绝额外参数、字符串整数、越界整数与非法 enum。 */
  @Test
  void validatesParametersAgainstDescriptor() {
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{"legacy":1}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{"duration":"5"}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{"duration":16}}
        """);
    assertInvalid(
        """
        {"prompt":{"segments":[{"type":"TEXT","text":"x"}]},"parameters":{"ratio":"wide"}}
        """);
  }

  private void assertInvalid(String json) {
    assertThrows(IllegalArgumentException.class, () -> codec.decode(json, model()));
  }

  private static CanvasFunctionModel model() {
    return new CanvasFunctionModel(
        "test-image",
        "Test Image",
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 12, Map.of()),
        List.of(
            CanvasFunctionParameterDefinition.enumParameter(
                "ratio", "Ratio", false, "AUTO", List.of("AUTO", "1:1")),
            CanvasFunctionParameterDefinition.integerParameter(
                "duration", "Duration", false, 5, 4, 15)));
  }
}
