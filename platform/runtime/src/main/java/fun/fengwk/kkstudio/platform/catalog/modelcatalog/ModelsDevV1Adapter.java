package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingContextConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * models.dev v1 适配器：顶层 provider map，模型价格是扁平 {@code cost}，context tiers 与 legacy {@code
 * context_over_200k} 都归一化为 context 覆盖规则。
 */
@Component
public class ModelsDevV1Adapter implements ModelsDevCatalogAdapter {

  private static final Set<String> COST_KEYS =
      Set.of(
          "input",
          "output",
          "cache_read",
          "cache_write",
          "reasoning",
          "tiers",
          "context_over_200k",
          "input_audio",
          "output_audio");
  private static final Set<String> TIER_KEYS =
      Set.of("input", "output", "cache_read", "cache_write", "reasoning", "tier");
  private static final Set<String> TIER_META_KEYS = Set.of("type", "size");
  private static final Set<String> LEGACY_TIER_KEYS =
      Set.of("input", "output", "cache_read", "cache_write", "reasoning");

  @Override
  public ModelCatalogSource source() {
    return ModelCatalogSource.MODELS_DEV_V1;
  }

  @Override
  public List<ModelDefaultCandidateDTO> adapt(String json) {
    JsonNode root = CatalogJson.parse(json);
    List<ModelDefaultCandidateDTO> candidates = new ArrayList<>();
    for (Map.Entry<String, JsonNode> providerEntry : root.properties()) {
      String providerId = providerEntry.getKey();
      JsonNode provider = providerEntry.getValue();
      if (provider == null || !provider.isObject()) {
        continue;
      }
      String providerName = CatalogJson.text(provider.get("id"), providerId);
      JsonNode models = provider.get("models");
      if (models == null || !models.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> modelEntry : models.properties()) {
        JsonNode model = modelEntry.getValue();
        if (model == null || !model.isObject()) {
          continue;
        }
        candidates.add(toCandidate(providerName, modelEntry.getKey(), model));
      }
    }
    return candidates;
  }

  private ModelDefaultCandidateDTO toCandidate(
      String provider, String fallbackModelId, JsonNode model) {
    String modelId = CatalogJson.text(model.get("id"), fallbackModelId);
    List<String> issues = new ArrayList<>();
    ModelDefaultCandidateDTO candidate = new ModelDefaultCandidateDTO();
    candidate.setId(source().value() + ":" + provider + ":" + modelId);
    candidate.setProvider(provider);
    candidate.setModelId(modelId);
    candidate.setName(CatalogJson.text(model.get("name"), modelId));
    candidate.setLimit(limit(model.get("limit"), issues));
    candidate.setAbilities(abilities(model, issues));
    candidate.setSupportIssues(issues);
    try {
      AgentModelPricingDTO pricing = pricing(model.get("cost"), issues);
      candidate.setPricing(pricing);
      candidate.setPricingSupported(true);
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      candidate.setPricing(null);
      candidate.setPricingSupported(false);
    }
    return candidate;
  }

