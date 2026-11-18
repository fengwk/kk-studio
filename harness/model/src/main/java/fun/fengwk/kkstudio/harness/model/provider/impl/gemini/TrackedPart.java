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
}
