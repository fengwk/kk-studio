package fun.fengwk.kkstudio.harness.common.network;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 只检查真实 selector 输出，不访问 DNS 或外网。 */
class HttpProxySelectorTest {

  private static final String URL = "http://proxy.invalid:8080";

  /** 意图：null 固定配置始终 DIRECT；配置代理仅返回 HTTP 代理，不附加故障直连。 */
  @Test
  void fixedIsExplicitAndNeverFallsBack() {
    ProxySelector direct = HttpProxySelector.fixed(null, "");
    ProxySelector proxy = HttpProxySelector.fixed(URL, "");
    for (String scheme : List.of("http", "https", "ws", "wss")) {
      assertDirect(direct, scheme + "://remote.invalid");
      Proxy chosen = select(proxy, scheme + "://remote.invalid");
      assertEquals(Proxy.Type.HTTP, chosen.type());
      InetSocketAddress address = (InetSocketAddress) chosen.address();
      assertTrue(address.isUnresolved());
      assertEquals("proxy.invalid", address.getHostString());
      assertEquals(8080, address.getPort());
    }
    proxy.connectFailed(
        URI.create("https://remote.invalid"), new InetSocketAddress(8080), new IOException());
    assertEquals(Proxy.Type.HTTP, select(proxy, "https://remote.invalid").type());
    assertDirect(proxy, "ftp://remote.invalid");
    assertDirect(proxy, "mailto:test@example.com");
    assertThrows(IllegalArgumentException.class, () -> proxy.select(null));
    assertThrows(IllegalArgumentException.class, () -> proxy.select(URI.create("http:/path")));
    assertThrows(NullPointerException.class, () -> proxy.connectFailed(null, null, null));
    assertThrows(
        NullPointerException.class,
        () -> proxy.connectFailed(URI.create("http://host"), null, new IOException()));
    assertThrows(
        NullPointerException.class,
        () -> proxy.connectFailed(URI.create("http://host"), new InetSocketAddress(80), null));
  }

  /** 意图：后缀匹配有域名边界，IP 精确匹配；大小写、尾点和 IPv6 展开形式归一化。 */
  @Test
  void bypassMatchesHostsDomainsAndLiterals() {
    ProxySelector selector =
        HttpProxySelector.fixed(
            URL, "localhost,127.*,10.0.0.1,.example.com,*.other.test,[::1],2001:db8::1");
    for (String host :
        List.of(
            "localhost",
            "LOCALHOST",
            "127.0.0.9",
            "10.0.0.1",
            "example.com",
            "a.example.com",
            "example.com.",
            "other.test",
            "a.other.test",
            "[::1]",
            "[0:0:0:0:0:0:0:1]",
            "[2001:0db8:0:0:0:0:0:1]")) {
      assertDirect(selector, "http://" + host);
    }
    for (String host :
        List.of(
            "notexample.com",
            "example.com.evil",
            "127.evil",
            "10.0.0.2",
            "[::2]",
            "elsewhere.invalid")) {
      assertEquals(Proxy.Type.HTTP, select(selector, "https://" + host).type(), host);
    }
    assertDirect(HttpProxySelector.fixed(URL, "*"), "https://any.invalid");
    assertEquals(
        "::1",
        ((InetSocketAddress)
                select(HttpProxySelector.fixed("http://[::1]:8888", ""), "http://any.invalid")
                    .address())
            .getHostString());
  }

  /** 意图：可选端口只影响对应端口，省略目标端口时按 HTTP/HTTPS 默认端口判断。 */
  @Test
  void bypassPortsAreProtocolAware() {
    ProxySelector selector = HttpProxySelector.fixed(URL, "example.com:443,[::1]:80,127.*:8080");
    assertDirect(selector, "https://example.com");
    assertDirect(selector, "wss://a.example.com");
    assertDirect(selector, "http://[::1]");
    assertDirect(selector, "http://127.2.3.4:8080");
    assertEquals(Proxy.Type.HTTP, select(selector, "http://example.com").type());
    assertEquals(Proxy.Type.HTTP, select(selector, "https://[::1]").type());
    assertEquals(Proxy.Type.HTTP, select(selector, "http://127.2.3.4").type());
  }

