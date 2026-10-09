package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSchemaProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.web.StrictJacksonConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * provider 与 system settings HTTP 边界上的 {@code modelHttpRetryStatusCodes} 契约。
 *
 * <p>用真实 Jackson 3 wire mapper（{@link StrictJacksonConfiguration}）与 MockMvc 验证：Provider DTO 始终显式输出
 * null 覆盖；严格整数数组从 wire 层拒绝 {@code 429.5}/{@code 429.0}/{@code "429"}/null 元素/重复/越界，且解析器确实接到
 * {@code @JsonSetter} 三态入口。
 */
class StudioHttpRetryStatusCodesContractTest {

  private static final List<String> INVALID_LISTS =
      List.of("429.5", "429.0", "\"429\"", "[null]", "[429,429]", "[399]", "[600]", "429", "{}");

  private AgentProviderService providerService;
  private SystemSettingsService settingsService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    providerService = mock(AgentProviderService.class);
    settingsService = mock(SystemSettingsService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudioAgentProviderController(providerService),
                new StudioSystemSettingsController(
                    settingsService, mock(SystemSettingsSchemaProvider.class)))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(strictHttpMapper()))
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  /** Provider 公开 DTO 的覆盖字段前端契约为 {@code number[] | null}：无覆盖时也必须显式输出 null。 */
  @Test
  void providerResponseCarriesExplicitNullRetryOverrides() throws Exception {
    AgentProviderDTO created = new AgentProviderDTO();
    created.setName("p");
    created.setProviderType("openai");
    created.setVersion("1");
    when(providerService.createProvider(any())).thenReturn(created);

    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerType\":\"openai\",\"credential\":\"k\"}"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.data.modelHttpRetryStatusCodes").value(nullValue()));
  }

  /** 合法整数数组必须被接受，并经 {@code @JsonSetter} 入口标记“字段出现”。 */
  @Test
  void providerCreateAcceptsStrictIntegerListThroughSetter() throws Exception {
    when(providerService.createProvider(any())).thenReturn(new AgentProviderDTO());

    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerType\":\"openai\",\"credential\":\"k\","
                        + "\"modelHttpRetryStatusCodes\":[500,503]}"))
        .andExpect(status().is2xxSuccessful());

    ArgumentCaptor<AgentProviderCreateDTO> captor =
        ArgumentCaptor.forClass(AgentProviderCreateDTO.class);
    verify(providerService).createProvider(captor.capture());
    assertTrue(captor.getValue().isModelHttpRetryStatusCodesProvided());
    assertEquals(List.of(500, 503), captor.getValue().getModelHttpRetryStatusCodes());
  }

  /** 省略字段与显式 null 必须可区分：省略不触发 setter，显式 null 触发但值为 null。 */
  @Test
  void providerCreateDistinguishesOmittedFromExplicitNull() throws Exception {
    when(providerService.createProvider(any())).thenReturn(new AgentProviderDTO());

    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerType\":\"openai\",\"credential\":\"k\"}"))
        .andExpect(status().is2xxSuccessful());
    mockMvc
        .perform(
            post("/api/ai/catalog/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerType\":\"openai\",\"credential\":\"k\","
                        + "\"modelHttpRetryStatusCodes\":null}"))
        .andExpect(status().is2xxSuccessful());

    ArgumentCaptor<AgentProviderCreateDTO> captor =
        ArgumentCaptor.forClass(AgentProviderCreateDTO.class);
    verify(providerService, times(2)).createProvider(captor.capture());
    List<AgentProviderCreateDTO> values = captor.getAllValues();
    assertFalse(values.get(0).isModelHttpRetryStatusCodesProvided());
    assertTrue(values.get(1).isModelHttpRetryStatusCodesProvided());
    assertNull(values.get(1).getModelHttpRetryStatusCodes());
  }

  /** 严格整数列表在 wire 层一律拒绝非整数/重复/越界，覆盖 system settings 与 Provider create/update。 */
  @Test
  void rejectsNonIntegerRetryStatusListsOverHttp() throws Exception {
    for (String token : INVALID_LISTS) {
      mockMvc
          .perform(
              post("/api/ai/catalog/providers")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"providerType\":\"openai\",\"credential\":\"k\","
                          + "\"modelHttpRetryStatusCodes\":"
                          + token
                          + "}"))
          .andExpect(status().isBadRequest());

      mockMvc
          .perform(
              put("/api/ai/catalog/providers/p")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"providerType\":\"openai\",\"modelHttpRetryStatusCodes\":" + token + "}"))
          .andExpect(status().isBadRequest());

      mockMvc
          .perform(
              put("/api/settings")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"expectedVersion\":\"0\",\"aiRuntime\":{\"modelHttpRetryStatusCodes\":"
                          + token
                          + "}}"))
          .andExpect(status().isBadRequest());
    }
  }

  /** 真实 HTTP mapper：Spring Boot 装配后经 {@link StrictJacksonConfiguration} 定制的 Jackson 3 映射器。 */
  private static JsonMapper strictHttpMapper() {
    AtomicReference<JsonMapper> mapper = new AtomicReference<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(StrictJacksonConfiguration.class)
        .run(context -> mapper.set(context.getBean(JsonMapper.class)));
    return mapper.get();
  }
}
