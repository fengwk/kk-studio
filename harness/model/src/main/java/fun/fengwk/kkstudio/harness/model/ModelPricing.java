package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.util.Objects;

/** 每百万 token 的模型价格，按 Provider 报告的用量类别分别计费。 */
public record ModelPricing(
    String currency,
    BigDecimal inputPerMillionTokens,
    BigDecimal outputPerMillionTokens,
    BigDecimal cacheReadPerMillionTokens,
    BigDecimal cacheWritePerMillionTokens,
    BigDecimal reasoningPerMillionTokens) {

  public ModelPricing {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    inputPerMillionTokens = requireNonNegative(inputPerMillionTokens, "inputPerMillionTokens");
    outputPerMillionTokens = requireNonNegative(outputPerMillionTokens, "outputPerMillionTokens");
    cacheReadPerMillionTokens =
        requireNonNegative(cacheReadPerMillionTokens, "cacheReadPerMillionTokens");
    cacheWritePerMillionTokens =
        requireNonNegative(cacheWritePerMillionTokens, "cacheWritePerMillionTokens");
    reasoningPerMillionTokens =
        requireNonNegative(reasoningPerMillionTokens, "reasoningPerMillionTokens");
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
