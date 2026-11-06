package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.i18n.MessageService;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsAiRuntimeDTO;
import fun.fengwk.kkstudio.web.advice.ProblemAdvice;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class HttpBoundaryConfigurationTest {
  private MockMvc mvc;
  private JsonMapper shared;

  @BeforeEach
  void setup() {
    shared = StrictHttpJsonMapperTestSupport.get();
    HttpBoundaryConfiguration config = new HttpBoundaryConfiguration(shared);
    List<HttpMessageConverter<?>> converters = new ArrayList<>();
    converters.add(new JacksonJsonHttpMessageConverter(shared));
    config.extendMessageConverters(converters);
    var conversionService = new DefaultFormattingConversionService();
    config.addFormatters(conversionService);
    mvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
            .setConversionService(conversionService)
            .setControllerAdvice(
                new HttpBoundaryConfiguration.CanonicalUuidBinding(),
                new RequestBodyRequiredValidationAdvice(shared),
                new ProblemAdvice(new MessageService()))
            .build();
  }

  @Test
  void rejectsInvalidWireTokens() throws Exception {
    // 每一项都只破坏一条请求体规则；detail 只描述路径与错误类别，不回显输入值。
    for (InvalidJson invalid :
        List.of(
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"extra\":0}",
                "extra is not allowed"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"internal\":1}",
                "internal is not allowed"),
            new InvalidJson(
                "{\"text\":7,\"number\":1,\"enabled\":true}", "text has an invalid value"),
            new InvalidJson(
                "{\"text\":true,\"number\":1,\"enabled\":true}", "text has an invalid value"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":\"1\",\"enabled\":true}",
                "number has an invalid value"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1.0,\"enabled\":true}", "number has an invalid value"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":\"\",\"enabled\":true}", "number has an invalid value"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":null,\"enabled\":true}", "number is required"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1,\"enabled\":\"true\"}",
                "enabled has an invalid value"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"text\":\"y\"}",
                "Failed to read request"),
            new InvalidJson(
                "{\"text\":\"x\",\"number\":1,\"enabled\":true} {}", "Failed to read request"),
            new InvalidJson("{ invalid json", "Failed to read request"))) {
      assertReadFailure("/probe", invalid.json(), invalid.expected());
    }
    mvc.perform(
            post("/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\",\"number\":1,\"enabled\":true}"))
        .andExpect(status().isOk());
  }

  /** 嵌套对象与数组元素沿用同一路径语法，错误定位到确切字段。 */
  @Test
  void requestBodyProblemsNameTheJsonPath() throws Exception {
    assertReadFailure(
        "/optional-body",
        "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\","
            + "\"nested\":{\"text\":\"x\",\"number\":\"1\",\"enabled\":true}}",
        "nested.number has an invalid value");
    assertReadFailure(
        "/items-body",
        "{\"items\":[{\"text\":\"x\",\"number\":\"1\",\"enabled\":true}]}",
        "items[0].number has an invalid value");
  }

  @Test
  void strictInputDoesNotChangeInjectedMapper() {
    // 非 HTTP 使用者仍保留原来的 coercion 行为。
    assertEquals(1, shared.readValue("{\"number\":\"1\"}", ProbeDto.class).number);
    JacksonException error =
        assertThrows(
            JacksonException.class,
            () ->
                HttpBoundaryConfiguration.strictHttpMapper(shared)
                    .readValue("{\"number\":\"1\"}", ProbeDto.class));
    assertTrue(error.getMessage().contains("Cannot coerce String value"));
  }

  @Test
  void settingsLongUsesItsDeclaredDecimalStringWireFormat() {
    // Settings 的 GET/PUT 共用十进制字符串，并保持重试名单的严格整数规则。
    JsonMapper mapper = HttpBoundaryConfiguration.strictHttpMapper(shared);
    var parsed =
        mapper.readValue(
            "{\"retryBaseDelayMillis\":\"2501\",\"modelHttpRetryStatusCodes\":[429]}",
            SystemSettingsAiRuntimeDTO.class);
    assertEquals(2501L, parsed.getRetryBaseDelayMillis());
    for (InvalidJson invalid :
        List.of(
            new InvalidJson("2501", "long must be a canonical non-negative decimal string"),
            new InvalidJson("\"02501\"", "long must be a canonical non-negative decimal string"),
            new InvalidJson("\"\"", "long must be a canonical non-negative decimal string"),
            new InvalidJson("\"-1\"", "long must be a canonical non-negative decimal string"),
            new InvalidJson(
                "\"9223372036854775808\"",
                "long must be a canonical non-negative decimal string"))) {
      JacksonException error =
          assertThrows(
              JacksonException.class,
              () ->
                  mapper.readValue(
                      "{\"retryBaseDelayMillis\":" + invalid.json() + "}",
                      SystemSettingsAiRuntimeDTO.class));
      assertTrue(error.getMessage().contains(invalid.expected()));
    }
    JacksonException error =
        assertThrows(
            JacksonException.class,
            () ->
                mapper.readValue(
                    "{\"modelHttpRetryStatusCodes\":[\"429\"]}", SystemSettingsAiRuntimeDTO.class));
    assertTrue(
        error.getMessage().contains("modelHttpRetryStatusCodes must contain only JSON integers"));
  }

  /** 同一解析规则覆盖 boxed/primitive 请求体、路径和查询，不允许 Spring 回退宽松编辑器。 */
  @Test
  void canonicalLongIsEnforcedAtEveryInputBoundary() throws Exception {
    JsonMapper mapper = HttpBoundaryConfiguration.strictHttpMapper(shared);
    for (String value : List.of("0", "1", "9223372036854775807")) {
      long expected = Long.parseLong(value);
      LongBody parsed =
          mapper.readValue(
              "{\"expectedVersion\":\"" + value + "\",\"other\":\"" + value + "\"}",
              LongBody.class);
      assertEquals(expected, parsed.expectedVersion());
      assertEquals(expected, parsed.other());
      mvc.perform(get("/long/" + value).param("value", value)).andExpect(status().isOk());
    }
    for (String value : List.of("", " 0", "0 ", "+1", "-1", "00", "9223372036854775808")) {
      assertThrows(
          JacksonException.class,
          () ->
              mapper.readValue(
                  "{\"expectedVersion\":\"" + value + "\",\"other\":\"0\"}", LongBody.class));
      mvc.perform(get("/long/1").param("value", value))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("validation"));
      if (!value.isEmpty() && !value.contains(" ")) {
        mvc.perform(get("/long/" + value).param("value", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("validation"));
      }
    }
    for (String token : List.of("1", "1.0", "true", "[]", "{}")) {
      assertThrows(
          JacksonException.class,
          () ->
              mapper.readValue(
                  "{\"expectedVersion\":" + token + ",\"other\":\"0\"}", LongBody.class));
    }
  }

  /** 非法 expectedVersion 经真实 HTTP mapper 与 ProblemAdvice 返回 validation，而非服务异常。 */
  @Test
  void malformedExpectedVersionIsValidationProblem() throws Exception {
    HttpBoundaryConfiguration config = new HttpBoundaryConfiguration(shared);
    List<HttpMessageConverter<?>> converters = new ArrayList<>();
    converters.add(new JacksonJsonHttpMessageConverter(shared));
    config.extendMessageConverters(converters);
    MockMvc problemMvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
            .setControllerAdvice(new ProblemAdvice(new MessageService()))
            .build();
    problemMvc
        .perform(
            post("/long-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\" 0\",\"other\":\"0\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));
  }

  @Test
  void emptyStringsNeverBecomeNullForTypedProperties() {
    // 空串不能绕过可空字段的 wire 类型检查，UUID 与对象都不是空字符串。
    JsonMapper mapper = HttpBoundaryConfiguration.strictHttpMapper(shared);
    for (InvalidJson invalid :
        List.of(
            new InvalidJson("{\"id\":\"\"}", "UUID must be a canonical lowercase string"),
            new InvalidJson("{\"nested\":\"\"}", "Cannot coerce empty String"))) {
      JacksonException error =
          assertThrows(
              JacksonException.class, () -> mapper.readValue(invalid.json(), OptionalTypes.class));
      assertTrue(error.getMessage().contains(invalid.expected()));
    }
  }

  @Test
  void pathAndQueryAcceptOnlyCanonicalLowercaseUuid() throws Exception {
    String canonical = "123e4567-e89b-12d3-a456-426614174000";
    mvc.perform(get("/uuid/" + canonical).param("id", canonical)).andExpect(status().isOk());
    for (String invalid : List.of(canonical.toUpperCase(), "1-1-1-1-1", "invalid")) {
      assertUuidBindingFailure(get("/uuid/" + invalid).param("id", canonical), "path");
      assertUuidBindingFailure(get("/uuid/" + canonical).param("id", invalid), "id");
    }
    for (String invalid : List.of("", " " + canonical, canonical + " ")) {
      assertUuidBindingFailure(get("/uuid/" + canonical).param("id", invalid), "id");
    }
  }

  private void assertUuidBindingFailure(MockHttpServletRequestBuilder request, String name)
      throws Exception {
    var result = mvc.perform(request).andExpect(status().isBadRequest()).andReturn();
    var error =
        assertInstanceOf(MethodArgumentTypeMismatchException.class, result.getResolvedException());
    assertEquals(name, error.getName());
    Throwable cause = error;
    while (cause != null
        && (cause.getMessage() == null || !cause.getMessage().contains("canonical UUID"))) {
      cause = cause.getCause();
    }
    assertTrue(cause != null, "UUID parser must reject the noncanonical value");
  }

  @Test
  void requestBodyUuidRequiresCanonicalLowercaseText() throws Exception {
    // HTTP UUID 只接受字符串规范形式，拒绝 Java 的短写和 Jackson 的 Base64 形态。
    String canonical = "123e4567-e89b-12d3-a456-426614174000";
    mvc.perform(
            post("/uuid-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + canonical + "\"}"))
        .andExpect(status().isOk());
    for (String value :
        List.of(
            "\"" + canonical.toUpperCase() + "\"",
            "\"1-1-1-1-1\"",
            "\"Ej5FZ+ibEtOkVkJmFBdAAA==\"",
            "\" " + canonical + "\"",
            "\"" + canonical + " \"",
            "\"\"",
            "\"not-a-uuid\"",
            "1",
            "true",
            "[]",
            "{}")) {
      assertReadFailure("/uuid-body", "{\"id\":" + value + "}", "id has an invalid value");
    }
  }

  private void assertReadFailure(String path, String json, String detail) throws Exception {
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value(detail));
  }

  private record InvalidJson(String json, String expected) {}

  static class ProbeDto {
    public String text;
    public int number;
    public boolean enabled;
    @JsonIgnore public int internal;
  }

  static class OptionalTypes {
    public UUID id;
    public ProbeDto nested;
  }

  record ItemsBody(List<ProbeDto> items) {}

  record UuidBody(UUID id) {}

  record LongBody(Long expectedVersion, long other) {}

  @RestController
  static class Probe {
    @PostMapping("/probe")
    void post(@RequestBody ProbeDto dto) {}

    @PostMapping("/optional-body")
    void optionalBody(@RequestBody OptionalTypes body) {}

    @PostMapping("/items-body")
    void itemsBody(@RequestBody ItemsBody body) {}

    @PostMapping("/uuid-body")
    void uuidBody(@RequestBody UuidBody dto) {}

    @GetMapping("/uuid/{path}")
    void uuid(@PathVariable("path") UUID path, @RequestParam("id") UUID id) {}

    @GetMapping("/long/{path}")
    void longParameters(@PathVariable("path") long path, @RequestParam("value") Long value) {}

    @PostMapping("/long-body")
    void longBody(@RequestBody LongBody body) {}
  }
}
