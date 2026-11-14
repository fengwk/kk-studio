package fun.fengwk.kkstudio.platform.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser.ParsedAgentModelConfig;

import java.util.List;
import java.util.Set;

/** {@link TurnModelBudget} 只做口径适配：合法值原样返回，非法值一律收敛为确定性拒绝。 */
class TurnModelBudgetTest {

  /** 合法 limit.context / limit.output 原样通过，供普通请求与压缩共用。 */
  @Test
  void returnsConfiguredBudgets() {
    ParsedAgentModelConfig model = model(4096, 1024);

    assertEquals(4096, TurnModelBudget.contextWindow(model));
    assertEquals(1024, TurnModelBudget.outputTokens(model));
    // 剩余上下文不足以容纳 limit.output 时严格取剩余值。
    assertEquals(37, TurnModelBudget.outputTokens(model, 4096, 4059));
  }

  /** 非正或超出 int 的 limit.context 是目录数据错误：必须是 planning 拒绝，不能退化成默认窗口。 */
  @Test
  void rejectsNonPositiveContextWindow() {
    TurnRejection rejection =
        assertThrows(TurnRejection.class, () -> TurnModelBudget.contextWindow(model(0, 1024)));

    assertEquals("model limit.context must be a positive int, got 0", rejection.getMessage());
    assertThrows(
        TurnRejection.class,
        () -> TurnModelBudget.contextWindow(model(Integer.MAX_VALUE + 1L, 1024)));
  }

  /** 非正 limit.output 与耗尽上下文的请求都必须拒绝，不能伪造 1-token 预算。 */
  @Test
  void rejectsUnusableOutputBudgets() {
    ParsedAgentModelConfig model = model(4096, 0);

    assertEquals(
        "model limit.output must be a positive int, got 0",
        assertThrows(TurnRejection.class, () -> TurnModelBudget.outputTokens(model)).getMessage());
    assertEquals(
        "model limit.output must be a positive int, got 0",
        assertThrows(TurnRejection.class, () -> TurnModelBudget.outputTokens(model, 4096, 0))
            .getMessage());
    assertEquals(
        "remaining context leaves no positive output budget: contextWindow=4096,"
            + " estimatedInputTokens=4096",
        assertThrows(
                TurnRejection.class,
                () -> TurnModelBudget.outputTokens(model(4096, 1024), 4096, 4096))
            .getMessage());
  }

  private static ParsedAgentModelConfig model(long contextWindow, long maxOutputTokens) {
    return new ParsedAgentModelConfig(
        contextWindow,
        maxOutputTokens,
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        false,
        false,
        List.of(new ModelVariant("default", null, PromptCacheRetention.NONE, null)),
        "default",
        ModelPricing.ZERO);
  }
}
