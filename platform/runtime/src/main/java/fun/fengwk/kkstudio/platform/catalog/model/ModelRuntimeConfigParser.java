package fun.fengwk.kkstudio.platform.catalog.model;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions.InvalidOptionsException;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions.InvalidReason;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;

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
 * <p>持久化 config JSON 的读写经过 {@link #decode(String)} 和 {@link #encode(ModelConfigDTO)}； 计价定义交由 {@link
 * ModelPricingCodec} 归一化、校验和转换。
 */
@Component
public final class ModelRuntimeConfigParser {

  private static final Set<String> RETENTIONS =
      Set.of(
          PromptCacheRetention.NONE.name(),
          PromptCacheRetention.SHORT.name(),
          PromptCacheRetention.LONG.name());

  private final ObjectMapper objectMapper;
  private final ObjectReader configReader;

  public ModelRuntimeConfigParser(ObjectMapper objectMapper) {
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
                LogicalType.Enum,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    this.configReader = strictMapper.readerFor(ModelConfigDTO.class);
  }

  /**
   * 将持久化 JSON 解码为类型化 config DTO。
   *
   * @throws IllegalArgumentException JSON 为 null/空白/格式错误，或类型化 DTO 校验失败时抛出
   */
  public ModelConfigDTO decode(String configJson) {
    return decodeValidated(configJson).config();
  }

