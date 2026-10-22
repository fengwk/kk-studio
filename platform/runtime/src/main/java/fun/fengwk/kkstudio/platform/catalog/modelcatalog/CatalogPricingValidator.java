package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricingCondition;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingContextConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDateConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingFactorsDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingMultiplierDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingTimeConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用内部定价模型校验目录候选；只有能完整保存/求值的价格才标记为 supported。
 *
 * <p>校验只构建不可变 {@link ModelPricing}，覆盖 context 边界、日期/时间、规则数量/深度、decimal 精度与 TTL 键。 校验失败统一转成 {@link
 * CatalogPriceException}，由单个候选降级为不支持，不影响整份目录。
 */
final class CatalogPricingValidator {

  private CatalogPricingValidator() {}

  static void validate(AgentModelPricingDTO pricing) {
    try {
      toModelPricing(pricing);
    } catch (CatalogPriceException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new CatalogPriceException(
          error.getMessage() == null || error.getMessage().isBlank()
              ? "invalid pricing definition"
              : error.getMessage());
    }
  }

  private static ModelPricing toModelPricing(AgentModelPricingDTO pricing) {
    if (pricing == null) {
      throw new CatalogPriceException("pricing definition is required");
    }
    List<ModelPricing.Override> overrides = new ArrayList<>();
    if (pricing.getOverrides() != null) {
      for (AgentModelPricingOverrideDTO override : pricing.getOverrides()) {
        if (override == null) {
          throw new CatalogPriceException("pricing override is required");
        }
        overrides.add(
            new ModelPricing.Override(
                override.getId(), condition(override.getWhen()), ratePatch(override.getRates())));
      }
    }
    List<ModelPricing.Multiplier> multipliers = new ArrayList<>();
    if (pricing.getMultipliers() != null) {
      for (AgentModelPricingMultiplierDTO multiplier : pricing.getMultipliers()) {
        if (multiplier == null) {
          throw new CatalogPriceException("pricing multiplier is required");
        }
        multipliers.add(
            new ModelPricing.Multiplier(
                multiplier.getId(),
                condition(multiplier.getWhen()),
                factors(multiplier.getFactors())));
      }
    }
    return new ModelPricing(rateSet(pricing.getBase()), overrides, multipliers);
  }

  private static ModelPricing.RateSet rateSet(AgentModelRateSetDTO rates) {
    if (rates == null) {
      return ModelPricing.RateSet.ZERO;
    }
    return new ModelPricing.RateSet(
        zeroIfNull(rates.getInput()),
        zeroIfNull(rates.getOutput()),
        zeroIfNull(rates.getCacheRead()),
        zeroIfNull(rates.getCacheWrite()),
        ttlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning());
  }

  private static ModelPricing.RatePatch ratePatch(AgentModelRatePatchDTO rates) {
    if (rates == null) {
      throw new CatalogPriceException("pricing override rates are required");
    }
    return new ModelPricing.RatePatch(
        rates.getInput(),
        rates.getOutput(),
        rates.getCacheRead(),
        rates.getCacheWrite(),
        rates.getCacheWriteByTtl() == null ? null : ttlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning(),
        rates.isReasoningSpecified());
  }

  private static ModelPricing.Factors factors(AgentModelPricingFactorsDTO factors) {
    if (factors == null) {
      throw new CatalogPriceException("pricing multiplier factors are required");
    }
    return new ModelPricing.Factors(
        factors.getInput(),
        factors.getOutput(),
        factors.getCacheRead(),
        factors.getCacheWrite(),
        factors.getReasoning());
  }

  private static ModelPricingCondition condition(AgentModelPricingConditionDTO condition) {
    if (condition == null) {
      return ModelPricingCondition.ALWAYS;
    }
    ModelPricingCondition.Context context = null;
    AgentModelPricingContextConditionDTO contextDto = condition.getContext();
    if (contextDto != null) {
      context =
          new ModelPricingCondition.Context(
              contextDto.getGt(), contextDto.getGte(), contextDto.getLt(), contextDto.getLte());
    }
    ModelPricingCondition.DateRange date = null;
    AgentModelPricingDateConditionDTO dateDto = condition.getDate();
    if (dateDto != null) {
      date =
          new ModelPricingCondition.DateRange(
              parseDate(dateDto.getGte()), parseDate(dateDto.getLt()));
    }
    ModelPricingCondition.TimeWindow time = null;
    AgentModelPricingTimeConditionDTO timeDto = condition.getTime();
    if (timeDto != null) {
      time =
          new ModelPricingCondition.TimeWindow(
              parseTime(timeDto.getStart(), false),
              parseTime(timeDto.getEnd(), true),
              ZoneId.of(timeDto.getTimezone()),
              timeDto.getWeekdays());
    }
    return new ModelPricingCondition(
        context,
        condition.getServiceTier(),
        date,
        time,
        conditions(condition.getAll()),
        conditions(condition.getAny()),
        condition.getNot() == null ? null : condition(condition.getNot()));
  }

  private static List<ModelPricingCondition> conditions(
      List<AgentModelPricingConditionDTO> conditions) {
    if (conditions == null || conditions.isEmpty()) {
      return List.of();
    }
    List<ModelPricingCondition> result = new ArrayList<>(conditions.size());
    for (AgentModelPricingConditionDTO condition : conditions) {
      result.add(condition(condition));
    }
    return result;
  }

  private static Map<Long, BigDecimal> ttlRates(Map<String, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> result = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl;
      try {
        ttl = Long.parseLong(entry.getKey());
      } catch (NumberFormatException error) {
        throw new CatalogPriceException("cacheWriteByTtl keys must be positive integer seconds");
      }
      if (result.put(ttl, entry.getValue()) != null) {
        throw new CatalogPriceException("cacheWriteByTtl contains duplicate TTL: " + ttl);
      }
    }
    return result;
  }

  private static LocalDate parseDate(String value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException error) {
      throw new CatalogPriceException("pricing date must be YYYY-MM-DD");
    }
  }

  private static LocalTime parseTime(String value, boolean allowEndOfDay) {
    if (value == null) {
      throw new CatalogPriceException("pricing time must be HH:mm");
    }
    if ("24:00".equals(value)) {
      if (!allowEndOfDay) {
        throw new CatalogPriceException("pricing time start must be before 24:00");
      }
      return LocalTime.MIDNIGHT;
    }
    try {
      return LocalTime.parse(value);
    } catch (DateTimeException error) {
      throw new CatalogPriceException("pricing time must be HH:mm");
    }
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
