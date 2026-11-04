package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.platform.plugin.support.DeadlineResponseBody;
import fun.fengwk.kkstudio.platform.plugin.support.PluginHttpSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 基于 JDK {@link HttpClient} 的生产传输实现。
 *
 * <p>固定 HTTP/1.1、禁止自动 redirect、显式 connect timeout，并且每次 {@link #send(MavisHttpRequest)} 只发送一个请求：不做
 * 自动重试，因为生成类调用是非幂等的，断连后的结果是不确定的。
 */
public class MavisHttpTransport {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

  private final HttpClient client;

  public MavisHttpTransport() {
    this(
        HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
  }

  MavisHttpTransport(HttpClient client) {
    this.client = client;
  }

  public MavisHttpResponse send(MavisHttpRequest request) {
    HttpRequest httpRequest;
    try {
      httpRequest = build(request);
    } catch (IllegalArgumentException error) {
      throw new MavisTransportException(error.getClass().getSimpleName());
    }
    long deadline = System.nanoTime() + request.timeout().toNanos();
    try {
      HttpResponse<InputStream> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = new DeadlineResponseBody(response.body(), deadline)) {
        if (PluginHttpSupport.isAuthRejected(response.statusCode())) {
          return new MavisHttpResponse(response.statusCode(), "");
        }
        return new MavisHttpResponse(
            response.statusCode(),
            new String(
                PluginHttpSupport.readBounded(body, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8));
      }
    } catch (IOException error) {
      throw new MavisTransportException(error.getClass().getSimpleName());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new MavisTransportException(error.getClass().getSimpleName());
    }
  }

  private static HttpRequest build(MavisHttpRequest request) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(request.url())).timeout(request.timeout());
    request.headers().forEach(builder::header);
    if ("GET".equals(request.method())) {
      builder.GET();
    } else {
      builder.method(
          request.method(),
          request.body() == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
    }
    return builder.build();
  }
}
