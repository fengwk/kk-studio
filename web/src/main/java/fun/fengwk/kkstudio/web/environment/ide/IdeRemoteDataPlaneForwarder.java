package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 非 owner 节点把浏览器 HTTP 数据面单跳到 READY owner 的客户端。
 *
 * <p>数据流使用 {@code /internal/environment-ide/{env}/data-http}，只有 IDE 会话 cookie
 * 与安全头跨节点；请求体与响应体始终流式搬运， 不整包缓冲、不落 SQL/PG。每次 exchange 最多一次认证请求，失败即上抛，绝不自动重放或回退本地。bootstrap/session
 * 等管理路径保留独立入口。
 */
@Slf4j
@Component
public final class IdeRemoteDataPlaneForwarder {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final int MAX_FORWARDED_HEADERS = 100;

  private final IdeClusterClient clusterInternalClient;
  private final IdeGatewayProperties properties;
  private final HttpClient httpClient;

  @Autowired
  public IdeRemoteDataPlaneForwarder(
      IdeClusterClient clusterInternalClient, IdeGatewayProperties properties) {
    this(
        clusterInternalClient,
        properties,
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  IdeRemoteDataPlaneForwarder(
      IdeClusterClient clusterInternalClient,
      IdeGatewayProperties properties,
      HttpClient httpClient) {
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  /** 浏览器视图作用域 HTTP 的单跳转发；普通数据响应不得把上游 {@code Set-Cookie} 带回浏览器。 */
  void forwardDataHttp(
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    forwardViewScopedHttp(owner, environmentId, viewId, rawPathAndQuery, request, response, false);
  }

  /** view 作用域 bootstrap/session 的单跳转发；管理入口必须原样保留 owner 设置的 cookie。 */
  void forwardManagementHttp(
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    forwardViewScopedHttp(owner, environmentId, viewId, rawPathAndQuery, request, response, true);
  }

  private void forwardViewScopedHttp(
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response,
      boolean management)
      throws IOException {
    String canonicalPath =
        IdeDataInternalPaths.dataHttpPath(environmentId, viewId, rawPathAndQuery);
    forward(
        owner,
        canonicalPath,
        request,
        response,
        forwardedBrowserHeaders(request, properties, false),
        environmentId,
        viewId,
        management);
  }

  private void forward(
      ClusterOwnerRoute owner,
      String canonicalPath,
      HttpServletRequest request,
      HttpServletResponse response,
      List<MuxHeader> forwardedHeaders,
      EnvironmentId environmentId,
      UUID viewId,
      boolean management)
      throws IOException {
    URI target;
    try {
      target = owner.internalUrl().resolve(canonicalPath);
    } catch (IllegalArgumentException error) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(target)
            .timeout(properties.requireRequestTimeout())
            .method(
                request.getMethod(), HttpRequest.BodyPublishers.ofInputStream(bodySource(request)));
    for (MuxHeader header : forwardedHeaders) {
      builder.header(header.name(), header.value());
    }
    clusterInternalClient
        .signHeaders(request.getMethod(), canonicalPath, owner)
        .forEach(builder::header);
    builder.header(IdeForwardedRequest.SCHEME_HEADER, browserScheme(request));

    HttpResponse<InputStream> upstream;
    try {
      upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    } catch (IOException error) {
      log.info(
          "remote ide data plane forward failed; phase=IDE_REMOTE_FORWARD; exception={}",
          error.getClass().getSimpleName());
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    copyResponse(upstream, response, request, environmentId, viewId, management);
  }

  /** 请求体来源：把 servlet 输入流按需交给内部 HTTP 客户端（流式，不整包缓冲）。 */
  private static Supplier<InputStream> bodySource(HttpServletRequest request) {
    return () -> {
      try {
        return request.getInputStream();
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    };
  }

  private static void copyResponse(
      HttpResponse<InputStream> upstream,
      HttpServletResponse response,
      HttpServletRequest request,
      EnvironmentId environmentId,
      UUID viewId,
      boolean management)
      throws IOException {
    response.setStatus(upstream.statusCode());
    for (Map.Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
      String name = header.getKey();
      String lower = name == null ? null : name.toLowerCase(Locale.ROOT);
      if (lower == null
          || lower.equals("content-length")
          || IdeDataHeaderPolicy.isHopByHop(lower)
          || (!management && "set-cookie".equals(lower))) {
        continue;
      }
      for (String value : header.getValue()) {
        if (!management && "location".equals(lower)) {
          value = IdeGatewayDataPlane.rebaseLocation(value, request, environmentId, viewId);
        }
        if (!management && "service-worker-allowed".equals(lower)) {
          value = IdeGatewayDataPlane.rebaseWorkerScope(value, request, environmentId, viewId);
        }
        response.addHeader(name, value);
      }
    }
    try (InputStream body = upstream.body()) {
      OutputStream output = response.getOutputStream();
      body.transferTo(output);
      output.flush();
    }
  }

  /** 浏览器 → owner 的转发头：只补一个 IDE 会话 cookie，凭据/内部/代理链头不跨节点。 */
  static List<MuxHeader> forwardedBrowserHeaders(
      HttpServletRequest request, IdeGatewayProperties properties) {
    return forwardedBrowserHeaders(request, properties, true);
  }

  private static List<MuxHeader> forwardedBrowserHeaders(
      HttpServletRequest request, IdeGatewayProperties properties, boolean webSocket) {
    return forwardedHeaders(request, properties.requireSessionCookieName(), webSocket);
  }

  private static List<MuxHeader> forwardedHeaders(
      HttpServletRequest request, String cookieName, boolean webSocket) {
    List<MuxHeader> headers =
        new ArrayList<>(IdeDataHeaderPolicy.forwardRequestHeaders(request, webSocket));
    String cookie = ideCookie(request, cookieName);
    if (cookie != null && headers.size() < MAX_FORWARDED_HEADERS) {
      try {
        headers.add(new MuxHeader("Cookie", cookie));
      } catch (IllegalArgumentException ignored) {
        // 非法 cookie 值不进入内部请求。
      }
    }
    return headers;
  }

  /** 只取 IDE 会话 cookie（按 cookie 名精确匹配）；Studio 域 cookie 一律不转发。 */
  private static String ideCookie(HttpServletRequest request, String cookieName) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName())) {
        return cookieName + "=" + cookie.getValue();
      }
    }
    return null;
  }

  /** 浏览器侧链路的 scheme 声明：TLS 直连或可信前置代理声明的 https 才算安全链路。 */
  static String browserScheme(HttpServletRequest request) {
    if (request.isSecure()) {
      return "https";
    }
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    if (forwardedProto != null) {
      for (String value : forwardedProto.split(",")) {
        if (value.trim().equalsIgnoreCase("https")) {
          return "https";
        }
      }
    }
    return "http";
  }
}
