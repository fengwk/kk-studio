package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * IDE 同源网关的源保护策略：HTTP 数据面与浏览器 WS 握手都必须来自**精确**的 Studio 同源 Origin。
 *
 * <p>比较按 URL 规范进行：scheme、host、effective port 三者都必须一致，且 Origin 不得携带 userinfo/query/fragment。默认端口按
 * scheme 归一（http/ws=80，https/wss=443），因此 {@code https://env.ide.test} 与 {@code
 * https://env.ide.test:443} 等价，而 {@code https://evil.env.ide.test}、{@code
 * http://env.ide.test}（scheme 不同）或任意其它端口都被拒绝——这堵住「同站其它子域」的 WS 劫持。
 *
 * <p>缺失 Origin 时绝不放过：只有浏览器明确声明 {@code Sec-Fetch-Site} 为 {@code same-origin}/{@code
 * none}（直接输入/同源导航）才允许非 WS 的 unsafe 请求；两者都缺失即 fail-closed。浏览器发起的请求一定会带其一。WS 握手额外要求 Origin 必须存在。
 */
final class IdeOriginPolicy {

  private IdeOriginPolicy() {}

  /** 非 WS 的 HTTP 请求准入：所有方法都要求同源声明，OPTIONS 不代理。 */
  static boolean httpRequestAllowed(HttpServletRequest request) {
    String method = request.getMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
      // IDE 数据面只服务同源请求，不需要 CORS 预检；不代理 OPTIONS。
      return false;
    }
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !isSameSite(site)) {
      return false;
    }
    String origin = request.getHeader("Origin");
    if (origin == null) {
      // 缺 Origin：必须有明确的同站/直接输入声明，否则 fail-closed。
      return site != null;
    }
    return sameOriginExact(origin, request);
  }

  /** 浏览器 WS 握手准入：Origin 必须存在且精确等于本请求的 Studio 同源 Origin。 */
  static boolean webSocketHandshakeAllowed(HttpServletRequest request) {
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !isSameSite(site)) {
      return false;
    }
    String origin = request.getHeader("Origin");
    return origin != null && sameOriginExact(origin, request);
  }

  /** Origin 头是否与本请求的 scheme/host/effective port 精确一致。 */
  static boolean sameOriginExact(String origin, HttpServletRequest request) {
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
    if (scheme == null || host == null || uri.getPath() != null && !uri.getPath().isEmpty()) {
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
