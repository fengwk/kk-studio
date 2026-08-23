package fun.fengwk.kkstudio.platform.canvas.function.opencli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.platform.canvas.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/** Hub wire 与固定长度 publisher 的失败语义必须保持确定、可关闭且 fail closed。 */
class OpenCliHubClientContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void parsesExecutionResourcesNullableUrlsAndAllTerminalStatuses() {
    for (ExecutionStatus status :
        List.of(ExecutionStatus.FAILED, ExecutionStatus.TIMED_OUT, ExecutionStatus.CANCELLED)) {
      String data =
          """
          {"id":"exec-1","status":"%s","stdout":"","stderr":"",
           "stdoutTruncated":false,"stderrTruncated":false,
           "resources":[
             {"fileName":"a.png","mimeType":"image/png","size":3,
              "contentUrl":null,"downloadUrl":"/api/resources/a.png"},
             {"fileName":"b.mp4","mimeType":"video/mp4","size":4,
              "contentUrl":"/api/resources/b.mp4","downloadUrl":null}
           ]}
          """
              .formatted(status);
      OpenCliHubClient client = clientReturning(jsonResponse(200, envelope(data)));

      Execution execution = client.getExecution("exec-1", 5);

      assertEquals(status, execution.status());
      assertEquals(2, execution.resources().size());
      assertEquals("/api/resources/a.png", execution.resources().get(0).downloadUrl());
      assertEquals("/api/resources/b.mp4", execution.resources().get(1).contentUrl());
      assertTrue(status.terminal());
    }
    assertFalse(ExecutionStatus.RUNNING.terminal());
  }

  @Test
  void rejectsMalformedEnvelopeAndExecutionResourceShapes() {
    assertHubFailure(response(200, "text/plain", "{}"));
    assertHubFailure(jsonResponse(200, "{\"status\":201,\"code\":\"OK\",\"data\":{}}"));
    assertHubFailure(
        jsonResponse(200, "{\"status\":200,\"code\":\"OK\",\"success\":false,\"data\":{}}"));
    assertHubFailure(jsonResponse(200, "not-json"));
    assertExecutionFailure("\"resources\":{}");
    assertExecutionFailure(
        "\"resources\":[{\"fileName\":\"x\",\"mimeType\":\"image/png\",\"size\":0}]");
    assertExecutionFailure(
        "\"resources\":[{\"fileName\":\"x\",\"mimeType\":\"image/png\",\"size\":\"3\"}]");
    assertExecutionFailure(
        "\"resources\":[{\"fileName\":\"x\",\"mimeType\":\"image/png\",\"size\":3,"
            + "\"downloadUrl\":\"\"}]");
    assertExecutionFailure("\"stdoutTruncated\":true,\"resources\":[]");
    assertExecutionFailure("\"stdout\":5,\"resources\":[]");

    SystemSettingsSnapshot limited = snapshot(hub(1024, 512 * 1024, 4096, 1024));
    String data = executionData("\"stdout\":\"" + "x".repeat(1025) + "\",\"resources\":[]");
    assertThrows(
        OpenCliHubException.class,
        () ->
            new OpenCliHubClient(
                    properties(),
                    limited,
                    MAPPER,
                    new StubHttpClient(request -> jsonResponse(200, envelope(data))))
                .getExecution("exec-1", 0));
  }

  @Test
  void boundsJsonAndErrorBodiesAndPreservesSanitizedHttpDetail() {
    SystemSettingsSnapshot limitedJson = snapshot(hub(1024, 1024, 4096, 65_535));
    OpenCliHubClient jsonClient =
        new OpenCliHubClient(
            properties(),
            limitedJson,
            MAPPER,
            new StubHttpClient(
                request ->
                    response(
                        200,
                        "application/json",
                        "{\"status\":200,\"code\":\"OK\",\"data\":\"" + "x".repeat(1100) + "\"}")));
    assertThrows(OpenCliHubException.class, () -> jsonClient.getExecution("exec-1", 0));

    SystemSettingsSnapshot limitedError = snapshot(hub(1024, 512 * 1024, 256, 65_535));
    OpenCliHubClient errorClient =
        new OpenCliHubClient(
            properties(),
            limitedError,
            MAPPER,
            new StubHttpClient(request -> response(500, "text/plain", "bad\n" + "x".repeat(300))));
    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> errorClient.getExecution("exec-1", 0));
    assertTrue(error.getMessage().contains("bad"));
    assertTrue(error.getMessage().contains("[truncated]"));
    assertFalse(error.getMessage().contains("\n"));
  }

  @Test
  void validatesResourceOriginPathLengthAndMediaTypeBeforeExposingStream() throws IOException {
    byte[] media = new byte[] {1, 2, 3};
    OpenCliHubClient success = clientReturning(response(200, Map.of("Content-Length", "3"), media));
    ExecutionResource fallbackMime =
        new ExecutionResource("a.png", "image/png", 3L, null, "/api/resources/a.png");
    try (HubResourceStream stream = success.openResource(fallbackMime)) {
      assertEquals("image/png", stream.mediaType());
      assertEquals(3L, stream.size());
      assertEquals(3, stream.content().readAllBytes().length);
    }

    OpenCliHubClient mismatch =
        clientReturning(
            response(200, Map.of("Content-Length", "3", "Content-Type", "image/png"), media));
    assertThrows(
        OpenCliHubException.class,
        () ->
            mismatch.openResource(
                new ExecutionResource("a.png", "image/png", 2L, null, "/api/resources/a.png")));

    OpenCliHubClient missingType =
        clientReturning(response(200, Map.of("Content-Length", "3"), media));
    assertThrows(
        OpenCliHubException.class,
        () ->
            missingType.openResource(
                new ExecutionResource("a", null, 3L, null, "/api/resources/a")));

    OpenCliHubClient neverCalled =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("resource request must be rejected before transport");
                }));
    for (ExecutionResource invalid :
        List.of(
            new ExecutionResource("a", "image/png", 1L, null, null),
            new ExecutionResource("a", "image/png", 1L, null, "bad path"),
            new ExecutionResource(
                "a", "image/png", 1L, null, "http://user@hub.test:8080/api/resources/a"),
            new ExecutionResource(
                "a", "image/png", 1L, null, "http://hub.test:8080/api/resources/a#x"),
            new ExecutionResource(
                "a", "image/png", 1L, null, "http://other.test:8080/api/resources/a"),
            new ExecutionResource("a", "image/png", 1L, null, "/outside/a"),
            new ExecutionResource("a", "image/png", 1L, null, "/api/resources/../a"))) {
      assertThrows(OpenCliHubException.class, () -> neverCalled.openResource(invalid));
    }
  }

  @Test
  void fixedLengthPublisherCompletesExactlyAndRejectsEarlyEofAndReplay() {
    TrackingInputStream exact = new TrackingInputStream(new byte[] {1, 2, 3});
    Throwable[] replayError = new Throwable[1];
    OpenCliHubClient exactClient =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
                  RecordingSubscriber first = new RecordingSubscriber(Long.MAX_VALUE, false);
                  publisher.subscribe(first);
                  assertTrue(first.completed);
                  assertNull(first.error);
                  RecordingSubscriber second = new RecordingSubscriber(Long.MAX_VALUE, false);
                  publisher.subscribe(second);
                  replayError[0] = second.error;
                  return jsonResponse(
                      201,
                      "{\"status\":201,\"code\":\"CREATED\",\"data\":{\"items\":[{"
                          + "\"resourcePath\":\"/resources/a.png\"}]}}");
                }));

    assertEquals(
        "/resources/a.png", exactClient.upload("a.png", "image/png", 3L, exact).resourcePath());
    assertTrue(exact.closed);
    assertNotNull(replayError[0]);
    assertTrue(messageChain(replayError[0]).contains("cannot be replayed"));

    TrackingInputStream shortInput = new TrackingInputStream(new byte[] {1, 2});
    OpenCliHubClient earlyEofClient =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, false);
                  request.bodyPublisher().orElseThrow().subscribe(subscriber);
                  throw new IOException("send failed", subscriber.error);
                }));
    OpenCliHubException earlyEof =
        assertThrows(
            OpenCliHubException.class,
            () -> earlyEofClient.upload("a.png", "image/png", 3L, shortInput));
    assertTrue(messageChain(earlyEof).contains("ended before declared Content-Length"));
    assertTrue(shortInput.closed);
  }

  @Test
  void fixedLengthPublisherCancellationClosesInputWithoutTerminalSignal() {
    TrackingInputStream input = new TrackingInputStream(new byte[] {1, 2, 3});
    RecordingSubscriber subscriber = new RecordingSubscriber(1L, true);
    OpenCliHubClient client =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  request.bodyPublisher().orElseThrow().subscribe(subscriber);
                  throw new IOException("cancelled");
                }));

    assertThrows(OpenCliHubException.class, () -> client.upload("a.png", "image/png", 3L, input));
    assertTrue(input.closed);
    assertFalse(subscriber.completed);
    assertNull(subscriber.error);
    assertTrue(subscriber.chunks >= 2);
  }

  @Test
  void transportInterruptionDisabledAndArgumentBoundariesFailClosed() {
    OpenCliHubClient ioClient =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  throw new IOException("offline");
                }));
    assertThrows(OpenCliHubException.class, () -> ioClient.getExecution("exec-1", 0));
    assertThrows(
        OpenCliHubException.class,
        () ->
            ioClient.openResource(
                new ExecutionResource("a.png", "image/png", 1L, null, "/api/resources/a.png")));

    OpenCliHubClient interrupted =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  throw new InterruptedException("stop");
                }));
    assertThrows(OpenCliHubException.class, () -> interrupted.getExecution("exec-1", 0));
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
    assertThrows(
        OpenCliHubException.class,
        () ->
            interrupted.openResource(
                new ExecutionResource("a.png", "image/png", 1L, null, "/api/resources/a.png")));
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();

    OpenCliHubClient disabled =
        new OpenCliHubClient(
            properties(),
            snapshot(SystemSettings.OpenCliHub.DEFAULT),
            MAPPER,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("disabled client must not send");
                }));
    assertThrows(
        OpenCliHubException.class, () -> disabled.execute(List.of("agent", "command"), 1L));

    OpenCliHubClient client =
        new OpenCliHubClient(
            properties(),
            snapshot(),
            MAPPER,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("invalid arguments must not send");
                }));
    assertThrows(IllegalArgumentException.class, () -> client.execute(List.of(), 1L));
    assertThrows(
        IllegalArgumentException.class, () -> client.execute(Arrays.asList("agent", null), 1L));
    assertThrows(IllegalArgumentException.class, () -> client.execute(List.of("agent", ""), 1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.execute(List.of("agent"), Duration.ofMinutes(31).toMillis()));
    assertThrows(IllegalArgumentException.class, () -> client.getExecution("bad id", 0));
    assertThrows(IllegalArgumentException.class, () -> client.getExecution("exec-1", -1));
  }

  private static void assertExecutionFailure(String fields) {
    OpenCliHubClient client = clientReturning(jsonResponse(200, envelope(executionData(fields))));
    assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
  }

  private static void assertHubFailure(HttpResponse<InputStream> response) {
    OpenCliHubClient client = clientReturning(response);
    assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
  }

  private static String executionData(String fields) {
    return """
        {"id":"exec-1","status":"SUCCEEDED","stdout":"","stderr":"",
         "stdoutTruncated":false,"stderrTruncated":false,%s}
        """
        .formatted(fields);
  }

  private static String envelope(String data) {
    return "{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":" + data + "}";
  }

  private static OpenCliHubProperties properties() {
    return new OpenCliHubProperties();
  }

  private static SystemSettingsSnapshot snapshot() {
    return snapshot(hub());
  }

  private static SystemSettingsSnapshot snapshot(SystemSettings.OpenCliHub hub) {
    return new SystemSettingsSnapshot(settings(hub));
  }

  private static SystemSettings settings(SystemSettings.OpenCliHub hub) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        SystemSettings.AiRuntime.DEFAULT,
        SystemSettings.Environment.DEFAULT,
        new SystemSettings.Integrations(
            SystemSettings.Comfyui.DEFAULT,
            hub,
            SystemSettings.Seedance.DEFAULT,
            SystemSettings.GptImage2.DEFAULT,
            SystemSettings.MiniMaxH3.DEFAULT),
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }

  private static SystemSettings.OpenCliHub hub() {
    return hub(1024, 512 * 1024, 4096, 65_535);
  }

  private static SystemSettings.OpenCliHub hub(
      int streamBufferBytes,
      int maxJsonResponseBytes,
      int maxErrorResponseBytes,
      int maxOutputChars) {
    return new SystemSettings.OpenCliHub(
        true,
        "http://hub.test:8080",
        5_000L,
        120_000L,
        130_000L,
        streamBufferBytes,
        maxJsonResponseBytes,
        maxErrorResponseBytes,
        maxOutputChars);
  }

  private static OpenCliHubClient clientReturning(HttpResponse<InputStream> response) {
    return new OpenCliHubClient(
        properties(), snapshot(), MAPPER, new StubHttpClient(request -> response));
  }

  private static HttpResponse<InputStream> jsonResponse(int status, String body) {
    return response(status, "application/json", body);
  }

  private static HttpResponse<InputStream> response(int status, String contentType, String body) {
    return response(
        status,
        Map.of("Content-Type", contentType, "Content-Length", Integer.toString(body.length())),
        body.getBytes(StandardCharsets.UTF_8));
  }

  private static HttpResponse<InputStream> response(
      int status, Map<String, String> headers, byte[] body) {
    HttpHeaders httpHeaders =
        HttpHeaders.of(
            headers.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue()))),
            (name, value) -> true);
    return new StubHttpResponse(status, httpHeaders, new ByteArrayInputStream(body));
  }

  private static String messageChain(Throwable error) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
    }
    return messages.toString();
  }

  @FunctionalInterface
  private interface Responder {
    HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
  }

  private static final class StubHttpClient extends HttpClient {

    private final Responder responder;

    private StubHttpClient(Responder responder) {
      this.responder = responder;
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
      return new SSLParameters();
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

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      return (HttpResponse<T>) responder.send(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> responseBodyHandler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class StubHttpResponse implements HttpResponse<InputStream> {

    private final int status;
    private final HttpHeaders headers;
    private final InputStream body;

    private StubHttpResponse(int status, HttpHeaders headers, InputStream body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return status;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<InputStream>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public InputStream body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("http://hub.test:8080");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private static final class RecordingSubscriber implements Flow.Subscriber<ByteBuffer> {

    private final long initialDemand;
    private final boolean cancelOnSecondChunk;
    private Flow.Subscription subscription;
    private int chunks;
    private boolean completed;
    private Throwable error;

    private RecordingSubscriber(long initialDemand, boolean cancelOnSecondChunk) {
      this.initialDemand = initialDemand;
      this.cancelOnSecondChunk = cancelOnSecondChunk;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(initialDemand);
    }

    @Override
    public void onNext(ByteBuffer item) {
      chunks++;
      if (cancelOnSecondChunk && chunks == 1) {
        subscription.request(1L);
      } else if (cancelOnSecondChunk && chunks == 2) {
        subscription.cancel();
      }
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onComplete() {
      completed = true;
    }
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private TrackingInputStream(byte[] buffer) {
      super(buffer);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
