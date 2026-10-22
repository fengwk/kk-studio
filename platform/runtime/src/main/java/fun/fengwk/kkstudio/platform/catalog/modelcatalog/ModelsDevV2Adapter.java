package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
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
 * models.dev v2 适配器：{@code cost.text.input/output.price}、cache read/write、字段级 overrides、
 * multipliers 与 utc_hours/utc_days 条件；未知且可能影响 token 价格的条件拒绝导入该候选价格。
 */
@Component
public class ModelsDevV2Adapter implements ModelsDevCatalogAdapter {

  private static final Set<String> COST_KEYS = Set.of("text", "multipliers");
  private static final Set<String> INPUT_KEYS = Set.of("price", "cache", "overrides", "unit");
  private static final Set<String> OUTPUT_KEYS = Set.of("price", "reasoning", "overrides", "unit");
  private static final Set<String> CACHE_KEYS = Set.of("read", "write");

  @Override
  public ModelCatalogSource source() {
    return ModelCatalogSource.MODELS_DEV_V2;
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
      candidate.setPricing(pricing(model.get("cost"), issues));
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
    JsonNode capabilities = model.get("capabilities");
    if (capabilities != null && capabilities.isObject()) {
      abilities.setTools(capability(capabilities.get("tools"), "capabilities.tools", issues));
      abilities.setReasoning(
          capability(capabilities.get("reasoning"), "capabilities.reasoning", issues));
    }
    List<AgentModelInputModality> modalities = modalities(model.get("modalities"), issues);
    if (modalities != null && !modalities.isEmpty()) {
      abilities.setInputModalities(modalities);
    }
    return abilities;
  }

