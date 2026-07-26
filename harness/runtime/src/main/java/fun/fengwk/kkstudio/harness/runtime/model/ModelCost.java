package fun.fengwk.kkstudio.harness.runtime.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 基于模型价格和 Provider 实际用量计算出的成本快照。 */
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
  private static final int COST_SCALE = 12;

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

  /** 返回 {@link #total} 的语义别名。 */
  public BigDecimal amount() {
    return total;
  }

  /**
   * 对每个分项独立计算 {@code price * tokens / 1_000_000 * serviceTierMultiplier}，统一 {@code scale = 12
   * HALF_UP}；再把六个 已含 multiplier 的分项相加作为 total。
   *
   * <p>分项先乘 multiplier 再舍入，构造时 {@code total == sum(categories)} 始终成立，避免分项舍入差让 {@link #ModelCost}
   * 校验失败。
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
        input
            .add(output)
            .add(cacheRead)
            .add(cacheWrite)
            .add(cacheWriteLong)
            .add(reasoning)
            .setScale(COST_SCALE, RoundingMode.HALF_UP);
    return new ModelCost(
        pricing.currency(), input, output, cacheRead, cacheWrite, cacheWriteLong, reasoning, total);
  }

  private static BigDecimal priced(
      BigDecimal pricePerMillionTokens, long tokens, BigDecimal multiplier) {
    return pricePerMillionTokens
        .multiply(BigDecimal.valueOf(tokens))
        .divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP)
        .multiply(multiplier)
        .setScale(COST_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
