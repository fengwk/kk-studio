package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingChangeDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Strict models.dev token-price conversion to the fixed pricing rule contract. */
final class ModelsDevPricingParser {

  private static final Set<String> COST_KEYS =
      Set.of("text", "multipliers", "audio", "image", "video", "web_search");
  private static final Set<String> TEXT_KEYS = Set.of("input", "output");
  private static final Set<String> INPUT_KEYS = Set.of("price", "cache", "overrides", "unit");
  private static final Set<String> OUTPUT_KEYS = Set.of("price", "reasoning", "overrides", "unit");
  private static final Set<String> CACHE_KEYS = Set.of("read", "write", "explicit", "storage");
  private static final Set<String> TOKEN_UNITS =
      Set.of("token", "tokens", "per_million", "million_tokens");
  private static final int MAX_SOURCE_RULES = 128;

  private ModelsDevPricingParser() {}

  /** Rejects the complete candidate if any token price cannot be represented exactly. */
  static ModelPricingDTO parse(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      throw noPrice();
    }
    JsonNode cost = CatalogJson.requireObject(node, "cost");
    for (String key : cost.propertyNames()) {
      if (!COST_KEYS.contains(key)) {
        throw new CatalogPriceException("unsupported v2 cost field: " + key);
      }
      if (!Set.of("text", "multipliers").contains(key) && !cost.get(key).isNull()) {
        issues.add("non-token cost is not included: " + key);
      }
    }
    JsonNode text = cost.get("text");
    if (text == null || text.isNull()) {
      throw noPrice();
    }

    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    pricing.setBase(base);
    List<ModelPricingRuleDTO> rules = new ArrayList<>();
    pricing.setRules(rules);
    pricing.setChanges(List.of());

