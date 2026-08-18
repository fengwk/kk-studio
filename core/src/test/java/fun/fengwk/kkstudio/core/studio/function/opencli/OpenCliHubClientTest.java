package fun.fengwk.kkstudio.core.studio.function.opencli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionResource;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.ExecutionStatus;
import fun.fengwk.kkstudio.core.studio.function.opencli.OpenCliHubClient.HubResourceStream;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** JDK HttpServer 验证 Hub wire、媒体流式边界、URL 同源约束和 cancel 语义。 */
class OpenCliHubClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private OpenCliHubProperties properties;
  private SystemSettingsSnapshot snapshot;
  private OpenCliHubClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    properties = new OpenCliHubProperties();
    snapshot =
        new SystemSettingsSnapshot(
            settings(openCliHub("http://127.0.0.1:" + server.getAddress().getPort(), 121_000L)));
    client = new OpenCliHubClient(properties, snapshot, mapper);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void streamsOneFixedLengthMultipartFileAndParsesUploadEnvelope() {
    byte[] media = new byte[256 * 1024];
    for (int index = 0; index < media.length; index++) {
      media[index] = (byte) (index % 251);
    }
    TrackingInputStream input = new TrackingInputStream(media);
    AtomicReference<String> transferEncoding = new AtomicReference<>();
    AtomicReference<String> contentLength = new AtomicReference<>();
    AtomicReference<byte[]> requestBody = new AtomicReference<>();
    server.createContext(
        "/api/resources/uploads",
        exchange -> {
          transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
          contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
          requestBody.set(exchange.getRequestBody().readAllBytes());
          respond(
              exchange,
              201,
              """
              {"status":201,"code":"CREATED","success":true,"data":{"items":[{
                "resourcePath":"/resources/2026-08-10/upload-test/source.png"
              }]}}
              """);
        });

    OpenCliHubClient.UploadedResource uploaded =
        client.upload("../unsafe source.png", "image/png", media.length, input);

    assertEquals("/resources/2026-08-10/upload-test/source.png", uploaded.resourcePath());
    assertEquals(null, transferEncoding.get());
    assertEquals(requestBody.get().length, Long.parseLong(contentLength.get()));
    assertTrue(input.closed);
    assertTrue(
        input.maxRequested <= snapshot.get().integrations().openCliHub().streamBufferBytes());
    byte[] body = requestBody.get();
    String text = new String(body, StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("name=\"files\""));
    assertTrue(text.contains("filename=\"unsafe_source.png\""));
    assertTrue(text.contains("Content-Type: image/png"));
    assertTrue(indexOf(body, media) > 0);
  }

  @Test
  void preservesExtensionWithinLimitForLongAsciiFilename() {
    String expected = "a".repeat(124) + ".png";

    assertEquals(128, expected.length());
    assertUploadedFilename("a".repeat(200) + ".png", expected);
  }

  @Test
  void preservesExtensionWithinLimitForLongUnicodeFilename() {
    String expected = "_".repeat(124) + ".mp4";

    assertEquals(128, expected.length());
    assertUploadedFilename("参考😀图".repeat(80) + ".mp4", expected);
  }

  @Test
  void capsLongFilenameWithoutExtension() {
    String expected = "b".repeat(128);

    assertUploadedFilename("b".repeat(200), expected);
  }

  @Test
  void neutralizesMultipleAndBoundaryDotsWhilePreservingExtension() {
    assertUploadedFilename("..archive..png", "__archive_.png");
  }

  @Test
  void fallsBackForEmptyAndDotSegmentFilenames() {
    List<String> requestBodies = new ArrayList<>();
    server.createContext(
        "/api/resources/uploads",
        exchange -> {
          requestBodies.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
          respond(
              exchange,
              201,
              """
              {"status":201,"code":"CREATED","success":true,"data":{"items":[{
                "resourcePath":"/resources/2026-08-10/upload-test/resource.bin"
              }]}}
              """);
        });

    for (String filename : List.of("", ".", "..")) {
      OpenCliHubClient.UploadedResource uploaded =
          client.upload(filename, "image/png", 1L, new ByteArrayInputStream(new byte[] {1}));
      assertEquals("/resources/2026-08-10/upload-test/resource.bin", uploaded.resourcePath());
    }

    assertEquals(3, requestBodies.size());
    requestBodies.forEach(body -> assertTrue(body.contains("filename=\"resource.bin\"")));
  }

  @Test
  void sendsExecutePollsStrictStatusesAndCancelsOnlyPending() {
    AtomicReference<JsonNode> executeBody = new AtomicReference<>();
    AtomicInteger cancelCalls = new AtomicInteger();
    server.createContext(
        "/api/opencli/execute",
        exchange -> {
          executeBody.set(mapper.readTree(exchange.getRequestBody()));
          respond(exchange, 200, envelope(execution("exec-1", "PENDING", false, "", "[]")));
        });
    server.createContext(
        "/api/executions/exec-1",
        exchange -> {
          if (exchange.getRequestURI().getPath().endsWith("/cancel")) {
            cancelCalls.incrementAndGet();
            respond(exchange, 200, envelope(execution("exec-1", "CANCELLED", false, "", "[]")));
          } else if ("POST".equals(exchange.getRequestMethod())) {
            throw new AssertionError("unexpected POST");
          } else {
            respond(exchange, 200, envelope(execution("exec-1", "PENDING", false, "", "[]")));
          }
        });

    properties.setInstanceId("instance-1");
    client = new OpenCliHubClient(properties, snapshot, mapper);
    Execution submitted = client.execute(List.of("chatgpt-agent", "ask", "prompt"), 1000L);
    client.cancelPendingBestEffort(submitted.id());

    assertEquals(ExecutionStatus.PENDING, submitted.status());
    assertEquals("instance-1", executeBody.get().get("instanceId").textValue());
    assertEquals(1000L, executeBody.get().get("timeoutMillis").longValue());
    assertEquals("chatgpt-agent", executeBody.get().get("argv").get(0).textValue());
    assertEquals(1, cancelCalls.get());
  }

  @Test
  void doesNotCancelRunningAndFailsClosedOnUnknownOrTruncatedOutput() {
    AtomicInteger cancelCalls = new AtomicInteger();
    server.createContext(
        "/api/executions/running",
        exchange -> {
          if ("POST".equals(exchange.getRequestMethod())) {
            cancelCalls.incrementAndGet();
          }
          respond(exchange, 200, envelope(execution("running", "RUNNING", false, "", "[]")));
        });
    client.cancelPendingBestEffort("running");
    assertEquals(0, cancelCalls.get());

    server.createContext(
        "/api/executions/truncated",
        exchange ->
            respond(exchange, 200, envelope(execution("truncated", "SUCCEEDED", true, "", "[]"))));
    server.createContext(
        "/api/executions/unknown",
        exchange ->
            respond(exchange, 200, envelope(execution("unknown", "RETRYING", false, "", "[]"))));

    assertThrows(OpenCliHubException.class, () -> client.getExecution("truncated", 0));
    assertThrows(OpenCliHubException.class, () -> client.getExecution("unknown", 0));
    assertThrows(IllegalArgumentException.class, () -> client.getExecution("x", 121));
  }

  @Test
  void opensOnlySameOriginResourcePathWithPositiveLengthAndMustCloseStream() throws Exception {
    byte[] media = new byte[] {1, 2, 3, 4};
    AtomicBoolean responseClosed = new AtomicBoolean();
    server.createContext(
        "/api/resources/2026-08-10/execution-1/output.png",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "image/png");
          exchange.sendResponseHeaders(200, media.length);
          exchange.getResponseBody().write(media);
          exchange.close();
          responseClosed.set(true);
        });
    server.createContext(
        "/api/resources/no-length",
        exchange -> {
          exchange.getResponseHeaders().set("Content-Type", "image/png");
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().write(media);
          exchange.close();
        });
    ExecutionResource resource =
        new ExecutionResource(
            "output.png",
            "image/png",
            media.length,
            "/api/resources/2026-08-10/execution-1/output.png?inline=true",
            "/api/resources/2026-08-10/execution-1/output.png");

    try (HubResourceStream stream = client.openResource(resource)) {
      assertEquals(media.length, stream.size());
      assertEquals("image/png", stream.mediaType());
      assertArrayEquals(media, stream.content().readAllBytes());
    }
    assertTrue(responseClosed.get());

    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource(
                    "x", "image/png", 1, null, "https://example.com/api/resources/x")));
    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource("x", "image/png", 1, null, "/api/not-resources/x")));
    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource("x", "image/png", 0, null, "/api/resources/no-length")));
    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource("x", "image/png", 1, null, "/api/resources/../../outside")));
    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource(
                    "x", "image/png", 1, null, "/api/resources/%2e%2e/%2e%2e/outside")));
  }

  @Test
  void rejectsDecodedDotSegmentsInUploadedVirtualPath() {
    server.createContext(
        "/api/resources/uploads",
        exchange -> {
          exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
          respond(
              exchange,
              201,
              """
              {"status":201,"code":"CREATED","success":true,"data":{"items":[{
                "resourcePath":"/resources/safe/%2e%2e/outside.png"
              }]}}
              """);
        });

    assertThrows(
        OpenCliHubException.class,
        () ->
            client.upload("source.png", "image/png", 1L, new ByteArrayInputStream(new byte[] {1})));
  }

  @Test
  void rejectsMalformedEnvelopeRedirectInvalidBaseUrlAndUnboundedTimeouts() {
    server.createContext(
        "/api/executions/bad",
        exchange -> respond(exchange, 200, "{\"status\":200,\"code\":\"OK\",\"data\":null}"));
    server.createContext(
        "/api/resources/redirect",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/api/resources/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });

    assertThrows(OpenCliHubException.class, () -> client.getExecution("bad", 0));
    assertThrows(
        OpenCliHubException.class,
        () ->
            client.openResource(
                new ExecutionResource("x", "image/png", 1, null, "/api/resources/redirect")));

    // Hub 部署信息（base 配置）由 SystemSettings.integrations.openCliHub 判定：
    // 带凭据/查询串的 base URL、超限超时都在聚合构造时 fail fast。
    assertThrows(
        IllegalArgumentException.class,
        () -> openCliHub("https://user@example.com/?secret=x", 130_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> openCliHub("http://127.0.0.1:1", 180_000L, 120_000L, 130_000L));
    assertThrows(
        IllegalArgumentException.class,
        () -> openCliHub("http://127.0.0.1:1", 120_000L, 31L * 60 * 1000, 130_000L));

    new OpenCliHubClient(
        properties,
        new SystemSettingsSnapshot(settings(openCliHub("http://[::1]:8080", 130_000L))),
        mapper);
  }

  private static SystemSettings.OpenCliHub openCliHub(String baseUrl, long longPollTimeoutMillis) {
    return openCliHub(baseUrl, 5_000L, 120_000L, longPollTimeoutMillis);
  }

  private static SystemSettings.OpenCliHub openCliHub(
      String baseUrl,
      long connectTimeoutMillis,
      long requestTimeoutMillis,
      long longPollTimeoutMillis) {
    return new SystemSettings.OpenCliHub(
        true,
        baseUrl,
        connectTimeoutMillis,
        requestTimeoutMillis,
        longPollTimeoutMillis,
        1024,
        512 * 1024,
        4096,
        65_535);
  }

  private static SystemSettings settings(SystemSettings.OpenCliHub openCliHub) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        SystemSettings.AiRuntime.DEFAULT,
        SystemSettings.Environment.DEFAULT,
        new SystemSettings.Integrations(
            SystemSettings.Comfyui.DEFAULT,
            openCliHub,
            SystemSettings.Seedance.DEFAULT,
            SystemSettings.GptImage2.DEFAULT,
            SystemSettings.MiniMaxH3.DEFAULT),
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }

  private void assertUploadedFilename(String filename, String expected) {
    AtomicReference<String> requestBody = new AtomicReference<>();
    String resourcePath = "/resources/2026-08-10/upload-test/" + expected;
    server.createContext(
        "/api/resources/uploads",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
          respond(
              exchange,
              201,
              """
              {"status":201,"code":"CREATED","success":true,"data":{"items":[{
                "resourcePath":"%s"
              }]}}
              """
                  .formatted(resourcePath));
        });

    OpenCliHubClient.UploadedResource uploaded =
        client.upload(filename, "image/png", 1L, new ByteArrayInputStream(new byte[] {1}));

    assertEquals(resourcePath, uploaded.resourcePath());
    assertTrue(requestBody.get().contains("filename=\"" + expected + "\""));
  }

  private String execution(
      String id, String status, boolean truncated, String stdout, String resources) {
    return """
        {"id":"%s","status":"%s","stdout":%s,"stdoutTruncated":%s,
         "stderr":"","stderrTruncated":false,"resources":%s}
        """
        .formatted(id, status, quote(stdout), truncated, resources);
  }

  private String quote(String value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String envelope(String data) {
    return "{\"status\":200,\"code\":\"OK\",\"success\":true,\"data\":" + data + "}";
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int start = 0; start <= haystack.length - needle.length; start++) {
      for (int index = 0; index < needle.length; index++) {
        if (haystack[start + index] != needle[index]) {
          continue outer;
        }
      }
      return start;
    }
    return -1;
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private int maxRequested;
    private boolean closed;

    private TrackingInputStream(byte[] buffer) {
      super(buffer);
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) {
      maxRequested = Math.max(maxRequested, length);
      return super.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
