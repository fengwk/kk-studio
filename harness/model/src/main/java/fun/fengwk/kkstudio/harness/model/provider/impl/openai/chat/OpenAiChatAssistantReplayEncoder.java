package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

/** 从 assistant 各 block 的 native metadata 重建 OpenAI Chat 的 replay message。 */
final class OpenAiChatAssistantReplayEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiChatAssistantReplayEncoder() {}

  /**
   * 按 format 与 affinity 决定是否原样回放：任一 block 缺少同协议同 affinity 的 metadata、或元素无法表达为 replay payload 时返回
   * {@code null}，由调用方退回语义编码。
   */
  static ObjectNode build(
      ProviderMessage message, ProviderDescriptor descriptor, String requestedModel) {
    ObjectNode result = NODES.objectNode();
    result.put("role", "assistant");
    StringBuilder content = new StringBuilder();
    StringBuilder refusal = new StringBuilder();
    StringBuilder reasoning = new StringBuilder();
    ArrayNode toolCalls = NODES.arrayNode();
    boolean hasTextBlock = false;
    boolean hasReasoningBlock = false;
    for (ProviderContentBlock block : message.contents()) {
      ProviderBlockData data = providerBlockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.OPENAI_CHAT
          || !data.affinity().equals(descriptor.affinity(requestedModel))) {
        return null;
      }
      ObjectNode nativeFields = (ObjectNode) data.nativeFields();
      if (block instanceof ProviderTextBlock textBlock) {
        if (data.refusal()) {
          refusal.append(textBlock.text());
        } else {
          hasTextBlock = true;
          content.append(textBlock.text());
        }
      } else if (block instanceof ProviderThinkingBlock thinkingBlock) {
        hasReasoningBlock = true;
        reasoning.append(thinkingBlock.thinking());
        if (!mergeFields(result, nativeFields)) {
          return null;
        }
      } else if (block instanceof ProviderToolCallBlock toolCallBlock) {
        ProviderToolCall call = toolCallBlock.toolCall();
        ProviderJsonHelper.parseStrictJsonObject(
            call.argumentsJson(), "toolCall argumentsJson must be a JSON object");
        ObjectNode callNode = encodeToolCall(call);
        if (!mergeToolCallFields(callNode, nativeFields)) {
          return null;
        }
        toolCalls.add(callNode);
      } else if (block instanceof ProviderOpaqueBlock opaqueBlock
          && "openai_chat_message".equals(opaqueBlock.blockType())) {
        if (!mergeFields(result, nativeFields)) {
          return null;
        }
      } else if (block instanceof ProviderRedactedThinkingBlock) {
        return null;
      } else {
        return null;
      }
    }
    if (hasTextBlock) {
      result.put("content", content.toString());
    }
    if (!refusal.isEmpty()) {
      result.put("refusal", refusal.toString());
    }
    if (hasReasoningBlock) {
      result.put("reasoning_content", reasoning.toString());
    }
    if (!toolCalls.isEmpty()) {
      result.set("tool_calls", toolCalls);
    }
    return result;
  }

  private static ObjectNode encodeToolCall(ProviderToolCall call) {
    ObjectNode callNode = NODES.objectNode();
    callNode.put("id", call.id());
    callNode.put("type", "function");
    ObjectNode function = callNode.putObject("function");
    function.put("name", call.name());
    function.put("arguments", call.argumentsJson());
    return callNode;
  }

  private static ProviderBlockData providerBlockData(ProviderContentBlock block) {
    if (block instanceof ProviderTextBlock textBlock) {
      return textBlock.providerBlockData();
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return thinkingBlock.providerBlockData();
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return toolCallBlock.providerBlockData();
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return opaqueBlock.providerBlockData();
    }
    if (block instanceof ProviderRedactedThinkingBlock redactedBlock) {
      return redactedBlock.providerBlockData();
    }
    return null;
  }

  private static boolean mergeFields(ObjectNode target, ObjectNode fields) {
    for (var entry : fields.properties()) {
      if (target.has(entry.getKey())) {
        return false;
      }
      target.set(entry.getKey(), entry.getValue().deepCopy());
    }
    return true;
  }

  private static boolean mergeToolCallFields(ObjectNode call, ObjectNode fields) {
    JsonNode functionFields = fields.get("function");
    ObjectNode function = (ObjectNode) call.get("function");
    if (functionFields != null) {
      if (!functionFields.isObject() || !mergeFields(function, (ObjectNode) functionFields)) {
        return false;
      }
    }
    ObjectNode topLevelFields = fields.deepCopy();
    topLevelFields.remove("function");
    return mergeFields(call, topLevelFields);
  }
}
