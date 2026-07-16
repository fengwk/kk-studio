package fun.fengwk.kkstudio.core.agent.model.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

/** Strictly parses the executable portion of persisted Agent model JSON. */
@Component
public final class AgentModelRuntimeConfigParser {

  private final ObjectMapper objectMapper;

  public AgentModelRuntimeConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ParsedAgentModelConfig parse(String capabilitiesJson, String configJson) {
    JsonNode capabilities = parseJson(capabilitiesJson, "capabilitiesJson");
    JsonNode config = parseJson(configJson, "configJson");
    if (!capabilities.isArray()) {
      throw invalid("capabilitiesJson must be an array");
    }
    if (!config.isObject()) {
      throw invalid("configJson must be an object");
    }

    long contextWindow = requiredPositiveLong(config, "contextWindow");
    long maxOutputTokens = requiredPositiveLong(config, "maxOutputTokens");
    if (maxOutputTokens > contextWindow) {
      throw invalid("configJson.maxOutputTokens must not exceed contextWindow");
    }
    Set<ModelInputModality> inputModalities =
        enumSet(
            requiredArray(config, "inputModalities"), ModelInputModality.class, "inputModalities");
    if (inputModalities.isEmpty()) {
      throw invalid("configJson.inputModalities must not be empty");
    }
    Set<ModelCapability> modelCapabilities =
        enumSet(capabilities, ModelCapability.class, "capabilitiesJson");
    List<ModelVariant> variants = variants(requiredArray(config, "variants"), maxOutputTokens);
    ModelPricing pricing = pricing(requiredObject(config, "pricing"));
    return new ParsedAgentModelConfig(
        contextWindow, maxOutputTokens, inputModalities, modelCapabilities, variants, pricing);
  }

  private List<ModelVariant> variants(JsonNode values, long modelMaxOutputTokens) {
    if (values.isEmpty()) {
      throw invalid("configJson.variants must not be empty");
    }
    List<ModelVariant> result = new ArrayList<>();
    Set<String> names = new HashSet<>();
    for (int index = 0; index < values.size(); index++) {
      JsonNode value = values.get(index);
      String path = "configJson.variants[" + index + "]";
      if (!value.isObject()) {
        throw invalid(path + " must be an object");
      }
      String name = requiredText(value, "name", path);
      if (!names.add(name)) {
        throw invalid("configJson.variants contains duplicate name: " + name);
      }
      Integer maxOutputTokens = optionalPositiveInt(value, "maxOutputTokens", path);
      if (maxOutputTokens != null && maxOutputTokens > modelMaxOutputTokens) {
        throw invalid(path + ".maxOutputTokens must not exceed model maxOutputTokens");
      }
      result.add(
          new ModelVariant(
              name,
              maxOutputTokens,
              optionalDouble(value, "temperature", path),
              optionalDouble(value, "topP", path),
              optionalPositiveInt(value, "topK", path),
              optionalDouble(value, "frequencyPenalty", path),
              optionalDouble(value, "presencePenalty", path),
              optionalStrings(value, "stopSequences", path)));
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
    JsonNode value = parent.get(field);
    if (value == null || !value.isArray()) {
      throw invalid("configJson." + field + " must be an array");
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

  private static long requiredPositiveLong(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw invalid("configJson." + field + " must be an integer");
    }
    long result = value.longValue();
    if (result <= 0) {
      throw invalid("configJson." + field + " must be positive");
    }
    return result;
  }

  private static String requiredText(JsonNode parent, String field, String path) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw invalid(path + "." + field + " must be a non-blank string");
    }
    return value.textValue();
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
    if (value == null) {
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
    if (value == null) {
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
    if (value == null) {
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
      ModelPricing pricing) {

    public ParsedAgentModelConfig {
      inputModalities = Set.copyOf(inputModalities);
      capabilities = Set.copyOf(capabilities);
      variants = List.copyOf(variants);
      pricing = Objects.requireNonNull(pricing, "pricing");
    }
  }
}
