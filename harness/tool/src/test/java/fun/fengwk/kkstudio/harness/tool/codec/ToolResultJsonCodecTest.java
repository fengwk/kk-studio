package fun.fengwk.kkstudio.harness.tool.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
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
                new TextToolContent("text"),
                new JsonToolContent("{\"answer\":42}"),
                new ResourceToolContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            true,
            "{\"code\":7}");

    ToolResult decoded = ToolResultJsonCodec.decode(ToolResultJsonCodec.encode(source));

    assertEquals("call", decoded.toolCallId());
    assertEquals(true, decoded.error());
    assertEquals("{\"code\":7}", decoded.detailsJson());
    assertEquals("text", ((TextToolContent) decoded.contents().get(0)).text());
    assertEquals("{\"answer\":42}", ((JsonToolContent) decoded.contents().get(1)).json());
    assertEquals(
        "https://example.com/a",
        ((ResourceToolContent) decoded.contents().get(2)).resource().uri());
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
                    List.of(new ResourceToolContent(resource, "hello preview")),
                    false,
                    "{}")));

    ResourceToolContent decodedResource = (ResourceToolContent) decoded.contents().getFirst();
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
                new ResourceToolContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            false,
            "{}");

    assertEquals(
        "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
            + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
            + "\"name\":null,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}",
        ToolResultJsonCodec.encode(source));
    assertNull(
        ((ResourceToolContent)
                ToolResultJsonCodec.decode(ToolResultJsonCodec.encode(source))
                    .contents()
                    .getFirst())
            .preview());
  }

  /** 旧的无 preview payload 与显式 null preview 都保持可解码，canonical null-preview 编码仍不新增字段。 */
  @Test
  void decodesOptionalResourcePreviewBackwardCompatibly() {
    String oldPayload =
        "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
            + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
            + "\"name\":null,\"size\":null,\"sha256\":null}],\"error\":false,\"details\":{}}";
    String explicitNullPayload =
        "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
            + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
            + "\"name\":null,\"size\":null,\"sha256\":null,\"preview\":null}],"
            + "\"error\":false,\"details\":{}}";

    assertNull(
        ((ResourceToolContent) ToolResultJsonCodec.decode(oldPayload).contents().getFirst())
            .preview());
    assertNull(
        ((ResourceToolContent)
                ToolResultJsonCodec.decode(explicitNullPayload).contents().getFirst())
            .preview());
    assertEquals(oldPayload, ToolResultJsonCodec.encode(ToolResultJsonCodec.decode(oldPayload)));
  }

  /** 严格 decode 拒绝缺失、类型错误或额外的 Resource 字段。 */
  @Test
  void rejectsMalformedResourceContents() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":null}],\"error\":false,\"details\":{}}"));
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
                    + "\"name\":null,\"size\":\"5\",\"sha256\":null}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"resource\","
                    + "\"uri\":\"https://example.com/a\",\"mediaType\":\"text/plain\","
                    + "\"name\":null,\"size\":-1,\"sha256\":null}],\"error\":false,\"details\":{}}"));
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
                new ToolResult("call", List.of(new TextToolContent("")), false, "{}")));

    assertEquals("", ((TextToolContent) decoded.contents().getFirst()).text());
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
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"other\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"text\"}],\"error\":false,\"details\":{}}"));
    // artifact discriminator 已彻底移除，任何 artifact wire 内容都必须按未知类型拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"artifact\",\"artifactId\":\"1\",\"mediaType\":\"text/plain\",\"sizeBytes\":-1}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":\"false\",\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"details\":{},\"extra\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"json\"}],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.decode("{\"toolCallId\":\"call\"}"));
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.decode("{"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":{},\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[1],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\" \",\"contents\":[],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"missing\":true}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"toolCallId\":\"other\",\"contents\":[],\"error\":false,\"details\":{}}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[],\"error\":false,\"details\":{}} trailing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ToolResultJsonCodec.decode(
                "{\"toolCallId\":\"call\",\"contents\":[{\"type\":\"binary\",\"mediaType\":\"text/plain\"}],\"error\":false,\"details\":{}}"));
  }

  /** bounded 编码辅助必须按 UTF-8 字节（而非字符）精确计数，并在恰好临界/超一字节处正确判定。 */
  @Test
  void exceedsEncodedUtf8BytesRespectsExactUtf8Boundary() {
    ToolResult ascii = new ToolResult("call", List.of(new TextToolContent("x")), false, "{}");
    int asciiExact = ToolResultJsonCodec.encode(ascii).getBytes(StandardCharsets.UTF_8).length;
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact - 1));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(ascii, asciiExact + 1));

    // 多字节字符：字节级计数（UTF-8 字节数 > UTF-16 字符数），一字节越界即判定超限。
    ToolResult cjk = new ToolResult("call", List.of(new TextToolContent("中")), false, "{}");
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
            "call", List.of(new TextToolContent("a".repeat(24 * 1024 * 1024))), false, "{}");
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 16 * 1024 * 1024));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 24 * 1024 * 1024 + 1024));
  }

  /** 通用 bounded 编码：未超限返回与 {@link #encode} 完全一致的文本，超限返回 null。 */
  @Test
  void encodeBoundedUtf8ReturnsNullAboveLimitAndExactTextWithin() {
    ToolResult base = new ToolResult("call", List.of(new TextToolContent("x")), false, "{}");
    JsonNode node = ToolResultJsonCodec.encodeNode(base);
    int exact = ToolResultJsonCodec.encode(base).getBytes(StandardCharsets.UTF_8).length;
    assertEquals(
        ToolResultJsonCodec.encode(base), ToolResultJsonCodec.encodeBoundedUtf8(node, exact));
    assertNull(ToolResultJsonCodec.encodeBoundedUtf8(node, exact - 1));
    assertThrows(
        IllegalArgumentException.class, () -> ToolResultJsonCodec.encodeBoundedUtf8(node, 0));
  }

  /** 64 条 1 MiB json 内容：bounded 直接序列化逐内容流式复制，不构造完整 contents 树；超 16 MiB 判定超限，足够大上限时不超限。 */
  @Test
  void exceedsEncodedUtf8BytesStaysBoundedForManyHugeJsonContents() {
    String oneMiBJson = "{\"a\":\"" + "a".repeat(JsonToolContent.MAX_JSON_UTF8_BYTES - 8) + "\"}";
    List<ToolContent> contents = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      contents.add(new JsonToolContent(oneMiBJson));
    }
    ToolResult huge = new ToolResult("call", contents, false, "{}");
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 16 * 1024 * 1024));
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(huge, 64 * 1024 * 1024 + 4096));
  }

  /** 恰好 64 条小内容：bounded 判定与 {@link #encode} 的 UTF-8 字节长度在临界点精确一致（64 条全量内容也不依赖完整树）。 */
  @Test
  void exceedsEncodedUtf8BytesMatchesEncodeExactlyForMaxItemCount() {
    List<ToolContent> contents = new ArrayList<>();
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      contents.add(new TextToolContent("x"));
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
                new TextToolContent("你好"),
                new JsonToolContent("{\"nested\":[1,2,{\"s\":\"x\\ny\"}],\"f\":3.14}"),
                new ResourceToolContent(
                    new ResourceRef(
                        "https://example.com/a",
                        "text/plain",
                        "a.txt",
                        5L,
                        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
                    "preview 你好"),
                new ResourceToolContent(
                    new ResourceRef("https://example.com/b", "text/plain", null, null, null))),
            true,
            "{\"error\":\"boom\",\"arr\":[null,true]}");
    int exact = ToolResultJsonCodec.encode(result).getBytes(StandardCharsets.UTF_8).length;
    assertFalse(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact));
    assertTrue(ToolResultJsonCodec.exceedsEncodedUtf8Bytes(result, exact - 1));
  }

  /** Binary 内容不受支持；json 内容带尾随 token / 重复字段时严格 parser 与 {@link #encode} 一致拒绝。 */
  @Test
  void exceedsEncodedUtf8BytesRejectsBinaryAndStrictParseErrorsLikeEncode() {
    ToolResult binary =
        new ToolResult(
            "c", List.of(new BinaryToolContent("text/plain", new byte[] {1})), false, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(binary, 1024));

    // 构造期宽松校验放行（readTree 忽略尾随/重复），codec 严格解析必须与 encode 一样拒绝。
    ToolResult trailing =
        new ToolResult("c", List.of(new JsonToolContent("{\"a\":1} trailing")), false, "{}");
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.encode(trailing));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(trailing, 1024));

    // 尾随 token 本身是合法 JSON 值时同样拒绝（严格 parser 只接受单个值）。
    ToolResult trailingValue =
        new ToolResult("c", List.of(new JsonToolContent("{\"a\":1} 5")), false, "{}");
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.encode(trailingValue));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(trailingValue, 1024));

    ToolResult duplicate =
        new ToolResult("c", List.of(new JsonToolContent("{\"a\":1,\"a\":2}")), false, "{}");
    assertThrows(IllegalArgumentException.class, () -> ToolResultJsonCodec.encode(duplicate));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolResultJsonCodec.exceedsEncodedUtf8Bytes(duplicate, 1024));
  }
}
