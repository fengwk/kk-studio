package fun.fengwk.kkstudio.core.ai.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.ai.image.configuration.GptImage2Properties;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Response;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Size;
import fun.fengwk.kkstudio.core.ai.image.model.ImageData;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.image.configuration.GptImage2Properties;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Response;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Size;
import fun.fengwk.kkstudio.core.ai.image.model.ImageData;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/**
 * @author fengwk
 */
public class GptImage2ServiceUnitTest {

  @Test
  public void shouldGenerateImageByPostingJsonRequest() throws Exception {
    RecordingHttpClient httpClient =
        new RecordingHttpClient(
            200,
            "{\"image\":{\"mimeType\":\"image/png\",\"base64\":\"AQID\"},\"conversationText\":\"ok\"}");
    GptImage2Service service = new GptImage2Service(properties(), httpClient);

    GptImage2Response response =
        service.generate(
            "draw", GptImage2Size.SQUARE, List.of(ImageData.of("image/png", new byte[] {4, 5, 6})));

    assertEquals("ok", response.getConversationText());
    assertEquals("image/png", response.getImage().getMimeType());
    assertArrayEquals(new byte[] {1, 2, 3}, response.getImage().decode());
    assertEquals(URI.create("http://image.local/generate"), httpClient.request.uri());
    assertEquals(Optional.of(Duration.ofMillis(1234)), httpClient.request.timeout());
    assertEquals(
        Optional.of("Bearer test-api-key"),
        httpClient.request.headers().firstValue("Authorization"));
    assertEquals(
        Optional.of("application/json"), httpClient.request.headers().firstValue("Content-Type"));
    assertTrue(readBody(httpClient.request).contains("\"prompt\":\"draw\""));
    assertTrue(readBody(httpClient.request).contains("\"size\":\"1:1\""));
    assertTrue(readBody(httpClient.request).contains("\"images\""));
  }

  @Test
  public void shouldRejectFailedImageResponse() {
    GptImage2Service service =
        new GptImage2Service(properties(), new RecordingHttpClient(500, "{\"error\":\"boom\"}"));

    IOException exception =
        assertThrows(IOException.class, () -> service.generate("draw", null, null));
    assertTrue(exception.getMessage().contains("status 500"));
    assertTrue(exception.getMessage().contains("boom"));
  }

  @Test
  public void shouldRejectNullPrompt() {
    GptImage2Service service =
        new GptImage2Service(properties(), new RecordingHttpClient(200, "{}"));

    assertThrows(NullPointerException.class, () -> service.generate(null, null, null));
  }

  private GptImage2Properties properties() {
    GptImage2Properties properties = new GptImage2Properties();
    properties.setUrl("http://image.local/generate");
    properties.setApiKey("test-api-key");
    properties.setTimeoutMs(1234L);
    return properties;
  }

  private static String readBody(HttpRequest request) throws InterruptedException {
    AtomicReference<String> bodyRef = new AtomicReference<>("");
    CountDownLatch done = new CountDownLatch(1);
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              private final StringBuilder body = new StringBuilder();

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item));
              }

              @Override
              public void onError(Throwable throwable) {
                done.countDown();
              }

              @Override
              public void onComplete() {
                bodyRef.set(body.toString());
                done.countDown();
              }
            });
    assertTrue(done.await(5, TimeUnit.SECONDS));
    return bodyRef.get();
  }

  private static final class RecordingHttpClient extends HttpClient {

    private final int statusCode;
    private final String responseBody;
    private HttpRequest request;

    private RecordingHttpClient(int statusCode, String responseBody) {
      this.statusCode = statusCode;
      this.responseBody = responseBody;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      this.request = request;
      return new StringHttpResponse<>(request, statusCode, responseBody);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      return CompletableFuture.completedFuture(send(request, responseBodyHandler));
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }
  }

  private record StringHttpResponse<T>(HttpRequest request, int statusCode, String rawBody)
      implements HttpResponse<T> {

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public T body() {
      @SuppressWarnings("unchecked")
      T body = (T) rawBody;
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
