package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.List;

/**
 * 把 ASSISTANT 消息编码为 OpenAI Chat 的 wire message：affinity 匹配时原样回放 native payload，否则语义回退。
 *
 * <p>语义回退把 durable 文本、思考与 JSON 诊断合并为 {@code content}，工具调用编码为 {@code tool_calls}；native opaque 与
 * redacted thinking 没有跨模型的语义投影，直接忽略。
 */
final class OpenAiChatAssistantMessageEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** 回放时值为 null 即视为未声明的 known 字段；与语义 fallback 的省略规则保持一致。 */
  private static final List<String> NULLABLE_REPLAY_FIELDS =
      List.of("content", "refusal", "reasoning_content", "reasoning_details", "tool_calls");

  private OpenAiChatAssistantMessageEncoder() {}

  static ObjectNode encode(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    ObjectNode replayPayload =
        OpenAiChatAssistantReplayEncoder.build(message, descriptor, requestedModel);
    if (replayPayload != null) {
      OpenAiChatReplayValidator.validate(replayPayload, message.contents());
      return buildReplayMessage(replayPayload);
    }
    return encodeSemanticFallback(message.contents());
  }

  /**
   * 从 native replay payload 构造 wire assistant message。
   *
   * <p>provider 返回的其他合法 assistant 字段（如请求侧仅含 id 的 {@code audio}、{@code function_call}
   * 及未识别的未来字段）属于厂商原生事实，必须保留；known 字段为 null 时与语义 fallback 一样省略。
   */
  private static ObjectNode buildReplayMessage(JsonNode payload) {
    ObjectNode msgNode = (ObjectNode) payload.deepCopy();
    msgNode.put("role", "assistant");
    for (String field : NULLABLE_REPLAY_FIELDS) {
      JsonNode value = msgNode.get(field);
      if (value != null && value.isNull()) {
        msgNode.remove(field);
      }
    }
    return msgNode;
  }

  private static ObjectNode encodeSemanticFallback(List<ProviderContentBlock> contents) {
    ObjectNode msgNode = NODES.objectNode();
    msgNode.put("role", "assistant");
    StringBuilder text = new StringBuilder();
    ArrayNode toolCalls = NODES.arrayNode();
    for (ProviderContentBlock block : contents) {
      if (block instanceof ProviderTextBlock textBlock) {
        text.append(textBlock.text());
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        if (!thinkingBlock.thinking().isEmpty()) {
          text.append(thinkingBlock.thinking());
        }
      } else if (block instanceof ProviderJsonBlock jsonBlock) {
        // assistant JSON 诊断（如 tool_call_diagnostic）降级为普通文本回放。
        text.append(jsonBlock.json());
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        toolCalls.add(encodeToolCall(toolCallBlock.toolCall()));
      } else if (!(block instanceof ProviderOpaqueBlock)
          && !(block instanceof ProviderRedactedThinkingBlock)) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST,
            "unsupported block in ASSISTANT message: " + block.getClass().getSimpleName());
      }
    }
    if (!text.isEmpty() || toolCalls.isEmpty()) {
      msgNode.put("content", text.toString());
    }
    if (!toolCalls.isEmpty()) {
      msgNode.set("tool_calls", toolCalls);
    }
    return msgNode;
  }

  private static ObjectNode encodeToolCall(ProviderToolCall call) {
    ProviderJsonHelper.parseStrictJsonObject(
        call.argumentsJson(), "toolCall argumentsJson must be a JSON object");
    ObjectNode callNode = NODES.objectNode();
    callNode.put("id", call.id());
    callNode.put("type", "function");
    ObjectNode function = callNode.putObject("function");
    function.put("name", call.name());
    function.put("arguments", call.argumentsJson());
    return callNode;
  }
}
