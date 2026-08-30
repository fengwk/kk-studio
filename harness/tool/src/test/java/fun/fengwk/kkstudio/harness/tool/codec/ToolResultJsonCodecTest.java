package fun.fengwk.kkstudio.harness.tool.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class ToolResultJsonCodecTest {

  /** 每种可持久化的 Tool 内容变体都支持 round-trip。 */
  @Test
  void roundTripsTextAndJsonContents() {
    ToolResult source =
        new ToolResult(
            "call",
            List.of(
                new TextResultContent("text"),
                new JsonResultContent("{\"answer\":42}"),
                new ResourceResultContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            true,
            "{\"code\":7}");

    ToolResult decoded = ToolResultJsonCodec.decode(ToolResultJsonCodec.encode(source));

    assertEquals("call", decoded.toolCallId());
    assertEquals(true, decoded.error());
    assertEquals("{\"code\":7}", decoded.detailsJson());
    assertEquals("text", ((TextResultContent) decoded.contents().get(0)).text());
    assertEquals("{\"answer\":42}", ((JsonResultContent) decoded.contents().get(1)).json());
    assertEquals(
        "https://example.com/a",
        ((ResourceResultContent) decoded.contents().get(2)).resource().uri());
  }

  /** 空的成功结果仍然有效，包括可选的默认 details 对象。 */
  @Test
  void roundTripsEmptySuccessfulResult() {
    ToolResult decoded =
        ToolResultJsonCodec.decode(
            ToolResultJsonCodec.encode(new ToolResult("call", List.of(), false, "{}")));

    assertEquals(List.of(), decoded.contents());
    assertEquals(false, decoded.error());
    assertEquals("{}", decoded.detailsJson());
  }

  /** Resource 内容在所有引用字段和 preview 均有值时支持 round-trip。 */
  @Test
  void roundTripsResourceContentWithAllFields() {
    ResourceRef resource =
        new ResourceRef(
            "data:text/plain,hello",
            "text/plain",
            "hello.txt",
            5L,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    ToolResult decoded =
        ToolResultJsonCodec.decode(
            ToolResultJsonCodec.encode(
                new ToolResult(
                    "call",
                    List.of(new ResourceResultContent(resource, "hello preview")),
                    false,
                    "{}")));

    ResourceResultContent decodedResource = (ResourceResultContent) decoded.contents().getFirst();
    assertEquals(resource, decodedResource.resource());
    assertEquals("hello preview", decodedResource.preview());
  }

  /** Resource 内容编码为扁平的精确字段，缺失的可选字段使用 JSON null。 */
  @Test
  void encodesFlatExactResourceFieldsWithJsonNulls() {
    ToolResult source =
        new ToolResult(
            "call",
            List.of(
                new ResourceResultContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            false,
            "{}");

    assertEquals(
        "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
            + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
            + "\"name\":null,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}",
        ToolResultJsonCodec.encode(source));
  }

  /** Resource 内容反序列化拒绝非法字段结构与错误类型。 */
  @Test
  void decodesResourceContentWithNullFieldsAndRejectsBadTypes() {
    ToolResult decoded =
        ToolResultJsonCodec.decode(
            "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                + "\"name\":null,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}");
    ResourceResultContent content = (ResourceResultContent) decoded.contents().getFirst();
    assertEquals("https://example.com/a", content.resource().uri());
    assertEquals("text/plain", content.resource().mediaType());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":\"big\",\"sha256\":null}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":5,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":5}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"extra\":1}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":5}],\"error\":false,\"details\":{}}"));
    // 非法 URI / mediaType 由 ResourceRef 构造校验在 codec 边界同样生效。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"http://EXAMPLE.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}"));
  }

  @Test
  void roundTripsEmptyTextContentProducedByWorkerNormalization() {
    ToolResult decoded =
        ToolResultJsonCodec.decode(
            ToolResultJsonCodec.encode(
                new ToolResult("call", List.of(new TextResultContent("")), false, "{}")));

    assertEquals("", ((TextResultContent) decoded.contents().getFirst()).text());
  }

  /** 持久化结果输入采用严格校验，防止格式错误的 journal 数据成为 Session 消息。 */
  @Test
  void rejectsMalformedOrUnknownContents() {
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.decode("[]"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"details\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"unknown\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"text\",\"text\":1}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"json\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"text\",\"text\":\"x\",\"extra\":1}],\"error\":false,\"details\":{}}"));
  }

  /** 超过 maxBytes 判定超限，未超限判定不超限；且边界精确（恰好等于 exact 时为 false，少一字节为 true）。 */
  @Test
  void exceedsEncodedUtf8BytesMatchesExactByteBoundaries() {
    ToolResult ascii =
        new ToolResult(
            "call-1",
            List.of(new TextResultContent("hello"), new JsonResultContent("{\"a\":1}")),
            false,
            "{}");
    int asciiExact = ToolResultJsonCodec.encode(ascii).getBytes(StandardCharsets.UTF_8).length;
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact - 1));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact + 1));

    // 多字节 UTF-8 文本：按字节计算而非字符数。
    ToolResult cjk =
        new ToolResult(
            "call-1",
            List.of(new TextResultContent("你好世界"), new JsonResultContent("{\"k\":\"测试\"}")),
            false,
            "{}");
    int cjkExact = ToolResultJsonCodec.encode(cjk).getBytes(StandardCharsets.UTF_8).length;
    assertTrue(cjkExact > ToolResultJsonCodec.encode(cjk).length());
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(cjk, cjkExact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(cjk, cjkExact - 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, -1));
    assertThrows(
        NullPointerException.class, () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(null, 1024));
  }

  /** 超大文本：bounded 输出在中止点停止（不物化完整编码副本），仍返回正确判定。 */
  @Test
  void exceedsEncodedUtf8BytesStopsAtLimitOnHugeText() {
    ToolResult huge =
        new ToolResult(
            "call", List.of(new TextResultContent("a".repeat(24 * 1024 * 1024))), false, "{}");
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 16 * 1024 * 1024));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 24 * 1024 * 1024 + 1024));
  }

  /** 64 条 1 MiB json 内容：bounded 直接序列化逐内容流式复制，不构造完整 contents 树；超 16 MiB 判定超限，足够大上限时不超限。 */
  @Test
  void exceedsEncodedUtf8BytesStaysBoundedForManyHugeJsonContents() {
    String oneMiBJson = "{\"a\":\"" + "a".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES - 8) + "\"}";
    List<ResultContent> contents = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      contents.add(new JsonResultContent(oneMiBJson));
    }
    ToolResult huge = new ToolResult("call", contents, false, "{}");
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 16 * 1024 * 1024));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 64 * 1024 * 1024 + 4096));
  }

  /** 恰好 64 条小内容：bounded 判定与 {@link #encode} 的 UTF-8 字节长度在临界点精确一致（64 条全量内容也不依赖完整树）。 */
  @Test
  void exceedsEncodedUtf8BytesMatchesEncodeExactlyForMaxItemCount() {
    List<ResultContent> contents = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      contents.add(new TextResultContent("x"));
    }
    ToolResult result = new ToolResult("call", contents, true, "{\"k\":\"v\"}");
    int exact = ToolResultJsonCodec.encode(result).getBytes(StandardCharsets.UTF_8).length;
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact - 1));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact + 1));
  }

  /** text/json/resource/details 全类型混合：逐字段直写与 {@link #encode} 的 UTF-8 字节长度精确一致（含 null 可选字段）。 */
  @Test
  void exceedsEncodedUtf8BytesMatchesEncodeExactlyForAllContentKinds() {
    ToolResult result =
        new ToolResult(
            "call",
            List.of(
                new TextResultContent("你好"),
                new JsonResultContent("{\"nested\":[1,2,{\"s\":\"x\\ny\"}],\"f\":3.14}"),
                new ResourceResultContent(
                    new ResourceRef(
                        "https://example.com/a",
                        "text/plain",
                        "a.txt",
                        5L,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                    "preview 你好"),
                new ResourceResultContent(
                    new ResourceRef("https://example.com/b", "text/plain", null, null, null))),
            true,
            "{\"error\":\"boom\",\"arr\":[null,true]}");
    int exact = ToolResultJsonCodec.encode(result).getBytes(StandardCharsets.UTF_8).length;
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact - 1));
  }

  /** Binary 内容不受支持；exceedsEncodedUtf8Bytes 与 encode 一致拒绝。 */
  @Test
  void exceedsEncodedUtf8BytesRejectsBinaryLikeEncode() {
    ToolResult binary =
        new ToolResult(
            "c", List.of(new BinaryResultContent("text/plain", new byte[] {1})), false, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(binary, 1024));
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.encode(binary));
  }
}
