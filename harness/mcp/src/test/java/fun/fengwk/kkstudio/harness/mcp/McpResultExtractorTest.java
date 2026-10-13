package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.json.BoundedOutputStream;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** MCP 结果提取器测试：验证多模态内容无损提取与非文本结构保留。 */
class McpResultExtractorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final McpResultExtractor extractor = new McpResultExtractor();

  /** 验证纯文本内容正确映射为 TextResultContent。 */
  @Test
  void extractsTextContent() throws Exception {
    JsonNode node = MAPPER.readTree("[{\"type\":\"text\",\"text\":\"hello world\"}]");
    ToolExecutionResult result = extractor.extract(node, false);
    assertThat(result.isError()).isFalse();

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst()).isInstanceOf(TextResultContent.class);
    assertThat(((TextResultContent) contents.getFirst()).text()).isEqualTo("hello world");
  }

  /** 验证图像 Base64 正确映射为 BinaryResultContent。 */
  @Test
  void extractsImageContent() throws Exception {
    JsonNode node =
        MAPPER.readTree("[{\"type\":\"image\",\"data\":\"AQID\",\"mimeType\":\"image/png\"}]");
    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst()).isInstanceOf(BinaryResultContent.class);
    BinaryResultContent binary = (BinaryResultContent) contents.getFirst();
    assertThat(binary.mediaType()).isEqualTo("image/png");
    assertThat(binary.content()).containsExactly(1, 2, 3);
  }

  /** 验证缺省 mimeType 时使用默认二进制媒体类型。 */
  @Test
  void extractsImageWithBlankMimeTypeUsesDefault() throws Exception {
    JsonNode node =
        MAPPER.readTree("[{\"type\":\"image\",\"data\":\"AQID\",\"mimeType\":\"   \"}]");
    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    BinaryResultContent binary = (BinaryResultContent) contents.getFirst();
    assertThat(binary.mediaType()).isEqualTo("application/octet-stream");
  }

  /** 验证非法 Base64 图像作为稳定 JSON 保留结构。 */
  @Test
  void extractsInvalidBase64AsJson() throws Exception {
    JsonNode node =
        MAPPER.readTree(
            "[{\"type\":\"image\",\"data\":\"not_base_64!!!\",\"mimeType\":\"image/png\"}]");
    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst()).isInstanceOf(JsonResultContent.class);
  }

  /** 验证 resource 与复合内容完整保留无损结构。 */
  @Test
  void extractsResourceAndMixedContents() throws Exception {
    String json =
        "["
            + "{\"type\":\"text\",\"text\":\"info\"},"
            + "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///test.txt\",\"text\":\"data\"}},"
            + "{\"type\":\"custom_type\",\"foo\":123}"
            + "]";
    JsonNode node = MAPPER.readTree(json);
    ToolExecutionResult result = extractor.extract(node, true);
    assertThat(result.isError()).isTrue();

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(3);
    assertThat(contents.get(0)).isInstanceOf(TextResultContent.class);
    assertThat(contents.get(1)).isInstanceOf(JsonResultContent.class);
    assertThat(contents.get(2)).isInstanceOf(JsonResultContent.class);
  }

  /** 验证单节点非数组以及纯文本节点输入。 */
  @Test
  void extractsSingleObjectOrTextNode() throws Exception {
    JsonNode objectNode = MAPPER.readTree("{\"type\":\"text\",\"text\":\"single\"}");
    ToolExecutionResult objectResult = extractor.extract(objectNode, false);
    @SuppressWarnings("unchecked")
    List<ResultContent> objectContents = (List<ResultContent>) objectResult.result();
    assertThat(objectContents).hasSize(1);
    assertThat(objectContents.getFirst()).isInstanceOf(TextResultContent.class);

    JsonNode textNode = MAPPER.readTree("\"plain string\"");
    ToolExecutionResult textResult = extractor.extract(textNode, false);
    @SuppressWarnings("unchecked")
    List<ResultContent> textContents = (List<ResultContent>) textResult.result();
    assertThat(textContents).hasSize(1);
    assertThat(((TextResultContent) textContents.getFirst()).text()).isEqualTo("plain string");
  }

  /** 结构化 JSON 超过单条 1 MiB 时必须 fail closed：不能物化成同尺寸无界文本，也不能吞掉超限继续返回内容。 */
  @Test
  void rejectsStructuredJsonAboveSingleItemLimit() {
    String largeValue = "x".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES + 100);
    JsonNode node = MAPPER.createObjectNode().put("type", "huge").put("data", largeValue);

    assertThatThrownBy(() -> extractor.extract(node, false))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(JsonResultContent.MAX_JSON_UTF8_BYTES));
  }

  /** 恰好不超过单条 JSON 上限的结构仍保留为 JsonResultContent，并计入累计预算。 */
  @Test
  void retainsStructuredJsonAtSingleItemLimit() {
    int overhead = "{\"type\":\"huge\",\"data\":\"\"}".getBytes(StandardCharsets.UTF_8).length;
    String largeValue = "x".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES - overhead);
    JsonNode node = MAPPER.createObjectNode().put("type", "huge").put("data", largeValue);

    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).singleElement().isInstanceOf(JsonResultContent.class);
    assertThat(((JsonResultContent) contents.getFirst()).json().getBytes(StandardCharsets.UTF_8))
        .hasSize(JsonResultContent.MAX_JSON_UTF8_BYTES);
  }

  /** 多条各自合法的文本合计超过 64 MiB 时拒绝，不能靠拆条绕过累计预算。 */
  @Test
  void rejectsAggregateTextAboveContentBudget() {
    int chunk = 1024 * 1024;
    ArrayNode array = MAPPER.createArrayNode();
    int count = McpResultExtractor.MAX_CONTENT_BUDGET_BYTES / chunk + 1;
    for (int index = 0; index < count; index++) {
      array.addObject().put("type", "text").put("text", "x".repeat(chunk));
    }

    assertThatThrownBy(() -> extractor.extract(array, false))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES));
  }

  /** 解码前按 encoded 长度拒绝超预算图片，不能先分配完整 decoded 数组再失败。 */
  @Test
  void rejectsBase64ImageBeforeDecodingWhenEncodedLengthExceedsBudget() {
    int encodedChars =
        Math.toIntExact(((long) McpResultExtractor.MAX_CONTENT_BUDGET_BYTES / 3 + 1) * 4);
    ObjectNode image = MAPPER.createObjectNode();
    image.put("type", "image");
    image.put("data", "A".repeat(encodedChars));
    image.put("mimeType", "image/png");

    assertThatThrownBy(() -> extractor.extract(image, false))
        .isInstanceOf(McpException.class)
        .hasMessageContaining(String.valueOf(McpResultExtractor.MAX_CONTENT_BUDGET_BYTES));
  }

  /** 非法 Base64 预先扣除的 decoded 上界必须退回，后续合法内容不被误判超预算。 */
  @Test
  void refundsBudgetAfterInvalidBase64Image() throws Exception {
    JsonNode node =
        MAPPER.readTree(
            "[{\"type\":\"image\",\"data\":\"not_base_64!!!\",\"mimeType\":\"image/png\"},"
                + "{\"type\":\"text\",\"text\":\"after\"}]");

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) extractor.extract(node, false).result();

    assertThat(contents).hasSize(2);
    assertThat(contents.get(0)).isInstanceOf(JsonResultContent.class);
    assertThat(((TextResultContent) contents.get(1)).text()).isEqualTo("after");
  }

  /** 文本单元之间的换行合并必须只在存在多个文本时插入分隔符。 */
  @Test
  void joinsOnlyMultipleTextParts() throws Exception {
    JsonNode single = MAPPER.readTree("[{\"type\":\"text\",\"text\":\"only\"}]");
    assertThat(extractor.extract(single, false).resultText()).isEqualTo("only");

    JsonNode multiple =
        MAPPER.readTree(
            "[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]");
    assertThat(extractor.extract(multiple, false).resultText()).isEqualTo("first\nsecond");
  }

  /** null 与裸值单元必须安全降级，绝不抛出二次异常。 */
  @Test
  void handlesNullAndScalarItemsSafely() throws Exception {
    JsonNode withNull = MAPPER.readTree("[null,{\"type\":\"text\",\"text\":\"ok\"}]");
    @SuppressWarnings("unchecked")
    List<ResultContent> contents =
        (List<ResultContent>) extractor.extract(withNull, false).result();
    assertThat(contents).hasSize(2);
    assertThat(((TextResultContent) contents.getFirst()).text()).isEmpty();

    JsonNode scalar = MAPPER.readTree("42");
    @SuppressWarnings("unchecked")
    List<ResultContent> scalarContents =
        (List<ResultContent>) extractor.extract(scalar, false).result();
    assertThat(scalarContents).hasSize(1);
    assertThat(scalarContents.getFirst()).isInstanceOf(JsonResultContent.class);
  }

  /** 验证空 content 返回默认空文本单元。 */
  @Test
  void extractsEmptyContent() throws Exception {
    JsonNode node = MAPPER.readTree("[]");
    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst()).isInstanceOf(TextResultContent.class);
    assertThat(((TextResultContent) contents.getFirst()).text()).isEmpty();
  }

  /**
   * 结构化内容经 SDK 的 Jackson 2 节点边界写出：多字节 UTF-8 按实际输出字节精确判定边界，超限一字节即拒绝。
   *
   * <p>同时锁定默认 profile 不对 BMP 非 ASCII（中文）转义，因此这里必须是有界写出的原文。
   */
  @Test
  void writeBoundedJsonCountsMultibyteUtf8AtExactBoundary() {
    ObjectNode node = MAPPER.createObjectNode().put("type", "huge").put("data", "你好世界");
    String written = McpResultExtractor.writeBoundedJson(node, Integer.MAX_VALUE);
    assertThat(written).contains("你好世界").doesNotContain("\\u");
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes)).isEqualTo(written);
    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes - 1)).isNull();
  }

  /** emoji（代理对）同样按 Jackson 2 输出流实际字节判定边界，临界点保留、少一字节即超限。 */
  @Test
  void writeBoundedJsonCountsEmojiAtExactBoundary() {
    ObjectNode node = MAPPER.createObjectNode().put("emoji", "😀🚀");
    String written = McpResultExtractor.writeBoundedJson(node, Integer.MAX_VALUE);
    int exactBytes = written.getBytes(StandardCharsets.UTF_8).length;

    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes)).isEqualTo(written);
    assertThat(McpResultExtractor.writeBoundedJson(node, exactBytes - 1)).isNull();
  }

  /** 远超上限的节点在写出期提前中止并返回 null，不物化完整输出。 */
  @Test
  void writeBoundedJsonAbortsEarlyOnOversizedNode() {
    ObjectNode node = MAPPER.createObjectNode().put("big", "x".repeat(10 * 1024 * 1024));

    assertThat(McpResultExtractor.writeBoundedJson(node, 1024)).isNull();
  }

  /** 恰好不超过单条 JSON 上限的多字节结构仍被保留，并按实际 UTF-8 字节数计入 1 MiB 上限。 */
  @Test
  void retainsMultibyteStructuredJsonAtSingleItemLimit() {
    int overhead = "{\"type\":\"huge\",\"data\":\"\"}".getBytes(StandardCharsets.UTF_8).length;
    int remaining = JsonResultContent.MAX_JSON_UTF8_BYTES - overhead;
    String value = "你".repeat(remaining / 3) + "x".repeat(remaining % 3);
    JsonNode node = MAPPER.createObjectNode().put("type", "huge").put("data", value);

    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).singleElement().isInstanceOf(JsonResultContent.class);
    assertThat(((JsonResultContent) contents.getFirst()).json().getBytes(StandardCharsets.UTF_8))
        .hasSize(JsonResultContent.MAX_JSON_UTF8_BYTES);
  }

  /** 非超限的序列化失败必须收敛为固定不透明 McpException，绝不回显内容或保留底层 cause。 */
  @Test
  void writeBoundedJsonMakesGenericSerializationFailureOpaque() {
    pendingSerializerFailure = new IOException("generic-secret");
    try {
      assertThatThrownBy(() -> McpResultExtractor.writeBoundedJson(failingValueNode(), 1024))
          .isInstanceOf(McpException.class)
          .hasMessage("MCP content cannot be encoded as bounded JSON")
          .hasNoCause()
          .satisfies(error -> assertThat(error.getMessage()).doesNotContain("generic-secret"));
    } finally {
      pendingSerializerFailure = null;
    }
  }

  /**
   * 超限识别沿 cause 链只看有界输出流自身的 {@link BoundedOutputStream.LimitExceededException}：被序列化层包装的超限也必须判为超限。
   */
  @Test
  void writeBoundedJsonRecognizesWrappedLimitExceeded() throws IOException {
    pendingSerializerFailure = new IOException("wrapper", capturedLimitExceeded());
    try {
      assertThat(McpResultExtractor.writeBoundedJson(failingValueNode(), 1024)).isNull();
    } finally {
      pendingSerializerFailure = null;
    }
  }

  /** 序列化器在写出期直接抛出超限中止时同样返回 null，而不是被当成普通编码失败。 */
  @Test
  void writeBoundedJsonRecognizesDirectLimitExceeded() throws IOException {
    pendingSerializerFailure = capturedLimitExceeded();
    try {
      assertThat(McpResultExtractor.writeBoundedJson(failingValueNode(), 1024)).isNull();
    } finally {
      pendingSerializerFailure = null;
    }
  }

  /** 让序列化器抛出预设失败的 POJO，用于验证超限与通用失败的区分。 */
  @JsonSerialize(using = FailingSerializer.class)
  private static final class FailingValue {}

  private static final class FailingSerializer extends JsonSerializer<FailingValue> {
    @Override
    public void serialize(FailingValue value, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      throw pendingSerializerFailure;
    }
  }

  /** 预设的序列化失败；测试之间串行执行，用静态哨兵模拟写出期异常。 */
  private static volatile IOException pendingSerializerFailure;

  private static JsonNode failingValueNode() {
    return JsonNodeFactory.instance.pojoNode(new FailingValue());
  }

  /** 使用真实有界输出流产生一个超限中止异常，供超限识别断言使用。 */
  private static BoundedOutputStream.LimitExceededException capturedLimitExceeded()
      throws IOException {
    BoundedOutputStream out = new BoundedOutputStream(1);
    try {
      out.write(new byte[] {1, 2});
    } catch (BoundedOutputStream.LimitExceededException error) {
      return error;
    }
    throw new AssertionError("expected BoundedOutputStream to abort over limit");
  }
}
