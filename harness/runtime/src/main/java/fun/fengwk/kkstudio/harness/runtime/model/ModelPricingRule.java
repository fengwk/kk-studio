package fun.fengwk.kkstudio.harness.runtime.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 条件价格：时段与上下文长度门槛至少给出一项，全部满足时用本规则的分项单价替换基础价格（每百万 token）。
 *
 * <p>{@code minContextTokens} 与请求的提示 token 数（输入加各类缓存读写）比较，达到门槛即满足。
 */
public record ModelPricingRule(
    ModelPricingWindow timeWindow,
    Long minContextTokens,
    BigDecimal inputPerMillionTokens,
    BigDecimal outputPerMillionTokens,
    BigDecimal cacheReadPerMillionTokens,
    BigDecimal cacheWritePerMillionTokens,
    BigDecimal cacheWriteLongPerMillionTokens,
    BigDecimal reasoningPerMillionTokens) {

  public ModelPricingRule {
    if (timeWindow == null && minContextTokens == null) {
      throw new IllegalArgumentException(
          "pricing rule needs a time window or a minimum context size");
    }
    if (minContextTokens != null && minContextTokens <= 0) {
      throw new IllegalArgumentException("minContextTokens must be positive");
    }
    requireNonNegative(inputPerMillionTokens, "inputPerMillionTokens");
    requireNonNegative(outputPerMillionTokens, "outputPerMillionTokens");
    requireNonNegative(cacheReadPerMillionTokens, "cacheReadPerMillionTokens");
    requireNonNegative(cacheWritePerMillionTokens, "cacheWritePerMillionTokens");
    requireNonNegative(cacheWriteLongPerMillionTokens, "cacheWriteLongPerMillionTokens");
    requireNonNegative(reasoningPerMillionTokens, "reasoningPerMillionTokens");
  }

  /** 请求在 {@code at} 完成、提示 token 为 {@code contextTokens} 时本规则是否生效。 */
  public boolean matches(Instant at, long contextTokens) {
    return (timeWindow == null || timeWindow.contains(at))
        && (minContextTokens == null || contextTokens >= minContextTokens);
  }

  private static void requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
