package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 把会话消息从左到右编码为 Anthropic wire messages，并保留当前对话尾部缓存端点与逐条消息的推理级别。
 *
 * <p>compaction 回放代表已经摘要完成的历史：durable 校验完成后清空已编码消息与 effort，使该 assistant message 成为首条消息。
 */
final class AnthropicWireMessages {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicWireMessages() {}

  static Result encode(
      List<ProviderMessage> messages,
      ProviderDescriptor descriptor,
      String requestedModel,
      int prefixMessageIndex) {
    ArrayNode wireMessagesArray = NODES.arrayNode();
    List<String> messageEfforts = new ArrayList<>();
    ObjectNode lastEligible = null;
    ObjectNode prefixEndpoint = null;
    for (int index = 0; index < messages.size(); index++) {
      ProviderMessage msg = messages.get(index);
      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        AnthropicEncodedAssistant assistant =
            AnthropicAssistantReplay.encodeMessage(msg, descriptor, requestedModel);
        // compaction 回放代表历史已被摘要：清空被摘要消息，使 compaction assistant message 成为首条消息。
        if (assistant.resetsHistory()) {
          wireMessagesArray.removeAll();
          messageEfforts.clear();
          lastEligible = null;
          prefixEndpoint = null;
        }
        wireMessagesArray.add(assistant.node());
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeToolMessage(msg));
      } else if (msg.role() == ProviderMessageRole.SYSTEM) {
        wireMessagesArray.add(AnthropicToolsEncoder.encodeSystemMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
      messageEfforts.add(msg.reasoningEffort());
      ObjectNode messageEndpoint =
          AnthropicCacheMarkers.latestEligibleBlock(
              wireMessagesArray.get(wireMessagesArray.size() - 1).path("content"));
      if (index == prefixMessageIndex) {
        prefixEndpoint = messageEndpoint;
      }
      if (messageEndpoint != null) {
        lastEligible = messageEndpoint;
      }
    }
    return new Result(wireMessagesArray, messageEfforts, lastEligible, prefixEndpoint);
  }

  /** 缓存端点引用最终保留的内容块，不依赖 wire 消息下标。 */
  record Result(
      ArrayNode messages,
      List<String> efforts,
      ObjectNode cacheEndpoint,
      ObjectNode prefixEndpoint) {}
}
