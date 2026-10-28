package fun.fengwk.kkstudio.web.environment.ide;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

/** 意图：bootstrap/session 只接受 view 作用域入口并交给会话切片，环境根路径的旧入口不再进入数据面。 */
class IdeGatewayFilterTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void routesViewScopedBootstrapAndSessionToDataPlane() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeBrowserWebSocketGateway browserGateway = mock(IdeBrowserWebSocketGateway.class);
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane, browserGateway);
    FilterChain chain = mock(FilterChain.class);

    HttpServletRequest bootstrap =
        request("/environment-ide/" + ENV + "/" + VIEW + "/__ide/bootstrap", "token=one-shot");
    HttpServletResponse bootstrapResponse = mock(HttpServletResponse.class);
    filter.doFilter(bootstrap, bootstrapResponse, chain);
    verify(dataPlane)
        .handleBootstrap(
            eq(ENV),
            eq(VIEW),
            eq("/__ide/bootstrap?token=one-shot"),
            same(bootstrap),
            same(bootstrapResponse));
    verifyNoInteractions(chain);

    HttpServletRequest session =
        request("/environment-ide/" + ENV + "/" + VIEW + "/__ide/session", null);
    HttpServletResponse sessionResponse = mock(HttpServletResponse.class);
    filter.doFilter(session, sessionResponse, chain);
    verify(dataPlane)
        .handleSession(
            eq(ENV), eq(VIEW), eq("/__ide/session"), same(session), same(sessionResponse));
    verifyNoInteractions(chain);
  }

  @Test
  void rejectsEnvironmentScopedLegacyBootstrap() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter =
        new IdeGatewayFilter(dataPlane, mock(IdeBrowserWebSocketGateway.class));
    HttpServletRequest request =
        request("/environment-ide/" + ENV + "/__ide/bootstrap", "token=legacy");
    HttpServletResponse response = mock(HttpServletResponse.class);

    filter.doFilter(request, response, mock(FilterChain.class));

    verify(response).sendError(HttpServletResponse.SC_NOT_FOUND);
    verifyNoInteractions(dataPlane);
  }

  private static HttpServletRequest request(String uri, String query) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getQueryString()).thenReturn(query);
    return request;
  }
}
