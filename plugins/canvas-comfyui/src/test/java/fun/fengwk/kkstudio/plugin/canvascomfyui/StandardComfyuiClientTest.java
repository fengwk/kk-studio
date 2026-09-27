package fun.fengwk.kkstudio.plugin.canvascomfyui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 标准客户端以本地 HTTP route 验证 multipart、严格 history、编码、流式 view 与 pending-only cancel。 */
class StandardComfyuiClientTest {

  private static final UUID CANVAS = new UUID(0L, 7L);

  private HttpServer server;
  private StandardComfyuiClient client;
  private final AtomicReference<String> promptResponse =
      new AtomicReference<>("{\"prompt_id\":\"p1\",\"node_errors\":{}}");
  private final AtomicReference<String> uploadResponse =
      new AtomicReference<>(
          "{\"name\":\"12.mp4\",\"subfolder\":\"kk-studio/00000000-0000-0000-0000-000000000007\",\"type\":\"input\"}");
  private final AtomicReference<String> viewQuery = new AtomicReference<>();
  private final AtomicReference<String> queueDeleteBody = new AtomicReference<>();
  private final AtomicInteger queueDeleteStatus = new AtomicInteger(200);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/upload/image", this::upload);
    server.createContext("/prompt", exchange -> json(exchange, 200, promptResponse.get()));
    server.createContext("/history/pending", exchange -> json(exchange, 200, "{}"));
    server.createContext(
        "/history/success",
        exchange ->
            json(
                exchange,
                200,
                "{\"success\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                    + "\"outputs\":{\"92\":{\"videos\":[{\"filename\":\"result.mp4\","
                    + "\"subfolder\":\"video/out\",\"type\":\"output\"}]}}}}"));
    server.createContext(
        "/history/error",
        exchange ->
            json(
                exchange,
                200,
                "{\"error\":{\"status\":{\"status_str\":\"error\",\"completed\":true,"
                    + "\"messages\":[\"boom\"]}}}"));
    server.createContext("/history/mismatch", exchange -> json(exchange, 200, "{\"another\":{}}"));
    server.createContext(
        "/history/bad-completed",
        exchange ->
            json(
                exchange,
                200,
                "{\"bad-completed\":{\"status\":{\"status_str\":\"running\","
                    + "\"completed\":\"false\"}}}"));
    server.createContext(
        "/history/bad-terminal",
        exchange ->
            json(
                exchange,
                200,
                "{\"bad-terminal\":{\"status\":{\"status_str\":\"stopped\","
                    + "\"completed\":true}}}"));
    server.createContext(
        "/history/two-videos",
        exchange ->
            json(
                exchange,
                200,
                "{\"two-videos\":{\"status\":{\"status_str\":\"success\",\"completed\":true},"
                    + "\"outputs\":{\"92\":{\"videos\":[{},{}]}}}}"));
    server.createContext("/history/invalid-json", exchange -> json(exchange, 200, "{"));
    server.createContext(
        "/history/http-error", exchange -> json(exchange, 500, "{\"error\":true}"));
    server.createContext("/view", this::view);
    server.createContext("/queue", this::queue);
    server.start();
    client =
        new StandardComfyuiClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "secret",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void uploadsFixedLengthMultipartAndSubmitsStrictPrompt() {
    H3UploadedFile uploaded =
        client.upload(
            "12.mp4", "video/mp4", 3L, new ByteArrayInputStream(new byte[] {1, 2, 3}), CANVAS);
    assertEquals(new H3UploadedFile("12.mp4", "kk-studio/" + CANVAS, "input"), uploaded);

    ObjectNode workflow = new ObjectMapper().createObjectNode().putObject("92");
    assertEquals("p1", client.submit(workflow, "client-1"));
    promptResponse.set("{\"prompt_id\":\"p1\",\"node_errors\":{\"136\":{\"error\":\"bad\"}}}");
    assertThrows(IllegalArgumentException.class, () -> client.submit(workflow, "client-1"));
    uploadResponse.set("{\"name\":\"12.mp4\",\"subfolder\":\"other\",\"type\":\"input\"}");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "12.mp4", "video/mp4", 3L, new ByteArrayInputStream(new byte[] {1, 2, 3}), CANVAS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "../escape.mp4",
                "video/mp4",
                3L,
                new ByteArrayInputStream(new byte[] {1, 2, 3}),
                CANVAS));
  }

  @Test
  void parsesPendingSuccessAndErrorWithExactlyOneSaveVideoOutput() {
    assertEquals(H3ComfyHistory.Status.PENDING, client.history("pending").status());
    H3ComfyHistory success = client.history("success");
    assertEquals(H3ComfyHistory.Status.SUCCESS, success.status());
    assertEquals("result.mp4", success.output().filename());
    assertEquals(H3ComfyHistory.Status.ERROR, client.history("error").status());
    assertTrue(client.history("error").error().contains("boom"));
    assertThrows(IllegalArgumentException.class, () -> client.history("mismatch"));
    assertThrows(IllegalArgumentException.class, () -> client.history("bad-completed"));
    assertThrows(IllegalArgumentException.class, () -> client.history("bad-terminal"));
    assertThrows(IllegalArgumentException.class, () -> client.history("two-videos"));
    assertThrows(IllegalArgumentException.class, () -> client.history("invalid-json"));
    assertThrows(IllegalStateException.class, () -> client.history("http-error"));
    assertThrows(IllegalArgumentException.class, () -> client.history("../bad"));
  }

  @Test
  void downloadsStreamingWithEncodedQueryAndDeletesPendingOnly() throws IOException {
    try (H3ComfyDownload download =
        client.download(new H3OutputDescriptor("result file.mp4", "video/out", "output"))) {
      assertEquals(4L, download.length());
      assertEquals("video/mp4", download.mediaType());
      assertArrayEquals(new byte[] {4, 5, 6, 7}, download.content().readAllBytes());
    }
    assertTrue(viewQuery.get().contains("filename=result%20file.mp4"));
    assertTrue(viewQuery.get().contains("subfolder=video%2Fout"));

    assertTrue(client.cancelPending("pending-id"));
    assertTrue(queueDeleteBody.get().contains("\"pending-id\""));
    assertFalse(client.cancelPending("running-id"));
    assertFalse(client.cancelPending("missing-id"));
    queueDeleteStatus.set(500);
    assertThrows(IllegalStateException.class, () -> client.cancelPending("pending-id"));
  }

  @Test
  void rejectsUnsafeInputsBaseUrlsAndMissingViewLength() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1/path",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "ftp://127.0.0.1",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://user@127.0.0.1",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1:8188?bad=true",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1:8188#fragment",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1:8188",
                "bad token\n",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1:8188",
                null,
                Duration.ZERO,
                Duration.ofSeconds(1),
                new ObjectMapper()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StandardComfyuiClient(
                "http://127.0.0.1:8188",
                null,
                Duration.ofSeconds(1),
                Duration.ofSeconds(-1),
                new ObjectMapper()));
  }

  private void upload(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (body.contains("filename=\"../escape.mp4\"")) {
      json(exchange, 400, "{\"error\":\"escape\"}");
      return;
    }
    json(exchange, 200, uploadResponse.get());
  }

  private void view(HttpExchange exchange) throws IOException {
    viewQuery.set(exchange.getRequestURI().getRawQuery());
    byte[] bytes = new byte[] {4, 5, 6, 7};
    exchange.getResponseHeaders().set("Content-Type", "video/mp4");
    exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void queue(HttpExchange exchange) throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) {
      json(
          exchange,
          200,
          "{\"queue_running\":[[\"0\",\"running-id\"]],\"queue_pending\":[[\"1\",\"pending-id\"]]}");
      return;
    }
    queueDeleteBody.set(
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    json(exchange, queueDeleteStatus.get(), "{}");
  }

  private static void json(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
