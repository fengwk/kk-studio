package fun.fengwk.kkstudio.platform.plugin.resource;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.RouteInfo.LayerType;
import org.apache.hc.client5.http.RouteInfo.TunnelType;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * 媒体目标先整体校验、单次解析，再选路；代理只负责连接已校验 IP，绝不代为解析媒体域名。
 *
 * <p>socket 目标和 TLS 名称刻意分离：直连的地址固定在 HttpHost，CONNECT 的 authority 是 IP， targetName
 * 始终为原域名（SNI/证书校验）；HTTP 请求的 authority 不改写。代理自己的 DNS 使用连接管理器的系统解析器， 因此本机/内网代理可用，但不放宽媒体地址准入。
 */
final class PinnedMediaRoutePlanner implements HttpRoutePlanner {

  private final DnsResolver dnsResolver;
  private final ProxySelector proxySelector;

  PinnedMediaRoutePlanner(DnsResolver dnsResolver, ProxySelector proxySelector) {
    this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
    this.proxySelector = Objects.requireNonNull(proxySelector, "proxySelector");
  }

  @Override
  public HttpRoute determineRoute(HttpHost target, HttpContext context) {
    InetAddress address;
    try {
      // PublicAddressDnsResolver 校验全部答案；选取其中一个，禁止重试后重新解析。
      address = dnsResolver.resolve(target.getHostName())[0];
    } catch (UnknownHostException error) {
      throw new PluginResourceUnavailableException("remote media host cannot be resolved", error);
    }
    boolean secure = "https".equalsIgnoreCase(target.getSchemeName());
    int port = target.getPort() >= 0 ? target.getPort() : secure ? 443 : 80;
    HttpHost name = new HttpHost(target.getSchemeName(), target.getHostName(), port);
    Proxy proxy = proxySelector.select(URI.create(name.toURI())).getFirst();
    if (proxy.type() == Proxy.Type.DIRECT) {
      HttpHost pinned = new HttpHost(target.getSchemeName(), address, target.getHostName(), port);
      return new HttpRoute(pinned, name, null, secure);
    }
    if (proxy.type() != Proxy.Type.HTTP
        || !(proxy.address() instanceof InetSocketAddress socketAddress)) {
      throw new PluginResourceUnavailableException("remote media requires an HTTP proxy");
    }
    HttpHost proxyHost =
        new HttpHost("http", socketAddress.getHostString(), socketAddress.getPort());
    HttpHost pinned = new HttpHost(target.getSchemeName(), address, address.getHostAddress(), port);
    // 即使测试使用 HTTP 也通过 CONNECT pin IP；生产网关只准入 HTTPS。
    return new HttpRoute(
        pinned,
        name,
        null,
        new HttpHost[] {proxyHost},
        secure,
        TunnelType.TUNNELLED,
        secure ? LayerType.LAYERED : LayerType.PLAIN);
  }
}
