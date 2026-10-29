package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 完整价格定义：基础费率、按顺序叠加的覆盖规则，以及按顺序叠加的倍率规则。
 *
 * <p>所有价格单位固定为 USD / 每百万 token。价格与倍率使用 {@link BigDecimal} 精确表示；普通缺价按 0、倍率缺省按 1、 reasoning
 * 缺省继承输出。一次定义不包含 currency、服务档或版本标签。
 */
public record ModelPricing(RateSet base, List<Override> overrides, List<Multiplier> multipliers) {

  public static final int MAX_RULES = 128;
  public static final int MAX_DECIMAL_PRECISION = 64;
  public static final int MAX_DECIMAL_SCALE = 18;

  public static final ModelPricing ZERO = new ModelPricing(RateSet.ZERO, List.of(), List.of());

  public ModelPricing {
    base = Objects.requireNonNull(base, "base");
    overrides = copyRules(overrides, "overrides");
    multipliers = copyRules(multipliers, "multipliers");
    requireUniqueIds(overrides);
    requireUniqueIds(multipliers);
  }

  /** 基础费率；字段缺失在配置入口已归一化为 0。 */
  public record RateSet(
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl,
      BigDecimal reasoning) {

    public static final RateSet ZERO =
        new RateSet(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), null);

    public RateSet {
      input = requireNonNullNonNegative(input, "input");
      output = requireNonNullNonNegative(output, "output");
      cacheRead = requireNonNullNonNegative(cacheRead, "cacheRead");
      cacheWrite = requireNonNullNonNegative(cacheWrite, "cacheWrite");
      cacheWriteByTtl = copyTtlRates(cacheWriteByTtl);
      reasoning = reasoning == null ? null : requireNonNegative(reasoning, "reasoning");
    }

    /** TTL 精确覆盖通用写价；没有覆盖时返回通用 cacheWrite。 */
    public BigDecimal cacheWriteRate(long ttlSeconds) {
      return cacheWriteByTtl.getOrDefault(ttlSeconds, cacheWrite);
    }

    /** reasoning 是否配置了独立单价；false 表示继承最终 output 价。 */
    public boolean independentReasoning() {
      return reasoning != null;
    }
  }

  /** 规则补丁；字段 null 表示不修改，{@code reasoningSet} 表示显式设置（包括 null 继承）。 */
  public record RatePatch(
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      Map<Long, BigDecimal> cacheWriteByTtl,
      BigDecimal reasoning,
      boolean reasoningSet) {

    public RatePatch {
      input = optionalNonNegative(input, "input");
      output = optionalNonNegative(output, "output");
      cacheRead = optionalNonNegative(cacheRead, "cacheRead");
      cacheWrite = optionalNonNegative(cacheWrite, "cacheWrite");
      cacheWriteByTtl = copyNullableTtlRates(cacheWriteByTtl);
      reasoning = optionalNonNegative(reasoning, "reasoning");
      if (reasoning != null) {
        reasoningSet = true;
      }
    }

    public boolean modifiesReasoning() {
      return reasoningSet;
    }
  }

  /** 一条覆盖规则：条件命中时按顺序把非 null 字段写入当前费率。 */
  public record Override(String id, ModelPricingCondition when, RatePatch rates) {

    public Override {
      id = requireId(id, "override id");
      when = Objects.requireNonNull(when, "override when");
      rates = Objects.requireNonNull(rates, "override rates");
    }
  }

  /** 一条倍率规则：只作用于指定价格字段，未列出字段倍率为 1。 */
  public record Multiplier(String id, ModelPricingCondition when, Factors factors) {

    public Multiplier {
      id = requireId(id, "multiplier id");
      when = Objects.requireNonNull(when, "multiplier when");
      factors = Objects.requireNonNull(factors, "multiplier factors");
    }
  }

  /** 价格字段倍率；null 表示该字段不应用倍率（等价于 1）。 */
  public record Factors(
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      BigDecimal reasoning) {

    public Factors {
      input = optionalNonNegative(input, "input factor");
      output = optionalNonNegative(output, "output factor");
      cacheRead = optionalNonNegative(cacheRead, "cacheRead factor");
      cacheWrite = optionalNonNegative(cacheWrite, "cacheWrite factor");
      reasoning = optionalNonNegative(reasoning, "reasoning factor");
    }
  }

  static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return requireBounded(value, name);
  }

  static BigDecimal optionalNonNegative(BigDecimal value, String name) {
    return value == null ? null : requireNonNegative(value, name);
  }

  private static BigDecimal requireNonNullNonNegative(BigDecimal value, String name) {
    return requireNonNegative(Objects.requireNonNull(value, name), name);
  }

  private static BigDecimal requireBounded(BigDecimal value, String name) {
    if (value.precision() > MAX_DECIMAL_PRECISION) {
      throw new IllegalArgumentException(
          name + " precision must not exceed " + MAX_DECIMAL_PRECISION);
    }
    if (value.scale() > MAX_DECIMAL_SCALE) {
      throw new IllegalArgumentException(name + " scale must not exceed " + MAX_DECIMAL_SCALE);
    }
    return value;
  }

  private static Map<Long, BigDecimal> copyTtlRates(Map<Long, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> copy = copyNullableTtlRates(rates);
    return Map.copyOf(copy);
  }

  private static Map<Long, BigDecimal> copyNullableTtlRates(Map<Long, BigDecimal> rates) {
    if (rates == null) {
      return null;
    }
    if (rates.size() > MAX_RULES) {
      throw new IllegalArgumentException(
          "cacheWriteByTtl must not exceed " + MAX_RULES + " entries");
    }
    Map<Long, BigDecimal> copy = new LinkedHashMap<>();
    for (Map.Entry<Long, BigDecimal> entry : rates.entrySet()) {
      Long ttl = entry.getKey();
      if (ttl == null || ttl <= 0) {
        throw new IllegalArgumentException("cacheWriteByTtl keys must be positive seconds");
      }
      copy.put(
          ttl,
          requireNonNegative(Objects.requireNonNull(entry.getValue(), "ttl rate"), "ttl rate"));
    }
    return copy;
  }

  private static <T> List<T> copyRules(List<T> rules, String name) {
    if (rules == null || rules.isEmpty()) {
      return List.of();
    }
    if (rules.size() > MAX_RULES) {
      throw new IllegalArgumentException(name + " must not exceed " + MAX_RULES + " rules");
    }
    List<T> copy = new ArrayList<>(rules.size());
    for (T rule : rules) {
      copy.add(Objects.requireNonNull(rule, name + " rule"));
    }
    return List.copyOf(copy);
  }

  private static void requireUniqueIds(List<?> rules) {
    Set<String> ids = new HashSet<>();
    for (Object rule : rules) {
      String id = rule instanceof Override override ? override.id() : ((Multiplier) rule).id();
      if (!ids.add(id)) {
        throw new IllegalArgumentException("pricing rule ids must be unique: " + id);
      }
    }
  }

  private static String requireId(String id, String name) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return id;
  }
}
