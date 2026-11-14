package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderJsonHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 按 assistant block 的 native metadata 重建 Responses 的原生 output item。
 *
 * <p>item 的边界与顺序由 {@link ProviderBlockData#itemStart()} 决定：同一 item 的各 content part 按 durable
 * 顺序追加。任何 block 缺少同协议同 affinity 的 metadata 时整体放弃回放，由调用方退回语义编码。
 */
final class OpenAiResponsesReplayEncoder {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiResponsesReplayEncoder() {}

  static List<ObjectNode> build(
      ProviderDescriptor descriptor, String requestedModel, List<ProviderContentBlock> blocks) {
    if (blocks.isEmpty()) {
      return null;
    }
    var affinity = descriptor.affinity(requestedModel);
    ArrayNode output = NODES.arrayNode();
    ObjectNode currentItem = null;
    for (ProviderContentBlock block : blocks) {
      ProviderBlockData data = providerBlockData(block);
      if (data == null
          || data.protocol() != ProviderReplayFormat.OPENAI_RESPONSES
          || !data.affinity().equals(affinity)) {
        return null;
      }
      if (data.itemStart()) {
        if (currentItem != null) {
          output.add(currentItem);
        }
        currentItem = beginItem(data);
      } else if (currentItem == null) {
        return null;
      }
      if (!appendBlock(currentItem, block, data)) {
        return null;
      }
    }
    if (currentItem == null) {
      return null;
    }
    output.add(currentItem);
    if (OpenAiResponsesReplayValidator.isEmptyPlaceholderOnly(output, blocks)) {
      return null;
    }
    List<ObjectNode> items = new ArrayList<>(output.size());
    for (JsonNode item : output) {
      items.add((ObjectNode) item.deepCopy());
    }
    return items;
  }

  private static ObjectNode beginItem(ProviderBlockData data) {
    ObjectNode item = NODES.objectNode();
    JsonNode itemFields = data.nativeFields().get("itemFields");
    if (itemFields != null) {
      mergeNativeFields(item, itemFields);
    }
    JsonNode rawItem = data.nativeFields().get("item");
    if (rawItem != null) {
      mergeNativeFields(item, rawItem);
    }
    if (data.itemId() != null) {
      item.put("id", data.itemId());
    }
    if (data.itemType() == null || data.itemType().isBlank()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid OpenAI Responses block item type");
    }
    item.put("type", data.itemType());
    if ("message".equals(data.itemType())) {
      item.put("role", "assistant");
      if (!item.path("content").isArray()) {
        item.putArray("content");
      }
    } else if ("reasoning".equals(data.itemType())) {
      if (data.encryptedContent() != null) {
        item.put("encrypted_content", data.encryptedContent());
      }
      if (!item.path("summary").isArray()) {
        item.putArray("summary");
      }
    }
    return item;
  }

  private static boolean appendBlock(
      ObjectNode item, ProviderContentBlock block, ProviderBlockData data) {
    if (block instanceof ProviderTextBlock textBlock) {
      return appendTextBlock(item, textBlock, data);
    }
    if (block instanceof ProviderThinkingBlock thinkingBlock) {
      return appendThinkingBlock(item, thinkingBlock, data);
    }
    if (block instanceof ProviderToolCallBlock toolCallBlock) {
      return appendToolCallBlock(item, toolCallBlock, data);
    }
    if (block instanceof ProviderOpaqueBlock opaqueBlock) {
      return appendOpaqueBlock(item, opaqueBlock, data);
    }
    return false;
  }

  private static boolean appendTextBlock(
      ObjectNode item, ProviderTextBlock block, ProviderBlockData data) {
    if (!"message".equals(data.itemType())
        || !Set.of("output_text", "refusal").contains(data.contentType())) {
      return false;
    }
    ObjectNode content = item.withArray("content").addObject();
    mergeNativeFields(content, data.nativeFields().get("contentFields"));
    content.put("type", data.contentType());
    if (data.refusal()) {
      content.put("refusal", block.text());
    } else {
      content.put("text", block.text());
    }
    JsonNode annotations = data.nativeFields().get("annotations");
    if (annotations != null) {
      content.set("annotations", annotations.deepCopy());
    }
    return true;
  }

  private static boolean appendThinkingBlock(
      ObjectNode item, ProviderThinkingBlock block, ProviderBlockData data) {
    if (!"reasoning".equals(data.itemType()) || data.contentType() == null) {
      return false;
    }
    ObjectNode summary = item.withArray("summary").addObject();
    mergeNativeFields(summary, data.nativeFields().get("summaryPartFields"));
    summary.put("type", data.contentType());
    summary.put("text", block.thinking());
    return true;
  }

  private static boolean appendToolCallBlock(
      ObjectNode item, ProviderToolCallBlock block, ProviderBlockData data) {
    if (!"function_call".equals(data.itemType()) || !data.itemStart()) {
      return false;
    }
    ProviderToolCall call = block.toolCall();
    ProviderJsonHelper.parseStrictJsonObject(
        call.argumentsJson(), "function_call arguments must be a JSON object");
    item.put("call_id", call.id());
    item.put("name", call.name());
    item.put("arguments", call.argumentsJson());
    return true;
  }

  private static boolean appendOpaqueBlock(
      ObjectNode item, ProviderOpaqueBlock block, ProviderBlockData data) {
    if ("message_content".equals(block.blockType())) {
      if (!"message".equals(data.itemType()) || data.contentType() == null) {
        return false;
      }
      JsonNode contentFields = data.nativeFields().get("content");
      if (contentFields == null || !contentFields.isObject()) {
        return false;
      }
      ObjectNode content = item.withArray("content").addObject();
      mergeNativeFields(content, contentFields);
      content.put("type", data.contentType());
      return true;
    }
    return data.itemStart() && data.itemType() != null;
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

  private static void mergeNativeFields(ObjectNode target, JsonNode fields) {
    if (fields == null) {
      return;
    }
    if (!fields.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_REQUEST, "invalid OpenAI Responses block native fields");
    }
    for (var field : fields.properties()) {
      target.set(field.getKey(), field.getValue().deepCopy());
    }
  }
}
