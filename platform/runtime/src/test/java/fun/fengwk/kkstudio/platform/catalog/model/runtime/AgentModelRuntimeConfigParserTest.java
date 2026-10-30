package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.ProviderProtocolOptions;
import fun.fengwk.kkstudio.harness.provider.ProviderProtocolOptionsJson;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

class AgentModelRuntimeConfigParserTest {

  private final AgentModelRuntimeConfigParser parser =
      new AgentModelRuntimeConfigParser(JsonMapper.builder().build());

  /** 定价段落按完整定义解析：基础费率与 TTL 价格精确保留，不再存在旧 6 个扁平价格字段。 */
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
    var pricing = parsed.pricing();
    assertEquals(new BigDecimal("1.1"), pricing.base().input());
    assertEquals(new BigDecimal("2.2"), pricing.base().output());
    assertEquals(new BigDecimal("0.3"), pricing.base().cacheRead());
    assertEquals(new BigDecimal("0.4"), pricing.base().cacheWrite());
    assertEquals(new BigDecimal("0.5"), pricing.base().cacheWriteRate(300));
    assertEquals(new BigDecimal("0.4"), pricing.base().cacheWriteRate(3600));
    assertEquals(new BigDecimal("3.6"), pricing.base().reasoning());
    assertEquals(List.of(), pricing.overrides());
    assertEquals(List.of(), pricing.multipliers());
  }

  /** 类型化 DTO 来回往返：decode -> encode -> parse 得到相同的 runtime 快照。 */
  @Test
  void decodeThenEncodeRoundTripsEveryTypedConfigField() {
    AgentModelConfigDTO decoded = parser.decode(validConfig());
    String encoded = parser.encode(decoded);
    var parsed = parser.parse(encoded);
    assertEquals(new BigDecimal("1.1"), parsed.pricing().base().input());
    assertEquals("quality", parsed.defaultVariant());
    assertEquals(List.of(new ModelVariant("quality", "high")), parsed.variants());
  }

  /** 条件与规则按顺序解析；日期允许单侧缺省，显式 reasoning:null 与未声明 reasoning 保持不同语义。 */
  @Test
  void parsesOrderedRulesAndPreservesExplicitReasoningNull() {
    String config =
        validConfig()
            .replace(
                "\"overrides\":[],\"multipliers\":[]",
                "\"overrides\":["
                    + "{\"id\":\"gte-only\",\"when\":{\"date\":{\"gte\":\"2026-08-17\"}},"
                    + "\"rates\":{\"input\":\"2\"}},"
                    + "{\"id\":\"reset-reasoning\",\"when\":{},"
                    + "\"rates\":{\"reasoning\":null}}],"
                    + "\"multipliers\":[{\"id\":\"priority\","
                    + "\"when\":{\"serviceTier\":[\"priority\"]},"
                    + "\"factors\":{\"input\":\"1.5\"}}]");

    var parsed = parser.parse(config);
    var overrides = parsed.pricing().overrides();
    assertEquals(2, overrides.size());
    assertEquals("gte-only", overrides.get(0).id());
    assertEquals(LocalDate.of(2026, 8, 17), overrides.get(0).when().date().gte());
    assertNull(overrides.get(0).when().date().lt());
    assertFalse(overrides.get(0).rates().modifiesReasoning());
    assertTrue(overrides.get(1).rates().modifiesReasoning());
    assertNull(overrides.get(1).rates().reasoning());
    assertEquals("priority", parsed.pricing().multipliers().get(0).id());
    assertEquals(new BigDecimal("1.5"), parsed.pricing().multipliers().get(0).factors().input());

    String encoded = parser.encode(parser.decode(config));
    var roundTripped = parser.parse(encoded).pricing().overrides();
    assertFalse(roundTripped.get(0).rates().modifiesReasoning());
    assertTrue(roundTripped.get(1).rates().modifiesReasoning());
  }

  /** 日期边界同时存在时必须可满足；单侧边界合法。 */
  @Test
  void rejectsInvertedDateRangeAndAcceptsOneSidedBounds() {
    assertInvalid(
        validConfig()
            .replace(
                "\"overrides\":[],\"multipliers\":[]",
                "\"overrides\":[{\"id\":\"bad\",\"when\":{\"date\":"
                    + "{\"gte\":\"2026-08-18\",\"lt\":\"2026-08-18\"}},"
                    + "\"rates\":{\"input\":\"1\"}}],\"multipliers\":[]"),
        "date.gte must be before date.lt");
    assertDoesNotThrow(
        () ->
            parser.parse(
                validConfig()
                    .replace(
                        "\"overrides\":[],\"multipliers\":[]",
                        "\"overrides\":[{\"id\":\"lt-only\",\"when\":"
                            + "{\"date\":{\"lt\":\"2026-08-18\"}},"
                            + "\"rates\":{\"input\":\"1\"}}],\"multipliers\":[]")));
  }

  /** 显式空 any/serviceTier 的 presence 必须跨 decode/encode 保留，不能被折叠为缺省。 */
  @Test
  void preservesExplicitEmptyAnyAndServiceTier() {
    String config =
        validConfig()
            .replace(
                "\"overrides\":[],\"multipliers\":[]",
                "\"overrides\":[{\"id\":\"empty\",\"when\":"
                    + "{\"any\":[],\"serviceTier\":[]},\"rates\":{\"input\":\"1\"}}],"
                    + "\"multipliers\":[]");

    var when = parser.decode(config).getPricing().getOverrides().get(0).getWhen();
    assertNotNull(when.getAny());
    assertTrue(when.getAny().isEmpty());
    assertNotNull(when.getServiceTier());
    assertTrue(when.getServiceTier().isEmpty());

    String encoded = parser.encode(parser.decode(config));
    assertTrue(encoded.contains("\"any\":[]"), encoded);
    assertTrue(encoded.contains("\"serviceTier\":[]"), encoded);

    var parsed = parser.parse(encoded).pricing().overrides().get(0).when();
    assertNotNull(parsed.any());
    assertTrue(parsed.any().isEmpty());
    assertNotNull(parsed.serviceTier());
    assertTrue(parsed.serviceTier().isEmpty());
  }

  /** wire end=24:00 可配任意合法 start；00:00-24:00 保留 canonical wire，22:00-24:00 表达当日终点。 */
  @Test
  void acceptsAllDayAndEndOfDayTimeAndKeepsCanonicalEnd24() {
    String allDay = configWithTime("00:00", "24:00");
    var allDayTime = parser.parse(allDay).pricing().overrides().get(0).when().time();
    assertEquals(LocalTime.MIDNIGHT, allDayTime.start());
    assertEquals(LocalTime.MIDNIGHT, allDayTime.end());
    assertTrue(allDayTime.allDay());
    assertTrue(parser.encode(parser.decode(allDay)).contains("\"end\":\"24:00\""));

    String late = configWithTime("22:00", "24:00");
    var lateTime = parser.parse(late).pricing().overrides().get(0).when().time();
    assertEquals(LocalTime.of(22, 0), lateTime.start());
    assertEquals(LocalTime.MIDNIGHT, lateTime.end());
    assertFalse(lateTime.allDay());
    assertTrue(parser.encode(parser.decode(late)).contains("\"end\":\"24:00\""));
  }

  /** 拒绝 start=24:00，以及把相同起止（含 00:00-00:00）静默当成全天。 */
  @Test
  void rejectsInvalidAllDayTimeForms() {
    assertInvalid(configWithTime("24:00", "24:00"), ".start must be before 24:00");
    assertInvalid(configWithTime("00:00", "00:00"), ".start and end must differ");
    assertInvalid(configWithTime("10:00", "10:00"), ".start and end must differ");
  }

  private String configWithTime(String start, String end) {
    return validConfig()
        .replace(
            "\"overrides\":[],\"multipliers\":[]",
            "\"overrides\":[{\"id\":\"time\",\"when\":{\"time\":{\"start\":\""
                + start
                + "\",\"end\":\""
                + end
                + "\",\"timezone\":\"UTC\",\"weekdays\":[1]}},"
                + "\"rates\":{\"input\":\"2\"}}],\"multipliers\":[]");
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

  /** 厂商自定义 reasoningEffort（如 MAX, xHigh）被正常解析、只去除首尾空白并原样保留大小写，在 round-trip 中保持不变。 */
  @Test
  void acceptsAndRoundTripsCustomReasoningEffort() {
    String maxConfig =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"  MAX \"");
    var parsedMax = parser.parse(maxConfig);
    assertEquals("MAX", parsedMax.variants().get(0).reasoningEffort());

    String xhighConfig =
        validConfig().replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"xHigh\"");
    AgentModelConfigDTO decoded = parser.decode(xhighConfig);
    assertEquals("xHigh", decoded.getVariants().get(0).getReasoningEffort());
    String reencoded = parser.encode(decoded);
    var parsedXhigh = parser.parse(reencoded);
    assertEquals("xHigh", parsedXhigh.variants().get(0).reasoningEffort());
  }

  /** 手工配置的 reasoningEffort 大小写是厂商原生值：HIGH/high 混合大小写在 decode→encode→parse 后原样保留。 */
  @Test
  void preservesReasoningEffortCaseAcrossConfigRoundTrip() {
    for (String effort : List.of("HIGH", "high", "HiGh")) {
      String config =
          validConfig()
              .replace("\"reasoningEffort\":\"high\"", "\"reasoningEffort\":\"" + effort + "\"");
      AgentModelConfigDTO decoded = parser.decode(config);
      assertEquals(effort, decoded.getVariants().get(0).getReasoningEffort());
      String encoded = parser.encode(decoded);
      assertEquals(effort, parser.decode(encoded).getVariants().get(0).getReasoningEffort());
      assertEquals(effort, parser.parse(encoded).variants().get(0).reasoningEffort());
    }
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
    ObjectMapper httpMapper = JsonMapper.builder().build();
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
    String wireJson = JsonMapper.builder().build().writeValueAsString(wireOptions);
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
        validConfig().replace("\"reasoning\":\"3.6\"", "\"reasoning\":\"-1\""),
        "reasoning must not be negative");
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
        validConfig().replace("\"input\":\"1.1\"", "\"input\":\"-1\""),
        "input must not be negative");
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
            + "\"base\":{\"input\":\"0\",\"output\":\"0\",\"cacheRead\":\"0\","
            + "\"cacheWrite\":\"0\",\"cacheWriteByTtl\":{},\"reasoning\":null},"
            + "\"overrides\":[],\"multipliers\":[]}}",
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

  /** 类型化 DTO 形式拒绝损坏的必填子结构；缺失 pricing 按零定义归一，而不是继续拒绝。 */
  @Test
  void validatesTypedConfigAndNormalizesMissingPricing() {
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
    assertEquals(ModelPricing.ZERO, parser.parse(missingPricing).pricing());

    AgentModelConfigDTO negativeInput =
        copy(base, c -> c.getPricing().getBase().setInput(new BigDecimal("-1")));
    IllegalArgumentException multiplierError =
        assertThrows(IllegalArgumentException.class, () -> parser.parse(negativeInput));
    assertTrue(
        multiplierError.getMessage().contains("config.pricing.base.input must not be negative"),
        multiplierError.getMessage());
  }

  /** 缺失 pricing、缺失 base 普通价格与显式全零定义得到相同 canonical DTO 与零成本。 */
  @Test
  void normalizesMissingPricingToCanonicalZeroDefinition() {
    AgentModelConfigDTO missing = parser.decode(configWithoutPricing());
    AgentModelConfigDTO partial = parser.decode(configWithPricing("{\"base\":{}}"));
    AgentModelConfigDTO explicit = parser.decode(configWithPricing(EXPLICIT_ZERO_PRICING));

    assertCanonicalZero(missing);
    assertCanonicalZero(partial);
    assertEquals(explicit.getPricing(), missing.getPricing());
    assertEquals(explicit.getPricing(), partial.getPricing());

    String encoded = parser.encode(missing);
    assertTrue(encoded.contains("\"pricing\":{\"base\":{"), encoded);
    assertTrue(encoded.contains("\"input\":\"0\""), encoded);
    assertTrue(encoded.contains("\"output\":\"0\""), encoded);
    assertTrue(encoded.contains("\"cacheRead\":\"0\""), encoded);
    assertTrue(encoded.contains("\"cacheWrite\":\"0\""), encoded);
    assertTrue(encoded.contains("\"cacheWriteByTtl\":{}"), encoded);
    assertTrue(encoded.contains("\"overrides\":[]"), encoded);
    assertTrue(encoded.contains("\"multipliers\":[]"), encoded);

    ModelUsage usage = new ModelUsage(1_000_000, 1_000_000, 0, 0, Map.of(), null, null);
    assertEquals(
        ModelCost.zero(), ModelCost.calculate(parser.parse(missing).pricing(), usage, null, null));
    assertEquals(
        ModelCost.zero(), ModelCost.calculate(parser.parse(explicit).pricing(), usage, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> parser.decode(configWithPricing("{\"base\":{\"input\":1.1}}")));
  }

  private static void assertCanonicalZero(AgentModelConfigDTO config) {
    var pricing = config.getPricing();
    assertNotNull(pricing);
    assertNotNull(pricing.getBase());
    assertEquals(BigDecimal.ZERO, pricing.getBase().getInput());
    assertEquals(BigDecimal.ZERO, pricing.getBase().getOutput());
    assertEquals(BigDecimal.ZERO, pricing.getBase().getCacheRead());
    assertEquals(BigDecimal.ZERO, pricing.getBase().getCacheWrite());
    assertEquals(Map.of(), pricing.getBase().getCacheWriteByTtl());
    assertNull(pricing.getBase().getReasoning());
    assertNotNull(pricing.getOverrides());
    assertTrue(pricing.getOverrides().isEmpty());
    assertNotNull(pricing.getMultipliers());
    assertTrue(pricing.getMultipliers().isEmpty());
  }

  /** requestBody 的 typed scalar 与精确数字必须跨 decode/encode 保留，显式 null 与缺字段严格区分。 */
  @Test
  void parsesRequestBodyTypedScalarsAndPreservesExactNumbers() {
    String json =
        configWithPricing(
            "{\"base\":{\"input\":\"1\"},\"overrides\":["
                + "{\"id\":\"string\",\"when\":{\"requestBody\":{\"path\":\"/speed\",\"equals\":\"fast\"}},"
                + "\"rates\":{\"input\":\"2\"}},"
                + "{\"id\":\"number\",\"when\":{\"requestBody\":{\"path\":\"/n\",\"equals\":2.000}},"
                + "\"rates\":{\"input\":\"3\"}},"
                + "{\"id\":\"boolean\",\"when\":{\"requestBody\":{\"path\":\"/a~1b/~0mode\",\"equals\":true}},"
                + "\"rates\":{\"input\":\"4\"}},"
                + "{\"id\":\"null\",\"when\":{\"requestBody\":{\"path\":\"/x\",\"equals\":null}},"
                + "\"rates\":{\"input\":\"5\"}}]}");
    var condition = parser.parse(json).pricing().overrides().get(1).when().requestBody();
    assertEquals("/n", condition.path());
    assertEquals(ModelPricingCondition.ScalarType.NUMBER, condition.type());
    assertEquals("2", condition.canonicalValue());
    assertEquals(
        ModelPricingCondition.ScalarType.NULL,
        parser.parse(json).pricing().overrides().get(3).when().requestBody().type());
    String encoded = parser.encode(parser.decode(json));
    assertTrue(encoded.contains("\"equals\":2.000"), encoded);
    assertTrue(encoded.contains("\"equals\":null"), encoded);
    // 非法数字不能进入 runtime，尤其不能先被 double 舍入。
    assertEquals(
        "9007199254740993",
        parser
            .parse(
                configWithPricing(
                    "{\"base\":{\"input\":\"1\"},\"overrides\":[{\"id\":\"n\",\"when\":"
                        + "{\"requestBody\":{\"path\":\"/n\",\"equals\":9007199254740993}},"
                        + "\"rates\":{\"input\":\"2\"}}]}"))
            .pricing()
            .overrides()
            .get(0)
            .when()
            .requestBody()
            .canonicalValue());
  }

  /** requestBody 对象本身、path 与 equals 形状和边界都在唯一 parser 入口校验。 */
  @Test
  void rejectsInvalidRequestBodyConditions() {
    String base =
        "{\"base\":{\"input\":\"1\"},\"overrides\":[{\"id\":\"r\",\"when\":{\"requestBody\":%s},\"rates\":{\"input\":\"2\"}}]}";
    for (String invalid :
        List.of(
            "{\"path\":\"/speed\"}",
            "{\"equals\":\"fast\"}",
            "{\"path\":\"/speed\",\"equals\":[]}",
            "{\"path\":\"/speed\",\"equals\":{}}",
            "{\"path\":\"speed\",\"equals\":\"fast\"}",
            "{\"path\":\"/a~2b\",\"equals\":\"fast\"}",
            "{\"path\":\"/\",\"equals\":\"fast\",\"unknown\":true}",
            "{\"path\":\"/\" + \"a\".repeat(1025) + \"\",\"equals\":\"x\"}")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> parser.parse(configWithPricing(String.format(base, invalid))),
          invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            parser.parse(
                configWithPricing(
                    "{\"base\":{\"input\":\"1\"},\"overrides\":[{\"id\":\"r\",\"when\":"
                        + "{\"requestBody\":{\"path\":\"/n\",\"equals\":1e19}},"
                        + "\"rates\":{\"input\":\"2\"}}]}")));
  }

  private String configWithPricing(String pricingJson) {
    String base = configWithoutPricing();
    return base.substring(0, base.length() - 1) + ",\"pricing\":" + pricingJson + "}";
  }

  private String configWithoutPricing() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + "\"variants\":[{\"id\":\"quality\",\"reasoningEffort\":\"high\"}]}";
  }

  private static AgentModelConfigDTO copy(
      AgentModelConfigDTO source, Consumer<AgentModelConfigDTO> mutator) {
    try {
      String json = JsonMapper.builder().build().writeValueAsString(source);
      AgentModelConfigDTO clone =
          JsonMapper.builder().build().readValue(json, AgentModelConfigDTO.class);
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

  private static final String EXPLICIT_ZERO_PRICING =
      "{\"base\":{\"input\":\"0\",\"output\":\"0\",\"cacheRead\":\"0\",\"cacheWrite\":\"0\","
          + "\"cacheWriteByTtl\":{},\"reasoning\":null},\"overrides\":[],\"multipliers\":[]}";

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
    return withProtocolOptionsToken(
        config, JsonMapper.builder().build().writeValueAsString(protocolOptionsJson));
  }

  private static String withProtocolOptionsToken(String config, String token) {
    return config.replace(
        "\"reasoningEffort\":\"high\"",
        "\"reasoningEffort\":\"high\",\"protocolOptionsJson\":" + token);
  }

  /** 模拟本地真相：convention4j 与 HTTP mapper 都把 Long 序列化为字符串以避免 JS 精度丢失。 */
  private static ObjectMapper longToStringMapper() {
    SimpleModule module = new SimpleModule();
    module.addSerializer(
        Long.class,
        new ValueSerializer<Long>() {
          @Override
          public void serialize(
              Long value, JsonGenerator generator, SerializationContext serializers) {
            generator.writeString(String.valueOf(value));
          }
        });
    return JsonMapper.builder().addModule(module).build();
  }

  private String validConfig() {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\",\"IMAGE\"]},"
        + "\"defaultVariant\":\"quality\","
        + VARIANT_JSON
        + "\"pricing\":{\"base\":{\"input\":\"1.1\",\"output\":\"2.2\","
        + "\"cacheRead\":\"0.3\",\"cacheWrite\":\"0.4\","
        + "\"cacheWriteByTtl\":{\"300\":\"0.5\"},\"reasoning\":\"3.6\"},"
        + "\"overrides\":[],\"multipliers\":[]}}";
  }
}