  private static AgentModelLimitDTO limit(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      JsonNode object = CatalogJson.requireObject(node, "limit");
      Integer context = CatalogJson.nonNegativeInt(object.get("context"), "limit.context");
      Integer output = CatalogJson.nonNegativeInt(object.get("output"), "limit.output");
      if (context == null && output == null) {
        return null;
      }
      AgentModelLimitDTO limit = new AgentModelLimitDTO();
      limit.setContext(context);
      limit.setOutput(output);
      return limit;
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      return null;
    }
  }

  private static AgentModelAbilitiesDTO abilities(JsonNode model, List<String> issues) {
    AgentModelAbilitiesDTO abilities = new AgentModelAbilitiesDTO();
    try {
      abilities.setTools(CatalogJson.bool(model.get("tool_call"), "tool_call"));
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
    }
    try {
      abilities.setReasoning(CatalogJson.bool(model.get("reasoning"), "reasoning"));
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
    }
    List<AgentModelInputModality> modalities = modalities(model.get("modalities"), issues);
    if (modalities != null && !modalities.isEmpty()) {
      abilities.setInputModalities(modalities);
    }
    return abilities;
  }

  private static List<AgentModelInputModality> modalities(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode input = node.get("input");
    if (input == null || input.isNull()) {
      return null;
    }
    if (!input.isArray()) {
      issues.add("modalities.input must be an array");
      return null;
    }
    List<AgentModelInputModality> modalities = new ArrayList<>();
    for (JsonNode value : input) {
      if (!value.isString()) {
        issues.add("modalities.input values must be text");
        continue;
      }
      AgentModelInputModality modality = modality(value.stringValue());
      if (modality == null) {
        issues.add("unsupported input modality: " + value.stringValue());
      } else if (!modalities.contains(modality)) {
        modalities.add(modality);
      }
    }
    return modalities;
  }

  private static AgentModelInputModality modality(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "text" -> AgentModelInputModality.TEXT;
      case "image" -> AgentModelInputModality.IMAGE;
      case "audio" -> AgentModelInputModality.AUDIO;
      case "video" -> AgentModelInputModality.VIDEO;
      case "pdf", "document", "application/pdf" -> AgentModelInputModality.DOCUMENT;
      default -> null;
    };
  }

  private static AgentModelPricingDTO pricing(JsonNode node, List<String> issues) {
    AgentModelPricingDTO pricing = new AgentModelPricingDTO();
    AgentModelRateSetDTO base = new AgentModelRateSetDTO();
    pricing.setBase(base);
    pricing.setOverrides(new ArrayList<>());
    pricing.setMultipliers(List.of());
    if (node == null || node.isNull()) {
      zero(base);
      return pricing;
    }
    JsonNode cost = CatalogJson.requireObject(node, "cost");
    for (String key : cost.propertyNames()) {
      if (!COST_KEYS.contains(key)) {
        throw new CatalogPriceException("unsupported v1 cost field: " + key);
      }
    }
    if (cost.has("input_audio") || cost.has("output_audio")) {
      issues.add("non-token audio cost is not included");
    }
    base.setInput(zeroIfNull(CatalogJson.decimal(cost.get("input"), "cost.input")));
    base.setOutput(zeroIfNull(CatalogJson.decimal(cost.get("output"), "cost.output")));
    base.setCacheRead(zeroIfNull(CatalogJson.decimal(cost.get("cache_read"), "cost.cache_read")));
    base.setCacheWrite(
        zeroIfNull(CatalogJson.decimal(cost.get("cache_write"), "cost.cache_write")));
    base.setCacheWriteByTtl(Map.of());
    base.setReasoning(CatalogJson.decimal(cost.get("reasoning"), "cost.reasoning"));

    Map<Integer, AgentModelRatePatchDTO> tiers = new LinkedHashMap<>();
    JsonNode tierArray = cost.get("tiers");
    if (tierArray != null && !tierArray.isNull()) {
      if (!tierArray.isArray()) {
        throw new CatalogPriceException("cost.tiers must be an array");
      }
      int index = 0;
      for (JsonNode tier : tierArray) {
        JsonNode object = CatalogJson.requireObject(tier, "cost.tiers[" + index + "]");
        validateKeys(object, TIER_KEYS, "cost.tiers[" + index + "]");
        JsonNode tierSpec = object.get("tier");
        JsonNode spec = CatalogJson.requireObject(tierSpec, "cost.tiers[" + index + "].tier");
        validateKeys(spec, TIER_META_KEYS, "cost.tiers[" + index + "].tier");
        String type = CatalogJson.text(spec.get("type"), "");
        if (!"context".equals(type)) {
          throw new CatalogPriceException("unsupported v1 tier type: " + type);
        }
        Integer size = CatalogJson.nonNegativeInt(spec.get("size"), "cost.tiers[" + index + "]");
        if (size == null) {
          throw new CatalogPriceException("cost.tiers[" + index + "].tier.size is required");
        }
        mergeTier(tiers, size, tierPatch(object, "cost.tiers[" + index + "]"));
        index++;
      }
    }
    JsonNode legacy = cost.get("context_over_200k");
    if (legacy != null && !legacy.isNull()) {
      JsonNode legacyNode = CatalogJson.requireObject(legacy, "cost.context_over_200k");
      validateKeys(legacyNode, LEGACY_TIER_KEYS, "cost.context_over_200k");
      mergeTier(tiers, 200_000, tierPatch(legacyNode, "cost.context_over_200k"));
    }
    for (Map.Entry<Integer, AgentModelRatePatchDTO> entry : tiers.entrySet()) {
      AgentModelPricingContextConditionDTO context = new AgentModelPricingContextConditionDTO();
      context.setGt(entry.getKey());
      AgentModelPricingConditionDTO when = new AgentModelPricingConditionDTO();
      when.setContext(context);
      AgentModelPricingOverrideDTO override = new AgentModelPricingOverrideDTO();
      override.setId("context-" + entry.getKey());
      override.setWhen(when);
      override.setRates(entry.getValue());
      pricing.getOverrides().add(override);
    }
    return pricing;
  }

  private static void mergeTier(
      Map<Integer, AgentModelRatePatchDTO> tiers, int size, AgentModelRatePatchDTO patch) {
    AgentModelRatePatchDTO existing = tiers.get(size);
    if (existing == null) {
      tiers.put(size, patch);
    } else if (!existing.equals(patch)) {
      throw new CatalogPriceException("conflicting v1 context tiers at " + size);
    }
  }

  private static AgentModelRatePatchDTO tierPatch(JsonNode node, String name) {
    validateKeys(node, TIER_KEYS, name);
    AgentModelRatePatchDTO patch = new AgentModelRatePatchDTO();
    patch.setInput(CatalogJson.decimal(node.get("input"), name + ".input"));
    patch.setOutput(CatalogJson.decimal(node.get("output"), name + ".output"));
    patch.setCacheRead(CatalogJson.decimal(node.get("cache_read"), name + ".cache_read"));
    patch.setCacheWrite(CatalogJson.decimal(node.get("cache_write"), name + ".cache_write"));
    patch.setReasoning(CatalogJson.decimal(node.get("reasoning"), name + ".reasoning"));
    return patch;
  }

  private static void validateKeys(JsonNode node, Set<String> allowed, String name) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new CatalogPriceException("unsupported v1 " + name + " field: " + key);
      }
    }
  }

  private static void zero(AgentModelRateSetDTO rates) {
    rates.setInput(BigDecimal.ZERO);
    rates.setOutput(BigDecimal.ZERO);
    rates.setCacheRead(BigDecimal.ZERO);
    rates.setCacheWrite(BigDecimal.ZERO);
    rates.setCacheWriteByTtl(Map.of());
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
