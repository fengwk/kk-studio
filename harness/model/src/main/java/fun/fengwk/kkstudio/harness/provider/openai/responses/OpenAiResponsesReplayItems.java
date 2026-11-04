package fun.fengwk.kkstudio.harness.provider.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 把 Responses 权威输出项整理为可回放形态，保留不参与规范化的原生字段。 */
final class OpenAiResponsesReplayItems {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiResponsesReplayItems() {}

  /** 深拷贝 message，只修复角色与可读内容，保留注解及其它原生字段。 */
  static ObjectNode normalizeMessage(JsonNode item) {
    ObjectNode msg = (ObjectNode) item.deepCopy();
    msg.put("type", "message");
    msg.put("role", "assistant");
    dropNonTextualValue(msg, "id");
    dropNonTextualValue(msg, "phase");

    ArrayNode contentArr = NODES.arrayNode();
    JsonNode srcContent = item.get("content");
    if (srcContent != null && srcContent.isArray()) {
      for (JsonNode c : srcContent) {
        if (!c.isObject()) {
          continue;
        }
        String blockType = c.path("type").asString("");
        if ("output_text".equals(blockType)) {
          ObjectNode textNode = (ObjectNode) c.deepCopy();
          textNode.put("text", c.path("text").asString(""));
          contentArr.add(textNode);
        } else if ("refusal".equals(blockType)) {
          ObjectNode refusalNode = (ObjectNode) c.deepCopy();
          refusalNode.put("refusal", c.path("refusal").asString(""));
          contentArr.add(refusalNode);
        }
      }
    } else if (srcContent != null && srcContent.isString()) {
      ObjectNode textNode = contentArr.addObject();
      textNode.put("type", "output_text");
      textNode.put("text", srcContent.asString(""));
    }
    msg.set("content", contentArr);
    return msg;
  }

  /** 深拷贝 reasoning，仅规范化摘要并去除不可回放的空标识与密文。 */
  static ObjectNode normalizeReasoning(JsonNode item) {
    ObjectNode reasoning = (ObjectNode) item.deepCopy();
    reasoning.put("type", "reasoning");
    dropNonTextualValue(reasoning, "id");
    dropNonTextualValue(reasoning, "encrypted_content");

    JsonNode summary = item.get("summary");
    if (summary == null) {
      return reasoning;
    }
    if (summary.isArray()) {
      ArrayNode sumArr = NODES.arrayNode();
      for (JsonNode s : summary) {
        if (!s.isObject() || !"summary_text".equals(s.path("type").asString(""))) {
          continue;
        }
        ObjectNode sNode = (ObjectNode) s.deepCopy();
        sNode.put("text", s.path("text").asString(""));
        sumArr.add(sNode);
      }
      reasoning.set("summary", sumArr);
      return reasoning;
    }
    if (summary.isString()) {
      ArrayNode sumArr = NODES.arrayNode();
      ObjectNode sNode = sumArr.addObject();
      sNode.put("type", "summary_text");
      sNode.put("text", summary.asString(""));
      reasoning.set("summary", sumArr);
      return reasoning;
    }
    reasoning.remove("summary");
    return reasoning;
  }

  /** 只保留非空白字符串形态的字段值。 */
  static void dropNonTextualValue(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value != null && nonBlankText(value) == null) {
      node.remove(field);
    }
  }

  static String nonBlankText(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    return nonBlank(node.stringValue());
  }

  static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 仅包含无法承载推理事实的 reasoning 占位符时不冻结 replay。 */
  static boolean isEmptyPlaceholderOnly(ArrayNode replayOutputArray) {
    boolean reasoningItemSeen = false;
    for (JsonNode item : replayOutputArray) {
      if (!"reasoning".equals(item.path("type").asString(""))) {
        continue;
      }
      reasoningItemSeen = true;
      // content 是独立原生事实，即使无可读文本也不是空 reasoning 占位符。
      if (item.has("content")) {
        return false;
      }
      if (!item.path("encrypted_content").asString("").isBlank()) {
        return false;
      }
      JsonNode summary = item.get("summary");
      if (summary != null) {
        for (JsonNode s : summary) {
          if (!s.path("text").asString("").isBlank()) {
            return false;
          }
        }
      }
    }
    return reasoningItemSeen;
  }
}
