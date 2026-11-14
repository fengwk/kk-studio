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
 * 把会话消息从左到右编码为 Anthropic wire messages，并记录缓存端点候选与每条消息的推理级别。
 *
 * <p>assistant 之前的完整前缀就是上次模型请求的端点，不受本轮新增块数量影响。compaction 回放代表已经摘要完成的历史：durable 校验完成后清空已编码消息、effort
 * 与缓存端点，使该 assistant message 成为首条消息。
 */
final class AnthropicWireMessages {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private AnthropicWireMessages() {}

  static Result encode(
      List<ProviderMessage> messages, ProviderDescriptor descriptor, String requestedModel) {
    ArrayNode wireMessagesArray = NODES.arrayNode();
    List<String> messageEfforts = new ArrayList<>();
    List<ObjectNode> cacheEndpoints = new ArrayList<>();
    ObjectNode lastEligible = null;
    for (ProviderMessage msg : messages) {
      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        // assistant 之前的完整前缀就是上次模型请求的端点，不受本轮新增块数量影响。
        AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);
        AnthropicEncodedAssistant assistant =
            AnthropicAssistantReplay.encodeMessage(msg, descriptor, requestedModel);
        // compaction 回放代表已经摘要完成的历史：durable 校验完成后清空已编码消息，
        // 使 compaction assistant message 成为首条消息、被摘要历史整体省略。
        if (assistant.resetsHistory()) {
          wireMessagesArray.removeAll();
          messageEfforts.clear();
          cacheEndpoints.clear();
          lastEligible = null;
        }
        wireMessagesArray.add(assistant.node());
      } else if (msg.role() == ProviderMessageRole.USER) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeUserMessage(msg));
      } else if (msg.role() == ProviderMessageRole.TOOL) {
        wireMessagesArray.add(AnthropicContentEncoder.encodeToolMessage(msg));
      } else if (msg.role() == ProviderMessageRole.SYSTEM) {
        AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);
        wireMessagesArray.add(AnthropicToolsEncoder.encodeSystemMessage(msg));
      } else {
        throw new ProviderException(
            ProviderErrorKind.INVALID_REQUEST, "unexpected message role: " + msg.role());
      }
      messageEfforts.add(msg.reasoningEffort());
      ObjectNode messageEndpoint =
          AnthropicCacheMarkers.latestEligibleBlock(
              wireMessagesArray.get(wireMessagesArray.size() - 1).path("content"));
      if (messageEndpoint != null) {
        lastEligible = messageEndpoint;
      }
    }
    AnthropicCacheMarkers.addEndpoint(cacheEndpoints, lastEligible);
    return new Result(wireMessagesArray, messageEfforts, cacheEndpoints);
  }

  /** 编码结果：wire messages、逐条消息的推理级别，以及按出现顺序记录的缓存端点候选。 */
  record Result(ArrayNode messages, List<String> efforts, List<ObjectNode> cacheEndpoints) {}
}
