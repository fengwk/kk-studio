package fun.fengwk.kkstudio.platform.catalog.model;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelPricingResolver;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingPreviewDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingPreviewRequestDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRuleDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Calculates previews with the same resolver used for authoritative usage costing. */
@Service
public class ModelPricingPreviewService {

  public ModelPricingPreviewDTO preview(ModelPricingPreviewRequestDTO request) {
    if (request == null) {
      throw new IllegalArgumentException("pricing preview request is required");
    }
    if (request.getInputTokens() < 0) {
      throw new IllegalArgumentException("inputTokens must not be negative");
    }
    ModelPricing pricing = ModelPricingCodec.parsePricing(request.getPricing());
    ModelPricingResolver.PricingPreview preview =
        ModelPricingResolver.preview(
            pricing, request.getInputTokens(), request.getRequestedAt(), request.getServiceTier());
    ModelPricingPreviewDTO result = new ModelPricingPreviewDTO();
    result.setRates(toRates(preview.rates()));
    result.setActiveFrom(preview.activeFrom() == null ? null : preview.activeFrom().toString());
    result.setMatchedRules(preview.matchedRules().stream().map(this::toRule).toList());
    return result;
  }

  private static ModelRateSetDTO toRates(ModelPricingResolver.ResolvedRates rates) {
    if (rates == null) {
      return null;
    }
    ModelRateSetDTO dto = new ModelRateSetDTO();
    dto.setInput(rates.input());
    dto.setOutput(rates.output());
    dto.setCacheRead(rates.cacheRead());
    dto.setCacheWrite(rates.cacheWrite());
    Map<String, BigDecimal> ttlRates = new LinkedHashMap<>();
    rates.cacheWriteByTtl().forEach((ttl, rate) -> ttlRates.put(Long.toString(ttl), rate));
    dto.setCacheWriteByTtl(ttlRates);
    dto.setReasoning(rates.independentReasoning() ? rates.reasoning() : null);
    return dto;
  }

  private ModelPricingRuleDTO toRule(ModelPricing.Rule rule) {
    ModelPricingRuleDTO dto = new ModelPricingRuleDTO();
    if (rule instanceof ModelPricing.InputTokenRange range) {
      dto.setType("input_token_range");
      if (range.minInputTokens() > 0) {
        dto.setMinInputTokens(range.minInputTokens());
      }
      dto.setMaxInputTokensExclusive(range.maxInputTokensExclusive());
      dto.setInput(range.input());
      dto.setOutput(range.output());
      dto.setCacheRead(range.cacheRead());
      dto.setCacheWrite(range.cacheWrite());
      if (range.cacheWriteByTtlSpecified()) {
        Map<String, BigDecimal> ttlRates = new LinkedHashMap<>();
        range.cacheWriteByTtl().forEach((ttl, rate) -> ttlRates.put(Long.toString(ttl), rate));
        dto.setCacheWriteByTtl(ttlRates);
      }
      if (range.reasoningSpecified()) {
        dto.setReasoning(range.reasoning());
      }
      return dto;
    }
    if (rule instanceof ModelPricing.TimeMultiplier time) {
      dto.setType("time_multiplier");
      dto.setStart(formatMinute(time.startMinuteOfDay()));
      dto.setEnd(formatMinute(time.endMinuteOfDay()));
      dto.setTimezone(time.timezone().getId());
      dto.setWeekdays(time.weekdays().isEmpty() ? null : time.weekdays());
      setFactors(dto, time.factors());
      return dto;
    }
    ModelPricing.ServiceTierMultiplier tier = (ModelPricing.ServiceTierMultiplier) rule;
    dto.setType("service_tier_multiplier");
    dto.setServiceTier(tier.serviceTier());
    setFactors(dto, tier.factors());
    return dto;
  }

  private static void setFactors(ModelPricingRuleDTO dto, ModelPricing.Factors factors) {
    dto.setFactor(factors.factor());
    dto.setInputFactor(factors.inputFactor());
    dto.setOutputFactor(factors.outputFactor());
    dto.setCacheReadFactor(factors.cacheReadFactor());
    dto.setCacheWriteFactor(factors.cacheWriteFactor());
    dto.setReasoningFactor(factors.reasoningFactor());
  }

  private static String formatMinute(int minute) {
    return minute == 1440
        ? "24:00"
        : String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }
}
