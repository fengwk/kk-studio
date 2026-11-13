package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;

import java.util.ArrayList;
import java.util.List;

/** Test fixtures for expressing Anthropic replay facts on their owning blocks. */
final class AnthropicNativeReplayTestSupport {
  private AnthropicNativeReplayTestSupport() {}

  static List<ProviderContentBlock> withNativeData(
      List<ProviderContentBlock> blocks, ProviderReplayAffinity affinity, JsonNode content) {
    if (!content.isArray() || content.size() != blocks.size()) {
      throw new IllegalArgumentException("native content must match block count");
    }
    List<ProviderContentBlock> result = new ArrayList<>(blocks.size());
    for (int i = 0; i < blocks.size(); i++) {
      ProviderContentBlock block = blocks.get(i);
      JsonNode node = content.get(i);
      if (!node.isObject()) {
        throw new IllegalArgumentException("native block must be an object");
      }
      ObjectNode fields = (ObjectNode) node.deepCopy();
      String signature = null;
      if (block instanceof ProviderTextBlock text) {
        fields.remove(List.of("type", "text"));
        result.add(new ProviderTextBlock(text.text(), data(affinity, null, fields)));
      } else if (block instanceof ProviderThinkingBlock thinking) {
        signature = node.path("signature").isString() ? node.path("signature").stringValue() : null;
        fields.remove(List.of("type", "thinking", "signature"));
        result.add(
            new ProviderThinkingBlock(thinking.thinking(), data(affinity, signature, fields)));
      } else if (block instanceof ProviderRedactedThinkingBlock redacted) {
        fields.remove(List.of("type", "data"));
        result.add(
            new ProviderRedactedThinkingBlock(redacted.data(), data(affinity, null, fields)));
      } else if (block instanceof ProviderToolCallBlock call) {
        fields.remove(List.of("type", "id", "name", "input"));
        result.add(new ProviderToolCallBlock(call.toolCall(), data(affinity, null, fields)));
      } else if (block instanceof ProviderOpaqueBlock opaque) {
        fields.remove("type");
        result.add(new ProviderOpaqueBlock(opaque.blockType(), data(affinity, null, fields)));
      } else {
        throw new IllegalArgumentException("unsupported Anthropic replay block: " + block);
      }
    }
    return List.copyOf(result);
  }

  private static ProviderBlockData data(
      ProviderReplayAffinity affinity, String signature, ObjectNode nativeFields) {
    return new ProviderBlockData(
        ProviderReplayFormat.ANTHROPIC_MESSAGES, affinity, signature, null, null, nativeFields);
  }
}
