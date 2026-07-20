package fun.fengwk.kkstudio.core.agent.model.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.share.model.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentModelInputModality;
import fun.fengwk.kkstudio.share.model.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.model.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.model.AgentModelVariantDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Single typed codec/parser for Agent model configurations.
 *
 * <p>This class owns every read/write against the persisted {@code config_json} column. Mutations
 * and reads must go through {@link #decode(String)} / {@link #encode(AgentModelConfigDTO)}; any
 * other path that performs its own ObjectMapper mapping will drift out of sync. Validation runs
 * against the typed DTOs; persisted JSON is treated as an opaque carrier.
 *
 * <p>Field paths in validation messages use the public {@code config.*} terminology. Internal
 * storage references may continue to call the column {@code config_json}; that naming choice is not
 * surfaced to clients.
 */
@Component
public final class AgentModelRuntimeConfigParser {

  private final ObjectMapper objectMapper;

  public AgentModelRuntimeConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * Decode persisted JSON into the typed config DTO.
   *
   * @throws IllegalArgumentException when JSON is null/blank/malformed or the typed DTO fails
   *     validation
   */
  public AgentModelConfigDTO decode(String configJson) {
    JsonNode root = parseJson(configJson);
    AgentModelConfigDTO config = new AgentModelConfigDTO();
    config.setLimit(limit(root.get("limit")));
    config.setAbilities(abilities(root.get("abilities")));
    config.setPricing(pricing(root.get("pricing")));
    config.setDefaultVariant(requiredText(root, "defaultVariant", "config"));
    config.setVariants(variants(root.get("variants")));
    validate(config);
    return config;
  }

  /** Encode a typed DTO into canonical JSON after revalidation. */
  public String encode(AgentModelConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode agent model config", error);
    }
  }

  /** Build the runtime descriptor view from persisted JSON. */
  public ParsedAgentModelConfig parse(String configJson) {
    return parse(decode(configJson));
  }

  /** Build the runtime descriptor view from a typed config DTO. */
  public ParsedAgentModelConfig parse(AgentModelConfigDTO config) {
    Objects.requireNonNull(config, "config");
    validate(config);
    AgentModelLimitDTO limit = config.getLimit();
    AgentModelAbilitiesDTO abilities = config.getAbilities();
    AgentModelPricingDTO pricing = config.getPricing();
    List<ModelVariant> variants = new ArrayList<>();
    for (AgentModelVariantDTO variant : config.getVariants()) {
      variants.add(toModelVariant(variant));
    }
    return new ParsedAgentModelConfig(
        limit.getContext(),
        limit.getOutput(),
        toRuntimeModalities(abilities.getInputModalities()),
        abilities.getTools(),
        abilities.getReasoning(),
        List.copyOf(variants),
        config.getDefaultVariant(),
        toModelPricing(pricing));
  }

  private void validate(AgentModelConfigDTO config) {
    AgentModelLimitDTO limit = config.getLimit();
    if (limit == null) {
      throw invalid("config.limit is required");
    }
    if (limit.getContext() == null || limit.getContext() <= 0) {
      throw invalid("config.limit.context must be a positive integer");
    }
    if (limit.getOutput() == null || limit.getOutput() <= 0) {
      throw invalid("config.limit.output must be a positive integer");
    }
    if (limit.getOutput() > limit.getContext()) {
      throw invalid("config.limit.output must not exceed limit.context");
    }

    AgentModelAbilitiesDTO abilities = config.getAbilities();
    if (abilities == null) {
      throw invalid("config.abilities is required");
    }
    if (abilities.getTools() == null) {
      throw invalid("config.abilities.tools is required");
    }
    if (abilities.getReasoning() == null) {
      throw invalid("config.abilities.reasoning is required");
    }
    if (abilities.getInputModalities() == null || abilities.getInputModalities().isEmpty()) {
      throw invalid("config.abilities.inputModalities must not be empty");
    }
    Set<AgentModelInputModality> uniqueModalities = new HashSet<>();
    List<AgentModelInputModality> rawModalities = abilities.getInputModalities();
    for (int index = 0; index < rawModalities.size(); index++) {
      AgentModelInputModality modality = rawModalities.get(index);
      if (modality == null) {
        throw invalid("config.abilities.inputModalities[" + index + "] must not be null");
      }
      if (!uniqueModalities.add(modality)) {
        throw invalid("config.abilities.inputModalities contains duplicate value: " + modality);
      }
    }

    List<AgentModelVariantDTO> variants = config.getVariants();
    if (variants == null || variants.isEmpty()) {
      throw invalid("config.variants must not be empty");
    }
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < variants.size(); index++) {
      AgentModelVariantDTO variant = variants.get(index);
      String path = "config.variants[" + index + "]";
      if (variant == null) {
        throw invalid(path + " must be an object");
      }
      if (variant.getId() == null || variant.getId().isBlank()) {
        throw invalid(path + ".id must be a non-blank string");
      }
      if (!ids.add(variant.getId())) {
        throw invalid("config.variants contains duplicate id: " + variant.getId());
      }
      Integer maxOutputTokens = variant.getMaxOutputTokens();
      if (maxOutputTokens != null && maxOutputTokens <= 0) {
        throw invalid(path + ".maxOutputTokens must be positive");
      }
      if (maxOutputTokens != null && maxOutputTokens > limit.getOutput()) {
        throw invalid(path + ".maxOutputTokens must not exceed model limit.output");
      }
      Double temperature = variant.getTemperature();
      if (temperature != null && temperature < 0) {
        throw invalid(path + ".temperature must not be negative");
      }
      Double topP = variant.getTopP();
      if (topP != null && (topP <= 0 || topP > 1)) {
        throw invalid(path + ".topP must be in (0, 1]");
      }
      Integer topK = variant.getTopK();
      if (topK != null && topK <= 0) {
        throw invalid(path + ".topK must be positive");
      }
      Double frequencyPenalty = variant.getFrequencyPenalty();
      if (frequencyPenalty != null && frequencyPenalty < 0) {
        throw invalid(path + ".frequencyPenalty must not be negative");
      }
      Double presencePenalty = variant.getPresencePenalty();
      if (presencePenalty != null && presencePenalty < 0) {
        throw invalid(path + ".presencePenalty must not be negative");
      }
      List<String> stopSequences = variant.getStopSequences();
      if (stopSequences != null) {
        for (int s = 0; s < stopSequences.size(); s++) {
          String value = stopSequences.get(s);
          if (value == null || value.isBlank()) {
            throw invalid(path + ".stopSequences[" + s + "] must be a non-blank string");
          }
        }
      }
    }
    String defaultVariant = config.getDefaultVariant();
    if (defaultVariant == null || defaultVariant.isBlank()) {
      throw invalid("config.defaultVariant must be a non-blank string");
    }
    if (ids.stream().noneMatch(id -> id.equals(defaultVariant))) {
      throw invalid("config.defaultVariant must match a variants[].id");
    }

    AgentModelPricingDTO pricing = config.getPricing();
    if (pricing == null) {
      throw invalid("config.pricing is required");
    }
    validatePricing(pricing);
  }

  private void validatePricing(AgentModelPricingDTO pricing) {
    String path = "config.pricing";
    requireNonBlank(pricing.getCurrency(), path + ".currency");
    requireNonBlank(pricing.getPricingTier(), path + ".pricingTier");
    requireNonBlank(pricing.getServiceTier(), path + ".serviceTier");
    requireNonBlank(pricing.getVersion(), path + ".version");
    requirePositiveBigDecimal(pricing.getServiceTierMultiplier(), path + ".serviceTierMultiplier");
    requireNonNegativeBigDecimal(
        pricing.getInputPerMillionTokens(), path + ".inputPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getOutputPerMillionTokens(), path + ".outputPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheReadPerMillionTokens(), path + ".cacheReadPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheWritePerMillionTokens(), path + ".cacheWritePerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheWriteLongPerMillionTokens(), path + ".cacheWriteLongPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getReasoningPerMillionTokens(), path + ".reasoningPerMillionTokens");
  }

  private static void requireNonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path + " must be a non-blank string");
    }
  }

  private static void requirePositiveBigDecimal(BigDecimal value, String path) {
    if (value == null) {
      throw invalid(path + " must be a number");
    }
    if (value.signum() <= 0) {
      throw invalid(path + " must be positive");
    }
  }

  private static void requireNonNegativeBigDecimal(BigDecimal value, String path) {
    if (value == null) {
      throw invalid(path + " must be a number");
    }
    if (value.signum() < 0) {
      throw invalid(path + " must not be negative");
    }
  }

  private AgentModelLimitDTO limit(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw invalid("config.limit must be an object");
    }
    AgentModelLimitDTO limit = new AgentModelLimitDTO();
    limit.setContext(requiredPositiveInt(node, "context", "config.limit"));
    limit.setOutput(requiredPositiveInt(node, "output", "config.limit"));
    return limit;
  }

  private AgentModelAbilitiesDTO abilities(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw invalid("config.abilities must be an object");
    }
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    abilities.setTools(requiredBoolean(node, "tools", "config.abilities"));
    abilities.setReasoning(requiredBoolean(node, "reasoning", "config.abilities"));
    abilities.setInputModalities(requiredInputModalities(node, "config.abilities"));
    return abilities;
  }

  private AgentModelPricingDTO pricing(JsonNode node) {
    if (node == null || !node.isObject()) {
      throw invalid("config.pricing must be an object");
    }
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    pricing.setCurrency(requiredText(node, "currency", "config.pricing"));
    pricing.setPricingTier(requiredText(node, "pricingTier", "config.pricing"));
    pricing.setServiceTier(requiredText(node, "serviceTier", "config.pricing"));
    pricing.setServiceTierMultiplier(
        requiredDecimal(node, "serviceTierMultiplier", "config.pricing"));
    pricing.setVersion(requiredText(node, "version", "config.pricing"));
    pricing.setInputPerMillionTokens(
        requiredDecimal(node, "inputPerMillionTokens", "config.pricing"));
    pricing.setOutputPerMillionTokens(
        requiredDecimal(node, "outputPerMillionTokens", "config.pricing"));
    pricing.setCacheReadPerMillionTokens(
        requiredDecimal(node, "cacheReadPerMillionTokens", "config.pricing"));
    pricing.setCacheWritePerMillionTokens(
        requiredDecimal(node, "cacheWritePerMillionTokens", "config.pricing"));
    pricing.setCacheWriteLongPerMillionTokens(
        requiredDecimal(node, "cacheWriteLongPerMillionTokens", "config.pricing"));
    pricing.setReasoningPerMillionTokens(
        requiredDecimal(node, "reasoningPerMillionTokens", "config.pricing"));
    return pricing;
  }

  private List<AgentModelVariantDTO> variants(JsonNode array) {
    if (array == null || !array.isArray()) {
      throw invalid("config.variants must be an array");
    }
    if (array.isEmpty()) {
      throw invalid("config.variants must not be empty");
    }
    List<AgentModelVariantDTO> result = new ArrayList<>();
    for (int index = 0; index < array.size(); index++) {
      JsonNode value = array.get(index);
      String path = "config.variants[" + index + "]";
      if (value == null || !value.isObject()) {
        throw invalid(path + " must be an object");
      }
      AgentModelVariantDTO variant = new AgentModelVariantDTO();
      variant.setId(requiredText(value, "id", path));
      Integer maxOutputTokens = optionalPositiveInt(value, "maxOutputTokens", path);
      if (maxOutputTokens != null) {
        variant.setMaxOutputTokens(maxOutputTokens);
      }
      Double temperature = optionalDouble(value, "temperature", path);
      if (temperature != null) {
        variant.setTemperature(temperature);
      }
      Double topP = optionalDouble(value, "topP", path);
      if (topP != null) {
        variant.setTopP(topP);
      }
      Integer topK = optionalPositiveInt(value, "topK", path);
      if (topK != null) {
        variant.setTopK(topK);
      }
      Double frequencyPenalty = optionalDouble(value, "frequencyPenalty", path);
      if (frequencyPenalty != null) {
        variant.setFrequencyPenalty(frequencyPenalty);
      }
      Double presencePenalty = optionalDouble(value, "presencePenalty", path);
      if (presencePenalty != null) {
        variant.setPresencePenalty(presencePenalty);
      }
      variant.setStopSequences(optionalStrings(value, "stopSequences", path));
      variant.setReasoningEffort(optionalText(value, "reasoningEffort", path));
      result.add(variant);
    }
    return result;
  }

  private List<AgentModelInputModality> requiredInputModalities(JsonNode parent, String path) {
    JsonNode node = parent.get("inputModalities");
    if (node == null || !node.isArray()) {
      throw invalid(path + ".inputModalities must be an array");
    }
    List<AgentModelInputModality> result = new ArrayList<>();
    for (int index = 0; index < node.size(); index++) {
      JsonNode item = node.get(index);
      if (item == null || !item.isTextual() || item.textValue().isBlank()) {
        throw invalid(path + ".inputModalities must contain non-blank strings");
      }
      try {
        result.add(AgentModelInputModality.valueOf(item.textValue()));
      } catch (IllegalArgumentException error) {
        throw invalid(
            path + ".inputModalities contains unsupported value: " + item.textValue(), error);
      }
    }
    if (result.isEmpty()) {
      throw invalid(path + ".inputModalities must not be empty");
    }
    return List.copyOf(result);
  }

  private JsonNode parseJson(String json) {
    if (json == null || json.isBlank()) {
      throw invalid("config must not be blank");
    }
    try {
      JsonNode node = objectMapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw invalid("config must be an object");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw invalid("config must be valid JSON", error);
    }
  }

  private static Integer requiredPositiveInt(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid(path + "." + field + " must be an integer");
    }
    int result = value.intValue();
    if (result <= 0) {
      throw invalid(path + "." + field + " must be positive");
    }
    return result;
  }

  private static boolean requiredBoolean(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalid(path + "." + field + " must be a boolean");
    }
    return value.booleanValue();
  }

  private static String requiredText(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw invalid(path + "." + field + " must be a non-blank string");
    }
    return value.textValue();
  }

  private static String optionalText(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw invalid(path + "." + field + " must be a string");
    }
    String text = value.textValue();
    return text == null || text.isBlank() ? null : text;
  }

  private static BigDecimal requiredDecimal(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isNumber()) {
      throw invalid(path + "." + field + " must be a number");
    }
    try {
      return value.decimalValue();
    } catch (NumberFormatException error) {
      throw invalid(path + "." + field + " must be a finite number", error);
    }
  }

  private static Integer optionalPositiveInt(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid(path + "." + field + " must be an integer");
    }
    int result = value.intValue();
    if (result <= 0) {
      throw invalid(path + "." + field + " must be positive");
    }
    return result;
  }

  private static Double optionalDouble(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw invalid(path + "." + field + " must be a number");
    }
    double result = value.doubleValue();
    if (!Double.isFinite(result)) {
      throw invalid(path + "." + field + " must be finite");
    }
    return result;
  }

  private static List<String> optionalStrings(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw invalid(path + "." + field + " must be an array");
    }
    List<String> result = new ArrayList<>();
    for (int index = 0; index < value.size(); index++) {
      JsonNode item = value.get(index);
      if (item == null || !item.isTextual() || item.textValue().isBlank()) {
        throw invalid(path + "." + field + " must contain non-blank strings");
      }
      result.add(item.textValue());
    }
    return List.copyOf(result);
  }

  private static Set<ModelInputModality> toRuntimeModalities(
      List<AgentModelInputModality> modalities) {
    Set<ModelInputModality> result = new HashSet<>();
    for (AgentModelInputModality modality : modalities) {
      result.add(ModelInputModality.valueOf(modality.name()));
    }
    return Set.copyOf(result);
  }

  private static ModelVariant toModelVariant(AgentModelVariantDTO variant) {
    try {
      return new ModelVariant(
          variant.getId(),
          variant.getMaxOutputTokens(),
          variant.getTemperature(),
          variant.getTopP(),
          variant.getTopK(),
          variant.getFrequencyPenalty(),
          variant.getPresencePenalty(),
          variant.getStopSequences(),
          variant.getReasoningEffort());
    } catch (IllegalArgumentException error) {
      throw invalid(
          "config.variants[" + variant.getId() + "] is invalid: " + error.getMessage(), error);
    }
  }

  private static ModelPricing toModelPricing(AgentModelPricingDTO pricing) {
    try {
      return new ModelPricing(
          pricing.getCurrency(),
          pricing.getPricingTier(),
          pricing.getServiceTier(),
          pricing.getServiceTierMultiplier(),
          pricing.getVersion(),
          pricing.getInputPerMillionTokens(),
          pricing.getOutputPerMillionTokens(),
          pricing.getCacheReadPerMillionTokens(),
          pricing.getCacheWritePerMillionTokens(),
          pricing.getCacheWriteLongPerMillionTokens(),
          pricing.getReasoningPerMillionTokens());
    } catch (IllegalArgumentException error) {
      throw invalid("config.pricing is invalid: " + error.getMessage(), error);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("invalid agent model: " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("invalid agent model: " + message, cause);
  }

  /** Verified runtime model configuration independent of Provider credentials and resource IDs. */
  public record ParsedAgentModelConfig(
      long contextWindow,
      long maxOutputTokens,
      Set<ModelInputModality> inputModalities,
      boolean tools,
      boolean reasoning,
      List<ModelVariant> variants,
      String defaultVariant,
      ModelPricing pricing) {

    public ParsedAgentModelConfig {
      inputModalities = Set.copyOf(inputModalities);
      variants = List.copyOf(variants);
      if (defaultVariant == null || defaultVariant.isBlank()) {
        throw new IllegalArgumentException("defaultVariant must not be blank");
      }
      pricing = Objects.requireNonNull(pricing, "pricing");
    }
  }
}
