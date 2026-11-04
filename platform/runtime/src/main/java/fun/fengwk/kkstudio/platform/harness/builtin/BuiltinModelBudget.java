package fun.fengwk.kkstudio.platform.harness.builtin;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPhase;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;

/**
 * Builtin 的模型与压缩预算解析。
 *
 * <p>TurnResolver 只做编排；limit.context、limit.output、剩余上下文与压缩输出预算的口径集中在这里，避免请求、预览与调试各自解释。 非法配置抛出
 * {@link IllegalArgumentException}，由调用方转换为确定性拒绝。
 */
public final class BuiltinModelBudget {

  private BuiltinModelBudget() {}

  /** model config limit.context 必须是可表示的正 int。 */
  public static int contextWindow(ParsedAgentModelConfig parsedModel) {
    long contextWindow = parsedModel.contextWindow();
    if (contextWindow <= 0 || contextWindow > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "model limit.context must be a positive int, got " + contextWindow);
    }
    return (int) contextWindow;
  }

  /** 普通请求输出预算：严格取 model 级 {@code limit.output} 与剩余上下文的较小者；剩余上下文无法提供正预算时拒绝。 */
  public static int outputTokens(
      ParsedAgentModelConfig parsedModel, int contextWindow, long estimatedInputTokens) {
    long limit = parsedModel.maxOutputTokens();
    if (limit <= 0 || limit > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("model limit.output must be a positive int, got " + limit);
    }
    long remaining = contextWindow - estimatedInputTokens;
    long outputTokens = Math.min(limit, remaining);
    if (outputTokens <= 0) {
      throw new IllegalArgumentException(
          "remaining context leaves no positive output budget: contextWindow="
              + contextWindow
              + ", estimatedInputTokens="
              + estimatedInputTokens);
    }
    return (int) outputTokens;
  }

  /** 压缩阶段预算上限：model 级 {@code limit.output}，必须是可表示的正 int。 */
  public static int outputTokens(ParsedAgentModelConfig parsedModel) {
    long outputTokens = parsedModel.maxOutputTokens();
    if (outputTokens <= 0 || outputTokens > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "model limit.output must be a positive int, got " + outputTokens);
    }
    return (int) outputTokens;
  }

  /** 按 CompactionConfig 计算压缩输出预算，并校验可表示为正 int。 */
  public static int compactionOutputBudget(
      CompactionConfig config,
      CompactionPhase phase,
      int maxOutputTokens,
      long removedPrefixTokens) {
    long outputBudget = config.outputBudget(phase, maxOutputTokens, removedPrefixTokens);
    if (outputBudget <= 0L || outputBudget > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "compaction output budget must be a positive int, got " + outputBudget);
    }
    return (int) outputBudget;
  }
}
