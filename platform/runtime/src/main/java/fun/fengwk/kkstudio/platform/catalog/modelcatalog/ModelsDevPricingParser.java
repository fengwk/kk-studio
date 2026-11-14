package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingFactorsDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingMultiplierDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingOverrideDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRatePatchDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * models.dev {@code cost} 字段的解析：text 输入/输出价格、cache read/write（含 TTL）、字段级 overrides 与 multipliers。
 *
 * <p>非 token 成本（audio/image/video/web_search）只提示不导入；地区倍率整条忽略并提示，其余未知且可能影响 token 价格的条件拒绝该候选价格。
 */
final class ModelsDevPricingParser {

  private static final Set<String> COST_KEYS =
      Set.of("text", "multipliers", "audio", "image", "video", "web_search");
  private static final Set<String> INPUT_KEYS = Set.of("price", "cache", "overrides", "unit");
  private static final Set<String> OUTPUT_KEYS = Set.of("price", "reasoning", "overrides", "unit");
  private static final Set<String> CACHE_KEYS = Set.of("read", "write", "explicit", "storage");
  private static final Set<String> INPUT_OVERRIDE_KEYS = Set.of("when", "price", "cache");
  private static final Set<String> OUTPUT_OVERRIDE_KEYS = Set.of("when", "price", "reasoning");
  private static final Set<String> MULTIPLIER_KEYS = Set.of("when", "factor");
  private static final Set<String> FACTOR_TEXT_KEYS = Set.of("input", "output");
  private static final Set<String> FACTOR_INPUT_KEYS = Set.of("price", "cache");
  private static final Set<String> FACTOR_CACHE_KEYS = Set.of("read", "write");
  private static final Set<String> FACTOR_OUTPUT_KEYS = Set.of("price", "reasoning");
  private static final Set<String> TOKEN_UNITS =
      Set.of("token", "tokens", "per_million", "million_tokens");

  private ModelsDevPricingParser() {}

  /** 解析完整 pricing；缺省 cost 归一为完整零定义，与配置侧共用同一套校验。 */
  static ModelPricingDTO parse(JsonNode node, List<String> issues) {
    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    pricing.setBase(base);
    pricing.setOverrides(new ArrayList<>());
    pricing.setMultipliers(new ArrayList<>());
    if (node == null || node.isNull()) {
      zero(base);
      return pricing;
    }
    JsonNode cost = CatalogJson.requireObject(node, "cost");
    rejectUnsupportedCostKeys(cost, issues);
    JsonNode text = cost.get("text");
    if (text == null || text.isNull()) {
      zero(base);
      pricing.setMultipliers(multipliers(cost.get("multipliers"), issues));
      return pricing;
    }
    List<ModelPricingOverrideDTO> overrides =
        textCost(CatalogJson.requireObject(text, "cost.text"), base, issues);
    int index = 0;
    for (ModelPricingOverrideDTO override : overrides) {
      override.setId("override-" + index++);
      pricing.getOverrides().add(override);
    }
    pricing.setMultipliers(multipliers(cost.get("multipliers"), issues));
    return pricing;
  }

  private static void rejectUnsupportedCostKeys(JsonNode cost, List<String> issues) {
    for (String key : cost.propertyNames()) {
      if (!COST_KEYS.contains(key)) {
        throw new CatalogPriceException("unsupported v2 cost field: " + key);
      }
      if (!"text".equals(key) && !"multipliers".equals(key) && !cost.get(key).isNull()) {
        issues.add("non-token cost is not included: " + key);
      }
    }
  }

  private static List<ModelPricingOverrideDTO> textCost(
      JsonNode textCost, ModelRateSetDTO base, List<String> issues) {
    for (String key : textCost.propertyNames()) {
      if (!"input".equals(key) && !"output".equals(key)) {
        throw new CatalogPriceException("unsupported v2 text cost field: " + key);
      }
    }
    List<ModelPricingOverrideDTO> overrides = new ArrayList<>();
    textInput(textCost.get("input"), base, overrides, issues);
    textOutput(textCost.get("output"), base, overrides, issues);
    return overrides;
  }

