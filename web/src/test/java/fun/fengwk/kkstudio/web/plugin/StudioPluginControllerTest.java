package fun.fengwk.kkstudio.web.plugin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
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
    prepareDTO.setAuthAttemptId("attempt-handle-1");
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
        .andExpect(jsonPath("$.data.authAttemptId").value("attempt-handle-1"))
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
    when(pluginManagementService.completeAuth("my-plugin", "attempt-handle-1", callbackUrl))
        .thenReturn(connectedDto);

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"authAttemptId\":\"attempt-handle-1\",\"callbackUrl\":\""
                        + callbackUrl
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.data.status").value("CONNECTED"))
        .andExpect(content().string(not(containsString(callbackMarker))));

    verify(pluginManagementService).completeAuth("my-plugin", "attempt-handle-1", callbackUrl);
  }

  /**
   * 测试意图：验证 POST /api/plugins/{pluginId}/auth/complete 当底层插件拒绝回调抛出 AiValidationException 时返回 400
   * Bad Request 且 code 为 validation。
   */
  @Test
  void completeAuthCallbackRejectedReturnsBadRequest() throws Exception {
    when(pluginManagementService.completeAuth(eq("my-plugin"), eq("attempt-handle-1"), any()))
        .thenThrow(new AiValidationException("plugin", "plugin rejected the auth callback"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"authAttemptId\":\"attempt-handle-1\","
                        + "\"callbackUrl\":\"https://app.example.com/callback?code=bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.errors.resource").value("plugin"));

    verify(pluginManagementService).completeAuth(eq("my-plugin"), eq("attempt-handle-1"), any());
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

  /**
   * 测试意图：验证 PUT /api/plugins/{pluginId}/configuration 当权威坐标或凭据行 CAS 冲突时返回 409
   * Conflict，且响应体绝不回显请求正文里的秘密值。
   */
  @Test
  void updateConfigurationVersionConflictReturnsConflict() throws Exception {
    String secretMarker = "SECRET_PATCH_VALUE_ABC123456";
    when(pluginManagementService.updateConfiguration(eq("my-plugin"), eq(4L), eq(7L), any(), any()))
        .thenThrow(new AiVersionConflictException("plugin", "epoch=4", "epoch=5"));

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedEpoch\":\"4\",\"expectedRevision\":\"7\","
                        + "\"valuesJson\":\"{}\",\"secretPatch\":{\"apiKey\":"
                        + "{\"operation\":\"SET\",\"value\":\""
                        + secretMarker
                        + "\"}}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.resource").value("plugin"))
        .andExpect(content().string(not(containsString(secretMarker))));

    verify(pluginManagementService)
        .updateConfiguration(eq("my-plugin"), eq(4L), eq(7L), any(), any());
  }

  /** 测试意图：验证带秘密补丁的请求体无法解析时返回 400，且固定去敏文案绝不回显正文中的秘密值。 */
  @Test
  void unreadableSecretPatchRequestIsSanitized() throws Exception {
    String secretMarker = "SECRET_PATCH_VALUE_XYZ987654";

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedEpoch\":\"4\",\"valuesJson\":\"{\"secret\":\""
                        + secretMarker
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(secretMarker))));

    verifyNoInteractions(pluginManagementService);
  }

  /** 测试意图：保存不等于发布。本节点尚未应用新 desired 状态时必须返回 202 Accepted，已应用时返回 200 OK，两者都禁止缓存。 */
  @Test
  void updateConfigurationReportsPendingApplicationAsAccepted() throws Exception {
    String body = "{\"expectedEpoch\":\"4\",\"expectedRevision\":\"7\",\"valuesJson\":\"{}\"}";

    PluginDTO pending = samplePluginDTO("my-plugin");
    pending.setApplicationError("configuration is saved but this node has not applied it yet");
    when(pluginManagementService.updateConfiguration(eq("my-plugin"), eq(4L), eq(7L), any(), any()))
        .thenReturn(pending);

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            jsonPath("$.data.applicationError")
                .value("configuration is saved but this node has not applied it yet"));

    PluginDTO applied = samplePluginDTO("my-plugin");
    when(pluginManagementService.updateConfiguration(eq("my-plugin"), eq(4L), eq(7L), any(), any()))
        .thenReturn(applied);

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.applicationError").doesNotExist());

    verify(pluginManagementService, times(2))
        .updateConfiguration(eq("my-plugin"), eq(4L), eq(7L), any(), any());
  }

  /** 测试意图：期望坐标与 SET 值只接受 JSON 字符串，数字/布尔不做标量强制转换；KEEP/CLEAR 的显式 null 同样被拒绝，且错误绝不回显请求 正文里的标记值。 */
  @Test
  void nonStringPluginWireFieldsAreRejectedWithoutEchoingContent() throws Exception {
    String marker = "WIRE_COERCION_MARKER_778899";

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedEpoch\":4,\"expectedRevision\":\"7\",\"valuesJson\":\"{}\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedEpoch\":\"4\",\"expectedRevision\":\"7\",\"valuesJson\":\"{}\""
                        + ",\"secretPatch\":{\"apiKey\":{\"operation\":\"SET\",\"value\":12345678}}}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedEpoch\":\"4\",\"expectedRevision\":\"7\",\"valuesJson\":\"{}\""
                        + ",\"secretPatch\":{\"apiKey\":{\"operation\":\"CLEAR\",\"value\":null}}}"))
        .andExpect(status().isBadRequest());

    // 非法十进制字符串同样只在绑定后校验：错误必须去敏，绝不回显正文中的标记值。
    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedEpoch\":\""
                        + marker
                        + "\",\"expectedRevision\":\"7\",\"valuesJson\":\"{}\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(marker))));

    verifyNoInteractions(pluginManagementService);
  }

  /** 测试意图：显式连接检查返回类型化维度、已应用坐标与 no-store，且绝不回显远端材料。 */
  @Test
  void checkConnectionReturnsTypedResultWithNoStore() throws Exception {
    when(pluginManagementService.checkConnection("my-plugin", 14L, 8L))
        .thenReturn(sampleCheckDTO("my-plugin"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedEpoch\":\"14\",\"expectedRevision\":\"8\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.data.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.data.appliedEpoch").value("14"))
        .andExpect(jsonPath("$.data.appliedRevision").value("8"))
        .andExpect(jsonPath("$.data.reachability").value("REACHABLE"))
        .andExpect(jsonPath("$.data.authentication").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.browserSession").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.url").doesNotExist())
        .andExpect(jsonPath("$.data.detail").doesNotExist())
        .andExpect(content().string(not(containsString("https://"))));

    verify(pluginManagementService).checkConnection("my-plugin", 14L, 8L);
  }

  /** 测试意图：尚未应用或坐标不匹配是 409，无能力是 400，未安装是 404，错误一律去敏。 */
  @Test
  void checkConnectionTranslatesDomainOutcomes() throws Exception {
    String body = "{\"expectedEpoch\":\"14\",\"expectedRevision\":\"8\"}";

    // 同一调用的连续三次失败：按顺序消费，避免重复 `when()` 在既有异常桩上再次求值。
    when(pluginManagementService.checkConnection("my-plugin", 14L, 8L))
        .thenThrow(new AiVersionConflictException("plugin", "epoch=14", "epoch=13"))
        .thenThrow(new AiValidationException("plugin", "plugin does not support connection checks"))
        .thenThrow(new AiResourceNotFoundException("plugin"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("resource_not_found"));
  }

  /** 测试意图：非字符串坐标、未知字段与缺失坐标都只在绑定期失败为 400，绝不进入服务层也绝不回显正文。 */
  @Test
  void nonStringConnectionCheckCoordinatesAreRejectedWithoutEchoingContent() throws Exception {
    String marker = "CONNECTION_CHECK_COERCION_MARKER_556677";

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedEpoch\":14,\"expectedRevision\":\"8\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedEpoch\":\"14\",\"expectedRevision\":\"8\",\"extra\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("extra"))));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedEpoch\":\"" + marker + "\",\"expectedRevision\":\"8\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(marker))));

    verifyNoInteractions(pluginManagementService);
  }

  private static PluginConnectionCheckDTO sampleCheckDTO(String pluginId) {
    PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
    dto.setPluginId(pluginId);
    dto.setAppliedEpoch("14");
    dto.setAppliedRevision("8");
    dto.setCheckedAt(Instant.parse("2026-03-01T12:00:00Z"));
    dto.setReachability(PluginConnectionCheckDTO.Reachability.REACHABLE);
    dto.setAuthentication(PluginConnectionCheckDTO.Authentication.ACCEPTED);
    dto.setBrowserSession(PluginConnectionCheckDTO.BrowserSession.UNKNOWN);
    return dto;
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
