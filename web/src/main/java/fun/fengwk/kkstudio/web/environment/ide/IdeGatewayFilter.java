package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDE 同源网关路由过滤器：在 Studio 同源下固定 {@code /environment-ide/{environmentId}/...} 路由。
 *
 * <p>按路径解析规范 UUID 的 environmentId；剥除 route base（{@code /environment-ide/{environmentId}/}）后将请求
 * 转发给数据面。bootstrap 请求（{@code __ide/bootstrap}）消费一次性票据并设置 Path 限定在当前环境 route base 的 cookie；WebSocket
 * upgrade 请求转交 {@link IdeBrowserWebSocketGateway}；其它请求由 {@link IdeGatewayDataPlane} 转发。非 IDE
 * 路径原样放行。
 */
@Component
public final class IdeGatewayFilter extends OncePerRequestFilter {

  private static final String BOOTSTRAP_ENDPOINT = "/" + EnvironmentIdeServiceImpl.BOOTSTRAP_PATH;

  private static final Pattern ROUTE_PATTERN =
      Pattern.compile(
          "^/environment-ide/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

  private final IdeGatewayDataPlane dataPlane;
  private final IdeBrowserWebSocketGateway browserGateway;

  public IdeGatewayFilter(
      IdeGatewayDataPlane dataPlane, IdeBrowserWebSocketGateway browserGateway) {
    this.dataPlane = Objects.requireNonNull(dataPlane, "dataPlane");
    this.browserGateway = Objects.requireNonNull(browserGateway, "browserGateway");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = relativePath(request);
    if (!path.startsWith(EnvironmentIdeServiceImpl.IDE_ROUTE_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }

    Matcher matcher = ROUTE_PATTERN.matcher(path);
    if (!matcher.matches()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    EnvironmentId environmentId;
    try {
      environmentId = EnvironmentId.parse(matcher.group(1));
    } catch (IllegalArgumentException error) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }

    String remainder = matcher.group(2);
    if (remainder == null) {
      String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
      String location =
          contextPath + EnvironmentIdeServiceImpl.IDE_ROUTE_PREFIX + environmentId + "/";
      String query = request.getQueryString();
      if (query != null && !query.isEmpty()) {
        location += "?" + query;
      }
      response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      response.setHeader("Location", location);
      return;
    }

    String query = request.getQueryString();
    String rawPathAndQuery =
        (query == null || query.isEmpty()) ? remainder : remainder + "?" + query;

    if (BOOTSTRAP_ENDPOINT.equals(remainder)) {
      dataPlane.handleBootstrap(environmentId, rawPathAndQuery, request, response);
      return;
    }

    if (IdeBrowserWebSocketGateway.isWebSocketUpgrade(request)) {
      browserGateway.handleUpgrade(environmentId, rawPathAndQuery, request, response);
      return;
    }

    dataPlane.handleHttp(environmentId, rawPathAndQuery, request, response);
  }

  private static String relativePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }
}
