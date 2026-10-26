package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.web.environment.cluster.ClusterInternalRequests;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 内部浏览器 WS 端点的握手准入：认证 → owner 归属 → 浏览器会话 → 代际，全部在任何握手与副作用之前完成。
 *
 * <p>HMAC 覆盖 method 与完整内部 path/query（原始浏览器 path 就在签名的 {@code ?path=} 内）。本节点不是 READY owner、缺少/过期 浏览器
 * cookie、或代际已变更时直接拒绝升级，绝不建立任何反向连接、绝不递归转发。
 *
 * <p>只接受转发方携带的 IDE 会话 cookie（按 cookie 名精确匹配）；Studio 域 cookie 与 {@code Authorization} 在转发方就被剥离。
 */
@Slf4j
final class IdeInternalBrowserHandshakeInterceptor implements HandshakeInterceptor {

  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeBrowserSessionRegistry sessions;
  private final IdeCommandTransport transport;
  private final String cookieName;

  IdeInternalBrowserHandshakeInterceptor(
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeBrowserSessionRegistry sessions,
      IdeCommandTransport transport,
      IdeGatewayProperties properties) {
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.cookieName = properties.requireSessionCookieName();
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler webSocketHandler,
      Map<String, Object> attributes) {
    URI uri = request.getURI();
    String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
    String rawQuery = uri.getRawQuery();
    try {
      // 签名基准必须逐字等于发送端：用原始（未解码）path/query，绝不使用 URI#getPath/getQuery。
      authentication.verify(
          "GET", ClusterInternalRequests.canonicalPath(rawPath, rawQuery), headers(request));
    } catch (ClusterInternalAuthenticationException error) {
      log.debug("rejected internal browser handshake authentication: {}", error.getMessage());
      return false;
    }
    Optional<EnvironmentId> environmentId = environmentOf(rawPath);
    if (environmentId.isEmpty() || !local(environmentId.get())) {
      log.debug("rejected internal browser handshake: not this node's READY owner route");
      return false;
    }
    String rawPathAndQuery = pathParameter(rawQuery);
    if (rawPathAndQuery == null || !rawPathAndQuery.startsWith("/")) {
      log.debug("rejected internal browser handshake: missing browser path parameter");
      return false;
    }
    Optional<IdeBrowserSessionRegistry.Session> session = session(request, environmentId.get());
    if (session.isEmpty()
        || IdeGenerationState.of(transport, session.get().identity())
            != IdeGenerationState.CURRENT) {
      log.debug("rejected internal browser handshake: browser session is missing or stale");
      return false;
    }
    attributes.put(IdeInternalBrowserWebSocketHandler.ATTRIBUTE_ENVIRONMENT, environmentId.get());
    attributes.put(IdeInternalBrowserWebSocketHandler.ATTRIBUTE_SESSION, session.get());
    attributes.put(IdeInternalBrowserWebSocketHandler.ATTRIBUTE_PATH, rawPathAndQuery);
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

  private boolean local(EnvironmentId environmentId) {
    return clusterNodeRegistry.route(environmentId).filter(ClusterOwnerRoute::local).isPresent();
  }

  private Optional<IdeBrowserSessionRegistry.Session> session(
      ServerHttpRequest request, EnvironmentId environmentId) {
    List<String> cookieHeaders = request.getHeaders().get("Cookie");
    if (cookieHeaders == null) {
      return Optional.empty();
    }
    for (String header : cookieHeaders) {
      for (String pair : header.split(";")) {
        int equals = pair.indexOf('=');
        if (equals <= 0 || !cookieName.equals(pair.substring(0, equals).trim())) {
          continue;
        }
        Optional<IdeBrowserSessionRegistry.Session> session =
            sessions.lookup(pair.substring(equals + 1).trim());
        if (session.isPresent() && session.get().environmentId().equals(environmentId)) {
          return session;
        }
      }
    }
    return Optional.empty();
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
    String prefix = IdeInternalPaths.INTERNAL_IDE_PREFIX + "/";
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

  /** 从内部原始 query 还原转发方声明的浏览器 path（含 query）；整体只做一次 percent-decode。 */
  private static String pathParameter(String query) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    String prefix = IdeInternalPaths.PATH_PARAMETER + "=";
    for (String pair : query.split("&")) {
      if (pair.startsWith(prefix)) {
        return URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
