package fun.fengwk.kkstudio.plugin.opencli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.HubResourceStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Hub wire 与固定长度 publisher 的失败语义必须保持确定、可关闭且 fail closed。 */
class OpenCliHubClientContractTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String DEFAULT_KEY = "sk-opencli-contract-test";

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

    OpenCliSettings limited = hub(1024, 512 * 1024, 4096, 1024);
    String data = executionData("\"stdout\":\"" + "x".repeat(1025) + "\",\"resources\":[]");
    assertThrows(
        OpenCliHubException.class,
        () ->
            new OpenCliHubClient(
                    limited,
                    MAPPER,
                    validCredentialAccess(DEFAULT_KEY),
                    new StubHttpClient(request -> jsonResponse(200, envelope(data))))
                .getExecution("exec-1", 0));
  }

  /** 合法执行结果不能让溢出的状态整数截断成 HTTP 200 后通过信封校验。 */
  @Test
  void rejectsEnvelopeStatusThatOnlyMatchesAfterIntegerTruncation() {
    for (String status : List.of("4294967496", "18446744073709551816")) {
      String response =
          "{\"status\":"
              + status
              + ",\"code\":\"OK\",\"data\":"
              + executionData("\"resources\":[]")
              + "}";
      OpenCliHubClient client = clientReturning(jsonResponse(200, response));
      OpenCliHubException error =
          assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
      assertEquals(
          "Malformed OpenCLI Hub response: Result envelope status must match HTTP status",
          error.getMessage());
    }
  }

  @Test
  void boundsJsonAndErrorBodiesWithoutEchoingRemoteDetail() {
    OpenCliSettings limitedJson = hub(1024, 1024, 4096, 65_535);
    OpenCliHubClient jsonClient =
        new OpenCliHubClient(
            limitedJson,
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request ->
                    response(
                        200,
                        "application/json",
                        "{\"status\":200,\"code\":\"OK\",\"data\":\"" + "x".repeat(1100) + "\"}")));
    assertThrows(OpenCliHubException.class, () -> jsonClient.getExecution("exec-1", 0));

    OpenCliSettings limitedError = hub(1024, 512 * 1024, 256, 65_535);
    OpenCliHubClient errorClient =
        new OpenCliHubClient(
            limitedError,
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(request -> response(500, "text/plain", "bad\n" + "x".repeat(300))));
    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> errorClient.getExecution("exec-1", 0));
    // 远端错误正文无论是否超限都不进入公开消息，只保留 HTTP 状态。
    assertEquals("OpenCLI Hub HTTP 500", error.getMessage());
    assertFalse(error.getMessage().contains("bad"));
    assertFalse(error.getMessage().contains("[truncated]"));
    assertNull(error.getCause());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void localTransportThrowableSecretNeverReachesPublicErrorOrLogs(CapturedOutput output) {
    // 本地 transport 失败 message 携带合成凭据 URL：公开异常链与诊断日志都不得出现该 marker。
    String marker = "https://user:s3cr3t@hub.test/private/path?token=abcdef";
    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request -> {
                  throw new IOException("cannot connect to " + marker);
                }));

    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));

    assertEquals("OpenCLI Hub request failed", error.getMessage());
    assertNull(error.getCause());
    assertFalse(chainText(error).contains("s3cr3t"));
    // 诊断只记录受控 stage 与异常 class，不含 marker、cause 或 suppressed。
    assertTrue(output.getAll().contains("type=IOException"));
    assertFalse(output.getAll().contains("s3cr3t"));
  }

  @Test
  void unknownExecutionStatusMarkerIsNotEchoed() {
    String marker = "sk-live-unknown-status-marker";
    String data =
        "{\"id\":\"exec-1\",\"status\":\""
            + marker
            + "\",\"stdout\":\"\",\"stderr\":\"\",\"stdoutTruncated\":false,"
            + "\"stderrTruncated\":false,\"resources\":[]}";
    OpenCliHubClient client = clientReturning(jsonResponse(200, envelope(data)));

    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));

    assertTrue(error.getMessage().contains("unknown OpenCLI Hub execution status"));
    assertFalse(error.getMessage().contains(marker));
    assertNull(error.getCause());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void returnedResourceStreamReadAndCloseFailuresAreSanitized(CapturedOutput output)
      throws IOException {
    // 返回给调用方的 Hub 媒体流：底层 read/close 的 marker 不得进入公开异常或日志。
    String marker = "https://user:s3cr3t@hub.test/private/path";

    InputStream readAndCloseFail =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read " + marker);
          }

          @Override
          public void close() throws IOException {
            throw new IOException("close " + marker);
          }
        };
    HubResourceStream both =
        clientReturning(streamResponse(5L, readAndCloseFail)).openResource(resource(5L));
    IOException readError = assertThrows(IOException.class, () -> both.content().readAllBytes());
    // 主读取失败优先，次要关闭失败已收敛为固定诊断，不进入 cause/suppressed。
    assertEquals("response body read failed", readError.getMessage());
    assertNull(readError.getCause());
    assertEquals(0, readError.getSuppressed().length);
    assertFalse(chainText(readError).contains("s3cr3t"));
    both.close();

    InputStream closeFail =
        new InputStream() {
          private int index;

          @Override
          public int read() {
            return index++ < 5 ? 1 : -1;
          }

          @Override
          public void close() throws IOException {
            throw new IOException("close " + marker);
          }
        };
    HubResourceStream closeOnly =
        clientReturning(streamResponse(5L, closeFail)).openResource(resource(5L));
    assertEquals(1, closeOnly.content().read());
    IOException closeError = assertThrows(IOException.class, closeOnly::close);
    assertEquals("response body close failed", closeError.getMessage());
    assertNull(closeError.getCause());
    assertEquals(0, closeError.getSuppressed().length);
    assertFalse(chainText(closeError).contains("s3cr3t"));
    assertFalse(output.getAll().contains("s3cr3t"));
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

    AtomicInteger resolveCount = new AtomicInteger();
    PluginCredentialAccess countedAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            resolveCount.incrementAndGet();
            return validSnapshot(DEFAULT_KEY);
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    OpenCliHubClient neverCalled =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            countedAccess,
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
    assertEquals(0, resolveCount.get());
  }

  @Test
  void fixedLengthPublisherCompletesExactlyAndRejectsEarlyEofAndReplay() {
    TrackingInputStream exact = new TrackingInputStream(new byte[] {1, 2, 3});
    Throwable[] replayError = new Throwable[1];
    OpenCliHubClient exactClient =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request -> {
                  HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
                  RecordingSubscriber first = new RecordingSubscriber(Long.MAX_VALUE, false);
                  publisher.subscribe(first);
                  assertTrue(first.completed);
                  assertNull(first.error);
                  RecordingSubscriber second = new RecordingSubscriber(Long.MAX_VALUE, false);
                  publisher.subscribe(second);
                  if (second.subscription != null) {
                    second.subscription.request(1L);
                    second.subscription.cancel();
                  }
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
    Throwable[] publisherError = new Throwable[1];
    OpenCliHubClient earlyEofClient =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request -> {
                  RecordingSubscriber subscriber = new RecordingSubscriber(Long.MAX_VALUE, false);
                  request.bodyPublisher().orElseThrow().subscribe(subscriber);
                  publisherError[0] = subscriber.error;
                  throw new IOException("send failed", subscriber.error);
                }));
    OpenCliHubException earlyEof =
        assertThrows(
            OpenCliHubException.class,
            () -> earlyEofClient.upload("a.png", "image/png", 3L, shortInput));
    // publisher 的真实早期 EOF 结果由 subscriber 观察；客户端只暴露固定阶段，不传播原始 cause。
    assertNotNull(publisherError[0]);
    assertTrue(publisherError[0].getMessage().contains("ended before declared Content-Length"));
    assertEquals("OpenCLI Hub request failed", earlyEof.getMessage());
    assertNull(earlyEof.getCause());
    assertTrue(shortInput.closed);
  }

  @Test
  void fixedLengthPublisherCancellationClosesInputWithoutTerminalSignal() {
    TrackingInputStream input = new TrackingInputStream(new byte[] {1, 2, 3});
    RecordingSubscriber subscriber = new RecordingSubscriber(1L, true);
    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
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
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
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
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
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

    OpenCliSettings disabledSettings =
        new OpenCliSettings(
            false,
            "http://hub.test:8080",
            5_000,
            120_000,
            130_000,
            1024,
            512 * 1024,
            4096,
            65_535,
            OpenCliTestSettings.defaults().gptImage2(),
            OpenCliTestSettings.defaults().seedance());
    OpenCliHubClient disabled =
        new OpenCliHubClient(
            disabledSettings,
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request -> {
                  throw new AssertionError("disabled client must not send");
                }));
    assertThrows(
        OpenCliHubException.class, () -> disabled.execute(List.of("agent", "command"), 1L));

    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
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

  @Test
  void invalidResourceLengthClosesOwnedBodyWithoutEchoingHeader() {
    // 合成响应覆盖 JDK 传输层通常先拦截的 firstValueAsLong 异常路径及 header 清理。
    for (String length : List.of("credential-secret", "99999999999999999999999", "0", "-1")) {
      TrackingInputStream input = new TrackingInputStream(new byte[] {1});
      HttpHeaders headers =
          HttpHeaders.of(
              Map.of("Content-Length", List.of(length), "Content-Type", List.of("image/png")),
              (name, value) -> true);
      OpenCliHubClient client = clientReturning(new StubHttpResponse(200, headers, input));
      var error =
          assertThrows(
              OpenCliHubException.class,
              () ->
                  client.openResource(
                      new ExecutionResource(
                          "a.png", "image/png", 0, null, "/api/resources/a.png")));
      assertTrue(input.closed);
      assertFalse(messageChain(error).contains(length));
    }
  }

  /** 已取得资源响应后元数据解释本身失败：原始 Error 必须原样上抛，且已取得的正文流必须被关闭。 */
  @Test
  void resourceMetadataErrorKeepsIdentityAndClosesTheOwnedBody() {
    TrackingInputStream input = new TrackingInputStream(new byte[] {1});
    Error metadataError = new AssertionError("synthetic metadata interpretation Error");
    OpenCliHubClient client = clientReturning(failingHeadersResponse(200, input, metadataError));

    Error thrown =
        assertThrows(
            Error.class,
            () ->
                client.openResource(
                    new ExecutionResource("a.png", "image/png", 1L, null, "/api/resources/a.png")));

    assertSame(metadataError, thrown, "the original Error must be rethrown, never wrapped");
    assertTrue(input.closed, "the owned response body must be closed on the failure path");
  }

  /** 元数据 getter 在解释阶段失败的响应：headers() 抛出合成 Error，body() 仍是真实取得的所有权流。 */
  @SuppressWarnings("unchecked")
  private static HttpResponse<InputStream> failingHeadersResponse(
      int status, InputStream body, Error headersError) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    when(response.headers()).thenThrow(headersError);
    return response;
  }

  @Test
  void pollingUsesExistingRequestOrLongPollBudget() {
    // deadline 直接取 request.timeout，因此同时固定普通轮询和长轮询预算选择契约。
    for (int waitSeconds : List.of(0, 5)) {
      var client =
          new OpenCliHubClient(
              hub(),
              MAPPER,
              validCredentialAccess(DEFAULT_KEY),
              new StubHttpClient(
                  request -> {
                    assertEquals(
                        Duration.ofMillis(waitSeconds == 0 ? 120_000 : 130_000),
                        request.timeout().orElseThrow());
                    return jsonResponse(200, envelope(executionData("\"resources\":[]")));
                  }));
      assertEquals(ExecutionStatus.SUCCEEDED, client.getExecution("exec-1", waitSeconds).status());
    }
  }

  @Test
  void singleKeyAllEndpointsIncludeBearerHeader() throws Exception {
    String apiKey = "sk-opencli-live-token";
    List<HttpRequest> capturedRequests = new ArrayList<>();
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              capturedRequests.add(request);
              String path = request.uri().getPath();
              if (path.endsWith("/resources/uploads")) {
                return jsonResponse(
                    201,
                    "{\"status\":201,\"code\":\"CREATED\",\"data\":{\"items\":[{"
                        + "\"resourcePath\":\"/resources/test/out.bin\"}]}}");
              }
              if (path.startsWith("/api/resources/")) {
                HttpHeaders headers =
                    HttpHeaders.of(
                        Map.of(
                            "Content-Length", List.of("4"),
                            "Content-Type", List.of("image/png")),
                        (k, v) -> true);
                return new StubHttpResponse(
                    200, headers, new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
              }
              return jsonResponse(
                  200,
                  envelope(
                      "{\"id\":\"exec-1\",\"status\":\"PENDING\",\"stdout\":\"\","
                          + "\"stderr\":\"\",\"stdoutTruncated\":false,"
                          + "\"stderrTruncated\":false,\"resources\":[]}"));
            });

    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(apiKey), stubHttp);

    client.execute(List.of("agent", "start"), 1000L);
    // 取消先查询 PENDING 再 POST，连同上传与下载覆盖全部五类端点。
    client.cancelPendingBestEffort("exec-1");
    client.upload(
        "test.bin", "application/octet-stream", 4L, new ByteArrayInputStream(new byte[4]));
    try (HubResourceStream stream =
        client.openResource(
            new ExecutionResource("test.png", "image/png", 4L, null, "/api/resources/test.png"))) {
      assertNotNull(stream);
    }

    assertEquals(5, capturedRequests.size());
    assertTrue(
        capturedRequests.stream()
            .anyMatch(
                request ->
                    request.method().equals("POST")
                        && request.uri().getPath().equals("/api/executions/exec-1/cancel")));
    for (HttpRequest request : capturedRequests) {
      assertEquals("Bearer " + apiKey, request.headers().firstValue("Authorization").orElse(null));
    }
  }

  @Test
  void apiKeyRotationFetchesNewKeyPerRequest() {
    AtomicInteger callCount = new AtomicInteger();
    PluginCredentialAccess rotatingAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            int count = callCount.incrementAndGet();
            return validSnapshot("key-" + count);
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    List<String> authHeaders = new ArrayList<>();
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              authHeaders.add(request.headers().firstValue("Authorization").orElse(null));
              return jsonResponse(200, envelope(executionData("\"resources\":[]")));
            });

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, rotatingAccess, stubHttp);

    client.getExecution("exec-1", 0);
    client.getExecution("exec-2", 0);

    assertEquals(List.of("Bearer key-1", "Bearer key-2"), authHeaders);
    assertEquals(2, callCount.get());
  }

  @Test
  void missingApiKeyThrowsSanitizedException() {
    PluginCredentialAccess noCredential =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.NOT_CONNECTED, "no static apiKey row");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              throw new AssertionError("must not send request when credential resolution fails");
            });

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, noCredential, stubHttp);

    OpenCliHubException ex =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub credential is not available", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void credentialUnavailableThrowsSanitizedException() {
    PluginCredentialAccess refreshing =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new PluginCredentialUnavailableException(
                PluginCredentialUnavailableReason.AUTH_REFRESHING, "reauth required");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            refreshing,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("must not call transport");
                }));

    OpenCliHubException ex =
        assertThrows(OpenCliHubException.class, () -> client.execute(List.of("opencli"), 1000L));
    assertEquals("OpenCLI Hub credential is not available", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void invalidPayloadThrowsAndDoesNotSend() {
    // 1. payload 为 null (snapshot 为 null)
    assertPayloadRejected(null);
    // 2. payload 为空对象 {}
    assertPayloadRejected("{}");
    // 3. payload apiKey 为空字符串
    assertPayloadRejected("{\"apiKey\":\"\"}");
    // 4. payload apiKey 为仅空白
    assertPayloadRejected("{\"apiKey\":\"   \"}");
    // 5. payload apiKey 不为 string (如数字)
    assertPayloadRejected("{\"apiKey\":12345}");
    // 6. payload 为非 JSON 对象（如 JSON array）
    assertPayloadRejected("[\"apiKey\"]");
    // 7. payload 包含未知字段 (严格模式)
    assertPayloadRejected("{\"apiKey\":\"abc\",\"unknownField\":123}");
    // 8. 只有私有身份而没有认证密钥
    assertPayloadRejected("{\"instanceId\":\"prod-1\"}");
    // 9. 私有身份不是文本
    assertPayloadRejected("{\"apiKey\":\"abc\",\"instanceId\":123}");
    // 10. 私有身份为空白
    assertPayloadRejected("{\"apiKey\":\"abc\",\"instanceId\":\"\"}");
    assertPayloadRejected("{\"apiKey\":\"abc\",\"instanceId\":\"   \"}");
    // 11. 私有身份超出 1..36 字符上限
    assertPayloadRejected("{\"apiKey\":\"abc\",\"instanceId\":\"" + "a".repeat(37) + "\"}");
  }

  private void assertPayloadRejected(String payloadJson) {
    if (payloadJson == null) {
      PluginCredentialAccess nullAccess =
          new PluginCredentialAccess() {
            @Override
            public PluginCredentialSnapshot resolve() {
              return null;
            }

            @Override
            public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
              return false;
            }
          };

      OpenCliHubClient client =
          new OpenCliHubClient(
              hub(),
              MAPPER,
              nullAccess,
              new StubHttpClient(
                  request -> {
                    throw new AssertionError(
                        "transport must not be called when payload is invalid");
                  }));

      OpenCliHubException ex =
          assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
      assertEquals("OpenCLI Hub credential is not available", ex.getMessage());
      assertNull(ex.getCause());
      return;
    }

    PluginCredentialSnapshot badSnapshot =
        new PluginCredentialSnapshot(
            "opencli",
            UUID.randomUUID(),
            "context",
            null,
            null,
            1L,
            new byte[] {1, 2, 3},
            payloadJson);

    PluginCredentialAccess badAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return badSnapshot;
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            badAccess,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("transport must not be called when payload is invalid");
                }));

    OpenCliHubException ex =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub credential payload is invalid", ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void hubReturns401RejectsUsedCredential() {
    assertHttpRejection(401);
  }

  @Test
  void hubReturns403RejectsUsedCredential() {
    assertHttpRejection(403);
  }

  private void assertHttpRejection(int statusCode) {
    AtomicBoolean rejected = new AtomicBoolean();
    PluginCredentialSnapshot snapshot = validSnapshot("revoked-token");
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return snapshot;
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot target, String error) {
            if (target == snapshot) {
              rejected.set(true);
            }
            return true;
          }
        };

    StubHttpClient stubHttp =
        new StubHttpClient(request -> response(statusCode, "text/plain", "unauthorized"));

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, access, stubHttp);

    OpenCliHubException ex =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub HTTP " + statusCode, ex.getMessage());
    assertTrue(rejected.get());
  }

  /** 私有身份与 apiKey 必须来自同一次 scoped snapshot：一次请求只 resolve 一次，两个字段都取自该次结果。 */
  @Test
  void executeUsesApiKeyAndInstanceIdFromTheSameSnapshot() {
    AtomicInteger resolveCount = new AtomicInteger();
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            int count = resolveCount.incrementAndGet();
            return snapshot("key-generation-" + count, "instance-generation-" + count);
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    List<String> authorizations = new ArrayList<>();
    List<String> bodies = new ArrayList<>();
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              authorizations.add(request.headers().firstValue("Authorization").orElse(null));
              bodies.add(requestBodyText(request));
              return jsonResponse(200, envelope(executionData("\"resources\":[]")));
            });

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, access, stubHttp);
    client.execute(List.of("chatgpt-agent", "ask", "prompt"), 1000L);
    client.execute(List.of("chatgpt-agent", "ask", "prompt"), 1000L);

    assertEquals(List.of("Bearer key-generation-1", "Bearer key-generation-2"), authorizations);
    assertEquals(2, bodies.size());
    assertTrue(bodies.get(0).contains("\"instanceId\":\"instance-generation-1\""), bodies.get(0));
    assertTrue(bodies.get(1).contains("\"instanceId\":\"instance-generation-2\""), bodies.get(1));
    assertEquals(2, resolveCount.get(), "每次请求只解析一次 scoped 凭据，Bearer 与私有身份同源");
  }

  /** 未配置私有身份时执行请求体不得出现 instanceId，由 Hub 自由路由。 */
  @Test
  void executeOmitsInstanceIdWhenTheSnapshotHasNoPrivateIdentity() {
    List<String> bodies = new ArrayList<>();
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              bodies.add(requestBodyText(request));
              return jsonResponse(200, envelope(executionData("\"resources\":[]")));
            });

    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), stubHttp);
    client.execute(List.of("chatgpt-agent", "ask", "prompt"), 1000L);

    assertEquals(1, bodies.size());
    assertFalse(bodies.get(0).contains("instanceId"), bodies.get(0));
    assertTrue(bodies.get(0).contains("\"timeoutMillis\":1000"), bodies.get(0));
  }

  /** 401 时凭据作废抛出的 Error 原样上抛，且已取得的响应正文必须被关闭。 */
  @Test
  void rejectUsedErrorPropagatesAndStillClosesTheResponseBody() {
    TrackingInputStream body = new TrackingInputStream(new byte[] {1, 2, 3});
    Error injected = new AssertionError("synthetic rejectUsed failure");
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return validSnapshot(DEFAULT_KEY);
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            throw injected;
          }
        };

    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(), MAPPER, access, new StubHttpClient(request -> bodyResponse(401, body)));

    Error error = assertThrows(Error.class, () -> client.getExecution("exec-1", 0));

    assertSame(injected, error, "凭据作废的 Error 不得被吞掉或降级");
    assertTrue(body.closed, "401 失败路径必须释放已取得的响应正文");
  }

  /** 原始主错误与致命关闭错误保留身份，普通关闭错误中的连接秘密不进入异常链或日志。 */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void cleanupPreservesFatalErrorsButSanitizesOrdinaryCloseFailures(CapturedOutput output) {
    String marker = "synthetic-private-transport-detail";
    for (Throwable closeFailure :
        List.of(
            new IOException(marker),
            new IllegalStateException(marker),
            new AssertionError("synthetic fatal close failure"))) {
      AtomicBoolean closed = new AtomicBoolean();
      InputStream body =
          new ByteArrayInputStream(new byte[] {1}) {
            @Override
            public void close() throws IOException {
              closed.set(true);
              if (closeFailure instanceof IOException ioFailure) {
                throw ioFailure;
              }
              if (closeFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
              }
              throw (Error) closeFailure;
            }
          };
      Error primary = new AssertionError("synthetic rejection failure");
      PluginCredentialAccess access =
          new PluginCredentialAccess() {
            @Override
            public PluginCredentialSnapshot resolve() {
              return validSnapshot(DEFAULT_KEY);
            }

            @Override
            public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
              throw primary;
            }
          };
      OpenCliHubClient client =
          new OpenCliHubClient(
              hub(), MAPPER, access, new StubHttpClient(request -> bodyResponse(401, body)));

      Error error = assertThrows(Error.class, () -> client.getExecution("exec-1", 0));

      assertSame(primary, error);
      assertTrue(closed.get());
      assertEquals(1, error.getSuppressed().length);
      if (closeFailure instanceof Error) {
        assertSame(closeFailure, error.getSuppressed()[0]);
      } else {
        assertTrue(error.getSuppressed()[0] instanceof IOException);
        assertNull(error.getSuppressed()[0].getCause());
      }
      assertFalse(chainText(error).contains(marker));
      assertFalse(output.getAll().contains(marker));
    }
  }

  /** 错误响应解释阶段抛出的 Error 原样上抛，且正文流必须被关闭。 */
  @Test
  void errorResponseInterpretationErrorPropagatesAndClosesTheBody() {
    TrackingInputStream body = new TrackingInputStream(new byte[] {1, 2, 3});
    Error injected = new StackOverflowError("synthetic drain failure");
    InputStream failing =
        new InputStream() {
          @Override
          public int read() {
            throw injected;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            throw injected;
          }

          @Override
          public void close() throws IOException {
            body.close();
          }
        };

    OpenCliHubClient client = clientReturning(bodyResponse(500, failing));

    Error error = assertThrows(Error.class, () -> client.getExecution("exec-1", 0));

    assertSame(injected, error, "响应解释阶段的 Error 不得被吞掉或降级");
    assertTrue(body.closed, "错误响应解释失败后必须释放响应正文");
  }

  /** 非 2xx 的常规失败路径同样释放正文流（真实 stream.closed 回归）。 */
  @Test
  void errorStatusClosesTheResponseBody() {
    TrackingInputStream body = new TrackingInputStream("denied".getBytes(StandardCharsets.UTF_8));
    OpenCliHubClient client = clientReturning(bodyResponse(409, body));

    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));

    assertEquals("OpenCLI Hub HTTP 409", error.getMessage());
    assertTrue(body.closed);
  }

  /** 持有秘密的取值对象不得经 toString 泄漏：日志与异常只能看到脱敏表示。 */
  @Test
  void scopedCredentialToStringNeverExposesSecrets() {
    OpenCliHubClient.ScopedCredential credential =
        new OpenCliHubClient.ScopedCredential("sk-live-secret", "private-instance");

    assertEquals("Bearer sk-live-secret", "Bearer " + credential.apiKey());
    assertEquals("private-instance", credential.instanceId());
    assertFalse(credential.toString().contains("sk-live-secret"));
    assertFalse(credential.toString().contains("private-instance"));
  }

  @Test
  void ownedHttpClientIsClosedOnClose() throws Exception {
    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY));

    Field field = OpenCliHubClient.class.getDeclaredField("httpClient");
    field.setAccessible(true);
    HttpClient ownedClient = (HttpClient) field.get(client);
    assertNotNull(ownedClient);
    assertEquals(HttpClient.Redirect.NEVER, ownedClient.followRedirects());
    assertFalse(ownedClient.isTerminated());

    client.close();
    assertTrue(ownedClient.isTerminated());

    // 再次 close 幂等且不抛出异常
    client.close();
    assertTrue(ownedClient.isTerminated());
  }

  @Test
  void injectedHttpClientIsNotClosedOnClose() {
    AtomicBoolean closeCalled = new AtomicBoolean();
    StubHttpClient stubClient =
        new StubHttpClient(
            request -> jsonResponse(200, envelope(executionData("\"resources\":[]")))) {
          @Override
          public void close() {
            closeCalled.set(true);
          }
        };

    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), stubClient);

    client.close();

    // injected 客户端不可被由 OpenCliHubClient 关闭
    assertFalse(closeCalled.get());
  }

  @Test
  void perRequestCredentialResolutionIsNotDoneAtConstruction() {
    AtomicBoolean resolveCalled = new AtomicBoolean();
    PluginCredentialAccess access =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            resolveCalled.set(true);
            return validSnapshot(DEFAULT_KEY);
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    // 构造阶段不得调用 credentials.resolve()
    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            access,
            new StubHttpClient(
                request -> jsonResponse(200, envelope(executionData("\"resources\":[]")))));
    assertFalse(resolveCalled.get());

    // 第一次发送请求时才触发 resolve()
    client.getExecution("exec-1", 0);
    assertTrue(resolveCalled.get());
  }

  @Test
  void concurrent401WithStaleSnapshotDoesNotAffectNewGeneration() {
    UUID gen1 = UUID.randomUUID();
    UUID gen2 = UUID.randomUUID();
    PluginCredentialSnapshot snapshot1 =
        new PluginCredentialSnapshot(
            "opencli", gen1, "ctx", null, null, 1L, new byte[] {1}, "{\"apiKey\":\"key-1\"}");
    PluginCredentialSnapshot snapshot2 =
        new PluginCredentialSnapshot(
            "opencli", gen2, "ctx", null, null, 2L, new byte[] {2}, "{\"apiKey\":\"key-2\"}");

    AtomicReference<PluginCredentialSnapshot> authoritativeStore = new AtomicReference<>(snapshot1);
    AtomicInteger rejectAttemptCount = new AtomicInteger();
    AtomicInteger rejectedCount = new AtomicInteger();

    PluginCredentialAccess casAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return authoritativeStore.get();
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot target, String error) {
            rejectAttemptCount.incrementAndGet();
            PluginCredentialSnapshot current = authoritativeStore.get();
            // 忠实 CAS：仅当权威 store 当前的代际和版本与传入的 snapshot 完全相同时才作废
            if (current != null
                && current.authGeneration().equals(target.authGeneration())
                && current.version() == target.version()) {
              rejectedCount.incrementAndGet();
              authoritativeStore.set(null);
              return true;
            }
            return false;
          }
        };

    // 模拟时序：
    // 请求 1 在 resolve() 时拿到 snapshot1（gen1, version 1）
    // 在 HTTP 发送期间，后台完成了登录更新/代际刷新，store 已变为 snapshot2（gen2, version 2）
    // 请求 1 收到 401，客户端忠实传递 snapshot1 调用 rejectUsed
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              String auth = request.headers().firstValue("Authorization").orElse("");
              if (auth.equals("Bearer key-1")) {
                // 模拟并发更新代际为 snapshot2
                authoritativeStore.set(snapshot2);
                return response(401, "text/plain", "revoked");
              }
              return jsonResponse(200, envelope(executionData("\"resources\":[]")));
            });

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, casAccess, stubHttp);

    // 请求 1 触发 401 并调用 rejectUsed(snapshot1)
    assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals(1, rejectAttemptCount.get());
    assertEquals(0, rejectedCount.get()); // CAS 失败，未作废新代际
    assertEquals(snapshot2, authoritativeStore.get()); // store 依然是 snapshot2

    // 后续请求 2 使用最新的 snapshot2，正常成功
    Execution execution = client.getExecution("exec-2", 0);
    assertEquals(ExecutionStatus.SUCCEEDED, execution.status());
    assertEquals(snapshot2, authoritativeStore.get());
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void maliciousApiKeyAndTrailingAndDuplicateRejectionsNeverLeakSecret(CapturedOutput output) {
    String secretWithCrlf = "sk-live-secret\r\nInjected-Header: malicious";
    String secretWithUnicode = "sk-live-secret-©-utf8";
    String secretWithNul = "sk-live-secret-\u0000";
    String secretWithDel = "sk-live-secret-\u007f";
    String secretWithSpaces = "sk-live-secret with space";
    String secretTrailing = "sk-live-secret-trailing-token";
    String secretDup1 = "sk-live-secret-dup-first";
    String secretDup2 = "sk-live-secret-dup-second";

    // 控制字符以合法 JSON 编码，确保测试进入 Bearer 校验而非提前在 JSON 解析处失败。
    List<String> badPayloads =
        List.of(
            MAPPER.writeValueAsString(Map.of("apiKey", secretWithCrlf)),
            MAPPER.writeValueAsString(Map.of("apiKey", secretWithUnicode)),
            MAPPER.writeValueAsString(Map.of("apiKey", secretWithNul)),
            MAPPER.writeValueAsString(Map.of("apiKey", secretWithDel)),
            MAPPER.writeValueAsString(Map.of("apiKey", secretWithSpaces)),
            "{\"apiKey\":\"" + secretTrailing + "\"} extra_trailing_token",
            "{\"apiKey\":\"" + secretDup1 + "\",\"apiKey\":\"" + secretDup2 + "\"}");

    for (String badPayload : badPayloads) {
      PluginCredentialSnapshot badSnapshot =
          new PluginCredentialSnapshot(
              "opencli",
              UUID.randomUUID(),
              "context",
              null,
              null,
              1L,
              new byte[] {1, 2, 3},
              badPayload);
      PluginCredentialAccess access =
          new PluginCredentialAccess() {
            @Override
            public PluginCredentialSnapshot resolve() {
              return badSnapshot;
            }

            @Override
            public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
              return false;
            }
          };

      OpenCliHubClient client =
          new OpenCliHubClient(
              hub(),
              MAPPER,
              access,
              new StubHttpClient(
                  request -> {
                    throw new AssertionError("malicious request must not be sent over transport");
                  }));

      OpenCliHubException ex =
          assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
      assertTrue(
          ex.getMessage().equals("OpenCLI Hub credential payload is invalid")
              || ex.getMessage().equals("OpenCLI Hub credential is invalid"));
      assertNull(ex.getCause());
      assertEquals(0, ex.getSuppressed().length);

      // 断言异常链和捕获的诊断日志绝对不包含上述任何 secret 标记
      for (String secretMarker :
          List.of(
              "sk-live-secret", "Injected-Header", "trailing-token", "dup-first", "dup-second")) {
        assertFalse(chainText(ex).contains(secretMarker));
        assertFalse(output.getAll().contains(secretMarker));
      }
    }
  }

  @Test
  void redirectResponsesAreNeverFollowed() {
    StubHttpClient stubHttp =
        new StubHttpClient(
            request ->
                response(
                    302,
                    Map.of(
                        "Location", "http://attacker.test/steal",
                        "Content-Type", "text/plain"),
                    new byte[0]));
    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), stubHttp);
    OpenCliHubException error =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub HTTP 302", error.getMessage());
  }

  @Test
  void cancelPendingBestEffortHandlesTransportFailureGracefully() {
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              throw new IOException("network disconnected during cancel");
            });
    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), stubHttp);
    assertDoesNotThrow(() -> client.cancelPendingBestEffort("exec-1"));
  }

  @Test
  void rejectUsedSwallowsStoreExceptionOn401() {
    PluginCredentialSnapshot snapshot = validSnapshot("key-failing-store");
    PluginCredentialAccess failingAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            return snapshot;
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot target, String error) {
            throw new RuntimeException("database lease lost");
          }
        };

    StubHttpClient stubHttp =
        new StubHttpClient(request -> response(401, "text/plain", "unauthorized"));

    OpenCliHubClient client = new OpenCliHubClient(hub(), MAPPER, failingAccess, stubHttp);
    OpenCliHubException ex =
        assertThrows(OpenCliHubException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub HTTP 401", ex.getMessage());
  }

  @Test
  void credentialResolutionRuntimeExceptionIsSanitized() {
    PluginCredentialAccess throwingAccess =
        new PluginCredentialAccess() {
          @Override
          public PluginCredentialSnapshot resolve() {
            throw new IllegalStateException("unexpected credential store state");
          }

          @Override
          public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
            return false;
          }
        };

    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            throwingAccess,
            new StubHttpClient(
                request -> {
                  throw new AssertionError("transport must not be called");
                }));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> client.getExecution("exec-1", 0));
    assertEquals("OpenCLI Hub credential resolution failed", ex.getMessage());
    assertFalse(
        ex.getMessage().contains("unexpected credential store state"),
        "the raw credential store message must not leak");
    assertNull(ex.getCause(), "the raw failure must not be attached as a cause");
  }

  @Test
  void uploadValidatesArgumentsStrictly() {
    OpenCliHubClient client =
        new OpenCliHubClient(
            hub(),
            MAPPER,
            validCredentialAccess(DEFAULT_KEY),
            new StubHttpClient(
                request -> {
                  throw new AssertionError("transport must not be called on invalid upload args");
                }));

    // 1. size <= 0
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload("a.png", "image/png", 0L, new ByteArrayInputStream(new byte[0])));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload("a.png", "image/png", -1L, new ByteArrayInputStream(new byte[0])));

    // 2. filename is null
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload(null, "image/png", 1L, new ByteArrayInputStream(new byte[1])));

    // 3. mediaType is null or invalid
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload("a.png", null, 1L, new ByteArrayInputStream(new byte[1])));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload("a.png", "image/ png", 1L, new ByteArrayInputStream(new byte[1])));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.upload("a.png", "not-a-mime", 1L, new ByteArrayInputStream(new byte[1])));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "a.png", "image/png; charset=utf-8", 1L, new ByteArrayInputStream(new byte[1])));
  }

  @Test
  void uploadHandlesFilenameSanitizationAndResponseItemValidation() {
    List<HttpRequest> captured = new ArrayList<>();
    StubHttpClient stubHttp =
        new StubHttpClient(
            request -> {
              captured.add(request);
              return jsonResponse(
                  201,
                  "{\"status\":201,\"code\":\"CREATED\",\"data\":{\"items\":[{"
                      + "\"resourcePath\":\"/resources/out.bin\"}]}}");
            });

    OpenCliHubClient client =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), stubHttp);

    // 1. 全标点文件名 fallback 为 resource.bin，含特殊字符被替换为 _
    OpenCliHubClient.UploadedResource res =
        client.upload("...???", "image/png", 1L, new ByteArrayInputStream(new byte[1]));
    assertEquals("/resources/out.bin", res.resourcePath());

    // 2. 返回 items 长度不为 1 (比如 0 或 2)
    StubHttpClient emptyItemsClient =
        new StubHttpClient(
            request ->
                jsonResponse(201, "{\"status\":201,\"code\":\"CREATED\",\"data\":{\"items\":[]}}"));
    OpenCliHubClient failClient =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), emptyItemsClient);
    OpenCliHubException ex1 =
        assertThrows(
            OpenCliHubException.class,
            () ->
                failClient.upload("a.bin", "image/png", 1L, new ByteArrayInputStream(new byte[1])));
    assertTrue(ex1.getMessage().contains("upload data.items must contain exactly one item"));

    // 3. 返回 items 中的 resourcePath 非法 (如 /invalid/path 或包含 ..)
    StubHttpClient badPathClient =
        new StubHttpClient(
            request ->
                jsonResponse(
                    201,
                    "{\"status\":201,\"code\":\"CREATED\",\"data\":{\"items\":[{\"resourcePath\":\"/not-resources/a.png\"}]}}"));
    OpenCliHubClient badPathHubClient =
        new OpenCliHubClient(hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), badPathClient);
    assertThrows(
        OpenCliHubException.class,
        () ->
            badPathHubClient.upload(
                "a.bin", "image/png", 1L, new ByteArrayInputStream(new byte[1])));
  }

  @Test
  void rejectsNonObjectEnvelopeAndNonTextualFields() {
    // 1. envelope 返回非对象 (如 array)
    assertHubFailure(jsonResponse(200, "[1,2,3]"));

    // 2. id 为空白文本
    assertExecutionFailure("\"id\":\"   \",\"resources\":[]");

    // 3. stdoutTruncated 为非布尔
    assertExecutionFailure("\"stdoutTruncated\":123,\"resources\":[]");
  }

  // ==========================================
  // 辅助方法
  // ==========================================

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

  private static OpenCliSettings hub() {
    return hub(1024, 512 * 1024, 4096, 65_535);
  }

  private static OpenCliSettings hub(
      int streamBufferBytes,
      int maxJsonResponseBytes,
      int maxErrorResponseBytes,
      int maxOutputChars) {
    return new OpenCliSettings(
        true,
        "http://hub.test:8080",
        5_000,
        120_000,
        130_000,
        streamBufferBytes,
        maxJsonResponseBytes,
        maxErrorResponseBytes,
        maxOutputChars,
        OpenCliTestSettings.defaults().gptImage2(),
        OpenCliTestSettings.defaults().seedance());
  }

  private static PluginCredentialSnapshot validSnapshot(String apiKey) {
    return snapshot(apiKey, null);
  }

  /** 同一份载荷同时承载认证密钥与可选私有身份，模拟宿主写入的秘密 JSON 形状。 */
  private static PluginCredentialSnapshot snapshot(String apiKey, String instanceId) {
    return new PluginCredentialSnapshot(
        "opencli",
        UUID.randomUUID(),
        "context",
        null,
        null,
        1L,
        new byte[] {1, 2, 3},
        instanceId == null
            ? "{\"apiKey\":\"" + apiKey + "\"}"
            : "{\"apiKey\":\"" + apiKey + "\",\"instanceId\":\"" + instanceId + "\"}");
  }

  /** 聚合客户端真正写出的请求体文本，用于断言私有身份字段的有无。 */
  private static String requestBodyText(HttpRequest request) {
    ByteArrayOutputStream collected = new ByteArrayOutputStream();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                collected.writeBytes(chunk);
              }

              @Override
              public void onError(Throwable throwable) {
                throw new IllegalStateException(throwable);
              }

              @Override
              public void onComplete() {}
            });
    return collected.toString(StandardCharsets.UTF_8);
  }

  private static HttpResponse<InputStream> bodyResponse(int status, InputStream body) {
    return new StubHttpResponse(status, HttpHeaders.of(Map.of(), (name, value) -> true), body);
  }

  private static PluginCredentialAccess validCredentialAccess(String apiKey) {
    return new PluginCredentialAccess() {
      @Override
      public PluginCredentialSnapshot resolve() {
        return validSnapshot(apiKey);
      }

      @Override
      public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
        return false;
      }
    };
  }

  private static OpenCliHubClient clientReturning(HttpResponse<InputStream> response) {
    return new OpenCliHubClient(
        hub(), MAPPER, validCredentialAccess(DEFAULT_KEY), new StubHttpClient(request -> response));
  }

  private static HttpResponse<InputStream> jsonResponse(int status, String body) {
    return response(status, "application/json", body);
  }

  private static HttpResponse<InputStream> streamResponse(long length, InputStream body) {
    HttpHeaders headers =
        HttpHeaders.of(
            Map.of(
                "Content-Length", List.of(Long.toString(length)),
                "Content-Type", List.of("image/png")),
            (name, value) -> true);
    return new StubHttpResponse(200, headers, body);
  }

  private static ExecutionResource resource(long size) {
    return new ExecutionResource("a.png", "image/png", size, null, "/api/resources/a.png");
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

  private static String chainText(Throwable error) {
    StringBuilder text = new StringBuilder(messageChain(error));
    for (Throwable current = error; current != null; current = current.getCause()) {
      for (Throwable suppressed : current.getSuppressed()) {
        if (suppressed.getMessage() != null) {
          text.append(suppressed.getMessage()).append('\n');
        }
      }
    }
    return text.toString();
  }

  @FunctionalInterface
  private interface Responder {
    HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
  }

  private static class StubHttpClient extends HttpClient {

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
