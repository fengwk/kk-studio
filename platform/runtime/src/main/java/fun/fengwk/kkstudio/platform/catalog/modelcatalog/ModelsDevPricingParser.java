package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** models.dev token 价格解析；只有当前可证明等价的基础价格会填入。 */
final class ModelsDevPricingParser {

  private static final Set<String> COST_KEYS =
      Set.of("text", "multipliers", "audio", "image", "video", "web_search");
  private static final Set<String> TEXT_KEYS = Set.of("input", "output");
  private static final Set<String> INPUT_KEYS = Set.of("price", "cache", "overrides", "unit");
  private static final Set<String> OUTPUT_KEYS = Set.of("price", "reasoning", "overrides", "unit");
  private static final Set<String> CACHE_KEYS = Set.of("read", "write", "explicit", "storage");
  private static final Set<String> TOKEN_UNITS =
      Set.of("token", "tokens", "per_million", "million_tokens");

  private ModelsDevPricingParser() {}

  /** 不支持精确转成固定三组/日期计划的条件时拒绝整套目录价格，不保留部分基础价。 */
  static ModelPricingDTO parse(JsonNode node, List<String> issues) {
    ModelPricingDTO pricing = new ModelPricingDTO();
    ModelRateSetDTO base = new ModelRateSetDTO();
    pricing.setBase(base);
    pricing.setRules(List.of());
    pricing.setChanges(List.of());
    if (node == null || node.isNull()) {
      throw new CatalogPriceException("model has no token price; no price was imported");
    }

    JsonNode cost = CatalogJson.requireObject(node, "cost");
    for (String key : cost.propertyNames()) {
      if (!COST_KEYS.contains(key)) {
        throw new CatalogPriceException("unsupported v2 cost field: " + key);
      }
      if (!Set.of("text", "multipliers").contains(key) && !cost.get(key).isNull()) {
        issues.add("non-token cost is not included: " + key);
      }
    }
    JsonNode text = cost.get("text");
    if (text == null || text.isNull()) {
      throw new CatalogPriceException("model has no token price; no price was imported");
    }
    parseText(CatalogJson.requireObject(text, "cost.text"), base, issues);
    JsonNode multipliers = cost.get("multipliers");
    if (multipliers != null
        && !multipliers.isNull()
        && (!multipliers.isArray() || multipliers.size() > 0)) {
      throw new CatalogPriceException(
          "token pricing rules cannot be represented exactly yet; the complete price was not imported");
    }
    return pricing;
  }

  private static void parseText(JsonNode text, ModelRateSetDTO base, List<String> issues) {
    validateKeys(text, TEXT_KEYS, "cost.text");
    JsonNode input = text.get("input");
    if (input == null || input.isNull()) {
      base.setInput(BigDecimal.ZERO);
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
    } else {
      parseInput(CatalogJson.requireObject(input, "cost.text.input"), base, issues);
    }
    JsonNode output = text.get("output");
    if (output == null || output.isNull()) {
      base.setOutput(BigDecimal.ZERO);
      base.setReasoning(null);
    } else {
      parseOutput(CatalogJson.requireObject(output, "cost.text.output"), base, issues);
    }
  }

  private static void parseInput(JsonNode input, ModelRateSetDTO base, List<String> issues) {
    validateKeys(input, INPUT_KEYS, "cost.text.input");
    validateUnit(input.get("unit"), "cost.text.input.unit");
    if (hasRules(input.get("overrides"))) {
      throw unsupportedRules();
    }
    base.setInput(zeroIfNull(CatalogJson.decimal(input.get("price"), "cost.text.input.price")));
    JsonNode cache = input.get("cache");
    if (cache == null || cache.isNull()) {
      base.setCacheRead(BigDecimal.ZERO);
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
      return;
    }
    JsonNode cacheNode = CatalogJson.requireObject(cache, "cost.text.input.cache");
    validateKeys(cacheNode, CACHE_KEYS, "cost.text.input.cache");
    if (cacheNode.has("explicit")) {
      issues.add("explicit cache pricing is not included");
    }
    if (cacheNode.has("storage")) {
      issues.add("cache storage cost is not included");
    }
    base.setCacheRead(
        zeroIfNull(CatalogJson.decimal(cacheNode.get("read"), "cost.text.input.cache.read")));
    setCacheWrite(base, cacheNode.get("write"));
  }

  private static void parseOutput(JsonNode output, ModelRateSetDTO base, List<String> issues) {
    validateKeys(output, OUTPUT_KEYS, "cost.text.output");
    validateUnit(output.get("unit"), "cost.text.output.unit");
    if (hasRules(output.get("overrides"))) {
      throw unsupportedRules();
    }
    base.setOutput(zeroIfNull(CatalogJson.decimal(output.get("price"), "cost.text.output.price")));
    base.setReasoning(CatalogJson.decimal(output.get("reasoning"), "cost.text.output.reasoning"));
  }

  private static boolean hasRules(JsonNode rules) {
    if (rules == null || rules.isNull()) {
      return false;
    }
    if (!rules.isArray()) {
      throw new CatalogPriceException("text price overrides must be an array");
    }
    return rules.size() > 0;
  }

  private static void setCacheWrite(ModelRateSetDTO base, JsonNode write) {
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
    Map<String, BigDecimal> byTtl = new LinkedHashMap<>();
    for (String key : write.propertyNames()) {
      String ttl = Long.toString(ttlSeconds(key));
      BigDecimal price = CatalogJson.decimal(write.get(key), "cost.text.input.cache.write." + key);
      if (price == null || byTtl.put(ttl, price) != null) {
        throw new CatalogPriceException("cache write contains invalid or duplicate TTL price");
      }
    }
    if (byTtl.isEmpty()) {
      base.setCacheWrite(BigDecimal.ZERO);
      base.setCacheWriteByTtl(Map.of());
    } else {
      base.setCacheWrite(null);
      base.setCacheWriteByTtl(byTtl);
    }
  }

  private static long ttlSeconds(String key) {
    if (key == null || key.length() < 2) {
      throw new CatalogPriceException("cache write has invalid TTL key");
    }
    char unit = key.charAt(key.length() - 1);
    long amount;
    try {
      amount = Long.parseLong(key.substring(0, key.length() - 1));
    } catch (NumberFormatException error) {
      throw new CatalogPriceException("cache write has invalid TTL key");
    }
    long multiplier =
        switch (unit) {
          case 's' -> 1;
          case 'm' -> 60;
          case 'h' -> 3600;
          case 'd' -> 86400;
          default -> throw new CatalogPriceException("cache write has invalid TTL unit");
        };
    try {
      long ttl = Math.multiplyExact(amount, multiplier);
      if (ttl <= 0) {
        throw new CatalogPriceException("cache write TTL must be positive");
      }
      return ttl;
    } catch (ArithmeticException error) {
      throw new CatalogPriceException("cache write TTL is too large");
    }
  }

  private static void validateUnit(JsonNode unit, String path) {
    if (unit == null || unit.isNull()) {
      return;
    }
    if (!unit.isString() || !TOKEN_UNITS.contains(unit.stringValue())) {
      throw new CatalogPriceException("unsupported v2 price unit at " + path);
    }
  }

  private static void validateKeys(JsonNode node, Set<String> allowed, String path) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new CatalogPriceException("unsupported v2 field at " + path + ": " + key);
      }
    }
  }

  private static CatalogPriceException unsupportedRules() {
    return new CatalogPriceException(
        "token pricing rules cannot be represented exactly yet; the complete price was not imported");
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
