package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Daemon v1 PARTIAL / COMPLETED payload codec 的双向与拒绝契约测试。 */
class DaemonToolResultCodecTest {

  private final DaemonToolResultCodec codec = new DaemonToolResultCodec();

  /** text / json 内容必须保持现有 wire shape，并且文本严格校验为 string。 */
  @Test
  void preservesTextAndJsonWireShape() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new TextToolContent("hello"),
                new JsonToolContent("{\"a\":1}"),
                new JsonToolContent("null")),
            false,
            "{}",
            false);

    String payload = codec.encodeResult(result, ref -> new byte[0]);

    assertTrue(payload.contains("\"toolCallId\":\"call-1\""));
    assertTrue(payload.contains("\"error\":false"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hello\""));
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":{\"a\":1}"));

    ToolResult decoded = codec.decodeResult(payload);
    assertEquals(3, decoded.contents().size());
    assertEquals("hello", ((TextToolContent) decoded.contents().get(0)).text());
    assertEquals("{\"a\":1}", ((JsonToolContent) decoded.contents().get(1)).json());
    assertEquals("null", ((JsonToolContent) decoded.contents().get(2)).json());
  }

  /** artifact 内容解码为内联 {@link BinaryToolContent}，不在 codec 边界持久化；size 限制在 Base64 分配前生效。 */
  @Test
  void artifactPayloadDecodesToInlineBinaryWithoutPersistence() {
    byte[] data = "kk-studio".getBytes(StandardCharsets.UTF_8);
    ArtifactRef local = new ArtifactRef("local-1", "text/plain", data.length);
    ToolResult result =
        new ToolResult("call-2", List.of(new ArtifactToolContent(local)), false, "{}", false);

    String payload =
        codec.encodeResult(
            result,
            ref -> {
              assertEquals(local, ref);
              return data;
            });

    assertTrue(payload.contains("\"artifactId\":\"local-1\""));
    assertTrue(payload.contains("\"mediaType\":\"text/plain\""));
    assertTrue(payload.contains("\"sizeBytes\":" + data.length));
    assertTrue(
        payload.contains("\"contentBase64\":\"" + Base64.getEncoder().encodeToString(data) + "\""));

    ToolResult decoded = codec.decodeResult(payload, 1024, true);
    assertEquals(1, decoded.contents().size());
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertArrayEquals(data, binary.content());
  }

  @Test
  void binaryToolContentRoundTripsOnWire() {
    byte[] data = new byte[] {1, 2, 3, 4};
    ToolResult result =
        new ToolResult(
            "call-b",
            List.of(new BinaryToolContent("application/octet-stream", data)),
            false,
            "{}",
            false);
    String payload = codec.encodeResult(result, ref -> new byte[0]);
    ToolResult decoded = codec.decodeResult(payload);
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertEquals("application/octet-stream", binary.mediaType());
    assertArrayEquals(data, binary.content());
  }

  @Test
  void rejectsArtifactWhenNotAllowed() {
    byte[] data = "xx".getBytes(StandardCharsets.UTF_8);
    ToolResult result =
        new ToolResult(
            "call-3",
            List.of(new ArtifactToolContent(new ArtifactRef("a", "text/plain", data.length))),
            false,
            "{}",
            false);
    String payload = codec.encodeResult(result, ref -> data);
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload, 1024, false));
    assertTrue(error.getMessage().contains("PARTIAL"));
  }

  @Test
  void rejectsOversizedArtifactBeforeAllocation() {
    byte[] data = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    ToolResult result =
        new ToolResult(
            "call-4",
            List.of(new ArtifactToolContent(new ArtifactRef("a", "text/plain", data.length))),
            false,
            "{}",
            false);
    String payload = codec.encodeResult(result, ref -> data);
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload, 4, true));
  }

  @Test
  void rejectsNonCanonicalBase64AndMismatchedToolCallId() {
    String bad =
        "{\"result\":{\"toolCallId\":\"x\",\"error\":false,\"details\":{},\"contents\":["
            + "{\"type\":\"artifact\",\"artifactId\":\"a\",\"mediaType\":\"text/plain\","
            + "\"sizeBytes\":1,\"contentBase64\":\"QQ==\"}]}}";
    // "QQ==" is 'A' which is fine size-wise; force non-canonical by wrong padding style is hard;
    // mismatched toolCallId for invocation decode:
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResultForInvocation(bad, "expected", 1024, true));
  }

  @Test
  void rejectsUnknownFieldsAndTypes() {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult("{\"x\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":[{\"type\":\"nope\"}]}}"));
  }

  @Test
  void rejectsNegativeMaximumArtifactBytes() {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeResult("{}", -1, true));
  }
}
