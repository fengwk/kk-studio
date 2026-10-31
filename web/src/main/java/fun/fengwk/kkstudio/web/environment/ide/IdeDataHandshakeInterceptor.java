package fun.fengwk.kkstudio.web.environment.ide;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 内部数据端点握手：先校验 HMAC，再核对本节点 READY owner 归属，最后解析路由参数。
 *
 * <p>签名使用原始 raw path + raw query 逐字校验；浏览器逻辑 WS 端点额外解析 {@code viewId} 与 {@code path}，物理数据端点只依赖
 * ticket。任何失败都拒绝升级，不建立 mux 物理连接。
 */
@Slf4j
final class IdeDataHandshakeInterceptor implements HandshakeInterceptor {

  static final String ENVIRONMENT = "kk.ide.data.environment";

  static final String VIEW_ID = "kk.ide.data.viewId";

  static final String RAW_PATH = "kk.ide.data.rawPath";

  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final boolean browser;

  IdeDataHandshakeInterceptor(
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry clusterNodeRegistry,
      boolean browser) {
    this.authentication = Objects.requireNonNull(authentication, "authentication");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.browser = browser;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler webSocketHandler,
      Map<String, Object> attributes) {
    String canonicalPath = canonicalPath(request.getURI());
    if (!verify(request, canonicalPath)) {
      log.debug(
          "IDE data handshake rejected; phase=auth; browser={}; path={}", browser, canonicalPath);
      return false;
    }
    Optional<EnvironmentId> environmentId = environmentOf(request.getURI().getRawPath());
    if (environmentId.isEmpty()) {
      log.debug("IDE data handshake rejected; phase=environment; browser={}", browser);
      return false;
    }
    boolean local =
        clusterNodeRegistry.route(environmentId.get()).filter(ClusterOwnerRoute::local).isPresent();
    if (!local) {
      log.debug("IDE data handshake rejected; phase=owner; browser={}", browser);
      return false;
    }
    attributes.put(ENVIRONMENT, environmentId.get());
    if (!browser) {
      return true;
    }
    MultiValueMap<String, String> query =
        UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
    List<String> viewIds = query.get(IdeDataInternalPaths.VIEW_ID_PARAMETER);
    List<String> paths = query.get(IdeDataInternalPaths.PATH_PARAMETER);
    if (viewIds == null || viewIds.size() != 1 || paths == null || paths.size() != 1) {
      log.debug("IDE data handshake rejected; phase=query; browser={}", browser);
      return false;
    }
    UUID viewId;
    try {
      viewId = Uuids.parseCanonical(viewIds.getFirst(), "viewId");
    } catch (IllegalArgumentException error) {
      log.debug("IDE data handshake rejected; phase=view-id; browser={}", browser);
      return false;
    }
    String rawPathAndQuery;
    try {
      rawPathAndQuery = URLDecoder.decode(paths.getFirst(), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      log.debug("IDE data handshake rejected; phase=path-escape; browser={}", browser);
      return false;
    }
    if (rawPathAndQuery == null || !rawPathAndQuery.startsWith("/")) {
      log.debug("IDE data handshake rejected; phase=raw-path; browser={}", browser);
      return false;
    }
    attributes.put(VIEW_ID, viewId);
    attributes.put(RAW_PATH, rawPathAndQuery);
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

  private boolean verify(ServerHttpRequest request, String canonicalPath) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String name :
        List.of(
            HmacClusterInternalAuthentication.HEADER_TARGET,
            HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
            HmacClusterInternalAuthentication.HEADER_NONCE,
            HmacClusterInternalAuthentication.HEADER_SIGNATURE)) {
      List<String> values = request.getHeaders().get(name);
      if (values == null) {
        continue;
      }
      if (values.size() != 1) {
        return false;
      }
      headers.put(name, values.getFirst());
    }
    try {
      authentication.verify("GET", canonicalPath, headers);
      return true;
    } catch (ClusterInternalAuthenticationException error) {
      return false;
    }
  }

  private static Optional<EnvironmentId> environmentOf(String rawPath) {
    if (rawPath == null) {
      return Optional.empty();
    }
    String prefix = IdeInternalPaths.INTERNAL_IDE_PREFIX + "/";
    if (!rawPath.startsWith(prefix)) {
      return Optional.empty();
    }
    String remainder = rawPath.substring(prefix.length());
    int slash = remainder.indexOf('/');
    String candidate = slash < 0 ? remainder : remainder.substring(0, slash);
    try {
      return Optional.of(EnvironmentId.parse(candidate));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  private static String canonicalPath(URI uri) {
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    String query = uri.getRawQuery();
    return query == null || query.isEmpty() ? path : path + "?" + query;
  }
}