    JsonNode textNode = CatalogJson.requireObject(text, "cost.text");
    validateKeys(textNode, TEXT_KEYS, "cost.text");
    JsonNode input = textNode.get("input");
    List<Override> overrides = new ArrayList<>();
    if (input == null || input.isNull()) {
      base.setInput(BigDecimal.ZERO);
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
    } else {
      parseInput(CatalogJson.requireObject(input, "cost.text.input"), base, overrides, issues);
    }
    JsonNode output = textNode.get("output");
    if (output == null || output.isNull()) {
      base.setOutput(BigDecimal.ZERO);
      base.setReasoning(null);
    } else {
      parseOutput(CatalogJson.requireObject(output, "cost.text.output"), base, overrides);
    }
    addInputRanges(overrides, base, rules);
    List<SourceMultiplier> multipliers = parseMultipliers(cost.get("multipliers"), issues);
    compileDefinitions(pricing, base, rules, multipliers);
    return pricing;
  }

  private static void parseInput(
      JsonNode input, ModelRateSetDTO base, List<Override> overrides, List<String> issues) {
    validateKeys(input, INPUT_KEYS, "cost.text.input");
    validateUnit(input.get("unit"), "cost.text.input.unit");
    parseOverrides(input.get("overrides"), overrides, true);
    base.setInput(zeroIfNull(CatalogJson.decimal(input.get("price"), "cost.text.input.price")));
    JsonNode cache = input.get("cache");
    if (cache == null || cache.isNull()) {
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    JsonNode cacheNode = CatalogJson.requireObject(cache, "cost.text.input.cache");
    validateKeys(cacheNode, CACHE_KEYS, "cost.text.input.cache");
    if (cacheNode.has("explicit")) {
      issues.add("explicit cache pricing is not included");
    }
    if (cacheNode.has("storage")) {
      issues.add("cache storage cost is not included");
    }
    base.setCacheRead(
        zeroIfNull(CatalogJson.decimal(cacheNode.get("read"), "cost.text.input.cache.read")));
    setCacheWrite(base, cacheNode.get("write"));
  }

  private static void parseOutput(JsonNode output, ModelRateSetDTO base, List<Override> overrides) {
    validateKeys(output, OUTPUT_KEYS, "cost.text.output");
    validateUnit(output.get("unit"), "cost.text.output.unit");
    parseOverrides(output.get("overrides"), overrides, false);
    base.setOutput(zeroIfNull(CatalogJson.decimal(output.get("price"), "cost.text.output.price")));
    base.setReasoning(CatalogJson.decimal(output.get("reasoning"), "cost.text.output.reasoning"));
  }

  private static void parseOverrides(JsonNode source, List<Override> target, boolean input) {
    if (source == null || source.isNull()) {
      return;
    }
    if (!source.isArray()) {
      throw new CatalogPriceException("text price overrides must be an array");
    }
    for (JsonNode override : source) {
      JsonNode item = CatalogJson.requireObject(override, "text price override");
      validateKeys(item, Set.of("when", "price"), "text price override");
      Bound bound = contextBound(item.get("when"));
      BigDecimal price = CatalogJson.decimal(item.get("price"), "text price override.price");
      if (price == null) {
        throw unsupportedRules("price override without a price");
      }
      target.add(new Override(bound.min(), bound.max(), input, price));
      if (target.size() > MAX_SOURCE_RULES) {
        throw unsupportedRules("pricing rule budget exceeded");
      }
    }
  }

  private static Bound contextBound(JsonNode whenNode) {
    JsonNode when = CatalogJson.requireObject(whenNode, "price override.when");
    validateKeys(when, Set.of("context"), "price override.when");
    JsonNode context = CatalogJson.requireObject(when.get("context"), "condition.context");
    validateKeys(context, Set.of("gt", "gte", "lt", "lte"), "condition.context");
    Integer gt = CatalogJson.nonNegativeInt(context.get("gt"), "condition.context.gt");
    Integer gte = CatalogJson.nonNegativeInt(context.get("gte"), "condition.context.gte");
    Integer lt = CatalogJson.nonNegativeInt(context.get("lt"), "condition.context.lt");
    Integer lte = CatalogJson.nonNegativeInt(context.get("lte"), "condition.context.lte");
    if (context.isEmpty()) {
      throw unsupportedRules("context override has no input-token boundary");
    }
    int min = Math.max(gt == null ? 0 : increment(gt), gte == null ? 0 : gte);
    Integer max = minValue(lt, lte == null ? null : increment(lte));
    if (max != null && max <= min) {
      throw unsupportedRules("empty or contradictory input context range");
    }
    return new Bound(min, max);
  }

  private static int increment(int value) {
    if (value == Integer.MAX_VALUE) {
      throw unsupportedRules("input context boundary is too large");
    }
    return value + 1;
  }

  private static Integer minValue(Integer left, Integer right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return Math.min(left, right);
  }

  /** Partitions all integer context boundaries, then replays patches in source order. */
  private static void addInputRanges(
      List<Override> overrides, ModelRateSetDTO base, List<ModelPricingRuleDTO> rules) {
    if (overrides.isEmpty()) {
      return;
    }
    TreeSet<Integer> boundaries = new TreeSet<>();
    boundaries.add(0);
    for (Override override : overrides) {
      boundaries.add(override.min());
      if (override.max() != null) {
        boundaries.add(override.max());
      }
    }
    List<Integer> starts = new ArrayList<>(boundaries);
    for (int i = 0; i < starts.size(); i++) {
      int start = starts.get(i);
      Integer end = i + 1 < starts.size() ? starts.get(i + 1) : null;
      BigDecimal input = base.getInput();
      BigDecimal output = base.getOutput();
      for (Override override : overrides) {
        if (start >= override.min() && (override.max() == null || start < override.max())) {
          if (override.input()) {
            input = override.price();
          } else {
            output = override.price();
          }
        }
      }
      boolean changesInput = input.compareTo(base.getInput()) != 0;
      boolean changesOutput = output.compareTo(base.getOutput()) != 0;
      if (!changesInput && !changesOutput) {
        continue;
      }
      ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
      rule.setType("input_token_range");
      rule.setMinInputTokens(start);
      rule.setMaxInputTokensExclusive(end);
      if (changesInput) {
        rule.setInput(input);
      }
      if (changesOutput) {
        rule.setOutput(output);
      }
      rules.add(rule);
    }
  }

  private static List<SourceMultiplier> parseMultipliers(JsonNode source, List<String> issues) {
    if (source == null || source.isNull()) {
      return List.of();
    }
    if (!source.isArray()) {
      throw unsupportedRules("multipliers must be an array");
    }
    List<SourceMultiplier> result = new ArrayList<>();
    for (JsonNode multiplier : source) {
      JsonNode item = CatalogJson.requireObject(multiplier, "cost.multipliers[]");
      validateKeys(item, Set.of("when", "factor"), "cost multiplier");
      JsonNode when = CatalogJson.requireObject(item.get("when"), "cost multiplier.when");
      if (containsRegion(when)) {
        validateRegionCondition(when);
        validateFactor(item.get("factor"));
        issues.add("ignored region multiplier");
        continue;
      }
      validateKeys(
          when, Set.of("date", "service_tier", "utc_hours", "utc_days"), "cost multiplier.when");
      DateRange date = when.has("date") ? dateRange(when.get("date")) : null;
      List<String> tiers =
          when.has("service_tier") ? serviceTiers(when.get("service_tier")) : List.of();
      if ((when.has("utc_hours") && when.get("utc_hours").isNull())
          || (when.has("utc_days") && when.get("utc_days").isNull())) {
        throw unsupportedRules("UTC time condition must not be null");
      }
      TimeWindow time =
          when.has("utc_hours") || when.has("utc_days")
              ? timeWindow(when.get("utc_hours"), when.get("utc_days"))
              : null;
      if (!tiers.isEmpty() && time != null) {
        throw unsupportedRules("combined service-tier and UTC-time multiplier condition");
      }
      Factor factor = factors(item.get("factor"), issues);
      if (factor != null) {
        result.add(new SourceMultiplier(date, tiers, time, factor));
      }
      if (result.size() > MAX_SOURCE_RULES) {
        throw unsupportedRules("pricing rule budget exceeded");
      }
    }
    return List.copyOf(result);
  }

  private static List<String> serviceTiers(JsonNode node) {
    JsonNode serviceTier = CatalogJson.requireObject(node, "condition.service_tier");
    validateKeys(serviceTier, Set.of("equals", "in"), "condition.service_tier");
    if (serviceTier.has("equals") && serviceTier.has("in")) {
      throw unsupportedRules("service-tier condition cannot mix equals and in");
    }
    JsonNode values = serviceTier.get("in");
    if (values == null) {
      values = serviceTier.get("equals");
    }
    return stringValues(values, "condition.service_tier");
  }

  private static List<String> stringValues(JsonNode values, String path) {
    List<String> result = new ArrayList<>();
    if (values != null && values.isString()) {
      result.add(requiredText(values, path));
    } else if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        String text = requiredText(value, path + ".in value");
        if (result.contains(text)) {
          throw unsupportedRules("duplicate service tier value");
        }
        result.add(text);
      }
    } else {
      throw unsupportedRules(path + " requires equals or an in array");
    }
    if (result.isEmpty()) {
      throw unsupportedRules(path + " must not be empty");
    }
    return List.copyOf(result);
  }

  private static DateRange dateRange(JsonNode node) {
    JsonNode date = CatalogJson.requireObject(node, "condition.date");
    validateKeys(date, Set.of("gte", "lt"), "condition.date");
    LocalDate from = localDate(date.get("gte"), "condition.date.gte");
    LocalDate until = localDate(date.get("lt"), "condition.date.lt");
    if (from == null && until == null) {
      throw unsupportedRules("date condition requires gte or lt");
    }
    if (from != null && until != null && !from.isBefore(until)) {
      throw unsupportedRules("date condition gte must be before lt");
    }
    return new DateRange(from, until);
  }

  private static LocalDate localDate(JsonNode node, String path) {
    if (node == null || node.isNull()) {
      return null;
    }
    String value = requiredText(node, path);
    if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
      throw unsupportedRules(path + " must be YYYY-MM-DD");
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException error) {
      throw unsupportedRules(path + " must be a valid date");
    }
  }

  private static void compileDefinitions(
      ModelPricingDTO pricing,
      ModelRateSetDTO base,
      List<ModelPricingRuleDTO> inputRules,
      List<SourceMultiplier> multipliers) {
    TreeSet<LocalDate> boundaries = new TreeSet<>();
    for (SourceMultiplier multiplier : multipliers) {
      if (multiplier.date() != null) {
        if (multiplier.date().from() != null) {
          boundaries.add(multiplier.date().from());
        }
        if (multiplier.date().until() != null) {
          boundaries.add(multiplier.date().until());
        }
      }
    }
    if (boundaries.size() > MAX_SOURCE_RULES) {
      throw unsupportedRules("date change budget exceeded");
    }
    ModelPricingDefinition initial = definition(base, inputRules, multipliers, LocalDate.MIN);
    pricing.setBase(initial.base());
    pricing.setRules(initial.rules());
    List<ModelPricingChangeDTO> changes = new ArrayList<>();
    for (LocalDate from : boundaries) {
      ModelPricingDefinition definition = definition(base, inputRules, multipliers, from);
      ModelPricingChangeDTO change = new ModelPricingChangeDTO();
      change.setFrom(from.toString());
      change.setBase(definition.base());
      change.setRules(definition.rules());
      changes.add(change);
    }
    pricing.setChanges(changes);
  }

  private static ModelPricingDefinition definition(
      ModelRateSetDTO base,
      List<ModelPricingRuleDTO> inputRules,
      List<SourceMultiplier> multipliers,
      LocalDate date) {
    List<Factor> rateFactors = new ArrayList<>();
    Map<String, Factor> tierFactors = new LinkedHashMap<>();
    List<SourceMultiplier> timeMultipliers = new ArrayList<>();
    List<ModelPricingRuleDTO> rules = new ArrayList<>();
    for (ModelPricingRuleDTO inputRule : inputRules) {
      rules.add(copyRange(inputRule));
    }
    for (SourceMultiplier multiplier : multipliers) {
      if (!active(multiplier.date(), date)) {
        continue;
      }
      if (multiplier.tiers().isEmpty() && multiplier.time() == null) {
        rateFactors.add(multiplier.factor());
      } else if (!multiplier.tiers().isEmpty()) {
        for (String tier : multiplier.tiers()) {
          tierFactors.merge(tier, multiplier.factor(), ModelsDevPricingParser::multiply);
        }
      } else {
        timeMultipliers.add(multiplier);
      }
    }
    for (Map.Entry<String, Factor> entry : tierFactors.entrySet()) {
      if (isIdentity(entry.getValue())) {
        continue;
      }
      ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
      rule.setType("service_tier_multiplier");
      rule.setServiceTier(entry.getKey());
      setFactors(rule, entry.getValue());
      rules.add(rule);
    }
    addDisjointTimeRules(timeMultipliers, rules);
    if (rules.size() > MAX_SOURCE_RULES) {
      throw unsupportedRules("pricing rule budget exceeded");
    }
    for (ModelPricingRuleDTO rule : rules) {
      if ("input_token_range".equals(rule.getType())) {
        if (rule.getInput() != null) {
          rule.setInput(scale(rule.getInput(), rateFactors, 0));
        }
        if (rule.getOutput() != null) {
          rule.setOutput(scale(rule.getOutput(), rateFactors, 1));
        }
      }
    }
    return new ModelPricingDefinition(scale(base, rateFactors), rules);
  }

  /**
   * Splits overlapping time multipliers by UTC weekday and minute, then multiplies each active set.
   */
  private static void addDisjointTimeRules(
      List<SourceMultiplier> multipliers, List<ModelPricingRuleDTO> rules) {
    if (multipliers.isEmpty()) {
      return;
    }
    TreeSet<Integer> boundaries = new TreeSet<>();
    boundaries.add(0);
    boundaries.add(1440);
    for (SourceMultiplier multiplier : multipliers) {
      boundaries.add(minutes(multiplier.time().start()));
      boundaries.add(minutes(multiplier.time().end()));
    }
    List<Integer> points = new ArrayList<>(boundaries);
    for (int weekday = 1; weekday <= 7; weekday++) {
      TimeSegment pending = null;
      for (int index = 0; index + 1 < points.size(); index++) {
        int start = points.get(index);
        int end = points.get(index + 1);
        List<Factor> factors = new ArrayList<>();
        for (SourceMultiplier multiplier : multipliers) {
          TimeWindow window = multiplier.time();
          if ((window.weekdays().isEmpty() || window.weekdays().contains(weekday))
              && start >= minutes(window.start())
              && start < minutes(window.end())) {
            factors.add(multiplier.factor());
          }
        }
        Factor combined = combine(factors);
        if (isIdentity(combined)) {
          if (pending != null) {
            addTimeRule(pending, weekday, rules);
            pending = null;
          }
          continue;
        }
        TimeSegment current = new TimeSegment(start, end, combined);
        if (pending != null
            && pending.end() == current.start()
            && equivalent(pending.factor(), current.factor())) {
          pending = new TimeSegment(pending.start(), current.end(), pending.factor());
        } else {
          if (pending != null) {
            addTimeRule(pending, weekday, rules);
          }
          pending = current;
        }
      }
      if (pending != null) {
        addTimeRule(pending, weekday, rules);
      }
    }
  }

  private static void addTimeRule(
      TimeSegment segment, int weekday, List<ModelPricingRuleDTO> rules) {
    ModelPricingRuleDTO rule = new ModelPricingRuleDTO();
    rule.setType("time_multiplier");
    rule.setStart(timeText(segment.start()));
    rule.setEnd(timeText(segment.end()));
    rule.setTimezone("UTC");
    rule.setWeekdays(List.of(weekday));
    setFactors(rule, segment.factor());
    rules.add(rule);
  }

  private static Factor combine(List<Factor> factors) {
    Factor combined = new Factor(BigDecimal.ONE, null, null, null, null, null);
    for (Factor factor : factors) {
      combined = multiply(combined, factor);
    }
    return combined;
  }

  private static Factor multiply(Factor left, Factor right) {
    BigDecimal common = left.factor().multiply(right.factor());
    return new Factor(
        common,
        overrideFactor(left.forField(0).multiply(right.forField(0)), common),
        overrideFactor(left.forField(1).multiply(right.forField(1)), common),
        overrideFactor(left.forField(2).multiply(right.forField(2)), common),
        overrideFactor(left.forField(3).multiply(right.forField(3)), common),
        overrideFactor(left.forField(4).multiply(right.forField(4)), common));
  }

  private static BigDecimal overrideFactor(BigDecimal value, BigDecimal common) {
    return value.compareTo(common) == 0 ? null : value;
  }

  private static boolean isIdentity(Factor factor) {
    for (int field = 0; field < 5; field++) {
      if (factor.forField(field).compareTo(BigDecimal.ONE) != 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean equivalent(Factor left, Factor right) {
    for (int field = 0; field < 5; field++) {
      if (left.forField(field).compareTo(right.forField(field)) != 0) {
        return false;
      }
    }
    return true;
  }

  private static int minutes(String time) {
    if ("24:00".equals(time)) {
      return 1440;
    }
    return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3));
  }

  private static String timeText(int minute) {
    if (minute == 1440) {
      return "24:00";
    }
    return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }

  private static boolean active(DateRange range, LocalDate date) {
    return range == null
        || (range.from() == null || !date.isBefore(range.from()))
            && (range.until() == null || date.isBefore(range.until()));
  }

  private static ModelPricingRuleDTO copyRange(ModelPricingRuleDTO source) {
    ModelPricingRuleDTO copy = new ModelPricingRuleDTO();
    copy.setType(source.getType());
    copy.setMinInputTokens(source.getMinInputTokens());
    copy.setMaxInputTokensExclusive(source.getMaxInputTokensExclusive());
    copy.setInput(source.getInput());
    copy.setOutput(source.getOutput());
    return copy;
  }

  private static ModelRateSetDTO scale(ModelRateSetDTO source, List<Factor> factors) {
    ModelRateSetDTO result = new ModelRateSetDTO();
    result.setInput(scale(source.getInput(), factors, 0));
    result.setOutput(scale(source.getOutput(), factors, 1));
    result.setCacheRead(scale(source.getCacheRead(), factors, 2));
    result.setCacheWrite(
        source.getCacheWrite() == null ? null : scale(source.getCacheWrite(), factors, 3));
    Map<String, BigDecimal> ttlRates = new LinkedHashMap<>();
    if (source.getCacheWriteByTtl() != null) {
      source
          .getCacheWriteByTtl()
          .forEach((ttl, rate) -> ttlRates.put(ttl, scale(rate, factors, 3)));
    }
    result.setCacheWriteByTtl(ttlRates);
    result.setReasoning(
        source.getReasoning() == null ? null : scale(source.getReasoning(), factors, 4));
    return result;
  }

  private static BigDecimal scale(BigDecimal value, List<Factor> factors, int field) {
    BigDecimal factor = BigDecimal.ONE;
    for (Factor sourceFactor : factors) {
      factor = factor.multiply(sourceFactor.forField(field));
    }
    return value.multiply(factor);
  }

  private static void setFactors(ModelPricingRuleDTO rule, Factor factors) {
    rule.setFactor(factors.factor());
    rule.setInputFactor(factors.input());
    rule.setOutputFactor(factors.output());
    rule.setCacheReadFactor(factors.cacheRead());
    rule.setCacheWriteFactor(factors.cacheWrite());
    rule.setReasoningFactor(factors.reasoning());
  }

  private static TimeWindow timeWindow(JsonNode hours, JsonNode days) {
    String start = "00:00";
    String end = "24:00";
    if (hours != null && !hours.isNull()) {
      JsonNode range = CatalogJson.requireObject(hours, "condition.utc_hours");
      validateKeys(range, Set.of("gte", "lt"), "condition.utc_hours");
      if (range.isEmpty()) {
        throw unsupportedRules("empty UTC hour condition");
      }
      start = timeText(range.get("gte"), "00:00", false);
      end = timeText(range.get("lt"), "24:00", true);
    }
    if (start.compareTo(end) >= 0) {
      throw unsupportedRules("UTC time condition crosses midnight or is empty");
    }
    List<Integer> weekdays = List.of();
    if (days != null && !days.isNull()) {
      JsonNode dayFilter = CatalogJson.requireObject(days, "condition.utc_days");
      validateKeys(dayFilter, Set.of("in", "equals"), "condition.utc_days");
      if (dayFilter.has("equals") && dayFilter.has("in")) {
        throw unsupportedRules("UTC weekday condition cannot mix equals and in");
      }
      JsonNode values = dayFilter.get("in");
      if (values == null) {
        values = dayFilter.get("equals");
      }
      List<String> names = new ArrayList<>();
      if (values != null && values.isArray()) {
        for (JsonNode value : values) {
          names.add(requiredText(value, "condition.utc_days value"));
        }
      } else if (values != null && values.isString()) {
        names.add(values.stringValue());
      } else {
        throw unsupportedRules("UTC weekday condition must be equals or an array");
      }
      List<Integer> parsed = new ArrayList<>();
      for (String name : names) {
        int day =
            switch (name.toLowerCase(Locale.ROOT)) {
              case "mon", "monday" -> 1;
              case "tue", "tuesday" -> 2;
              case "wed", "wednesday" -> 3;
              case "thu", "thursday" -> 4;
              case "fri", "friday" -> 5;
              case "sat", "saturday" -> 6;
              case "sun", "sunday" -> 7;
              default -> throw unsupportedRules("unknown UTC weekday");
            };
        if (parsed.contains(day)) {
          throw unsupportedRules("duplicate UTC weekday");
        }
        parsed.add(day);
      }
      weekdays = List.copyOf(parsed);
    }
    return new TimeWindow(start, end, weekdays);
  }

  private static String timeText(JsonNode node, String fallback, boolean end) {
    if (node == null || node.isNull()) {
      return fallback;
    }
    if (!node.isString()) {
      throw unsupportedRules("UTC time boundary must be HH:mm");
    }
    String value = node.stringValue();
    if (end && "24:00".equals(value)) {
      return value;
    }
    if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
      throw unsupportedRules("UTC time boundary must be HH:mm");
    }
    return value;
  }

  private static Factor factors(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      throw unsupportedRules("multiplier has no factor");
    }
    if (node.isNumber()) {
      BigDecimal value = CatalogJson.decimal(node, "cost multiplier.factor");
      return new Factor(value, null, null, null, null, null);
    }
    JsonNode factor = CatalogJson.requireObject(node, "cost multiplier.factor");
    for (String key : factor.propertyNames()) {
      if (!Set.of("text", "audio", "image", "video", "web_search").contains(key)) {
        throw new CatalogPriceException("unsupported multiplier factor: " + key);
      }
    }
    for (String key : Set.of("audio", "image", "video", "web_search")) {
      if (factor.has(key) && !factor.get(key).isNull()) {
        issues.add("non-token multiplier is not included: " + key);
      }
    }
    JsonNode text = factor.get("text");
    if (text == null || text.isNull()) {
      return null;
    }
    if (text.isNumber()) {
      return new Factor(CatalogJson.decimal(text, "factor.text"), null, null, null, null, null);
    }
    JsonNode textFactors = CatalogJson.requireObject(text, "factor.text");
    validateKeys(textFactors, Set.of("input", "output"), "factor.text");
    BigDecimal input = null;
    BigDecimal output = null;
    BigDecimal cacheRead = null;
    BigDecimal cacheWrite = null;
    BigDecimal reasoning = null;
    JsonNode inputNode = textFactors.get("input");
    if (inputNode != null && !inputNode.isNull()) {
      if (inputNode.isNumber()) {
        input = CatalogJson.decimal(inputNode, "factor.text.input");
      } else {
        JsonNode inputObject = CatalogJson.requireObject(inputNode, "factor.text.input");
        validateKeys(inputObject, Set.of("price", "cache"), "factor.text.input");
        input = CatalogJson.decimal(inputObject.get("price"), "factor.text.input.price");
        JsonNode cache = inputObject.get("cache");
        if (cache != null && !cache.isNull()) {
          JsonNode cacheObject = CatalogJson.requireObject(cache, "factor.text.input.cache");
          validateKeys(cacheObject, Set.of("read", "write"), "factor.text.input.cache");
          cacheRead = CatalogJson.decimal(cacheObject.get("read"), "factor.text.input.cache.read");
          cacheWrite =
              CatalogJson.decimal(cacheObject.get("write"), "factor.text.input.cache.write");
        }
      }
    }
    JsonNode outputNode = textFactors.get("output");
    if (outputNode != null && !outputNode.isNull()) {
      if (outputNode.isNumber()) {
        output = CatalogJson.decimal(outputNode, "factor.text.output");
      } else {
        JsonNode outputObject = CatalogJson.requireObject(outputNode, "factor.text.output");
        validateKeys(outputObject, Set.of("price", "reasoning"), "factor.text.output");
        output = CatalogJson.decimal(outputObject.get("price"), "factor.text.output.price");
        reasoning =
            CatalogJson.decimal(outputObject.get("reasoning"), "factor.text.output.reasoning");
      }
    }
    return new Factor(BigDecimal.ONE, input, output, cacheRead, cacheWrite, reasoning);
  }

  private static boolean containsRegion(JsonNode node) {
    if (node.has("region")) {
      return true;
    }
    for (String key : List.of("all", "any")) {
      JsonNode children = node.get(key);
      if (children != null && children.isArray()) {
        for (JsonNode child : children) {
          if (child.isObject() && containsRegion(child)) {
            return true;
          }
        }
      }
    }
    JsonNode not = node.get("not");
    return not != null && not.isObject() && containsRegion(not);
  }

  private static void validateRegionCondition(JsonNode when) {
    // Region pricing is deliberately ignored, but its external structure must not hide unknown
    // syntax.
    for (String key : when.propertyNames()) {
      switch (key) {
        case "region" -> {
          JsonNode region = CatalogJson.requireObject(when.get(key), "condition.region");
          validateKeys(region, Set.of("equals", "in"), "condition.region");
          JsonNode values = region.get("in");
          if (values == null) {
            values = region.get("equals");
          }
          stringValues(values, "condition.region");
        }
        case "all", "any" -> {
          JsonNode children = when.get(key);
          if (!children.isArray()) {
            throw unsupportedRules("invalid compound region condition");
          }
          for (JsonNode child : children) {
            validateRegionCondition(CatalogJson.requireObject(child, "region condition"));
          }
        }
        case "not" -> validateRegionCondition(
            CatalogJson.requireObject(when.get(key), "region condition.not"));
        case "service_tier" -> serviceTiers(when.get(key));
        case "context" -> {
          JsonNode context = CatalogJson.requireObject(when.get(key), "condition.context");
          validateKeys(context, Set.of("gt", "gte", "lt", "lte"), "condition.context");
          // Also validate conflicting/empty numeric ranges.
          contextBound(CatalogJson.parse("{\"context\":" + context.toString() + "}"));
        }
        case "date" -> dateRange(when.get(key));
        case "utc_hours", "utc_days" -> {
          if ((when.has("utc_hours") && when.get("utc_hours").isNull())
              || (when.has("utc_days") && when.get("utc_days").isNull())) {
            throw unsupportedRules("UTC time condition must not be null");
          }
          timeWindow(when.get("utc_hours"), when.get("utc_days"));
        }
        default -> throw new CatalogPriceException("unsupported pricing condition: " + key);
      }
    }
  }

  private static void validateFactor(JsonNode node) {
    factors(node, new ArrayList<>());
  }

  private static void setCacheWrite(ModelRateSetDTO base, JsonNode write) {
    if (write == null || write.isNull()) {
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    if (!write.isObject()) {
      base.setCacheWrite(zeroIfNull(CatalogJson.decimal(write, "cost.text.input.cache.write")));
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    Map<String, BigDecimal> byTtl = new LinkedHashMap<>();
    for (String key : write.propertyNames()) {
      String ttl = Long.toString(ttlSeconds(key));
      BigDecimal price = CatalogJson.decimal(write.get(key), "cost.text.input.cache.write." + key);
      if (price == null || byTtl.put(ttl, price) != null) {
        throw new CatalogPriceException("cache write contains invalid or duplicate TTL price");
      }
    }
    if (byTtl.isEmpty()) {
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
    } else {
      base.setCacheWrite(null);
      base.setCacheWriteByTtl(byTtl);
    }
  }

  private static long ttlSeconds(String key) {
    if (key == null || key.length() < 2) {
      throw new CatalogPriceException("cache write has invalid TTL key");
    }
    char unit = key.charAt(key.length() - 1);
    long amount;
    try {
      amount = Long.parseLong(key.substring(0, key.length() - 1));
    } catch (NumberFormatException error) {
      throw new CatalogPriceException("cache write has invalid TTL key");
    }
    long multiplier =
        switch (unit) {
          case 's' -> 1;
          case 'm' -> 60;
          case 'h' -> 3600;
          case 'd' -> 86400;
          default -> throw new CatalogPriceException("cache write has invalid TTL unit");
        };
    try {
      long ttl = Math.multiplyExact(amount, multiplier);
      if (ttl <= 0) {
        throw new CatalogPriceException("cache write TTL must be positive");
      }
      return ttl;
    } catch (ArithmeticException error) {
      throw new CatalogPriceException("cache write TTL is too large");
    }
  }

  private static void validateUnit(JsonNode unit, String path) {
    if (unit != null
        && !unit.isNull()
        && (!unit.isString() || !TOKEN_UNITS.contains(unit.stringValue()))) {
      throw new CatalogPriceException("unsupported v2 price unit at " + path);
    }
  }

  private static void validateKeys(JsonNode node, Set<String> allowed, String path) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new CatalogPriceException("unsupported v2 field at " + path + ": " + key);
      }
    }
  }

  private static String requiredText(JsonNode node, String name) {
    if (node == null || !node.isString() || node.stringValue().isBlank()) {
      throw new CatalogPriceException(name + " must be a non-empty string");
    }
    return node.stringValue();
  }

  private static CatalogPriceException noPrice() {
    return new CatalogPriceException("model has no token price; no price was imported");
  }

  private static CatalogPriceException unsupportedRules(String reason) {
    return new CatalogPriceException(
        "token pricing rules cannot be represented exactly: "
            + reason
            + "; complete price not imported");
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private record Override(int min, Integer max, boolean input, BigDecimal price) {}

  private record Bound(int min, Integer max) {}

  private record TimeWindow(String start, String end, List<Integer> weekdays) {}

  private record TimeSegment(int start, int end, Factor factor) {}

  private record DateRange(LocalDate from, LocalDate until) {}

  private record SourceMultiplier(
      DateRange date, List<String> tiers, TimeWindow time, Factor factor) {}

  private record ModelPricingDefinition(ModelRateSetDTO base, List<ModelPricingRuleDTO> rules) {}

  private record Factor(
      BigDecimal factor,
      BigDecimal input,
      BigDecimal output,
      BigDecimal cacheRead,
      BigDecimal cacheWrite,
      BigDecimal reasoning) {

    BigDecimal forField(int field) {
      return switch (field) {
        case 0 -> input == null ? factor : input;
        case 1 -> output == null ? factor : output;
        case 2 -> cacheRead == null ? factor : cacheRead;
        case 3 -> cacheWrite == null ? factor : cacheWrite;
        case 4 -> reasoning == null ? factor : reasoning;
        default -> throw new IllegalArgumentException("unknown price field");
      };
    }
  }
}