  private static Boolean capability(JsonNode node, String name, List<String> issues) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      if (node.isObject()) {
        return CatalogJson.bool(node.get("supported"), name + ".supported");
      }
      return CatalogJson.bool(node, name);
    } catch (CatalogPriceException error) {
      issues.add(error.getMessage());
      return null;
    }
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
    pricing.setMultipliers(new ArrayList<>());
    if (node == null || node.isNull()) {
      zero(base);
      return pricing;
    }
    JsonNode cost = CatalogJson.requireObject(node, "cost");
    for (String key : cost.propertyNames()) {
      if (!COST_KEYS.contains(key)) {
        issues.add("non-token cost is not included: " + key);
      }
    }
    JsonNode text = cost.get("text");
    if (text == null || text.isNull()) {
      zero(base);
      pricing.setMultipliers(multipliers(cost.get("multipliers"), issues));
      return pricing;
    }
    JsonNode textCost = CatalogJson.requireObject(text, "cost.text");
    for (String key : textCost.propertyNames()) {
      if (!"input".equals(key) && !"output".equals(key)) {
        throw new CatalogPriceException("unsupported v2 text cost field: " + key);
      }
    }
    JsonNode input = CatalogJson.requireObject(textCost.get("input"), "cost.text.input");
    JsonNode output = CatalogJson.requireObject(textCost.get("output"), "cost.text.output");
    validateKeys(input, INPUT_KEYS, "cost.text.input");
    validateKeys(output, OUTPUT_KEYS, "cost.text.output");
    validateUnit(input.get("unit"), "cost.text.input.unit");
    validateUnit(output.get("unit"), "cost.text.output.unit");

    base.setInput(zeroIfNull(CatalogJson.decimal(input.get("price"), "cost.text.input.price")));
    base.setOutput(zeroIfNull(CatalogJson.decimal(output.get("price"), "cost.text.output.price")));
    JsonNode cache = input.get("cache");
    if (cache != null && !cache.isNull()) {
      JsonNode cacheNode = CatalogJson.requireObject(cache, "cost.text.input.cache");
      validateKeys(cacheNode, CACHE_KEYS, "cost.text.input.cache");
      base.setCacheRead(
          zeroIfNull(CatalogJson.decimal(cacheNode.get("read"), "cost.text.input.cache.read")));
      applyCacheWrite(base, cacheNode.get("write"));
    } else {
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
    }
    base.setReasoning(CatalogJson.decimal(output.get("reasoning"), "cost.text.output.reasoning"));

    LinkedHashMap<AgentModelPricingConditionDTO, AgentModelRatePatchDTO> overrides =
        new LinkedHashMap<>();
    collectOverrides(input.get("overrides"), true, overrides);
    collectOverrides(output.get("overrides"), false, overrides);
    int index = 0;
    for (Map.Entry<AgentModelPricingConditionDTO, AgentModelRatePatchDTO> entry :
        overrides.entrySet()) {
      AgentModelPricingOverrideDTO override = new AgentModelPricingOverrideDTO();
      override.setId("override-" + index++);
      override.setWhen(entry.getKey());
      override.setRates(entry.getValue());
      pricing.getOverrides().add(override);
    }
    pricing.setMultipliers(multipliers(cost.get("multipliers"), issues));
    return pricing;
  }

  private static void validateKeys(JsonNode node, Set<String> allowed, String name) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new CatalogPriceException("unsupported v2 " + name + " field: " + key);
      }
    }
  }

  private static void validateUnit(JsonNode unit, String name) {
    if (unit == null || unit.isNull()) {
      return;
    }
    if (!unit.isString()) {
      throw new CatalogPriceException(name + " must be text");
    }
    String value = unit.stringValue();
    if (!"token".equals(value) && !"tokens".equals(value)) {
      throw new CatalogPriceException("unsupported v2 price unit: " + value);
    }
  }

  private static void applyCacheWrite(AgentModelRateSetDTO base, JsonNode write) {
    if (write == null || write.isNull()) {
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    if (!write.isObject()) {
      base.setCacheWrite(zeroIfNull(CatalogJson.decimal(write, "cost.text.input.cache.write")));
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    base.setCacheWrite(BigDecimal.ZERO);
    base.setCacheWriteByTtl(ttlRates(write, "cost.text.input.cache.write"));
  }

  private static void applyCacheWrite(AgentModelRatePatchDTO patch, JsonNode write) {
    if (write == null || write.isNull()) {
      return;
    }
    if (!write.isObject()) {
      patch.setCacheWrite(CatalogJson.decimal(write, "override.cache.write"));
      return;
    }
    patch.setCacheWriteByTtl(ttlRates(write, "override.cache.write"));
  }

  private static Map<String, BigDecimal> ttlRates(JsonNode node, String name) {
    Map<String, BigDecimal> rates = new LinkedHashMap<>();
    for (String key : node.propertyNames()) {
      long seconds = ttlSeconds(key, name);
      BigDecimal price = CatalogJson.decimal(node.get(key), name + "." + key);
      if (price == null) {
        throw new CatalogPriceException(name + "." + key + " must be a decimal number");
      }
      if (rates.put(Long.toString(seconds), price) != null) {
        throw new CatalogPriceException(name + " contains duplicate TTL: " + key);
      }
    }
    return rates;
  }

  private static long ttlSeconds(String key, String name) {
    if (key == null || key.length() < 2) {
      throw new CatalogPriceException(name + " has invalid TTL key: " + key);
    }
    char unit = key.charAt(key.length() - 1);
    long amount;
    try {
      amount = Long.parseLong(key.substring(0, key.length() - 1));
    } catch (NumberFormatException error) {
      throw new CatalogPriceException(name + " has invalid TTL key: " + key);
    }
    long multiplier =
        switch (unit) {
          case 's' -> 1L;
          case 'm' -> 60L;
          case 'h' -> 3600L;
          default -> throw new CatalogPriceException(name + " has invalid TTL unit: " + key);
        };
    long seconds = Math.multiplyExact(amount, multiplier);
    if (seconds <= 0) {
      throw new CatalogPriceException(name + " TTL keys must be positive: " + key);
    }
    return seconds;
  }

  private static void collectOverrides(
      JsonNode array,
      boolean inputSide,
      LinkedHashMap<AgentModelPricingConditionDTO, AgentModelRatePatchDTO> overrides) {
    if (array == null || array.isNull()) {
      return;
    }
    if (!array.isArray()) {
      throw new CatalogPriceException("cost.text overrides must be an array");
    }
    for (JsonNode item : array) {
      JsonNode override = CatalogJson.requireObject(item, "cost.text override");
      AgentModelPricingConditionDTO when = condition(override.get("when"), 0);
      AgentModelRatePatchDTO patch =
          overrides.computeIfAbsent(when, ignored -> new AgentModelRatePatchDTO());
      if (inputSide) {
        patch.setInput(CatalogJson.decimal(override.get("price"), "input override price"));
        JsonNode cache = override.get("cache");
        if (cache != null && !cache.isNull()) {
          JsonNode cacheNode = CatalogJson.requireObject(cache, "input override cache");
          validateKeys(cacheNode, CACHE_KEYS, "input override cache");
          patch.setCacheRead(CatalogJson.decimal(cacheNode.get("read"), "override.cache.read"));
          applyCacheWrite(patch, cacheNode.get("write"));
        }
      } else {
        patch.setOutput(CatalogJson.decimal(override.get("price"), "output override price"));
        patch.setReasoning(
            CatalogJson.decimal(override.get("reasoning"), "output override reasoning"));
      }
    }
  }

  private static List<AgentModelPricingMultiplierDTO> multipliers(
      JsonNode array, List<String> issues) {
    List<AgentModelPricingMultiplierDTO> multipliers = new ArrayList<>();
    if (array == null || array.isNull()) {
      return multipliers;
    }
    if (!array.isArray()) {
      throw new CatalogPriceException("cost.multipliers must be an array");
    }
    int index = 0;
    for (JsonNode item : array) {
      JsonNode multiplier = CatalogJson.requireObject(item, "cost.multipliers[" + index + "]");
      AgentModelPricingMultiplierDTO dto = new AgentModelPricingMultiplierDTO();
      dto.setId("multiplier-" + index);
      dto.setWhen(condition(multiplier.get("when"), 0));
      dto.setFactors(factors(multiplier.get("factor"), issues));
      multipliers.add(dto);
      index++;
    }
    return multipliers;
  }

  private static AgentModelPricingFactorsDTO factors(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      throw new CatalogPriceException("cost.multipliers factor is required");
    }
    AgentModelPricingFactorsDTO factors = new AgentModelPricingFactorsDTO();
    if (node.isNumber()) {
      BigDecimal factor = CatalogJson.decimal(node, "multiplier factor");
      factors.setInput(factor);
      factors.setOutput(factor);
      factors.setCacheRead(factor);
      factors.setCacheWrite(factor);
      factors.setReasoning(factor);
      return factors;
    }
    JsonNode factor = CatalogJson.requireObject(node, "multiplier factor");
    for (String key : factor.propertyNames()) {
      if (!"text".equals(key)) {
        issues.add("non-token multiplier is not included: " + key);
      }
    }
    JsonNode text = CatalogJson.requireObject(factor.get("text"), "multiplier factor.text");
    for (String key : text.propertyNames()) {
      if (!"input".equals(key) && !"output".equals(key)) {
        throw new CatalogPriceException("unsupported multiplier text field: " + key);
      }
    }
    JsonNode input = text.get("input");
    if (input != null && !input.isNull()) {
      JsonNode inputNode = CatalogJson.requireObject(input, "multiplier factor.text.input");
      factors.setInput(CatalogJson.decimal(inputNode.get("price"), "input factor price"));
      JsonNode cache = inputNode.get("cache");
      if (cache != null && !cache.isNull()) {
        JsonNode cacheNode = CatalogJson.requireObject(cache, "multiplier factor.text.input.cache");
        factors.setCacheRead(CatalogJson.decimal(cacheNode.get("read"), "cache read factor"));
        factors.setCacheWrite(CatalogJson.decimal(cacheNode.get("write"), "cache write factor"));
      }
    }
    JsonNode output = text.get("output");
    if (output != null && !output.isNull()) {
      if (output.isNumber()) {
        factors.setOutput(CatalogJson.decimal(output, "output factor"));
      } else {
        JsonNode outputNode = CatalogJson.requireObject(output, "multiplier factor.text.output");
        factors.setOutput(CatalogJson.decimal(outputNode.get("price"), "output factor price"));
        factors.setReasoning(
            CatalogJson.decimal(outputNode.get("reasoning"), "output reasoning factor"));
      }
    }
    return factors;
  }

  private static AgentModelPricingConditionDTO condition(JsonNode node, int depth) {
    if (depth > 8) {
      throw new CatalogPriceException("pricing condition nesting is too deep");
    }
    if (node == null || node.isNull()) {
      return new AgentModelPricingConditionDTO();
    }
    JsonNode when = CatalogJson.requireObject(node, "pricing condition");
    AgentModelPricingConditionDTO condition = new AgentModelPricingConditionDTO();
    for (String key : when.propertyNames()) {
      switch (key) {
        case "context" -> condition.setContext(context(when.get("context")));
        case "service_tier" -> condition.setServiceTier(serviceTier(when.get("service_tier")));
        case "date" -> condition.setDate(date(when.get("date")));
        case "utc_hours" -> {
          // utc_days and utc_hours jointly form one time window.
        }
        case "utc_days" -> {
          // handled with utc_hours below
        }
        case "all" -> condition.setAll(conditions(when.get("all"), depth + 1));
        case "any" -> condition.setAny(conditions(when.get("any"), depth + 1));
        case "not" -> condition.setNot(condition(when.get("not"), depth + 1));
        default -> throw new CatalogPriceException("unsupported pricing condition: " + key);
      }
    }
    if (when.has("utc_hours") || when.has("utc_days")) {
      condition.setTime(time(when.get("utc_hours"), when.get("utc_days")));
    }
    return condition;
  }

  private static AgentModelPricingContextConditionDTO context(JsonNode node) {
    JsonNode context = CatalogJson.requireObject(node, "condition.context");
    AgentModelPricingContextConditionDTO dto = new AgentModelPricingContextConditionDTO();
    dto.setGt(CatalogJson.nonNegativeInt(context.get("gt"), "condition.context.gt"));
    dto.setGte(CatalogJson.nonNegativeInt(context.get("gte"), "condition.context.gte"));
    dto.setLt(CatalogJson.nonNegativeInt(context.get("lt"), "condition.context.lt"));
    dto.setLte(CatalogJson.nonNegativeInt(context.get("lte"), "condition.context.lte"));
    return dto;
  }

  private static List<String> serviceTier(JsonNode node) {
    JsonNode serviceTier = CatalogJson.requireObject(node, "condition.service_tier");
    JsonNode equals = serviceTier.get("equals");
    if (equals != null && !equals.isNull()) {
      return List.of(requiredText(equals, "condition.service_tier.equals"));
    }
    JsonNode in = serviceTier.get("in");
    if (in != null && !in.isNull()) {
      if (!in.isArray()) {
        throw new CatalogPriceException("condition.service_tier.in must be an array");
      }
      List<String> tiers = new ArrayList<>();
      for (JsonNode value : in) {
        tiers.add(requiredText(value, "condition.service_tier.in value"));
      }
      return tiers;
    }
    throw new CatalogPriceException("condition.service_tier requires equals or in");
  }

  private static AgentModelPricingDateConditionDTO date(JsonNode node) {
    JsonNode date = CatalogJson.requireObject(node, "condition.date");
    AgentModelPricingDateConditionDTO dto = new AgentModelPricingDateConditionDTO();
    dto.setGte(textOr(date.get("gte"), "0001-01-01"));
    dto.setLt(textOr(date.get("lt"), "9999-12-31"));
    return dto;
  }

  private static AgentModelPricingTimeConditionDTO time(JsonNode hours, JsonNode days) {
    String start = "00:00";
    String end = "00:00";
    if (hours != null && !hours.isNull()) {
      JsonNode window = CatalogJson.requireObject(hours, "condition.utc_hours");
      String gte = textOr(window.get("gte"), null);
      String lt = textOr(window.get("lt"), null);
      if (gte == null && lt == null) {
        throw new CatalogPriceException("condition.utc_hours requires gte or lt");
      }
      start = gte == null ? "00:00" : gte;
      end = lt == null ? "00:00" : lt;
    }
    AgentModelPricingTimeConditionDTO dto = new AgentModelPricingTimeConditionDTO();
    dto.setStart(start);
    dto.setEnd(end);
    dto.setTimezone("UTC");
    if (days != null && !days.isNull()) {
      JsonNode dayNode = CatalogJson.requireObject(days, "condition.utc_days");
      JsonNode in = dayNode.get("in");
      if (in == null || !in.isArray()) {
        throw new CatalogPriceException("condition.utc_days.in must be an array");
      }
      List<Integer> weekdays = new ArrayList<>();
      for (JsonNode value : in) {
        weekdays.add(weekday(requiredText(value, "condition.utc_days value")));
      }
      dto.setWeekdays(weekdays);
    }
    return dto;
  }

  private static int weekday(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "mon", "monday" -> 1;
      case "tue", "tuesday" -> 2;
      case "wed", "wednesday" -> 3;
      case "thu", "thursday" -> 4;
      case "fri", "friday" -> 5;
      case "sat", "saturday" -> 6;
      case "sun", "sunday" -> 7;
      default -> throw new CatalogPriceException("unsupported weekday: " + value);
    };
  }

  private static List<AgentModelPricingConditionDTO> conditions(JsonNode node, int depth) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isArray()) {
      throw new CatalogPriceException("condition all/any must be an array");
    }
    List<AgentModelPricingConditionDTO> conditions = new ArrayList<>();
    for (JsonNode item : node) {
      conditions.add(condition(item, depth));
    }
    return conditions;
  }

  private static String textOr(JsonNode node, String fallback) {
    if (node == null || node.isNull()) {
      return fallback;
    }
    return requiredText(node, "condition value");
  }

  private static String requiredText(JsonNode node, String name) {
    if (node == null || !node.isString() || node.stringValue().isBlank()) {
      throw new CatalogPriceException(name + " must be non-blank text");
    }
    return node.stringValue();
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
