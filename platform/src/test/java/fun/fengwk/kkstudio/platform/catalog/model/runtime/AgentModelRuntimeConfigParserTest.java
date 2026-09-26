package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

class AgentModelRuntimeConfigParserTest {

  private final AgentModelRuntimeConfigParser parser =
      new AgentModelRuntimeConfigParser(new ObjectMapper());

  /** 每个可执行字段（含 6 个独立价格）都经严格解析保留下来；variant 只保留 id、reasoningEffort 与原生协议选项。 */
  @Test
  void parsesCompleteRuntimeModelWithoutTokenBasedPriceInference() {
    var parsed = parser.parse(validConfig());

    assertEquals(128000L, parsed.contextWindow());
    assertEquals(8192L, parsed.maxOutputTokens());
    assertEquals(
        Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE), parsed.inputModalities());
    assertTrue(parsed.tools());
    assertTrue(parsed.reasoning());
    assertEquals("quality", parsed.defaultVariant());
    assertEquals(List.of(new ModelVariant("quality", "high")), parsed.variants());
    assertEquals("USD", parsed.pricing().currency());
    assertEquals("batch", parsed.pricing().pricingTier());
    assertEquals("priority", parsed.pricing().serviceTier());
    assertEquals(new BigDecimal("1.25"), parsed.pricing().serviceTierMultiplier());
    assertEquals("2026-07-16", parsed.pricing().version());
    assertEquals(new BigDecimal("1.1"), parsed.pricing().inputPerMillionTokens());
    assertEquals(new BigDecimal("2.2"), parsed.pricing().outputPerMillionTokens());
    assertEquals(new BigDecimal("0.3"), parsed.pricing().cacheReadPerMillionTokens());
    assertEquals(new BigDecimal("0.4"), parsed.pricing().cacheWritePerMillionTokens());
    assertEquals(new BigDecimal("0.5"), parsed.pricing().cacheWriteLongPerMillionTokens());
    assertEquals(new BigDecimal("3.6"), parsed.pricing().reasoningPerMillionTokens());
  }

  /** 类型化 DTO 来回往返：decode -> encode -> parse 得到相同的 runtime 快照。 */
  @Test
  void decodeThenEncodeRoundTripsEveryTypedConfigField() {
    AgentModelConfigDTO decoded = parser.decode(validConfig());
    String encoded = parser.encode(decoded);
    var parsed = parser.parse(encoded);
    assertEquals("USD", parsed.pricing().currency());
    assertEquals("quality", parsed.defaultVariant());
    assertEquals(List.of(new ModelVariant("quality", "high")), parsed.variants());
  }

  /** off 是显式关闭语义，绝不与 null（协议默认）静默映射。 */
  @Test
  void keepsOffReasoningEffortAsExplicitDisable() {
    String config =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"off\"");
    var parsed = parser.parse(config);
    assertEquals("off", parsed.variants().get(0).reasoningEffort());
    assertTrue(parsed.variants().get(0).reasoningOff());
  }

  /** 厂商自定义 reasoningEffort（如 max, xhigh）被正常解析、归一化并在 round-trip 中保留。 */
  @Test
  void acceptsAndRoundTripsCustomReasoningEffort() {
    String maxConfig =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"  MAX \"");
    var parsedMax = parser.parse(maxConfig);
    assertEquals("max", parsedMax.variants().get(0).reasoningEffort());

    String xhighConfig =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"xHigh\"");
    AgentModelConfigDTO decoded = parser.decode(xhighConfig);
    assertEquals("xhigh", decoded.getVariants().get(0).getReasoningEffort());
    String reencoded = parser.encode(decoded);
    var parsedXhigh = parser.parse(reencoded);
    assertEquals("xhigh", parsedXhigh.variants().get(0).reasoningEffort());
  }

  /** 未声明 reasoningEffort 的 variant 保持 null，与显式 off 是不同语义；未声明原生选项等价于空 object。 */
  @Test
  void keepsAbsentReasoningEffortAsProtocolDefault() {
    String config = validConfig().replace(",\"reasoningEffort\":\"high\"", "");
    var parsed = parser.parse(config);
    assertEquals(new ModelVariant("quality"), parsed.variants().get(0));
    assertFalse(parsed.variants().get(0).reasoningOff());
    assertTrue(parsed.variants().get(0).protocolOptions().isEmpty());
    assertEquals(ProviderProtocolOptions.EMPTY, parsed.variants().get(0).protocolOptions());
  }

  /**
   * 厂商原生协议选项必须经 public config API 无损往返并进入 runtime variant：decode → encode → parse 后仍是同一 canonical
   * object，且协议编码器读到的选项包含全部厂商字段。
   */
  @Test
  void roundTripsProtocolOptionsFromApiConfigIntoRuntimeVariant() {
    String config = withProtocolOptions(validConfig(), PROTOCOL_OPTIONS_JSON);
    ModelVariant expected =
        new ModelVariant("quality", "high", new ProviderProtocolOptions(PROTOCOL_OPTIONS_JSON));

    ParsedAgentModelConfig parsed = parser.parse(config);
    assertEquals(List.of(expected), parsed.variants());
    assertEquals(PROTOCOL_OPTIONS_JSON, parsed.variants().get(0).protocolOptions().canonicalJson());

    AgentModelConfigDTO decoded = parser.decode(config);
    assertEquals(
        Map.of("user_id", "u-1"),
        decoded.getVariants().get(0).getProtocolOptions().get("metadata"));

    String encoded = parser.encode(decoded);
    ParsedAgentModelConfig reparsed = parser.parse(encoded);
    assertEquals(List.of(expected), reparsed.variants());
    assertEquals(encoded, parser.encode(parser.decode(encoded)));
  }

  /** 原生协议选项同样受严格约束：非 object、对象内重复键、trailing token 与超限 payload 都必须明确失败，且错误消息绝不回显 选项内容。 */
  @Test
  void rejectsInvalidProtocolOptionsWithoutEchoingPayload() {
    assertInvalid(withProtocolOptions(validConfig(), "[{\"a\":1}]"), ".protocolOptions");
    assertInvalid(withProtocolOptions(validConfig(), "\"not-an-object\""), ".protocolOptions");
    assertInvalid(withProtocolOptions(validConfig(), "{\"a\":1,\"a\":2}"), "protocolOptions");

    String marker = "top-secret-marker";
    IllegalArgumentException oversized =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parser.parse(
                    withProtocolOptions(
                        validConfig(),
                        "{\"a\":\""
                            + marker
                            + "b".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES)
                            + "\"}")));
    assertTrue(oversized.getMessage().contains("must not exceed"), oversized.getMessage());
    assertFalse(oversized.getMessage().contains(marker), oversized.getMessage());

    IllegalArgumentException malformed =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                parser.parse(withProtocolOptions(validConfig(), "{\"a\":\"" + marker + "\"} {}")));
    assertFalse(malformed.getMessage().contains(marker), malformed.getMessage());
  }

  /** 程序化构造的 DTO 也必须走同一校验：无法序列化为 JSON 的值不得进入持久化。 */
  @Test
  void rejectsNonJsonProtocolOptionsValuesFromTypedConfig() {
    AgentModelConfigDTO config = parser.decode(withProtocolOptions(validConfig(), "{}"));
    Map<String, Object> invalid = new LinkedHashMap<>();
    invalid.put("bad", new Object());
    config.getVariants().get(0).setProtocolOptions(invalid);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(config));
    assertTrue(
        error.getMessage().contains("config.variants[0].protocolOptions"), error.getMessage());
  }

  /**
   * 本地 JSON 约定（convention4j 与 HTTP mapper）把 Long 序列化为字符串以避免 JS 精度丢失；厂商原生选项必须不受该约定影响， 因此解析时把整数归一化为
   * BigInteger，encode 之后仍是 JSON 数值。
   */
  @Test
  void keepsLargeIntegerProtocolOptionsNumericUnderLocalLongToStringConvention() throws Exception {
    ObjectMapper conventionMapper = longToStringMapper();
    assertEquals(
        "{\"revision\":\"1758880000000\"}",
        conventionMapper.writeValueAsString(Map.of("revision", 1758880000000L)));

    AgentModelRuntimeConfigParser conventionParser =
        new AgentModelRuntimeConfigParser(conventionMapper);
    String config =
        withProtocolOptions(validConfig(), "{\"revision\":1758880000000,\"ratio\":0.5}");

    AgentModelConfigDTO decoded = conventionParser.decode(config);
    // 整数与小数分别使用 BigInteger/BigDecimal，既不命中 Long 字符串化约定，也不经过二进制浮点数。
    assertEquals(
        List.of(BigInteger.valueOf(1758880000000L), new BigDecimal("0.5")),
        List.of(
            decoded.getVariants().get(0).getProtocolOptions().get("revision"),
            decoded.getVariants().get(0).getProtocolOptions().get("ratio")));

    String encoded = conventionParser.encode(decoded);
    assertTrue(encoded.contains("\"revision\":1758880000000"), encoded);
    assertFalse(encoded.contains("\"revision\":\"1758880000000\""), encoded);
    assertEquals(
        "{\"revision\":1758880000000,\"ratio\":0.5}",
        conventionParser.parse(encoded).variants().get(0).protocolOptions().canonicalJson());
  }

  /** Untyped protocolOptions 必须用十进制任意精度解析，不能先落入 Double 后静默损失厂商参数精度。 */
  @Test
  void preservesHighPrecisionDecimalProtocolOptions() {
    String decimal = "0.12345678901234567890123456789";
    String config = withProtocolOptions(validConfig(), "{\"threshold\":" + decimal + "}");

    AgentModelConfigDTO decoded = parser.decode(config);
    Object threshold = decoded.getVariants().get(0).getProtocolOptions().get("threshold");

    assertEquals(new BigDecimal(decimal), threshold);
    assertEquals(
        "{\"threshold\":" + decimal + "}",
        parser.parse(parser.encode(decoded)).variants().get(0).protocolOptions().canonicalJson());
  }

  /** 禁用的 reasoning 在描述符上以 falsy 的 tools/reasoning 布尔形式呈现。 */
  @Test
  void exposesReasoningAndToolsAsBooleans() {
    String config =
        validConfig()
            .replace("\"reasoning\":true", "\"reasoning\":false")
            .replace(",\"reasoningEffort\":\"high\"", "");
    var parsed = parser.parse(config);
    assertFalse(parsed.reasoning());
    assertTrue(parsed.tools());
  }

  /** reasoning=false 但 variant 仍携带 reasoningEffort 是自相矛盾配置，必须拒绝。 */
  @Test
  void rejectsReasoningEffortWhenReasoningAbilityIsDisabled() {
    assertInvalid(
        validConfig().replace("\"reasoning\":true", "\"reasoning\":false"),
        "reasoningEffort must be null when config.abilities.reasoning is false");
    assertInvalid(
        validConfig()
            .replace("\"reasoning\":true", "\"reasoning\":false")
            .replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"off\""),
        "reasoningEffort must be null when config.abilities.reasoning is false");
  }

  /** reasoning=false 且全部 variant 的 reasoningEffort 为 null（协议默认）是合法配置。 */
  @Test
  void acceptsNullReasoningEffortWhenReasoningAbilityIsDisabled() {
    String config =
        validConfig()
            .replace("\"reasoning\":true", "\"reasoning\":false")
            .replace(",\"reasoningEffort\":\"high\"", "");
    var parsed = parser.parse(config);
    assertFalse(parsed.reasoning());
    assertEquals(new ModelVariant("quality"), parsed.variants().get(0));
  }

  /** 缺失字段、错误的 JSON 类型、未知枚举与无效 variant 都明确失败。 */
  @Test
  void rejectsIncompleteOrMistypedExecutableConfiguration() {
    assertInvalid("{}", "config.limit is required");
    assertInvalid(validConfig().replace("128000", "\"128000\""), "limit.context");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[]"), "inputModalities must not be empty");
    assertInvalid(
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"   \""),
        "reasoningEffort must not be blank");
    assertInvalid(
        validConfig()
            .replace("\"reasoningPerMillionTokens\":3.6", "\"reasoningPerMillionTokens\":null"),
        "reasoningPerMillionTokens");
    assertInvalid(
        validConfig().replace("[\"TEXT\",\"IMAGE\"]", "[\"UNKNOWN\"]"), "inputModalities[0]");
  }

  /** 结构和数值边界会拒绝任何可能导致模型不可用的形态。 */
  @Test
  void rejectsInvalidVariantPricingAndJsonBoundaries() {
    assertInvalid("", "config must not be blank");
    assertInvalid("not-json", "config must be valid JSON");
    assertInvalid("[]", "config is invalid");
    assertInvalid(
        validConfig().replace("\"output\":8192", "\"output\":128001"),
        "must not exceed limit.context");
    assertInvalid(validConfig().replace(VARIANT_JSON, ""), "config.variants must not be empty");
    assertInvalid(validConfig().replace("\"variants\":[{", "\"variants\":[1,{"), "variants[0]");
    assertInvalid(
        validConfig()
            .replace(
                "\"reasoningEffort\":\"high\"}]",
                "\"reasoningEffort\":\"high\"},{" + "\"id\":\"quality\"}]"),
        "duplicate id");
    assertInvalid(
        validConfig().replace("\"defaultVariant\":\"quality\"", "\"defaultVariant\":\"missing\""),
        "defaultVariant must match");
    assertInvalid(
        validConfig().replace("\"serviceTierMultiplier\":1.25", "\"serviceTierMultiplier\":0"),
        "serviceTierMultiplier must be positive");
    assertInvalid(
        validConfig().replace("\"inputPerMillionTokens\":1.1", "\"inputPerMillionTokens\":-1"),
        "inputPerMillionTokens must not be negative");
    assertInvalid(
        validConfig()
            .replace(
                "\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"" + "a".repeat(65) + "\""),
        "reasoningEffort must not exceed 64 characters");
  }

  /**
   * variant 只保留 id 与 reasoningEffort：任何已删除的采样、penalty、stopSequences 与单次输出上限都必须作为未知字段被
   * 拒绝，绝不作为兼容路径被静默忽略。
   */
  @Test
  void rejectsEveryRemovedVariantField() {
    for (String removed : REMOVED_VARIANT_FIELDS) {
      String config =
          validConfig()
              .replace(
                  "\"id\":\"quality\",\"reasoningEffort\":\"high\"",
                  "\"id\":\"quality\"," + removed + "\"reasoningEffort\":\"high\"");
      assertInvalid(config, "config.variants[0]." + removed.substring(1, removed.indexOf('"', 1)));
    }
  }

  /** 已持久化的 JSON 永远不能被静默修复：null/空白/畸形结构应大声抛出。 */
  @Test
  void rejectsInvalidPersistedJsonShapes() {
    assertInvalid("", "config must not be blank");
    assertInvalid("   ", "config must not be blank");
    assertInvalid("not-json", "config must be valid JSON");
    assertInvalid("null", "config is required");
    assertInvalid("[1,2,3]", "config is invalid");
    assertInvalid("{\"limit\":{}}", "config.limit.context");
    assertInvalid(
        "{\"limit\":{\"context\":1,\"output\":1},\"abilities\":{\"tools\":true,"
            + "\"reasoning\":true,\"inputModalities\":[\"TEXT\"]},"
            + "\"defaultVariant\":\"x\",\"variants\":[],\"pricing\":{"
            + "\"currency\":\"USD\",\"pricingTier\":\"x\",\"serviceTier\":\"x\","
            + "\"serviceTierMultiplier\":1,\"version\":\"v\",\"inputPerMillionTokens\":0,"
            + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
            + "\"cacheWritePerMillionTokens\":0,\"cacheWriteLongPerMillionTokens\":0,"
            + "\"reasoningPerMillionTokens\":0}}",
        "config.variants must not be empty");
  }

  /** 持久化配置解码拒绝未知字段、标量强转与尾随文档。 */
  @Test
  void rejectsUnknownFieldsCoercionAndTrailingJson() {
    assertInvalid(
        validConfig().replace("{\"limit\"", "{\"unknown\":true,\"limit\""), "config.unknown");
    assertInvalid(
        validConfig().replace("\"context\":128000", "\"context\":128000.5"), "limit.context");
    assertInvalid(validConfig() + " {}", "config is invalid");
    assertInvalid(
        validConfig().replace("\"id\":\"quality\"", "\"id\":\" quality \""),
        "surrounding whitespace");
  }

  /** 类型化 DTO 形式拒绝 null 和损坏的子结构；部分子结构会以明确方式失败。 */
  @Test
  void rejectsInvalidTypedConfigDirectly() {
    AgentModelConfigDTO base = parser.decode(validConfig());
    assertNotNull(base);
    AgentModelConfigDTO missingLimit = copy(base, c -> c.setLimit(null));
    IllegalArgumentException limitError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingLimit));
    assertTrue(
        limitError.getMessage().contains("config.limit is required"), limitError.getMessage());

    AgentModelConfigDTO missingAbilities = copy(base, c -> c.setAbilities(null));
    IllegalArgumentException abilitiesError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingAbilities));
    assertTrue(
        abilitiesError.getMessage().contains("config.abilities is required"),
        abilitiesError.getMessage());

    AgentModelConfigDTO missingPricing = copy(base, c -> c.setPricing(null));
    IllegalArgumentException pricingError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(missingPricing));
    assertTrue(
        pricingError.getMessage().contains("config.pricing is required"),
        pricingError.getMessage());

    AgentModelConfigDTO zeroMultiplier =
        copy(base, c -> c.getPricing().setServiceTierMultiplier(BigDecimal.ZERO));
    IllegalArgumentException multiplierError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(zeroMultiplier));
    assertTrue(
        multiplierError
            .getMessage()
            .contains("config.pricing.serviceTierMultiplier must be positive"),
        multiplierError.getMessage());
  }

  private static AgentModelConfigDTO copy(
      AgentModelConfigDTO source, Consumer<AgentModelConfigDTO> mutator) {
    try {
      String json = new ObjectMapper().writeValueAsString(source);
      AgentModelConfigDTO clone = new ObjectMapper().readValue(json, AgentModelConfigDTO.class);
      mutator.accept(clone);
      return clone;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private void assertInvalid(String config, String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(config));
    assertTrue(error.getMessage().contains(message), error.getMessage());
  }

  /** canonical variant wire：只含 id 与 reasoningEffort。 */
  private static final String VARIANT_JSON =
      "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\"}],";

  /** 厂商原生协议选项 fixture：覆盖嵌套 object、数组、布尔、小数与超出 Integer 范围的整数。 */
  private static final String PROTOCOL_OPTIONS_JSON =
      "{\"metadata\":{\"user_id\":\"u-1\"},\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":4096},"
          + "\"ratio\":0.5,\"revision\":1758880000000,\"flags\":[true,false]}";

  /** 已从 variant 契约删除的字段：必须作为未知字段被严格拒绝。 */
  private static final List<String> REMOVED_VARIANT_FIELDS =
      List.of(
          "\"maxOutputTokens\":4096,",
          "\"temperature\":0.4,",
          "\"topP\":0.8,",
          "\"topK\":20,",
          "\"frequencyPenalty\":0.1,",
          "\"presencePenalty\":0.2,",
          "\"stopSequences\":[\"done\"],");

  private static String withProtocolOptions(String config, String protocolOptionsJson) {
    return config.replace(
        "\"reasoningEffort\":\"high\"",
        "\"reasoningEffort\":\"high\",\"protocolOptions\":" + protocolOptionsJson);
  }

  /** 模拟本地真相：convention4j 与 HTTP mapper 都把 Long 序列化为字符串以避免 JS 精度丢失。 */
  private static ObjectMapper longToStringMapper() {
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addSerializer(
        Long.class,
        new JsonSerializer<Long>() {
          @Override
          public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers)
              throws IOException {
            generator.writeString(String.valueOf(value));
          }
        });
    mapper.registerModule(module);
    return mapper;
  }

  private String validConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + VARIANT_JSON
        + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"batch\","
        + "\"serviceTier\":\"priority\",\"serviceTierMultiplier\":1.25,"
        + "\"version\":\"2026-07-16\",\"inputPerMillionTokens\":1.1,"
        + "\"outputPerMillionTokens\":2.2,\"cacheReadPerMillionTokens\":0.3,"
        + "\"cacheWritePerMillionTokens\":0.4,"
        + "\"cacheWriteLongPerMillionTokens\":0.5,"
        + "\"reasoningPerMillionTokens\":3.6}}";
  }
}
