package fun.fengwk.kkstudio.harness.runtime.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一次请求的费用明细；每个金额都由当前 {@link ModelPricing} 与记录的真实用量精确现算。
 *
 * <p>输出金额只覆盖非 reasoning 输出；独立 reasoning 价存在时，reasoning 金额单独列出。reasoning 继承输出价时， output 金额包含全部输出
 * token，reasoning 为 0，避免重复计费。
 */
public record ModelCost(
    String currency,
    BigDecimal input,
    BigDecimal output,
    BigDecimal cacheRead,
    BigDecimal cacheWrite,
    BigDecimal reasoning,
    BigDecimal total) {

  /** 本版本唯一计价币种。 */
  public static final String USD = "USD";

  private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

  public ModelCost {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    input = requireNonNegative(input, "input");
    output = requireNonNegative(output, "output");
    cacheRead = requireNonNegative(cacheRead, "cacheRead");
    cacheWrite = requireNonNegative(cacheWrite, "cacheWrite");
    reasoning = requireNonNegative(reasoning, "reasoning");
    Objects.requireNonNull(total, "total");
    BigDecimal sum = input.add(output).add(cacheRead).add(cacheWrite).add(reasoning);
    if (total.compareTo(sum) != 0) {
      throw new IllegalArgumentException(
          "total must equal the sum of input, output, cacheRead, cacheWrite and reasoning");
    }
    if (total.signum() < 0) {
      throw new IllegalArgumentException("total must not be negative");
    }
  }

  /** 无条件便利入口；缺少计价事实时返回 null，不冒充免费请求。 */
  public static ModelCost calculate(ModelPricing pricing, ModelUsage usage) {
    return calculate(pricing, usage, null, null);
  }

  /**
   * 使用实际请求时间与最终 service tier 解析规则并计算精确费用。
   *
   * <p>缺少完成当前规则判定必需的事实、reasoning 独立定价缺少可信子集或没有匹配费率时返回 null；非法配置和用量溢出仍然抛出异常。
   */
  public static ModelCost calculate(
      ModelPricing pricing, ModelUsage usage, Instant requestedAt, String serviceTier) {
    Objects.requireNonNull(pricing, "pricing");
    Objects.requireNonNull(usage, "usage");
    ModelPricingResolver.ResolvedRates rates =
        ModelPricingResolver.resolve(pricing, usage, requestedAt, serviceTier);
    if (rates == null) {
      return null;
    }

    BigDecimal input = priced(rates.input(), usage.inputTokens());
    BigDecimal cacheRead = priced(rates.cacheRead(), usage.cacheReadTokens());
    BigDecimal cacheWrite = cacheWriteCost(rates, usage);

    BigDecimal output;
    BigDecimal reasoning;
    if (rates.independentReasoning()
        && rates.output().compareTo(rates.reasoning()) != 0
        && usage.outputTokens() != 0) {
      Long reasoningTokens = usage.reasoningTokens();
      if (reasoningTokens == null) {
        // 独立 reasoning 价必须有可信子集，否则不能把全部输出都按 reasoning 价猜测。
        return null;
      }
      long nonReasoningTokens = usage.outputTokens() - reasoningTokens;
      output = priced(rates.output(), nonReasoningTokens);
      reasoning = priced(rates.reasoning(), reasoningTokens);
    } else {
      output = priced(rates.output(), usage.outputTokens());
      reasoning = BigDecimal.ZERO;
    }

    BigDecimal total = input.add(output).add(cacheRead).add(cacheWrite).add(reasoning);
    return new ModelCost(USD, input, output, cacheRead, cacheWrite, reasoning, total);
  }

  /** 已知的完整零费用；不用于表示缺价或缺少计价事实。 */
  public static ModelCost zero() {
    return new ModelCost(
        USD,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static BigDecimal cacheWriteCost(
      ModelPricingResolver.ResolvedRates rates, ModelUsage usage) {
    BigDecimal total = BigDecimal.ZERO;
    long allocated = 0L;
    for (Map.Entry<Long, Long> entry : usage.cacheWriteByTtl().entrySet()) {
      long tokens = entry.getValue();
      BigDecimal rate = rates.cacheWriteByTtl().getOrDefault(entry.getKey(), rates.cacheWrite());
      total = total.add(priced(rate, tokens));
      allocated = Math.addExact(allocated, tokens);
    }
    long unallocated = usage.cacheWriteTokens() - allocated;
    return total.add(priced(rates.cacheWrite(), unallocated));
  }

  private static BigDecimal priced(BigDecimal pricePerMillionTokens, long tokens) {
    return pricePerMillionTokens.multiply(BigDecimal.valueOf(tokens)).divide(ONE_MILLION);
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
