package fun.fengwk.kkstudio.harness.common.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP 代理策略的共同边界：仅接受无认证 HTTP 代理，HTTPS 由客户端使用 CONNECT。
 *
 * <p>不查询目标 DNS，不在代理失败时追加 DIRECT。错误不包含配置值或解析异常原因。
 */
public final class HttpProxySelector extends ProxySelector {

  private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);
  private final Proxy http;
  private final Proxy https;
  private final List<Bypass> bypass;
  private final ProxySelector fallback;

  private HttpProxySelector(Proxy http, Proxy https, List<Bypass> bypass, ProxySelector fallback) {
    this.http = http;
    this.https = https;
    this.bypass = bypass;
    this.fallback = fallback;
  }

  /**
   * 固定 Backend 策略；null 地址强制直连，绝不读取环境或默认 selector。
   *
   * <p>地址必须是 http://host:port（不接受尾部 /）。bypass 是逗号分隔的主机/域名后缀、
   * .domain、*.domain、127.*、IPv4、IPv6（可加方括号）或 *；可附 :port， IPv6 带端口必须加方括号。空字符串合法，null 与不支持的语法拒绝。
   */
  public static ProxySelector fixed(String proxyUrl, String noProxyHosts) {
    List<Bypass> bypass = parseBypass(noProxyHosts);
    Proxy proxy = proxyUrl == null ? Proxy.NO_PROXY : parseProxy(proxyUrl);
    return new HttpProxySelector(proxy, proxy, bypass, null);
  }

  /**
   * 环境变量优先，HTTP/HTTPS 独立配置，ws/wss 分别映射到 HTTP/HTTPS。
   *
   * <p>小写变量存在时优先于大写（包括空值）；空代理值表示该协议明确直连，未配置则委托 fallback（null 表示直连）。no_proxy 同样约束
   * fallback。非法配置在构建时失败， 不暗中回退；环境代理也使用严格 URL 语法。
   */
  public static ProxySelector fromEnvironment(
      Map<String, String> environment, ProxySelector fallback) {
    Objects.requireNonNull(environment, "environment");
    String noProxy = variable(environment, "no_proxy");
    return new HttpProxySelector(
        environmentProxy(variable(environment, "http_proxy")),
        environmentProxy(variable(environment, "https_proxy")),
        parseBypass(noProxy == null ? "" : noProxy),
        fallback);
  }

  private static String variable(Map<String, String> environment, String name) {
    String key = environment.containsKey(name) ? name : name.toUpperCase(Locale.ROOT);
    String value = environment.get(key);
    if (value == null && environment.containsKey(key)) {
      throw invalid();
    }
    return value;
  }

  private static Proxy environmentProxy(String value) {
    return value == null ? null : value.isEmpty() ? Proxy.NO_PROXY : parseProxy(value);
  }

  private static Proxy parseProxy(String value) {
    try {
      URI uri = new URI(value);
      if (!"http".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getPort() < 1
          || uri.getPort() > 65535) {
        throw invalid();
      }
      normalizeHost(uri.getHost());
      return new Proxy(
          Proxy.Type.HTTP,
          InetSocketAddress.createUnresolved(stripBrackets(uri.getHost()), uri.getPort()));
    } catch (URISyntaxException | IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid HTTP proxy or bypass configuration");
  }

  private static List<Bypass> parseBypass(String value) {
    if (value == null) {
      throw invalid();
    }
    if (value.isEmpty()) {
      return List.of();
    }
    List<Bypass> rules = new ArrayList<>();
    for (String item : value.split(",", -1)) {
      String host = item.trim();
      int port = -1;
      if (host.startsWith("[")) {
        int closing = host.indexOf(']');
        if (closing < 0) {
          throw invalid();
        }
        String suffix = host.substring(closing + 1);
        if (!suffix.isEmpty()) {
          if (!suffix.startsWith(":")) {
            throw invalid();
          }
          port = parsePort(suffix.substring(1));
        }
        host = host.substring(1, closing);
        if (!host.contains(":")) {
          throw invalid();
        }
      } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')) {
        int colon = host.indexOf(':');
        port = parsePort(host.substring(colon + 1));
        host = host.substring(0, colon);
      }
      if (host.startsWith("*.")) {
        host = host.substring(2);
        rejectNonDomainSuffix(host);
      } else if (host.startsWith(".")) {
        host = host.substring(1);
        rejectNonDomainSuffix(host);
      }
      boolean prefix = host.equals("127.*");
      rules.add(new Bypass(prefix ? "127." : normalizeHost(host), port, prefix));
    }
    return List.copyOf(rules);
  }

  private static int parsePort(String value) {
    if (!value.matches("[0-9]{1,5}")) {
      throw invalid();
    }
    int port = Integer.parseInt(value);
    if (port < 1 || port > 65535) {
      throw invalid();
    }
    return port;
  }

  private static void rejectNonDomainSuffix(String host) {
    if (host.contains("*") || host.contains(":") || host.matches("[0-9.]+")) {
      throw invalid();
    }
  }

  private static String stripBrackets(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static String normalizeHost(String host) {
    host = stripBrackets(host).toLowerCase(Locale.ROOT);
    if (host.contains(":")) {
      // 仅数字 IPv6 可进入此调用；不会触发主机名 DNS 查询，也不接受 zone ID。
      if (!host.matches("[0-9a-f:.]+")) {
        throw invalid();
      }
      try {
        return InetAddress.getByName(host).getHostAddress();
      } catch (UnknownHostException error) {
        throw invalid();
      }
    }
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (!host.equals("*")
        && !host.matches(
            "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")) {
      throw invalid();
    }
    return host;
  }

  @Override
  public List<Proxy> select(URI uri) {
    if (uri == null) {
      throw new IllegalArgumentException("URI is required");
    }
    String scheme = uri.getScheme();
    boolean secure = "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
    boolean plain = "http".equalsIgnoreCase(scheme) || "ws".equalsIgnoreCase(scheme);
    if (!secure && !plain) {
      return DIRECT;
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("HTTP URI host is required");
    }
    String host = normalizeHost(uri.getHost());
    int port = uri.getPort() == -1 ? (secure ? 443 : 80) : uri.getPort();
    for (Bypass rule : bypass) {
      if (rule.matches(host, port)) {
        return DIRECT;
      }
    }
    Proxy proxy = secure ? https : http;
    if (proxy != null) {
      return List.of(proxy);
    }
    if (fallback == null) {
      return DIRECT;
    }
    // JDK OS selector 不识别 WebSocket scheme，以等价 HTTP scheme 委托。
    return fallback.select(httpUri(uri));
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException error) {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(error, "error");
    String scheme = uri.getScheme();
    Proxy configured =
        "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme) ? https : http;
    if (configured == null && fallback != null) {
      fallback.connectFailed(httpUri(uri), address, error);
    }
  }

  private static URI httpUri(URI uri) {
    String scheme = uri.getScheme();
    if ("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)) {
      return URI.create(
          ("wss".equalsIgnoreCase(scheme) ? "https" : "http")
              + uri.toString().substring(scheme.length()));
    }
    return uri;
  }

  private record Bypass(String host, int port, boolean prefix) {
    boolean matches(String target, int targetPort) {
      return (port == -1 || port == targetPort)
          && (host.equals("*")
              || host.equals(target)
              || (prefix
                  ? target.matches("127\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                  : !host.contains(":")
                      && !host.matches("[0-9.]+")
                      && target.endsWith("." + host)));
    }
  }
}
