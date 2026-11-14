package fun.fengwk.kkstudio.harness.model.provider.impl.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 单个 Gemini 原生 part 的累积状态：{@code rawPart} 保真，已知字段由规范化累积结果统一写回。
 *
 * <p>{@code rawPart} 是剔除 null text 与空白签名后的原生副本，由累积器持有：未知字段与未知 union 成员原样保留，供下一轮 opaque replay。
 */
final class TrackedPart {

  /** 原生 part 的保真化副本（已剔除 null text 与空白签名），由累积器持有：未知字段与未知 union 成员原样保留，供下一轮 opaque replay。 */
  ObjectNode rawPart;

  /** part 级 thought=true。 */
  final boolean thought;

  /** 非空白 thoughtSignature；null 表示该 part 不携带签名。 */
  final String signature;

  /** 原生 part 是否声明了 text 字段。 */
  final boolean hasText;

  /** 字段集合是否只含 text/thought；被上游超集形状替换后重新计算。 */
  boolean plainShape;

  /** 规范化累积文本。 */
  final StringBuilder text = new StringBuilder();

  String functionCallName = null;
  String functionCallId = null;
  JsonNode functionCallArgs = null;
  boolean functionCallEmitted = false;

  TrackedPart(ObjectNode rawPart, String signature, boolean thought) {
    this.rawPart = rawPart;
    this.thought = thought;
    this.signature = signature;
    this.hasText = GeminiPartAccumulator.hasPartText(rawPart);
    this.plainShape = GeminiPartAccumulator.isPlainPartShape(rawPart);
  }

  boolean isFunctionCall() {
    return rawPart.has("functionCall");
  }

  /**
   * 判断上游再次给出的同一 slot part 是否可证明为该 part 的完整累积形态。
   *
   * <p>成立条件：本地 part 无签名且非 functionCall 边界、双方 thought 语义一致、双方都声明 text、上游字段名是本地字段名的超集（替换不会静默丢字段），
   * 且上游文本严格扩展本地累积文本。带签名的 part 绝不参与该替换，因此签名永远不会被跨 part 迁移或改写。
   */
  boolean canAdoptCumulativeText(
      ObjectNode incomingPart, String incomingText, boolean incomingThought) {
    if (signature != null || isFunctionCall() || !hasText || thought != incomingThought) {
      return false;
    }
    if (!GeminiPartAccumulator.carriesAllFieldNames(incomingPart, rawPart)) {
      return false;
    }
    return incomingText.startsWith(text.toString()) && incomingText.length() > text.length();
  }

  /** 以同一 slot 的上游完整累积形态整体替换本地 part：只做原样替换，绝不逐字段递归合并未知字段。 */
  void replaceWith(ObjectNode incomingPart, String accumulatedText) {
    this.rawPart = incomingPart;
    this.plainShape = GeminiPartAccumulator.isPlainPartShape(this.rawPart);
    this.text.setLength(0);
    this.text.append(accumulatedText);
  }
}
