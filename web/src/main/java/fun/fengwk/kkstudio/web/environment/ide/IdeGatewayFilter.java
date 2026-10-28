package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDE 同源网关路由过滤器：在 Studio 同源下固定 {@code /environment-ide/{environmentId}/{viewId}/...} 数据路由。
 *
 * <p>view 基址之后的原始 path/query 进入 HTTP/WS 数据面；{@code __ide/bootstrap} 与 {@code __ide/session}
 * 仍是浏览器会话切片的保留 管理入口。非 IDE 路径原样放行。数据面自身复核 view+epoch 与 owner 归属，非 owner 只做单跳转发。
 */
@Component
public final class IdeGatewayFilter extends OncePerRequestFilter {

  private static final String BOOTSTRAP_ENDPOINT = "/" + EnvironmentIdeServiceImpl.BOOTSTRAP_PATH;

  private static final String SESSION_ENDPOINT = "/" + EnvironmentIdeServiceImpl.SESSION_PATH;

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
      redirectToViewRoot(request, response, environmentId);
      return;
    }
    String query = request.getQueryString();
    String tail = remainder.substring(1);
    int slash = tail.indexOf('/');
    String viewSegment = slash < 0 ? tail : tail.substring(0, slash);
    UUID viewId;
    try {
      viewId = UUID.fromString(viewSegment);
      if (!viewId.toString().equals(viewSegment)) {
        throw new IllegalArgumentException("viewId is not canonical");
      }
    } catch (IllegalArgumentException error) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (slash < 0) {
      String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
      String location =
          contextPath
              + EnvironmentIdeServiceImpl.IDE_ROUTE_PREFIX
              + environmentId
              + "/"
              + viewId
              + "/";
      if (query != null && !query.isEmpty()) {
        location += "?" + query;
      }
      response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      response.setHeader("Location", location);
      return;
    }

    String viewPath = tail.substring(slash);
    String rawPathAndQuery = (query == null || query.isEmpty()) ? viewPath : viewPath + "?" + query;
    if (BOOTSTRAP_ENDPOINT.equals(viewPath)) {
      dataPlane.handleBootstrap(environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }
    if (SESSION_ENDPOINT.equals(viewPath)) {
      dataPlane.handleSession(environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }
    if (IdeBrowserWebSocketGateway.isWebSocketUpgrade(request)) {
      browserGateway.handleUpgrade(environmentId, viewId, rawPathAndQuery, request, response);
      return;
    }
    dataPlane.handleHttp(environmentId, viewId, rawPathAndQuery, request, response);
  }

  private static void redirectToViewRoot(
      HttpServletRequest request, HttpServletResponse response, EnvironmentId environmentId) {
    // 没有 viewId 的旧环境根路径不再承载数据面；保留明确的跳转目标供会话切片继续处理。
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    String location =
        contextPath + EnvironmentIdeServiceImpl.IDE_ROUTE_PREFIX + environmentId + "/";
    String query = request.getQueryString();
    if (query != null && !query.isEmpty()) {
      location += "?" + query;
    }
    response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
    response.setHeader("Location", location);
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
