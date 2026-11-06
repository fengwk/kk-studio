package fun.fengwk.kkstudio.platform.catalog.model;

import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CRUD 测试共享的完整模型配置（不完整的模型现已被拒绝）。 */
public final class ModelTestData {

  public static ModelConfigDTO executableConfig() {
    return buildConfig(32768, 4096, true, false, "test", "test-v1", List.of("default"), "default");
  }

  public static ModelConfigDTO buildConfig(
      int context,
      int output,
      boolean tools,
      boolean reasoning,
      String pricingTier,
      String pricingVersion,
      List<String> variantIds,
      String defaultVariant) {
    ModelConfigDTO config = new ModelConfigDTO();
    ModelLimitDTO limit = new ModelLimitDTO();
    limit.setContext(context);
    limit.setOutput(output);
    config.setLimit(limit);

    ModelAbilitiesDTO abilities = new ModelAbilitiesDTO();
    abilities.setTools(tools);
    abilities.setReasoning(reasoning);
    abilities.setInputModalities(List.of(ModelInputModality.TEXT));
    config.setAbilities(abilities);

    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    base.setInput(BigDecimal.ZERO);
    base.setOutput(BigDecimal.ZERO);
    base.setCacheRead(BigDecimal.ZERO);
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(BigDecimal.ZERO);
    pricing.setBase(base);
    pricing.setOverrides(List.of());
    pricing.setMultipliers(List.of());
    config.setPricing(pricing);

    List<ModelVariantDTO> variants = new ArrayList<>();
    for (String id : variantIds) {
      ModelVariantDTO variant = new ModelVariantDTO();
      variant.setId(id);
      variant.setPromptCacheRetention(PromptCacheRetention.NONE);
      variants.add(variant);
    }
    config.setVariants(variants);
    config.setDefaultVariant(defaultVariant);
    return config;
  }

  public static ModelConfigDTO fromJson(String json) {
    try {
      return JsonMapper.builder().build().readValue(json, ModelConfigDTO.class);
    } catch (Exception error) {
      throw new IllegalArgumentException("invalid config", error);
    }
  }

  /** 应用规范化的可执行配置，使 CRUD/web 测试能使用有效载荷；modelId 与逻辑 name 独立。 */
  public static void executable(SingleModelCreateDTO properties) {
    properties.setConfig(executableConfig());
    if (properties.getModelId() == null || properties.getModelId().isBlank()) {
      properties.setModelId("wire-model");
    }
  }

  public static void executable(SingleModelUpdateDTO properties) {
    properties.setConfig(executableConfig());
    if (properties.getModelId() == null || properties.getModelId().isBlank()) {
      properties.setModelId("wire-model");
    }
  }

  private ModelTestData() {}
}
