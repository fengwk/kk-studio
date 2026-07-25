package fun.fengwk.kkstudio.core.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.share.model.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelInputModality;
import fun.fengwk.kkstudio.share.model.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.model.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.model.AgentModelVariantDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared complete model config used by CRUD tests now that incomplete models are rejected. */
public final class AgentModelTestData {

  public static AgentModelConfigDTO executableConfig() {
    return buildConfig(32768, 4096, true, false, "test", "test-v1", List.of("default"), "default");
  }

  public static AgentModelConfigDTO buildConfig(
      int context,
      int output,
      boolean tools,
      boolean reasoning,
      String pricingTier,
      String pricingVersion,
      List<String> variantIds,
      String defaultVariant) {
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(context);
    limit.setOutput(output);
    config.setLimit(limit);

    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(tools);
    abilities.setReasoning(reasoning);
    abilities.setInputModalities(List.of(AgentModelInputModality.TEXT));
    config.setAbilities(abilities);

    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency("USD");
    pricing.setPricingTier(pricingTier);
    pricing.setServiceTier("default");
    pricing.setServiceTierMultiplier(BigDecimal.ONE);
    pricing.setVersion(pricingVersion);
    pricing.setInputPerMillionTokens(BigDecimal.ZERO);
    pricing.setOutputPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheReadPerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWritePerMillionTokens(BigDecimal.ZERO);
    pricing.setCacheWriteLongPerMillionTokens(BigDecimal.ZERO);
    pricing.setReasoningPerMillionTokens(BigDecimal.ZERO);
    config.setPricing(pricing);

    List<AgentModelVariantDTO> variants = new ArrayList<>();
    for (String id : variantIds) {
      AgentModelVariantDTO variant = new AgentModelVariantDTO();
      variant.setId(id);
      variants.add(variant);
    }
    config.setVariants(variants);
    config.setDefaultVariant(defaultVariant);
    return config;
  }

  public static AgentModelConfigDTO fromJson(String json) {
    try {
      return new ObjectMapper().readValue(json, AgentModelConfigDTO.class);
    } catch (Exception error) {
      throw new IllegalArgumentException("invalid config", error);
    }
  }

  /** Apply the canonical executable config so CRUD/web tests can exercise a valid body. */
  public static void executable(AgentModelEditablePropertiesDTO properties) {
    properties.setConfig(executableConfig());
  }

  private AgentModelTestData() {}
}
