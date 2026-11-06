package fun.fengwk.kkstudio.platform.catalog.definition.configuration;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillNames;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Agent 结构化配置的唯一持久化 JSON 边界。 */
@Component
public class AgentConfigCodec {

  private final ObjectMapper objectMapper;
  private final ObjectReader configReader;

  public AgentConfigCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectMapper strictMapper =
        objectMapper
            .rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .withCoercionConfig(
                LogicalType.Integer,
                config ->
                    config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Boolean,
                config ->
                    config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    this.configReader = strictMapper.readerFor(AgentConfigDTO.class);
  }

  public AgentConfigDTO decode(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw new IllegalStateException("stored agent config is blank");
    }
    try {
      AgentConfigDTO config = configReader.readValue(configJson);
      try {
        validate(config);
      } catch (IllegalArgumentException error) {
        throw new IllegalStateException("stored agent config is incomplete", error);
      }
      return config;
    } catch (JacksonException error) {
      throw new IllegalStateException("stored agent config is invalid", error);
    }
  }

  public String encode(AgentConfigDTO config) {
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("agent config cannot be serialized", error);
    }
  }

  private static void validate(AgentConfigDTO config) {
    if (config == null) {
      throw new IllegalArgumentException("agent config is required");
    }
    validateToolNames(config.getTools());
    validateSkills(config.getSkills());
    validateNames(config.getSubagents(), "subagents");
  }

  /** 校验选中的全局 Skill 引用列表：非空列表、非空元素、canonical 短名、保序且不重复。 */
  private static void validateSkills(List<SkillRefDTO> skills) {
    if (skills == null) {
      throw new IllegalArgumentException("agent config skills is required");
    }
    Set<SkillRefKey> seen = new HashSet<>();
    for (SkillRefDTO skill : skills) {
      if (skill == null) {
        throw new IllegalArgumentException("agent config skills must not contain null elements");
      }
      try {
        SkillNames.canonicalPackageName(skill.getPackageName());
        SkillNames.canonicalSkillName(skill.getName());
      } catch (IllegalArgumentException | NullPointerException error) {
        throw new IllegalArgumentException(
            "agent config skills must contain canonical short names: "
                + skill.getPackageName()
                + "/"
                + skill.getName(),
            error);
      }
      if (!seen.add(new SkillRefKey(skill.getPackageName(), skill.getName()))) {
        throw new IllegalArgumentException(
            "agent config skills must not contain duplicates: "
                + skill.getPackageName()
                + "/"
                + skill.getName());
      }
    }
  }

  private record SkillRefKey(String packageName, String name) {}

  private static void validateToolNames(List<String> values) {
    if (values == null) {
      throw new IllegalArgumentException("agent config tools is required");
    }
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (!ToolDescriptor.isValidName(value)) {
        throw new IllegalArgumentException(
            "agent config tools must contain valid model-visible tool names: " + value);
      }
      if (!seen.add(value)) {
        throw new IllegalArgumentException(
            "agent config tools must not contain duplicates: " + value);
      }
    }
  }

  private static void validateNames(List<String> values, String field) {
    if (values == null) {
      throw new IllegalArgumentException("agent config " + field + " is required");
    }
    Set<String> seen = new HashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "agent config " + field + " must contain non-blank names");
      }
      if (!value.equals(value.trim())) {
        throw new IllegalArgumentException(
            "agent config " + field + " must not contain surrounding whitespace");
      }
      if (value.length() > 128
          || value.indexOf(':') >= 0
          || value.indexOf('/') >= 0
          || value.indexOf('@') >= 0
          || value.indexOf('\\') >= 0) {
        throw new IllegalArgumentException(
            "agent config " + field + " must contain short names only: " + value);
      }
      if (!seen.add(value)) {
        throw new IllegalArgumentException(
            "agent config " + field + " must not contain duplicates: " + value);
      }
    }
  }
}
