package fun.fengwk.kkstudio.platform.catalog.model.runtime;

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
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingContextConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDateConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingFactorsDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingMultiplierDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingRequestBodyConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingTimeConditionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        parsePricing(pricing));
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
      pricing = new AgentModelPricingDTO();
      config.setPricing(pricing);
    }
    normalizePricing(pricing);
    validatePricing(pricing);
  }

  /** 直接解析单个 pricing DTO；null/缺省即完整零定义。调用方与 {@code parse(config)} 使用同一转换，避免目录校验等旁路漂移。 */
  public static ModelPricing parsePricing(AgentModelPricingDTO pricing) {
    if (pricing == null) {
      return ModelPricing.ZERO;
    }
    normalizePricing(pricing);
    validatePricing(pricing);
    return toModelPricing(pricing);
  }

  /**
   * 归一化为 canonical pricing DTO：base 普通价格缺失写 0、TTL 缺失写空 map、规则数组缺失写空列表。
   *
   * <p>base.reasoning 缺省仍表示继承 output；rate patch 的 null/缺省仍表示不修改，不能在此默认成 0。
   */
  private static void normalizePricing(AgentModelPricingDTO pricing) {
    AgentModelRateSetDTO base = pricing.getBase();
    if (base == null) {
      base = new AgentModelRateSetDTO();
      pricing.setBase(base);
    }
    if (base.getInput() == null) {
      base.setInput(BigDecimal.ZERO);
    }
    if (base.getOutput() == null) {
      base.setOutput(BigDecimal.ZERO);
    }
    if (base.getCacheRead() == null) {
      base.setCacheRead(BigDecimal.ZERO);
    }
    if (base.getCacheWrite() == null) {
      base.setCacheWrite(BigDecimal.ZERO);
    }
    if (base.getCacheWriteByTtl() == null) {
      base.setCacheWriteByTtl(Map.of());
    }
    if (pricing.getOverrides() == null) {
      pricing.setOverrides(List.of());
    }
    if (pricing.getMultipliers() == null) {
      pricing.setMultipliers(List.of());
    }
  }

  private static void validatePricing(AgentModelPricingDTO pricing) {
    String path = "config.pricing";
    validateRateSet(pricing.getBase(), path + ".base");
    List<AgentModelPricingOverrideDTO> overrides = pricing.getOverrides();
    if (overrides != null) {
      requireRuleCount(overrides.size(), path + ".overrides");
      Set<String> ids = new HashSet<>();
      for (int index = 0; index < overrides.size(); index++) {
        AgentModelPricingOverrideDTO override = overrides.get(index);
        if (override == null) {
          throw invalid(path + ".overrides[" + index + "] must be an object");
        }
        String rulePath = path + ".overrides[" + index + "]";
        requireRuleId(override.getId(), ids, rulePath + ".id");
        validateCondition(override.getWhen(), rulePath + ".when", 0);
        validateRatePatch(override.getRates(), rulePath + ".rates");
      }
    }
    List<AgentModelPricingMultiplierDTO> multipliers = pricing.getMultipliers();
    if (multipliers != null) {
      requireRuleCount(multipliers.size(), path + ".multipliers");
      Set<String> ids = new HashSet<>();
      for (int index = 0; index < multipliers.size(); index++) {
        AgentModelPricingMultiplierDTO multiplier = multipliers.get(index);
        if (multiplier == null) {
          throw invalid(path + ".multipliers[" + index + "] must be an object");
        }
        String rulePath = path + ".multipliers[" + index + "]";
        requireRuleId(multiplier.getId(), ids, rulePath + ".id");
        validateCondition(multiplier.getWhen(), rulePath + ".when", 0);
        validateFactors(multiplier.getFactors(), rulePath + ".factors");
      }
    }
  }

  private static void validateRateSet(AgentModelRateSetDTO rates, String path) {
    if (rates == null) {
      return;
    }
    requireOptionalNonNegative(rates.getInput(), path + ".input");
    requireOptionalNonNegative(rates.getOutput(), path + ".output");
    requireOptionalNonNegative(rates.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rates.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(rates.getReasoning(), path + ".reasoning");
    validateTtlRates(rates.getCacheWriteByTtl(), path + ".cacheWriteByTtl");
  }

  private static void validateRatePatch(AgentModelRatePatchDTO rates, String path) {
    if (rates == null) {
      throw invalid(path + " must be an object");
    }
    requireOptionalNonNegative(rates.getInput(), path + ".input");
    requireOptionalNonNegative(rates.getOutput(), path + ".output");
    requireOptionalNonNegative(rates.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(rates.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(rates.getReasoning(), path + ".reasoning");
    validateTtlRates(rates.getCacheWriteByTtl(), path + ".cacheWriteByTtl");
  }

  private static void validateFactors(AgentModelPricingFactorsDTO factors, String path) {
    if (factors == null) {
      throw invalid(path + " must be an object");
    }
    requireOptionalNonNegative(factors.getInput(), path + ".input");
    requireOptionalNonNegative(factors.getOutput(), path + ".output");
    requireOptionalNonNegative(factors.getCacheRead(), path + ".cacheRead");
    requireOptionalNonNegative(factors.getCacheWrite(), path + ".cacheWrite");
    requireOptionalNonNegative(factors.getReasoning(), path + ".reasoning");
  }

  private static void validateTtlRates(Map<String, BigDecimal> rates, String path) {
    if (rates == null) {
      return;
    }
    if (rates.size() > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " entries");
    }
    Set<Long> ttls = new HashSet<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), path);
      if (!ttls.add(ttl)) {
        throw invalid(path + " contains duplicate TTL: " + ttl);
      }
      requireOptionalNonNegative(entry.getValue(), path + "." + ttl);
    }
  }

  private static void requireRuleCount(int size, String path) {
    if (size > ModelPricing.MAX_RULES) {
      throw invalid(path + " must not exceed " + ModelPricing.MAX_RULES + " rules");
    }
  }

  private static void requireRuleId(String id, Set<String> ids, String path) {
    requireNonBlank(id, path);
    if (!ids.add(id)) {
      throw invalid("config.pricing rule ids must be unique: " + id);
    }
  }

  private static void validateCondition(
      AgentModelPricingConditionDTO condition, String path, int depth) {
    if (condition == null) {
      throw invalid(path + " must be an object");
    }
    if (depth > ModelPricingCondition.MAX_DEPTH) {
      throw invalid(path + " nesting must not exceed " + ModelPricingCondition.MAX_DEPTH);
    }
    AgentModelPricingContextConditionDTO context = condition.getContext();
    if (context != null) {
      String contextPath = path + ".context";
      requireOptionalNonNegativeInt(context.getGt(), contextPath + ".gt");
      requireOptionalNonNegativeInt(context.getGte(), contextPath + ".gte");
      requireOptionalNonNegativeInt(context.getLt(), contextPath + ".lt");
      requireOptionalNonNegativeInt(context.getLte(), contextPath + ".lte");
      ensureCondition(
          () ->
              new ModelPricingCondition.Context(
                  context.getGt(), context.getGte(), context.getLt(), context.getLte()),
          contextPath);
    }
    List<String> serviceTier = condition.getServiceTier();
    if (serviceTier != null) {
      if (serviceTier.size() > ModelPricingCondition.MAX_LIST_SIZE) {
        throw invalid(
            path
                + ".serviceTier must not exceed "
                + ModelPricingCondition.MAX_LIST_SIZE
                + " values");
      }
      for (int index = 0; index < serviceTier.size(); index++) {
        requireNonBlank(serviceTier.get(index), path + ".serviceTier[" + index + "]");
      }
      Set<String> uniqueTiers = new HashSet<>(serviceTier);
      if (uniqueTiers.size() != serviceTier.size()) {
        throw invalid(path + ".serviceTier contains duplicate value");
      }
    }
    AgentModelPricingDateConditionDTO date = condition.getDate();
    if (date != null) {
      String datePath = path + ".date";
      LocalDate gte = parseOptionalDate(date.getGte(), datePath + ".gte");
      LocalDate lt = parseOptionalDate(date.getLt(), datePath + ".lt");
      ensureCondition(() -> new ModelPricingCondition.DateRange(gte, lt), datePath);
    }
    AgentModelPricingTimeConditionDTO time = condition.getTime();
    if (time != null) {
      String timePath = path + ".time";
      TimeBounds bounds = parseTimeBounds(time.getStart(), time.getEnd(), timePath);
      requireNonBlank(time.getTimezone(), timePath + ".timezone");
      ZoneId timezone;
      try {
        timezone = ZoneId.of(time.getTimezone());
      } catch (DateTimeException error) {
        throw invalid(timePath + ".timezone must be an IANA time zone");
      }
      List<Integer> weekdays = time.getWeekdays();
      if (weekdays != null && weekdays.size() > 7) {
        throw invalid(timePath + ".weekdays must not exceed 7 values");
      }
      ensureCondition(
          () ->
              new ModelPricingCondition.TimeWindow(
                  bounds.start(), bounds.end(), timezone, weekdays),
          timePath);
    }
    AgentModelPricingRequestBodyConditionDTO requestBody = condition.getRequestBody();
    if (requestBody != null) {
      validateRequestBody(requestBody, path + ".requestBody");
    }
    validateConditionList(condition.getAll(), path + ".all", depth);
    validateConditionList(condition.getAny(), path + ".any", depth);
    if (condition.getNot() != null) {
      validateCondition(condition.getNot(), path + ".not", depth + 1);
    }
  }

  private static void validateRequestBody(
      AgentModelPricingRequestBodyConditionDTO requestBody, String path) {
    if (requestBody.getPath() == null) {
      throw invalid(path + ".path must be a JSON Pointer string");
    }
    if (requestBody.getEquals() == null) {
      throw invalid(path + ".equals is required; write null explicitly to match JSON null");
    }
    // 构造 requestBody 即完成 path/equals 的全部边界校验。
    toRequestBody(requestBody.getPath(), requestBody.getEquals(), path);
  }

  private static ModelPricingCondition.RequestBody toRequestBody(
      String pathValue, JsonNode equals, String path) {
    if (equals.isString()) {
      return ModelPricingCondition.RequestBody.stringValue(pathValue, equals.stringValue());
    }
    if (equals.isNumber()) {
      // 保留指数文本交给 RequestBody 先检查 precision/scale，避免展开巨大指数后才失败。
      return ModelPricingCondition.RequestBody.numberValue(pathValue, equals.toString());
    }
    if (equals.isBoolean()) {
      return ModelPricingCondition.RequestBody.booleanValue(pathValue, equals.booleanValue());
    }
    if (equals.isNull()) {
      return ModelPricingCondition.RequestBody.nullValue(pathValue);
    }
    throw invalid(path + ".equals must be a JSON string, number, boolean or null");
  }

  private static void validateConditionList(
      List<AgentModelPricingConditionDTO> conditions, String path, int depth) {
    if (conditions == null) {
      return;
    }
    if (conditions.size() > ModelPricingCondition.MAX_LIST_SIZE) {
      throw invalid(
          path + " must not exceed " + ModelPricingCondition.MAX_LIST_SIZE + " conditions");
    }
    for (int index = 0; index < conditions.size(); index++) {
      validateCondition(conditions.get(index), path + "[" + index + "]", depth + 1);
    }
  }

  private static LocalDate parseOptionalDate(String value, String path) {
    return value == null ? null : parseDate(value, path);
  }

  private static LocalDate parseDate(String value, String path) {
    requireNonBlank(value, path);
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException error) {
      throw invalid(path + " must be YYYY-MM-DD");
    }
  }

  private static LocalTime parseTime(String value, String path) {
    requireNonBlank(value, path);
    try {
      return LocalTime.parse(value);
    } catch (DateTimeParseException error) {
      throw invalid(path + " must be HH:mm");
    }
  }

  /**
   * Wire 时间窗左闭右开；全天必须显式写成 00:00-24:00，并在内部规范为 00:00-00:00。end=24:00 可配任意合法 start 表示当日 24:00 右边界；不接受
   * start=24:00，也不接受 start=end（含 00:00-00:00）被误读为全天。
   */
  private static TimeBounds parseTimeBounds(String startValue, String endValue, String path) {
    if ("24:00".equals(startValue)) {
      throw invalid(path + ".start must be before 24:00");
    }
    LocalTime start = parseTime(startValue, path + ".start");
    if ("24:00".equals(endValue)) {
      return new TimeBounds(start, LocalTime.MIDNIGHT);
    }
    LocalTime end = parseTime(endValue, path + ".end");
    if (start.equals(end)) {
      throw invalid(path + ".start and end must differ; use 00:00-24:00 for all day");
    }
    return new TimeBounds(start, end);
  }

  private record TimeBounds(LocalTime start, LocalTime end) {}

  private static long parseTtl(String value, String path) {
    try {
      long ttl = Long.parseLong(value);
      if (ttl > 0) {
        return ttl;
      }
    } catch (NumberFormatException error) {
      // fall through to the single public error below
    }
    throw invalid(path + " keys must be positive integer seconds");
  }

  private static void requireOptionalNonNegative(BigDecimal value, String path) {
    if (value == null) {
      return;
    }
    if (value.signum() < 0) {
      throw invalid(path + " must not be negative");
    }
    if (value.precision() > ModelPricing.MAX_DECIMAL_PRECISION) {
      throw invalid(path + " precision must not exceed " + ModelPricing.MAX_DECIMAL_PRECISION);
    }
    if (value.scale() > ModelPricing.MAX_DECIMAL_SCALE) {
      throw invalid(path + " scale must not exceed " + ModelPricing.MAX_DECIMAL_SCALE);
    }
  }

  private static void requireOptionalNonNegativeInt(Integer value, String path) {
    if (value != null && value < 0) {
      throw invalid(path + " must not be negative");
    }
  }

  private static void ensureCondition(Runnable constructor, String path) {
    try {
      constructor.run();
    } catch (IllegalArgumentException error) {
      throw invalid(path + " is invalid: " + error.getMessage(), error);
    }
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
      List<ModelPricing.Override> overrides = new ArrayList<>();
      if (pricing.getOverrides() != null) {
        for (AgentModelPricingOverrideDTO override : pricing.getOverrides()) {
          overrides.add(
              new ModelPricing.Override(
                  override.getId(),
                  toCondition(override.getWhen()),
                  toRatePatch(override.getRates())));
        }
      }
      List<ModelPricing.Multiplier> multipliers = new ArrayList<>();
      if (pricing.getMultipliers() != null) {
        for (AgentModelPricingMultiplierDTO multiplier : pricing.getMultipliers()) {
          multipliers.add(
              new ModelPricing.Multiplier(
                  multiplier.getId(),
                  toCondition(multiplier.getWhen()),
                  toFactors(multiplier.getFactors())));
        }
      }
      return new ModelPricing(toRateSet(pricing.getBase()), overrides, multipliers);
    } catch (IllegalArgumentException error) {
      throw invalid("config.pricing is invalid: " + error.getMessage(), error);
    }
  }

  private static ModelPricing.RateSet toRateSet(AgentModelRateSetDTO rates) {
    if (rates == null) {
      return ModelPricing.RateSet.ZERO;
    }
    return new ModelPricing.RateSet(
        zeroIfNull(rates.getInput()),
        zeroIfNull(rates.getOutput()),
        zeroIfNull(rates.getCacheRead()),
        zeroIfNull(rates.getCacheWrite()),
        toTtlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning());
  }

  private static ModelPricing.RatePatch toRatePatch(AgentModelRatePatchDTO rates) {
    return new ModelPricing.RatePatch(
        rates.getInput(),
        rates.getOutput(),
        rates.getCacheRead(),
        rates.getCacheWrite(),
        rates.getCacheWriteByTtl() == null ? null : toTtlRates(rates.getCacheWriteByTtl()),
        rates.getReasoning(),
        rates.isReasoningSpecified());
  }

  private static ModelPricing.Factors toFactors(AgentModelPricingFactorsDTO factors) {
    return new ModelPricing.Factors(
        factors.getInput(),
        factors.getOutput(),
        factors.getCacheRead(),
        factors.getCacheWrite(),
        factors.getReasoning());
  }

  private static Map<Long, BigDecimal> toTtlRates(Map<String, BigDecimal> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    Map<Long, BigDecimal> result = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
      long ttl = parseTtl(entry.getKey(), "config.pricing.cacheWriteByTtl");
      if (result.put(ttl, entry.getValue()) != null) {
        throw new IllegalArgumentException("cacheWriteByTtl contains duplicate TTL: " + ttl);
      }
    }
    return Map.copyOf(result);
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static ModelPricingCondition toCondition(AgentModelPricingConditionDTO condition) {
    ModelPricingCondition.Context context = null;
    AgentModelPricingContextConditionDTO contextDto = condition.getContext();
    if (contextDto != null) {
      context =
          new ModelPricingCondition.Context(
              contextDto.getGt(), contextDto.getGte(), contextDto.getLt(), contextDto.getLte());
    }
    ModelPricingCondition.DateRange date = null;
    AgentModelPricingDateConditionDTO dateDto = condition.getDate();
    if (dateDto != null) {
      date =
          new ModelPricingCondition.DateRange(
              parseOptionalDate(dateDto.getGte(), "config.pricing.condition.date.gte"),
              parseOptionalDate(dateDto.getLt(), "config.pricing.condition.date.lt"));
    }
    ModelPricingCondition.TimeWindow time = null;
    AgentModelPricingTimeConditionDTO timeDto = condition.getTime();
    if (timeDto != null) {
      TimeBounds bounds =
          parseTimeBounds(timeDto.getStart(), timeDto.getEnd(), "config.pricing.condition.time");
      time =
          new ModelPricingCondition.TimeWindow(
              bounds.start(),
              bounds.end(),
              ZoneId.of(timeDto.getTimezone()),
              timeDto.getWeekdays());
    }
    ModelPricingCondition.RequestBody requestBody = null;
    AgentModelPricingRequestBodyConditionDTO requestBodyDto = condition.getRequestBody();
    if (requestBodyDto != null) {
      requestBody =
          toRequestBody(
              requestBodyDto.getPath(),
              requestBodyDto.getEquals(),
              "config.pricing.condition.requestBody");
    }
    return new ModelPricingCondition(
        context,
        condition.getServiceTier(),
        date,
        time,
        requestBody,
        toConditions(condition.getAll()),
        toConditions(condition.getAny()),
        condition.getNot() == null ? null : toCondition(condition.getNot()));
  }

  private static List<ModelPricingCondition> toConditions(
      List<AgentModelPricingConditionDTO> conditions) {
    if (conditions == null) {
      return null;
    }
    if (conditions.isEmpty()) {
      return List.of();
    }
    List<ModelPricingCondition> result = new ArrayList<>(conditions.size());
    for (AgentModelPricingConditionDTO condition : conditions) {
      result.add(toCondition(condition));
    }
    return result;
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
