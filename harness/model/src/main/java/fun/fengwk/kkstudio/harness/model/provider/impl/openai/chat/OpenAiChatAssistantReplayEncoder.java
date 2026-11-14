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
    Draft draft = new Draft();
    for (ProviderContentBlock block : message.contents()) {
      if (!draft.append(block, descriptor, requestedModel)) {
        return null;
      }
    }
    return draft.toWireMessage();
  }

  /** 回放草稿：按 durable 顺序累积可读内容与工具调用，同时合并各 block 的原生字段。 */
  private static final class Draft {
    private final ObjectNode result = NODES.objectNode();
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder refusal = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final ArrayNode toolCalls = NODES.arrayNode();
    private boolean hasTextBlock = false;
    private boolean hasReasoningBlock = false;

    Draft() {
      result.put("role", "assistant");
    }

    /** 追加一个 block；缺少同协议同 affinity 的 metadata 或原生字段冲突时返回 {@code false}。 */
    boolean append(
        ProviderContentBlock block, ProviderDescriptor descriptor, String requestedModel) {
      ProviderBlockData data = providerBlockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.OPENAI_CHAT
          || !data.affinity().equals(descriptor.affinity(requestedModel))) {
        return false;
      }
      ObjectNode nativeFields = (ObjectNode) data.nativeFields();
      if (block instanceof ProviderTextBlock textBlock) {
        appendText(textBlock, data);
        return true;
      }
      if (block instanceof ProviderThinkingBlock thinkingBlock) {
        hasReasoningBlock = true;
        reasoning.append(thinkingBlock.thinking());
        return mergeFields(result, nativeFields);
      }
      if (block instanceof ProviderToolCallBlock toolCallBlock) {
        return appendToolCall(toolCallBlock, nativeFields);
      }
      if (block instanceof ProviderOpaqueBlock opaqueBlock
          && "openai_chat_message".equals(opaqueBlock.blockType())) {
        return mergeFields(result, nativeFields);
      }
      return false;
    }

    private void appendText(ProviderTextBlock block, ProviderBlockData data) {
      if (data.refusal()) {
        refusal.append(block.text());
        return;
      }
      hasTextBlock = true;
      content.append(block.text());
    }

    private boolean appendToolCall(ProviderToolCallBlock block, ObjectNode nativeFields) {
      ProviderToolCall call = block.toolCall();
      ProviderJsonHelper.parseStrictJsonObject(
          call.argumentsJson(), "toolCall argumentsJson must be a JSON object");
      ObjectNode callNode = encodeToolCall(call);
      if (!mergeToolCallFields(callNode, nativeFields)) {
        return false;
      }
      toolCalls.add(callNode);
      return true;
    }

    ObjectNode toWireMessage() {
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
