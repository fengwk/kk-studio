package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Daemon wire PARTIAL / COMPLETED payload codec 的双向与拒绝契约测试。 */
class DaemonCapabilityResultCodecTest {

  private static final String EXPORT_URI = "file:///export/abc";
  private static final String SHA_HELLO =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  private final DaemonCapabilityResultCodec codec = new DaemonCapabilityResultCodec();

  /** text / json 内容必须保持现有 wire shape，并且文本严格校验为 string。 */
  @Test
  void preservesTextAndJsonWireShape() {
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-1",
            List.of(
                new TextResultContent("hello"),
                new JsonResultContent("{\"a\":1}"),
                new JsonResultContent("null")),
            false,
            "{}");

    String payload = codec.encodeCompleted(result, new NoopStore());

    assertTrue(payload.contains("\"callId\":\"call-1\""));
    assertTrue(payload.contains("\"error\":false"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hello\""));
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":{\"a\":1}"));

    EnvironmentCapabilityResult decoded = codec.decodeResult(payload);
    assertEquals(3, decoded.contents().size());
    assertEquals("hello", ((TextResultContent) decoded.contents().get(0)).text());
    assertEquals("{\"a\":1}", ((JsonResultContent) decoded.contents().get(1)).json());
    assertEquals("null", ((JsonResultContent) decoded.contents().get(2)).json());
  }

  /** resource 内容编码为 flat 精确字段 + contentBase64；解码还原为内联 BinaryResultContent，不触碰持久化。 */
  @Test
  void resourcePayloadEncodesFlatFieldsAndDecodesToInlineBinary() {
    byte[] data = "kk-studio".getBytes(StandardCharsets.UTF_8);
    ResourceRef local =
        new ResourceRef(EXPORT_URI, "text/plain", "kk.txt", (long) data.length, sha256Hex(data));
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-2", List.of(new ResourceResultContent(local)), false, "{}");

    String payload =
        codec.encodeCompleted(
            result,
            new DaemonResourceStore() {
              @Override
              public DaemonResourceRef store(byte[] bytes, String mediaType) {
                throw new AssertionError("store must not be called for resource content");
              }

              @Override
              public byte[] read(DaemonResourceRef ref) {
                assertEquals(local.uri(), ref.uri());
                return data;
              }
            });

    assertTrue(payload.contains("\"type\":\"resource\""));
    assertTrue(payload.contains("\"uri\":\"" + EXPORT_URI + "\""));
    assertTrue(payload.contains("\"mediaType\":\"text/plain\""));
    assertTrue(payload.contains("\"name\":\"kk.txt\""));
    assertTrue(payload.contains("\"size\":" + data.length));
    assertTrue(payload.contains("\"sha256\":\"" + sha256Hex(data) + "\""));
    assertTrue(
        payload.contains("\"contentBase64\":\"" + Base64.getEncoder().encodeToString(data) + "\""));

    EnvironmentCapabilityResult decoded =
        codec.decodeCompletedForInvocation(payload, "call-2", 1024);
    assertEquals(1, decoded.contents().size());
    BinaryResultContent binary = (BinaryResultContent) decoded.contents().get(0);
    assertEquals(local.mediaType(), binary.mediaType());
    assertArrayEquals(data, binary.content());
    assertNull(binary.textMetadata());
  }

  /** resource 的完整 bytes 与 TextArtifactMetadata 必须跨 wire 保留；Daemon 本地 URI 和 preview 不进入接收结果。 */
  @Test
  void resourcePayloadRoundTripsWithPreviewAndTextArtifactMetadata() {
    byte[] data = "hello world\nsecond line\n".getBytes(StandardCharsets.UTF_8);
    ResourceRef local =
        new ResourceRef(EXPORT_URI, "text/plain", "log.txt", (long) data.length, sha256Hex(data));
    TextArtifactMetadata metadata = new TextArtifactMetadata(data.length, 2);
    ResourceResultContent content = new ResourceResultContent(local, "hello world\n", metadata);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult("call-rt", List.of(content), false, "{}");

    String payload = codec.encodeCompleted(result, new StubStore(data, null));
    assertTrue(payload.contains("\"preview\":\"hello world\\n\""));
    assertTrue(
        payload.contains("\"textMetadata\":{\"totalBytes\":" + data.length + ",\"totalLines\":2}"));

    EnvironmentCapabilityResult decoded =
        codec.decodeCompletedForInvocation(payload, "call-rt", 1024);
    assertEquals(1, decoded.contents().size());
    BinaryResultContent decodedContent = (BinaryResultContent) decoded.contents().get(0);
    assertEquals(local.mediaType(), decodedContent.mediaType());
    assertArrayEquals(data, decodedContent.content());
    assertEquals(metadata, decodedContent.textMetadata());
  }

  /** resource 的 preview 超限或包含非法 Unicode 时在解码期被协议异常拒绝。 */
  @Test
  void rejectsOversizedOrInvalidPreviewOnDecode() {
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    String validBase64 = Base64.getEncoder().encodeToString(data);
    String hugePreview = "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1);
    String payload =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
            + "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + validBase64
            + "\",\"preview\":\""
            + hugePreview
            + "\"}]}}";

    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload));
  }

  /** resource 的 textMetadata 字段包含负数或未知字段时被严格拒绝。 */
  @Test
  void rejectsInvalidTextMetadataOnDecode() {
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    String validBase64 = Base64.getEncoder().encodeToString(data);
    String negativeBytes =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
            + "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + validBase64
            + "\",\"textMetadata\":{\"totalBytes\":-1,\"totalLines\":1}}]}}";

    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(negativeBytes));

    String unknownField =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
            + "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + validBase64
            + "\",\"textMetadata\":{\"totalBytes\":1,\"totalLines\":1,\"extra\":true}}]}}";

    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(unknownField));

    String mismatchedBytes =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
            + "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + validBase64
            + "\",\"textMetadata\":{\"totalBytes\":2,\"totalLines\":1}}]}}";

    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(mismatchedBytes));
  }

  /** 编码 ResourceResultContent 时 store 返回字节必须通过 size/sha 复核；读取失败确定性抛协议异常。 */
  @Test
  void encodeRejectsStoreMismatchAndReadFailures() {
    byte[] data = "kk-studio".getBytes(StandardCharsets.UTF_8);
    ResourceRef wrongSize =
        new ResourceRef(EXPORT_URI, "text/plain", null, (long) data.length + 1, sha256Hex(data));

    DaemonProtocolException sizeMismatch =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    new EnvironmentCapabilityResult(
                        "c", List.of(new ResourceResultContent(wrongSize)), false, "{}"),
                    new StubStore(data, null)));
    assertTrue(sizeMismatch.getMessage().contains("store size mismatch"));

    ResourceRef ref =
        new ResourceRef(EXPORT_URI, "text/plain", null, (long) data.length, sha256Hex(data));

    ResourceRef wrongSha =
        new ResourceRef(
            EXPORT_URI,
            "text/plain",
            null,
            (long) data.length,
            sha256Hex("other".getBytes(StandardCharsets.UTF_8)));
    DaemonProtocolException shaMismatch =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    new EnvironmentCapabilityResult(
                        "c", List.of(new ResourceResultContent(wrongSha)), false, "{}"),
                    new StubStore(data, null)));
    assertTrue(shaMismatch.getMessage().contains("store sha256 mismatch"));

    DaemonProtocolException readFailure =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    new EnvironmentCapabilityResult(
                        "c", List.of(new ResourceResultContent(ref)), false, "{}"),
                    new StubStore(
                        null, new IOException("resource gone at file:///private/secret"))));
    assertTrue(readFailure.getMessage().contains("cannot read resource bytes"));
    assertNoThrowableMessageContains(readFailure, "file:///private/secret");
  }

  /** 编码 BinaryResultContent 必须先经 store 落盘，再编码返回的 resource 引用。 */
  @Test
  void binaryResultContentIsStoredThenEncodedAsResource() {
    byte[] data = new byte[] {1, 2, 3, 4};
    ResourceRef stored =
        new ResourceRef(EXPORT_URI, "application/octet-stream", null, 4L, sha256Hex(data));
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-b",
            List.of(new BinaryResultContent("application/octet-stream", data)),
            false,
            "{}");

    String payload =
        codec.encodeCompleted(
            result,
            new DaemonResourceStore() {
              @Override
              public DaemonResourceRef store(byte[] bytes, String mediaType) {
                assertArrayEquals(data, bytes);
                assertEquals("application/octet-stream", mediaType);
                return new DaemonResourceRef(
                    stored.uri(),
                    stored.mediaType(),
                    stored.name(),
                    stored.size(),
                    stored.sha256());
              }

              @Override
              public byte[] read(DaemonResourceRef ref) {
                throw new AssertionError("read must not be called for binary content");
              }
            });

    assertTrue(payload.contains("\"type\":\"resource\""));
    assertTrue(payload.contains("\"uri\":\"" + EXPORT_URI + "\""));
    EnvironmentCapabilityResult decoded = codec.decodeResult(payload);
    BinaryResultContent binary = (BinaryResultContent) decoded.contents().get(0);
    assertEquals(stored.mediaType(), binary.mediaType());
    assertArrayEquals(data, binary.content());

    DaemonProtocolException storeFailure =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(result, new StubStore(null, new IOException("store down"))));
    assertTrue(storeFailure.getMessage().contains("cannot store binary result content"));
  }

  /** http(s) resource 的 size/sha256 同样是必填 wire 字段；解码只保留已校验字节，不传播外部 URI。 */
  @Test
  void decodesHttpResourceWithRequiredSizeAndSha() {
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    String segment =
        "{\"type\":\"resource\",\"uri\":\"https://example.com/a\","
            + "\"mediaType\":\"text/plain\",\"name\":null,\"size\":"
            + data.length
            + ",\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + Base64.getEncoder().encodeToString(data)
            + "\"}";

    EnvironmentCapabilityResult decoded =
        codec.decodeResult(
            "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                + "\"contents\":["
                + segment
                + "]}}");

    BinaryResultContent binary = (BinaryResultContent) decoded.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertArrayEquals(data, binary.content());
    assertNull(binary.textMetadata());

    // size/sha256 缺失或为 null 必须拒绝，不允许以 null 声明绕过大小预检。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":["
                    + segment.replace("\"size\":1,", "")
                    + "]}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":["
                    + segment.replace("\"sha256\":\"" + sha256Hex(data) + "\"", "\"sha256\":null")
                    + "]}}"));
  }

  /** PARTIAL（allowResources=false）必须在任何回调前拒绝 resource 内容。 */
  @Test
  void rejectsResourceWhenNotAllowed() {
    byte[] data = "xx".getBytes(StandardCharsets.UTF_8);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-3",
            List.of(
                new ResourceResultContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 2L, sha256Hex(data)))),
            false,
            "{}");
    String payload = codec.encodeCompleted(result, new StubStore(data, null));
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodePartialForInvocation(payload, "call-3", 1024));
    assertTrue(error.getMessage().contains("PARTIAL"));
  }

  /** 单 item 声明 size 超限必须在 Base64 分配前拒绝。 */
  @Test
  void rejectsOversizedResourceBeforeAllocation() {
    byte[] data = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-4",
            List.of(
                new ResourceResultContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 8L, sha256Hex(data)))),
            false,
            "{}");
    String payload = codec.encodeCompleted(result, new StubStore(data, null));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeCompletedForInvocation(payload, "call-4", 4));
  }

  /** contents 元素数上限 64，防止内容列表无限放大。 */
  @Test
  void rejectsMoreThanSixtyFourContents() {
    StringBuilder contents = new StringBuilder();
    for (int index = 0; index < EnvironmentCapabilityResult.MAX_CONTENT_ITEMS + 1; index++) {
      if (contents.length() > 0) {
        contents.append(',');
      }
      contents.append("{\"type\":\"text\",\"text\":\"x\"}");
    }
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
                        + contents
                        + "]}}"));
    assertTrue(error.getMessage().contains("must not exceed"));
  }

  /** resource name 必须满足 ResourceRef 规范（可选非空白、有界 UTF-8、无 control）。 */
  @Test
  void decodesResourceNameUnderUtf8Cap() {
    byte[] data = "a".getBytes(StandardCharsets.UTF_8);
    String segment =
        "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\",\"name\":\"my-file.txt\",\"size\":1,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\"YQ==\"}";
    EnvironmentCapabilityResult decoded =
        codec.decodeResult(
            "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
                + segment
                + "]}}");
    assertEquals(1, decoded.contents().size());

    // control 字符在 name 中拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
                    + segment.replace("my-file.txt", "my\\u0000file.txt")
                    + "]}}"));
  }

  /** Base64 填充和长度必须符合 RFC 4648 basic（无换行、无空格）。 */
  @Test
  void rejectsNonCanonicalBase64() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                wire(
                    resource(
                        EXPORT_URI, "text/plain", 1L, sha256Hex(new byte[] {0x61}), " YQ==\n"))));
  }

  /** Base64 解码长度与 sha256 必须与声明一致。 */
  @Test
  void rejectsMismatchedDecodedSizeAndSha() {
    byte[] real = new byte[] {1, 2, 3};
    String realB64 = Base64.getEncoder().encodeToString(real);
    // 声明 size=4 但实际 Base64 解码出 3 字节。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                wire(
                    resource(
                        EXPORT_URI, "application/octet-stream", 4L, sha256Hex(real), realB64))));

    // 声明 sha256 与 Base64 实际内容不符。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                wire(
                    resource(
                        EXPORT_URI, "application/octet-stream", 3L, "0".repeat(64), realB64))));
  }

  /** 聚合 resource 字节超过预算在累加时拒绝。 */
  @Test
  void rejectsAggregateResourceBytesExceedingBudget() {
    byte[] chunk = new byte[] {1, 2, 3};
    String chunkB64 = Base64.getEncoder().encodeToString(chunk);
    String sha = sha256Hex(chunk);
    String payload =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":["
            + resource(EXPORT_URI + "/1", "application/octet-stream", 3L, sha, chunkB64)
            + ","
            + resource(EXPORT_URI + "/2", "application/octet-stream", 3L, sha, chunkB64)
            + "]}}";

    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeCompletedForInvocation(payload, "c", 5));
    assertEquals(2, codec.decodeCompletedForInvocation(payload, "c", 6).contents().size());
  }

  /** payload 超过 16 MiB 在解码前拒绝。 */
  @Test
  void rejectsPayloadExceedingSixteenMegabytes() {
    String huge =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\""
            + "a".repeat(DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES)
            + "\"}]}}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(huge));
    assertTrue(error.getMessage().contains("payload exceeds"));
  }

  @Test
  void rejectsInvalidResourceRefFieldsOnDecode() {
    assertRejected(
        wire(
            resource(
                "http://EXAMPLE.com/a", "text/plain", 1L, sha256Hex(new byte[] {0x61}), "YQ==")));
    assertRejected(
        wire(resource(EXPORT_URI, "Text/Plain", 1L, sha256Hex(new byte[] {0x61}), "YQ==")));
  }

  @Test
  void rejectsMismatchedCallIdForInvocation() {
    String payload =
        "{\"result\":{\"callId\":\"x\",\"error\":false,\"details\":{},"
            + "\"contents\":["
            + resource(
                "https://example.com/a", "text/plain", 1L, sha256Hex(new byte[] {0x61}), "YQ==")
            + "]}}";
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeCompletedForInvocation(payload, "expected", 1024));
  }

  @Test
  void rejectsUnknownFieldsAndTypes() {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult("{\"x\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"a\","
                    + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,"
                    + "\"contentBase64\":\"YQ==\"}]}}"));
  }

  /** Capability result 必须拒绝未知字段。 */
  @Test
  void rejectsUnknownResultField() {
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    "{\"result\":{\"unexpected\":\"c\",\"error\":false,\"details\":{},"
                        + "\"contents\":[]}}"));
    assertTrue(error.getMessage().contains("unknown field"));
  }

  @Test
  void rejectsNegativeMaximumResourceBytes() {
    assertThrows(
        IllegalArgumentException.class, () -> codec.decodeCompletedForInvocation("{}", "c", -1));
  }

  /** 解码不依赖持久化 store；text/json 与空 contents 组合保持可用。 */
  @Test
  void decodesEmptyAndTextOnlyResultsWithoutStore() {
    EnvironmentCapabilityResult empty =
        codec.decodeResult(
            "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":[]}}");
    assertEquals(List.of(), empty.contents());
    assertEquals("c", empty.callId());
    assertEquals("{}", empty.detailsJson());
  }

  /** 顶层 result / details / contents 容器缺失、null 或类型错误必须在边界拒绝。 */
  @Test
  void rejectsMissingOrMalformedResultContainers() {
    assertRejected("{}");
    assertRejected("{\"result\":null}");
    assertRejected(
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":null,\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":[],\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":null}}");
    assertRejected(
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":{}}}");
    assertRejected("[]");
  }

  /** 字段类型错误与内容边界：callId/error/text/json/resource size/Base64/元素类型逐一拒绝。 */
  @Test
  void rejectsMalformedResultFieldTypes() {
    assertRejected("{\"result\":{\"error\":false,\"details\":{},\"contents\":[]}}");
    assertRejected("{\"result\":{\"callId\":5,\"error\":false,\"details\":{},\"contents\":[]}}");
    assertRejected("{\"result\":{\"callId\":\"c\",\"details\":{},\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"callId\":\"c\",\"error\":\"no\",\"details\":{},\"contents\":[]}}");
    assertRejected(wire("{\"type\":\"text\",\"text\":5}"));
    assertRejected(wire("{\"type\":\"json\"}"));
    assertRejected(wire("5"));
    // resource 缺 size；非法 Base64 字符；声明 size 超出 canonical 长度可表示范围。
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uri\":\""
                + EXPORT_URI
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"sha256\":null,"
                + "\"contentBase64\":\"YQ==\"}"));
    assertRejected(
        wire(resource(EXPORT_URI, "text/plain", 1L, sha256Hex(new byte[] {0x61}), "!!!")));
    assertRejected(
        wire(
            resource(
                EXPORT_URI, "text/plain", Long.MAX_VALUE, sha256Hex(new byte[] {0x61}), "YQ==")));
  }

  /** 空字符串是合法文本内容，只要求类型是 string。 */
  @Test
  void emptyTextContentIsAllowed() {
    EnvironmentCapabilityResult decoded =
        codec.decodeResult(
            "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                + "\"contents\":[{\"type\":\"text\",\"text\":\"\"}]}}");
    assertEquals("", ((TextResultContent) decoded.contents().get(0)).text());
  }

  /** ResourceRef 必须在任何 Base64 分配之前完成校验：非法字段与非法 Base64 同时出现时报字段错误。 */
  @Test
  void decodeValidatesResourceRefBeforeBase64Allocation() {
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    wire(
                        "{\"type\":\"resource\",\"uri\":\"not a uri\","
                            + "\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,"
                            + "\"sha256\":\""
                            + sha256Hex(new byte[] {0x61})
                            + "\",\"contentBase64\":\"!!!\"}")));
    assertTrue(error.getMessage().contains("resource fields are invalid"));
  }

  /** contents 元素数上限由 CapabilityResult 构造期统一强制（与解码侧共用同一来源）；恰好 64 条经 COMPLETED/PARTIAL 编码均通过。 */
  @Test
  void countCapIsEnforcedAtCapabilityResultConstruction() {
    List<ResultContent> atLimit = new ArrayList<>();
    for (int index = 0; index < EnvironmentCapabilityResult.MAX_CONTENT_ITEMS; index++) {
      atLimit.add(new TextResultContent("x"));
    }
    EnvironmentCapabilityResult result = new EnvironmentCapabilityResult("c", atLimit, false, "{}");
    assertEquals(EnvironmentCapabilityResult.MAX_CONTENT_ITEMS, result.contents().size());
    codec.encodeCompleted(result, new NoopStore());
    codec.encodePartial(result, new NoopStore());

    List<ResultContent> overLimit = new ArrayList<>(atLimit);
    overLimit.add(new TextResultContent("x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityResult("c", overLimit, false, "{}"));
  }

  /** 编码 ResourceResultContent 必须携带 size/sha256：wire 不允许 null 声明。 */
  @Test
  void encodeRejectsResourceRefWithoutSizeOrSha() {
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    ResourceRef noSizeSha =
        new ResourceRef("https://example.com/a", "text/plain", null, null, null);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "c", List.of(new ResourceResultContent(noSizeSha)), false, "{}");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    result,
                    new DaemonResourceStore() {
                      @Override
                      public DaemonResourceRef store(byte[] bytes, String mediaType) {
                        throw new AssertionError("store must not be called");
                      }

                      @Override
                      public byte[] read(DaemonResourceRef ref) {
                        return data;
                      }
                    }));
    assertTrue(error.getMessage().contains("must declare size and sha256"));
  }

  /** PARTIAL 编码只接受 text/json；resource/binary 在任何 store 操作之前被拒绝（NoopStore 遇调用即失败）。 */
  @Test
  void encodePartialAcceptsTextAndJsonAndRejectsResourceOrBinaryBeforeAnyStoreCall() {
    EnvironmentCapabilityResult partial =
        new EnvironmentCapabilityResult(
            "p", List.of(new TextResultContent("hi"), new JsonResultContent("[1]")), false, "{}");
    String payload = codec.encodePartial(partial, new NoopStore());
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"type\":\"json\""));
    assertEquals(2, codec.decodeResult(payload).contents().size());

    byte[] data = new byte[] {1};
    ResourceRef ref = new ResourceRef(EXPORT_URI, "text/plain", null, 1L, sha256Hex(data));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encodePartial(
                new EnvironmentCapabilityResult(
                    "p", List.of(new ResourceResultContent(ref)), false, "{}"),
                new NoopStore()));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encodePartial(
                new EnvironmentCapabilityResult(
                    "p", List.of(new BinaryResultContent("text/plain", data)), false, "{}"),
                new NoopStore()));
  }

  /** COMPLETED 编码预检先于一切 store 调用：后置条目超预算时不得产生任何 store 副作用。 */
  @Test
  void encodeCompletedPreflightsAllContentsBeforeAnyStoreCall() {
    EnvironmentCapabilityResult laterOverBudgetBinary =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new TextResultContent("ok"),
                new BinaryResultContent("application/octet-stream", new byte[5])),
            false,
            "{}");
    DaemonProtocolException binaryError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetBinary, 4, new NoopStore()));
    assertTrue(binaryError.getMessage().contains("aggregate resource bytes exceed"));

    EnvironmentCapabilityResult laterOverBudgetResource =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new TextResultContent("ok"),
                new ResourceResultContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 5L, sha256Hex(new byte[5])))),
            false,
            "{}");
    DaemonProtocolException resourceError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetResource, 4, new NoopStore()));
    assertTrue(resourceError.getMessage().contains("aggregate resource bytes exceed"));

    // 聚合超限：两个 3 字节 binary 在 4 字节预算下，第二个条目在预检阶段触发聚合错误。
    EnvironmentCapabilityResult aggregateOver =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new BinaryResultContent("application/octet-stream", new byte[3]),
                new BinaryResultContent("application/octet-stream", new byte[3])),
            false,
            "{}");
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(aggregateOver, 4, new NoopStore()));
  }

  /** 最终 payload 必须 ≤ 16 MiB UTF-8：超出时由 bounded 输出在中止点拒绝，不物化完整输出。 */
  @Test
  void encodeCompletedRejectsOversizedPayload() {
    EnvironmentCapabilityResult huge =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new TextResultContent(
                    "a".repeat(DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES))),
            false,
            "{}");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.encodeCompleted(huge, new NoopStore()));
    assertTrue(error.getMessage().contains("payload exceeds"));
  }

  /** COMPLETED 编码的预算参数必须为正；store 返回的 binary ref 必须携带 size/sha。 */
  @Test
  void encodeCompletedValidatesBudgetAndStoreReturnedRefs() {
    EnvironmentCapabilityResult text =
        new EnvironmentCapabilityResult("c", List.of(new TextResultContent("x")), false, "{}");
    assertThrows(
        IllegalArgumentException.class, () -> codec.encodeCompleted(text, 0, new NoopStore()));
    assertThrows(
        IllegalArgumentException.class, () -> codec.encodeCompleted(text, -1, new NoopStore()));

    byte[] data = new byte[] {1};
    EnvironmentCapabilityResult binary =
        new EnvironmentCapabilityResult(
            "c", List.of(new BinaryResultContent("text/plain", data)), false, "{}");
    DaemonProtocolException noSizeSha =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    binary,
                    new DaemonResourceStore() {
                      @Override
                      public DaemonResourceRef store(byte[] bytes, String mediaType) {
                        return new DaemonResourceRef(
                            "https://example.com/a", mediaType, null, null, null);
                      }

                      @Override
                      public byte[] read(DaemonResourceRef ref) {
                        throw new AssertionError("read must not be called for binary content");
                      }
                    }));
    assertTrue(noSizeSha.getMessage().contains("must declare size and sha256"));
  }

  /** 其余解析边界：result 非对象、type 空白、canonical 长度匹配但非法 Base64 字符、超大 size 走 canonical 长度守卫。 */
  @Test
  void rejectsRemainingParseBoundaries() {
    // result 节点类型错误。
    assertRejected("{\"result\":5}");
    // type 空白。
    assertRejected(wire("{\"type\":\"\",\"text\":\"x\"}"));
    // canonical 长度匹配但含非法 Base64 字符：解码器拒绝。
    DaemonProtocolException badChars =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    wire(
                        resource(
                            EXPORT_URI, "text/plain", 1L, sha256Hex(new byte[] {0x61}), "!!!="))));
    assertTrue(badChars.getMessage().contains("not valid Base64"));
    // 超大声明 size + 超大预算：canonical 长度守卫生效（溢出保护返回 Long.MAX_VALUE，长度必然不匹配）。
    DaemonProtocolException huge =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeCompletedForInvocation(
                    wire(
                        resource(
                            EXPORT_URI,
                            "text/plain",
                            Long.MAX_VALUE,
                            sha256Hex(new byte[] {0x61}),
                            "YQ==")),
                    "c",
                    Long.MAX_VALUE));
    assertTrue(huge.getMessage().contains("canonical Base64"));
  }

  /** wire 上的 details/json 内容超过 1 MiB 构造上限时按协议错误拒绝（不触达无界树解析）。 */
  @Test
  void decodeRejectsOversizedDetailsAndJsonContents() {
    // details 超限（> 1 MiB UTF-8）：EnvironmentCapabilityResult 构造期上限 → 协议错误。
    String oversizedDetails =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{\"d\":\""
            + "a".repeat(EnvironmentCapabilityResult.MAX_DETAILS_JSON_UTF8_BYTES)
            + "\"},\"contents\":[]}}";
    DaemonProtocolException detailsError =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(oversizedDetails));
    assertTrue(detailsError.getMessage().contains("result fields are invalid"));

    // json 内容超限（> 1 MiB UTF-8）：JsonResultContent 构造期上限 → 协议错误。
    String oversizedJson =
        "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"json\",\"json\":\""
            + "a".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES)
            + "\"}]}}";
    DaemonProtocolException jsonError =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(oversizedJson));
    assertTrue(jsonError.getMessage().contains("'json' is invalid or too large"));
  }

  /** 分相入口：decodePartialForInvocation 拒绝 resource；两者都校验空白期望 id。 */
  @Test
  void phaseSpecificInvocationEntrypoints() {
    String textPayload =
        "{\"result\":{\"callId\":\"call-x\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
    EnvironmentCapabilityResult partial =
        codec.decodePartialForInvocation(textPayload, "call-x", 1024);
    assertEquals("call-x", partial.callId());
    assertEquals(
        "call-x", codec.decodeCompletedForInvocation(textPayload, "call-x", 1024).callId());

    byte[] data = "xx".getBytes(StandardCharsets.UTF_8);
    String resourcePayload =
        "{\"result\":{\"callId\":\"call-x\",\"error\":false,\"details\":{},"
            + "\"contents\":["
            + resource(
                EXPORT_URI,
                "text/plain",
                2L,
                sha256Hex(data),
                Base64.getEncoder().encodeToString(data))
            + "]}}";
    DaemonProtocolException partialRejectsResource =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.decodePartialForInvocation(resourcePayload, "call-x", 1024));
    assertTrue(partialRejectsResource.getMessage().contains("PARTIAL"));
    assertEquals(
        1, codec.decodeCompletedForInvocation(resourcePayload, "call-x", 1024).contents().size());

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodePartialForInvocation(textPayload, " ", 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeCompletedForInvocation(textPayload, null, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodePartialForInvocation(textPayload, null, 1024));
  }

  /** encode 边界：null 参数、store 返回 null 字节、binary store 返回 size/sha 不符的 ref 全部拒绝。 */
  @Test
  void encodeRejectsNullArgumentsNullStoreBytesAndMismatchedStoredRefs() {
    byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
    ResourceRef ref =
        new ResourceRef(EXPORT_URI, "text/plain", null, (long) data.length, sha256Hex(data));
    EnvironmentCapabilityResult resourceResult =
        new EnvironmentCapabilityResult("c", List.of(new ResourceResultContent(ref)), false, "{}");
    EnvironmentCapabilityResult binaryResult =
        new EnvironmentCapabilityResult(
            "c", List.of(new BinaryResultContent("text/plain", data)), false, "{}");

    assertThrows(NullPointerException.class, () -> codec.encodeCompleted(null, new NoopStore()));
    assertThrows(NullPointerException.class, () -> codec.encodeCompleted(resourceResult, null));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(resourceResult, new StubStore(null, null)));

    DaemonProtocolException wrongSize =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    binaryResult,
                    new DaemonResourceStore() {
                      @Override
                      public DaemonResourceRef store(byte[] bytes, String mediaType) {
                        return new DaemonResourceRef(
                            EXPORT_URI, mediaType, null, ref.size() + 1, ref.sha256());
                      }

                      @Override
                      public byte[] read(DaemonResourceRef ignored) {
                        throw new AssertionError("read must not be called for binary content");
                      }
                    }));
    assertTrue(wrongSize.getMessage().contains("resource size mismatch"));

    DaemonProtocolException wrongSha =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    binaryResult,
                    new DaemonResourceStore() {
                      @Override
                      public DaemonResourceRef store(byte[] bytes, String mediaType) {
                        return new DaemonResourceRef(
                            EXPORT_URI, mediaType, null, ref.size(), "0".repeat(63) + "a");
                      }

                      @Override
                      public byte[] read(DaemonResourceRef ignored) {
                        throw new AssertionError("read must not be called for binary content");
                      }
                    }));
    assertTrue(wrongSha.getMessage().contains("resource sha256 mismatch"));
  }

  /** detailsJson 为 null 时编码为空对象 details，text/json 内容不触碰 store。 */
  @Test
  void encodesNullDetailsJsonAsEmptyDetailsObject() {
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult("c", List.of(new TextResultContent("hi")), false, null);
    assertTrue(codec.encodeCompleted(result, new NoopStore()).contains("\"details\":{}"));
  }

  private void assertRejected(String payload) {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload));
  }

  private static final class StubStore implements DaemonResourceStore {

    private final byte[] bytes;
    private final IOException failure;

    private StubStore(byte[] bytes, IOException failure) {
      this.bytes = bytes;
      this.failure = failure;
    }

    @Override
    public DaemonResourceRef store(byte[] bytes, String mediaType) throws IOException {
      if (failure != null) {
        throw failure;
      }
      throw new AssertionError("store must not be called");
    }

    @Override
    public byte[] read(DaemonResourceRef ref) throws IOException {
      if (failure != null) {
        throw failure;
      }
      return bytes;
    }
  }

  private static final class NoopStore implements DaemonResourceStore {

    @Override
    public DaemonResourceRef store(byte[] bytes, String mediaType) {
      throw new AssertionError("store must not be called");
    }

    @Override
    public byte[] read(DaemonResourceRef ref) {
      throw new AssertionError("read must not be called");
    }
  }

  private static String resource(
      String uri, String mediaType, Long size, String sha256, String contentBase64) {
    return "{\"type\":\"resource\",\"uri\":\""
        + uri
        + "\",\"mediaType\":\""
        + mediaType
        + "\",\"name\":null,\"size\":"
        + size
        + ",\"sha256\":\""
        + sha256
        + "\",\"contentBase64\":\""
        + contentBase64
        + "\"}";
  }

  private static String wire(String content) {
    return "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
        + "\"contents\":["
        + content
        + "]}}";
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void assertNoThrowableMessageContains(Throwable error, String sensitive) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      assertFalse(message != null && message.contains(sensitive));
      current = current.getCause();
    }
  }
}