  private static void textInput(
      JsonNode input,
      ModelRateSetDTO base,
      List<ModelPricingOverrideDTO> overrides,
      List<String> issues) {
    if (input == null || input.isNull()) {
      base.setInput(BigDecimal.ZERO);
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    JsonNode inputNode = CatalogJson.requireObject(input, "cost.text.input");
    validateKeys(inputNode, INPUT_KEYS, "cost.text.input");
    validateUnit(inputNode.get("unit"), "cost.text.input.unit");
    base.setInput(zeroIfNull(CatalogJson.decimal(inputNode.get("price"), "cost.text.input.price")));
    cache(inputNode.get("cache"), base, issues);
    collectOverrides(inputNode.get("overrides"), true, overrides, issues);
  }

  private static void cache(JsonNode cache, ModelRateSetDTO base, List<String> issues) {
    if (cache == null || cache.isNull()) {
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    JsonNode cacheNode = CatalogJson.requireObject(cache, "cost.text.input.cache");
    validateCacheKeys(cacheNode, "cost.text.input.cache", issues);
    base.setCacheRead(
        zeroIfNull(CatalogJson.decimal(cacheNode.get("read"), "cost.text.input.cache.read")));
    applyCacheWrite(base, cacheNode.get("write"));
  }

  private static void textOutput(
      JsonNode output,
      ModelRateSetDTO base,
      List<ModelPricingOverrideDTO> overrides,
      List<String> issues) {
    if (output == null || output.isNull()) {
      base.setOutput(BigDecimal.ZERO);
      base.setReasoning(null);
      return;
    }
    JsonNode outputNode = CatalogJson.requireObject(output, "cost.text.output");
    validateKeys(outputNode, OUTPUT_KEYS, "cost.text.output");
    validateUnit(outputNode.get("unit"), "cost.text.output.unit");
    base.setOutput(
        zeroIfNull(CatalogJson.decimal(outputNode.get("price"), "cost.text.output.price")));
    base.setReasoning(
        CatalogJson.decimal(outputNode.get("reasoning"), "cost.text.output.reasoning"));
    collectOverrides(outputNode.get("overrides"), false, overrides, issues);
  }

  private static void validateCacheKeys(JsonNode node, String name, List<String> issues) {
    validateKeys(node, CACHE_KEYS, name);
    if (node.has("explicit")) {
      issues.add("explicit cache pricing is not included");
    }
    if (node.has("storage")) {
      issues.add("cache storage cost is not included");
    }
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
    if (!TOKEN_UNITS.contains(value)) {
      throw new CatalogPriceException("unsupported v2 price unit: " + value);
    }
  }

  private static void applyCacheWrite(ModelRateSetDTO base, JsonNode write) {
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

  private static void applyCacheWrite(ModelRatePatchDTO patch, JsonNode write) {
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
          case 'd' -> 86400L;
          default -> throw new CatalogPriceException(name + " has invalid TTL unit: " + key);
        };
    long seconds;
    try {
      seconds = Math.multiplyExact(amount, multiplier);
    } catch (ArithmeticException error) {
      throw new CatalogPriceException(name + " TTL is too large: " + key);
    }
    if (seconds <= 0) {
      throw new CatalogPriceException(name + " TTL keys must be positive: " + key);
    }
    return seconds;
  }

  private static void collectOverrides(
      JsonNode array,
      boolean inputSide,
      List<ModelPricingOverrideDTO> overrides,
      List<String> issues) {
    if (array == null || array.isNull()) {
      return;
    }
    if (!array.isArray()) {
      throw new CatalogPriceException("cost.text overrides must be an array");
    }
    for (JsonNode item : array) {
      JsonNode override = CatalogJson.requireObject(item, "cost.text override");
      validateKeys(
          override, inputSide ? INPUT_OVERRIDE_KEYS : OUTPUT_OVERRIDE_KEYS, "text override");
      ModelRatePatchDTO patch = new ModelRatePatchDTO();
      if (inputSide) {
        inputPatch(patch, override, issues);
      } else {
        outputPatch(patch, override);
      }
      ModelPricingOverrideDTO rule = new ModelPricingOverrideDTO();
      rule.setWhen(ModelsDevPricingConditionParser.parse(override.get("when"), null));
      rule.setRates(patch);
      overrides.add(rule);
    }
  }

  private static void inputPatch(ModelRatePatchDTO patch, JsonNode override, List<String> issues) {
    patch.setInput(CatalogJson.decimal(override.get("price"), "input override price"));
    JsonNode cache = override.get("cache");
    if (cache == null || cache.isNull()) {
      return;
    }
    JsonNode cacheNode = CatalogJson.requireObject(cache, "input override cache");
    validateCacheKeys(cacheNode, "input override cache", issues);
    patch.setCacheRead(CatalogJson.decimal(cacheNode.get("read"), "override.cache.read"));
    applyCacheWrite(patch, cacheNode.get("write"));
  }

  private static void outputPatch(ModelRatePatchDTO patch, JsonNode override) {
    patch.setOutput(CatalogJson.decimal(override.get("price"), "output override price"));
    if (override.has("reasoning")) {
      patch.setReasoning(
          CatalogJson.decimal(override.get("reasoning"), "output override reasoning"));
    }
  }

  private static List<ModelPricingMultiplierDTO> multipliers(JsonNode array, List<String> issues) {
    List<ModelPricingMultiplierDTO> multipliers = new ArrayList<>();
    if (array == null || array.isNull()) {
      return multipliers;
    }
    if (!array.isArray()) {
      throw new CatalogPriceException("cost.multipliers must be an array");
    }
    List<ModelPricingMultiplierDTO> parsed = new ArrayList<>();
    int index = 0;
    for (JsonNode item : array) {
      JsonNode multiplier = CatalogJson.requireObject(item, "cost.multipliers[" + index + "]");
      validateKeys(multiplier, MULTIPLIER_KEYS, "multiplier");
      ModelPricingMultiplierDTO dto = new ModelPricingMultiplierDTO();
      dto.setId("multiplier-" + index);
      ModelsDevPricingConditionParser.RegionImportContext context =
          new ModelsDevPricingConditionParser.RegionImportContext();
      dto.setWhen(ModelsDevPricingConditionParser.parse(multiplier.get("when"), context));
      dto.setFactors(factors(multiplier.get("factor"), issues));
      parsed.add(dto);
      if (!context.hasRegion()) {
        multipliers.add(dto);
      }
      index++;
    }
    if (parsed.size() != multipliers.size()) {
      // 被忽略的规则也必须通过其余条件、倍率精度和数量校验；临时定义只校验，不用于计价。
      ModelPricingDTO validation = new ModelPricingDTO();
      validation.setMultipliers(parsed);
      CatalogPricingValidator.validate(validation);
      issues.add("ignored region multiplier");
    }
    return multipliers;
  }

  private static ModelPricingFactorsDTO factors(JsonNode node, List<String> issues) {
    if (node == null || node.isNull()) {
      throw new CatalogPriceException("cost.multipliers factor is required");
    }
    ModelPricingFactorsDTO factors = new ModelPricingFactorsDTO();
    if (node.isNumber()) {
      return uniformFactor(node, factors);
    }
    JsonNode factor = CatalogJson.requireObject(node, "multiplier factor");
    for (String key : factor.propertyNames()) {
      if ("text".equals(key)) {
        continue;
      }
      if ("audio".equals(key)) {
        issues.add("non-token multiplier is not included: " + key);
        continue;
      }
      throw new CatalogPriceException("unsupported multiplier factor field: " + key);
    }
    JsonNode text = factor.get("text");
    if (text == null || text.isNull()) {
      return factors;
    }
    if (text.isNumber()) {
      return factors(text, issues);
    }
    textFactor(CatalogJson.requireObject(text, "multiplier factor.text"), factors);
    return factors;
  }

  private static ModelPricingFactorsDTO uniformFactor(
      JsonNode node, ModelPricingFactorsDTO factors) {
    BigDecimal factor = CatalogJson.decimal(node, "multiplier factor");
    factors.setInput(factor);
    factors.setOutput(factor);
    factors.setCacheRead(factor);
    factors.setCacheWrite(factor);
    factors.setReasoning(factor);
    return factors;
  }

  private static void textFactor(JsonNode textFactor, ModelPricingFactorsDTO factors) {
    validateKeys(textFactor, FACTOR_TEXT_KEYS, "multiplier factor.text");
    inputFactor(textFactor.get("input"), factors);
    outputFactor(textFactor.get("output"), factors);
  }

  private static void inputFactor(JsonNode input, ModelPricingFactorsDTO factors) {
    if (input == null || input.isNull()) {
      return;
    }
    JsonNode inputNode = CatalogJson.requireObject(input, "multiplier factor.text.input");
    validateKeys(inputNode, FACTOR_INPUT_KEYS, "multiplier factor.text.input");
    factors.setInput(CatalogJson.decimal(inputNode.get("price"), "input factor price"));
    JsonNode cache = inputNode.get("cache");
    if (cache == null || cache.isNull()) {
      return;
    }
    JsonNode cacheNode = CatalogJson.requireObject(cache, "multiplier factor.text.input.cache");
    validateKeys(cacheNode, FACTOR_CACHE_KEYS, "multiplier factor.text.input.cache");
    factors.setCacheRead(CatalogJson.decimal(cacheNode.get("read"), "cache read factor"));
    JsonNode write = cacheNode.get("write");
    if (write != null && !write.isNull() && write.isObject()) {
      throw new CatalogPriceException("TTL multiplier cache write is not supported");
    }
    factors.setCacheWrite(CatalogJson.decimal(write, "cache write factor"));
  }

  private static void outputFactor(JsonNode output, ModelPricingFactorsDTO factors) {
    if (output == null || output.isNull()) {
      return;
    }
    if (output.isNumber()) {
      factors.setOutput(CatalogJson.decimal(output, "output factor"));
      return;
    }
    JsonNode outputNode = CatalogJson.requireObject(output, "multiplier factor.text.output");
    validateKeys(outputNode, FACTOR_OUTPUT_KEYS, "multiplier factor.text.output");
    factors.setOutput(CatalogJson.decimal(outputNode.get("price"), "output factor price"));
    factors.setReasoning(
        CatalogJson.decimal(outputNode.get("reasoning"), "output reasoning factor"));
  }

  private static void zero(ModelRateSetDTO rates) {
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
