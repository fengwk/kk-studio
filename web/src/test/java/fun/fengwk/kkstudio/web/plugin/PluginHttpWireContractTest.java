package fun.fengwk.kkstudio.web.plugin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.NumberSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaElement;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginSecretProjectionDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /api/plugins} 管理面的 HTTP wire 契约 golden：由生产 HTTP Jackson 3 mapper（Boot4 {@code
 * JacksonAutoConfiguration} + 生产 {@code StrictJacksonConfiguration}）自己的 {@link
 * JacksonJsonHttpMessageConverter} 在 MockMvc 上真实产出，因此手写 DTO fixture 证明不了的实际形态（null 是否省略、{@code
 * Instant} 的 token 形态、schema 派生文本的数字精度）都在这里被锁定。
 *
 * <p>golden 是与前端共用的同一份资源（{@code fun/fengwk/kkstudio/web/plugin/plugins-http.json}、{@code
 * .../auth-prepare-http.json}），内容就是真实响应体的 UTF-8 原文；测试只比对已提交的 golden，绝不在运行时改写资源。
 */
class PluginHttpWireContractTest {

  /** 生产 HTTP mapper：Boot4 自动装配 + 生产严格定制，绝不以 plain {@code new JsonMapper()} 代替。 */
  private static final JsonMapper HTTP_MAPPER = StrictHttpJsonMapperTestSupport.get();

  /** 该 mapper 自己的 MVC converter，用来证明响应确实由这份 profile 产出。 */
  private static final JacksonJsonHttpMessageConverter HTTP_CONVERTER =
      new JacksonJsonHttpMessageConverter(HTTP_MAPPER);

  private static final String GOLDEN_DIR = "fun/fengwk/kkstudio/web/plugin/";
  private static final String SECRET_FIELD = "apiKey";
  private static final String SECRET_DESCRIPTION = "plugin API key issued by the provider";
  private static final String DEFAULTS_JSON = "{\"mode\":\"FAST\",\"limits\":{\"maxTokens\":4096}}";

  /** 受控配置契约：嵌套 object/array、integer、number、bool、string enum，外加前端必须按 own key 处理的特殊属性名。 */
  private static final PluginConfigSchema CONFIG_SCHEMA =
      new PluginConfigSchema(
          new InputSchema(
              "alpha plugin configuration", configProperties(), Set.of("endpoint", "mode"), false),
          DEFAULTS_JSON,
          Map.of(SECRET_FIELD, SECRET_DESCRIPTION));

  /**
   * 由生产 {@code SchemaJsonCodec} 派生的 canonical values schema 文本，与 {@code PluginManagementService}
   * 的做法一致。
   */
  private static final String VALUES_SCHEMA_JSON =
      new SchemaJsonCodec().encode(CONFIG_SCHEMA.valuesSchema());

  /**
   * 数字精度探针：{@code 9007199254740993} 超出 double 精度、{@code 1.230000} 带 scale、{@code 1e1000} 不得折叠为
   * Infinity。
   */
  private static final String PROBE_VALUES_JSON =
      CONFIG_SCHEMA.resolveValues(
          "{\"endpoint\":\"https://beta-deep.example.com\","
              + "\"limits\":{\"maxTokens\":9007199254740993,\"temperature\":1.230000},"
              + "\"mode\":\"BALANCED\",\"retries\":[1,2,3],\"verbose\":true,"
              + "\"weight\":1e1000,"
              + "\"__proto__\":\"own-key-probe\",\"constructor\":false}");

  private static final Instant DEEP_PENDING_EXPIRES_AT = Instant.parse("2026-03-01T12:00:00Z");
  private static final Instant DEEP_PENDING_NEXT_REFRESH_AT = Instant.parse("2026-03-01T11:00:00Z");
  private static final Instant DEEP_PENDING_LAST_REFRESHED_AT =
      Instant.parse("2026-02-01T10:00:00Z");
  private static final Instant DEEP_CONNECTED_EXPIRES_AT = Instant.parse("2026-04-30T18:45:30Z");
  private static final Instant DEEP_CONNECTED_NEXT_REFRESH_AT =
      Instant.parse("2026-04-30T17:45:30Z");
  private static final Instant DEEP_CONNECTED_LAST_REFRESHED_AT =
      Instant.parse("2026-04-30T16:45:30Z");
  private static final Instant CHECKED_AT = Instant.parse("2026-03-01T12:00:00Z");

