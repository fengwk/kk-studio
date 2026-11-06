package fun.fengwk.kkstudio.platform.pluginhost.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthCompleteRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationUpdateRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginDTO;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** {@link PluginController} 的委托、状态码与 no-store 边界；错误映射由 web 的统一 advice 负责。 */
class PluginControllerTest {

  private static final String VALUES_JSON = "{\"enabled\":true}";

  private PluginManagementService pluginManagementService;
  private PluginController controller;

  @BeforeEach
  void setUp() {
    pluginManagementService = mock(PluginManagementService.class);
    controller = new PluginController(pluginManagementService);
  }

  @Test
  void listAndDetailReturnTheSafeProjectionDirectly() {
    PluginDTO plugin = samplePluginDTO("my-plugin");
    when(pluginManagementService.listPlugins()).thenReturn(List.of(plugin));
    when(pluginManagementService.getPlugin("my-plugin")).thenReturn(plugin);

    assertEquals(List.of(plugin), controller.listPlugins());
    assertSame(plugin, controller.getPlugin("my-plugin"));
  }

  @Test
  void configurationUpdatePassesTheTypedRequestThrough() {
    PluginDTO applied = samplePluginDTO("my-plugin");
    when(pluginManagementService.updateConfiguration(
            "my-plugin", "7", VALUES_JSON, Map.of("apiKey", SecretUpdate.set("secret"))))
        .thenReturn(applied);

    PluginConfigurationUpdateRequestDTO request = new PluginConfigurationUpdateRequestDTO();
    request.setExpectedVersion("7");
    request.setValuesJson(VALUES_JSON);
    request.setSecretPatch(Map.of("apiKey", SecretUpdate.set("secret")));

    ResponseEntity<PluginDTO> response = controller.updateConfiguration("my-plugin", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertSame(applied, response.getBody());
  }

  /** 保存成功但本节点尚未应用时必须以 202 表达，而不是当作已生效。 */
  @Test
  void pendingApplicationIsReportedAsAccepted() {
    PluginDTO pending = samplePluginDTO("my-plugin");
    pending.setApplicationError("configuration is saved but this node has not applied it yet");
    when(pluginManagementService.updateConfiguration("my-plugin", "7", VALUES_JSON, null))
        .thenReturn(pending);

    PluginConfigurationUpdateRequestDTO request = new PluginConfigurationUpdateRequestDTO();
    request.setExpectedVersion("7");
    request.setValuesJson(VALUES_JSON);

    ResponseEntity<PluginDTO> response = controller.updateConfiguration("my-plugin", request);

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertSame(pending, response.getBody());
  }

  @Test
  void checkConnectionReturnsTheTypedResultWithNoStore() {
    PluginConnectionCheckDTO result = new PluginConnectionCheckDTO();
    result.setPluginId("my-plugin");
    result.setAppliedVersion("8");
    result.setCheckedAt(Instant.parse("2026-03-01T12:00:00Z"));
    result.setReachability(PluginConnectionCheckDTO.Reachability.REACHABLE);
    result.setAuthentication(PluginConnectionCheckDTO.Authentication.ACCEPTED);
    result.setBrowserSession(PluginConnectionCheckDTO.BrowserSession.UNKNOWN);
    when(pluginManagementService.checkConnection("my-plugin", "8")).thenReturn(result);

    PluginConnectionCheckRequestDTO request = new PluginConnectionCheckRequestDTO();
    request.setExpectedVersion("8");
    ResponseEntity<PluginConnectionCheckDTO> response =
        controller.checkConnection("my-plugin", request);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertSame(result, response.getBody());
    verify(pluginManagementService).checkConnection("my-plugin", "8");
  }

  @Test
  void authPrepareAndCompleteReturnNoStoreProjections() {
    PluginAuthPrepareDTO prepared = new PluginAuthPrepareDTO();
    prepared.setAuthAttemptId("attempt-1");
    prepared.setLoginUrl("https://login.example/cn");
    when(pluginManagementService.prepareAuth("my-plugin", "CN")).thenReturn(prepared);

    PluginAuthPrepareRequestDTO prepareRequest = new PluginAuthPrepareRequestDTO();
    prepareRequest.setRegion("CN");
    ResponseEntity<PluginAuthPrepareDTO> preparedResponse =
        controller.prepareAuth("my-plugin", prepareRequest);
    assertEquals(HttpStatus.OK, preparedResponse.getStatusCode());
    assertEquals("no-store", preparedResponse.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertSame(prepared, preparedResponse.getBody());

    PluginDTO connected = samplePluginDTO("my-plugin");
    when(pluginManagementService.completeAuth("my-plugin", "attempt-1", "app://callback"))
        .thenReturn(connected);
    PluginAuthCompleteRequestDTO completeRequest = new PluginAuthCompleteRequestDTO();
    completeRequest.setAuthAttemptId("attempt-1");
    completeRequest.setCallbackUrl("app://callback");
    ResponseEntity<PluginDTO> completedResponse =
        controller.completeAuth("my-plugin", completeRequest);
    assertEquals(HttpStatus.OK, completedResponse.getStatusCode());
    assertEquals("no-store", completedResponse.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
    assertSame(connected, completedResponse.getBody());
  }

  @Test
  void disconnectIsIdempotentAndReturnsNoContent() {
    ResponseEntity<Void> response = controller.disconnect("my-plugin");

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    assertNull(response.getBody());
    verify(pluginManagementService).disconnect("my-plugin");
  }

  private static PluginDTO samplePluginDTO(String pluginId) {
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(pluginId);
    dto.setName("Test Plugin");
    dto.setPluginVersion("1.0.0");
    dto.setAuthKind(new PluginAuthKindDTO.DeepLink(List.of("us-east-1")));
    dto.setStatus(PluginCredentialStatus.CONNECTED);
    dto.setRegion("us-east-1");
    return dto;
  }
}
