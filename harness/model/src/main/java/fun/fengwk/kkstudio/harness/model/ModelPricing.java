package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.util.Objects;

/** 每百万 token 的模型价格，用于可重现地计算请求成本。 */
public record ModelPricing(
    String currency,
    BigDecimal inputPerMillionTokens,
    BigDecimal outputPerMillionTokens,
    BigDecimal cachedInputPerMillionTokens) {

  public ModelPricing {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    inputPerMillionTokens = requireNonNegative(inputPerMillionTokens, "inputPerMillionTokens");
    outputPerMillionTokens = requireNonNegative(outputPerMillionTokens, "outputPerMillionTokens");
    cachedInputPerMillionTokens =
        requireNonNegative(cachedInputPerMillionTokens, "cachedInputPerMillionTokens");
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
