package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderStreamBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Gemini candidate parts 的原生边界状态机。
 *
 * <p>官方协议没有定义跨 chunk 的通用 Part 合并算法，因此只执行两类可证明安全的文本合并，其余 part 一律作为完整原生边界原样保留：
 *
 * <ul>
 *   <li>同一 slot 的累计快照替换：上游再次发送同一 slot part 的完整累积形态，且形状为超集、双方都无签名、文本严格扩展；
 *   <li>朴素 text/thought part 的增量累积：双方都只含 text/thought，累积不会丢失任何字段语义。
 * </ul>
 *
 * <p>带 thoughtSignature 的 part（包括空文本签名 part）绝不参与字段级合并，签名绝不跨 part 迁移。未知字段既不递归合并也不拼接，未知 part 绝不静默丢弃。
 */
final class GeminiPartAccumulator {

  /** 只含 text/thought 的朴素 part：仅这种形状允许增量文本累积。 */
  private static final Set<String> PLAIN_PART_FIELDS = Set.of("text", "thought");

  private final ProviderStreamBridge bridge;

  private final List<TrackedPart> trackedParts = new ArrayList<>();
  private final List<ProviderToolCall> collectedToolCalls = new ArrayList<>();
  private int nextToolOrdinal = 0;

  GeminiPartAccumulator(ProviderStreamBridge bridge) {
    this.bridge = bridge;
  }

  List<TrackedPart> trackedParts() {
    return trackedParts;
  }

  List<ProviderToolCall> collectedToolCalls() {
    return collectedToolCalls;
  }

  void process(ArrayNode partsArray) {
    if (partsArray.size() == 0) {
      return;
    }

    validateParts(partsArray);

    for (int i = 0; i < partsArray.size(); i++) {
      processPart(i, partsArray.get(i), partsArray.size() == 1);
    }
  }

  /**
   * 累积单个原生 part。
   *
   * <p>官方协议没有定义跨 chunk 的通用 Part 合并算法，因此这里只承认两类可证明安全的文本合并：同一 slot 上「形状为超集 + 文本严格扩展 +
   * 双方均无签名」的累计快照替换，以及双方都仅含 text/thought 的朴素增量累积。其余 part（functionCall、inlineData / toolResponse 等
   * union 成员，以及任何带未知字段的 part）一律作为完整原生边界原样保留，绝不递归、拼接或猜测未知字段，也绝不跨 part 迁移或改写 thoughtSignature。
   */
  private void processPart(int index, JsonNode partNode, boolean singlePartFrame) {
    boolean functionCallPart = partNode.has("functionCall");
    String signature = readThoughtSignature(partNode);
    String text = readPartText(partNode);
    boolean thought = isThoughtPart(partNode);
    ObjectNode normalizedPart = normalizedRawPart(partNode);
    TrackedPart slot = index < trackedParts.size() ? trackedParts.get(index) : null;

    // 1) 与同一 slot 已记录的原生 part 完全相等：同一快照重复发送的可证明证据，无需重复保留也不重复计入文本。
    if (slot != null && slot.rawPart.equals(normalizedPart)) {
      return;
    }

    // 2) 同一 slot 的累计快照替换：上游把同一 part 的完整累积形态再发一次，整体替换为上游事实。
    if (text != null
        && !functionCallPart
        && signature == null
        && slot != null
        && slot.canAdoptCumulativeText(normalizedPart, text, thought)) {
      String delta = text.substring(slot.text.length());
      slot.replaceWith(normalizedPart, text);
      emitTextDelta(delta, thought);
      return;
    }

    // 3) 朴素 text/thought part 的增量累积：双方都只含 text/thought，累积不会丢失任何字段语义。
    if (text != null
        && signature == null
        && !functionCallPart
        && isPlainPartShape(partNode)
        && singlePartFrame) {
      TrackedPart lastPart = lastTrackedPart();
      if (lastPart != null
          && lastPart.plainShape
          && lastPart.hasText
          && lastPart.thought == thought) {
        String accumulated = lastPart.text.toString();
        String delta =
            accumulated.isEmpty() || !text.startsWith(accumulated)
                ? text
                : text.substring(accumulated.length());
        if (!delta.isEmpty()) {
          lastPart.text.append(delta);
          emitTextDelta(delta, thought);
        }
        return;
      }
    }

    // 4) 完整原生 part 边界：原样保留，未知字段与未知 union 成员绝不合并、绝不丢弃。
    TrackedPart trackedPart = new TrackedPart(normalizedPart, signature, thought);
    trackedParts.add(trackedPart);
    if (text != null) {
      trackedPart.text.append(text);
      emitTextDelta(text, thought);
    }
    if (functionCallPart) {
      captureFunctionCall(trackedPart, partNode.get("functionCall"));
      emitFunctionCall(trackedPart);
    }
  }

  /** 捕捉 functionCall 的规范化状态：id 缺失时补协议 id；name/args 已在 {@link #validateParts} 严格校验。 */
  private void captureFunctionCall(TrackedPart trackedPart, JsonNode fnNode) {
    trackedPart.functionCallName = fnNode.get("name").asString("");
    trackedPart.functionCallId =
        (fnNode.has("id") && !fnNode.get("id").isNull())
            ? fnNode.get("id").asString("")
            : "call_" + nextToolOrdinal;
    trackedPart.functionCallArgs = fnNode.get("args");
  }

  /** 按 part 的 thought 语义发射规范化文本增量；空增量不发射。 */
  private void emitTextDelta(String delta, boolean thought) {
    if (delta.isEmpty()) {
      return;
    }
    bridge.emitEvent(
        thought
            ? new ProviderStreamEvent.ThinkingDelta(delta)
            : new ProviderStreamEvent.TextDelta(delta));
  }

