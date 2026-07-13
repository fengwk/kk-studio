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

  /** 根据每种 Provider 用量类别的适用单价计算成本。 */
  public static ModelCost calculate(ModelPricing pricing, ModelUsage usage) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");
    BigDecimal amount =
        cost(pricing.inputPerMillionTokens(), usage.inputTokens())
            .add(cost(pricing.outputPerMillionTokens(), usage.outputTokens()))
            .add(cost(pricing.cacheReadPerMillionTokens(), usage.cacheReadTokens()))
            .add(cost(pricing.cacheWritePerMillionTokens(), usage.cacheWriteTokens()))
            .add(cost(pricing.reasoningPerMillionTokens(), usage.reasoningTokens()));
    return new ModelCost(pricing.currency(), amount.setScale(12, RoundingMode.HALF_UP));
  }

  private static BigDecimal cost(BigDecimal pricePerMillionTokens, long tokens) {
    return pricePerMillionTokens
        .multiply(BigDecimal.valueOf(tokens))
        .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
  }
}