  /** 意图：IPv4 CIDR 匹配上下界与紧邻外部，/0、/32 及主机位非零时只比较 prefix 位。 */
  @Test
  void bypassMatchesIpv4CidrBoundsAndHostBits() {
    ProxySelector private16 = HttpProxySelector.fixed(URL, "192.168.0.0/16");
    assertDirect(private16, "http://192.168.0.0");
    assertDirect(private16, "https://192.168.255.255");
    assertEquals(Proxy.Type.HTTP, select(private16, "http://192.169.0.0").type());
    assertEquals(Proxy.Type.HTTP, select(private16, "http://192.167.255.255").type());

    // 100.64.0.0/10 是运营商级 NAT 段，边界按位比较而非按十进制前缀。
    ProxySelector cgnat = HttpProxySelector.fixed(URL, "100.64.0.0/10");
    assertDirect(cgnat, "http://100.64.0.0");
    assertDirect(cgnat, "http://100.127.255.255");
    assertEquals(Proxy.Type.HTTP, select(cgnat, "http://100.128.0.0").type());
    assertEquals(Proxy.Type.HTTP, select(cgnat, "http://100.63.255.255").type());

    ProxySelector all4 = HttpProxySelector.fixed(URL, "0.0.0.0/0");
    assertDirect(all4, "http://8.8.8.8");
    assertDirect(all4, "http://10.0.0.0");

    ProxySelector single = HttpProxySelector.fixed(URL, "10.0.0.0/32");
    assertDirect(single, "http://10.0.0.0");
    assertEquals(Proxy.Type.HTTP, select(single, "http://10.0.0.1").type());

    // 主机位非 0 不需要规范化为网络地址，只比较前 16 位。
    ProxySelector hostBits = HttpProxySelector.fixed(URL, "192.168.1.5/16");
    assertDirect(hostBits, "http://192.168.99.99");
    assertEquals(Proxy.Type.HTTP, select(hostBits, "http://192.169.0.1").type());
  }

  /** 意图：IPv6 CIDR 支持 /0、/128、非整字节前缀，并归一化大小写与压缩写法。 */
  @Test
  void bypassMatchesIpv6CidrAcrossPrefixWidths() {
    ProxySelector doc = HttpProxySelector.fixed(URL, "2001:db8::/32");
    assertDirect(doc, "http://[2001:db8::1]");
    assertDirect(doc, "https://[2001:DB8:0:0:0:0:0:1]");
    assertEquals(Proxy.Type.HTTP, select(doc, "http://[2001:db9::1]").type());

    assertDirect(HttpProxySelector.fixed(URL, "::/0"), "http://[2001:db8::1]");
    ProxySelector single = HttpProxySelector.fixed(URL, "2001:db8::1/128");
    assertDirect(single, "http://[2001:db8::1]");
    assertEquals(Proxy.Type.HTTP, select(single, "http://[2001:db8::2]").type());

    // /126 只覆盖最后一个字节的高 6 位，验证非整字节掩码。
    ProxySelector bits = HttpProxySelector.fixed(URL, "2001:db8::/126");
    assertDirect(bits, "http://[2001:db8::3]");
    assertEquals(Proxy.Type.HTTP, select(bits, "http://[2001:db8::4]").type());
  }

  /** 意图：地址族不误匹配；IPv4-mapped IPv6 保持 IPv6 族，/120 不等价于 IPv4 /16。 */
  @Test
  void cidrKeepsAddressFamilyAndMappedIpv6() {
    assertEquals(
        Proxy.Type.HTTP,
        select(HttpProxySelector.fixed(URL, "0.0.0.0/0"), "http://[2001:db8::1]").type());
    assertEquals(
        Proxy.Type.HTTP, select(HttpProxySelector.fixed(URL, "::/0"), "http://8.8.8.8").type());
    // 域名永远不会命中 CIDR，也不触发目标 DNS。
    assertEquals(
        Proxy.Type.HTTP,
        select(HttpProxySelector.fixed(URL, "::/0"), "http://remote.invalid").type());
    assertEquals(
        Proxy.Type.HTTP,
        select(HttpProxySelector.fixed(URL, "10.0.0.0/8"), "http://remote.invalid").type());

    ProxySelector mapped = HttpProxySelector.fixed(URL, "::ffff:192.168.0.0/120");
    assertDirect(mapped, "http://[::ffff:192.168.0.5]");
    assertEquals(Proxy.Type.HTTP, select(mapped, "http://[::ffff:192.168.1.5]").type());
    assertEquals(Proxy.Type.HTTP, select(mapped, "http://192.168.0.5").type());
    // 反向：IPv4 /16 不匹配映射形式的目标。
    assertEquals(
        Proxy.Type.HTTP,
        select(HttpProxySelector.fixed(URL, "192.168.0.0/16"), "http://[::ffff:192.168.0.5]")
            .type());
  }

