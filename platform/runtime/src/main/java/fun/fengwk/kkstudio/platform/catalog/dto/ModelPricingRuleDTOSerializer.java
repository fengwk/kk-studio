package fun.fengwk.kkstudio.platform.catalog.dto;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;
import java.util.Map;

/** 按规则类型序列化，并保留 TTL 表覆盖与显式 reasoning:null 的存在性。 */
public final class ModelPricingRuleDTOSerializer extends ValueSerializer<ModelPricingRuleDTO> {

  @Override
  public void serialize(
      ModelPricingRuleDTO rule, JsonGenerator generator, SerializationContext context) {
    generator.writeStartObject();
    generator.writeStringProperty("type", rule.getType());
    switch (rule.getType()) {
      case "input_token_range" -> writeRange(rule, generator);
      case "time_multiplier" -> writeTime(rule, generator);
      case "service_tier_multiplier" -> writeTier(rule, generator);
      default -> throw new IllegalArgumentException(
          "unsupported pricing rule type: " + rule.getType());
    }
    generator.writeEndObject();
  }

  private static void writeRange(ModelPricingRuleDTO rule, JsonGenerator generator) {
    if (rule.getMinInputTokens() != null) {
      generator.writeName("minInputTokens");
      generator.writeNumber(rule.getMinInputTokens());
    }
    if (rule.getMaxInputTokensExclusive() != null) {
      generator.writeName("maxInputTokensExclusive");
      generator.writeNumber(rule.getMaxInputTokensExclusive());
    }
    writeDecimal(generator, "input", rule.getInput());
    writeDecimal(generator, "output", rule.getOutput());
    writeDecimal(generator, "cacheRead", rule.getCacheRead());
    writeDecimal(generator, "cacheWrite", rule.getCacheWrite());
    if (rule.isCacheWriteByTtlSpecified()) {
      if (rule.getCacheWriteByTtl() == null) {
        throw new IllegalArgumentException("cacheWriteByTtl must be an object when specified");
      }
      generator.writeName("cacheWriteByTtl");
      generator.writeStartObject();
      for (Map.Entry<String, BigDecimal> entry : rule.getCacheWriteByTtl().entrySet()) {
        generator.writeStringProperty(entry.getKey(), entry.getValue().toPlainString());
      }
      generator.writeEndObject();
    }
    if (rule.isReasoningSpecified()) {
      generator.writeName("reasoning");
      if (rule.getReasoning() == null) {
        generator.writeNull();
      } else {
        generator.writeString(rule.getReasoning().toPlainString());
      }
    }
  }

  private static void writeTime(ModelPricingRuleDTO rule, JsonGenerator generator) {
    generator.writeStringProperty("start", rule.getStart());
    generator.writeStringProperty("end", rule.getEnd());
    generator.writeStringProperty("timezone", rule.getTimezone());
    if (rule.getWeekdays() != null) {
      generator.writeName("weekdays");
      generator.writeStartArray();
      for (Integer weekday : rule.getWeekdays()) {
        generator.writeNumber(weekday);
      }
      generator.writeEndArray();
    }
    writeFactors(rule, generator);
  }

  private static void writeTier(ModelPricingRuleDTO rule, JsonGenerator generator) {
    generator.writeStringProperty("serviceTier", rule.getServiceTier());
    writeFactors(rule, generator);
  }

  private static void writeFactors(ModelPricingRuleDTO rule, JsonGenerator generator) {
    writeDecimal(generator, "factor", rule.getFactor());
    writeDecimal(generator, "inputFactor", rule.getInputFactor());
    writeDecimal(generator, "outputFactor", rule.getOutputFactor());
    writeDecimal(generator, "cacheReadFactor", rule.getCacheReadFactor());
    writeDecimal(generator, "cacheWriteFactor", rule.getCacheWriteFactor());
    writeDecimal(generator, "reasoningFactor", rule.getReasoningFactor());
  }

  private static void writeDecimal(JsonGenerator generator, String name, BigDecimal value) {
    if (value != null) {
      generator.writeStringProperty(name, value.toPlainString());
    }
  }
}
