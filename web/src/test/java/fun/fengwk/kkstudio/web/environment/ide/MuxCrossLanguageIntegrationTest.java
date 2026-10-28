package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.ide.mux.MuxClose;
import fun.fengwk.kkstudio.ide.mux.MuxConnection;
import fun.fengwk.kkstudio.ide.mux.MuxDataKind;
import fun.fengwk.kkstudio.ide.mux.MuxHeader;
import fun.fengwk.kkstudio.ide.mux.MuxHeaders;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.ide.mux.MuxOpen;
import fun.fengwk.kkstudio.ide.mux.MuxReceivedData;
import fun.fengwk.kkstudio.ide.mux.MuxReset;
import fun.fengwk.kkstudio.ide.mux.MuxStream;
import fun.fengwk.kkstudio.ide.mux.MuxStreamListener;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java {@link MuxConnection} 与 Node 24 Daemon 传输的跨进程真实互通。
 *
 * <p>Java 侧在真实 Tomcat WebSocket 上运行生产物理传输与 mux 引擎；Node 子进程运行已构建的 {@code
 * daemon/dist/transport/mux-upstream.js} 和 {@code mux-connection.js}，把每个 OPEN 转到自己创建的私有 loopback
 * HTTP/WebSocket fixture。测试因此同时经过两端生产 codec、credit、半关闭和 WS 分段逻辑，而不是各端独立回环。
 *
 * <p>前置：{@code daemon} 已执行 {@code npm ci} 与 {@code npm run build}，PATH 上有 Node 24（可用 {@code
 * NODE_BIN} / {@code -Dnode.bin} 覆盖）。
 *
 * <p>测试类按 JUnit 默认串行执行；{@code MuxInteropServer.start()} 仅在 Tomcat 初始化期间短暂设置全局 {@code
 * catalina.home/base}，随后恢复原值，不与其他 Tomcat fixture 并行。
 */
class MuxCrossLanguageIntegrationTest {

  private static final UUID VIEW_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  private static final long BINDING_EPOCH = 7L;

  private static final int TWO_MEBIBYTES = 2 * 1024 * 1024;

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final List<MuxHeader> OPEN_HEADERS =
      List.of(new MuxHeader("accept-language", "zh-CN"), new MuxHeader("x-kk-interop", "mux-v2"));

  private MuxInteropServer server;
  private MuxInteropNodeProcess node;
  private MuxConnection connection;

  @BeforeEach
  void setUp() throws Exception {
    server = MuxInteropServer.start();
    node =
        MuxInteropNodeProcess.start(
            repoRoot(),
            server.studioUrl(),
            server.streamUrl(),
            server.ticket(),
            VIEW_ID,
            BINDING_EPOCH);
    try {
      connection = server.awaitConnection(Duration.ofSeconds(20));
    } catch (AssertionError error) {
      throw new AssertionError(error.getMessage() + node.diagnostics(), error);
    }
  }

  @AfterEach
  void tearDown() {
    MuxInteropNodeProcess currentNode = node;
    node = null;
    if (currentNode != null) {
      currentNode.close();
    }
    MuxInteropServer currentServer = server;
    server = null;
    if (currentServer != null) {
      currentServer.close();
    }
  }

  @Test
  void carriesConcurrentTwoMebibyteHttpBodiesAndHashes() throws Exception {
    byte[] firstBody = deterministicBytes(TWO_MEBIBYTES, 17);
    byte[] secondBody = deterministicBytes(TWO_MEBIBYTES, 29);
    OpenedStream first = openHttp("/http/hash/a");
    OpenedStream second = openHttp("/http/hash/b");

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Void> firstSend =
          executor.submit(
              () -> {
                sendHttpBody(first.stream(), firstBody);
                return null;
              });
      Future<Void> secondSend =
          executor.submit(
              () -> {
                sendHttpBody(second.stream(), secondBody);
                return null;
              });
      firstSend.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      secondSend.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      executor.shutdownNow();
    }

