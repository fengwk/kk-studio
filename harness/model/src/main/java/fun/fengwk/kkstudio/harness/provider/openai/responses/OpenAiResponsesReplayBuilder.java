package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesReplayItems.dropNonTextualValue;
import static fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesReplayItems.nonBlank;
import static fun.fengwk.kkstudio.harness.provider.openai.responses.OpenAiResponsesReplayItems.nonBlankText;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.provider.ProviderJsonHelper;

import java.util.List;

/**
 * 把 Responses 流的权威输出项重建为 stateless replay 的 {@code output} 数组。
 *
 * <p>已知 {@code message}/{@code reasoning}/{@code function_call} item 以终态/{@code output_item.done}
 * 的权威 item 为唯一事实来源：深拷贝后只修复会让下一轮必然失败或丢语义的字段，其余原生字段全部保留。未知官方 item 作为不透明事实整体透传； 只有显式终态清空输出或没有任何权威 item
 * 时才从流式草稿合成 replay。
 */
final class OpenAiResponsesReplayBuilder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiResponsesReplayBuilder() {}

  /** 流式工具状态的 replay 快照；key 是流内识别该工具的稳定键。 */
  record ReplayTool(
      String key,
      String id,
      String callId,
      String name,
      String arguments,
      boolean argumentsTextual) {}

  /**
   * 组装 replay output 数组。
   *
   * @param rawOutputItems 权威输出项；顺序即 replay 顺序
   * @param explicitTerminalOutputProcessed 是否已用终态 {@code output} 覆盖流式草稿
   * @param thinking 规范化思考文本，仅在需要合成 replay 时使用
   * @param text 规范化回答文本，仅在需要合成 replay 时使用
   * @param tools 流式工具快照，仅在需要合成 replay 时使用
   */
  static ArrayNode build(
      List<JsonNode> rawOutputItems,
      boolean explicitTerminalOutputProcessed,
      String thinking,
      String text,
      List<ReplayTool> tools) {
    ArrayNode array = NODES.arrayNode();
    for (JsonNode item : rawOutputItems) {
      if (!item.isObject()) {
        continue;
      }
      String type = item.path("type").asString("");
      switch (type) {
        case "message" -> array.add(OpenAiResponsesReplayItems.normalizeMessage(item));
        case "reasoning" -> array.add(OpenAiResponsesReplayItems.normalizeReasoning(item));
        case "function_call" -> array.add(normalizeFunctionCall(item, tools));
        default -> {
          // 未知但可信的官方 output item（web_search_call / file_search_call / image_generation_call /
          // 自定义工具 item 等）：保留为不透明事实供下一轮 stateless replay，绝不再静默丢弃。
          if (isOpaqueReplayItem(item)) {
            array.add(item.deepCopy());
          }
        }
      }
    }

    // 若显式处理了 terminal output 且 rawOutputItems 为空（例如 output: []），
    // 则表示终端权威输出清空了所有内容，不得回退合成草稿 replay。
    if (explicitTerminalOutputProcessed && rawOutputItems.isEmpty()) {
      return array;
    }

    // 若 rawOutputItems 为空但有文本或工具调用，合成标准的 replay output
    if (array.isEmpty()) {
      if (!thinking.isEmpty()) {
        ObjectNode reasoning = array.addObject();
        reasoning.put("type", "reasoning");
        ArrayNode sum = reasoning.putArray("summary");
        ObjectNode s = sum.addObject();
        s.put("type", "summary_text");
        s.put("text", thinking);
      }
      if (!text.isEmpty()) {
        ObjectNode msg = array.addObject();
        msg.put("type", "message");
        msg.put("role", "assistant");
        ArrayNode c = msg.putArray("content");
        ObjectNode t = c.addObject();
        t.put("type", "output_text");
        t.put("text", text);
      }
      for (ReplayTool tool : tools) {
        String effectiveCallId = nonBlank(tool.callId()) != null ? tool.callId() : tool.id();
        String args = tool.arguments();
        if (effectiveCallId == null
            || effectiveCallId.isBlank()
            || tool.name() == null
            || tool.name().isBlank()
            || args == null
            || args.isBlank()
            || ProviderJsonHelper.tryParseJsonObject(args) == null) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
        }
        ObjectNode fc = array.addObject();
        fc.put("type", "function_call");
        fc.put("call_id", effectiveCallId);
        fc.put("name", tool.name());
        fc.put("arguments", args);
      }
    }

    return array;
  }

  /**
   * 归一化 replay 中的 function_call item：深拷贝权威 item，只用流式工具状态补齐 durable 语义必需的 {@code call_id}/{@code
   * name}/{@code arguments}，其余 {@code id}/{@code status} 与未来成员原样保留。
   */
  private static ObjectNode normalizeFunctionCall(JsonNode item, List<ReplayTool> tools) {
    String itemId = nonBlankText(item.get("id"));
    String callId = nonBlankText(item.get("call_id"));
    ReplayTool tool = findTool(tools, itemId, callId);

    String effectiveCallId = callId != null ? callId : itemId;

    String name = nonBlankText(item.get("name"));
    if (name == null && tool != null) {
      name = nonBlank(tool.name());
    }

    String args = null;
    JsonNode argumentsNode = item.get("arguments");
    if (argumentsNode != null && argumentsNode.isString()) {
      args = argumentsNode.stringValue();
    } else if (tool != null && tool.argumentsTextual() && !tool.arguments().isEmpty()) {
      args = tool.arguments();
    }

    if (effectiveCallId == null
        || effectiveCallId.isBlank()
        || name == null
        || name.isBlank()
        || args == null
        || args.isBlank()
        || ProviderJsonHelper.tryParseJsonObject(args) == null) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid function call in replay construction");
    }

    ObjectNode fc = (ObjectNode) item.deepCopy();
    fc.put("type", "function_call");
    fc.put("call_id", effectiveCallId);
    fc.put("name", name);
    fc.put("arguments", args);
    dropNonTextualValue(fc, "id");
    return fc;
  }

  /** 与流内工具状态相同的查找优先级：先 id，再 call_id，最后按两个字段线性匹配。 */
  private static ReplayTool findTool(List<ReplayTool> tools, String itemId, String callId) {
    if (itemId != null && !itemId.isBlank()) {
      for (ReplayTool tool : tools) {
        if (itemId.equals(tool.key())) {
          return tool;
        }
      }
    }
    if (callId != null && !callId.isBlank()) {
      for (ReplayTool tool : tools) {
        if (callId.equals(tool.key())) {
          return tool;
        }
      }
    }
    for (ReplayTool tool : tools) {
      if ((itemId != null
              && !itemId.isBlank()
              && (itemId.equals(tool.id()) || itemId.equals(tool.callId())))
          || (callId != null
              && !callId.isBlank()
              && (callId.equals(tool.callId()) || callId.equals(tool.id())))) {
        return tool;
      }
    }
    return null;
  }

  /** 只有具备非空白字符串 {@code type} 的对象才是可回放的协议 item。 */
  static boolean isOpaqueReplayItem(JsonNode item) {
    JsonNode typeNode = item.get("type");
    return typeNode != null && typeNode.isString() && !typeNode.stringValue().isBlank();
  }
}
