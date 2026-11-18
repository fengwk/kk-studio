package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一个完整价格计划：基础费率、三个互斥规则组，以及按 UTC 日期生效的完整价格变更。
 *
 * <p>规则只由 input-token range 选择费率；time 和 service-tier 两组分别选择倍率，并在该顺序下相乘。所有金额及倍率均由 {@link BigDecimal}
 * 精确表示，普通缺价仍按配置入口约定归一为零，TTL-only 缓存写入允许普通写价为空。
 */
public record ModelPricing(RateSet base, List<Rule> rules, List<DateChange> changes) {

  public static final int MAX_RULES = 128;
  public static final int MAX_DECIMAL_PRECISION = 64;
  public static final int MAX_DECIMAL_SCALE = 18;

  public static final ModelPricing ZERO = new ModelPricing(RateSet.ZERO, List.of(), List.of());

  public ModelPricing {
    base = Objects.requireNonNull(base, "base");
    rules = sortedRules(rules);
    changes = sortedChanges(changes);
    validateChanges(changes);
  }

  /** 基础单价（USD / 百万 token）；reasoning 为 null 表示跟随最终 output 价。 */
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
      input = requiredRate(input, "input");
      output = requiredRate(output, "output");
      cacheRead = requiredRate(cacheRead, "cacheRead");
      cacheWrite = optionalRate(cacheWrite, "cacheWrite");
      cacheWriteByTtl = copyTtlRates(cacheWriteByTtl);
      reasoning = optionalRate(reasoning, "reasoning");
    }

    public boolean independentReasoning() {
      return reasoning != null;
    }
  }

  /** 三个固定类型规则之一；列表不会表达跨类型的优先级。 */
  public sealed interface Rule permits InputTokenRange, TimeMultiplier, ServiceTierMultiplier {}

  /** 命中时仅覆盖所给单价；总输入 token 数包含缓存读写。上界为排他边界，null 表示无上界。 */
  public record InputTokenRange(
      int minInputTokens, Integer maxInputTokensExclusive, BigDecimal input, BigDecimal output)
      implements Rule {

    public InputTokenRange {
      if (minInputTokens < 0) {
        throw new IllegalArgumentException("minInputTokens must not be negative");
      }
      if (maxInputTokensExclusive != null && maxInputTokensExclusive <= minInputTokens) {
        throw new IllegalArgumentException("maxInputTokensExclusive must exceed minInputTokens");
      }
      if (input == null && output == null) {
        throw new IllegalArgumentException("input_token_range must override input or output");
      }
      input = optionalRate(input, "input range rate");
      output = optionalRate(output, "output range rate");
    }

    public boolean contains(long inputTokens) {
      return inputTokens >= minInputTokens
          && (maxInputTokensExclusive == null || inputTokens < maxInputTokensExclusive);
    }
  }

  /** 按本次 attempt 开始时间在指定时区匹配；区间左闭右开且不跨午夜。 */
  public record TimeMultiplier(
      int startMinuteOfDay,
      int endMinuteOfDay,
      ZoneId timezone,
      List<Integer> weekdays,
      Factors factors)
      implements Rule {

    public TimeMultiplier {
      if (startMinuteOfDay < 0 || startMinuteOfDay >= 1440) {
        throw new IllegalArgumentException("time start must be within a day");
      }
      if (endMinuteOfDay <= startMinuteOfDay || endMinuteOfDay > 1440) {
        throw new IllegalArgumentException("time end must be after start and at most 24:00");
      }
      timezone = Objects.requireNonNull(timezone, "timezone");
      weekdays = copyWeekdays(weekdays);
      factors = Objects.requireNonNull(factors, "factors");
    }

    boolean matches(Instant at) {
      var local = at.atZone(timezone);
      int minute = local.getHour() * 60 + local.getMinute();
      return minute >= startMinuteOfDay
          && minute < endMinuteOfDay
          && (weekdays.isEmpty() || weekdays.contains(local.getDayOfWeek().getValue()));
    }
  }

  /** Provider 返回的实际服务档倍率；请求意图不能代替最终响应事实。 */
  public record ServiceTierMultiplier(String serviceTier, Factors factors) implements Rule {

    public ServiceTierMultiplier {
      if (serviceTier == null || serviceTier.isBlank()) {
        throw new IllegalArgumentException("serviceTier must not be blank");
      }
      factors = Objects.requireNonNull(factors, "factors");
    }
  }

  /** 默认倍率作用于全部费用项；指定费用项倍率覆盖默认值。 */
  public record Factors(
      BigDecimal factor,
      BigDecimal inputFactor,
      BigDecimal outputFactor,
      BigDecimal cacheReadFactor,
      BigDecimal cacheWriteFactor,
      BigDecimal reasoningFactor) {

    public Factors {
      factor = requiredRate(factor, "factor");
      inputFactor = optionalRate(inputFactor, "inputFactor");
      outputFactor = optionalRate(outputFactor, "outputFactor");
      cacheReadFactor = optionalRate(cacheReadFactor, "cacheReadFactor");
      cacheWriteFactor = optionalRate(cacheWriteFactor, "cacheWriteFactor");
      reasoningFactor = optionalRate(reasoningFactor, "reasoningFactor");
    }

    BigDecimal input() {
      return inputFactor == null ? factor : inputFactor;
    }

    BigDecimal output() {
      return outputFactor == null ? factor : outputFactor;
    }

    BigDecimal cacheRead() {
      return cacheReadFactor == null ? factor : cacheReadFactor;
    }

    BigDecimal cacheWrite() {
      return cacheWriteFactor == null ? factor : cacheWriteFactor;
    }

    BigDecimal reasoning() {
      return reasoningFactor == null ? factor : reasoningFactor;
    }
  }

  /** 每个变更从 UTC 日期（含当天）起替换整个价格定义。 */
  public record DateChange(LocalDate from, RateSet base, List<Rule> rules) {

    public DateChange {
      from = Objects.requireNonNull(from, "change.from");
      base = Objects.requireNonNull(base, "change.base");
      rules = sortedRules(rules);
    }
  }

  /** 按统一价格逻辑解析基础定义或变更定义，便于静态结构检查。 */
  public record PriceDefinition(RateSet base, List<Rule> rules) {

    public PriceDefinition {
      base = Objects.requireNonNull(base, "base");
      rules = sortedRules(rules);
    }
  }

  static BigDecimal requireNonNegative(BigDecimal value, String name) {
    Objects.requireNonNull(value, name);
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    if (value.precision() > MAX_DECIMAL_PRECISION) {
      throw new IllegalArgumentException(
          name + " precision must not exceed " + MAX_DECIMAL_PRECISION);
    }
    if (value.scale() > MAX_DECIMAL_SCALE) {
      throw new IllegalArgumentException(name + " scale must not exceed " + MAX_DECIMAL_SCALE);
    }
    return value;
  }

  static BigDecimal optionalRate(BigDecimal value, String name) {
    return value == null ? null : requireNonNegative(value, name);
  }

  private static BigDecimal requiredRate(BigDecimal value, String name) {
    return requireNonNegative(Objects.requireNonNull(value, name), name);
  }

  private static Map<Long, BigDecimal> copyTtlRates(Map<Long, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    if (rates.size() > MAX_RULES) {
      throw new IllegalArgumentException(
          "cacheWriteByTtl must not exceed " + MAX_RULES + " entries");
    }
    TreeMap<Long, BigDecimal> copy = new TreeMap<>();
    for (Map.Entry<Long, BigDecimal> entry : rates.entrySet()) {
      Long ttl = entry.getKey();
      if (ttl == null || ttl <= 0) {
        throw new IllegalArgumentException("cacheWriteByTtl keys must be positive seconds");
      }
      copy.put(ttl, requiredRate(entry.getValue(), "ttl rate"));
    }
    return Map.copyOf(copy);
  }

  private static List<Rule> sortedRules(List<Rule> values) {
    if (values == null) {
      values = List.of();
    }
    if (values.size() > MAX_RULES) {
      throw new IllegalArgumentException("rules must not exceed " + MAX_RULES + " rules");
    }
    List<Rule> result = values.isEmpty() ? new ArrayList<>() : new ArrayList<>(values);
    if (result.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("rules must not contain null");
    }
    result.sort(RULE_ORDER);
    validateRuleGroups(result);
    return List.copyOf(result);
  }

  private static List<DateChange> sortedChanges(List<DateChange> values) {
    if (values == null) {
      values = List.of();
    }
    if (values.size() > MAX_RULES) {
      throw new IllegalArgumentException("changes must not exceed " + MAX_RULES + " entries");
    }
    List<DateChange> result = values.isEmpty() ? new ArrayList<>() : new ArrayList<>(values);
    if (result.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("changes must not contain null");
    }
    result.sort(Comparator.comparing(DateChange::from));
    return List.copyOf(result);
  }

  private static void validateChanges(List<DateChange> changes) {
    LocalDate previous = null;
    for (DateChange change : changes) {
      if (previous != null && !previous.isBefore(change.from())) {
        throw new IllegalArgumentException("changes.from dates must be unique");
      }
      previous = change.from();
    }
  }

  private static final Comparator<Rule> RULE_ORDER =
      Comparator.comparingInt(ModelPricing::ruleType).thenComparing(ModelPricing::ruleKey);

  private static int ruleType(Rule rule) {
    if (rule instanceof InputTokenRange) {
      return 0;
    }
    if (rule instanceof ServiceTierMultiplier) {
      return 1;
    }
    return 2;
  }

  private static String ruleKey(Rule rule) {
    if (rule instanceof InputTokenRange range) {
      int upper =
          range.maxInputTokensExclusive() == null
              ? Integer.MAX_VALUE
              : range.maxInputTokensExclusive();
      return String.format("%010d:%010d", range.minInputTokens(), upper);
    }
    if (rule instanceof ServiceTierMultiplier tier) {
      return tier.serviceTier();
    }
    TimeMultiplier time = (TimeMultiplier) rule;
    return time.weekdays() + ":" + time.startMinuteOfDay();
  }

  private static void validateRuleGroups(List<Rule> rules) {
    List<InputTokenRange> ranges = new ArrayList<>();
    Set<String> tiers = new HashSet<>();
    List<TimeMultiplier> times = new ArrayList<>();
    ZoneId timezone = null;
    for (Rule rule : rules) {
      if (rule instanceof InputTokenRange range) {
        ranges.add(range);
      } else if (rule instanceof ServiceTierMultiplier tier) {
        if (!tiers.add(tier.serviceTier())) {
          throw new IllegalArgumentException(
              "service tier rules must be unique: " + tier.serviceTier());
        }
      } else if (rule instanceof TimeMultiplier time) {
        if (timezone != null && !timezone.equals(time.timezone())) {
          throw new IllegalArgumentException("time rules must use one timezone");
        }
        timezone = time.timezone();
        for (TimeMultiplier previous : times) {
          if (shareWeekday(previous.weekdays(), time.weekdays())
              && previous.startMinuteOfDay() < time.endMinuteOfDay()
              && time.startMinuteOfDay() < previous.endMinuteOfDay()) {
            throw new IllegalArgumentException("time rules must not overlap on a weekday");
          }
        }
        times.add(time);
      }
    }
    ranges.sort(Comparator.comparingInt(InputTokenRange::minInputTokens));
    for (int i = 1; i < ranges.size(); i++) {
      Integer previousEnd = ranges.get(i - 1).maxInputTokensExclusive();
      if (previousEnd == null || previousEnd > ranges.get(i).minInputTokens()) {
        throw new IllegalArgumentException("input token ranges must not overlap");
      }
    }
  }

  private static boolean shareWeekday(List<Integer> left, List<Integer> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return true;
    }
    for (Integer weekday : left) {
      if (right.contains(weekday)) {
        return true;
      }
    }
    return false;
  }

  private static List<Integer> copyWeekdays(List<Integer> weekdays) {
    if (weekdays == null || weekdays.isEmpty()) {
      return List.of();
    }
    Set<Integer> values = new HashSet<>();
    for (Integer weekday : weekdays) {
      if (weekday == null || weekday < 1 || weekday > 7 || !values.add(weekday)) {
        throw new IllegalArgumentException("weekdays must be unique ISO weekdays from 1 to 7");
      }
    }
    return values.stream().sorted().toList();
  }
}
