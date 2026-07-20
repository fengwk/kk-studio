package fun.fengwk.kkstudio.core.agent.model.service.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelInputModality;
import fun.fengwk.kkstudio.share.model.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.model.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.model.AgentModelVariantDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Converts global model resources to public DTOs. */
@Component
public class AgentModelConverter {

  private final ObjectMapper objectMapper;

  public AgentModelConverter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AgentModelDTO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDTO dto = new AgentModelDTO();
    dto.setId(Long.toString(model.getId()));
    dto.setProviderId(Long.toString(model.getProviderId()));
    dto.setName(model.getName());
    dto.setDescription(model.getDescription());
    dto.setConfig(parseConfig(model.getConfigJson()));
    dto.setVersion(model.getVersion());
    dto.setCreateTime(model.getCreateTime());
    dto.setUpdateTime(model.getUpdateTime());
    return dto;
  }

  private AgentModelConfigDTO parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return null;
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(configJson);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("invalid persisted config_json", error);
    }
    if (root == null || root.isNull()) {
      return null;
    }
    if (!root.isObject()) {
      throw new IllegalStateException("config_json must be an object");
    }
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    config.setLimit(limit(root.get("limit")));
    config.setAbilities(abilities(root.get("abilities")));
    config.setPricing(pricing(root.get("pricing")));
    config.setDefaultVariant(textOrNull(root.get("defaultVariant")));
    config.setVariants(variants(root.get("variants")));
    return config;
  }

  private AgentModelLimitDTO limit(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(intOrNull(node.get("context")));
    limit.setOutput(intOrNull(node.get("output")));
    return limit;
  }

  private AgentModelAbilitiesDTO abilities(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(booleanOrNull(node.get("tools")));
    abilities.setReasoning(booleanOrNull(node.get("reasoning")));
    abilities.setInputModalities(inputModalities(node.get("inputModalities")));
    return abilities;
  }

  private List<AgentModelInputModality> inputModalities(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<AgentModelInputModality> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (item == null || !item.isTextual()) {
        continue;
      }
      try {
        result.add(AgentModelInputModality.valueOf(item.textValue()));
      } catch (IllegalArgumentException ignored) {
        // skip unknown enum to avoid breaking read paths
      }
    }
    return List.copyOf(result);
  }

  private AgentModelPricingDTO pricing(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency(textOrNull(node.get("currency")));
    pricing.setPricingTier(textOrNull(node.get("pricingTier")));
    pricing.setServiceTier(textOrNull(node.get("serviceTier")));
    pricing.setServiceTierMultiplier(decimalOrNull(node.get("serviceTierMultiplier")));
    pricing.setVersion(textOrNull(node.get("version")));
    pricing.setInputPerMillionTokens(decimalOrNull(node.get("inputPerMillionTokens")));
    pricing.setOutputPerMillionTokens(decimalOrNull(node.get("outputPerMillionTokens")));
    pricing.setCacheReadPerMillionTokens(decimalOrNull(node.get("cacheReadPerMillionTokens")));
    pricing.setCacheWritePerMillionTokens(decimalOrNull(node.get("cacheWritePerMillionTokens")));
    pricing.setCacheWriteLongPerMillionTokens(
        decimalOrNull(node.get("cacheWriteLongPerMillionTokens")));
    pricing.setReasoningPerMillionTokens(decimalOrNull(node.get("reasoningPerMillionTokens")));
    return pricing;
  }

  private List<AgentModelVariantDTO> variants(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<AgentModelVariantDTO> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (item == null || !item.isObject()) {
        continue;
      }
      AgentModelVariantDTO variant = new AgentModelVariantDTO();
      variant.setId(textOrNull(item.get("id")));
      variant.setReasoningEffort(textOrNull(item.get("reasoningEffort")));
      variant.setMaxOutputTokens(intOrNull(item.get("maxOutputTokens")));
      variant.setTemperature(doubleOrNull(item.get("temperature")));
      variant.setTopP(doubleOrNull(item.get("topP")));
      variant.setTopK(intOrNull(item.get("topK")));
      variant.setFrequencyPenalty(doubleOrNull(item.get("frequencyPenalty")));
      variant.setPresencePenalty(doubleOrNull(item.get("presencePenalty")));
      variant.setStopSequences(stringsOrEmpty(item.get("stopSequences")));
      result.add(variant);
    }
    return List.copyOf(result);
  }

  private List<String> stringsOrEmpty(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : node) {
      if (item == null || !item.isTextual()) {
        continue;
      }
      String value = item.textValue();
      if (value != null && !value.isBlank()) {
        result.add(value);
      }
    }
    return List.copyOf(result);
  }

  private Long longOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    return node.longValue();
  }

  private Integer intOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    if (!node.canConvertToInt()) {
      throw new IllegalStateException("value out of int range");
    }
    return node.intValue();
  }

  private Double doubleOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    return node.doubleValue();
  }

  private Boolean booleanOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    return null;
  }

  private BigDecimal decimalOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    try {
      return node.decimalValue();
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) {
      return null;
    }
    String value = node.textValue();
    return value == null || value.isBlank() ? null : value;
  }
}
