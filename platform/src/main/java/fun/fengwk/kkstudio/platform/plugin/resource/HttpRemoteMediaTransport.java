package fun.fengwk.kkstudio.platform.plugin.resource;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP 媒体传输的生产实现：下载与预签名上传使用互不干扰的两条客户端路径。
 *
 * <ul>
 *   <li><b>GET</b>：使用 Apache HttpClient5，路由阶段通过 {@link PublicAddressDnsResolver} 单次解析并校验全部地址， 直连和代理
 *       CONNECT 都固定到已校验 IP，TLS/HTTP 名称保留原域名。代理自身使用普通系统 DNS； 禁用自动重定向与重试，连接/响应空闲超时由配置给出，总期限仍由网关看门狗施加。
 *   <li><b>PUT</b>：使用 JDK {@link HttpClient} 直传预签名地址。该地址由本部署的 Storage 签发，可信且可能位于内网，因此这里不施加公网地址策略；
 *       请求体直接从临时文件流式发出，不把内容读进内存。
 * </ul>
 */
final class HttpRemoteMediaTransport implements RemoteMediaTransport {

  private final CloseableHttpClient downloadClient;
  private final HttpClient uploadClient;

  HttpRemoteMediaTransport(Duration connectTimeout, DnsResolver dnsResolver) {
    this(connectTimeout, dnsResolver, null, ProxySelector.getDefault());
  }

  /** 测试装配：可额外注入只面向本地测试服务器的 socket 策略，生产装配不注入并使用 JDK 默认信任与主机名校验。 */
  HttpRemoteMediaTransport(
      Duration connectTimeout, DnsResolver dnsResolver, TlsSocketStrategy tlsStrategy) {
    this(connectTimeout, dnsResolver, tlsStrategy, ProxySelector.getDefault());
  }

  HttpRemoteMediaTransport(
      Duration connectTimeout,
      DnsResolver dnsResolver,
      TlsSocketStrategy tlsStrategy,
      ProxySelector proxySelector) {
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    PoolingHttpClientConnectionManagerBuilder connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(Timeout.of(connectTimeout)).build());
    if (tlsStrategy != null) {
      connections.setTlsSocketStrategy(tlsStrategy);
    }
    this.downloadClient =
        HttpClients.custom()
            .setConnectionManager(connections.build())
            .setRoutePlanner(new PinnedMediaRoutePlanner(dnsResolver, proxySelector))
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build();
    this.uploadClient =
        HttpClient.newBuilder()
            .proxy(proxySelector)
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * 发起一次 GET。
   *
   * <p>地址解析与校验都由路由器使用注入的 {@link DnsResolver} 在选代理及建连前完成，私网地址、DNS rebinding 与解析失败都会在发出请求之前收敛为 {@link
   * PluginResourceUnavailableException}；响应不可读时 response 会在本次调用内立即关闭。
   *
   * <p>{@code responseTimeout} 只是响应空闲超时，不是整次下载的总期限；总期限由网关的看门狗施加，因此响应体被关闭时必须立刻中止仍在阻塞的读取。
   */
  @Override
  public MediaResponse get(URI uri, Duration timeout) {
    HttpGet request = new HttpGet(uri);
    request.setConfig(
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.of(timeout))
            .setResponseTimeout(Timeout.of(timeout))
            .build());
    CloseableHttpResponse response;
    try {
      response = downloadClient.execute(request);
    } catch (PluginResourceUnavailableException error) {
      // 地址准入拒绝必须保持原语义向上传递。
      throw error;
    } catch (IOException | RuntimeException error) {
      throw new PluginResourceUnavailableException("remote media request failed", error);
    }
    try {
      HttpEntity entity = response.getEntity();
      InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
      return new MediaResponse(
          response.getCode(),
          flatten(response.getHeaders()),
          new ResponseBodyStream(response, content));
    } catch (IOException | RuntimeException error) {
      // response 已经建立，任何后续失败都必须先把它释放掉，不能把连接留在未关闭状态。
      response.close(CloseMode.IMMEDIATE);
      throw new PluginResourceUnavailableException("remote media response is not readable", error);
    }
  }

  @Override
  public int put(URI uri, Map<String, String> headers, Path file, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        if (header.getKey() == null
            || header.getValue() == null
            || RemoteMediaTransport.isRestrictedHeader(header.getKey())) {
          continue;
        }
        builder.header(header.getKey(), header.getValue());
      }
    }
    try {
      builder.PUT(HttpRequest.BodyPublishers.ofFile(file));
    } catch (FileNotFoundException error) {
      throw new PluginResourceUnavailableException("staged media temp file disappeared", error);
    }
    try {
      return uploadClient
          .send(builder.build(), HttpResponse.BodyHandlers.discarding())
          .statusCode();
    } catch (IOException error) {
      throw new PluginResourceUnavailableException("presigned media upload failed", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PluginResourceUnavailableException("presigned media upload was interrupted", error);
    }
  }

  /**
   * 关闭下载与上传两个客户端。
   *
   * <p>JDK 21 的 {@link HttpClient} 同样持有连接与后台线程资源，必须一并释放；{@code shutdownNow} 立即回收而不等待在途请求，
   * 避免应用关闭时的无界阻塞。下载客户端关闭失败也要保证上传客户端被关闭。
   */
  @Override
  public void close() throws IOException {
    try {
      downloadClient.close();
    } finally {
      uploadClient.shutdownNow();
    }
  }

  private static Map<String, List<String>> flatten(Header[] headers) {
    Map<String, List<String>> flattened = new LinkedHashMap<>();
    for (Header header : headers) {
      if (header.getName() == null || header.getValue() == null) {
        continue;
      }
      flattened.computeIfAbsent(header.getName(), name -> new ArrayList<>()).add(header.getValue());
    }
    flattened.replaceAll((name, values) -> List.copyOf(values));
    return Map.copyOf(flattened);
  }

  /**
   * 让调用方只持有响应体也能释放 HttpClient5 的 response。
   *
   * <p>必须用 {@code CloseMode.IMMEDIATE}：排空路径会一直读到正文结束，服务端停顿时把调用线程一起阻塞住；IMMEDIATE 立刻断开底层连接， 中止仍在阻塞的
   * {@code read}——网关看门狗施加总期限时正依赖「关闭即中止」。正文已读完时连接已归还池，断开是空操作，不破坏复用。
   */
  private static final class ResponseBodyStream extends FilterInputStream {

    private final CloseableHttpResponse response;

    ResponseBodyStream(CloseableHttpResponse response, InputStream content) {
      super(content);
      this.response = response;
    }

    @Override
    public void close() {
      response.close(CloseMode.IMMEDIATE);
    }
  }
}
