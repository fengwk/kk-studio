package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 基于模型价格和 Provider 实际用量计算出的成本快照。 */
public record ModelCost(String currency, BigDecimal amount) {

  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  public ModelCost {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    Objects.requireNonNull(amount, "amount");
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
  }

  public static ModelCost calculate(ModelPricing pricing, ModelUsage usage) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");
    long billableInputTokens = usage.inputTokens() - usage.cachedInputTokens();
    BigDecimal amount =
        pricing
            .inputPerMillionTokens()
            .multiply(BigDecimal.valueOf(billableInputTokens))
            .add(
                pricing
                    .cachedInputPerMillionTokens()
                    .multiply(BigDecimal.valueOf(usage.cachedInputTokens())))
            .add(
                pricing.outputPerMillionTokens().multiply(BigDecimal.valueOf(usage.outputTokens())))
            .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
    return new ModelCost(pricing.currency(), amount);
  }
}
