package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;

/** 每个 reasoning item 的可比较文本：可用摘要优先，否则读取 content；两者绝不拼接。 */
final class OpenAiResponsesReasoningText {

  private OpenAiResponsesReasoningText() {}

  static String read(JsonNode item) {
    String summary = readBlocks(item.get("summary"), "summary_text");
    if (!summary.isBlank()) {
      return summary;
    }
    String content = readBlocks(item.get("content"), "reasoning_text");
    return content.isBlank() ? "" : content;
  }

  private static String readBlocks(JsonNode blocks, String type) {
    if (blocks == null) {
      return "";
    }
    // producer 既有的 summary 字符串归一化；consumer 在调用前仍严格要求数组。
    if ("summary_text".equals(type) && blocks.isTextual()) {
      return blocks.textValue();
    }
    StringBuilder text = new StringBuilder();
    if (blocks.isArray()) {
      for (JsonNode block : blocks) {
        if (block.isObject()
            && type.equals(block.path("type").asText())
            && block.path("text").isTextual()) {
          text.append(block.get("text").textValue());
        }
      }
    }
    return text.toString();
  }
}
