package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;

import java.util.List;

/**
 * 反序列化会话消息为 Gemini {@code contents}：相邻同 role 的消息合并为一个 content，assistant 优先原生回放、否则语义编码。
 *
 * <p>合并只在 role 真正切换时新建 content，因此连续 user/tool 消息共享同一个 parts 数组。
 */
final class GeminiWireContents {

  private GeminiWireContents() {}

  static void encode(
      ArrayNode contentsArray,
      List<ProviderMessage> messages,
      ProviderDescriptor descriptor,
      String requestedModel) {
    String currentRole = null;
    ArrayNode currentParts = null;

    for (int i = 0; i < messages.size(); i++) {
      ProviderMessage msg = messages.get(i);

      if (msg.role() == ProviderMessageRole.ASSISTANT) {
        String wireRole = "model";

        if (currentParts == null || !wireRole.equals(currentRole)) {
          ObjectNode contentNode = contentsArray.addObject();
          contentNode.put("role", wireRole);
          currentRole = wireRole;
          currentParts = contentNode.putArray("parts");
        }

        List<ObjectNode> blockReplayParts =
            GeminiAssistantReplay.buildParts(descriptor, requestedModel, msg.contents());
        if (blockReplayParts != null) {
          for (ObjectNode partNode : blockReplayParts) {
            currentParts.add(partNode);
          }
        } else {
          for (ProviderContentBlock block : msg.contents()) {
            GeminiAssistantReplay.encodeBlock(currentParts, block);
          }
        }
      } else { // USER or TOOL
        String wireRole = "user";

        if (currentParts == null || !wireRole.equals(currentRole)) {
          ObjectNode contentNode = contentsArray.addObject();
          contentNode.put("role", wireRole);
          currentRole = wireRole;
          currentParts = contentNode.putArray("parts");
        }

        if (msg.role() == ProviderMessageRole.USER) {
          for (ProviderContentBlock block : msg.contents()) {
            GeminiContentEncoder.encodeUserBlock(currentParts, block);
          }
        } else { // TOOL
          for (ProviderContentBlock block : msg.contents()) {
            if (block instanceof ProviderToolResultBlock resultBlock) {
              GeminiContentEncoder.encodeToolResultBlock(currentParts, resultBlock);
            }
          }
        }
      }
    }
  }
}