    completeHttp(first);
    completeHttp(second);
    String firstHash = sha256Hex(firstBody);
    String secondHash = sha256Hex(secondBody);
    assertEquals(firstHash + ":" + TWO_MEBIBYTES, first.recorder().httpBodyText());
    assertEquals(secondHash + ":" + TWO_MEBIBYTES, second.recorder().httpBodyText());

    JsonNode report =
        node.awaitSnapshot(
            root ->
                root.path("physical").path("activeStreams").asInt() == 0
                    && root.path("http").path("requests").size() == 2,
            TIMEOUT);
    assertHttpRequest(report, "/http/hash/a", firstBody);
    assertHttpRequest(report, "/http/hash/b", secondBody);
    assertEquals(1, report.path("physical").path("bindCalls").asInt());
    assertEquals(0, report.path("physical").path("dataCloseCount").asInt());
  }

  @Test
  void requestEndHalfCloseDoesNotTruncateResponse() throws Exception {
    byte[] firstChunk = deterministicBytes(64 * 1024, 41);
    byte[] secondChunk = deterministicBytes(32 * 1024, 43);
    byte[] requestBody = concat(firstChunk, secondChunk);
    OpenedStream opened = openHttp("/http/half-close");

    opened.stream().sendHttpData(firstChunk).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(opened.recorder().awaitHttpChunks(1, TIMEOUT), "first response chunk");
    String firstResponse =
        new String(opened.recorder().httpChunks().getFirst(), StandardCharsets.UTF_8);
    assertTrue(firstResponse.startsWith("before-end:"));
    assertFalse(opened.recorder().isEnded(), "response must still be open before request END");

    opened.stream().sendHttpData(secondChunk).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    opened.stream().sendEnd().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(opened.recorder().awaitEnd(TIMEOUT), "response END after request half-close");
    String expected = "before-end:after-end:" + requestBody.length + ":" + sha256Hex(requestBody);
    assertEquals(expected, opened.recorder().httpBodyText());
    completeHttp(opened);

    JsonNode report =
        node.awaitSnapshot(
            root ->
                root.path("physical").path("activeStreams").asInt() == 0
                    && root.path("http").path("requests").size() == 1,
            TIMEOUT);
    JsonNode request = findHttpRequest(report, "/http/half-close");
    assertNotNull(request, "fixture request record");
    assertEquals(requestBody.length, request.path("bytes").asInt());
    assertEquals(sha256Hex(requestBody), request.path("sha256").asText());
    assertTrue(request.path("ended").asBoolean());
    assertTrue(
        request.path("responseFirstChunkBeforeEnd").asBoolean(),
        "fixture must observe response progress before request END");
  }

  @Test
  void preservesBidirectionalWebSocketTextBinaryAndFin() throws Exception {
    OpenedStream opened = openWebSocket("/ws/echo");
    MuxStream stream = opened.stream();
    stream
        .sendWsData(true, false, "he".getBytes(StandardCharsets.UTF_8))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    stream
        .sendWsData(true, true, "llo".getBytes(StandardCharsets.UTF_8))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    stream
        .sendWsData(false, false, new byte[] {1, 2, 3})
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    stream
        .sendWsData(false, false, new byte[] {4, 5})
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    stream.sendWsData(false, true, new byte[] {6}).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    assertTrue(opened.recorder().awaitWsFrames(5, TIMEOUT), "five echoed WS fragments");
    List<MuxStreamRecorder.WsFrame> frames = opened.recorder().wsFrames();
    assertWsFrame(frames.get(0), true, false, "he".getBytes(StandardCharsets.UTF_8));
    assertWsFrame(frames.get(1), true, true, "llo".getBytes(StandardCharsets.UTF_8));
    assertWsFrame(frames.get(2), false, false, new byte[] {1, 2, 3});
    assertWsFrame(frames.get(3), false, false, new byte[] {4, 5});
    assertWsFrame(frames.get(4), false, true, new byte[] {6});

    JsonNode report =
        node.awaitSnapshot(
            root ->
                root.path("ws").path("connections").size() == 1
                    && root.path("ws").path("connections").get(0).path("frames").size() == 5,
            TIMEOUT);
    JsonNode nodeFrames = report.path("ws").path("connections").get(0).path("frames");
    assertNodeWsFrame(nodeFrames.get(0), 1, false, 2);
    assertNodeWsFrame(nodeFrames.get(1), 0, true, 3);
    assertNodeWsFrame(nodeFrames.get(2), 2, false, 3);
    assertNodeWsFrame(nodeFrames.get(3), 0, false, 2);
    assertNodeWsFrame(nodeFrames.get(4), 0, true, 1);

    stream.sendClose(1000, "interop done").get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(opened.recorder().awaitClose(TIMEOUT), "WS close handshake");
    stream.closed().toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Test
  void resetEndsOnlyTheTargetStream() throws Exception {
    OpenedStream reset = openWebSocket("/ws/reset-target");
    OpenedStream survivor = openWebSocket("/ws/reset-survivor");
    reset.stream()
        .sendWsData(true, true, "a1".getBytes(StandardCharsets.UTF_8))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    survivor.stream()
        .sendWsData(true, true, "b1".getBytes(StandardCharsets.UTF_8))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(reset.recorder().awaitWsFrames(1, TIMEOUT));
    assertTrue(survivor.recorder().awaitWsFrames(1, TIMEOUT));

    reset.stream()
        .reset("CANCELLED", "only this stream")
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    reset.stream().closed().toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(1, connection.activeStreamCount());

    JsonNode afterReset =
        node.awaitSnapshot(
            root ->
                root.path("physical").path("activeStreams").asInt() == 1
                    && root.path("ws").path("connections").size() == 2
                    && root.path("ws").path("connections").get(0).path("closed").asBoolean(),
            TIMEOUT);
    assertFalse(afterReset.path("ws").path("connections").get(1).path("closed").asBoolean());

    survivor.stream()
        .sendWsData(true, true, "b2".getBytes(StandardCharsets.UTF_8))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(survivor.recorder().awaitWsFrames(2, TIMEOUT));
    List<MuxStreamRecorder.WsFrame> survivorFrames = survivor.recorder().wsFrames();
    assertWsFrame(survivorFrames.get(0), true, true, "b1".getBytes(StandardCharsets.UTF_8));
    assertWsFrame(survivorFrames.get(1), true, true, "b2".getBytes(StandardCharsets.UTF_8));
    List<MuxStreamRecorder.WsFrame> resetFrames = reset.recorder().wsFrames();
    assertEquals(1, resetFrames.size(), "RESET must not deliver later traffic to the reset stream");

    survivor.stream()
        .reset("CANCELLED", "test cleanup")
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    node.awaitSnapshot(root -> root.path("physical").path("activeStreams").asInt() == 0, TIMEOUT);
    assertEquals(2, afterReset.path("ws").path("connections").size());
  }

  @Test
  void physicalDisconnectFailsEveryStreamWithoutReplay() throws Exception {
    OpenedStream first = openWebSocket("/ws/disconnect-a");
    OpenedStream second = openWebSocket("/ws/disconnect-b");
    OpenedStream http = openHttp("/http/disconnect");
    http.stream()
        .sendHttpData(deterministicBytes(64 * 1024, 61))
        .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

    JsonNode before =
        node.awaitSnapshot(
            root ->
                root.path("ws").path("connections").size() == 2
                    && root.path("http").path("requests").size() == 1
                    && root.path("physical").path("activeStreams").asInt() == 3,
            TIMEOUT);
    assertEquals(1, before.path("physical").path("bindCalls").asInt());

    server.disconnect();
    server.awaitDisconnected(TIMEOUT);
    assertStreamFailed(first.stream());
    assertStreamFailed(second.stream());
    assertStreamFailed(http.stream());

    JsonNode after =
        node.awaitSnapshot(
            root ->
                root.path("physical").path("dataCloseCount").asInt() >= 1
                    && root.path("physical").path("activeStreams").asInt() == 0
                    && root.path("ws").path("connections").size() == 2
                    && root.path("ws").path("connections").get(0).path("closed").asBoolean()
                    && root.path("ws").path("connections").get(1).path("closed").asBoolean(),
            TIMEOUT);
    assertEquals(1, after.path("physical").path("bindCalls").asInt());
    assertEquals(1, after.path("http").path("requests").size());
    assertEquals(1, server.acceptedConnections());
    assertEquals(1, server.closedConnections());

    // 物理连接断开后留出一个有界静默窗口，证明 Java/Node 都没有重连或重放逻辑流。
    Thread.sleep(500L);
    JsonNode quiet = node.snapshot(TIMEOUT);
    assertEquals(1, quiet.path("physical").path("bindCalls").asInt());
    assertEquals(1, quiet.path("http").path("requests").size());
    assertEquals(2, quiet.path("ws").path("connections").size());
    assertEquals(1, server.acceptedConnections());
    assertEquals(1, server.closedConnections());
  }

  private OpenedStream openHttp(String suffix) throws Exception {
    MuxStreamRecorder recorder = new MuxStreamRecorder();
    MuxStream stream =
        connection.open(
            MuxOpen.http(VIEW_ID, BINDING_EPOCH, "POST", viewPath(suffix), OPEN_HEADERS), recorder);
    assertTrue(recorder.awaitReady(TIMEOUT), "READY for " + suffix);
    return new OpenedStream(stream, recorder);
  }

  private OpenedStream openWebSocket(String suffix) throws Exception {
    MuxStreamRecorder recorder = new MuxStreamRecorder();
    MuxStream stream =
        connection.open(
            MuxOpen.webSocket(VIEW_ID, BINDING_EPOCH, viewPath(suffix), OPEN_HEADERS), recorder);
    assertTrue(recorder.awaitReady(TIMEOUT), "READY for " + suffix);
    assertTrue(recorder.awaitHeaders(TIMEOUT), "101 for " + suffix);
    assertEquals(101, recorder.headers().status());
    return new OpenedStream(stream, recorder);
  }

  private void sendHttpBody(MuxStream stream, byte[] body) throws Exception {
    for (int offset = 0; offset < body.length; offset += MuxLimits.MAX_PAYLOAD_BYTES) {
      int end = Math.min(offset + MuxLimits.MAX_PAYLOAD_BYTES, body.length);
      stream
          .sendHttpData(Arrays.copyOfRange(body, offset, end))
          .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
    stream.sendEnd().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void completeHttp(OpenedStream opened) throws Exception {
    assertTrue(opened.recorder().awaitEnd(TIMEOUT), "HTTP response END");
    opened.stream().complete().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    opened.stream().closed().toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void assertStreamFailed(MuxStream stream) {
    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () ->
                stream
                    .closed()
                    .toCompletableFuture()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    assertNotNull(error.getCause(), "stream failure must expose its physical cause");
  }

  private static void assertWsFrame(
      MuxStreamRecorder.WsFrame frame, boolean text, boolean fin, byte[] payload) {
    assertEquals(text, frame.text());
    assertEquals(fin, frame.fin());
    assertArrayEquals(payload, frame.payload());
  }

  private static void assertNodeWsFrame(JsonNode frame, int opcode, boolean fin, int bytes) {
    assertEquals(opcode, frame.path("opcode").asInt());
    assertEquals(fin, frame.path("fin").asBoolean());
    assertEquals(bytes, frame.path("bytes").asInt());
  }

  private static void assertHttpRequest(JsonNode report, String suffix, byte[] body) {
    JsonNode request = findHttpRequest(report, suffix);
    assertNotNull(request, "fixture request " + suffix);
    assertEquals(body.length, request.path("bytes").asInt());
    assertEquals(sha256Hex(body), request.path("sha256").asText());
    assertTrue(request.path("ended").asBoolean());
  }

  private static JsonNode findHttpRequest(JsonNode report, String suffix) {
    for (JsonNode request : report.path("http").path("requests")) {
      if (request.path("path").asText().endsWith(suffix)) {
        return request;
      }
    }
    return null;
  }

  private static String viewPath(String suffix) {
    return "/__kk/ide/view/" + VIEW_ID + "/" + BINDING_EPOCH + suffix;
  }

  private static byte[] deterministicBytes(int size, int seed) {
    byte[] bytes = new byte[size];
    for (int index = 0; index < size; index++) {
      bytes[index] = (byte) ((index * 31 + seed) & 0xff);
    }
    return bytes;
  }

  private static byte[] concat(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 not available", error);
    }
  }

  private static Path repoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pom.xml"))
          && Files.isDirectory(current.resolve("daemon"))
          && Files.isDirectory(current.resolve("web"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found from user.dir");
  }

  private record OpenedStream(MuxStream stream, MuxStreamRecorder recorder) {}

  /** 记录一条逻辑流的响应事实，并在真实消费后归还 credit。 */
  private static final class MuxStreamRecorder implements MuxStreamListener {

    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch headers = new CountDownLatch(1);
    private final CountDownLatch end = new CountDownLatch(1);
    private final CountDownLatch close = new CountDownLatch(1);
    private final CountDownLatch reset = new CountDownLatch(1);
    private final AtomicReference<MuxHeaders> responseHeaders = new AtomicReference<>();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final List<byte[]> httpChunks = new CopyOnWriteArrayList<>();
    private final List<WsFrame> wsFrames = new CopyOnWriteArrayList<>();

    @Override
    public void onReady(MuxStream stream) {
      ready.countDown();
    }

    @Override
    public void onHeaders(MuxStream stream, MuxHeaders received) {
      responseHeaders.set(received);
      headers.countDown();
    }

    @Override
    public void onData(MuxStream stream, MuxReceivedData data) {
      try {
        byte[] payload = data.payload().clone();
        if (data.kind() == MuxDataKind.HTTP) {
          httpChunks.add(payload);
        } else {
          wsFrames.add(new WsFrame(data.kind() == MuxDataKind.WS_TEXT, data.fin(), payload));
        }
      } finally {
        data.consumed();
      }
    }

    @Override
    public void onEnd(MuxStream stream) {
      ended.set(true);
      end.countDown();
    }

    @Override
    public void onReset(MuxStream stream, MuxReset ignored) {
      reset.countDown();
    }

    @Override
    public void onClose(MuxStream stream, MuxClose ignored) {
      close.countDown();
    }

    boolean awaitReady(Duration timeout) throws InterruptedException {
      return ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitHeaders(Duration timeout) throws InterruptedException {
      return headers.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitEnd(Duration timeout) throws InterruptedException {
      return end.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitClose(Duration timeout) throws InterruptedException {
      return close.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    boolean awaitHttpChunks(int count, Duration timeout) throws InterruptedException {
      return awaitSize(httpChunks, count, timeout);
    }

    boolean awaitWsFrames(int count, Duration timeout) throws InterruptedException {
      return awaitSize(wsFrames, count, timeout);
    }

    boolean isEnded() {
      return ended.get();
    }

    MuxHeaders headers() {
      return responseHeaders.get();
    }

    List<byte[]> httpChunks() {
      return List.copyOf(httpChunks);
    }

    String httpBodyText() {
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      for (byte[] chunk : httpChunks) {
        body.writeBytes(chunk);
      }
      return body.toString(StandardCharsets.UTF_8);
    }

    List<WsFrame> wsFrames() {
      return List.copyOf(wsFrames);
    }

    private static boolean awaitSize(List<?> values, int count, Duration timeout)
        throws InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (values.size() < count) {
        if (System.nanoTime() >= deadline) {
          return false;
        }
        Thread.sleep(5L);
      }
      return true;
    }

    record WsFrame(boolean text, boolean fin, byte[] payload) {}
  }
}
