package fun.fengwk.kkstudio.platform.harness.thread.command;

import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.harness.builtin.BuiltinModelBudget;

/**
 * Builtin 预算口径的适配层：预算算法集中在 {@link BuiltinModelBudget}，这里只把非法配置转换为确定性拒绝。
 *
 * <p>非法的 contextWindow / outputTokens 配置属于目录数据问题，必须让本 turn 以 planning 失败结束，不能退化成默认值继续执行。
 */
final class TurnModelBudget {

  private TurnModelBudget() {}

  static int contextWindow(ParsedAgentModelConfig parsedModel) {
    try {
      return BuiltinModelBudget.contextWindow(parsedModel);
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of(error.getMessage());
    }
  }

  static int outputTokens(
      ParsedAgentModelConfig parsedModel, int contextWindow, long estimatedInputTokens) {
    try {
      return BuiltinModelBudget.outputTokens(parsedModel, contextWindow, estimatedInputTokens);
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of(error.getMessage());
    }
  }

  static int outputTokens(ParsedAgentModelConfig parsedModel) {
    try {
      return BuiltinModelBudget.outputTokens(parsedModel);
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of(error.getMessage());
    }
  }
}
