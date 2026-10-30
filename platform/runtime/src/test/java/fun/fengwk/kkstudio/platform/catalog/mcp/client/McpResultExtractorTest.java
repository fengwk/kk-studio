package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 结果提取器测试：验证多模态内容无损提取、content 优先与结构化回退、错误映射和预算边界。 */
class McpResultExtractorTest {

  private final McpResultExtractor extractor = new McpResultExtractor();

  /** 验证纯文本内容正确映射为 TextResultContent。 */
  @Test
  void extractsTextContent() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder().addTextContent("hello world").build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.error()).isFalse();
    assertThat(mapped.contents()).containsExactly(new TextResultContent("hello world"));
  }

  /** 验证图像 Base64 正确映射为 BinaryResultContent。 */
  @Test
  void extractsImageContent() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.ImageContent.builder("AQID", "image/png").build())
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.contents()).hasSize(1);
    BinaryResultContent binary = (BinaryResultContent) mapped.contents().getFirst();
    assertThat(binary.mediaType()).isEqualTo("image/png");
    assertThat(binary.content()).containsExactly(1, 2, 3);
  }

  /** 验证缺省 mimeType 时使用默认二进制媒体类型。 */
  @Test
  void extractsImageWithBlankMimeTypeUsesDefault() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.ImageContent.builder("AQID", "   ").build())
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(((BinaryResultContent) mapped.contents().getFirst()).mediaType())
        .isEqualTo("application/octet-stream");
  }

  /** 验证音频内容同样映射为二进制，并使用音频默认媒体类型。 */
  @Test
  void extractsAudioContent() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.AudioContent.builder("AQID", "  ").build())
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(((BinaryResultContent) mapped.contents().getFirst()).mediaType())
        .isEqualTo("audio/wav");
  }

  /** 验证非法 Base64 图像作为稳定 JSON 保留结构，并保留 type 判别字段。 */
  @Test
  void extractsInvalidBase64AsJson() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.ImageContent.builder("not_base_64!!!", "image/png").build())
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.contents()).hasSize(1);
    assertThat(mapped.contents().getFirst()).isInstanceOf(JsonResultContent.class);
    String json = ((JsonResultContent) mapped.contents().getFirst()).json();
    assertThat(json).contains("\"type\":\"image\"").contains("\"mimeType\":\"image/png\"");
  }

  /** 验证 resource 与混合内容完整保留无损结构。 */
  @Test
  void extractsResourceAndMixedContents() {
    McpSchema.ResourceContents resource =
        McpSchema.TextResourceContents.builder("file:///test.txt", "data").build();
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addTextContent("info")
            .addContent(McpSchema.EmbeddedResource.builder(resource).build())
            .addContent(
                McpSchema.ResourceLink.builder()
                    .name("link")
                    .uri("file:///link.txt")
                    .mimeType("text/plain")
                    .build())
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.error()).isFalse();
    assertThat(mapped.contents()).hasSize(3);
    assertThat(mapped.contents().get(0)).isEqualTo(new TextResultContent("info"));
    assertThat(mapped.contents().get(1)).isInstanceOf(JsonResultContent.class);
    assertThat(((JsonResultContent) mapped.contents().get(1)).json())
        .contains("\"type\":\"resource\"")
        .contains("\"uri\":\"file:///test.txt\"")
        .contains("\"text\":\"data\"");
    assertThat(mapped.contents().get(2)).isInstanceOf(JsonResultContent.class);
    assertThat(((JsonResultContent) mapped.contents().get(2)).json())
        .contains("\"type\":\"resource_link\"")
        .contains("\"uri\":\"file:///link.txt\"")
        .contains("\"name\":\"link\"");
  }

  /** 普通 content 优先以保持既有映射；_meta 只保留业务详情。 */
  @Test
  void prefersContentAndFiltersProtocolMeta() {
    Map<String, Object> structured = new LinkedHashMap<>();
    structured.put("answer", 42);
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("traceId", "t-1");
    meta.put("io.modelcontextprotocol/serverInfo", Map.of("name", "fake"));
    meta.put("com.mcp/tool", "reserved");
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addTextContent("hello")
            .structuredContent(structured)
            .meta(meta)
            .build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.error()).isFalse();
    assertThat(mapped.contents()).containsExactly(new TextResultContent("hello"));
    assertThat(mapped.detailsJson()).isEqualTo("{\"traceId\":\"t-1\"}");
  }

  /** content 为空时才使用结构化结果，并把它写成可读 JSON 文本。 */
  @Test
  void mapsStructuredContentWhenContentIsEmpty() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder().structuredContent(Map.of("answer", 42)).build();

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.error()).isFalse();
    assertThat(mapped.contents()).containsExactly(new TextResultContent("{\"answer\":42}"));
  }

  /** 错误结果映射为可读错误文本；结构化错误同样保留其 JSON 文本。 */
  @Test
  void mapsErrorResultsToReadableText() {
    McpToolCallResult textError =
        extractor.extract(
            McpSchema.CallToolResult.builder()
                .addTextContent("first")
                .addTextContent("second")
                .isError(true)
                .build());
    assertThat(textError.error()).isTrue();
    assertThat(textError.contents()).containsExactly(new TextResultContent("first\nsecond"));

    McpToolCallResult structuredError =
        extractor.extract(
            McpSchema.CallToolResult.builder()
                .structuredContent(Map.of("reason", "boom"))
                .isError(true)
                .build());
    assertThat(structuredError.contents())
        .containsExactly(new TextResultContent("{\"reason\":\"boom\"}"));

    McpToolCallResult mixedError =
        extractor.extract(
            McpSchema.CallToolResult.builder()
                .addTextContent("first")
                .structuredContent(Map.of("reason", "boom"))
                .isError(true)
                .build());
    assertThat(mixedError.contents()).containsExactly(new TextResultContent("first"));
  }

  /** 既无文本也无结构化内容的错误结果给出固定兜底文案。 */
  @Test
  void mapsEmptyErrorResultToCanonicalFallback() {
    McpToolCallResult mapped =
        extractor.extract(McpSchema.CallToolResult.builder().isError(true).build());

    assertThat(mapped.contents())
        .containsExactly(new TextResultContent("MCP tool returned an error result"));
  }

  /** 空 content 返回默认空文本单元。 */
  @Test
  void extractsEmptyContent() {
    McpToolCallResult mapped = extractor.extract(McpSchema.CallToolResult.builder().build());

    assertThat(mapped.contents()).containsExactly(new TextResultContent(""));
  }

  /** null 内容项必须安全降级，绝不抛出二次异常。 */
  @Test
  void handlesNullContentItemSafely() {
    McpSchema.CallToolResult result =
        new McpSchema.CallToolResult(
            Arrays.asList(null, McpSchema.TextContent.builder("ok").build()), false, null, null);

    McpToolCallResult mapped = extractor.extract(result);

    assertThat(mapped.contents())
        .containsExactly(new TextResultContent(""), new TextResultContent("ok"));
  }

  /** 累计文本超过 64 MiB 时拒绝，不能靠拆条绕过累计预算。 */
  @Test
  void rejectsAggregateTextAboveContentBudget() {
    String text = "x".repeat(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES + 1);

    assertThatThrownBy(
            () ->
                extractor.extract(McpSchema.CallToolResult.builder().addTextContent(text).build()))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES));
  }

  /** 错误结果的可读文本同样受累计预算约束，不能绕过成功结果的 64 MiB 上限。 */
  @Test
  void rejectsAggregateErrorTextAboveContentBudget() {
    String text = "x".repeat(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES + 1);

    assertThatThrownBy(
            () ->
                extractor.extract(
                    McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build()))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES));
  }

  /** 解码前按 encoded 长度拒绝超预算图片，不能先分配完整 decoded 数组再失败。 */
  @Test
  void rejectsBase64ImageBeforeDecodingWhenEncodedLengthExceedsBudget() {
    int encodedChars =
        Math.toIntExact(((long) McpResultExtractor.MAX_CONTENT_BUDGET_BYTES / 3 + 1) * 4);
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(
                McpSchema.ImageContent.builder("A".repeat(encodedChars), "image/png").build())
            .build();

    assertThatThrownBy(() -> extractor.extract(result))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES));
  }

  /** 非法 Base64 预先扣除的 decoded 上界必须退回，后续合法内容不被误判超预算。 */
  @Test
  void refundsBudgetAfterInvalidBase64Image() {
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.ImageContent.builder("not_base_64!!!", "image/png").build())
            .addTextContent("after")
            .build();

    List<ResultContent> contents = extractor.extract(result).contents();

    assertThat(contents).hasSize(2);
    assertThat(contents.get(0)).isInstanceOf(JsonResultContent.class);
    assertThat(((TextResultContent) contents.get(1)).text()).isEqualTo("after");
  }

  /**
   * 结构化内容经有界输出流写出：多字节 UTF-8 按实际输出字节精确判定边界，超限一字节即拒绝。
   *
   * <p>同时锁定默认 profile 不对 BMP 非 ASCII（中文）转义，因此这里必须是有界写出的原文。
   */
  @Test
  void writeBoundedJsonCountsMultibyteUtf8AtExactBoundary() {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "huge");
    node.put("data", "你好世界");
    String written = McpResultExtractor.writeBoundedJson(node, Integer.MAX_VALUE);
    assertThat(written).contains("你好世界").doesNotContain("\\u");
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes)).isEqualTo(written);
    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes - 1)).isNull();
  }

  /** emoji（代理对）同样按实际输出字节判定边界，临界点保留、少一字节即超限。 */
  @Test
  void writeBoundedJsonCountsEmojiAtExactBoundary() {
    Map<String, Object> node = Map.of("emoji", "😀🚀");
    String written = McpResultExtractor.writeBoundedJson(node, Integer.MAX_VALUE);
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes)).isEqualTo(written);
    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes - 1)).isNull();
  }

  /** 远超上限的节点在写出期提前中止并返回 null，不物化完整输出。 */
  @Test
  void writeBoundedJsonAbortsEarlyOnOversizedNode() {
    Map<String, Object> node = Map.of("big", "x".repeat(10 * 1024 * 1024));

    assertThat(McpResultExtractor.writeBoundedJson(node, 1024)).isNull();
  }

  /** 非超限的序列化失败必须收敛为固定不透明 McpException，绝不回显内容或保留底层 cause。 */
  @Test
  void writeBoundedJsonMakesGenericSerializationFailureOpaque() {
    Map<String, Object> cyclic = new LinkedHashMap<>();
    cyclic.put("self", cyclic);

    assertThatThrownBy(() -> McpResultExtractor.writeBoundedJson(cyclic, 1024))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP content cannot be encoded as bounded JSON")
        .hasNoCause();
  }
}