  private TrackedPart lastTrackedPart() {
    return trackedParts.isEmpty() ? null : trackedParts.get(trackedParts.size() - 1);
  }

  /** 判断 part 的字段集合是否只含 text/thought，即可安全增量累积的朴素形状。 */
  static boolean isPlainPartShape(JsonNode partNode) {
    for (String fieldName : partNode.propertyNames()) {
      if (!PLAIN_PART_FIELDS.contains(fieldName)) {
        return false;
      }
    }
    return true;
  }

  /** 判断 candidate 是否声明了 base 的全部字段名：只有超集形状的替换才可能不丢字段。 */
  static boolean carriesAllFieldNames(JsonNode candidate, JsonNode base) {
    for (String fieldName : base.propertyNames()) {
      if (!candidate.has(fieldName)) {
        return false;
      }
    }
    return true;
  }

  /** 读取 part 的 thoughtSignature：null 与空白字符串都表示该 part 不携带签名。 */
  private static String readThoughtSignature(JsonNode partNode) {
    JsonNode signature = partNode.get("thoughtSignature");
    if (signature == null || signature.isNull() || signature.asString("").isBlank()) {
      return null;
    }
    return signature.asString("");
  }

  /** 读取 part 的 text：null 表示未声明。 */
  private static String readPartText(JsonNode partNode) {
    return hasPartText(partNode) ? partNode.get("text").asString("") : null;
  }

  static boolean hasPartText(JsonNode partNode) {
    return partNode.has("text") && !partNode.get("text").isNull();
  }

  private static boolean isThoughtPart(JsonNode partNode) {
    return partNode.path("thought").asBoolean(false);
  }

  /** 保真化原生 part：null text 与空白 thoughtSignature 不承载语义且无法写入合法 replay，按不存在处理。 */
  private static ObjectNode normalizedRawPart(JsonNode partNode) {
    ObjectNode rawPart = (ObjectNode) partNode.deepCopy();
    if (rawPart.has("text") && rawPart.get("text").isNull()) {
      rawPart.remove("text");
    }
    JsonNode signature = rawPart.get("thoughtSignature");
    if (signature != null && (signature.isNull() || signature.asString("").isBlank())) {
      rawPart.remove("thoughtSignature");
    }
    return rawPart;
  }

  /** 严格校验 part 已知字段的类型：已知字段无法保真时明确 INVALID_RESPONSE；未知字段与未知 union 成员不在此列，一律 opaque 透传。 */
  private void validateParts(ArrayNode partsArray) {
    for (JsonNode partNode : partsArray) {
      if (partNode == null || !partNode.isObject()) {
        throw new ProviderException(
            ProviderErrorKind.INVALID_RESPONSE, "candidate part must be a JSON object");
      }
      validatePartKnownFields(partNode);
      if (partNode.has("functionCall")) {
        JsonNode fnNode = partNode.get("functionCall");
        if (fnNode == null || fnNode.isNull() || !fnNode.isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "functionCall must be a JSON object");
        }
        if (!fnNode.has("name")
            || fnNode.get("name") == null
            || !fnNode.get("name").isString()
            || fnNode.get("name").asString("").isBlank()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE, "functionCall name must be a non-blank string");
        }
        if (!fnNode.has("args")
            || fnNode.get("args") == null
            || fnNode.get("args").isNull()
            || !fnNode.get("args").isObject()) {
          throw new ProviderException(
              ProviderErrorKind.INVALID_RESPONSE,
              "functionCall args must be an explicit JSON object");
        }
        if (fnNode.has("id") && fnNode.get("id") != null && !fnNode.get("id").isNull()) {
          if (!fnNode.get("id").isString() || fnNode.get("id").asString("").isBlank()) {
            throw new ProviderException(
                ProviderErrorKind.INVALID_RESPONSE, "functionCall id must be a non-blank string");
          }
        }
      }
    }
  }

  /** text/thought/thoughtSignature 是文本累积与签名回放直接依赖的已知字段，类型不合法时绝不猜测或改写。 */
  private static void validatePartKnownFields(JsonNode partNode) {
    if (partNode.has("text")
        && !partNode.get("text").isNull()
        && !partNode.get("text").isString()) {
      throw new ProviderException(ProviderErrorKind.INVALID_RESPONSE, "part text must be a string");
    }
    if (partNode.has("thought")
        && !partNode.get("thought").isNull()
        && !partNode.get("thought").isBoolean()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "part thought must be a boolean");
    }
    if (partNode.has("thoughtSignature")
        && !partNode.get("thoughtSignature").isNull()
        && !partNode.get("thoughtSignature").isString()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "part thoughtSignature must be a string");
    }
  }

  private void emitFunctionCall(TrackedPart tp) {
    if (tp.functionCallEmitted) {
      return;
    }
    if (tp.functionCallId == null
        || tp.functionCallId.isBlank()
        || tp.functionCallName == null
        || tp.functionCallName.isBlank()
        || tp.functionCallArgs == null
        || !tp.functionCallArgs.isObject()) {
      throw new ProviderException(
          ProviderErrorKind.INVALID_RESPONSE, "invalid function call in stream emission");
    }
    tp.functionCallEmitted = true;
    String argsJson = tp.functionCallArgs.toString();
    ProviderToolCall toolCall =
        new ProviderToolCall(tp.functionCallId, tp.functionCallName, argsJson);
    collectedToolCalls.add(toolCall);

    bridge.emitEvent(
        new ProviderStreamEvent.ToolCallDelta(
            nextToolOrdinal, tp.functionCallId, tp.functionCallName, argsJson));
    nextToolOrdinal++;
  }
}
