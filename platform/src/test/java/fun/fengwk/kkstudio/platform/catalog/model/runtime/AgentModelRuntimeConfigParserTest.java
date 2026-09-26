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

import fun.fengwk.kkstudio.harness.provider.ProviderProtocolOptionsJson;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
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
    assertEquals(PROTOCOL_OPTIONS_JSON, decoded.getVariants().get(0).getProtocolOptionsJson());

    String encoded = parser.encode(decoded);
    ParsedAgentModelConfig reparsed = parser.parse(encoded);
    assertEquals(List.of(expected), reparsed.variants());
    assertEquals(encoded, parser.encode(parser.decode(encoded)));
  }

  /** HTTP 使用的普通 mapper 也只能收取真正的 JSON string token；校验后可存回 config 并作为 string 响应。 */
  @Test
  void acceptsOnlyStringTokenAtHttpDtoBoundary() throws Exception {
    ObjectMapper httpMapper = new ObjectMapper();
    String options =
        "{\"n\":9007199254740993,\"nested\":{\"ratio\":0.12345678901234567890123456789}}";
    AgentModelConfigDTO request =
        httpMapper.readValue(
            withProtocolOptions(validConfig(), options), AgentModelConfigDTO.class);
    String persisted = parser.encode(request);
    AgentModelConfigDTO response = parser.decode(persisted);
    String responseJson = httpMapper.writeValueAsString(response);
    assertEquals(
        options,
        httpMapper
            .readTree(responseJson)
            .path("variants")
            .get(0)
            .path("protocolOptionsJson")
            .textValue());
    assertEquals(
        options,
        httpMapper
            .readValue(responseJson, AgentModelConfigDTO.class)
            .getVariants()
            .get(0)
            .getProtocolOptionsJson());

    for (String token : List.of("1", "false", "{}", "[]")) {
      Exception error =
          assertThrows(
              Exception.class,
              () ->
                  httpMapper.readValue(
                      withProtocolOptionsToken(validConfig(), token), AgentModelConfigDTO.class));
      assertTrue(error.getMessage().contains("protocolOptionsJson must be a JSON string"));
    }
    Exception secret =
        assertThrows(
            Exception.class,
            () ->
                httpMapper.readValue(
                    withProtocolOptionsToken(validConfig(), "{\"secret-option-value\":1}"),
                    AgentModelConfigDTO.class));
    assertFalse(secret.getMessage().contains("secret-option-value"));
  }

  /** null、空白与显式空对象均落到相同的 runtime 默认值和持久化文本。 */
  @Test
  void normalizesEmptyOptionsToEmptyObject() {
    for (String text : List.of("null", "\"\"", "\"  \"", "\"{}\"")) {
      String config = withProtocolOptionsToken(validConfig(), text);
      assertEquals("{}", parser.decode(config).getVariants().get(0).getProtocolOptionsJson());
      assertEquals(
          ProviderProtocolOptions.EMPTY, parser.parse(config).variants().get(0).protocolOptions());
    }
  }

  /** 按原生 JSON 的 UTF-8 字节计数；恰好达到上限可保存，多字节字符造成的溢出必须拒绝。 */
  @Test
  void enforcesProtocolOptionsUtf8Boundary() {
    String exact = "{\"x\":\"" + "a".repeat(ProviderProtocolOptions.MAX_UTF8_BYTES - 8) + "\"}";
    assertEquals(
        exact,
        parser
            .decode(withProtocolOptions(validConfig(), exact))
            .getVariants()
            .get(0)
            .getProtocolOptionsJson());
    String multiByte = "{\"x\":\"" + "测".repeat(22000) + "\"}";
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.decode(withProtocolOptions(validConfig(), multiByte)));
    assertTrue(error.getMessage().contains("must not exceed"));
    assertFalse(error.getMessage().contains("测"));
    assertEquals(null, error.getCause());
  }

  /** 原生协议选项同样受严格约束：非 object、对象内重复键、trailing token 与超限 payload 都必须明确失败，且错误消息绝不回显 选项内容。 */
  @Test
  void rejectsInvalidProtocolOptionsWithoutEchoingPayload() {
    assertInvalid(withProtocolOptions(validConfig(), "[{\"a\":1}]"), ".protocolOptionsJson");
    assertInvalid(withProtocolOptions(validConfig(), "\"not-an-object\""), ".protocolOptionsJson");
    assertInvalid(withProtocolOptions(validConfig(), "{\"a\":1,\"a\":2}"), "protocolOptionsJson");
    assertInvalid(withProtocolOptions(validConfig(), "{} true"), "protocolOptionsJson");
    for (String token : List.of("1", "false", "{}", "[]")) {
      assertInvalid(withProtocolOptionsToken(validConfig(), token), ".protocolOptionsJson");
    }
    assertInvalid(
        withProtocolOptionsToken(validConfig(), "{\"protocolOptions\":{\"x\":1}}"),
        "protocolOptions");

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
    assertEquals(null, malformed.getCause());
  }

  /** 程序化构造的 DTO 也必须走同一严格校验。 */
  @Test
  void rejectsMalformedProtocolOptionsFromTypedConfig() {
    AgentModelConfigDTO config = parser.decode(withProtocolOptions(validConfig(), "{}"));
    config.getVariants().get(0).setProtocolOptionsJson("{\"secret-marker\":1,\"secret-marker\":2}");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(config));
    assertTrue(
        error.getMessage().contains("config.variants[0].protocolOptionsJson"), error.getMessage());
    assertFalse(error.getMessage().contains("secret-marker"));
    assertEquals(null, error.getCause());
  }

  /** 本地 JSON 约定把 Long 序列化为字符串；协议文本在 HTTP 和 config 中均作为字符串传输，运行时才严格解码为数值。 */
  @Test
  void keepsLargeIntegerProtocolOptionsNumericUnderLocalLongToStringConvention() throws Exception {
    ObjectMapper conventionMapper = longToStringMapper();
    assertEquals(
        "{\"revision\":\"1758880000000\"}",
        conventionMapper.writeValueAsString(Map.of("revision", 1758880000000L)));

    AgentModelRuntimeConfigParser conventionParser =
        new AgentModelRuntimeConfigParser(conventionMapper);
    String options =
        "{\"large\":9007199254740993,\"beyond\":9223372036854775808,"
            + "\"nested\":{\"ratio\":0.12345678901234567890123456789,\"exp\":1.234567890123456789e+45}}";
    String config = withProtocolOptions(validConfig(), options);

    AgentModelConfigDTO decoded = conventionParser.decode(config);
    assertEquals(options, decoded.getVariants().get(0).getProtocolOptionsJson());
    String encoded = conventionParser.encode(decoded);
    assertTrue(
        encoded.contains("\"protocolOptionsJson\":\"{\\\"large\\\":9007199254740993"), encoded);
    assertEquals(
        options, conventionParser.decode(encoded).getVariants().get(0).getProtocolOptionsJson());
    String httpResponse = conventionMapper.writeValueAsString(decoded);
    assertEquals(
        options,
        conventionMapper
            .readValue(httpResponse, AgentModelConfigDTO.class)
            .getVariants()
            .get(0)
            .getProtocolOptionsJson());
    String runtime =
        conventionParser.parse(encoded).variants().get(0).protocolOptions().canonicalJson();
    assertTrue(runtime.contains("9007199254740993"), runtime);
    assertTrue(runtime.contains("9223372036854775808"), runtime);
    assertTrue(runtime.contains("0.12345678901234567890123456789"), runtime);
    assertTrue(runtime.contains("1.234567890123456789E+45"), runtime);
    // 协议编码器实际使用的树仍是 JSON 数字；写成 wire 后不能变为字符串或 JS 浮点近似值。
    var wireOptions =
        ProviderProtocolOptionsJson.copyOfOptions(
            conventionParser.parse(encoded).variants().get(0));
    assertEquals(new BigInteger("9007199254740993"), wireOptions.path("large").bigIntegerValue());
    assertEquals(
        new BigInteger("9223372036854775808"), wireOptions.path("beyond").bigIntegerValue());
    assertEquals(
        new BigDecimal("0.12345678901234567890123456789"),
        wireOptions.path("nested").path("ratio").decimalValue());
    String wireJson = new ObjectMapper().writeValueAsString(wireOptions);
    assertTrue(wireJson.contains("\"large\":9007199254740993"), wireJson);
    assertTrue(wireJson.contains("\"beyond\":9223372036854775808"), wireJson);
    assertTrue(wireJson.contains("\"ratio\":0.12345678901234567890123456789"), wireJson);
    assertTrue(wireJson.contains("\"exp\":1.234567890123456789E+45"), wireJson);
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
    try {
      return withProtocolOptionsToken(
          config, new ObjectMapper().writeValueAsString(protocolOptionsJson));
    } catch (IOException error) {
      throw new AssertionError(error);
    }
  }

  private static String withProtocolOptionsToken(String config, String token) {
    return config.replace(
        "\"reasoningEffort\":\"high\"",
        "\"reasoningEffort\":\"high\",\"protocolOptionsJson\":" + token);
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
