package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAiRuntimeDTO;

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
            .setControllerAdvice(new HttpBoundaryConfiguration.CanonicalUuidBinding())
            .build();
  }

  @Test
  void rejectsInvalidWireTokens() throws Exception {
    // 每一项都只破坏一条请求体规则。
    for (String json :
        List.of(
            "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"extra\":0}",
            "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"internal\":1}",
            "{\"text\":7,\"number\":1,\"enabled\":true}",
            "{\"text\":true,\"number\":1,\"enabled\":true}",
            "{\"text\":\"x\",\"number\":\"1\",\"enabled\":true}",
            "{\"text\":\"x\",\"number\":1.0,\"enabled\":true}",
            "{\"text\":\"x\",\"number\":\"\",\"enabled\":true}",
            "{\"text\":\"x\",\"number\":null,\"enabled\":true}",
            "{\"text\":\"x\",\"number\":1,\"enabled\":\"true\"}",
            "{\"text\":\"x\",\"number\":1,\"enabled\":true,\"text\":\"y\"}",
            "{\"text\":\"x\",\"number\":1,\"enabled\":true} {}")) {
      mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content(json))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/probe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\",\"number\":1,\"enabled\":true}"))
        .andExpect(status().isOk());
  }

  @Test
  void strictInputDoesNotChangeInjectedMapper() {
    // 非 HTTP 使用者仍保留原来的 coercion 行为。
    assertEquals(1, shared.readValue("{\"number\":\"1\"}", ProbeDto.class).number);
    assertThrows(
        RuntimeException.class,
        () ->
            HttpBoundaryConfiguration.strictHttpMapper(shared)
                .readValue("{\"number\":\"1\"}", ProbeDto.class));
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
    for (String value : List.of("2501", "\"02501\"", "\"\"", "\"-1\"", "\"9223372036854775808\"")) {
      assertThrows(
          RuntimeException.class,
          () ->
              mapper.readValue(
                  "{\"retryBaseDelayMillis\":" + value + "}", SystemSettingsAiRuntimeDTO.class));
    }
    assertThrows(
        RuntimeException.class,
        () ->
            mapper.readValue(
                "{\"modelHttpRetryStatusCodes\":[\"429\"]}", SystemSettingsAiRuntimeDTO.class));
  }

  @Test
  void emptyStringsNeverBecomeNullForTypedProperties() {
    // 空串不能绕过可空字段的 wire 类型检查，UUID 与对象都不是空字符串。
    JsonMapper mapper = HttpBoundaryConfiguration.strictHttpMapper(shared);
    for (String json : List.of("{\"id\":\"\"}", "{\"nested\":\"\"}")) {
      assertThrows(RuntimeException.class, () -> mapper.readValue(json, OptionalTypes.class));
    }
  }

  @Test
  void pathAndQueryAcceptOnlyCanonicalLowercaseUuid() throws Exception {
    String canonical = "123e4567-e89b-12d3-a456-426614174000";
    mvc.perform(get("/uuid/" + canonical).param("id", canonical)).andExpect(status().isOk());
    for (String invalid : List.of(canonical.toUpperCase(), "1-1-1-1-1", "invalid")) {
      mvc.perform(get("/uuid/" + invalid).param("id", canonical))
          .andExpect(status().isBadRequest());
      mvc.perform(get("/uuid/" + canonical).param("id", invalid))
          .andExpect(status().isBadRequest());
    }
    for (String invalid : List.of("", " " + canonical, canonical + " ")) {
      mvc.perform(get("/uuid/" + canonical).param("id", invalid))
          .andExpect(status().isBadRequest());
    }
  }

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

  @RestController
  static class Probe {
    @PostMapping("/probe")
    void post(@RequestBody ProbeDto dto) {}

    @GetMapping("/uuid/{path}")
    void uuid(@PathVariable("path") UUID path, @RequestParam("id") UUID id) {}
  }
}
