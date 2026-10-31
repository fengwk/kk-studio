package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayConstants;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayDataPlane;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDE 同源网关路由过滤器：在 Studio 同源下固定 {@code /environment-ide/{environmentId}/{viewId}/...} 数据路由。
 *
 * <p>view 基址之后的原始 path/query 进入 HTTP/WS 数据面；非 IDE 路径原样放行。数据面自身复核 view+epoch 与 owner 归属，非 owner
 * 只做单跳转发。
 */
@Component
public final class IdeGatewayFilter extends OncePerRequestFilter {

  private static final Pattern ROUTE_PATTERN =
      Pattern.compile(
          "^/environment-ide/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

  private final IdeGatewayDataPlane dataPlane;

  public IdeGatewayFilter(IdeGatewayDataPlane dataPlane) {
    this.dataPlane = Objects.requireNonNull(dataPlane, "dataPlane");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = relativePath(request);
    if (!path.startsWith(IdeGatewayConstants.ROUTE_PREFIX)) {
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
      viewId = Uuids.parseCanonical(viewSegment, "viewId");
    } catch (IllegalArgumentException error) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    if (slash < 0) {
      String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
      String location =
          contextPath + IdeGatewayConstants.ROUTE_PREFIX + environmentId + "/" + viewId + "/";
      if (query != null && !query.isEmpty()) {
        location += "?" + query;
      }
      response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
      response.setHeader("Location", location);
      return;
    }

    String viewPath = tail.substring(slash);
    String rawPathAndQuery = (query == null || query.isEmpty()) ? viewPath : viewPath + "?" + query;
    dataPlane.handleBrowserTraffic(environmentId, viewId, rawPathAndQuery, request, response);
  }

  private static void redirectToViewRoot(
      HttpServletRequest request, HttpServletResponse response, EnvironmentId environmentId) {
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    String location = contextPath + IdeGatewayConstants.ROUTE_PREFIX + environmentId + "/";
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
