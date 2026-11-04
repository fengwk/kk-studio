package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent model 配置的唯一类型化 codec/parser。
 *
 * <p>variant 承载 reasoning effort 和厂商原生协议选项 {@code protocolOptionsJson}； 输出预算由 {@code limit.output}
 * 承担。原生协议选项严格校验并保留原始 JSON 数值精度。
 *
 * <p>持久化 config JSON 的读写经过 {@link #decode(String)} 和 {@link #encode(AgentModelConfigDTO)}； 计价定义交由
 * {@link AgentModelPricingCodec} 归一化、校验和转换。
 */
@Component
public final class AgentModelRuntimeConfigParser {

  private final ObjectMapper objectMapper;
  private final ObjectReader configReader;

  public AgentModelRuntimeConfigParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectMapper strictMapper =
        objectMapper
            .rebuild()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(
                LogicalType.Integer,
                config ->
                    config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Float,
                config -> config.setCoercion(CoercionInputShape.String, CoercionAction.Fail))
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
    this.configReader = strictMapper.readerFor(AgentModelConfigDTO.class);
  }

  /**
   * 将持久化 JSON 解码为类型化 config DTO。
   *
   * @throws IllegalArgumentException JSON 为 null/空白/格式错误，或类型化 DTO 校验失败时抛出
   */
  public AgentModelConfigDTO decode(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw invalid("config must not be blank");
    }
    try {
      AgentModelConfigDTO config = configReader.readValue(configJson);
      validate(config);
      return config;
    } catch (DatabindException error) {
      if (mappingPath(error).endsWith(".protocolOptionsJson")) {
        throw invalid(mappingPath(error) + " must be a JSON string");
      }
      throw invalid(mappingPath(error) + " is invalid: " + error.getOriginalMessage(), error);
    } catch (JacksonException error) {
      throw invalid("config must be valid JSON object", error);
    }
  }

  /** 重新校验后把类型化 DTO 编码为 canonical JSON。 */
  public String encode(AgentModelConfigDTO config) {
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode agent model config", error);
    }
  }

  /** 从持久化 JSON 构建 runtime descriptor 视图。 */
  public ParsedAgentModelConfig parse(String configJson) {
    return toParsedConfig(decode(configJson));
  }

  /** 从类型化 config DTO 构建 runtime descriptor 视图。 */
  public ParsedAgentModelConfig parse(AgentModelConfigDTO config) {
    validate(config);
    return toParsedConfig(config);
  }

  private ParsedAgentModelConfig toParsedConfig(AgentModelConfigDTO config) {
    AgentModelLimitDTO limit = config.getLimit();
    AgentModelAbilitiesDTO abilities = config.getAbilities();
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
        AgentModelPricingCodec.toModelPricing(config.getPricing()));
  }

  private void validate(AgentModelConfigDTO config) {
    if (config == null) {
      throw invalid("config is required");
    }
    validateLimit(config.getLimit());
    AgentModelAbilitiesDTO abilities = validateAbilities(config.getAbilities());
    validateVariants(config, abilities);
    config.setPricing(AgentModelPricingCodec.normalizeAndValidate(config.getPricing()));
  }

  private static void validateLimit(AgentModelLimitDTO limit) {
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
  }

  private static AgentModelAbilitiesDTO validateAbilities(AgentModelAbilitiesDTO abilities) {
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
    return abilities;
  }

  private static void validateVariants(
      AgentModelConfigDTO config, AgentModelAbilitiesDTO abilities) {
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
      // reasoning 关闭时任何非 null reasoningEffort 都是自相矛盾配置；null（协议默认）仍然合法。
      if (Boolean.FALSE.equals(abilities.getReasoning()) && variant.getReasoningEffort() != null) {
        throw invalid(
            path + ".reasoningEffort must be null when config.abilities.reasoning is false");
      }
      validateReasoningEffort(variant.getReasoningEffort(), path);
      if (variant.getReasoningEffort() != null) {
        variant.setReasoningEffort(variant.getReasoningEffort().trim());
      }
      variant.setProtocolOptionsJson(
          normalizeProtocolOptionsJson(variant.getProtocolOptionsJson(), path));
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
  }

  private static void validateReasoningEffort(String reasoningEffort, String path) {
    if (reasoningEffort == null) {
      return;
    }
    if (reasoningEffort.isBlank()) {
      throw invalid(path + ".reasoningEffort must not be blank");
    }
    String trimmed = reasoningEffort.trim();
    if (trimmed.length() > ModelVariant.MAX_REASONING_EFFORT_LENGTH) {
      throw invalid(
          path
              + ".reasoningEffort must not exceed "
              + ModelVariant.MAX_REASONING_EFFORT_LENGTH
              + " characters");
    }
  }

  /** 只去除外层空白；ProviderProtocolOptions 严格校验但不替换用户的内部数值文本。 */
  private static String normalizeProtocolOptionsJson(String json, String path) {
    try {
      ProviderProtocolOptions parsed = new ProviderProtocolOptions(json);
      return parsed.isEmpty() ? "{}" : json.trim();
    } catch (IllegalArgumentException error) {
      // 丢弃 cause：底层异常可能包含原始输入，HTTP 和日志不能泄露选项值。
      if (error.getMessage() != null
          && error.getMessage().startsWith("protocolOptions must not exceed ")) {
        throw invalid(
            path
                + ".protocolOptionsJson must not exceed "
                + ProviderProtocolOptions.MAX_UTF8_BYTES
                + " UTF-8 bytes");
      }
      throw invalid(path + ".protocolOptionsJson is invalid");
    }
  }

  private static String mappingPath(DatabindException error) {
    StringBuilder path = new StringBuilder("config");
    for (JacksonException.Reference reference : error.getPath()) {
      if (reference.getPropertyName() != null) {
        path.append('.').append(reference.getPropertyName());
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
          variant.getReasoningEffort(),
          new ProviderProtocolOptions(variant.getProtocolOptionsJson()));
    } catch (IllegalArgumentException error) {
      throw invalid(
          "config.variants[" + variant.getId() + "] is invalid: " + error.getMessage(), error);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("invalid agent model: " + message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("invalid agent model: " + message, cause);
  }

  /** 已校验的 runtime model 配置，与 Provider 凭据和资源 ID 无关。 */
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
