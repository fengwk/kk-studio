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
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayDataPlane;

import java.util.UUID;

class IdeGatewayFilterTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void routesViewScopedTrafficToDataPlane() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane);
    FilterChain chain = mock(FilterChain.class);

    HttpServletRequest req =
        request("/environment-ide/" + ENV + "/" + VIEW + "/src/main.ts", "a=1");
    HttpServletResponse res = mock(HttpServletResponse.class);

    filter.doFilter(req, res, chain);

    verify(dataPlane)
        .handleBrowserTraffic(eq(ENV), eq(VIEW), eq("/src/main.ts?a=1"), same(req), same(res));
    verifyNoInteractions(chain);
  }

  @Test
  void nonIdeRoutesPassThroughChain() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane);
    FilterChain chain = mock(FilterChain.class);

    HttpServletRequest req = request("/api/v1/projects", null);
    HttpServletResponse res = mock(HttpServletResponse.class);

    filter.doFilter(req, res, chain);

    verify(chain).doFilter(req, res);
    verifyNoInteractions(dataPlane);
  }

  @Test
  void redirectsMissingTrailingSlashOnView() throws Exception {
    IdeGatewayDataPlane dataPlane = mock(IdeGatewayDataPlane.class);
    IdeGatewayFilter filter = new IdeGatewayFilter(dataPlane);
    FilterChain chain = mock(FilterChain.class);

    HttpServletRequest req = request("/environment-ide/" + ENV + "/" + VIEW, null);
    when(req.getContextPath()).thenReturn("");
    HttpServletResponse res = mock(HttpServletResponse.class);

    filter.doFilter(req, res, chain);

    verify(res).setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
    verify(res).setHeader("Location", "/environment-ide/" + ENV + "/" + VIEW + "/");
    verifyNoInteractions(dataPlane);
  }

  private static HttpServletRequest request(String uri, String query) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn(uri);
    when(req.getQueryString()).thenReturn(query);
    return req;
  }
}