  /** 意图：用户真实 no_proxy 混合域名与 CIDR 初始化成功；CIDR 覆盖 OS 回退且适用各端口与协议。 */
  @Test
  void cidrInteroperatesWithHostsAndEnvironmentFallback() {
    String userNoProxy =
        "localhost,127.0.0.1,localaddress,.localdomain.com,.kk1.fun,192.168.0.0/16,"
            + "100.64.0.0/10,.local,.minimaxi.com,langbase.netease.com,.internal";
    ProxySelector selector = HttpProxySelector.fixed(URL, userNoProxy);
    for (String host :
        List.of(
            "localhost",
            "127.0.0.1",
            "localaddress",
            "a.localdomain.com",
            "api.kk1.fun",
            "192.168.10.20",
            "100.100.0.1",
            "x.local",
            "api.minimaxi.com",
            "langbase.netease.com",
            "svc.internal")) {
      assertDirect(selector, "http://" + host);
    }
    for (String host : List.of("127.0.0.2", "192.169.0.1", "100.128.0.1", "public.invalid")) {
      assertEquals(Proxy.Type.HTTP, select(selector, "http://" + host).type(), host);
    }

    // 环境形式同样接受 CIDR：命中时 DIRECT，未命中才委托 fallback（含 https 与 WebSocket）。
    RecordingFallback fallback = new RecordingFallback();
    ProxySelector environment =
        HttpProxySelector.fromEnvironment(
            Map.of("no_proxy", "192.168.0.0/16,100.64.0.0/10,2001:db8::/32"), fallback);
    assertDirect(environment, "http://192.168.1.1");
    assertDirect(environment, "wss://100.64.5.5");
    assertDirect(environment, "https://[2001:db8::9]");
    assertEquals(0, fallback.calls);
    assertEquals(fallback.proxy, select(environment, "http://192.169.0.1"));
    assertEquals(fallback.proxy, select(environment, "https://100.128.0.1"));
    assertEquals(2, fallback.calls);
  }

  /** 意图：所有非法配置都在构建时拒绝，异常消息及 cause 不泄漏原始输入。 */
  @Test
  void invalidConfigurationIsRedactedAndFailsClosed() {
    assertRedacted(() -> HttpProxySelector.fixed("", ""));
    assertDirect(
        HttpProxySelector.fromEnvironment(Map.of("https_proxy", ""), null),
        "https://remote.invalid");
    for (String value :
        List.of(
            "secret.invalid:80",
            "https://secret.invalid:80",
            "http://user:secret@host:80",
            "http://secret.invalid",
            "http://secret.invalid:0",
            "http://secret.invalid:65536",
            "http://secret.invalid:-1",
            "http://secret.invalid:80/",
            "http://secret.invalid:80/path",
            "http://secret.invalid:80?q=secret",
            "http://secret.invalid:80#secret",
            "http://secret invalid:80",
            "http://[fe80::1%25secret]:80")) {
      assertRedacted(() -> HttpProxySelector.fixed(value, ""));
      assertRedacted(() -> HttpProxySelector.fromEnvironment(Map.of("https_proxy", value), null));
    }
    for (String value :
        List.of(
            ",",
            "host,",
            " ",
            "10.0.0.0/33",
            "10.0.0.0/",
            "/8",
            "10.0.0.0/8/8",
            "10.0.0/8",
            "1.2.3.x/8",
            "10.0.0.256/8",
            "10.0.0.0.0/8",
            "10.0.0.0/abc",
            "10.0.0.0/-1",
            "10.0.0.0/8:80",
            "host/8",
            "*.example.com/24",
            "2001:db8::/129",
            "::ffff:1.2.3.4/129",
            "1:2:3:4:5:6:7:8:9/64",
            "fe80::1%eth0/64",
            "[::1]/128",
            "foo*bar",
            "*.*",
            ".*",
            "*.127.*",
            ".::1",
            ".127.0.0.1",
            "192.*",
            "[::1",
            "[localhost]",
            "[::1]bad",
            "[::1]:",
            "host:0",
            "host:65536",
            "host:abc",
            "host:123456",
            "host:-1",
            "::::",
            "fe80::1%eth0",
            "http://host")) {
      assertRedacted(() -> HttpProxySelector.fixed(URL, value));
    }
    assertRedacted(() -> HttpProxySelector.fixed(URL, null));
    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("http_proxy", null);
    assertRedacted(() -> HttpProxySelector.fromEnvironment(nullValue, null));
  }

