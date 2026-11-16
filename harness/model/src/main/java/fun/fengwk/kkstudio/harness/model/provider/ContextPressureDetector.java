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
 *   <li>authoritative usage 的 prompt tokens（input + cacheRead）达到/超过 contextWindow。
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
    return promptTokens(usage) >= contextWindow
        || (stopReason == GenerationStopReason.LENGTH
            && usage.outputTokens() == 0
            && promptTokens(usage) >= (long) Math.ceil(contextWindow * 0.99d));
  }

  private static long promptTokens(ModelUsage usage) {
    long input = usage.inputTokens();
    long cache = usage.cacheReadTokens();
    return cache > Long.MAX_VALUE - input ? Long.MAX_VALUE : input + cache;
  }
}
