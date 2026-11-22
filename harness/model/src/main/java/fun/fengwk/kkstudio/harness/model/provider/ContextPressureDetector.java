package fun.fengwk.kkstudio.harness.model.provider;

import fun.fengwk.kkstudio.harness.model.ModelUsage;

import java.util.Objects;

/**
 * 成功生成响应的 context-pressure 纯检测器。
 *
 * <p>只基于 authoritative usage、stop reason 与 context window 判断 silent context wall，不引入 durable
 * 状态/status，也不解析 Provider 错误文本。命中任一规则即返回 true：
 *
 * <ol>
 *   <li>authoritative usage 的 prompt tokens（input + cacheRead + cacheWrite）达到/超过 contextWindow。
 *   <li>严格 LENGTH：stopReason 为 LENGTH 且 outputTokens == 0 且 prompt tokens ≥ ceil(contextWindow *
 *       0.99)。
 * </ol>
 *
 * <p>普通 LENGTH（有 output 或明显低于 window）不是 context wall；{@code contextWindow} 必须为正。
 */
public final class ContextPressureDetector {

  private ContextPressureDetector() {}

  /** 只基于成功 terminal response 的 generation/usage/window 判断 silent context wall。 */
  public static boolean detectResponse(
      GenerationStopReason stopReason, ModelUsage usage, long contextWindow) {
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    Objects.requireNonNull(usage, "usage");
    long promptTokens;
    try {
      promptTokens = usage.contextTokens();
    } catch (ArithmeticException overflow) {
      // 用量分量均非负：总和溢出意味着已超过任意可表示的窗口。
      return true;
    }
    return promptTokens >= contextWindow
        || (stopReason == GenerationStopReason.LENGTH
            && usage.outputTokens() == 0
            && promptTokens >= (long) Math.ceil(contextWindow * 0.99d));
  }
}
