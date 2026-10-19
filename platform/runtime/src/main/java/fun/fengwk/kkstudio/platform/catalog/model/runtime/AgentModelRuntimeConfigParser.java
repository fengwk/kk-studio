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

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricingRule;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricingWindow;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingRuleDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingWindowDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Agent model 配置的唯一类型化 codec/parser。
 *
 * <p>variant 只承载 reasoning effort（由厂商自定义，如 high/medium/low/max/xhigh，off 为显式关闭、null 为协议默认）与厂商原生
 * 协议选项 {@code protocolOptionsJson}（严格 JSON object 文本，null 等价于空 object）；输出预算由 {@code limit.output}
 * 承担，采样参数与单次上限不属于本 schema。
 *
 * <p>本类独占对持久化 {@code config} JSONB 列的所有读写。变更与读取都必须经过 {@link #decode(String)} / {@link
 * #encode(AgentModelConfigDTO)}；任何自行执行 ObjectMapper 映射的其他路径都会与规范漂移。 校验针对类型化 DTO 进行；持久化 JSON
 * 只作为不透明载体。校验消息使用公开的 {@code config.*} 术语。
 *
 * <p>原生协议选项在写入持久化之前由 {@link ProviderProtocolOptions} 严格校验；原始文本保留 JSON 数值精度。
 */
@Component
public final class AgentModelRuntimeConfigParser {

  private static final int MAX_PRICING_RULES = 16;

  private static final Pattern CLOCK_TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

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
    AgentModelPricingDTO pricing = config.getPricing();
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
        toModelPricing(pricing));
  }

  private void validate(AgentModelConfigDTO config) {
    if (config == null) {
      throw invalid("config is required");
    }
    AgentModelLimitDTO limit = config.getLimit();
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

    AgentModelAbilitiesDTO abilities = config.getAbilities();
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

    AgentModelPricingDTO pricing = config.getPricing();
    if (pricing == null) {
      throw invalid("config.pricing is required");
    }
    validatePricing(pricing);
  }

  private void validatePricing(AgentModelPricingDTO pricing) {
    String path = "config.pricing";
    requireNonBlank(pricing.getCurrency(), path + ".currency");
    requireNonBlank(pricing.getPricingTier(), path + ".pricingTier");
    requireNonBlank(pricing.getServiceTier(), path + ".serviceTier");
    requireNonBlank(pricing.getVersion(), path + ".version");
    requirePositiveBigDecimal(pricing.getServiceTierMultiplier(), path + ".serviceTierMultiplier");
    requireNonNegativeBigDecimal(
        pricing.getInputPerMillionTokens(), path + ".inputPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getOutputPerMillionTokens(), path + ".outputPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheReadPerMillionTokens(), path + ".cacheReadPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheWritePerMillionTokens(), path + ".cacheWritePerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getCacheWriteLongPerMillionTokens(), path + ".cacheWriteLongPerMillionTokens");
    requireNonNegativeBigDecimal(
        pricing.getReasoningPerMillionTokens(), path + ".reasoningPerMillionTokens");
    List<AgentModelPricingRuleDTO> rules = pricing.getRules();
    if (rules == null) {
      return;
    }
    if (rules.size() > MAX_PRICING_RULES) {
      throw invalid(path + ".rules must not exceed " + MAX_PRICING_RULES + " entries");
    }
    for (int index = 0; index < rules.size(); index++) {
      toPricingRule(rules.get(index), path + ".rules[" + index + "]");
    }
  }

  /** 校验并转换一条条件价格；时段与上下文门槛至少一项，六项单价都必填。 */
  private static ModelPricingRule toPricingRule(AgentModelPricingRuleDTO rule, String path) {
    if (rule == null) {
      throw invalid(path + " must not be null");
    }
    ModelPricingWindow window = null;
    AgentModelPricingWindowDTO timeWindow = rule.getTimeWindow();
    if (timeWindow != null) {
      ZoneId zone;
      try {
        zone = ZoneId.of(requireText(timeWindow.getTimeZone(), path + ".timeWindow.timeZone"));
      } catch (DateTimeException error) {
        throw invalid(path + ".timeWindow.timeZone must be an IANA time zone", error);
      }
      LocalTime start = clockTime(timeWindow.getStart(), path + ".timeWindow.start");
      LocalTime end = clockTime(timeWindow.getEnd(), path + ".timeWindow.end");
      if (start.equals(end)) {
        throw invalid(path + ".timeWindow start and end must differ");
      }
      window = new ModelPricingWindow(zone, start, end);
    }
    if (window == null && rule.getMinContextTokens() == null) {
      throw invalid(path + " needs a timeWindow or minContextTokens");
    }
    if (rule.getMinContextTokens() != null && rule.getMinContextTokens() <= 0) {
      throw invalid(path + ".minContextTokens must be positive");
    }
    requireNonNegativeBigDecimal(rule.getInputPerMillionTokens(), path + ".inputPerMillionTokens");
    requireNonNegativeBigDecimal(
        rule.getOutputPerMillionTokens(), path + ".outputPerMillionTokens");
    requireNonNegativeBigDecimal(
        rule.getCacheReadPerMillionTokens(), path + ".cacheReadPerMillionTokens");
    requireNonNegativeBigDecimal(
        rule.getCacheWritePerMillionTokens(), path + ".cacheWritePerMillionTokens");
    requireNonNegativeBigDecimal(
        rule.getCacheWriteLongPerMillionTokens(), path + ".cacheWriteLongPerMillionTokens");
    requireNonNegativeBigDecimal(
        rule.getReasoningPerMillionTokens(), path + ".reasoningPerMillionTokens");
    return new ModelPricingRule(
        window,
        rule.getMinContextTokens(),
        rule.getInputPerMillionTokens(),
        rule.getOutputPerMillionTokens(),
        rule.getCacheReadPerMillionTokens(),
        rule.getCacheWritePerMillionTokens(),
        rule.getCacheWriteLongPerMillionTokens(),
        rule.getReasoningPerMillionTokens());
  }

  private static String requireText(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path + " is required");
    }
    return value;
  }

  private static LocalTime clockTime(String value, String path) {
    if (value == null || !CLOCK_TIME.matcher(value).matches()) {
      throw invalid(path + " must be HH:mm");
    }
    return LocalTime.parse(value);
  }

  private void validateReasoningEffort(String reasoningEffort, String path) {
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

  private static void requireNonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path + " must be a non-blank string");
    }
  }

  private static void requirePositiveBigDecimal(BigDecimal value, String path) {
    if (value == null) {
      throw invalid(path + " must be a number");
    }
    if (value.signum() <= 0) {
      throw invalid(path + " must be positive");
    }
  }

  private static void requireNonNegativeBigDecimal(BigDecimal value, String path) {
    if (value == null) {
      throw invalid(path + " must be a number");
    }
    if (value.signum() < 0) {
      throw invalid(path + " must not be negative");
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

  private static ModelPricing toModelPricing(AgentModelPricingDTO pricing) {
    try {
      return new ModelPricing(
          pricing.getCurrency(),
          pricing.getPricingTier(),
          pricing.getServiceTier(),
          pricing.getServiceTierMultiplier(),
          pricing.getVersion(),
          pricing.getInputPerMillionTokens(),
          pricing.getOutputPerMillionTokens(),
          pricing.getCacheReadPerMillionTokens(),
          pricing.getCacheWritePerMillionTokens(),
          pricing.getCacheWriteLongPerMillionTokens(),
          pricing.getReasoningPerMillionTokens(),
          pricing.getRules() == null
              ? List.of()
              : IntStream.range(0, pricing.getRules().size())
                  .mapToObj(
                      index ->
                          toPricingRule(
                              pricing.getRules().get(index), "config.pricing.rules[" + index + "]"))
                  .toList());
    } catch (IllegalArgumentException error) {
      throw invalid("config.pricing is invalid: " + error.getMessage(), error);
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
