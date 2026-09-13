package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

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

  /** 验证超限大结构 JSON 安全退化为纯文本而绝不抛出二次异常。 */
  @Test
  void fallsBackToTextWhenJsonExceedsLimit() throws Exception {
    String largeValue = "x".repeat(1024 * 1024 + 100);
    JsonNode node = MAPPER.createObjectNode().put("type", "huge").put("data", largeValue);
    ToolExecutionResult result = extractor.extract(node, false);

    @SuppressWarnings("unchecked")
    List<ResultContent> contents = (List<ResultContent>) result.result();
    assertThat(contents).hasSize(1);
    assertThat(contents.getFirst()).isInstanceOf(TextResultContent.class);
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
}
