package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonIdeStreamCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeAction;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamMode;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.IdeRequest;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器 WS 数据面的**原生分段**契约：大消息按 ≤64 KiB 分段与 FIN 标记双向流转，不整包缓冲。
 *
 * <p>浏览器→Daemon：真实 Tomcat 把超过会话缓冲的浏览器消息按段交付（handler 声明 {@code supportsPartialMessages}），网关再按 credit
 * 切段发往 Daemon，文本段必须落在码点边界（每段自身是合法 UTF-8）。
 *
 * <p>Daemon→浏览器：Daemon 的文本分段可以在码点中间切分，网关用流式 UTF-8 解码器跨段解码后按原生 partial 帧发给浏览器。
 */
class IdeBrowserWebSocketStreamingIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");

  @DynamicPropertySource
  static void ideGatewayProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "kk-studio.harness.ide-gateway.stream-url-template",
        () -> "wss://gateway.test" + IdeGatewayProperties.STREAM_PATH);
    registry.add("kk-studio.harness.ide.origin-template", () -> "https://{environmentId}.ide.test");
  }

  @LocalServerPort private int port;

  @MockitoBean private EnvironmentDaemonServer endpoint;

  @Autowired private IdeBrowserSessionRegistry sessions;

  @Autowired private IdeGatewayProperties gatewayProperties;

  /** 超过会话缓冲的浏览器二进制消息必须被分段透传，且 echo 回来仍是同一条消息、同样的字节。 */
  @Test
  void segmentsLargeBrowserBinaryMessageAndEchoesIt() throws Exception {
    byte[] payload = new byte[200_000];
    new Random(7).nextBytes(payload);
    FakeDaemon daemon = new FakeDaemon();

    try (Browser browser = openBrowser(daemon)) {
      assertTrue(browser.socket.send(ByteString.of(payload)), "browser must send the payload");
      assertTrue(daemon.awaitBinaryBytes(payload.length), "daemon must consume every byte");
      assertTrue(daemon.binaryFins.size() > 1, "large message must arrive as multiple segments");
      assertTrue(
          daemon.binaryFins.get(daemon.binaryFins.size() - 1), "last segment must carry FIN");
      assertFalse(daemon.binaryFins.get(0), "only the last segment carries FIN");
      assertEquals(payload.length, daemon.totalBinaryBytes());
      byte[] echoed = browser.messages.poll(20, TimeUnit.SECONDS);
      assertArrayEquals(payload, echoed, "browser must receive the reassembled message");
    }
  }

  /** 浏览器文本消息分段后，每个发往 Daemon 的文本段都必须是独立合法的 UTF-8，且重组结果完全一致。 */
  @Test
  void segmentsLargeBrowserTextMessageIntoValidUtf8() throws Exception {
    StringBuilder builder = new StringBuilder();
    while (builder.length() < 120_000) {
      builder.append("世界");
    }
    String payload = builder.toString();
    byte[] expected = payload.getBytes(StandardCharsets.UTF_8);
    FakeDaemon daemon = new FakeDaemon();

    try (Browser browser = openBrowser(daemon)) {
      assertTrue(browser.socket.send(payload), "browser must send the payload");
      assertTrue(daemon.awaitTextBytes(expected.length), "daemon must consume every byte");
      assertTrue(daemon.textFins.size() > 1, "large message must arrive as multiple segments");
      for (byte[] segment : daemon.textSegments) {
        assertDoesNotThrow(() -> decodeUtf8(segment), "each text segment must be valid UTF-8");
      }
      assertArrayEquals(expected, daemon.reassembledText());
      String echoed = browser.textMessages.poll(20, TimeUnit.SECONDS);
      assertEquals(payload, echoed);
    }
  }

  /** Daemon 的文本分段可在码点中间切分：网关用流式解码器跨段解码，浏览器仍收到完整字符串。 */
  @Test
  void streamsLargeDaemonTextMessageAcrossCodepointBoundaries() throws Exception {
    StringBuilder builder = new StringBuilder();
    while (builder.length() < 100_000) {
      builder.append("世界");
    }
    String payload = builder.toString();
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    // 按 3 字节周期切分：多数边界会落在「世/界」的码点内部。
    List<byte[]> segments = new ArrayList<>();
    int offset = 0;
    while (offset < bytes.length) {
      int size = Math.min(65_536, bytes.length - offset);
      segments.add(Arrays.copyOfRange(bytes, offset, offset + size));
      offset += size;
    }
    assertTrue(segments.size() > 1, "test payload must span multiple segments");
    FakeDaemon daemon = new FakeDaemon();

    try (Browser browser = openBrowser(daemon)) {
      assertTrue(daemon.awaitReady(), "daemon reverse connection must be ready");
      for (int index = 0; index < segments.size(); index++) {
        daemon.push(
            codec().encodeWsSegment(true, index == segments.size() - 1, segments.get(index)));
      }
      assertEquals(payload, browser.textMessages.poll(20, TimeUnit.SECONDS));
    }
  }

  private static void decodeUtf8(byte[] bytes) throws CharacterCodingException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    decoder.decode(ByteBuffer.wrap(bytes));
  }

  private static DaemonIdeStreamCodec codec() {
    return new DaemonIdeStreamCodec();
  }

  private Browser openBrowser(FakeDaemon daemon) throws Exception {
    IdeSessionIdentity identity =
        new IdeSessionIdentity(ENVIRONMENT, "daemon-1", UUID.randomUUID());
    when(endpoint.ideSessionIdentity(ENVIRONMENT)).thenReturn(Optional.of(identity));
    when(endpoint.requestIde(eq(ENVIRONMENT), any()))
        .thenAnswer(
            invocation -> {
              IdeCommand command = invocation.getArgument(1);
              assertEquals(IdeAction.OPEN_STREAM, command.action());
              assertEquals(IdeStreamMode.WS, command.mode());
              daemon.dial(reverseUrl(command.ticket()));
              CompletableFuture<IdeReply> reply = new CompletableFuture<>();
              reply.complete(IdeReply.ok(null, null, null));
              return new IdeRequest(identity, UUID.randomUUID(), reply);
            });
    String cookie = sessions.open(identity, "/home/dev/project");
    OkHttpClient client =
        new OkHttpClient.Builder()
            .dns(hostname -> List.of(InetAddress.getByName("127.0.0.1")))
            .build();
    Browser browser = new Browser(client);
    browser.socket =
        client.newWebSocket(
            new Request.Builder()
                .url("ws://" + ENVIRONMENT + ".ide.test:" + port + "/ws")
                .header("Cookie", gatewayProperties.requireSessionCookieName() + "=" + cookie)
                .header("Origin", "http://" + ENVIRONMENT + ".ide.test:" + port)
                .build(),
            browser);
    assertTrue(browser.opened.await(15, TimeUnit.SECONDS), "browser websocket must be established");
    return browser;
  }

  private String reverseUrl(String ticket) {
    return "ws://localhost:"
        + port
        + IdeGatewayProperties.STREAM_PATH
        + "/"
        + ticket
        + "?environment="
        + ENVIRONMENT;
  }

  /** 浏览器侧句柄：把收到的（可能是分段的）消息按类型收集起来。 */
  private static final class Browser extends WebSocketListener implements AutoCloseable {

    private final OkHttpClient client;
    private final CountDownLatch opened = new CountDownLatch(1);
    private final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> textMessages = new LinkedBlockingQueue<>();
    private volatile WebSocket socket;

    private Browser(OkHttpClient client) {
      this.client = client;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      opened.countDown();
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      messages.add(bytes.toByteArray());
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      textMessages.add(text);
    }

    @Override
    public void close() {
      WebSocket current = socket;
      if (current != null) {
        current.close(1000, "done");
      }
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  /** 反向 WS Daemon peer：记录入站分段的 FIN 与字节，并可主动推送分段。 */
  private final class FakeDaemon extends WebSocketListener {

    private final DaemonIdeStreamCodec codec = new DaemonIdeStreamCodec();
    private final List<Boolean> binaryFins = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> binarySegments = Collections.synchronizedList(new ArrayList<>());
    private final List<Boolean> textFins = Collections.synchronizedList(new ArrayList<>());
    private final List<byte[]> textSegments = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch ready = new CountDownLatch(1);

    private volatile WebSocket socket;
    private volatile OkHttpClient client;

    void dial(String streamUrl) {
      client = new OkHttpClient();
      client.newWebSocket(new Request.Builder().url(streamUrl).build(), this);
    }

    boolean awaitReady() throws InterruptedException {
      return ready.await(15, TimeUnit.SECONDS);
    }

    void push(byte[] frame) {
      WebSocket current = socket;
      if (current != null) {
        current.send(ByteString.of(frame));
      }
    }

    long totalBinaryBytes() {
      synchronized (binarySegments) {
        long total = 0;
        for (byte[] segment : binarySegments) {
          total += segment.length;
        }
        return total;
      }
    }

    boolean awaitBinaryBytes(long expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < deadline) {
        if (totalBinaryBytes() >= expected) {
          return true;
        }
        Thread.sleep(10);
      }
      return totalBinaryBytes() >= expected;
    }

    boolean awaitTextBytes(long expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (System.nanoTime() < deadline) {
        if (reassembledText().length >= expected) {
          return true;
        }
        Thread.sleep(10);
      }
      return reassembledText().length >= expected;
    }

    byte[] reassembledText() {
      synchronized (textSegments) {
        int total = 0;
        for (byte[] segment : textSegments) {
          total += segment.length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] segment : textSegments) {
          System.arraycopy(segment, 0, all, offset, segment.length);
          offset += segment.length;
        }
        return all;
      }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      this.socket = webSocket;
      webSocket.send("{\"type\":\"WS_READY\"}");
      ready.countDown();
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      byte[] frame = bytes.toByteArray();
      if (frame.length < 2) {
        return;
      }
      boolean last = frame[1] != 0;
      byte[] payload = Arrays.copyOfRange(frame, 2, frame.length);
      if (frame[0] == 4) {
        textFins.add(last);
        textSegments.add(payload);
        // echo：原样把分段回给浏览器，并 ACK 已消费字节。
        webSocket.send(ByteString.of(frame));
        webSocket.send(ByteString.of(codec.encodeAck(payload.length)));
      } else if (frame[0] == 5) {
        binaryFins.add(last);
        binarySegments.add(payload);
        webSocket.send(ByteString.of(frame));
        webSocket.send(ByteString.of(codec.encodeAck(payload.length)));
      }
    }
  }
}
