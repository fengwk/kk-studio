package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.http.ProviderController;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSchemaProvider;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.platform.settings.http.SystemSettingsController;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;

import java.util.List;

/**
 * provider 与 system settings HTTP 边界上的 {@code modelHttpRetryStatusCodes} 契约。
 *
 * <p>用真实 Jackson 3 wire mapper（{@link StrictHttpJsonMapperTestSupport}）与 MockMvc 验证：Provider DTO
 * 始终显式输出 null 覆盖；严格整数数组从 wire 层拒绝 {@code 429.5}/{@code 429.0}/{@code "429"}/null 元素/重复/越界；null
 * 表示继承系统名单。
 */
class HttpRetryStatusCodesContractTest {

  private static final List<String> INVALID_LISTS =
      List.of("429.5", "429.0", "\"429\"", "[null]", "[429,429]", "[399]", "[600]", "429", "{}");

  private ProviderService providerService;
  private SystemSettingsService settingsService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    providerService = mock(ProviderService.class);
    settingsService = mock(SystemSettingsService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ProviderController(providerService),
                new SystemSettingsController(
                    settingsService, mock(SystemSettingsSchemaProvider.class)))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(strictHttpMapper()))
            .build();
  }

  /** Provider 公开 DTO 的覆盖字段前端契约为 {@code number[] | null}：无覆盖时也必须显式输出 null。 */
  @Test
  void providerResponseCarriesExplicitNullRetryOverrides() throws Exception {
    ProviderDTO created = new ProviderDTO();
    created.setName("p");
    created.setProviderType(ProviderType.OPENAI);
    created.setVersion("1");
    when(providerService.createProvider(any())).thenReturn(created);

    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerType\":\"openai\",\"credential\":\"k\"}"))
        .andExpect(status().is2xxSuccessful())
        .andExpect(jsonPath("$.modelHttpRetryStatusCodes").value(nullValue()));
  }

  /** 合法整数数组与显式 null 都必须被接受：null 表示继承系统名单。 */
  @Test
  void providerCreateAcceptsStrictIntegerListAndExplicitNull() throws Exception {
    when(providerService.createProvider(any())).thenReturn(new ProviderDTO());

    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"p\",\"providerType\":\"openai\",\"credential\":\"k\","
                        + "\"modelHttpRetryStatusCodes\":[500,503]}"))
        .andExpect(status().is2xxSuccessful());
    mockMvc
        .perform(
            post("/api/platform/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"p\",\"providerType\":\"openai\",\"credential\":\"k\","
                        + "\"modelHttpRetryStatusCodes\":null}"))
        .andExpect(status().is2xxSuccessful());

    ArgumentCaptor<ProviderCreateDTO> captor = ArgumentCaptor.forClass(ProviderCreateDTO.class);
    verify(providerService, times(2)).createProvider(captor.capture());
    List<ProviderCreateDTO> values = captor.getAllValues();
    assertEquals(List.of(500, 503), values.get(0).getModelHttpRetryStatusCodes());
    assertNull(values.get(1).getModelHttpRetryStatusCodes());
  }

  /** 严格整数列表在 wire 层一律拒绝非整数/重复/越界，覆盖 system settings 与 Provider create/update。 */
  @Test
  void rejectsNonIntegerRetryStatusListsOverHttp() throws Exception {
    for (String token : INVALID_LISTS) {
      mockMvc
          .perform(
              post("/api/platform/providers")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"p\",\"providerType\":\"openai\",\"credential\":\"k\","
                          + "\"modelHttpRetryStatusCodes\":"
                          + token
                          + "}"))
          .andExpect(status().isBadRequest());

      mockMvc
          .perform(
              put("/api/platform/providers/p")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"credential\":{\"operation\":\"KEEP\"},"
                          + "\"modelCallTimeoutMillis\":\"1\",\"modelCallIdleTimeoutMillis\":\"1\","
                          + "\"modelHttpRetryStatusCodes\":"
                          + token
                          + "}"))
          .andExpect(status().isBadRequest());

      mockMvc
          .perform(
              put("/api/platform/settings")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"expectedVersion\":\"0\",\"aiRuntime\":{\"modelHttpRetryStatusCodes\":"
                          + token
                          + "}}"))
          .andExpect(status().isBadRequest());
    }
  }

  /** 真实 HTTP mapper：Spring Boot 装配后统一 profile 的 Jackson 3 映射器。 */
  private static JsonMapper strictHttpMapper() {
    return StrictHttpJsonMapperTestSupport.get();
  }
}
