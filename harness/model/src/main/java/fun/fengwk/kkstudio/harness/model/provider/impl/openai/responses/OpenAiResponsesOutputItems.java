package fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按到达顺序保存 Responses 的权威 output item，并为未知官方 item 维护 added/done 共享的槽位。 */
final class OpenAiResponsesOutputItems {

  private final List<JsonNode> items = new ArrayList<>();
  private final Map<String, Integer> slots = new LinkedHashMap<>();

  /** added 先占位、done 覆盖同一槽位；槽位键优先 output_index，其次 item id，两者都缺时追加。 */
  static void retainReasoningEncryptedContent(
      ObjectNode terminalItem, List<JsonNode> streamedItems) {
    if (terminalItem.has("encrypted_content")) {
      return;
    }

    JsonNode terminalId = terminalItem.get("id");
    if (terminalId == null || !terminalId.isString() || terminalId.stringValue().isBlank()) {
      return;
    }

    for (JsonNode streamedItem : streamedItems) {
      if (!streamedItem.isObject()
          || !"reasoning".equals(streamedItem.path("type").asString(""))
          || !terminalId.stringValue().equals(streamedItem.path("id").asString(null))) {
        continue;
      }
      JsonNode encryptedContent = streamedItem.get("encrypted_content");
      if (encryptedContent != null
          && encryptedContent.isString()
          && !encryptedContent.stringValue().isBlank()) {
        terminalItem.set("encrypted_content", encryptedContent.deepCopy());
      }
      return;
    }
  }

  /**
   * 记录流式到达的未知官方 item。
   *
   * <p>{@code output_item.added} 先建立槽位：未知 item 没有 delta 等其它语义来源，丢掉就再也回不来；随后到达的 {@code
   * output_item.done} 以更完整的 deepCopy 覆盖同一槽位，成为该 item 的 replay 事实。槽位键优先取 added/done 共享的 {@code
   * output_index}，其次取 item id；两者都缺失的 done 帧仍然追加，绝不静默丢弃。
   */
  void retainOpaque(JsonNode node) {
    JsonNode item = node.get("item");
    if (item == null
        || !item.isObject()
        || !OpenAiResponsesReplayBuilder.isOpaqueReplayItem(item)) {
      return;
    }
    String key = opaqueItemSlotKey(node, item);
    Integer slot = key == null ? null : slots.get(key);
    if (slot != null) {
      items.set(slot, item.deepCopy());
      return;
    }
    if (key != null) {
      slots.put(key, items.size());
    }
    items.add(item.deepCopy());
  }

  private static String opaqueItemSlotKey(JsonNode node, JsonNode item) {
    JsonNode outputIndex = node.get("output_index");
    if (outputIndex != null && outputIndex.isIntegralNumber()) {
      // 槽位键取索引的完整十进制文字：任意宽度的整数坐标都不会因 int 截断而与其他 item 共用同一槽位。
      return "index:" + outputIndex.bigIntegerValue();
    }
    JsonNode id = item.get("id");
    if (id != null && id.isString() && !id.stringValue().isBlank()) {
      return "id:" + id.stringValue();
    }
    return null;
  }

  void add(JsonNode item) {
    items.add(item.deepCopy());
  }

  List<JsonNode> items() {
    return items;
  }

  /** 显式终态 output 覆盖流内草稿时，返回草稿快照并清空槽位。 */
  List<JsonNode> clearAndSnapshot() {
    List<JsonNode> snapshot = new ArrayList<>(items);
    items.clear();
    slots.clear();
    return snapshot;
  }
}
