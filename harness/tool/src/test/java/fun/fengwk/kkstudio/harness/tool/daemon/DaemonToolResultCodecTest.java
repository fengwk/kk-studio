package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/** Daemon v2 PARTIAL / COMPLETED payload codec 的双向与拒绝契约测试。 */
class DaemonToolResultCodecTest {

  private static final String EXPORT_URI = "file:///export/abc";
  private static final String SHA_HELLO =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

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

    String payload = codec.encodeCompleted(result, new NoopStore());

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

  /** resource 内容编码为 flat 精确字段 + contentBase64；解码还原为内联 BinaryToolContent，不触碰持久化。 */
  @Test
  void resourcePayloadEncodesFlatFieldsAndDecodesToInlineBinary() {
    byte[] data = "kk-studio".getBytes(StandardCharsets.UTF_8);
    ResourceRef local =
        new ResourceRef(EXPORT_URI, "text/plain", "kk.txt", (long) data.length, sha256Hex(data));
    ToolResult result =
        new ToolResult("call-2", List.of(new ResourceToolContent(local)), false, "{}", false);

    String payload =
        codec.encodeCompleted(
            result,
            new DaemonResourceStore() {
              @Override
              public ResourceRef store(byte[] bytes, String mediaType) {
                throw new AssertionError("store must not be called for resource content");
              }

              @Override
              public byte[] read(ResourceRef ref) {
                assertEquals(local, ref);
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

    ToolResult decoded = codec.decodeResult(payload, 1024, true);
    assertEquals(1, decoded.contents().size());
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertArrayEquals(data, binary.content());
  }

  /** 编码 ResourceToolContent 时 store 返回字节必须通过 size/sha 复核；读取失败确定性抛协议异常。 */
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
                    new ToolResult(
                        "c", List.of(new ResourceToolContent(wrongSize)), false, "{}", false),
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
                    new ToolResult(
                        "c", List.of(new ResourceToolContent(wrongSha)), false, "{}", false),
                    new StubStore(data, null)));
    assertTrue(shaMismatch.getMessage().contains("store sha256 mismatch"));

    DaemonProtocolException readFailure =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    new ToolResult("c", List.of(new ResourceToolContent(ref)), false, "{}", false),
                    new StubStore(null, new IOException("resource gone"))));
    assertTrue(readFailure.getMessage().contains("cannot read resource bytes"));
  }

  /** 编码 BinaryToolContent 必须先经 store 落盘，再编码返回的 resource 引用。 */
  @Test
  void binaryToolContentIsStoredThenEncodedAsResource() {
    byte[] data = new byte[] {1, 2, 3, 4};
    ResourceRef stored =
        new ResourceRef(EXPORT_URI, "application/octet-stream", null, 4L, sha256Hex(data));
    ToolResult result =
        new ToolResult(
            "call-b",
            List.of(new BinaryToolContent("application/octet-stream", data)),
            false,
            "{}",
            false);

    String payload =
        codec.encodeCompleted(
            result,
            new DaemonResourceStore() {
              @Override
              public ResourceRef store(byte[] bytes, String mediaType) {
                assertArrayEquals(data, bytes);
                assertEquals("application/octet-stream", mediaType);
                return stored;
              }

              @Override
              public byte[] read(ResourceRef ref) {
                throw new AssertionError("read must not be called for binary content");
              }
            });

    assertTrue(payload.contains("\"type\":\"resource\""));
    assertTrue(payload.contains("\"uri\":\"" + EXPORT_URI + "\""));
    ToolResult decoded = codec.decodeResult(payload);
    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertArrayEquals(data, binary.content());

    DaemonProtocolException storeFailure =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(result, new StubStore(null, new IOException("store down"))));
    assertTrue(storeFailure.getMessage().contains("cannot store binary tool content"));
  }

  /** http(s) resource 的 size/sha256 同样是必填 wire 字段；解码仍返回内联字节。 */
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

    ToolResult decoded =
        codec.decodeResult(
            "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                + "\"contents\":["
                + segment
                + "]}}");

    BinaryToolContent binary = (BinaryToolContent) decoded.contents().get(0);
    assertArrayEquals(data, binary.content());

    // size/sha256 缺失或为 null 必须拒绝，不允许以 null 声明绕过大小预检。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":["
                    + segment.replace("\"size\":1,", "")
                    + "]}}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":["
                    + segment.replace("\"sha256\":\"" + sha256Hex(data) + "\"", "\"sha256\":null")
                    + "]}}"));
  }

  /** PARTIAL（allowResources=false）必须在任何回调前拒绝 resource 内容。 */
  @Test
  void rejectsResourceWhenNotAllowed() {
    byte[] data = "xx".getBytes(StandardCharsets.UTF_8);
    ToolResult result =
        new ToolResult(
            "call-3",
            List.of(
                new ResourceToolContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 2L, sha256Hex(data)))),
            false,
            "{}",
            false);
    String payload = codec.encodeCompleted(result, new StubStore(data, null));
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload, 1024, false));
    assertTrue(error.getMessage().contains("PARTIAL"));
  }

  /** 单 item 声明 size 超限必须在 Base64 分配前拒绝。 */
  @Test
  void rejectsOversizedResourceBeforeAllocation() {
    byte[] data = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    ToolResult result =
        new ToolResult(
            "call-4",
            List.of(
                new ResourceToolContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 8L, sha256Hex(data)))),
            false,
            "{}",
            false);
    String payload = codec.encodeCompleted(result, new StubStore(data, null));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload, 4, true));
  }

  /** contents 元素数上限 64，防止内容列表无限放大。 */
  @Test
  void rejectsMoreThanSixtyFourContents() {
    StringBuilder contents = new StringBuilder();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS + 1; index++) {
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
                    "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                        + "\"contents\":["
                        + contents
                        + "]}}"));
    assertTrue(error.getMessage().contains("must not exceed 64"));
  }

  /** 聚合解码字节数不得超过 maximumResourceBytes，防止 N×limit 放大。 */
  @Test
  void rejectsAggregateDecodedResourceBytesAboveLimit() {
    byte[] data = "abcdef".getBytes(StandardCharsets.UTF_8);
    String item =
        "{\"type\":\"resource\",\"uri\":\""
            + EXPORT_URI
            + "\",\"mediaType\":\"text/plain\","
            + "\"name\":null,\"size\":6,\"sha256\":\""
            + sha256Hex(data)
            + "\",\"contentBase64\":\""
            + Base64.getEncoder().encodeToString(data)
            + "\"}";
    String payload =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":["
            + item
            + ","
            + item
            + "]}}";

    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload, 8, true));
    assertTrue(error.getMessage().contains("aggregate decoded resource bytes exceed"));
  }

  /** 聚合预算预检先于 Base64 校验/分配：预算耗尽时即使后续条目 Base64 非法也报聚合错误。 */
  @Test
  void decodeRejectsAggregateExhaustionBeforeBase64Validation() {
    byte[] data = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    String valid =
        resource(
            EXPORT_URI,
            "text/plain",
            8L,
            sha256Hex(data),
            Base64.getEncoder().encodeToString(data));
    String overBudgetWithInvalidBase64 =
        resource(EXPORT_URI, "text/plain", 8L, sha256Hex(data), "!!!");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                        + "\"contents\":["
                        + valid
                        + ","
                        + overBudgetWithInvalidBase64
                        + "]}}",
                    8,
                    true));
    assertTrue(error.getMessage().contains("aggregate decoded resource bytes exceed"));
    assertFalse(error.getMessage().contains("Base64"));
  }

  /** 无参解码使用 8 MiB 默认预算：不存在 unbounded 默认入口。 */
  @Test
  void defaultDecodeBudgetIsEightMebibytes() {
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    wire(
                        resource(
                            EXPORT_URI,
                            "text/plain",
                            DaemonToolResultCodec.DEFAULT_MAX_RESOURCE_BYTES + 1,
                            sha256Hex(new byte[] {0x61}),
                            "YQ=="))));
    assertTrue(error.getMessage().contains("aggregate decoded resource bytes exceed"));
  }

  /** 原始 payload 的 UTF-8 长度在解析前受 16 MiB 上限约束；非法 Unicode（未配对代理项）同样拒绝。 */
  @Test
  void decodeRejectsPayloadsAboveSixteenMebibytesAndInvalidUnicode() {
    String oversized =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\""
            + "a".repeat(DaemonToolResultCodec.MAX_PAYLOAD_UTF8_BYTES)
            + "\"}]}}";
    DaemonProtocolException error =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(oversized));
    assertTrue(error.getMessage().contains("payload exceeds"));

    String loneSurrogate =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\"\uD800\"}]}}";
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(loneSurrogate));
  }

  /** canonical Base64（含 pad bits）与 size/sha 复核必须在解码边界强制执行。 */
  @Test
  void rejectsNonCanonicalBase64AndSizeShaMismatches() {
    String shaOfA = sha256Hex(new byte[] {0x61});
    // "QR==" 解码为 0x41 但 pad bits 非零，重编码为 "QQ=="，必须拒绝。
    assertRejected(wire(resource(EXPORT_URI, "text/plain", 1L, shaOfA, "QR==")));
    // 缺失 padding：encoded 长度与声明 size 不匹配，分配前拒绝。
    assertRejected(
        wire(resource(EXPORT_URI, "text/plain", 2L, sha256Hex(new byte[] {0x61, 0x62}), "YWI")));
    // 声明 size 与解码长度不符。
    assertRejected(wire(resource(EXPORT_URI, "text/plain", 1L, shaOfA, "YWI=")));
    // 声明 sha256 与解码字节不符。
    assertRejected(
        wire(
            resource(
                EXPORT_URI,
                "text/plain",
                1L,
                sha256Hex("zz".getBytes(StandardCharsets.UTF_8)),
                "YQ==")));
    // size/sha256 缺失或为 null 必须拒绝（不允许以 null 声明绕过大小预检）。
    assertRejected(wire(resource(EXPORT_URI, "text/plain", null, null, "YQ==")));
    assertRejected(wire(resource(EXPORT_URI, "text/plain", 1L, null, "YQ==")));
    assertRejected(wire(resource(EXPORT_URI, "text/plain", null, shaOfA, "YQ==")));
  }

  /** resource wire 的字段集必须精确：缺失、额外、错误类型、duplicate 与 trailing 全部拒绝。 */
  @Test
  void rejectsMalformedResourceWireFields() {
    String validSegment =
        resource(EXPORT_URI, "text/plain", 1L, sha256Hex(new byte[] {0x61}), "YQ==");
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResult(wire(validSegment.replace("\"name\":null,", ""))));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResult(wire(validSegment.replace("}", ",\"extra\":true}"))));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResult(wire(validSegment.replace("\"name\":null", "\"name\":5"))));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResult(wire(validSegment.replace("\"size\":1", "\"size\":\"1\""))));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeResult(wire(validSegment.replace("\"size\":1", "\"size\":-1"))));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                wire(validSegment.replace("\"sha256\":\"", "\"sha256\":5,\"x\":\""))));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                wire(
                    "{\"type\":\"resource\",\"type\":\"resource\",\"uri\":\""
                        + EXPORT_URI
                        + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,"
                        + "\"sha256\":\""
                        + sha256Hex(new byte[] {0x61})
                        + "\",\"contentBase64\":\"YQ==\"}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeResult(
                "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":["
                    + validSegment
                    + "]}} trailing"));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeResult(wire("{\"type\":\"nope\"}")));
  }

  private static String wire(String segment) {
    return "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
        + "\"contents\":["
        + segment
        + "]}}";
  }

  private static String resource(
      String uri, String mediaType, Long size, String sha256, String contentBase64) {
    return "{\"type\":\"resource\",\"uri\":\""
        + uri
        + "\",\"mediaType\":\""
        + mediaType
        + "\",\"name\":null,\"size\":"
        + size
        + ",\"sha256\":"
        + (sha256 == null ? "null" : "\"" + sha256 + "\"")
        + ",\"contentBase64\":\""
        + contentBase64
        + "\"}";
  }

  /** 解码的 resource 构造必须通过 ResourceRef 全量校验（非法 URI / mediaType 同样在 codec 边界拒绝）。 */
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
  void rejectsMismatchedToolCallIdForInvocation() {
    String payload =
        "{\"result\":{\"toolCallId\":\"x\",\"error\":false,\"details\":{},"
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
                "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                    + "\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"a\","
                    + "\"mediaType\":\"text/plain\",\"sizeBytes\":1,"
                    + "\"contentBase64\":\"YQ==\"}]}}"));
  }

  @Test
  void rejectsNegativeMaximumResourceBytes() {
    assertThrows(IllegalArgumentException.class, () -> codec.decodeResult("{}", -1, true));
  }

  /** 解码不依赖持久化 store；text/json 与空 contents 组合保持可用。 */
  @Test
  void decodesEmptyAndTextOnlyResultsWithoutStore() {
    ToolResult empty =
        codec.decodeResult(
            "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},\"contents\":[]}}");
    assertEquals(List.of(), empty.contents());
    assertEquals("c", empty.toolCallId());
    assertEquals("{}", empty.detailsJson());
  }

  /** 顶层 result / details / contents 容器缺失、null 或类型错误必须在边界拒绝。 */
  @Test
  void rejectsMissingOrMalformedResultContainers() {
    assertRejected("{}");
    assertRejected("{\"result\":null}");
    assertRejected(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":null,\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":[],\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},\"contents\":null}}");
    assertRejected(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},\"contents\":{}}}");
    assertRejected("[]");
  }

  /** 字段类型错误与内容边界：toolCallId/error/text/json/resource size/Base64/元素类型逐一拒绝。 */
  @Test
  void rejectsMalformedResultFieldTypes() {
    assertRejected("{\"result\":{\"error\":false,\"details\":{},\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"toolCallId\":5,\"error\":false,\"details\":{},\"contents\":[]}}");
    assertRejected("{\"result\":{\"toolCallId\":\"c\",\"details\":{},\"contents\":[]}}");
    assertRejected(
        "{\"result\":{\"toolCallId\":\"c\",\"error\":\"no\",\"details\":{},\"contents\":[]}}");
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
    ToolResult decoded =
        codec.decodeResult(
            "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
                + "\"contents\":[{\"type\":\"text\",\"text\":\"\"}]}}");
    assertEquals("", ((TextToolContent) decoded.contents().get(0)).text());
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

  /** contents 元素数上限由 ToolResult 构造期统一强制（与解码侧共用同一来源）；恰好 64 条经 COMPLETED/PARTIAL 编码均通过。 */
  @Test
  void countCapIsEnforcedAtToolResultConstruction() {
    List<ToolContent> atLimit = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      atLimit.add(new TextToolContent("x"));
    }
    ToolResult result = new ToolResult("c", atLimit, false, "{}", false);
    assertEquals(ToolResult.MAX_CONTENT_ITEMS, result.contents().size());
    codec.encodeCompleted(result, new NoopStore());
    codec.encodePartial(result, new NoopStore());

    List<ToolContent> overLimit = new ArrayList<>(atLimit);
    overLimit.add(new TextToolContent("x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ToolResult("c", overLimit, false, "{}", false));
  }

  /** 编码 ResourceToolContent 必须携带 size/sha256：wire 不允许 null 声明。 */
  @Test
  void encodeRejectsResourceRefWithoutSizeOrSha() {
    byte[] data = "x".getBytes(StandardCharsets.UTF_8);
    ResourceRef noSizeSha =
        new ResourceRef("https://example.com/a", "text/plain", null, null, null);
    ToolResult result =
        new ToolResult("c", List.of(new ResourceToolContent(noSizeSha)), false, "{}", false);
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    result,
                    new DaemonResourceStore() {
                      @Override
                      public ResourceRef store(byte[] bytes, String mediaType) {
                        throw new AssertionError("store must not be called");
                      }

                      @Override
                      public byte[] read(ResourceRef ref) {
                        return data;
                      }
                    }));
    assertTrue(error.getMessage().contains("must declare size and sha256"));
  }

  /** PARTIAL 编码只接受 text/json；resource/binary 在任何 store 操作之前被拒绝（NoopStore 遇调用即失败）。 */
  @Test
  void encodePartialAcceptsTextAndJsonAndRejectsResourceOrBinaryBeforeAnyStoreCall() {
    ToolResult partial =
        new ToolResult(
            "p",
            List.of(new TextToolContent("hi"), new JsonToolContent("[1]")),
            false,
            "{}",
            false);
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
                new ToolResult("p", List.of(new ResourceToolContent(ref)), false, "{}", false),
                new NoopStore()));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encodePartial(
                new ToolResult(
                    "p", List.of(new BinaryToolContent("text/plain", data)), false, "{}", false),
                new NoopStore()));
  }

  /** COMPLETED 编码预检先于一切 store 调用：后置条目超预算时不得产生任何 store 副作用。 */
  @Test
  void encodeCompletedPreflightsAllContentsBeforeAnyStoreCall() {
    ToolResult laterOverBudgetBinary =
        new ToolResult(
            "c",
            List.of(
                new TextToolContent("ok"),
                new BinaryToolContent("application/octet-stream", new byte[5])),
            false,
            "{}",
            false);
    DaemonProtocolException binaryError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetBinary, 4, new NoopStore()));
    assertTrue(binaryError.getMessage().contains("aggregate resource bytes exceed"));

    ToolResult laterOverBudgetResource =
        new ToolResult(
            "c",
            List.of(
                new TextToolContent("ok"),
                new ResourceToolContent(
                    new ResourceRef(EXPORT_URI, "text/plain", null, 5L, sha256Hex(new byte[5])))),
            false,
            "{}",
            false);
    DaemonProtocolException resourceError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetResource, 4, new NoopStore()));
    assertTrue(resourceError.getMessage().contains("aggregate resource bytes exceed"));

    // 聚合超限：两个 3 字节 binary 在 4 字节预算下，第二个条目在预检阶段触发聚合错误。
    ToolResult aggregateOver =
        new ToolResult(
            "c",
            List.of(
                new BinaryToolContent("application/octet-stream", new byte[3]),
                new BinaryToolContent("application/octet-stream", new byte[3])),
            false,
            "{}",
            false);
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(aggregateOver, 4, new NoopStore()));
  }

  /** 最终 payload 必须 ≤ 16 MiB UTF-8：超出时由 bounded 输出在中止点拒绝，不物化完整输出。 */
  @Test
  void encodeCompletedRejectsOversizedPayload() {
    ToolResult huge =
        new ToolResult(
            "c",
            List.of(new TextToolContent("a".repeat(DaemonToolResultCodec.MAX_PAYLOAD_UTF8_BYTES))),
            false,
            "{}",
            false);
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class, () -> codec.encodeCompleted(huge, new NoopStore()));
    assertTrue(error.getMessage().contains("payload exceeds"));
  }

  /** COMPLETED 编码的预算参数必须为正；store 返回的 binary ref 必须携带 size/sha。 */
  @Test
  void encodeCompletedValidatesBudgetAndStoreReturnedRefs() {
    ToolResult text = new ToolResult("c", List.of(new TextToolContent("x")), false, "{}", false);
    assertThrows(
        IllegalArgumentException.class, () -> codec.encodeCompleted(text, 0, new NoopStore()));
    assertThrows(
        IllegalArgumentException.class, () -> codec.encodeCompleted(text, -1, new NoopStore()));

    byte[] data = new byte[] {1};
    ToolResult binary =
        new ToolResult("c", List.of(new BinaryToolContent("text/plain", data)), false, "{}", false);
    DaemonProtocolException noSizeSha =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    binary,
                    new DaemonResourceStore() {
                      @Override
                      public ResourceRef store(byte[] bytes, String mediaType) {
                        return new ResourceRef(
                            "https://example.com/a", mediaType, null, null, null);
                      }

                      @Override
                      public byte[] read(ResourceRef ref) {
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
                codec.decodeResult(
                    wire(
                        resource(
                            EXPORT_URI,
                            "text/plain",
                            Long.MAX_VALUE,
                            sha256Hex(new byte[] {0x61}),
                            "YQ==")),
                    Long.MAX_VALUE,
                    true));
    assertTrue(huge.getMessage().contains("canonical Base64"));
  }

  /** wire 上的 details/json 内容超过 1 MiB 构造上限时按协议错误拒绝（不触达无界树解析）。 */
  @Test
  void decodeRejectsOversizedDetailsAndJsonContents() {
    // details 超限（> 1 MiB UTF-8）：ToolResult 构造期上限 → 协议错误。
    String oversizedDetails =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{\"d\":\""
            + "a".repeat(ToolResult.MAX_DETAILS_JSON_UTF8_BYTES)
            + "\"},\"contents\":[]}}";
    DaemonProtocolException detailsError =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(oversizedDetails));
    assertTrue(detailsError.getMessage().contains("result fields are invalid"));

    // json 内容超限（> 1 MiB UTF-8）：JsonToolContent 构造期上限 → 协议错误。
    String oversizedJson =
        "{\"result\":{\"toolCallId\":\"c\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"json\",\"json\":\""
            + "a".repeat(JsonToolContent.MAX_JSON_UTF8_BYTES)
            + "\"}]}}";
    DaemonProtocolException jsonError =
        assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(oversizedJson));
    assertTrue(jsonError.getMessage().contains("'json' is invalid or too large"));
  }

  /** 分相入口：decodePartialForInvocation 拒绝 resource；两者都校验空白期望 id。 */
  @Test
  void phaseSpecificInvocationEntrypoints() {
    String textPayload =
        "{\"result\":{\"toolCallId\":\"call-x\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
    ToolResult partial = codec.decodePartialForInvocation(textPayload, "call-x", 1024);
    assertEquals("call-x", partial.toolCallId());
    assertEquals(
        "call-x", codec.decodeCompletedForInvocation(textPayload, "call-x", 1024).toolCallId());

    byte[] data = "xx".getBytes(StandardCharsets.UTF_8);
    String resourcePayload =
        "{\"result\":{\"toolCallId\":\"call-x\",\"error\":false,\"details\":{},"
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
    ToolResult resourceResult =
        new ToolResult("c", List.of(new ResourceToolContent(ref)), false, "{}", false);
    ToolResult binaryResult =
        new ToolResult("c", List.of(new BinaryToolContent("text/plain", data)), false, "{}", false);

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
                      public ResourceRef store(byte[] bytes, String mediaType) {
                        return new ResourceRef(
                            EXPORT_URI, mediaType, null, ref.size() + 1, ref.sha256());
                      }

                      @Override
                      public byte[] read(ResourceRef ignored) {
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
                      public ResourceRef store(byte[] bytes, String mediaType) {
                        return new ResourceRef(
                            EXPORT_URI, mediaType, null, ref.size(), "0".repeat(63) + "a");
                      }

                      @Override
                      public byte[] read(ResourceRef ignored) {
                        throw new AssertionError("read must not be called for binary content");
                      }
                    }));
    assertTrue(wrongSha.getMessage().contains("resource sha256 mismatch"));
  }

  /** detailsJson 为 null 时编码为空对象 details，text/json 内容不触碰 store。 */
  @Test
  void encodesNullDetailsJsonAsEmptyDetailsObject() {
    ToolResult result = new ToolResult("c", List.of(new TextToolContent("hi")), false, null, false);
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
    public ResourceRef store(byte[] bytes, String mediaType) throws IOException {
      if (failure != null) {
        throw failure;
      }
      throw new AssertionError("store must not be called");
    }

    @Override
    public byte[] read(ResourceRef ref) throws IOException {
      if (failure != null) {
        throw failure;
      }
      return bytes;
    }
  }

  private static final class NoopStore implements DaemonResourceStore {

    @Override
    public ResourceRef store(byte[] bytes, String mediaType) {
      throw new AssertionError("store must not be called");
    }

    @Override
    public byte[] read(ResourceRef ref) {
      throw new AssertionError("read must not be called");
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
