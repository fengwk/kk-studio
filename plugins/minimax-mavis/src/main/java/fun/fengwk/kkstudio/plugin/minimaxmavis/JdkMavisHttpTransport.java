package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.io.IOException;
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
public final class JdkMavisHttpTransport implements MavisHttpTransport {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client;

  public JdkMavisHttpTransport() {
    this(
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
  }

  JdkMavisHttpTransport(HttpClient client) {
    this.client = client;
  }

  @Override
  public MavisHttpResponse send(MavisHttpRequest request) {
    HttpRequest httpRequest;
    try {
      httpRequest = build(request);
    } catch (IllegalArgumentException error) {
      throw new MavisTransportException(error.getClass().getSimpleName(), error);
    }
    try {
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new MavisHttpResponse(response.statusCode(), response.body());
    } catch (IOException error) {
      throw new MavisTransportException(error.getClass().getSimpleName(), error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new MavisTransportException(error.getClass().getSimpleName(), error);
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
