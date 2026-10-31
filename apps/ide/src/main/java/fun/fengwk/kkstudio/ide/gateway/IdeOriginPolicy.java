package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.Locale;

/**
 * IDE 同源网关的源保护策略：HTTP 数据面与浏览器 WS 握手都必须来自精确的 Studio 同源 Origin。
 *
 * <p>比较按 URL 规范进行：scheme、host、effective port 三者都必须一致，且 Origin 不得携带 userinfo/query/fragment。
 */
public final class IdeOriginPolicy {

  private IdeOriginPolicy() {}

  /** 判断请求是否为 WebSocket Upgrade 请求。 */
  public static boolean isWebSocketUpgrade(HttpServletRequest request) {
    String upgrade = request.getHeader("Upgrade");
    if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade.trim())) {
      return false;
    }
    Enumeration<String> connectionHeaders = request.getHeaders("Connection");
    if (connectionHeaders != null && connectionHeaders.hasMoreElements()) {
      while (connectionHeaders.hasMoreElements()) {
        String header = connectionHeaders.nextElement();
        if (header != null) {
          for (String token : header.split(",")) {
            if ("upgrade".equalsIgnoreCase(token.trim())) {
              return true;
            }
          }
        }
      }
      return false;
    }
    String connection = request.getHeader("Connection");
    if (connection != null) {
      for (String token : connection.split(",")) {
        if ("upgrade".equalsIgnoreCase(token.trim())) {
          return true;
        }
      }
    }
    return false;
  }

  /** 非 WS 的 HTTP 请求准入：所有方法都要求同源声明，OPTIONS 不代理。 */
  public static boolean httpRequestAllowed(HttpServletRequest request) {
    String method = request.getMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
      return false;
    }
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !isSameSite(site)) {
      return false;
    }
    String origin = request.getHeader("Origin");
    if (origin == null) {
      return site != null;
    }
    return sameOriginExact(origin, request);
  }

  /** 浏览器 WS 握手准入：Origin 必须存在且精确等于本请求的 Studio 同源 Origin。 */
  public static boolean webSocketHandshakeAllowed(HttpServletRequest request) {
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !isSameSite(site)) {
      return false;
    }
    String origin = request.getHeader("Origin");
    return origin != null && sameOriginExact(origin, request);
  }

  /** Origin 头是否与本请求的 scheme/host/effective port 精确一致。 */
  public static boolean sameOriginExact(String origin, HttpServletRequest request) {
    URI uri;
    try {
      uri = new URI(origin);
    } catch (URISyntaxException error) {
      return false;
    }
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
      return false;
    }
    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (scheme == null || host == null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
      return false;
    }
    if (!scheme.equalsIgnoreCase(request.getScheme())) {
      return false;
    }
    if (!host.equalsIgnoreCase(request.getServerName())) {
      return false;
    }
    return effectivePort(uri.getPort(), scheme) == request.getServerPort();
  }

  private static boolean isSameSite(String site) {
    return "same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site);
  }

  private static int effectivePort(int port, String scheme) {
    if (port != -1) {
      return port;
    }
    String normalized = scheme.toLowerCase(Locale.ROOT);
    return "https".equals(normalized) || "wss".equals(normalized) ? 443 : 80;
  }
}
