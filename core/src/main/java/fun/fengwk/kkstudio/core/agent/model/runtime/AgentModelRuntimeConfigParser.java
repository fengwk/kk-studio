package fun.fengwk.kkstudio.core.agent.model.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
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
  private final ObjectReader configReader;

  public AgentModelRuntimeConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectMapper strictMapper = objectMapper.copy();
    strictMapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    strictMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    strictMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    strictMapper
        .coercionConfigFor(LogicalType.Integer)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    strictMapper
        .coercionConfigFor(LogicalType.Float)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
    strictMapper
        .coercionConfigFor(LogicalType.Boolean)
        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
    strictMapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    this.configReader = strictMapper.readerFor(AgentModelConfigDTO.class);
  }

  /**
   * Decode persisted JSON into the typed config DTO.
   *
   * @throws IllegalArgumentException when JSON is null/blank/malformed or the typed DTO fails
   *     validation
   */
  public AgentModelConfigDTO decode(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw invalid("config must not be blank");
    }
    try {
      AgentModelConfigDTO config = configReader.readValue(configJson);
      validate(config);
      return config;
    } catch (JsonMappingException error) {
      throw invalid(mappingPath(error) + " is invalid: " + error.getOriginalMessage(), error);
    } catch (JsonProcessingException error) {
      throw invalid("config must be valid JSON object", error);
    }
  }

  /** Encode a typed DTO into canonical JSON after revalidation. */
  public String encode(AgentModelConfigDTO config) {
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode agent model config", error);
    }
  }

  /** Build the runtime descriptor view from persisted JSON. */
  public ParsedAgentModelConfig parse(String configJson) {
    return toParsedConfig(decode(configJson));
  }

  /** Build the runtime descriptor view from a typed config DTO. */
  public ParsedAgentModelConfig parse(AgentModelConfigDTO config) {
    validate(config);
    return toParsedConfig(config);
  }

  private ParsedAgentModelConfig toParsedConfig(AgentModelConfigDTO config) {
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
    if (config == null) {
      throw invalid("config is required");
    }
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
      if (!variant.getId().equals(variant.getId().trim())) {
        throw invalid(path + ".id must not have surrounding whitespace");
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
      if (temperature != null) {
        requireFinite(temperature, path + ".temperature");
        if (temperature < 0) {
          throw invalid(path + ".temperature must not be negative");
        }
      }
      Double topP = variant.getTopP();
      if (topP != null) {
        requireFinite(topP, path + ".topP");
        if (topP <= 0 || topP > 1) {
          throw invalid(path + ".topP must be in (0, 1]");
        }
      }
      Integer topK = variant.getTopK();
      if (topK != null && topK <= 0) {
        throw invalid(path + ".topK must be positive");
      }
      Double frequencyPenalty = variant.getFrequencyPenalty();
      if (frequencyPenalty != null) {
        requireFinite(frequencyPenalty, path + ".frequencyPenalty");
      }
      Double presencePenalty = variant.getPresencePenalty();
      if (presencePenalty != null) {
        requireFinite(presencePenalty, path + ".presencePenalty");
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
    if (!defaultVariant.equals(defaultVariant.trim())) {
      throw invalid("config.defaultVariant must not have surrounding whitespace");
    }
    if (!ids.contains(defaultVariant)) {
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

  private static void requireFinite(Double value, String path) {
    if (!Double.isFinite(value)) {
      throw invalid(path + " must be finite");
    }
  }

  private static String mappingPath(JsonMappingException error) {
    StringBuilder path = new StringBuilder("config");
    for (JsonMappingException.Reference reference : error.getPath()) {
      if (reference.getFieldName() != null) {
        path.append('.').append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.toString();
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
