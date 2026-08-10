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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** JDK HttpServer 验证 Hub wire、媒体流式边界、URL 同源约束和 cancel 语义。 */
class OpenCliHubClientTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private HttpServer server;
  private OpenCliHubProperties properties;
  private OpenCliHubClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    properties = new OpenCliHubProperties();
    properties.setEnabled(true);
    properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    properties.setLongPollTimeout(Duration.ofSeconds(121));
    properties.setStreamBufferBytes(1024);
    client = new OpenCliHubClient(properties, mapper);
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
    assertTrue(input.maxRequested <= properties.getStreamBufferBytes());
    byte[] body = requestBody.get();
    String text = new String(body, StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("name=\"files\""));
    assertTrue(text.contains("filename=\"unsafe_source.png\""));
    assertTrue(text.contains("Content-Type: image/png"));
    assertTrue(indexOf(body, media) > 0);
  }

  @Test
  void canonicalizesChineseAndSupplementaryFilenameToAsciiWhilePreservingExtension() {
    AtomicReference<String> requestBody = new AtomicReference<>();
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
                "resourcePath":"/resources/2026-08-10/upload-test/____.png"
              }]}}
              """);
        });

    OpenCliHubClient.UploadedResource uploaded =
        client.upload("参考😀图.png", "image/png", 1L, new ByteArrayInputStream(new byte[] {1}));

    assertEquals("/resources/2026-08-10/upload-test/____.png", uploaded.resourcePath());
    assertTrue(requestBody.get().contains("filename=\"____.png\""));
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
    client = new OpenCliHubClient(properties, mapper);
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

    OpenCliHubProperties invalid = new OpenCliHubProperties();
    invalid.setBaseUrl(URI.create("https://user@example.com/?secret=x"));
    assertThrows(IllegalArgumentException.class, () -> new OpenCliHubClient(invalid, mapper));

    OpenCliHubProperties excessiveConnectTimeout = new OpenCliHubProperties();
    excessiveConnectTimeout.setConnectTimeout(Duration.ofMinutes(3));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OpenCliHubClient(excessiveConnectTimeout, mapper));
    OpenCliHubProperties excessiveRequestTimeout = new OpenCliHubProperties();
    excessiveRequestTimeout.setRequestTimeout(Duration.ofMinutes(31));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OpenCliHubClient(excessiveRequestTimeout, mapper));

    OpenCliHubProperties ipv6 = new OpenCliHubProperties();
    ipv6.setBaseUrl(URI.create("http://[::1]:8080"));
    new OpenCliHubClient(ipv6, mapper);
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
