package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.ide.IdeOriginProperties;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * IDE 独立 Origin 的入口路由与 host guard。
 *
 * <p>Host 命中 IDE Origin 模板的请求一律由 {@link IdeGatewayDataPlane} 在 MVC 之外处理：bootstrap 路径消费一次性票据并设置
 * host-only cookie，其它路径经反向数据面转发到该 Environment 的 code-server。它们绝不进入 Studio REST 管线，因此 IDE Origin
 * 无法访问 Studio API 或 {@code /internal}。非 IDE Origin 的请求（含 Daemon 反向 stream 端点的 WS 升级）原样放行给后续过滤器 /
 * MVC / WebSocket。
 */
@Component
public final class IdeOriginRequestFilter extends OncePerRequestFilter {

  private final IdeHostResolver hostResolver;
  private final IdeOriginProperties originProperties;
  private final IdeGatewayDataPlane dataPlane;
  private final IdeBrowserWebSocketGateway browserGateway;

  public IdeOriginRequestFilter(
      IdeHostResolver hostResolver,
      IdeOriginProperties originProperties,
      IdeGatewayDataPlane dataPlane,
      IdeBrowserWebSocketGateway browserGateway) {
    this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
    this.originProperties = Objects.requireNonNull(originProperties, "originProperties");
    this.dataPlane = Objects.requireNonNull(dataPlane, "dataPlane");
    this.browserGateway = Objects.requireNonNull(browserGateway, "browserGateway");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<EnvironmentId> environmentId = hostResolver.resolve(request.getHeader("Host"));
    if (environmentId.isEmpty()) {
      // Studio Origin（或未知 Host）：正常进入后续过滤器/MVC/WebSocket，不受 IDE 数据面影响。
      chain.doFilter(request, response);
      return;
    }
    String path = relativePath(request);
    if (isBootstrapPath(path)) {
      dataPlane.handleBootstrap(environmentId.get(), request, response);
      return;
    }
    String pathAndQuery = rawPathAndQuery(request, path);
    if (IdeBrowserWebSocketGateway.isWebSocketUpgrade(request)) {
      browserGateway.handleUpgrade(environmentId.get(), pathAndQuery, request, response);
      return;
    }
    dataPlane.handleHttp(environmentId.get(), pathAndQuery, request, response);
  }

  private boolean isBootstrapPath(String path) {
    String bootstrapPath = originProperties.getBootstrapPath();
    return bootstrapPath != null && !bootstrapPath.isBlank() && bootstrapPath.equals(path);
  }

  private static String relativePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static String rawPathAndQuery(HttpServletRequest request, String path) {
    String query = request.getQueryString();
    return query == null || query.isEmpty() ? path : path + "?" + query;
  }
}
