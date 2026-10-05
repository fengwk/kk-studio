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
   * .domain、*.domain、127.*、IPv4、IPv6（可加方括号）或 *；可附 :port，IPv6 带端口必须加方括号。 IPv4/IPv6 CIDR（{@code
   * address/prefix}）只按目标 URL 中的数值 IP 逐位匹配，不解析域名。空字符串合法，null 与不支持的语法拒绝。
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
      String rule = item.trim();
      if (rule.indexOf('/') >= 0) {
        rules.add(parseCidr(rule));
        continue;
      }
      int port = -1;
      if (rule.startsWith("[")) {
        int closing = rule.indexOf(']');
        if (closing < 0) {
          throw invalid();
        }
        String suffix = rule.substring(closing + 1);
        if (!suffix.isEmpty()) {
          if (!suffix.startsWith(":")) {
            throw invalid();
          }
          port = parsePort(suffix.substring(1));
        }
        rule = rule.substring(1, closing);
        if (!rule.contains(":")) {
          throw invalid();
        }
      } else if (rule.indexOf(':') >= 0 && rule.indexOf(':') == rule.lastIndexOf(':')) {
        int colon = rule.indexOf(':');
        port = parsePort(rule.substring(colon + 1));
        rule = rule.substring(0, colon);
      }
      if (rule.startsWith("*.")) {
        rule = rule.substring(2);
        rejectNonDomainSuffix(rule);
      } else if (rule.startsWith(".")) {
        rule = rule.substring(1);
        rejectNonDomainSuffix(rule);
      }
      boolean prefix = rule.equals("127.*");
      rules.add(new HostRule(prefix ? "127." : normalizeHost(rule), port, prefix));
    }
    return List.copyOf(rules);
  }

  /**
   * 严格解析 {@code address/prefix}：IPv4 /0..32、IPv6 /0..128。地址必须是数值字面量，不接受 hostname、wildcard、 zone
   * ID、方括号或端口；主机位可不为 0，匹配时只比较 prefix 位。
   */
  private static CidrRule parseCidr(String value) {
    int slash = value.indexOf('/');
    if (slash != value.lastIndexOf('/')) {
      throw invalid();
    }
    String address = value.substring(0, slash);
    String prefixText = value.substring(slash + 1);
    if (address.isEmpty() || !prefixText.matches("[0-9]{1,3}")) {
      throw invalid();
    }
    int prefix = Integer.parseInt(prefixText);
    if (address.indexOf(':') >= 0) {
      if (address.indexOf('[') >= 0 || address.indexOf(']') >= 0) {
        throw invalid();
      }
      // 仅数字 IPv6 字面量可进入此调用；不会触发主机名 DNS 查询。
      if (!address.matches("[0-9a-fA-F:.]+")) {
        throw invalid();
      }
      byte[] network = ipv6Bytes(address);
      if (network == null || prefix > 128) {
        throw invalid();
      }
      return new CidrRule(network, prefix);
    }
    byte[] network = ipv4Bytes(address);
    if (network == null || prefix > 32) {
      throw invalid();
    }
    return new CidrRule(network, prefix);
  }

  /** 严格十进制 IPv4：恰好 4 个 0..255 octet，不接受缩略或非数字形式。 */
  private static byte[] ipv4Bytes(String value) {
    String[] octets = value.split("\\.", -1);
    if (octets.length != 4) {
      return null;
    }
    byte[] bytes = new byte[4];
    for (int i = 0; i < octets.length; i++) {
      if (!octets[i].matches("[0-9]{1,3}")) {
        return null;
      }
      int octet = Integer.parseInt(octets[i]);
      if (octet > 255) {
        return null;
      }
      bytes[i] = (byte) octet;
    }
    return bytes;
  }

  /** 解析纯数字 IPv6；IPv4-mapped 被 JDK 折叠为 4 字节时显式还原为 16 字节，保持 IPv6 地址族。 */
  private static byte[] ipv6Bytes(String value) {
    try {
      byte[] bytes = InetAddress.getByName(value).getAddress();
      if (bytes.length == 16) {
        return bytes;
      }
      byte[] mapped = new byte[16];
      mapped[10] = (byte) 0xff;
      mapped[11] = (byte) 0xff;
      System.arraycopy(bytes, 0, mapped, 12, 4);
      return mapped;
    } catch (UnknownHostException error) {
      return null;
    }
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
    String literal = stripBrackets(uri.getHost());
    int port = uri.getPort() == -1 ? (secure ? 443 : 80) : uri.getPort();
    for (Bypass rule : bypass) {
      if (rule.matches(host, literal, port)) {
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

  /** 绕过规则匹配：{@code host} 是归一化域名/字面量，{@code literal} 是剥离方括号的原始 URI host。 */
  private interface Bypass {
    boolean matches(String host, String literal, int port);
  }

  /** 主机、域名后缀与 127.* 规则；保留原有可选端口语义。 */
  private record HostRule(String host, int port, boolean prefix) implements Bypass {
    @Override
    public boolean matches(String host, String literal, int port) {
      return (this.port == -1 || this.port == port)
          && (this.host.equals("*")
              || this.host.equals(host)
              || (prefix
                  ? host.matches("127\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                  : !this.host.contains(":")
                      && !this.host.matches("[0-9.]+")
                      && host.endsWith("." + this.host)));
    }
  }

  /** IPv4/IPv6 CIDR 规则，只匹配目标 URL 中数值 IP 的相同地址族，且不受端口限制。 */
  private record CidrRule(byte[] network, int prefix) implements Bypass {
    @Override
    public boolean matches(String host, String literal, int port) {
      byte[] address = numericBytes(literal);
      if (address == null || address.length != network.length) {
        return false;
      }
      int fullBytes = prefix / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (network[i] != address[i]) {
          return false;
        }
      }
      int remaining = prefix % 8;
      if (remaining == 0) {
        return true;
      }
      int mask = 0xff << (8 - remaining);
      return (network[fullBytes] & mask) == (address[fullBytes] & mask);
    }
  }

  /** 仅对数值 IP 返回地址字节，域名或非数值 host 返回 null；不做 DNS 解析。 */
  private static byte[] numericBytes(String host) {
    return host.indexOf(':') >= 0 ? ipv6Bytes(host) : ipv4Bytes(host);
  }
}
