package fun.fengwk.kkstudio.harness.runtime.model;

import java.math.BigDecimal;
import java.util.Objects;

/** 基于模型价格与 Provider 用量的成本计算结果；每次读取投影按当前 catalog pricing 复用 {@link #calculate} 现算。 */
public record ModelCost(
    String currency,
    BigDecimal input,
    BigDecimal output,
    BigDecimal cacheRead,
    BigDecimal cacheWrite,
    BigDecimal cacheWriteLong,
    BigDecimal reasoning,
    BigDecimal total) {

  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  public ModelCost {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    input = requireNonNegative(input, "input");
    output = requireNonNegative(output, "output");
    cacheRead = requireNonNegative(cacheRead, "cacheRead");
    cacheWrite = requireNonNegative(cacheWrite, "cacheWrite");
    cacheWriteLong = requireNonNegative(cacheWriteLong, "cacheWriteLong");
    reasoning = requireNonNegative(reasoning, "reasoning");
    Objects.requireNonNull(total, "total");
    BigDecimal sum =
        input.add(output).add(cacheRead).add(cacheWrite).add(cacheWriteLong).add(reasoning);
    if (total.compareTo(sum) != 0) {
      throw new IllegalArgumentException(
          "total must equal the sum of input, output, cacheRead, cacheWrite, cacheWriteLong and"
              + " reasoning");
    }
    if (total.signum() < 0) {
      throw new IllegalArgumentException("total must not be negative");
    }
  }

  /**
   * 纯计算：每个分项独立计算 {@code price * tokens * serviceTierMultiplier / 1_000_000}，全链路使用精确 {@link
   * BigDecimal} 除法（除数是 10 的幂，商必然终止），不做任何分项舍入；{@code total} 是六个精确分项之和。
   *
   * <p>先求和后舍入由调用方（读取投影）负责，微费用不会在分项被截断为 0；构造时 {@code total == sum(categories)} 始终成立。
   */
  public static ModelCost calculate(ModelPricing pricing, ModelUsage usage) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");
    BigDecimal multiplier = pricing.serviceTierMultiplier();
    BigDecimal input = priced(pricing.inputPerMillionTokens(), usage.inputTokens(), multiplier);
    BigDecimal output = priced(pricing.outputPerMillionTokens(), usage.outputTokens(), multiplier);
    BigDecimal cacheRead =
        priced(pricing.cacheReadPerMillionTokens(), usage.cacheReadTokens(), multiplier);
    BigDecimal cacheWrite =
        priced(pricing.cacheWritePerMillionTokens(), usage.cacheWriteTokens(), multiplier);
    BigDecimal cacheWriteLong =
        priced(pricing.cacheWriteLongPerMillionTokens(), usage.cacheWriteLongTokens(), multiplier);
    BigDecimal reasoning =
        priced(pricing.reasoningPerMillionTokens(), usage.reasoningTokens(), multiplier);
    BigDecimal total =
        input.add(output).add(cacheRead).add(cacheWrite).add(cacheWriteLong).add(reasoning);
    return new ModelCost(
        pricing.currency(), input, output, cacheRead, cacheWrite, cacheWriteLong, reasoning, total);
  }

  private static BigDecimal priced(
      BigDecimal pricePerMillionTokens, long tokens, BigDecimal multiplier) {
    return pricePerMillionTokens
        .multiply(BigDecimal.valueOf(tokens))
        .divide(ONE_MILLION)
        .multiply(multiplier);
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
