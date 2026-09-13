package fun.fengwk.kkstudio.harness.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpToolResultExtractor;
import dev.langchain4j.service.tool.ToolExecutionResult;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 完整保留 MCP text/image/resource 等 SDK 内容的工具执行结果抽取器。
 *
 * <ul>
 *   <li>text 内容映射为 {@link TextResultContent}；
 *   <li>image 内容（Base64）映射为 {@link BinaryResultContent}；
 *   <li>resource 或其他非文本结构化内容无损映射为稳定 JSON {@link JsonResultContent}，不丢弃元数据；
 *   <li>在 JSON 转化遇到尺寸超限或非法格式时安全退化为文本，绝不触发二次异常。
 * </ul>
 */
public final class McpResultExtractor implements McpToolResultExtractor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public ToolExecutionResult extract(JsonNode contentNode, boolean isError) {
    List<ResultContent> contents = new ArrayList<>();
    if (contentNode != null) {
      if (contentNode.isArray()) {
        for (JsonNode item : contentNode) {
          contents.add(parseItem(item));
        }
      } else {
        contents.add(parseItem(contentNode));
      }
    }
    if (contents.isEmpty()) {
      contents.add(new TextResultContent(""));
    }
    return ToolExecutionResult.builder()
        .isError(isError)
        .result(contents)
        .resultText(extractCombinedText(contents))
        .build();
  }

  private static ResultContent parseItem(JsonNode item) {
    if (item == null || item.isNull()) {
      return new TextResultContent("");
    }
    if (item.isTextual()) {
      return new TextResultContent(item.asText(""));
    }
    String type = item.has("type") ? item.get("type").asText() : "";
    if ("text".equals(type) && item.has("text")) {
      return new TextResultContent(item.get("text").asText(""));
    }
    if ("image".equals(type) && item.has("data")) {
      String data = item.get("data").asText("");
      String mimeType =
          item.has("mimeType") && !item.get("mimeType").asText("").isBlank()
              ? item.get("mimeType").asText()
              : "application/octet-stream";
      try {
        byte[] bytes = Base64.getDecoder().decode(data);
        return new BinaryResultContent(mimeType, bytes);
      } catch (IllegalArgumentException ignored) {
        // Base64 解码异常时保留原始结构
        return safeJsonOrText(item);
      }
    }
    // resource 或任意扩展类型结构化内容：无损映射为稳定 JSON，失败时退化为文本
    return safeJsonOrText(item);
  }

  private static ResultContent safeJsonOrText(JsonNode node) {
    if (node == null || node.isNull()) {
      return new TextResultContent("");
    }
    if (node.isTextual()) {
      return new TextResultContent(node.asText(""));
    }
    try {
      String json = OBJECT_MAPPER.writeValueAsString(node);
      return new JsonResultContent(json);
    } catch (Exception error) {
      return new TextResultContent(node.asText(node.toString()));
    }
  }

  private static String extractCombinedText(List<ResultContent> contents) {
    StringBuilder sb = new StringBuilder();
    for (ResultContent content : contents) {
      if (content instanceof TextResultContent textContent) {
        if (!sb.isEmpty()) {
          sb.append("\n");
        }
        sb.append(textContent.text());
      }
    }
    return sb.toString();
  }
}
