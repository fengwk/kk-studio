package fun.fengwk.kkstudio.web;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;

import java.math.BigDecimal;

/** 测试专用的固定合法模型 usage 快照。 */
public final class HarnessUsageFixtures {

  private HarnessUsageFixtures() {}

  public static ModelUsageDraft completedUsageDraft() {
    return usageDraft(ProviderStopReason.COMPLETED);
  }

  public static ModelUsageDraft toolCallsUsageDraft() {
    return usageDraft(ProviderStopReason.TOOL_CALLS);
  }

  private static ModelUsageDraft usageDraft(ProviderStopReason stopReason) {
    ModelUsage usage = new ModelUsage(11, 7, 5, 3, 2, 13, 47);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "fixture-tier",
            "priority",
            new BigDecimal("1.250000000000"),
            "fixture-v1",
            new BigDecimal("1.100000000000"),
            new BigDecimal("2.200000000000"),
            new BigDecimal("0.300000000000"),
            new BigDecimal("3.400000000000"),
            new BigDecimal("4.500000000000"),
            new BigDecimal("5.600000000000"));
    return new ModelUsageDraft(
        101L,
        202L,
        ProviderType.ANTHROPIC,
        "claude-fixture",
        PromptCacheMode.BREAKPOINTS,
        PromptCacheRetention.LONG,
        true,
        "fixture-affinity",
        stopReason,
        usage,
        ModelCost.calculate(pricing, usage),
        pricing,
        "request-fixture",
        "priority-reported",
        "{\"input_tokens\":11,\"output_tokens\":7}");
  }
}
