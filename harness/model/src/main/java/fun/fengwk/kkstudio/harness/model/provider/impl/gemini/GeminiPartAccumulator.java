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

/**
 * Gemini candidate parts 的原生边界状态机。
 *
 * <p>每个 SSE part 按官方流式增量语义追加，不通过文本相等或前缀关系猜测快照。相邻的朴素 text/thought 单 part 事件累积到同一块；签名、工具和 opaque part
 * 保持原生边界。
 */
final class GeminiPartAccumulator {

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
      processPart(partsArray.get(i), partsArray.size() == 1);
    }
  }

  /**
   * 累积单个原生 part。
   *
   * <p>每个 SSE part 都按增量事件处理，不根据相等、前缀或字段形状猜测累计快照。只有相邻的朴素 text/thought 单 part 事件会累积到同一文本块；带签名、工具和
   * opaque part 保持完整原生边界。
   */
  private void processPart(JsonNode partNode, boolean singlePartFrame) {
    boolean functionCallPart = partNode.has("functionCall");
    String signature = readThoughtSignature(partNode);
    String text = readPartText(partNode);
    boolean thought = isThoughtPart(partNode);
    ObjectNode normalizedPart = normalizedRawPart(partNode);

    // Each SSE part is an event: never infer cumulative snapshots from equal or prefix-related
    // contents.
    if (appendPlainDelta(partNode, text, thought, functionCallPart, signature, singlePartFrame)) {
      return;
    }

    // Keep each native boundary intact, including signatures, tools, and opaque parts.
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

  /** 双方都只含 text/thought 的朴素增量累积；返回是否已消费该 part。 */
  private boolean appendPlainDelta(
      JsonNode partNode,
      String text,
      boolean thought,
      boolean functionCallPart,
      String signature,
      boolean singlePartFrame) {
    if (text == null
        || signature != null
        || functionCallPart
        || !isPlainPartShape(partNode)
        || !singlePartFrame) {
      return false;
    }
    TrackedPart lastPart = lastTrackedPart();
    if (lastPart == null
        || !lastPart.plainShape
        || !lastPart.hasText
        || lastPart.thought != thought) {
      return false;
    }
    lastPart.text.append(text);
    emitTextDelta(text, thought);
    return true;
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

  /** 只有不含未知字段或其它 union 成员的 text/thought part 才能安全累积到纯文本块。 */
  static boolean isPlainPartShape(JsonNode partNode) {
    for (String fieldName : partNode.propertyNames()) {
      if (!fieldName.equals("text") && !fieldName.equals("thought")) {
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
