package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;

/**
 * 已知 reasoning item 的可比较文本与 {@code content} 形状校验，供 producer 与 consumer 复用。
 *
 * <p>可比较文本按 item 取值：非空白 {@code summary[].summary_text} 优先，没有可用摘要时读取 {@code
 * content[].reasoning_text}，两者绝不拼接。{@code content} 是已知语义输入，其已知坏形状必须拒绝而不是静默忽略； 错误分类由调用方决定（producer
 * 捕获阶段 {@code INVALID_RESPONSE}，consumer 回放校验 {@code INVALID_REQUEST}），异常消息绝不回显文本或密文。
 */
final class OpenAiResponsesReasoningText {

  private OpenAiResponsesReasoningText() {}

  /** 严格校验 {@code content} 的形状：缺失合法；否则必须是 {@code reasoning_text} 块数组，额外成员无损保留。 */
  static void requireValidContent(JsonNode content, ProviderErrorKind errorKind) {
    if (content == null) {
      return;
    }
    if (!content.isArray()) {
      throw new ProviderException(errorKind, "reasoning content must be an array");
    }
    for (JsonNode block : content) {
      if (!block.isObject()) {
        throw new ProviderException(errorKind, "reasoning content block must be an object");
      }
      JsonNode type = block.get("type");
      if (type == null || !type.isTextual() || !"reasoning_text".equals(type.textValue())) {
        throw new ProviderException(
            errorKind, "reasoning content block type must be 'reasoning_text'");
      }
      if (!block.path("text").isTextual()) {
        throw new ProviderException(errorKind, "reasoning content block must have string text");
      }
    }
  }

  /** 取 item 的可比较文本；调用前先按调用方分类严格校验 {@code content} 形状。 */
  static String read(JsonNode item, ProviderErrorKind contentErrorKind) {
    requireValidContent(item.get("content"), contentErrorKind);
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
