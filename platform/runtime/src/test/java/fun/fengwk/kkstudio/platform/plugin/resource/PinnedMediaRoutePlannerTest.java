package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/** 路由的非网络边界守卫：默认端口、拒绝不支持的代理，以及解析失败。真实 TLS/CONNECT 见 transport 测试。 */
class PinnedMediaRoutePlannerTest {

  /** 省略端口时按协议补齐；HTTP 代理同样必须 CONNECT 到 pin IP，不能让代理重新解析域名。 */
  @Test
  void normalizesDefaultPortsAndPinsPlainHttpTunnels() throws Exception {
    DnsResolver dns = mock(DnsResolver.class);
    when(dns.resolve("media.example.com"))
        .thenReturn(new InetAddress[] {InetAddress.getByName("8.8.8.8")});
    ProxySelector selector = mock(ProxySelector.class);
    when(selector.select(URI.create("http://media.example.com:80")))
        .thenReturn(List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 3128))));
    when(selector.select(URI.create("https://media.example.com:443")))
        .thenReturn(List.of(Proxy.NO_PROXY));
    PinnedMediaRoutePlanner planner = new PinnedMediaRoutePlanner(dns, selector);
    HttpRoute plain = planner.determineRoute(new HttpHost("http", "media.example.com", -1), null);
    assertTrue(plain.isTunnelled());
    assertEquals("8.8.8.8:80", plain.getTargetHost().toHostString());
    assertEquals("media.example.com", plain.getTargetName().getHostName());
    HttpRoute secure = planner.determineRoute(new HttpHost("https", "media.example.com", -1), null);
    assertTrue(secure.isSecure());
    assertEquals(443, secure.getTargetHost().getPort());
  }

  /** 即使注入异常的 SOCKS 策略也不能暗中退回直连，解析失败必须收敛为资源不可用。 */
  @Test
  void rejectsUnsupportedProxyAndConvergesDnsFailure() throws Exception {
    DnsResolver dns = mock(DnsResolver.class);
    when(dns.resolve("media.example.com"))
        .thenReturn(new InetAddress[] {InetAddress.getByName("8.8.8.8")});
    when(dns.resolve("missing.example.com")).thenThrow(new UnknownHostException("test missing"));
    ProxySelector selector = mock(ProxySelector.class);
    when(selector.select(URI.create("https://media.example.com:443")))
        .thenReturn(List.of(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 1080))));
    PinnedMediaRoutePlanner planner = new PinnedMediaRoutePlanner(dns, selector);
    assertThrows(
        PluginResourceUnavailableException.class,
        () -> planner.determineRoute(new HttpHost("https", "media.example.com", -1), null));
    PluginResourceUnavailableException error =
        assertThrows(
            PluginResourceUnavailableException.class,
            () -> planner.determineRoute(new HttpHost("https", "missing.example.com", -1), null));
    assertEquals("remote media host cannot be resolved", error.getMessage());
    assertTrue(error.getCause() instanceof UnknownHostException);
  }
}
