package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeHeader;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;

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
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 非 owner 节点把浏览器 HTTP 数据面（含 bootstrap/cookie 消费）单跳到 READY owner 的客户端。
 *
 * <p>每个 exchange 建一条内部认证 HTTP 连接、结束即关闭：请求体与响应体都以流的方式双向搬运，不做整包缓冲、不落 SQL/PG；响应状态与 多值头（含 {@code
 * Set-Cookie}）原样回传，hop-by-hop 头被过滤。原始 path/query 只放在被 HMAC 覆盖的 {@code ?path=} 里，绝不放进未签名 的额外
 * header；不自动重放、不重试、不在失败时回退本地。
 *
 * <p>转发方只携带 IDE 会话 cookie（按 cookie 名精确匹配）与安全头：Studio 域 cookie、{@code Authorization}、内部路由头与代理链一律不
 * 透传。浏览器侧链路的 TLS 状态用固定头显式声明，owner 据此决定 cookie 的 {@code Secure} 属性。
 */
@Slf4j
@Component
public final class IdeRemoteDataPlaneForwarder {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final int MAX_FORWARDED_HEADERS = 64;

  private final ClusterInternalClient clusterInternalClient;
  private final IdeGatewayProperties properties;
  private final HttpClient httpClient;

  @Autowired
  public IdeRemoteDataPlaneForwarder(
      ClusterInternalClient clusterInternalClient, IdeGatewayProperties properties) {
    this(
        clusterInternalClient,
        properties,
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  IdeRemoteDataPlaneForwarder(
      ClusterInternalClient clusterInternalClient,
      IdeGatewayProperties properties,
      HttpClient httpClient) {
    this.clusterInternalClient =
        Objects.requireNonNull(clusterInternalClient, "clusterInternalClient");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  /**
   * 把一个浏览器 HTTP exchange（含 bootstrap）单跳转发到 owner 的内部数据入口。
   *
   * @param rawPathAndQuery 浏览器原始 path（含 query）
   */
  void forwardHttp(
      ClusterIdeOwnerRoute owner,
      EnvironmentId environmentId,
      String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    String canonicalPath = ClusterInternalPaths.dataHttpPath(environmentId, rawPathAndQuery);
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
    for (IdeHeader header : forwardedBrowserHeaders(request, properties)) {
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
      log.debug("remote ide data plane forward failed: {}", error.getMessage());
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      return;
    }
    copyResponse(upstream, response);
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

  private void copyResponse(HttpResponse<InputStream> upstream, HttpServletResponse response)
      throws IOException {
    response.setStatus(upstream.statusCode());
    for (Map.Entry<String, List<String>> header : upstream.headers().map().entrySet()) {
      String name = header.getKey();
      if (name == null || IdeGatewayDataPlane.isNeverForwarded(name.toLowerCase(Locale.ROOT))) {
        continue;
      }
      for (String value : header.getValue()) {
        response.addHeader(name, value);
      }
    }
    try (InputStream body = upstream.body()) {
      OutputStream output = response.getOutputStream();
      body.transferTo(output);
      output.flush();
    }
  }

  /**
   * 浏览器 → owner 的转发头（HTTP 与 WS 两条路径共用）：屏蔽凭据/内部/代理链头，并只补一个 IDE 会话 cookie。
   *
   * <p>Studio 域 cookie 与 {@code Authorization} 绝不跨越节点边界。
   */
  static List<IdeHeader> forwardedBrowserHeaders(
      HttpServletRequest request, IdeGatewayProperties properties) {
    return forwardedHeaders(request, properties.requireSessionCookieName());
  }

  private static List<IdeHeader> forwardedHeaders(HttpServletRequest request, String cookieName) {
    List<IdeHeader> headers = new ArrayList<>();
    Set<String> connectionTokens = IdeGatewayDataPlane.connectionTokens(request);
    Enumeration<String> names = request.getHeaderNames();
    if (names != null) {
      while (names.hasMoreElements() && headers.size() < MAX_FORWARDED_HEADERS) {
        String name = names.nextElement();
        String lower = name.toLowerCase(Locale.ROOT);
        if (IdeGatewayDataPlane.isNeverForwarded(lower) || connectionTokens.contains(lower)) {
          continue;
        }
        Enumeration<String> values = request.getHeaders(name);
        while (values.hasMoreElements() && headers.size() < MAX_FORWARDED_HEADERS) {
          try {
            headers.add(new IdeHeader(name, values.nextElement()));
          } catch (IllegalArgumentException ignored) {
            // 非法 header 名/值不进入内部请求。
          }
        }
      }
    }
    String cookie = ideCookie(request, cookieName);
    if (cookie != null) {
      try {
        headers.add(new IdeHeader("Cookie", cookie));
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
