package fun.fengwk.kkstudio.platform.catalog.model;

import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.TimeBounds;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.invalid;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseOptionalDate;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTimeBounds;
import static fun.fengwk.kkstudio.platform.catalog.model.ModelPricingWireValues.parseTtl;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已归一化并校验过的计价 DTO 到运行时领域模型的转换。
 *
 * <p>领域构造函数仍会做自己的边界检查；转换把它们统一的 {@link IllegalArgumentException} 收敛成一条带 {@code config.pricing}
 * 前缀的错误，调用方只会看到单一失败原因。
 */
final class ModelPricingMapper {

  private static final String PATH = "config.pricing";

  private ModelPricingMapper() {}

  static ModelPricing toModelPricing(ModelPricingDTO pricing) {
    try {
      List<ModelPricing.Override> overrides = new ArrayList<>();
      if (pricing.getOverrides() != null) {
        for (ModelPricingOverrideDTO override : pricing.getOverrides()) {
          overrides.add(
              new ModelPricing.Override(
                  override.getId(),
                  toCondition(override.getWhen()),
                  toRatePatch(override.getRates())));
        }
      }
      List<ModelPricing.Multiplier> multipliers = new ArrayList<>();
      if (pricing.getMultipliers() != null) {
        for (ModelPricingMultiplierDTO multiplier : pricing.getMultipliers()) {
          multipliers.add(
              new ModelPricing.Multiplier(
                  multiplier.getId(),
                  toCondition(multiplier.getWhen()),
                  toFactors(multiplier.getFactors())));
        }
      }
      return new ModelPricing(toRateSet(pricing.getBase()), overrides, multipliers);
    } catch (IllegalArgumentException error) {
      throw invalid(PATH + " is invalid: " + error.getMessage(), error);
    }
  }

  private static ModelPricing.RateSet toRateSet(ModelRateSetDTO rates) {
    if (rates == null) {
      return ModelPricing.RateSet.ZERO;
    }
    return new ModelPricing.RateSet(
        zeroIfNull(rates.getInput()),
        zeroIfNull(rates.getOutput()),
        zeroIfNull(rates.getCacheRead()),
        rates.getCacheWrite(),
        toTtlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning());
  }

  private static ModelPricing.RatePatch toRatePatch(ModelRatePatchDTO rates) {
    return new ModelPricing.RatePatch(
        rates.getInput(),
        rates.getOutput(),
        rates.getCacheRead(),
        rates.getCacheWrite(),
        rates.getCacheWriteByTtl() == null ? null : toTtlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning(),
        rates.isReasoningSpecified());
  }

  private static ModelPricing.Factors toFactors(ModelPricingFactorsDTO factors) {
    return new ModelPricing.Factors(
        factors.getInput(),
        factors.getOutput(),
        factors.getCacheRead(),
        factors.getCacheWrite(),
        factors.getReasoning());
  }

  private static Map<Long, BigDecimal> toTtlRates(Map<String, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> result = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), PATH + ".cacheWriteByTtl");
      if (result.put(ttl, entry.getValue()) != null) {
        throw new IllegalArgumentException("cacheWriteByTtl contains duplicate TTL: " + ttl);
      }
    }
    return Map.copyOf(result);
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static ModelPricingCondition toCondition(ModelPricingConditionDTO condition) {
    return new ModelPricingCondition(
        toContext(condition.getContext()),
        condition.getServiceTier(),
        toDate(condition.getDate()),
        toTime(condition.getTime()),
        toRequestBodyOrNull(condition.getRequestBody()),
        toConditions(condition.getAll()),
        toConditions(condition.getAny()),
        condition.getNot() == null ? null : toCondition(condition.getNot()));
  }

  private static ModelPricingCondition.Context toContext(ModelPricingContextConditionDTO context) {
    if (context == null) {
      return null;
    }
    return new ModelPricingCondition.Context(
        context.getGt(), context.getGte(), context.getLt(), context.getLte());
  }

  private static ModelPricingCondition.DateRange toDate(ModelPricingDateConditionDTO date) {
    if (date == null) {
      return null;
    }
    return new ModelPricingCondition.DateRange(
        parseOptionalDate(date.getGte(), PATH + ".condition.date.gte"),
        parseOptionalDate(date.getLt(), PATH + ".condition.date.lt"));
  }

  private static ModelPricingCondition.TimeWindow toTime(ModelPricingTimeConditionDTO time) {
    if (time == null) {
      return null;
    }
    TimeBounds bounds = parseTimeBounds(time.getStart(), time.getEnd(), PATH + ".condition.time");
    return new ModelPricingCondition.TimeWindow(
        bounds.start(), bounds.end(), ZoneId.of(time.getTimezone()), time.getWeekdays());
  }

  private static ModelPricingCondition.RequestBody toRequestBodyOrNull(
      ModelPricingRequestBodyConditionDTO requestBody) {
    if (requestBody == null) {
      return null;
    }
    return toRequestBody(
        requestBody.getPath(), requestBody.getEquals(), PATH + ".condition.requestBody");
  }

  private static List<ModelPricingCondition> toConditions(
      List<ModelPricingConditionDTO> conditions) {
    if (conditions == null) {
      return null;
    }
    if (conditions.isEmpty()) {
      return List.of();
    }
    List<ModelPricingCondition> result = new ArrayList<>(conditions.size());
    for (ModelPricingConditionDTO condition : conditions) {
      result.add(toCondition(condition));
    }
    return result;
  }
}
