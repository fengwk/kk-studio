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
            "10.0.0.0/8",
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