  private PluginManagementService pluginManagementService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    pluginManagementService = mock(PluginManagementService.class);
    StudioPluginController controller = new StudioPluginController(pluginManagementService);
    StudioPluginErrorAdvice errorAdvice = new StudioPluginErrorAdvice(new StudioMessageService());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            // 只注册生产 mapper 的 converter：MVC 没有机会回退到构件自建或 Jackson 2 的副本。
            .setMessageConverters(HTTP_CONVERTER)
            .setControllerAdvice(new ResultResponseBodyAdvice(), errorAdvice)
            .build();
  }

  /** 测试意图：GET /api/plugins 的真实响应体必须与已提交的共用 golden 逐值一致。 */
  @Test
  void listPluginsResponseMatchesCommittedHttpWireGolden() throws Exception {
    when(pluginManagementService.listPlugins()).thenReturn(pluginCatalogue());

    MockHttpServletResponse response = listPlugins();

    assertEquals(200, response.getStatus());
    assertWireMatchesGolden("plugins-http.json", response);
  }

  /** 测试意图：wire 上的 DTO 列表必须与同一 Boot mapper 直接序列化的结果一致，证明 MVC 选中的正是该 mapper。 */
  @Test
  void listPluginsDataIsProducedByTheSameBootJacksonMapper() throws Exception {
    assertSame(HTTP_MAPPER, HTTP_CONVERTER.getMapper());
    List<PluginDTO> catalogue = pluginCatalogue();
    when(pluginManagementService.listPlugins()).thenReturn(catalogue);

    JsonNode data = responseBody(listPlugins()).get("data");

    assertEquals(JsonValues.readTree(HTTP_MAPPER.writeValueAsString(catalogue)), data);
  }

  /** 测试意图：反证 golden 锁定的是生产定制 profile——无任何定制的 plain mapper 会给出 ISO 文本并保留 null。 */
  @Test
  void plainJsonMapperWouldNotReproduceTheProductionHttpProfile() {
    JsonMapper plain = JsonMapper.builder().build();

    JsonNode plainConnected = plain.readTree(plain.writeValueAsString(deepLinkConnectedPlugin()));
    assertTrue(
        plainConnected.get("expiresAt").isTextual(),
        "plain mapper must fall back to ISO-8601 text for Instant");

    JsonNode plainNone = plain.readTree(plain.writeValueAsString(nonePlugin()));
    assertTrue(plainNone.has("authKind"), "plain mapper must keep the null authKind key");
    assertTrue(plainNone.get("authKind").isNull());
  }

  /** 测试意图：None 形态的 authKind/status/token metadata 是真实省略，而能力列表仍是显式空数组。 */
  @Test
  void nonePluginOmitsNullAuthStatusAndTokenMetadata() throws Exception {
    when(pluginManagementService.listPlugins()).thenReturn(pluginCatalogue());

    JsonNode none = responseBody(listPlugins()).get("data").get(0);

    assertEquals("alpha-none", none.get("pluginId").asString());
    for (String omitted :
        List.of(
            "authKind",
            "status",
            "region",
            "expiresAt",
            "nextRefreshAt",
            "lastRefreshedAt",
            "lastRefreshError",
            "applicationError")) {
      assertFalse(none.has(omitted), omitted + " must be omitted when null");
    }
    assertTrue(none.get("harnessToolNames").isArray());
    assertEquals(0, none.get("harnessToolNames").size());
    assertTrue(none.get("canvasFunctionNames").isArray());
    assertEquals(0, none.get("canvasFunctionNames").size());
  }

  /** 测试意图：Instant 元数据是 epoch 秒数值 token 而不是 ISO 文本，坐标是 canonical 十进制字符串 token。 */
  @Test
  void instantMetadataAndCoordinatesKeepTheirWireTokenTypes() throws Exception {
    when(pluginManagementService.getPlugin("beta-deep-connected"))
        .thenReturn(deepLinkConnectedPlugin());

    JsonNode connected = responseBody(getPlugin("beta-deep-connected")).get("data");

    assertInstantToken(connected.get("expiresAt"), DEEP_CONNECTED_EXPIRES_AT);
    assertInstantToken(connected.get("nextRefreshAt"), DEEP_CONNECTED_NEXT_REFRESH_AT);
    assertInstantToken(connected.get("lastRefreshedAt"), DEEP_CONNECTED_LAST_REFRESHED_AT);

    assertEquals(JsonNodeType.STRING, connected.get("desiredEpoch").getNodeType());
    assertEquals("14", connected.get("desiredEpoch").asString());
    assertEquals("14", connected.get("appliedEpoch").asString());
    assertEquals("8", connected.get("desiredRevision").asString());
    assertEquals("8", connected.get("appliedRevision").asString());
  }

  /** 测试意图：保存不等于发布——desired 坐标、applied 坐标与固定去敏说明必须同时出现在同一份响应里。 */
  @Test
  void deepLinkPendingPluginSeparatesDesiredAndAppliedCoordinates() throws Exception {
    when(pluginManagementService.listPlugins()).thenReturn(pluginCatalogue());

    JsonNode pending = responseBody(listPlugins()).get("data").get(3);

    assertEquals("beta-deep-pending", pending.get("pluginId").asString());
    assertEquals("12", pending.get("desiredEpoch").asString());
    assertEquals("11", pending.get("appliedEpoch").asString());
    assertEquals("2", pending.get("desiredRevision").asString());
    assertEquals("1", pending.get("appliedRevision").asString());
    assertEquals(
        PluginConfigurationCommitService.PENDING_APPLICATION_ERROR,
        pending.get("applicationError").asString());
  }

  /** 测试意图：配置投影携带真实 schema 派生的 values schema/默认值，且 valuesJson 保留数字精度与特殊属性名。 */
  @Test
  void configurationCarriesSchemaDerivedValuesDefaultsAndRawNumberScale() throws Exception {
    when(pluginManagementService.getPlugin("beta-deep-connected"))
        .thenReturn(deepLinkConnectedPlugin());

    JsonNode configuration =
        responseBody(getPlugin("beta-deep-connected")).get("data").get("configuration");

    assertEquals("8", configuration.get("revision").asString());

    JsonNode values = JsonValues.readTree(configuration.get("valuesJson").asString());
    assertEquals(9007199254740993L, values.get("limits").get("maxTokens").longValue());
    assertEquals("1.230000", values.get("limits").get("temperature").toString());
    assertEquals("1E+1000", values.get("weight").toString());
    assertEquals("own-key-probe", values.get("__proto__").asString());
    assertFalse(values.get("constructor").booleanValue());
    assertEquals("BALANCED", values.get("mode").asString());
    assertEquals(3, values.get("retries").size());

    JsonNode valuesSchema = JsonValues.readTree(configuration.get("valuesSchema").asString());
    assertEquals("object", valuesSchema.get("type").asString());
    assertFalse(valuesSchema.get("additionalProperties").booleanValue());
    assertTrue(valuesSchema.get("properties").has("__proto__"));
    assertTrue(valuesSchema.get("properties").has("constructor"));
    assertEquals(
        "integer",
        valuesSchema
            .get("properties")
            .get("limits")
            .get("properties")
            .get("maxTokens")
            .get("type")
            .asString());
    assertEquals(
        "number",
        valuesSchema
            .get("properties")
            .get("limits")
            .get("properties")
            .get("temperature")
            .get("type")
            .asString());
    assertEquals("array", valuesSchema.get("properties").get("retries").get("type").asString());
    assertEquals("boolean", valuesSchema.get("properties").get("verbose").get("type").asString());
    assertEquals("string", valuesSchema.get("properties").get("mode").get("type").asString());

    JsonNode defaults = JsonValues.readTree(configuration.get("defaultsJson").asString());
    assertEquals("FAST", defaults.get("mode").asString());
    assertEquals(4096, defaults.get("limits").get("maxTokens").intValue());

    JsonNode secret = configuration.get("secrets").get(SECRET_FIELD);
    assertEquals(SECRET_DESCRIPTION, secret.get("description").asString());
    assertTrue(secret.get("configured").booleanValue());
  }

  /** 测试意图：已保存的 canonical valuesJson 文本被 HTTP 层原样转发，defaultsJson 始终是 schema 的默认值声明。 */
  @Test
  void configurationForwardsStoredValuesJsonAndSchemaDefaultsVerbatim() throws Exception {
    PluginDTO none = nonePlugin();
    when(pluginManagementService.getPlugin("alpha-none")).thenReturn(none);

    JsonNode configuration = responseBody(getPlugin("alpha-none")).get("data").get("configuration");

    assertEquals(
        none.getConfiguration().getValuesJson(), configuration.get("valuesJson").asString());
    assertEquals(CONFIG_SCHEMA.defaultsJson(), configuration.get("defaultsJson").asString());

    JsonNode values = JsonValues.readTree(configuration.get("valuesJson").asString());
    assertEquals("https://alpha-none.example.com", values.get("endpoint").asString());
    assertEquals(2048, values.get("limits").get("maxTokens").intValue());
    assertEquals("FAST", values.get("mode").asString());
    assertTrue(values.has("__proto__"));
    assertTrue(values.has("constructor"));
  }

  /** 测试意图：连接检查能力标记是真实 boolean，既按实例能力取值也绝不在 wire 上省略。 */
  @Test
  void connectionCheckSupportIsProjectedAsARealBoolean() throws Exception {
    when(pluginManagementService.listPlugins()).thenReturn(pluginCatalogue());

    JsonNode catalogue = responseBody(listPlugins()).get("data");

    for (JsonNode plugin : catalogue) {
      assertTrue(plugin.has("connectionCheckSupported"), "capability flag must not be omitted");
      assertEquals(JsonNodeType.BOOLEAN, plugin.get("connectionCheckSupported").getNodeType());
    }
    assertFalse(catalogue.get(0).get("connectionCheckSupported").booleanValue());
    assertTrue(catalogue.get(4).get("connectionCheckSupported").booleanValue());
  }

  /** 测试意图：POST connection-check 的真实响应体（含 no-store 头）必须与已提交的共用 golden 逐值一致。 */
  @Test
  void checkConnectionResponseMatchesCommittedHttpWireGolden() throws Exception {
    when(pluginManagementService.checkConnection("beta-deep-connected", 14L, 8L))
        .thenReturn(connectionCheck("beta-deep-connected", "14", "8"));

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/plugins/beta-deep-connected/connection-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedEpoch\":\"14\",\"expectedRevision\":\"8\"}"))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    assertWireMatchesGolden("connection-check-http.json", response);
  }

  /** 测试意图：检查结果只含类型化维度与已应用坐标，绝不回显远端地址、正文、凭据或异常文本。 */
  @Test
  void checkConnectionBodyNeverCarriesRemoteMaterial() throws Exception {
    when(pluginManagementService.checkConnection("beta-deep-connected", 14L, 8L))
        .thenReturn(connectionCheck("beta-deep-connected", "14", "8"));

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/plugins/beta-deep-connected/connection-check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedEpoch\":\"14\",\"expectedRevision\":\"8\"}"))
            .andReturn()
            .getResponse();

    String body = bodyText(response);
    for (String forbidden :
        List.of("http://", "https://", "Bearer", "apiKey", "instanceId", "credential")) {
      assertFalse(body.contains(forbidden), "connection check body must never expose " + forbidden);
    }
    JsonNode data = JsonValues.readTree(body).get("data");
    assertEquals("REACHABLE", data.get("reachability").asString());
    assertEquals("ACCEPTED", data.get("authentication").asString());
    assertEquals("UNKNOWN", data.get("browserSession").asString());
    assertInstantToken(data.get("checkedAt"), CHECKED_AT);
  }

  /** 测试意图：POST auth/prepare 的响应体（含 no-store 头）必须与已提交的共用 golden 逐值一致。 */
  @Test
  void prepareAuthResponseMatchesCommittedHttpWireGolden() throws Exception {
    PluginAuthPrepareDTO prepare = new PluginAuthPrepareDTO();
    prepare.setAuthAttemptId("attempt-handle-c0ffee-cafe");
    prepare.setLoginUrl("https://auth.example.com/deep-link?region=us-east-1");
    when(pluginManagementService.prepareAuth("beta-deep-connected", "us-east-1"))
        .thenReturn(prepare);

    MockHttpServletResponse response =
        mockMvc
            .perform(
                post("/api/plugins/beta-deep-connected/auth/prepare")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"region\":\"us-east-1\"}"))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    assertWireMatchesGolden("auth-prepare-http.json", response);
  }

  /** 测试意图：golden 里绝不允许出现凭据、密文或回调原文等敏感材料。 */
  @Test
  void httpGoldenNeverCarriesCredentialMaterial() throws Exception {
    when(pluginManagementService.listPlugins()).thenReturn(pluginCatalogue());

    String body = bodyText(listPlugins());

    for (String forbidden :
        List.of(
            "credential",
            "cipher",
            "accessToken",
            "refreshToken",
            "clientSecret",
            "callbackUrl",
            "secretValue")) {
      assertFalse(body.contains(forbidden), "HTTP body must never expose " + forbidden);
    }
  }

  private MockHttpServletResponse listPlugins() throws Exception {
    return mockMvc.perform(get("/api/plugins")).andReturn().getResponse();
  }

  private MockHttpServletResponse getPlugin(String pluginId) throws Exception {
    return mockMvc.perform(get("/api/plugins/" + pluginId)).andReturn().getResponse();
  }

  private JsonNode responseBody(MockHttpServletResponse response) throws Exception {
    assertEquals(200, response.getStatus());
    return JsonValues.readTree(bodyText(response));
  }

  /** 响应体必须按 UTF-8 解码：依赖容器默认编码会让断言随环境漂移。 */
  private static String bodyText(MockHttpServletResponse response) {
    return new String(response.getContentAsByteArray(), UTF_8);
  }

  /** 真实响应体与共用 golden 按 JSON 值比对：{@link JsonValues} 严格读树保留 BigDecimal scale，属性顺序无关。 */
  private static void assertWireMatchesGolden(String goldenName, MockHttpServletResponse response) {
    assertEquals(
        JsonValues.readTree(golden(goldenName)),
        JsonValues.readTree(bodyText(response)),
        goldenName + " must match the live HTTP body");
  }

  private static String golden(String name) {
    ClassPathResource resource = new ClassPathResource(GOLDEN_DIR + name);
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException("shared plugin HTTP golden must exist: " + name, error);
    }
  }

  /** 生产 profile 把 Instant 写成 epoch 秒的数值 token（零纳秒即 9 位小数 scale），绝不是 ISO 文本。 */
  private static void assertInstantToken(JsonNode token, Instant instant) {
    assertEquals(JsonNodeType.NUMBER, token.getNodeType());
    assertTrue(token.isFloatingPointNumber());
    assertEquals(instant.getEpochSecond(), token.longValue());
    assertEquals(instant.getEpochSecond() + ".000000000", token.toString());
  }

  private static Map<String, SchemaElement> configProperties() {
    Map<String, SchemaElement> properties = new LinkedHashMap<>();
    properties.put(
        "__proto__", new StringSchema("frontend must treat this as an own key, not a prototype"));
    properties.put(
        "constructor",
        new BooleanSchema("frontend must treat this as an own key, not a prototype"));
    properties.put("endpoint", new StringSchema("public plugin endpoint"));
    Map<String, SchemaElement> limitProperties = new LinkedHashMap<>();
    limitProperties.put("maxTokens", new IntegerSchema("maximum tokens per request"));
    limitProperties.put("temperature", new NumberSchema("sampling temperature"));
    properties.put(
        "limits", new ObjectSchema("request limits", limitProperties, Set.of("maxTokens"), false));
    properties.put("mode", new EnumSchema("interaction mode", List.of("FAST", "BALANCED")));
    properties.put(
        "retries", new ArraySchema("retry schedule in seconds", new IntegerSchema(null)));
    properties.put("verbose", new BooleanSchema("verbose tracing"));
    properties.put("weight", new NumberSchema("lossless number probe"));
    return properties;
  }

  /** 由真实 {@link PluginConfigSchema} 归一化的完整合法配置：声明字段全部显式给出，因此 golden 不依赖默认值补齐的插入顺序。 */
  private static String configValuesJson(
      String endpoint, String limits, String mode, String retries, boolean verbose, String weight) {
    return CONFIG_SCHEMA.resolveValues(
        "{\"endpoint\":\""
            + endpoint
            + "\",\"limits\":"
            + limits
            + ",\"mode\":\""
            + mode
            + "\",\"retries\":"
            + retries
            + ",\"verbose\":"
            + verbose
            + ",\"weight\":"
            + weight
            + ",\"__proto__\":\"own-key-probe\",\"constructor\":false}");
  }

  private static List<PluginDTO> pluginCatalogue() {
    return List.of(
        nonePlugin(),
        staticKeyConnectedPlugin(),
        staticKeyReauthRequiredPlugin(),
        deepLinkPendingPlugin(),
        deepLinkConnectedPlugin());
  }

  /** None：Plugin 没有认证能力，凭据探测整体不发生，也没有任何已应用能力。 */
  private static PluginDTO nonePlugin() {
    PluginDTO dto = basePlugin("alpha-none", "Alpha None", "0.1.0");
    dto.setConfiguration(
        configuration(
            "1",
            configValuesJson(
                "https://alpha-none.example.com",
                "{\"maxTokens\":2048,\"temperature\":0.25}",
                "FAST",
                "[]",
                false,
                "1"),
            false));
    dto.setDesiredEpoch("1");
    dto.setAppliedEpoch("1");
    dto.setDesiredRevision("1");
    dto.setAppliedRevision("1");
    return dto;
  }

  /** StaticKey 已连接：没有 region 与刷新元数据，秘密只以说明与布尔出现。 */
  private static PluginDTO staticKeyConnectedPlugin() {
    PluginDTO dto = basePlugin("alpha-static", "Alpha Static", "2.4.1");
    dto.setAuthKind(new PluginAuthKindDTO.StaticKey(SECRET_FIELD));
    dto.setStatus("CONNECTED");
    dto.setHarnessToolNames(List.of("alpha-static-tool"));
    dto.setCanvasFunctionNames(List.of("alpha-static-fn"));
    dto.setConfiguration(
        configuration(
            "3",
            configValuesJson(
                "https://alpha-static.example.com",
                "{\"maxTokens\":4096,\"temperature\":0.2}",
                "BALANCED",
                "[1,2]",
                false,
                "2"),
            true));
    dto.setDesiredEpoch("7");
    dto.setAppliedEpoch("7");
    dto.setDesiredRevision("3");
    dto.setAppliedRevision("3");
    return dto;
  }

  /** StaticKey 需要重新授权：状态与有界去敏刷新错误都如实投影，秘密仍只是布尔。 */
  private static PluginDTO staticKeyReauthRequiredPlugin() {
    PluginDTO dto = basePlugin("alpha-static-reauth", "Alpha Static Reauth", "2.4.2");
    dto.setAuthKind(new PluginAuthKindDTO.StaticKey(SECRET_FIELD));
    dto.setStatus("REAUTH_REQUIRED");
    dto.setLastRefreshError("provider rejected the stored static key");
    dto.setHarnessToolNames(List.of("alpha-static-reauth-tool"));
    dto.setCanvasFunctionNames(List.of());
    dto.setConfiguration(
        configuration(
            "5",
            configValuesJson(
                "https://alpha-reauth.example.com",
                "{\"maxTokens\":1000,\"temperature\":0.5}",
                "FAST",
                "[]",
                true,
                "3"),
            false));
    dto.setDesiredEpoch("9");
    dto.setAppliedEpoch("9");
    dto.setDesiredRevision("5");
    dto.setAppliedRevision("5");
    return dto;
  }

  /** DeepLink 已保存但本节点尚未应用：desired 与 applied 坐标分离，并带固定去敏说明。 */
  private static PluginDTO deepLinkPendingPlugin() {
    PluginDTO dto = basePlugin("beta-deep-pending", "Beta Deep Pending", "3.0.0");
    dto.setAuthKind(new PluginAuthKindDTO.DeepLink(List.of("us-east-1", "eu-west-1")));
    dto.setStatus("CONNECTED");
    dto.setRegion("us-east-1");
    dto.setExpiresAt(DEEP_PENDING_EXPIRES_AT);
    dto.setNextRefreshAt(DEEP_PENDING_NEXT_REFRESH_AT);
    dto.setLastRefreshedAt(DEEP_PENDING_LAST_REFRESHED_AT);
    dto.setHarnessToolNames(List.of("beta-deep-pending-tool"));
    dto.setCanvasFunctionNames(List.of("beta-deep-pending-fn"));
    dto.setConfiguration(
        configuration(
            "2",
            configValuesJson(
                "https://beta-pending.example.com",
                "{\"maxTokens\":8192,\"temperature\":0.7}",
                "BALANCED",
                "[5,15,60]",
                false,
                "4"),
            true));
    dto.setDesiredEpoch("12");
    dto.setAppliedEpoch("11");
    dto.setDesiredRevision("2");
    dto.setAppliedRevision("1");
    dto.setApplicationError(PluginConfigurationCommitService.PENDING_APPLICATION_ERROR);
    return dto;
  }

  /** DeepLink 已连接：Instant 元数据与坐标都给出精确值，配置携带数字精度探针。 */
  private static PluginDTO deepLinkConnectedPlugin() {
    PluginDTO dto = basePlugin("beta-deep-connected", "Beta Deep Connected", "3.1.0");
    dto.setAuthKind(
        new PluginAuthKindDTO.DeepLink(List.of("us-east-1", "eu-west-1", "ap-northeast-1")));
    dto.setStatus("CONNECTED");
    dto.setRegion("eu-west-1");
    dto.setExpiresAt(DEEP_CONNECTED_EXPIRES_AT);
    dto.setNextRefreshAt(DEEP_CONNECTED_NEXT_REFRESH_AT);
    dto.setLastRefreshedAt(DEEP_CONNECTED_LAST_REFRESHED_AT);
    dto.setHarnessToolNames(List.of("beta-deep-connected-tool", "beta-deep-connected-extra"));
    dto.setCanvasFunctionNames(List.of("beta-deep-connected-fn"));
    dto.setConfiguration(configuration("8", PROBE_VALUES_JSON, true));
    dto.setConnectionCheckSupported(true);
    dto.setDesiredEpoch("14");
    dto.setAppliedEpoch("14");
    dto.setDesiredRevision("8");
    dto.setAppliedRevision("8");
    return dto;
  }

  /** 显式连接检查的响应 fixture：只有类型化维度与本次已应用坐标。 */
  private static PluginConnectionCheckDTO connectionCheck(
      String pluginId, String appliedEpoch, String appliedRevision) {
    PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
    dto.setPluginId(pluginId);
    dto.setAppliedEpoch(appliedEpoch);
    dto.setAppliedRevision(appliedRevision);
    dto.setCheckedAt(CHECKED_AT);
    dto.setReachability(PluginConnectionCheckDTO.Reachability.REACHABLE);
    dto.setAuthentication(PluginConnectionCheckDTO.Authentication.ACCEPTED);
    dto.setBrowserSession(PluginConnectionCheckDTO.BrowserSession.UNKNOWN);
    return dto;
  }

  private static PluginDTO basePlugin(String pluginId, String name, String version) {
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(pluginId);
    dto.setName(name);
    dto.setVersion(version);
    dto.setHarnessToolNames(List.of());
    dto.setCanvasFunctionNames(List.of());
    return dto;
  }

  private static PluginConfigurationDTO configuration(
      String revision, String valuesJson, boolean secretConfigured) {
    PluginConfigurationDTO configuration = new PluginConfigurationDTO();
    configuration.setRevision(revision);
    configuration.setValuesJson(valuesJson);
    configuration.setValuesSchema(VALUES_SCHEMA_JSON);
    configuration.setDefaultsJson(CONFIG_SCHEMA.defaultsJson());
    PluginSecretProjectionDTO secret = new PluginSecretProjectionDTO();
    secret.setDescription(SECRET_DESCRIPTION);
    secret.setConfigured(secretConfigured);
    configuration.setSecrets(Map.of(SECRET_FIELD, secret));
    return configuration;
  }
}
