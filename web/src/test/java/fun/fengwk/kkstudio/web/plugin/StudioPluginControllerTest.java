package fun.fengwk.kkstudio.web.plugin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.share.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.plugin.PluginDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@link StudioPluginController} 与插件 advice 独立 MockMvc 契约与错误翻译测试。 */
class StudioPluginControllerTest {

  private PluginManagementService pluginManagementService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    pluginManagementService = mock(PluginManagementService.class);
    StudioPluginController controller = new StudioPluginController(pluginManagementService);
    StudioMessageService messageService = new StudioMessageService();
    StudioProblemAdvice errorAdvice = new StudioProblemAdvice(messageService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiResponseStatusAdvice(), errorAdvice)
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
        .andExpect(jsonPath("$[0].pluginId").value("test-plugin"))
        .andExpect(jsonPath("$[0].name").value("Test Plugin"))
        .andExpect(jsonPath("$[0].version").value("1.0.0"))
        .andExpect(jsonPath("$[0].status").value("CONNECTED"))
        .andExpect(jsonPath("$[0].region").value("us-east-1"))
        .andExpect(jsonPath("$[0].authKind.type").value("DEEP_LINK"))
        .andExpect(jsonPath("$[0].authKind.regionCandidates[0]").value("us-east-1"))
        .andExpect(jsonPath("$[0].token").doesNotExist())
        .andExpect(jsonPath("$[0].credential").doesNotExist())
        .andExpect(jsonPath("$[0].cipher").doesNotExist())
        .andExpect(jsonPath("$[0].callback").doesNotExist())
        .andExpect(jsonPath("$[0].lease").doesNotExist())
        .andExpect(jsonPath("$[0].key").doesNotExist())
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
        .andExpect(jsonPath("$.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.name").value("Test Plugin"))
        .andExpect(jsonPath("$.version").value("1.0.0"))
        .andExpect(jsonPath("$.status").value("CONNECTED"))
        .andExpect(jsonPath("$.region").value("us-east-1"))
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.credential").doesNotExist())
        .andExpect(jsonPath("$.cipher").doesNotExist())
        .andExpect(jsonPath("$.callback").doesNotExist())
        .andExpect(jsonPath("$.lease").doesNotExist())
        .andExpect(jsonPath("$.key").doesNotExist())
        .andExpect(content().string(not(containsString("credential"))))
        .andExpect(content().string(not(containsString("cipher"))));

