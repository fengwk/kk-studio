package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.TimeBounds;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.ensureCondition;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.invalid;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseOptionalDate;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTimeBounds;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTtl;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireNonBlank;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireOptionalNonNegative;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.requireOptionalNonNegativeInt;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.toRequestBody;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingContextConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDateConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingFactorsDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingMultiplierDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingOverrideDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRequestBodyConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingTimeConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRatePatchDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计价定义的归一键之后的结构与数值校验。
 *
 * <p>检查顺序与错误文案是对外契约：base → overrides（计数、逐条 id/when/rates）→ multipliers（计数、逐条 id/when/factors），条件内部按
 * context → serviceTier → date → time → requestBody → all → any → not。首个失败立即抛出，不再继续后续检查。
 */
final class ModelPricingValidator {

  private static final String PATH = "config.pricing";

  private ModelPricingValidator() {}

  static void validate(ModelPricingDTO pricing) {
    validateRateSet(pricing.getBase(), PATH + ".base");
    validateOverrides(pricing.getOverrides(), PATH + ".overrides");
    validateMultipliers(pricing.getMultipliers(), PATH + ".multipliers");
  }

  private static void validateOverrides(List<ModelPricingOverrideDTO> overrides, String path) {
    if (overrides == null) {
      return;
    }
    requireRuleCount(overrides.size(), path);
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < overrides.size(); index++) {
      ModelPricingOverrideDTO override = overrides.get(index);
      if (override == null) {
        throw invalid(path + "[" + index + "] must be an object");
      }
      String rulePath = path + "[" + index + "]";
      requireRuleId(override.getId(), ids, rulePath + ".id");
      validateCondition(override.getWhen(), rulePath + ".when", 0);
      validateRatePatch(override.getRates(), rulePath + ".rates");
    }
  }

  private static void validateMultipliers(
      List<ModelPricingMultiplierDTO> multipliers, String path) {
    if (multipliers == null) {
      return;
    }
    requireRuleCount(multipliers.size(), path);
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < multipliers.size(); index++) {
      ModelPricingMultiplierDTO multiplier = multipliers.get(index);
      if (multiplier == null) {
        throw invalid(path + "[" + index + "] must be an object");
      }
      String rulePath = path + "[" + index + "]";
      requireRuleId(multiplier.getId(), ids, rulePath + ".id");
      validateCondition(multiplier.getWhen(), rulePath + ".when", 0);
      validateFactors(multiplier.getFactors(), rulePath + ".factors");
    }
  }

  private static void validateRateSet(ModelRateSetDTO rates, String path) {
    if (rates == null) {
      return;
    }
    requireOptionalNonNegative(rates.getInput(), path + ".input");
    requireOptionalNonNegative(rates.getOutput(), path + ".output");
    requireOptionalNonNegative(rates.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rates.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(rates.getReasoning(), path + ".reasoning");
    validateTtlRates(rates.getCacheWriteByTtl(), path + ".cacheWriteByTtl");
  }

  private static void validateRatePatch(ModelRatePatchDTO rates, String path) {
    if (rates == null) {
      throw invalid(path + " must be an object");
    }
    requireOptionalNonNegative(rates.getInput(), path + ".input");
    requireOptionalNonNegative(rates.getOutput(), path + ".output");
    requireOptionalNonNegative(rates.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rates.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(rates.getReasoning(), path + ".reasoning");
    validateTtlRates(rates.getCacheWriteByTtl(), path + ".cacheWriteByTtl");
  }

  private static void validateFactors(ModelPricingFactorsDTO factors, String path) {
    if (factors == null) {
      throw invalid(path + " must be an object");
    }
    requireOptionalNonNegative(factors.getInput(), path + ".input");
    requireOptionalNonNegative(factors.getOutput(), path + ".output");
    requireOptionalNonNegative(factors.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(factors.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(factors.getReasoning(), path + ".reasoning");
  }

  private static void validateTtlRates(Map<String, BigDecimal> rates, String path) {
    if (rates == null) {
      return;
    }
    if (rates.size() > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " entries");
    }
    Set<Long> ttls = new HashSet<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), path);
      if (!ttls.add(ttl)) {
        throw invalid(path + " contains duplicate TTL: " + ttl);
      }
      requireOptionalNonNegative(entry.getValue(), path + "." + ttl);
    }
  }

  private static void requireRuleCount(int size, String path) {
    if (size > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " rules");
    }
  }

  private static void requireRuleId(String id, Set<String> ids, String path) {
    requireNonBlank(id, path);
    if (!ids.add(id)) {
      throw invalid(PATH + " rule ids must be unique: " + id);
    }
  }

  /** 递归校验嵌套条件；深度上限与领域构造函数一致，超出时先于任何子节点检查失败。 */
  private static void validateCondition(
      ModelPricingConditionDTO condition, String path, int depth) {
    if (condition == null) {
      throw invalid(path + " must be an object");
    }
    if (depth > ModelPricingCondition.MAX_DEPTH) {
      throw invalid(path + " nesting must not exceed " + ModelPricingCondition.MAX_DEPTH);
    }
    validateContext(condition.getContext(), path + ".context");
    validateServiceTier(condition.getServiceTier(), path + ".serviceTier");
    validateDate(condition.getDate(), path + ".date");
    validateTime(condition.getTime(), path + ".time");
    validateRequestBody(condition.getRequestBody(), path + ".requestBody");
    validateConditionList(condition.getAll(), path + ".all", depth);
    validateConditionList(condition.getAny(), path + ".any", depth);
    if (condition.getNot() != null) {
      validateCondition(condition.getNot(), path + ".not", depth + 1);
    }
  }

  private static void validateContext(ModelPricingContextConditionDTO context, String path) {
    if (context == null) {
      return;
    }
    requireOptionalNonNegativeInt(context.getGt(), path + ".gt");
    requireOptionalNonNegativeInt(context.getGte(), path + ".gte");
    requireOptionalNonNegativeInt(context.getLt(), path + ".lt");
    requireOptionalNonNegativeInt(context.getLte(), path + ".lte");
    ensureCondition(
        () ->
            new ModelPricingCondition.Context(
                context.getGt(), context.getGte(), context.getLt(), context.getLte()),
        path);
  }

  private static void validateServiceTier(List<String> serviceTier, String path) {
    if (serviceTier == null) {
      return;
    }
    if (serviceTier.size() > ModelPricingCondition.MAX_LIST_SIZE) {
      throw invalid(path + " must not exceed " + ModelPricingCondition.MAX_LIST_SIZE + " values");
    }
    for (int index = 0; index < serviceTier.size(); index++) {
      requireNonBlank(serviceTier.get(index), path + "[" + index + "]");
    }
    Set<String> uniqueTiers = new HashSet<>(serviceTier);
    if (uniqueTiers.size() != serviceTier.size()) {
      throw invalid(path + " contains duplicate value");
    }
  }

  private static void validateDate(ModelPricingDateConditionDTO date, String path) {
    if (date == null) {
      return;
    }
    LocalDate gte = parseOptionalDate(date.getGte(), path + ".gte");
    LocalDate lt = parseOptionalDate(date.getLt(), path + ".lt");
    ensureCondition(() -> new ModelPricingCondition.DateRange(gte, lt), path);
  }

  private static void validateTime(ModelPricingTimeConditionDTO time, String path) {
    if (time == null) {
      return;
    }
    TimeBounds bounds = parseTimeBounds(time.getStart(), time.getEnd(), path);
    requireNonBlank(time.getTimezone(), path + ".timezone");
    ZoneId timezone;
    try {
      timezone = ZoneId.of(time.getTimezone());
    } catch (DateTimeException error) {
      throw invalid(path + ".timezone must be an IANA time zone");
    }
    List<Integer> weekdays = time.getWeekdays();
    if (weekdays != null && weekdays.size() > 7) {
      throw invalid(path + ".weekdays must not exceed 7 values");
    }
    ensureCondition(
        () ->
            new ModelPricingCondition.TimeWindow(bounds.start(), bounds.end(), timezone, weekdays),
        path);
  }

  private static void validateRequestBody(
      ModelPricingRequestBodyConditionDTO requestBody, String path) {
    if (requestBody == null) {
      return;
    }
    if (requestBody.getPath() == null) {
      throw invalid(path + ".path must be a JSON Pointer string");
    }
    if (requestBody.getEquals() == null) {
      throw invalid(path + ".equals is required; write null explicitly to match JSON null");
    }
    // 构造 requestBody 即完成 path/equals 的全部边界校验。
    toRequestBody(requestBody.getPath(), requestBody.getEquals(), path);
  }

  private static void validateConditionList(
      List<ModelPricingConditionDTO> conditions, String path, int depth) {
    if (conditions == null) {
      return;
    }
    if (conditions.size() > ModelPricingCondition.MAX_LIST_SIZE) {
      throw invalid(
          path + " must not exceed " + ModelPricingCondition.MAX_LIST_SIZE + " conditions");
    }
    for (int index = 0; index < conditions.size(); index++) {
      validateCondition(conditions.get(index), path + "[" + index + "]", depth + 1);
    }
  }
}
