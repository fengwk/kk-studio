package fun.fengwk.kkstudio.core.agent.model.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strictly parses the executable portion of persisted Agent model JSON.
 *
 * <p>config_json 真源：
 *
 * <pre>
 * {
 *   "limit": { "context", "output" },
 *   "abilities": {
 *     "tools", "reasoning",
 *     "modalities": { "input": [...], "output": [...] }
 *   },
 *   "pricing": { ... },
 *   "defaultVariant": "medium",
 *   "variants": [{ "id", "reasoningEffort?", sampling... }]
 * }
 * </pre>
 *
 * <p>capabilities_json 仍校验为合法 {@link ModelCapability} 非空数组（API 投影）；runtime 的 capabilities 由
 * abilities 派生。
 */
@Component
public final class AgentModelRuntimeConfigParser {

  private final ObjectMapper objectMapper;

  public AgentModelRuntimeConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ParsedAgentModelConfig parse(String capabilitiesJson, String configJson) {
    JsonNode capabilitiesNode = parseJson(capabilitiesJson, "capabilitiesJson");
    JsonNode config = parseJson(configJson, "configJson");
    if (!capabilitiesNode.isArray()) {
      throw invalid("capabilitiesJson must be an array");
    }
    if (!config.isObject()) {
      throw invalid("configJson must be an object");
    }

    // capabilitiesJson 仍校验合法枚举投影；runtime 以 abilities 派生结果为准。
    enumSet(capabilitiesNode, ModelCapability.class, "capabilitiesJson");
    if (capabilitiesNode.isEmpty()) {
      throw invalid("capabilitiesJson must not be empty");
    }

    JsonNode limit = requiredObject(config, "limit");
    long contextWindow = requiredPositiveLong(limit, "context", "configJson.limit");
    long maxOutputTokens = requiredPositiveLong(limit, "output", "configJson.limit");
    if (maxOutputTokens > contextWindow) {
      throw invalid("configJson.limit.output must not exceed limit.context");
    }

    JsonNode abilities = requiredObject(config, "abilities");
    boolean tools = requiredBoolean(abilities, "tools", "configJson.abilities");
    boolean reasoning = requiredBoolean(abilities, "reasoning", "configJson.abilities");
    JsonNode modalities = requiredObject(abilities, "modalities");
    Set<ModelInputModality> inputModalities =
        enumSet(
            requiredArray(modalities, "input", "configJson.abilities.modalities"),
            ModelInputModality.class,
            "configJson.abilities.modalities.input");
    if (inputModalities.isEmpty()) {
      throw invalid("configJson.abilities.modalities.input must not be empty");
    }
    // output 暂只校验形态（若提供），不进 runtime descriptor。
    if (modalities.has("output") && !modalities.get("output").isNull()) {
      enumSet(
          requiredArray(modalities, "output", "configJson.abilities.modalities"),
          ModelInputModality.class,
          "configJson.abilities.modalities.output");
    }

    Set<ModelCapability> derivedCapabilities =
        deriveCapabilities(tools, reasoning, inputModalities);

    List<ModelVariant> variants = variants(requiredArray(config, "variants"), maxOutputTokens);
    String defaultVariant = requiredText(config, "defaultVariant", "configJson");
    boolean defaultFound =
        variants.stream().anyMatch(variant -> variant.name().equals(defaultVariant));
    if (!defaultFound) {
      throw invalid("configJson.defaultVariant must match a variants[].id");
    }

    ModelPricing pricing = pricing(requiredObject(config, "pricing"));
    return new ParsedAgentModelConfig(
        contextWindow,
        maxOutputTokens,
        inputModalities,
        derivedCapabilities,
        variants,
        defaultVariant,
        pricing);
  }

  private static Set<ModelCapability> deriveCapabilities(
      boolean tools, boolean reasoning, Set<ModelInputModality> inputModalities) {
    EnumSet<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
    if (inputModalities.contains(ModelInputModality.TEXT)) {
      result.add(ModelCapability.TEXT);
    }
    if (tools) {
      result.add(ModelCapability.TOOLS);
    }
    if (reasoning) {
      result.add(ModelCapability.THINKING);
    }
    if (inputModalities.contains(ModelInputModality.IMAGE)) {
      result.add(ModelCapability.VISION);
    }
    if (inputModalities.contains(ModelInputModality.AUDIO)) {
      result.add(ModelCapability.AUDIO);
    }
    if (result.isEmpty()) {
      // 极端：无 TEXT 仅 VIDEO 等——至少保证非空集合给 descriptor 校验。
      result.add(ModelCapability.TEXT);
    }
    return Set.copyOf(result);
  }