  private ValidatedConfig decodeValidated(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw invalid("config must not be blank");
    }
    try {
      requireExactPromptCacheRetention(objectMapper.readTree(configJson));
      ModelConfigDTO config = configReader.readValue(configJson);
      return new ValidatedConfig(config, validate(config));
    } catch (DatabindException error) {
      if (mappingPath(error).endsWith(".protocolOptionsJson")) {
        throw invalid(mappingPath(error) + " must be a JSON string");
      }
      throw invalid(mappingPath(error) + " is invalid: " + error.getOriginalMessage(), error);
    } catch (JacksonException error) {
      throw invalid("config must be valid JSON object", error);
    }
  }

  /** 持久化配置中的留存档位必须是精确的枚举名：数字序号、null 与带空白近似值都不是合法 wire 值。 */
  private static void requireExactPromptCacheRetention(JsonNode config) {
    JsonNode variants = config == null ? null : config.get("variants");
    if (variants == null || !variants.isArray()) {
      return;
    }
    for (JsonNode variant : variants) {
      JsonNode retention = variant.get("promptCacheRetention");
      if (retention != null
          && (!retention.isString()
              || !retention.stringValue().equals(retention.stringValue().strip())
              || !RETENTIONS.contains(retention.stringValue()))) {
        throw invalid("config.variants[].promptCacheRetention must be a known enum name");
      }
    }
  }

  /** 重新校验后把类型化 DTO 编码为 canonical JSON。 */
  public String encode(ModelConfigDTO config) {
    validate(config);
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode agent model config", error);
    }
  }

  /** 从持久化 JSON 构建 runtime descriptor 视图。 */
  public ParsedAgentModelConfig parse(String configJson) {
    ValidatedConfig validated = decodeValidated(configJson);
    return toParsedConfig(validated.config(), validated.options());
  }

  /** 从类型化 config DTO 构建 runtime descriptor 视图。 */
  public ParsedAgentModelConfig parse(ModelConfigDTO config) {
    return toParsedConfig(config, validate(config));
  }

  private record ValidatedConfig(ModelConfigDTO config, List<ProviderProtocolOptions> options) {}

  private ParsedAgentModelConfig toParsedConfig(
      ModelConfigDTO config, List<ProviderProtocolOptions> options) {
    ModelLimitDTO limit = config.getLimit();
    ModelAbilitiesDTO abilities = config.getAbilities();
    List<ModelVariant> variants = new ArrayList<>();
    for (int index = 0; index < config.getVariants().size(); index++) {
      variants.add(toModelVariant(config.getVariants().get(index), options.get(index)));
    }
    return new ParsedAgentModelConfig(
        limit.getContext(),
        limit.getOutput(),
        toRuntimeModalities(abilities.getInputModalities()),
        abilities.getTools(),
        abilities.getReasoning(),
        List.copyOf(variants),
        config.getDefaultVariant(),
        ModelPricingCodec.toModelPricing(config.getPricing()));
  }

  private List<ProviderProtocolOptions> validate(ModelConfigDTO config) {
    if (config == null) {
      throw invalid("config is required");
    }
    validateLimit(config.getLimit());
    ModelAbilitiesDTO abilities = validateAbilities(config.getAbilities());
    List<ProviderProtocolOptions> options = validateVariants(config, abilities);
    config.setPricing(ModelPricingCodec.normalizeAndValidate(config.getPricing()));
    return options;
  }

  private static void validateLimit(ModelLimitDTO limit) {
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
    if (limit.getInput() != null && limit.getInput() <= 0) {
      throw invalid("config.limit.input must be a positive integer");
    }
    if (limit.getInput() != null && limit.getInput() > limit.getContext()) {
      throw invalid("config.limit.input must not exceed limit.context");
    }
  }

  private static ModelAbilitiesDTO validateAbilities(ModelAbilitiesDTO abilities) {
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
    Set<ModelInputModality> uniqueModalities = new HashSet<>();
    List<ModelInputModality> rawModalities = abilities.getInputModalities();
    for (int index = 0; index < rawModalities.size(); index++) {
      ModelInputModality modality = rawModalities.get(index);
      if (modality == null) {
        throw invalid("config.abilities.inputModalities[" + index + "] must not be null");
      }
      if (!uniqueModalities.add(modality)) {
        throw invalid("config.abilities.inputModalities contains duplicate value: " + modality);
      }
    }
    return abilities;
  }

  private static List<ProviderProtocolOptions> validateVariants(
      ModelConfigDTO config, ModelAbilitiesDTO abilities) {
    List<ModelVariantDTO> variants = config.getVariants();
    if (variants == null || variants.isEmpty()) {
      throw invalid("config.variants must not be empty");
    }
    Set<String> ids = new HashSet<>();
    List<ProviderProtocolOptions> options = new ArrayList<>();
    for (int index = 0; index < variants.size(); index++) {
      ModelVariantDTO variant = variants.get(index);
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
      if (variant.getPromptCacheRetention() == null) {
        throw invalid(path + ".promptCacheRetention is required");
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
      String json = variant.getProtocolOptionsJson();
      ProviderProtocolOptions parsed = toProtocolOptions(json, path);
      variant.setProtocolOptionsJson(parsed.isEmpty() ? "{}" : json.trim());
      options.add(parsed);
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
    return options;
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

  /** 内容解析与规范化唯一委派给 {@link ProviderProtocolOptions}；这里只映射 config 路径语义。 */
  private static ProviderProtocolOptions toProtocolOptions(String json, String path) {
    try {
      return new ProviderProtocolOptions(json);
    } catch (InvalidOptionsException error) {
      // 丢弃 cause：底层异常可能包含原始输入，HTTP 和日志不能泄露选项值。
      if (error.reason() == InvalidReason.TOO_LARGE) {
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

  private static Set<ModelInputModality> toRuntimeModalities(List<ModelInputModality> modalities) {
    Set<ModelInputModality> result = new HashSet<>();
    for (ModelInputModality modality : modalities) {
      result.add(ModelInputModality.valueOf(modality.name()));
    }
    return Set.copyOf(result);
  }

  private static ModelVariant toModelVariant(
      ModelVariantDTO variant, ProviderProtocolOptions options) {
    try {
      PromptCacheRetention retention =
          PromptCacheRetention.valueOf(variant.getPromptCacheRetention().name());
      return new ModelVariant(variant.getId(), variant.getReasoningEffort(), retention, options);
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
