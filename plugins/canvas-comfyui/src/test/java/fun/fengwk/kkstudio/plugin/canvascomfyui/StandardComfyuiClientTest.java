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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private final AtomicReference<byte[]> uploadBody = new AtomicReference<>();
  private final AtomicReference<String> uploadContentLength = new AtomicReference<>();
  private final AtomicInteger uploadStatus = new AtomicInteger(200);

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
  void uploadsExactMultipartBytesWhenContentIsDemandedInOneRequest() {
    byte[] payload = new byte[] {9, 8, 7, 6};
    AtomicBoolean closed = new AtomicBoolean();
    uploadResponse.set(
        "{\"name\":\"clip.mp4\",\"subfolder\":\"kk-studio/" + CANVAS + "\",\"type\":\"input\"}");
    H3UploadedFile uploaded =
        client.upload(
            "clip.mp4",
            "video/mp4",
            payload.length,
            tracking(new ByteArrayInputStream(payload), closed),
            CANVAS);
    assertEquals("clip.mp4", uploaded.name());
    byte[] body = uploadBody.get();
    assertEquals(Long.parseLong(uploadContentLength.get()), body.length);
    String text = new String(body, StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("name=\"subfolder\""));
    assertTrue(text.contains("kk-studio/" + CANVAS));
    assertTrue(text.contains("name=\"overwrite\""));
    assertTrue(text.contains("name=\"image\"; filename=\"clip.mp4\""));
    assertTrue(text.contains("Content-Type: video/mp4"));
    int payloadAt = indexOf(body, payload);
    assertTrue(payloadAt > 0);
    assertEquals(
        "\r\n--", text.substring(payloadAt + payload.length, payloadAt + payload.length + 4));
    assertTrue(text.trim().endsWith("--"));
    assertTrue(closed.get());
  }

  /** 碎片化输入流（每次最多 100 字节）必须被完整组装成单个 multipart 请求体，不能截断或重复内容。 */
  @Test
  void uploadsCompleteMultipartFromFragmentedContentStream() {
    byte[] payload = new byte[40 * 1024];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) (i & 0xff);
    }
    AtomicBoolean closed = new AtomicBoolean();
    client.upload(
        "wide.bin",
        "application/octet-stream",
        payload.length,
        tracking(new ChunkedInputStream(payload, 100), closed),
        CANVAS);
    byte[] body = uploadBody.get();
    assertEquals(Long.parseLong(uploadContentLength.get()), body.length);
    assertTrue(indexOf(body, payload) >= 0);
    String text = new String(body, StandardCharsets.ISO_8859_1);
    assertTrue(text.contains("filename=\"wide.bin\""));
    assertTrue(text.contains("Content-Type: application/octet-stream"));
    assertTrue(closed.get());
  }

  /** HttpClient 的 executor 拒绝提交上传请求时调用方流仍必须被关闭，且不得继续读取内容。 */
  @Test
  void closesCallerStreamWhenExecutorRejectsUploadRequest() {
    AtomicBoolean closed = new AtomicBoolean();
    InputStream content =
        tracking(
            new InputStream() {
              @Override
              public int read() {
                return 7;
              }
            },
            closed);
    HttpClient cancelling =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(
                command -> {
                  throw new SecurityException("cancelled before request");
                })
            .build();
    StandardComfyuiClient cancellingClient =
        new StandardComfyuiClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            null,
            Duration.ofSeconds(2),
            new ObjectMapper(),
            cancelling);
    assertThrows(
        SecurityException.class,
        () ->
            cancellingClient.upload(
                "cancel.bin", "application/octet-stream", 1024L, content, CANVAS));
    assertTrue(closed.get());
    assertThrows(IOException.class, () -> content.read(new byte[1]));
  }

  @Test
  void exactUploadContentChecksSingleBulkAndZeroReadsOnce() throws Exception {
    InputStream content =
        StandardComfyuiClient.contentSupplier(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2L)
            .get();
    assertEquals(1, content.read());
    byte[] bulk = new byte[4];
    assertEquals(1, content.read(bulk, 1, 3));
    assertEquals(2, bulk[1]);
    assertEquals(0, content.read(bulk, 0, 0));
    assertThrows(IOException.class, () -> content.read(bulk, 0, 1));

    InputStream empty =
        StandardComfyuiClient.contentSupplier(new ByteArrayInputStream(new byte[0]), 1L).get();
    assertEquals(0, empty.read(new byte[2], 0, 0));
    assertThrows(IOException.class, () -> empty.read());

    InputStream exact =
        StandardComfyuiClient.contentSupplier(new ByteArrayInputStream(new byte[] {7}), 1L).get();
    assertEquals(7, exact.read());
    assertEquals(-1, exact.read());

    var again = StandardComfyuiClient.contentSupplier(new ByteArrayInputStream(new byte[] {9}), 1L);
    again.get();
    IllegalStateException replay = assertThrows(IllegalStateException.class, again::get);
    assertTrue(replay.getMessage().contains("already open"));
  }

  @Test
  void rejectsUploadShorterOrLongerThanDeclaredLength() {
    AtomicBoolean shortClosed = new AtomicBoolean();
    UncheckedIOException tooShort =
        assertThrows(
            UncheckedIOException.class,
            () ->
                client.upload(
                    "short.bin",
                    "application/octet-stream",
                    8L,
                    tracking(new ByteArrayInputStream(new byte[] {1, 2}), shortClosed),
                    CANVAS));
    assertTrue(tooShort.getCause().getMessage().contains("ended after"));
    assertTrue(shortClosed.get());
    assertEquals(null, uploadBody.get());

    AtomicBoolean longClosed = new AtomicBoolean();
    UncheckedIOException tooLong =
        assertThrows(
            UncheckedIOException.class,
            () ->
                client.upload(
                    "long.bin",
                    "application/octet-stream",
                    2L,
                    tracking(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), longClosed),
                    CANVAS));
    assertTrue(tooLong.getCause().getMessage().contains("longer than declared"));
    assertTrue(longClosed.get());
    assertEquals(null, uploadBody.get());
  }

  @Test
  void rejectsHeaderBreakingFilenameAndMediaType() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "bad\"name.mp4",
                "video/mp4",
                1L,
                new ByteArrayInputStream(new byte[] {1}),
                CANVAS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "bad\r\nname.mp4",
                "video/mp4",
                1L,
                new ByteArrayInputStream(new byte[] {1}),
                CANVAS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.upload(
                "clip.mp4",
                "video/mp4\r\nX-Injected: 1",
                1L,
                new ByteArrayInputStream(new byte[] {1}),
                CANVAS));
  }

  @Test
  void closesUploadStreamWhenReadFailsOrContentIsShorterThanDeclared() {
    AtomicBoolean failedClosed = new AtomicBoolean();
    assertThrows(
        UncheckedIOException.class,
        () ->
            client.upload(
                "broken.bin",
                "application/octet-stream",
                8L,
                tracking(new FailingInputStream(), failedClosed),
                CANVAS));
    assertTrue(failedClosed.get());

    AtomicBoolean shortClosed = new AtomicBoolean();
    assertThrows(
        UncheckedIOException.class,
        () ->
            client.upload(
                "short.bin",
                "application/octet-stream",
                8L,
                tracking(new ByteArrayInputStream(new byte[] {1, 2}), shortClosed),
                CANVAS));
    assertTrue(shortClosed.get());
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
    byte[] body = exchange.getRequestBody().readAllBytes();
    uploadBody.set(body);
    uploadContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
    String text = new String(body, StandardCharsets.ISO_8859_1);
    if (text.contains("filename=\"../escape.mp4\"")) {
      json(exchange, 400, "{\"error\":\"escape\"}");
      return;
    }
    json(exchange, uploadStatus.get(), uploadResponse.get());
  }

  private static InputStream tracking(InputStream content, AtomicBoolean closed) {
    return new FilterInputStream(content) {
      @Override
      public void close() throws IOException {
        closed.set(true);
        super.close();
      }

      @Override
      public int read() throws IOException {
        if (closed.get()) {
          throw new IOException("closed");
        }
        return super.read();
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (closed.get()) {
          throw new IOException("closed");
        }
        return super.read(buffer, offset, length);
      }
    };
  }

  private static int indexOf(byte[] body, byte[] needle) {
    for (int i = 0; i <= body.length - needle.length; i++) {
      boolean matched = true;
      for (int j = 0; j < needle.length; j++) {
        if (body[i + j] != needle[j]) {
          matched = false;
          break;
        }
      }
      if (matched) {
        return i;
      }
    }
    return -1;
  }

  /** 每次最多读固定字节，让 BodyPublisher 分多次拉取内容（模拟碎片化输入流）。 */
  private static final class ChunkedInputStream extends InputStream {

    private final byte[] payload;
    private final int chunk;
    private int offset;

    private ChunkedInputStream(byte[] payload, int chunk) {
      this.payload = payload;
      this.chunk = chunk;
    }

    @Override
    public int read(byte[] buffer, int off, int len) {
      if (offset >= payload.length) {
        return -1;
      }
      int count = Math.min(chunk, Math.min(len, payload.length - offset));
      System.arraycopy(payload, offset, buffer, off, count);
      offset += count;
      return count;
    }

    @Override
    public int read() {
      byte[] one = new byte[1];
      int read = read(one, 0, 1);
      return read < 0 ? -1 : one[0] & 0xff;
    }
  }

  private static final class FailingInputStream extends InputStream {

    @Override
    public int read() throws IOException {
      throw new IOException("read failed");
    }
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
