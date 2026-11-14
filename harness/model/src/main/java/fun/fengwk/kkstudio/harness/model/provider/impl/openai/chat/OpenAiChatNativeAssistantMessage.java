package fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/**
 * provider 原生 assistant message 的增量合并。
 *
 * <p>只合并 {@code choices[0].delta}，绝不混入 chunk 级 transport metadata（id、object、usage
 * 等）。合并规则只描述「同名字段如何叠加」：字符串增量追加， 带 {@code index} 的数组按 index 合并条目（tool_calls 片段因此按 index 累加），object
 * 递归合并，其余标量覆盖。未规范化的官方字段与未来字段因此都能无损保留到 replay。
 */
final class OpenAiChatNativeAssistantMessage {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** known assistant 字段：replay 时由规范化 durable 事实重新写入，不沿用 native 片段形状。 */
  private static final Set<String> KNOWN_ASSISTANT_FIELDS =
      Set.of("content", "refusal", "reasoning_content", "reasoning_details", "tool_calls");

  private final ObjectNode message = NODES.objectNode();

  /** 空的独立字段对象，供没有原生扩展事实的调用方使用。 */
  static ObjectNode emptyObject() {
    return NODES.objectNode();
  }

  void mergeDelta(JsonNode delta) {
    for (Map.Entry<String, JsonNode> field : delta.properties()) {
      mergeField(message, field.getKey(), field.getValue());
    }
  }

  private static void mergeField(ObjectNode target, String field, JsonNode incoming) {
    JsonNode existing = target.get(field);
    if (incoming.isString() && existing != null && existing.isString()) {
      target.put(field, existing.stringValue() + incoming.stringValue());
      return;
    }
    if (incoming.isObject() && existing != null && existing.isObject()) {
      mergeObject((ObjectNode) existing, (ObjectNode) incoming);
      return;
    }
    if (incoming.isArray()
        && existing != null
        && existing.isArray()
        && isIndexedArray((ArrayNode) incoming)) {
      mergeIndexedArray((ArrayNode) existing, (ArrayNode) incoming);
      return;
    }
    target.set(field, incoming.deepCopy());
  }

  private static void mergeObject(ObjectNode target, ObjectNode incoming) {
    for (Map.Entry<String, JsonNode> field : incoming.properties()) {
      mergeField(target, field.getKey(), field.getValue());
    }
  }

  private static boolean isIndexedArray(ArrayNode array) {
    if (array.isEmpty()) {
      return false;
    }
    for (JsonNode item : array) {
      if (!item.isObject()) {
        return false;
      }
      JsonNode index = item.get("index");
      if (index == null || !index.isIntegralNumber()) {
        return false;
      }
    }
    return true;
  }

  /**
   * 按条目自身的 {@code index} 定位既有槽位并递归合并，槽位不存在时追加，保持 provider 给出的片段顺序。
   *
   * <p>槽位坐标是任意宽度的整数：索引按 {@code BigInteger} 读出并精确比对/写回，超出 int 范围的原生索引不会因截断被并进别的槽位。
   */
  private static void mergeIndexedArray(ArrayNode target, ArrayNode incoming) {
    for (JsonNode item : incoming) {
      ObjectNode slot = indexedSlot(target, item.get("index").bigIntegerValue());
      mergeObject(slot, (ObjectNode) item);
    }
  }

  private static ObjectNode indexedSlot(ArrayNode target, BigInteger index) {
    for (JsonNode item : target) {
      if (!item.isObject()) {
        continue;
      }
      JsonNode itemIndex = item.get("index");
      if (itemIndex != null
          && itemIndex.isIntegralNumber()
          && itemIndex.bigIntegerValue().equals(index)) {
        return (ObjectNode) item;
      }
    }
    ObjectNode slot = target.addObject();
    slot.put("index", index);
    return slot;
  }

  /** 按 wire index 查找原生 tool call 槽位；未出现时返回 {@code null}。 */
  JsonNode toolCall(int wireIndex) {
    JsonNode calls = message.path("tool_calls");
    if (!calls.isArray()) {
      return null;
    }
    for (JsonNode call : calls) {
      if (call.path("index").isInt() && call.path("index").intValue() == wireIndex) {
        return call;
      }
    }
    return null;
  }

  /**
   * 冻结为 replay 基座：去掉 known 字段（它们由规范化 durable 事实重新写入）与 role/annotations，audio 只保留请求侧的 id
   * 引用。返回空对象表示没有需要保留的原生事实。
   */
  ObjectNode replayFields(boolean durableToolCallsPresent) {
    ObjectNode fields = message.deepCopy();
    for (String field : KNOWN_ASSISTANT_FIELDS) {
      if (!"tool_calls".equals(field) || durableToolCallsPresent) {
        fields.remove(field);
      }
    }
    JsonNode nativeToolCalls = fields.get("tool_calls");
    if (nativeToolCalls != null && nativeToolCalls.isArray()) {
      for (JsonNode call : nativeToolCalls) {
        if (call instanceof ObjectNode callObject) {
          callObject.remove("index");
        }
      }
    }
    fields.remove("role");
    fields.remove("annotations");
    JsonNode audio = fields.get("audio");
    if (audio != null) {
      JsonNode audioId = audio.path("id");
      fields.remove("audio");
      if (audioId.isString() && !audioId.stringValue().isBlank()) {
        ObjectNode replayAudio = NODES.objectNode();
        replayAudio.set("id", audioId.deepCopy());
        fields.set("audio", replayAudio);
      }
    }
    return fields;
  }
}
