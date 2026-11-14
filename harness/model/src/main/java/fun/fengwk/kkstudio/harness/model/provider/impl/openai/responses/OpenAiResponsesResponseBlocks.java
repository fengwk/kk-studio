package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderBlockData;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把冻结的 Responses output item 还原为 durable content block。
 *
 * <p>message 的每个 content part、reasoning 的每个 summary part 各自成为一个 block，并携带重建原生 item 所需的 metadata；
 * function_call 复用终态解析出的 durable 调用，其余 item 以不透明 block 原样保留。
 */
final class OpenAiResponsesResponseBlocks {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ProviderDescriptor descriptor;
  private final String modelId;

  OpenAiResponsesResponseBlocks(ProviderDescriptor descriptor, String modelId) {
    this.descriptor = descriptor;
    this.modelId = modelId;
  }

  List<ProviderContentBlock> build(ArrayNode outputItems, List<ProviderToolCall> toolCalls) {
    Map<String, ProviderToolCall> callsById = new LinkedHashMap<>();
    for (ProviderToolCall call : toolCalls) {
      callsById.put(call.id(), call);
    }
    List<ProviderContentBlock> blocks = new ArrayList<>();
    for (JsonNode item : outputItems) {
      String itemType = item.path("type").asString("");
      if ("message".equals(itemType)) {
        addMessageBlocks(blocks, item);
      } else if ("reasoning".equals(itemType)) {
        addReasoningBlocks(blocks, item);
      } else if ("function_call".equals(itemType)) {
        ProviderToolCall call = callsById.get(item.path("call_id").asString(""));
        if (call == null) {
          ObjectNode fields = NODES.objectNode();
          fields.set("item", item.deepCopy());
          blocks.add(new ProviderOpaqueBlock(itemType, responseBlockData(fields)));
        } else {
          ObjectNode fields = NODES.objectNode();
          fields.put("itemType", itemType);
          fields.set(
              "itemFields", copyFields(item, Set.of("type", "call_id", "name", "arguments")));
          blocks.add(new ProviderToolCallBlock(call, responseBlockData(fields)));
        }
      } else {
        ObjectNode fields = NODES.objectNode();
        fields.set("item", item.deepCopy());
        blocks.add(new ProviderOpaqueBlock(itemType, responseBlockData(fields)));
      }
    }
    return blocks;
  }

  private void addMessageBlocks(List<ProviderContentBlock> blocks, JsonNode item) {
    JsonNode content = item.path("content");
    if (!content.isArray() || content.isEmpty()) {
      ObjectNode fields = NODES.objectNode();
      fields.set("item", item.deepCopy());
      blocks.add(new ProviderOpaqueBlock("message", responseBlockData(fields)));
      return;
    }
    ObjectNode itemFields = copyFields(item, Set.of("content"));
    boolean first = true;
    for (JsonNode contentPart : content) {
      String contentType = contentPart.path("type").asString("");
      ObjectNode fields = NODES.objectNode();
      fields.put("itemType", "message");
      fields.put("contentType", contentType);
      if (first) {
        fields.set("itemFields", itemFields);
        first = false;
      }
      Set<String> semanticContentFields =
          switch (contentType) {
            case "output_text" -> Set.of("type", "text");
            case "refusal" -> Set.of("type", "refusal");
            default -> Set.of("type");
          };
      ObjectNode contentFields = copyFields(contentPart, semanticContentFields);
      if (!contentFields.isEmpty()) {
        fields.set("contentFields", contentFields);
      }
      String text =
          "refusal".equals(contentType)
              ? contentPart.path("refusal").asString("")
              : contentPart.path("text").asString("");
      if ("output_text".equals(contentType)) {
        blocks.add(new ProviderTextBlock(text, responseBlockData(fields)));
      } else if ("refusal".equals(contentType)) {
        fields.put("refusal", true);
        blocks.add(new ProviderTextBlock(text, responseBlockData(fields)));
      } else {
        blocks.add(new ProviderOpaqueBlock("message_content", responseBlockData(fields)));
      }
    }
  }

  private void addReasoningBlocks(List<ProviderContentBlock> blocks, JsonNode item) {
    JsonNode summary = item.path("summary");
    if (summary.isArray() && !summary.isEmpty() && hasOnlyTextSummaryParts(summary)) {
      ObjectNode itemFields = copyFields(item, Set.of("summary"));
      boolean first = true;
      for (JsonNode summaryPart : summary) {
        ObjectNode fields = NODES.objectNode();
        fields.put("itemType", "reasoning");
        fields.put("contentType", "summary_text");
        fields.set("summaryPartFields", copyFields(summaryPart, Set.of("type", "text")));
        if (first) {
          fields.set("itemFields", itemFields);
          first = false;
        }
        blocks.add(
            new ProviderThinkingBlock(
                summaryPart.path("text").stringValue(), responseBlockData(fields)));
      }
      return;
    }
    ObjectNode fields = NODES.objectNode();
    fields.set("item", item.deepCopy());
    blocks.add(new ProviderOpaqueBlock("reasoning", responseBlockData(fields)));
  }

  private static boolean hasOnlyTextSummaryParts(JsonNode summary) {
    for (JsonNode summaryPart : summary) {
      if (!summaryPart.isObject()
          || !"summary_text".equals(summaryPart.path("type").asString(""))
          || !summaryPart.path("text").isString()) {
        return false;
      }
    }
    return true;
  }

  private ProviderBlockData responseBlockData(ObjectNode nativeFields) {
    String itemId = null;
    String encryptedContent = null;
    String itemType = textualValue(nativeFields.remove("itemType"));
    String contentType = textualValue(nativeFields.remove("contentType"));
    JsonNode refusalNode = nativeFields.remove("refusal");
    boolean refusal = refusalNode != null && refusalNode.isBoolean() && refusalNode.booleanValue();
    JsonNode itemFields = nativeFields.get("itemFields");
    if (itemFields instanceof ObjectNode objectFields) {
      itemId = textualValue(objectFields.remove("id"));
      if (itemType == null) {
        itemType = textualValue(objectFields.remove("type"));
      } else {
        objectFields.remove("type");
      }
      encryptedContent = textualValue(objectFields.remove("encrypted_content"));
    }
    JsonNode item = nativeFields.get("item");
    if (item instanceof ObjectNode objectItem) {
      itemId = itemId == null ? textualValue(objectItem.remove("id")) : itemId;
      if (itemType == null) {
        itemType = textualValue(objectItem.remove("type"));
      } else {
        objectItem.remove("type");
      }
      if ("reasoning".equals(itemType)) {
        encryptedContent = textualValue(objectItem.remove("encrypted_content"));
      }
    }
    return new ProviderBlockData(
        ProviderReplayFormat.OPENAI_RESPONSES,
        descriptor.affinity(modelId),
        null,
        encryptedContent,
        itemId,
        itemType,
        contentType,
        refusal,
        null,
        nativeFields.has("itemFields") || nativeFields.has("item"),
        nativeFields);
  }

  private static String textualValue(JsonNode node) {
    return node != null && node.isString() ? node.stringValue() : null;
  }

  private ObjectNode copyFields(JsonNode source, Set<String> excludedFields) {
    ObjectNode copy = NODES.objectNode();
    source
        .properties()
        .forEach(
            entry -> {
              if (!excludedFields.contains(entry.getKey())) {
                copy.set(entry.getKey(), entry.getValue().deepCopy());
              }
            });
    return copy;
  }
}
