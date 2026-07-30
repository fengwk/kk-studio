package fun.fengwk.kkstudio.core.ai.catalog.definition.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Agent Definition 结构化配置的唯一持久化 JSON 边界。 */
@Component
public class AgentDefinitionConfigCodec {

  private final ObjectMapper objectMapper;
  private final ObjectReader configReader;

  public AgentDefinitionConfigCodec(ObjectMapper objectMapper) {
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
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    this.configReader = strictMapper.readerFor(AgentDefinitionConfigDTO.class);
  }

  public AgentDefinitionConfigDTO decode(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw new IllegalStateException("stored agent definition config is blank");
    }
    try {
      AgentDefinitionConfigDTO config = configReader.readValue(configJson);
      try {
        validate(config);
      } catch (IllegalArgumentException error) {
        throw new IllegalStateException("stored agent definition config is incomplete", error);
      }
      return config;
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("stored agent definition config is invalid", error);
    }
  }

  public String encode(AgentDefinitionConfigDTO config) {
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("agent definition config cannot be serialized", error);
    }
  }

  private static void validate(AgentDefinitionConfigDTO config) {
    if (config == null) {
      throw new IllegalArgumentException("agent definition config is required");
    }
    String environmentName = config.getEnvironmentName();
    if (environmentName != null) {
      if (environmentName.isBlank()) {
        throw new IllegalArgumentException(
            "agent definition config environmentName must not be blank when present");
      }
      if (!environmentName.equals(environmentName.trim())) {
        throw new IllegalArgumentException(
            "agent definition config environmentName must not contain surrounding whitespace");
      }
    }
    validateNames(config.getTools(), "tools");
    validateNames(config.getSkills(), "skills");
  }

  private static void validateNames(List<String> values, String field) {
    if (values == null) {
      throw new IllegalArgumentException("agent definition config " + field + " is required");
    }
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "agent definition config " + field + " must contain non-blank names");
      }
      if (!value.equals(value.trim())) {
        throw new IllegalArgumentException(
            "agent definition config " + field + " must not contain surrounding whitespace");
      }
      if (!seen.add(value)) {
        throw new IllegalArgumentException(
            "agent definition config " + field + " must not contain duplicates: " + value);
      }
    }
  }
}
