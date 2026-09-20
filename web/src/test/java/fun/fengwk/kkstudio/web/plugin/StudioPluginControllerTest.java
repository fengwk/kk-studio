package fun.fengwk.kkstudio.web.plugin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.time.Instant;
import java.util.List;

/** {@link StudioPluginController} 与 {@link StudioPluginErrorAdvice} 独立 MockMvc 契约与错误翻译测试。 */
class StudioPluginControllerTest {

  private PluginManagementService pluginManagementService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    pluginManagementService = mock(PluginManagementService.class);
    StudioPluginController controller = new StudioPluginController(pluginManagementService);
    StudioPluginErrorAdvice errorAdvice = new StudioPluginErrorAdvice(new StudioMessageService());
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice(), errorAdvice)
            .build();
  }

  /** 测试意图：验证 GET /api/plugins 返回已安装 Plugin 列表的安全投影，且响应体中绝不包含 token、credential 等敏感字段。 */
  @Test
  void listPluginsReturnsSafeProjectionWithoutSensitiveFields() throws Exception {
    PluginDTO dto = samplePluginDTO("test-plugin");
    when(pluginManagementService.listPlugins()).thenReturn(List.of(dto));

    mockMvc
        .perform(get("/api/plugins"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].pluginId").value("test-plugin"))
        .andExpect(jsonPath("$.data[0].name").value("Test Plugin"))
        .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
        .andExpect(jsonPath("$.data[0].status").value("CONNECTED"))
        .andExpect(jsonPath("$.data[0].region").value("us-east-1"))
        .andExpect(jsonPath("$.data[0].authKind.type").value("DEEP_LINK"))
        .andExpect(jsonPath("$.data[0].authKind.regionCandidates[0]").value("us-east-1"))
        .andExpect(jsonPath("$.data[0].token").doesNotExist())
        .andExpect(jsonPath("$.data[0].credential").doesNotExist())
        .andExpect(jsonPath("$.data[0].cipher").doesNotExist())
        .andExpect(jsonPath("$.data[0].callback").doesNotExist())
        .andExpect(jsonPath("$.data[0].lease").doesNotExist())
        .andExpect(jsonPath("$.data[0].key").doesNotExist())
        .andExpect(content().string(not(containsString("credential"))))
        .andExpect(content().string(not(containsString("cipher"))));

    verify(pluginManagementService).listPlugins();
  }

  /** 测试意图：验证 GET /api/plugins/{pluginId} 返回单个 Plugin 的安全投影，字段与预期一致且不包含敏感字段。 */
  @Test
  void getPluginReturnsSafeProjectionWithoutSensitiveFields() throws Exception {
    PluginDTO dto = samplePluginDTO("my-plugin");
    when(pluginManagementService.getPlugin("my-plugin")).thenReturn(dto);

    mockMvc
        .perform(get("/api/plugins/my-plugin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.data.name").value("Test Plugin"))
        .andExpect(jsonPath("$.data.version").value("1.0.0"))
        .andExpect(jsonPath("$.data.status").value("CONNECTED"))
        .andExpect(jsonPath("$.data.region").value("us-east-1"))
        .andExpect(jsonPath("$.data.token").doesNotExist())
        .andExpect(jsonPath("$.data.credential").doesNotExist())
        .andExpect(jsonPath("$.data.cipher").doesNotExist())
        .andExpect(jsonPath("$.data.callback").doesNotExist())
        .andExpect(jsonPath("$.data.lease").doesNotExist())
        .andExpect(jsonPath("$.data.key").doesNotExist())
        .andExpect(content().string(not(containsString("credential"))))
        .andExpect(content().string(not(containsString("cipher"))));

    verify(pluginManagementService).getPlugin("my-plugin");
  }

  /** 测试意图：验证对未安装的 pluginId 发起 GET 请求时返回 404 Not Found，且错误 code 为 resource_not_found。 */
  @Test
  void getPluginNonExistentIdReturnsNotFound() throws Exception {
    when(pluginManagementService.getPlugin("unknown-plugin"))
        .thenThrow(new AiResourceNotFoundException("plugin"));

    mockMvc
        .perform(get("/api/plugins/unknown-plugin"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"))
        .andExpect(jsonPath("$.errors.resource").value("plugin"));

    verify(pluginManagementService).getPlugin("unknown-plugin");
  }

  /** 测试意图：验证 POST /api/plugins/{pluginId}/auth/prepare 请求体包含未知字段时被严格拒绝并返回 400 Bad Request。 */
  @Test
  void prepareAuthRejectsUnknownFieldWithBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/prepare")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"region\":\"us-east-1\",\"unknownField\":\"some-value\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

    verifyNoInteractions(pluginManagementService);
  }

  /**
   * 测试意图：验证 POST /api/plugins/{pluginId}/auth/prepare 传入合法 region 时返回 200 OK、Cache-Control:
   * no-store，且响应仅包含 loginUrl。
   */
  @Test
  void prepareAuthWithValidRegionReturnsLoginUrlAndNoStore() throws Exception {
    PluginAuthPrepareDTO prepareDTO = new PluginAuthPrepareDTO();
    prepareDTO.setLoginUrl("https://login.example.com/oauth?region=us-east-1");
    when(pluginManagementService.prepareAuth("my-plugin", "us-east-1")).thenReturn(prepareDTO);

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/prepare")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"region\":\"us-east-1\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            jsonPath("$.data.loginUrl").value("https://login.example.com/oauth?region=us-east-1"))
        .andExpect(jsonPath("$.data.token").doesNotExist())
        .andExpect(jsonPath("$.data.callbackUrl").doesNotExist());

    verify(pluginManagementService).prepareAuth("my-plugin", "us-east-1");
  }

  /**
   * 测试意图：验证 POST /api/plugins/{pluginId}/auth/complete 成功完成认证，返回 200 OK 与 no-store，且响应中绝不回显回调原文。
   */
  @Test
  void completeAuthWithValidCallbackUrlReturnsNoStoreAndOmitsCallbackUrl() throws Exception {
    String callbackMarker = "SECRET_CALLBACK_TOKEN_XYZ987654";
    String callbackUrl = "https://app.example.com/callback?code=" + callbackMarker;
    PluginDTO connectedDto = samplePluginDTO("my-plugin");
    connectedDto.setStatus("CONNECTED");
    when(pluginManagementService.completeAuth("my-plugin", callbackUrl)).thenReturn(connectedDto);

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callbackUrl\":\"" + callbackUrl + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.data.status").value("CONNECTED"))
        .andExpect(content().string(not(containsString(callbackMarker))));

    verify(pluginManagementService).completeAuth("my-plugin", callbackUrl);
  }

  /**
   * 测试意图：验证 POST /api/plugins/{pluginId}/auth/complete 当底层插件拒绝回调抛出 AiValidationException 时返回 400
   * Bad Request 且 code 为 validation。
   */
  @Test
  void completeAuthCallbackRejectedReturnsBadRequest() throws Exception {
    when(pluginManagementService.completeAuth(eq("my-plugin"), any()))
        .thenThrow(new AiValidationException("plugin", "plugin rejected the auth callback"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callbackUrl\":\"https://app.example.com/callback?code=bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("plugin"));

    verify(pluginManagementService).completeAuth(eq("my-plugin"), any());
  }

  /** 测试意图：验证 DELETE /api/plugins/{pluginId}/auth 幂等断连并返回 204 No Content，且不强制要求 no-store。 */
  @Test
  void disconnectReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/plugins/my-plugin/auth"))
        .andExpect(status().isNoContent())
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));

    verify(pluginManagementService).disconnect("my-plugin");
  }

  /**
   * 测试意图：验证当主密钥不可用导致抛出 PluginKeyUnavailableException 时返回 503 Service Unavailable 且 code 为
   * plugin_key_unavailable。
   */
  @Test
  void pluginKeyUnavailableReturnsServiceUnavailable() throws Exception {
    when(pluginManagementService.getPlugin("my-plugin"))
        .thenThrow(
            new PluginKeyUnavailableException(
                "plugin credential key is unavailable at /secrets/key"));

    mockMvc
        .perform(get("/api/plugins/my-plugin"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("plugin_key_unavailable"))
        .andExpect(jsonPath("$.errors.resource").value("plugin"))
        // 固定去敏文案：异常原文（可能含主密钥文件路径）不得进入响应。
        .andExpect(jsonPath("$.errors.detail").value("Plugin credential key is unavailable"))
        .andExpect(content().string(not(containsString("/secrets"))))
        .andExpect(content().string(not(containsString("password"))))
        .andExpect(content().string(not(containsString("secretToken"))));

    verify(pluginManagementService).getPlugin("my-plugin");
  }

  private static PluginDTO samplePluginDTO(String pluginId) {
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(pluginId);
    dto.setName("Test Plugin");
    dto.setVersion("1.0.0");
    dto.setAuthKind(new PluginAuthKindDTO.DeepLink(List.of("us-east-1")));
    dto.setStatus("CONNECTED");
    dto.setRegion("us-east-1");
    dto.setExpiresAt(Instant.parse("2026-10-01T00:00:00Z"));
    dto.setNextRefreshAt(Instant.parse("2026-09-25T00:00:00Z"));
    dto.setLastRefreshedAt(Instant.parse("2026-09-20T00:00:00Z"));
    dto.setLastRefreshError(null);
    return dto;
  }
}
