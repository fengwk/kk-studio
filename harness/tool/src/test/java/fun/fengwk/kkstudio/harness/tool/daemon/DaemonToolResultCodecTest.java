package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    ToolResult decoded = codec.decodeResult(payload, (mt, s, b) -> null);
    assertEquals(3, decoded.contents().size());
    assertEquals("hello", ((TextToolContent) decoded.contents().get(0)).text());
    assertEquals("{\"a\":1}", ((JsonToolContent) decoded.contents().get(1)).json());
    assertEquals("null", ((JsonToolContent) decoded.contents().get(2)).json());
  }

  /**
   * artifact 内容必须带 {@code contentBase64}，解码后长度等于 {@code sizeBytes}；接收端用 reader 返回的全局 ref 替换本地 ref。
   */
  @Test
  void artifactPayloadBase64RoundTripsAndRewritesRef() {
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

    AtomicInteger seq = new AtomicInteger();
    byte[] captured = new byte[data.length];
    ToolResult decoded =
        codec.decodeResult(
            payload,
            (mediaType, size, bytes) -> {
              assertEquals("text/plain", mediaType);
              assertEquals(data.length, size);
              System.arraycopy(bytes, 0, captured, 0, bytes.length);
              return new ArtifactRef("global-" + seq.incrementAndGet(), mediaType, size);
            });

    assertEquals(1, decoded.contents().size());
    ArtifactToolContent decodedArtifact = (ArtifactToolContent) decoded.contents().get(0);
    assertEquals("global-1", decodedArtifact.artifact().artifactId());
    assertNotEquals(local.artifactId(), decodedArtifact.artifact().artifactId());
    assertArrayEquals(data, captured);
  }

  /** reader 返回 null 必须抛出 {@link DaemonProtocolException}。 */
  @Test
  void rejectsNullArtifactReaderResult() {
    byte[] data = new byte[] {1};
    ToolResult result =
        new ToolResult(
            "call-3",
            List.of(new ArtifactToolContent(new ArtifactRef("local", "application/json", 1))),
            false,
            "{}",
            false);
    String payload = codec.encodeResult(result, ref -> data);

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, (mt, s, b) -> null));
    assertTrue(error.getMessage().contains("artifact reader returned null ref"));
  }

  /** reader 返回与 wire media type 或长度不一致的 ref 必须被拒绝。 */
  @Test
  void rejectsArtifactReaderMetadataMismatch() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,\"contentBase64\":\"YQ==\"}]}}";

    DaemonProtocolException mediaTypeError =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    payload,
                    (mediaType, size, bytes) ->
                        new ArtifactRef("global", "application/json", size)));
    assertTrue(mediaTypeError.getMessage().contains("mismatched mediaType"));

    DaemonProtocolException sizeError =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    payload,
                    (mediaType, size, bytes) -> new ArtifactRef("global", mediaType, size + 1)));
    assertTrue(sizeError.getMessage().contains("mismatched sizeBytes"));
  }

  /** artifact 发端返回 null bytes 不能产生不完整 wire payload。 */
  @Test
  void rejectsNullArtifactWriterBytes() {
    ToolResult result =
        new ToolResult(
            "call",
            List.of(new ArtifactToolContent(new ArtifactRef("x", "text/plain", 0))),
            false,
            "{}",
            false);
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.encodeResult(result, ref -> null));
    assertTrue(error.getMessage().contains("null bytes"));
  }

  /** 非法 Base64 必须在解码边界拒绝。 */
  @Test
  void rejectsMalformedBase64() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":2,\"contentBase64\":\"@@@@\"}]}}";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader()));
    assertTrue(error.getMessage().contains("contentBase64"));
  }

  /** Base64 解码后长度不匹配 {@code sizeBytes} 必须被拒绝。 */
  @Test
  void rejectsBase64LengthMismatch() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":99,\"contentBase64\":\"YQ==\"}]}}";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader()));
    assertTrue(error.getMessage().contains("sizeBytes"));
  }

  /** Base64 文本长度必须先与声明大小匹配，避免伪造小 sizeBytes 时分配超额字节数组。 */
  @Test
  void rejectsOversizedBase64BeforeDecoding() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,\"contentBase64\":\"YWFhYQ==\"}]}}";

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader(), 8));

    assertTrue(error.getMessage().contains("canonical Base64"));
    assertTrue(error.getMessage().contains("sizeBytes"));
  }

  /** 缺少 padding 的 Base64 与发端 canonical 形式不一致，必须拒绝。 */
  @Test
  void rejectsNonCanonicalBase64() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,\"contentBase64\":\"YQ\"}]}}";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader()));
    assertTrue(error.getMessage().contains("canonical Base64"));
  }

  /** 零字节 artifact 的 canonical Base64 是空字符串，必须能完整 round-trip。 */
  @Test
  void preservesEmptyArtifact() {
    ArtifactRef local = new ArtifactRef("local-empty", "application/octet-stream", 0);
    ToolResult result =
        new ToolResult("call-empty", List.of(new ArtifactToolContent(local)), false, "{}", false);
    String payload = codec.encodeResult(result, ref -> new byte[0]);

    ToolResult decoded =
        codec.decodeResult(
            payload,
            (mediaType, size, bytes) -> {
              assertEquals(0, size);
              assertEquals(0, bytes.length);
              return new ArtifactRef("global-empty", mediaType, size);
            });
    ArtifactToolContent artifact = (ArtifactToolContent) decoded.contents().get(0);
    assertEquals("global-empty", artifact.artifact().artifactId());
  }

  /** artifact 上限必须在 Base64 解码与 receiver 写入前按声明 sizeBytes 拒绝。 */
  @Test
  void rejectsArtifactLargerThanConfiguredLimitBeforeDecode() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":2,\"contentBase64\":\"!\"}]}}";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader(), 1));
    assertTrue(error.getMessage().contains("exceeds maximumArtifactBytes"));
  }

  /** 声明大小恰等于上限时仍按常规 Base64 与 receiver 契约处理。 */
  @Test
  void acceptsArtifactAtConfiguredLimit() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,\"contentBase64\":\"YQ==\"}]}}";
    ToolResult decoded =
        codec.decodeResult(
            payload, (mediaType, size, bytes) -> new ArtifactRef("global", mediaType, size), 1);
    assertEquals(
        "global", ((ArtifactToolContent) decoded.contents().get(0)).artifact().artifactId());
  }

  /**
   * A callback for another invocation is rejected before its artifact reader can create storage.
   */
  @Test
  void rejectsUnexpectedInvocationBeforePersistingArtifacts() {
    String payload =
        "{\"result\":{\"toolCallId\":\"other\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\","
            + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,\"contentBase64\":\"YQ==\"}]}}";

    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodeResultForInvocation(payload, "expected", failReader(), 1));

    assertTrue(error.getMessage().contains("toolCallId"));
  }

  /**
   * Invocation-bound decoding requires an expected id and keeps the configured size bound valid.
   */
  @Test
  void validatesInvocationBoundDecodeArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeResultForInvocation("{}", " ", failReader(), 1));
    assertThrows(IllegalArgumentException.class, () -> codec.decodeResult("{}", failReader(), -1));
  }

  /** 缺失字段、未知字段、未知 type 都必须在 wire 边界明确拒绝。 */
  @Test
  void rejectsMissingUnknownAndOversizedFields() {
    assertMissingField(
        "{\"result\":{\"error\":false,\"details\":{},\"contents\":[]}}", "toolCallId");
    assertMissingField(
        "{\"result\":{\"toolCallId\":\"c\",\"details\":{},\"contents\":[]}}", "error");
    assertMissingField(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"contents\":[]}}", "details");
    assertMissingField(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{}}}", "contents");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\",\"mediaType\":\"t\","
            + "\"sizeBytes\":1}]}}");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\",\"mediaType\":\"t\","
            + "\"sizeBytes\":1,\"contentBase64\":\"YQ==\",\"extra\":1}]}}");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"unknown\"}]}}");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\"}]}}");
    assertProtocolError("{\"toolCallId\":\"c\",\"error\":false,\"details\":{},\"contents\":[]}");
  }

  /** 非对象的 details、contents、result 必须在边界拒绝。 */
  @Test
  void rejectsMalformedResultShape() {
    assertProtocolError("{}");
    assertProtocolError("{\"result\":\"not-an-object\"}");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":[],\"contents\":[]}}");
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},\"contents\":\"nope\"}}");
    assertProtocolError("[]");
    assertProtocolError("not-json");
  }

  /** text 内容缺失 text 字段必须拒绝。 */
  @Test
  void rejectsMissingTextField() {
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\"}]}}");
  }

  /** json 内容缺失 json 字段必须拒绝。 */
  @Test
  void rejectsMissingJsonField() {
    assertProtocolError(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"json\"}]}}");
  }

  /** {@code sizeBytes} 为负必须拒绝。 */
  @Test
  void rejectsNegativeSizeBytes() {
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"x\",\"mediaType\":\"t\","
            + "\"sizeBytes\":-1,\"contentBase64\":\"\"}]}}";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.decodeResult(payload, failReader()));
    assertTrue(error.getMessage().contains("sizeBytes"));
  }

  /** writer 抛 {@link IOException} 必须包装为 {@link DaemonProtocolException}。 */
  @Test
  void wrapsWriterIOException() {
    ToolResult result =
        new ToolResult(
            "call",
            List.of(new ArtifactToolContent(new ArtifactRef("x", "application/json", 1))),
            false,
            "{}",
            false);
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeResult(
                    result,
                    ref -> {
                      throw new IOException("disk gone");
                    }));
    assertTrue(error.getMessage().contains("x"));
  }

  /** writer 返回 size 与 ref 不一致的字节必须被拒绝。 */
  @Test
  void writerSizeMismatchIsRejected() {
    ToolResult result =
        new ToolResult(
            "call",
            List.of(new ArtifactToolContent(new ArtifactRef("x", "application/json", 5))),
            false,
            "{}",
            false);
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeResult(result, ref -> new byte[] {1, 2}));
    assertTrue(error.getMessage().contains("size mismatch"));
  }

  /** 空 contents 数组必须合法，且 decoder 不会调用 reader。 */
  @Test
  void emptyContentsAreCanonical() {
    ToolResult result = new ToolResult("call", List.of(), false, "{}", false);
    String payload = codec.encodeResult(result, ref -> new byte[0]);
    ToolResult decoded = codec.decodeResult(payload, failReader());
    assertEquals(0, decoded.contents().size());
  }

  private static DaemonArtifactContentReader failReader() {
    return (mediaType, size, bytes) -> {
      throw new AssertionError("reader must not be called for this test");
    };
  }

  private void assertProtocolError(String json) {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(json, failReader()));
  }

  private void assertMissingField(String json, String field) {
    DaemonProtocolException error = null;
    try {
      codec.decodeResult(json, failReader());
    } catch (DaemonProtocolException ex) {
      error = ex;
    }
    if (error == null) {
      throw new AssertionError("expected DaemonProtocolException for missing field: " + field);
    }
    if (!error.getMessage().contains(field)) {
      throw new AssertionError(
          "expected error message to mention '" + field + "', got: " + error.getMessage());
    }
  }
}
