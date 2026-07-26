package fun.fengwk.kkstudio.harness.runtime.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 一次请求生效的不可变 pricing snapshot，包含分项单价、tier、service tier multiplier 与 version。
 *
 * <p>所有价格按每百万 token 计费；{@code serviceTierMultiplier} 在 {@link ModelCost#calculate(ModelPricing,
 * ModelUsage)} 中由 harness 应用到分项金额，并非 Provider 服务端在计费时使用。
 */
public record ModelPricing(
    String currency,
    String pricingTier,
    String serviceTier,
    BigDecimal serviceTierMultiplier,
    String version,
    BigDecimal inputPerMillionTokens,
    BigDecimal outputPerMillionTokens,
    BigDecimal cacheReadPerMillionTokens,
    BigDecimal cacheWritePerMillionTokens,
    BigDecimal cacheWriteLongPerMillionTokens,
    BigDecimal reasoningPerMillionTokens) {

  public ModelPricing {
    currency = requireNonBlank(currency, "currency");
    pricingTier = requireNonBlank(pricingTier, "pricingTier");
    serviceTier = requireNonBlank(serviceTier, "serviceTier");
    version = requireNonBlank(version, "version");
    serviceTierMultiplier = requirePositive(serviceTierMultiplier, "serviceTierMultiplier");
    inputPerMillionTokens = requireNonNegative(inputPerMillionTokens, "inputPerMillionTokens");
    outputPerMillionTokens = requireNonNegative(outputPerMillionTokens, "outputPerMillionTokens");
    cacheReadPerMillionTokens =
        requireNonNegative(cacheReadPerMillionTokens, "cacheReadPerMillionTokens");
    cacheWritePerMillionTokens =
        requireNonNegative(cacheWritePerMillionTokens, "cacheWritePerMillionTokens");
    cacheWriteLongPerMillionTokens =
        requireNonNegative(cacheWriteLongPerMillionTokens, "cacheWriteLongPerMillionTokens");
    reasoningPerMillionTokens =
        requireNonNegative(reasoningPerMillionTokens, "reasoningPerMillionTokens");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static BigDecimal requirePositive(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
