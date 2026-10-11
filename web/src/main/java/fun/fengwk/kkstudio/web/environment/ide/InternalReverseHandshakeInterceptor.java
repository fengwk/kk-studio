package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 内部反向端点的握手认证：在任何握手/副作用之前校验 HMAC 与本节点 owner 归属，并把目标 Environment 交给后续 handler。
 *
 * <p>签名绑定完整 raw path 与 query（ticket 已在 query 内），因此转发方不能改写路径；校验失败或本节点不是 READY owner 时直接拒绝升级，不建立
 * 任何反向连接。校验顺序固定为「认证 → owner 归属」，与内部 REST 端点一致。
 */
final class InternalReverseHandshakeInterceptor implements HandshakeInterceptor {

  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry clusterNodeRegistry;

  InternalReverseHandshakeInterceptor(
      ClusterInternalAuthentication authentication, ClusterNodeRegistry clusterNodeRegistry) {
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler webSocketHandler,
      Map<String, Object> attributes) {
    URI uri = request.getURI();
    String canonicalPath = canonicalPath(uri);
    try {
      authentication.verify("GET", canonicalPath, headers(request));
    } catch (ClusterInternalAuthenticationException error) {
      return false;
    }
    Optional<EnvironmentId> environmentId = environmentOf(uri.getPath());
    if (environmentId.isEmpty()) {
      return false;
    }
    boolean local =
        clusterNodeRegistry
            .route(environmentId.get())
            .filter(ClusterIdeOwnerRoute::local)
            .isPresent();
    if (!local) {
      return false;
    }
    attributes.put(IdeInternalReverseWebSocketHandler.ATTRIBUTE_ENVIRONMENT, environmentId.get());
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler webSocketHandler,
      Exception exception) {
    // 无握手后副作用。
  }

  private static String canonicalPath(URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    String query = uri.getQuery();
    return query == null || query.isEmpty() ? path : path + "?" + query;
  }

  private static Map<String, String> headers(ServerHttpRequest request) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    request
        .getHeaders()
        .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.get(0)));
    return headers;
  }

  private static Optional<EnvironmentId> environmentOf(String path) {
    if (path == null) {
      return Optional.empty();
    }
    String prefix = ClusterInternalPaths.INTERNAL_IDE_PREFIX + "/";
    if (!path.startsWith(prefix)) {
      return Optional.empty();
    }
    String remainder = path.substring(prefix.length());
    int slash = remainder.indexOf('/');
    String candidate = slash < 0 ? remainder : remainder.substring(0, slash);
    try {
      return Optional.of(EnvironmentId.parse(candidate));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }
}