    verify(pluginManagementService).getPlugin("my-plugin");
  }

  /** 测试意图：未安装的 pluginId 返回 404 not_found。 */
  @Test
  void getPluginNonExistentIdReturnsNotFound() throws Exception {
    when(pluginManagementService.getPlugin("unknown-plugin"))
        .thenThrow(DomainException.notFound("plugin"));

    mockMvc
        .perform(get("/api/plugins/unknown-plugin"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.detail").value("plugin not found"));

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
        .andExpect(jsonPath("$.code").value("validation"));

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
        .andExpect(jsonPath("$.loginUrl").value("https://login.example.com/oauth?region=us-east-1"))
        .andExpect(jsonPath("$.authAttemptId").value("attempt-handle-1"))
        .andExpect(jsonPath("$.token").doesNotExist())
        .andExpect(jsonPath("$.callbackUrl").doesNotExist());

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
        .andExpect(jsonPath("$.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.status").value("CONNECTED"))
        .andExpect(content().string(not(containsString(callbackMarker))));

    verify(pluginManagementService).completeAuth("my-plugin", "attempt-handle-1", callbackUrl);
  }

  /**
   * 测试意图：验证 POST /api/plugins/{pluginId}/auth/complete 当底层插件拒绝回调抛出 DomainException 时返回 400 Bad
   * Request 且 code 为 validation。
   */
  @Test
  void completeAuthCallbackRejectedReturnsBadRequest() throws Exception {
    when(pluginManagementService.completeAuth(eq("my-plugin"), eq("attempt-handle-1"), any()))
        .thenThrow(DomainException.validation("plugin", "plugin rejected the auth callback"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"authAttemptId\":\"attempt-handle-1\","
                        + "\"callbackUrl\":\"https://app.example.com/callback?code=bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("validation"))
        .andExpect(jsonPath("$.detail").value("plugin rejected the auth callback"));

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
   * 测试意图：验证 PUT /api/plugins/{pluginId}/configuration 当配置修订冲突时返回 409 Conflict，且响应体绝不回显请求正文里的秘密值。
   */
  @Test
  void updateConfigurationVersionConflictReturnsConflict() throws Exception {
    String secretMarker = "SECRET_PATCH_VALUE_ABC123456";
    when(pluginManagementService.updateConfiguration(eq("my-plugin"), eq(7L), any(), any()))
        .thenThrow(DomainException.versionConflict("plugin", "revision=7", "revision=8"));

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedRevision\":\"7\","
                        + "\"valuesJson\":\"{}\",\"secretPatch\":{\"apiKey\":"
                        + "{\"operation\":\"SET\",\"value\":\""
                        + secretMarker
                        + "\"}}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("version_conflict"))
        .andExpect(jsonPath("$.errors.expectedVersion").value("revision=7"))
        .andExpect(jsonPath("$.errors.actualVersion").value("revision=8"))
        .andExpect(content().string(not(containsString(secretMarker))));

    verify(pluginManagementService).updateConfiguration(eq("my-plugin"), eq(7L), any(), any());
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
                    "{\"expectedRevision\":\"7\",\"valuesJson\":\"{\"secret\":\""
                        + secretMarker
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(secretMarker))));

    verifyNoInteractions(pluginManagementService);
  }

  /** 测试意图：保存不等于发布。本节点尚未应用新 desired 状态时必须返回 202 Accepted，已应用时返回 200 OK，两者都禁止缓存。 */
  @Test
  void updateConfigurationReportsPendingApplicationAsAccepted() throws Exception {
    String body = "{\"expectedRevision\":\"7\",\"valuesJson\":\"{}\"}";

    PluginDTO pending = samplePluginDTO("my-plugin");
    pending.setApplicationError("configuration is saved but this node has not applied it yet");
    when(pluginManagementService.updateConfiguration(
            eq("my-plugin"), eq(7L), any(), nullable(Map.class)))
        .thenReturn(pending);

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            jsonPath("$.applicationError")
                .value("configuration is saved but this node has not applied it yet"));

    PluginDTO applied = samplePluginDTO("my-plugin");
    when(pluginManagementService.updateConfiguration(
            eq("my-plugin"), eq(7L), any(), nullable(Map.class)))
        .thenReturn(applied);

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.applicationError").doesNotExist());

    verify(pluginManagementService, times(2))
        .updateConfiguration(eq("my-plugin"), eq(7L), any(), nullable(Map.class));
  }

  /** 测试意图：期望坐标与 SET 值只接受 JSON 字符串，数字/布尔不做标量强制转换；KEEP/CLEAR 的显式 null 同样被拒绝，且错误绝不回显请求 正文里的标记值。 */
  @Test
  void nonStringPluginWireFieldsAreRejectedWithoutEchoingContent() throws Exception {
    String marker = "WIRE_COERCION_MARKER_778899";

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":7,\"valuesJson\":\"{}\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedRevision\":\"7\",\"valuesJson\":\"{}\""
                        + ",\"secretPatch\":{\"apiKey\":{\"operation\":\"SET\",\"value\":12345678}}}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedRevision\":\"7\",\"valuesJson\":\"{}\""
                        + ",\"secretPatch\":{\"apiKey\":{\"operation\":\"CLEAR\",\"value\":null}}}"))
        .andExpect(status().isBadRequest());

    // 非法十进制字符串同样只在绑定后校验：错误必须去敏，绝不回显正文中的标记值。
    mockMvc
        .perform(
            put("/api/plugins/my-plugin/configuration")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"" + marker + "\",\"valuesJson\":\"{}\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(marker))));

    verifyNoInteractions(pluginManagementService);
  }

  /** 测试意图：显式连接检查返回类型化维度、已应用坐标与 no-store，且绝不回显远端材料。 */
  @Test
  void checkConnectionReturnsTypedResultWithNoStore() throws Exception {
    when(pluginManagementService.checkConnection("my-plugin", 8L))
        .thenReturn(sampleCheckDTO("my-plugin"));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"8\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.pluginId").value("my-plugin"))
        .andExpect(jsonPath("$.appliedRevision").value("8"))
        .andExpect(jsonPath("$.reachability").value("REACHABLE"))
        .andExpect(jsonPath("$.authentication").value("ACCEPTED"))
        .andExpect(jsonPath("$.browserSession").value("UNKNOWN"))
        .andExpect(jsonPath("$.url").doesNotExist())
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(content().string(not(containsString("https://"))));

    verify(pluginManagementService).checkConnection("my-plugin", 8L);
  }

  /** 测试意图：尚未应用或坐标不匹配是 409，无能力是 400，未安装是 404，错误一律去敏。 */
  @Test
  void checkConnectionTranslatesDomainOutcomes() throws Exception {
    String body = "{\"expectedRevision\":\"8\"}";

    // 同一调用的连续三次失败：按顺序消费，避免重复 `when()` 在既有异常桩上再次求值。
    when(pluginManagementService.checkConnection("my-plugin", 8L))
        .thenThrow(DomainException.versionConflict("plugin", "revision=8", "revision=7"))
        .thenThrow(
            DomainException.validation("plugin", "plugin does not support connection checks"))
        .thenThrow(DomainException.notFound("plugin"));

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
        .andExpect(jsonPath("$.code").value("not_found"));
  }

  /** 测试意图：非字符串坐标、未知字段与缺失坐标都只在绑定期失败为 400，绝不进入服务层也绝不回显正文。 */
  @Test
  void nonStringConnectionCheckCoordinatesAreRejectedWithoutEchoingContent() throws Exception {
    String marker = "CONNECTION_CHECK_COERCION_MARKER_556677";

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":8}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"8\",\"extra\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("extra"))));

    mockMvc
        .perform(
            post("/api/plugins/my-plugin/connection-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedRevision\":\"" + marker + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString(marker))));

    verifyNoInteractions(pluginManagementService);
  }

  private static PluginConnectionCheckDTO sampleCheckDTO(String pluginId) {
    PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
    dto.setPluginId(pluginId);
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
