package fun.fengwk.kkstudio.ide.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayDataPlane;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.DomainException;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

class ClusterEnvironmentIdeInternalDataPlaneControllerTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void dataHttpDispatchesAfterAuth() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterInternalAuthentication auth = mock(ClusterInternalAuthentication.class);

    when(registry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    ClusterEnvironmentIdeInternalDataPlaneController controller =
        new ClusterEnvironmentIdeInternalDataPlaneController(dataPlane, registry, auth);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/internal/environment-ide/" + ENV + "/data-http");
    when(req.getQueryString()).thenReturn("viewId=" + VIEW + "&path=%2Fsrc%2Fa.ts");
    HttpServletResponse res = mock(HttpServletResponse.class);

    controller.dataHttp(ENV.toString(), VIEW.toString(), "/src/a.ts", req, res);

    verify(auth).verify(any(), any(), any());
    verify(dataPlane).handleInternalDataPlane(eq(ENV), eq(VIEW), eq("/src/a.ts"), any(), eq(res));
  }

  @Test
  void dataWebSocketDispatchesAfterAuth() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterInternalAuthentication auth = mock(ClusterInternalAuthentication.class);

    when(registry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    ClusterEnvironmentIdeInternalDataPlaneController controller =
        new ClusterEnvironmentIdeInternalDataPlaneController(dataPlane, registry, auth);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getRequestURI()).thenReturn("/internal/environment-ide/" + ENV + "/data-ws");
    when(req.getQueryString()).thenReturn("viewId=" + VIEW + "&path=%2Fws");
    HttpServletResponse res = mock(HttpServletResponse.class);

    controller.dataWebSocket(ENV.toString(), VIEW.toString(), "/ws", req, res);

    verify(auth).verify(any(), any(), any());
    verify(dataPlane).handleInternalDataPlane(eq(ENV), eq(VIEW), eq("/ws"), any(), eq(res));
  }

  /** 非 owner 不再直接写容器错误页：抛出领域错误，由统一内部错误信封映射为 409。 */
  @Test
  void nonOwnerFailsWithIdeUnavailableBeforeForwarding() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterInternalAuthentication auth = mock(ClusterInternalAuthentication.class);

    // Route is remote (not local)
    when(registry.route(ENV))
        .thenReturn(
            Optional.of(
                ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:8080"))));

    ClusterEnvironmentIdeInternalDataPlaneController controller =
        new ClusterEnvironmentIdeInternalDataPlaneController(dataPlane, registry, auth);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    HttpServletResponse res = mock(HttpServletResponse.class);

    assertThrows(
        DomainException.class,
        () -> controller.dataHttp(ENV.toString(), VIEW.toString(), "/path", req, res));
    verifyNoInteractions(dataPlane);
  }

  /** 认证失败按认证异常上抛，由统一内部错误信封映射为 401。 */
  @Test
  void authenticationFailureFailsBeforeForwarding() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterInternalAuthentication auth = mock(ClusterInternalAuthentication.class);
    doThrow(new ClusterInternalAuthenticationException("bad signature"))
        .when(auth)
        .verify(any(), any(), any());

    ClusterEnvironmentIdeInternalDataPlaneController controller =
        new ClusterEnvironmentIdeInternalDataPlaneController(dataPlane, registry, auth);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    HttpServletResponse res = mock(HttpServletResponse.class);

    assertThrows(
        ClusterInternalAuthenticationException.class,
        () -> controller.dataWebSocket(ENV.toString(), VIEW.toString(), "/ws", req, res));
    verifyNoInteractions(dataPlane);
    verifyNoInteractions(registry);
  }
}