  private List<ModelVariant> variants(JsonNode values, long modelMaxOutputTokens) {
    if (values.isEmpty()) {
      throw invalid("configJson.variants must not be empty");
    }
    List<ModelVariant> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < values.size(); index++) {
      JsonNode value = values.get(index);
      String path = "configJson.variants[" + index + "]";
      if (!value.isObject()) {
        throw invalid(path + " must be an object");
      }
      String id = requiredText(value, "id", path);
      if (!ids.add(id)) {
        throw invalid("configJson.variants contains duplicate id: " + id);
      }
      Integer maxOutputTokens = optionalPositiveInt(value, "maxOutputTokens", path);
      if (maxOutputTokens != null && maxOutputTokens > modelMaxOutputTokens) {
        throw invalid(path + ".maxOutputTokens must not exceed model limit.output");
      }
      String reasoningEffort = optionalText(value, "reasoningEffort", path);
      try {
        result.add(
            new ModelVariant(
                id,
                maxOutputTokens,
                optionalDouble(value, "temperature", path),
                optionalDouble(value, "topP", path),
                optionalPositiveInt(value, "topK", path),
                optionalDouble(value, "frequencyPenalty", path),
                optionalDouble(value, "presencePenalty", path),
                optionalStrings(value, "stopSequences", path),
                reasoningEffort));
      } catch (IllegalArgumentException error) {
        throw invalid(path + " is invalid: " + error.getMessage(), error);
      }
    }
    return List.copyOf(result);
  }

  private ModelPricing pricing(JsonNode value) {
    String path = "configJson.pricing";
    try {
      return new ModelPricing(
          requiredText(value, "currency", path),
          requiredText(value, "pricingTier", path),
          requiredText(value, "serviceTier", path),
          requiredDecimal(value, "serviceTierMultiplier", path),
          requiredText(value, "version", path),
          requiredDecimal(value, "inputPerMillionTokens", path),
          requiredDecimal(value, "outputPerMillionTokens", path),
          requiredDecimal(value, "cacheReadPerMillionTokens", path),
          requiredDecimal(value, "cacheWritePerMillionTokens", path),
          requiredDecimal(value, "cacheWriteLongPerMillionTokens", path),
          requiredDecimal(value, "reasoningPerMillionTokens", path));
    } catch (NullPointerException | IllegalArgumentException error) {
      throw invalid(path + " is invalid: " + error.getMessage(), error);
    }
  }

  private JsonNode parseJson(String json, String field) {
    if (json == null || json.isBlank()) {
      throw invalid(field + " must not be blank");
    }
    try {
      return objectMapper.readTree(json);
    } catch (JsonProcessingException error) {
      throw invalid(field + " must be valid JSON", error);
    }
  }

  private static JsonNode requiredArray(JsonNode parent, String field) {
    return requiredArray(parent, field, "configJson");
  }

  private static JsonNode requiredArray(JsonNode parent, String field, String parentPath) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isArray()) {
      throw invalid(parentPath + "." + field + " must be an array");
    }
    return value;
  }

  private static JsonNode requiredObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isObject()) {
      throw invalid("configJson." + field + " must be an object");
    }
    return value;
  }

  private static long requiredPositiveLong(JsonNode parent, String field, String parentPath) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid(parentPath + "." + field + " must be an integer");
    }
    long result = value.longValue();
    if (result <= 0) {
      throw invalid(parentPath + "." + field + " must be positive");
    }
    return result;
  }

  private static boolean requiredBoolean(JsonNode parent, String field, String parentPath) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalid(parentPath + "." + field + " must be a boolean");
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
      if (!item.isTextual() || item.textValue().isBlank()) {
        throw invalid(path + "." + field + " must contain non-blank strings");
      }
      result.add(item.textValue());
    }
    return List.copyOf(result);
  }

  private static <E extends Enum<E>> Set<E> enumSet(
      JsonNode values, Class<E> enumType, String path) {
    EnumSet<E> result = EnumSet.noneOf(enumType);
    for (int index = 0; index < values.size(); index++) {
      JsonNode value = values.get(index);
      if (!value.isTextual() || value.textValue().isBlank()) {
        throw invalid(path + " must contain non-blank strings");
      }
      E parsed;
      try {
        parsed = Enum.valueOf(enumType, value.textValue());
      } catch (IllegalArgumentException error) {
        throw invalid(path + " contains unsupported value: " + value.textValue(), error);
      }
      if (!result.add(parsed)) {
        throw invalid(path + " contains duplicate value: " + parsed);
      }
    }
    return Set.copyOf(result);
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("invalid executable agent model: " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("invalid executable agent model: " + message, cause);
  }

  /** Verified runtime model configuration independent of Provider credentials and resource IDs. */
  public record ParsedAgentModelConfig(
      long contextWindow,
      long maxOutputTokens,
      Set<ModelInputModality> inputModalities,
      Set<ModelCapability> capabilities,
      List<ModelVariant> variants,
      String defaultVariant,
      ModelPricing pricing) {

    public ParsedAgentModelConfig {
      inputModalities = Set.copyOf(inputModalities);
      capabilities = Set.copyOf(capabilities);
      variants = List.copyOf(variants);
      if (defaultVariant == null || defaultVariant.isBlank()) {
        throw new IllegalArgumentException("defaultVariant must not be blank");
      }
      pricing = Objects.requireNonNull(pricing, "pricing");
    }
  }
}