  /** 意图：小写明确优先，协议独立；空值禁用该协议，no_proxy 覆盖系统回退结果。 */
  @Test
  void environmentPrecedenceAndFallback() {
    RecordingFallback fallback = new RecordingFallback();
    ProxySelector selector =
        HttpProxySelector.fromEnvironment(
            Map.of(
                "http_proxy",
                URL,
                "HTTP_PROXY",
                "invalid-secret",
                "HTTPS_PROXY",
                "http://secure.invalid:8443",
                "no_proxy",
                "bypass.invalid",
                "NO_PROXY",
                "*"),
            fallback);
    assertEquals("proxy.invalid", addressHost(select(selector, "ws://remote.invalid")));
    assertEquals("secure.invalid", addressHost(select(selector, "wss://remote.invalid")));
    assertDirect(selector, "https://bypass.invalid");
    assertEquals(0, fallback.calls);

    selector =
        HttpProxySelector.fromEnvironment(
            Map.of("http_proxy", URL, "no_proxy", "bypass.invalid"), fallback);
    assertDirect(selector, "https://bypass.invalid");
    assertEquals(0, fallback.calls);
    assertEquals(fallback.proxy, select(selector, "wss://remote.invalid/path?q=x%20y"));
    assertEquals("https", fallback.last.getScheme());
    assertEquals("q=x%20y", fallback.last.getRawQuery());
    assertEquals(fallback.proxy, select(selector, "https://remote.invalid"));
    selector.connectFailed(
        URI.create("https://remote.invalid"), new InetSocketAddress(80), new IOException());
    assertEquals(1, fallback.failures);
    selector.connectFailed(
        URI.create("http://remote.invalid"), new InetSocketAddress(80), new IOException());
    assertEquals(1, fallback.failures);

    selector =
        HttpProxySelector.fromEnvironment(
            Map.of("http_proxy", "", "HTTP_PROXY", URL, "no_proxy", "", "NO_PROXY", "*"), fallback);
    assertDirect(selector, "http://remote.invalid");
    assertEquals(fallback.proxy, select(selector, "wsS://remote.invalid"));
    selector = HttpProxySelector.fromEnvironment(Map.of(), fallback);
    assertEquals(fallback.proxy, select(selector, "ws://remote.invalid"));
    assertEquals("http", fallback.last.getScheme());
    assertDirect(HttpProxySelector.fromEnvironment(Map.of(), null), "https://remote.invalid");
    assertDirect(
        HttpProxySelector.fromEnvironment(Map.of("NO_PROXY", "*"), fallback),
        "http://remote.invalid");
    assertEquals(
        "proxy.invalid",
        addressHost(
            select(
                HttpProxySelector.fromEnvironment(Map.of("HTTP_PROXY", URL), null),
                "http://remote.invalid")));
    assertDirect(
        HttpProxySelector.fromEnvironment(Map.of("http_proxy", URL), null),
        "https://remote.invalid");
  }

  private static void assertRedacted(Runnable operation) {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, operation::run);
    assertEquals("Invalid HTTP proxy or bypass configuration", error.getMessage());
    assertNull(error.getCause());
  }

  private static String addressHost(Proxy proxy) {
    return ((InetSocketAddress) proxy.address()).getHostString();
  }

  private static Proxy select(ProxySelector selector, String target) {
    List<Proxy> result = selector.select(URI.create(target));
    assertEquals(1, result.size());
    return result.getFirst();
  }

  private static void assertDirect(ProxySelector selector, String target) {
    assertEquals(Proxy.NO_PROXY, select(selector, target), target);
  }

  private static final class RecordingFallback extends ProxySelector {
    private final Proxy proxy =
        new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("os.invalid", 9000));
    private int calls;
    private int failures;
    private URI last;

    @Override
    public List<Proxy> select(URI uri) {
      calls++;
      last = uri;
      return List.of(proxy);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress address, IOException error) {
      failures++;
    }
  }
}
