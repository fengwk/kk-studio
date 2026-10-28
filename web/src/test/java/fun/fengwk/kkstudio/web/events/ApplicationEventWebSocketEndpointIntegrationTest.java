package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.tomcat.websocket.WsRemoteEndpointImplBase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code /api/events/v2} 的真实端到端覆盖（RANDOM_PORT + testcontainers PG）：真实 Tomcat 握手、connected 首帧、
 * 严格帧解码、非法帧关闭、资源错误保活、合法订阅 ack，以及原生 ping 与文本共享同一物理 writer。
 */
class ApplicationEventWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String BINARY_PARTIAL_WRITING = "BINARY_PARTIAL_WRITING";

  @LocalServerPort private int port;

  @Autowired private ApplicationEventWebSocketHandler handler;
  @Autowired private IdeBrowserUseRegistry browserUseRegistry;

  @Test
  void handshakeDecodesStrictlyAndClosesOnInvalidFrame() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);

    socket.sendText("{\"op\":\"subscribe\"}", true).get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.version must be the integer 2\"}",
        collector.nextText());
    assertEquals(1002, collector.nextCloseCode(), "invalid protocol frame must close with 1002");
  }

  @Test
  void ideUseOverRealTomcatRegistersExactIntentAndPongTimeoutRevokesIt() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    ConnectionIdentity identity = assertConnected(collector);
    UUID documentId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    UUID environmentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    UUID viewId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    UUID claimId = UUID.fromString("66666666-6666-6666-6666-666666666666");

    socket
        .sendText(ideUseFrame(documentId, environmentId, viewId, claimId), true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"version\":2,\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", collector.nextText());
    assertTrue(
        browserUseRegistry
            .findExactIntent(identity, documentId, EnvironmentId.of(environmentId), viewId, claimId)
            .isPresent());
    assertEquals(
        9L,
        browserUseRegistry
            .activeBoundProofs(identity, documentId)
            .orElseThrow()
            .views()
            .getFirst()
            .bindingEpoch());

    // 同 revision 不同内容被拒绝，但连接保持可用。
    socket
        .sendText(ideUseFrame(documentId, environmentId, viewId, UUID.randomUUID()), true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"IDE_USE_REJECTED\",\"message\":\"ide-use revision must increase and keep identical content for the same revision\"}",
        collector.nextText());

    // 以合成时间越过 45 秒：真实 Tomcat 连接以 1013 关闭，事件节点立即撤销证明。
    handler.ping(System.nanoTime() + TimeUnit.SECONDS.toNanos(46));
    assertEquals(1013, collector.nextCloseCode());
    assertFalse(browserUseRegistry.usage(identity).isPresent());
  }

  @Test
  void ideUseAcceptsTheMaximumViewListOverRealTomcat() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);

    String documentId = UUID.randomUUID().toString();
    String environmentId = UUID.randomUUID().toString();
    String claimId = UUID.randomUUID().toString();
    StringBuilder views = new StringBuilder();
    for (int i = 0; i < 64; i++) {
      if (i > 0) {
        views.append(',');
      }
      views
          .append("{\"environmentId\":\"")
          .append(environmentId)
          .append("\",\"viewId\":\"")
          .append(new UUID(0L, i + 1))
          .append("\",\"claimId\":\"")
          .append(claimId)
          .append("\",\"bindingEpoch\":\"")
          .append(Long.MAX_VALUE)
          .append("\"}");
    }
    String frame =
        "{\"version\":2,\"type\":\"ide-use\",\"documentId\":\""
            + documentId
            + "\",\"revision\":\"1\",\"views\":["
            + views
            + "]}";
    assertTrue(frame.length() > 8192, "the maximum list must exercise Tomcat's default text limit");

    socket.sendText(frame, true).get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", collector.nextText());
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void subscribeUnknownThreadSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);

    String unknown = "00000000-0000-0000-0000-000000000999";
    socket
        .sendText(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + unknown
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + unknown
            + "\"}}",
        collector.nextText());

    // 连接保持：第二个未知资源仍走资源级 error，而不是被关闭。
    String another = "00000000-0000-0000-0000-000000000998";
    socket
        .sendText(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + another
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + another
            + "\"}}",
        collector.nextText());

    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void pingConnectedAndTextUseOneTomcatPhysicalWriter() throws Exception {
    Logger tomcat = (Logger) LoggerFactory.getLogger(WsRemoteEndpointImplBase.class);
    Level previousLevel = tomcat.getLevel();
    boolean previousAdditivity = tomcat.isAdditive();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(tomcat.getLoggerContext());
    logs.start();
    tomcat.addAppender(logs);
    tomcat.setLevel(Level.ALL);
    tomcat.setAdditive(false);
    try {
      FrameCollector collector = new FrameCollector();
      WebSocket socket = connect(collector);
      assertConnected(collector);

      // Tomcat 11 的 AsyncRemote.sendPing 走 sendMessageBlock：同步取得 messagePartInProgress
      // 信号量并等待 BlockingSendHandler 完成，因此 ping 返回后同一 writer 已可安全写下一帧。
      // 连续触发文本、ping、文本，真实容器不得出现并发写状态 BINARY_PARTIAL_WRITING。
      for (int i = 1; i <= 16; i++) {
        String unknown = new UUID(0L, i).toString();
        socket
            .sendText(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + unknown
                    + "\"}}",
                true)
            .get(10, TimeUnit.SECONDS);
        handler.ping();
      }

      for (int i = 1; i <= 16; i++) {
        String unknown = new UUID(0L, i).toString();
        assertEquals(
            "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + unknown
                + "\"}}",
            collector.nextText());
      }
      assertFalse(
          logs.list.stream()
              .anyMatch(ApplicationEventWebSocketEndpointIntegrationTest::isBinaryPartialWriting),
          "same physical writer must never enter BINARY_PARTIAL_WRITING");
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
    } finally {
      tomcat.detachAppender(logs);
      tomcat.setLevel(previousLevel);
      tomcat.setAdditive(previousAdditivity);
    }
  }

  @Test
  void subscribeExistingThreadReturnsSubscribedAckWithCursor() throws Exception {
    // 经真实 API 创建 Chat，并单次提交 command-batch 创建 Session/Thread；再经 WS 订阅验证 ack。
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> chatResponse =
        http.send(
            HttpRequest.newBuilder(uri("/api/chats"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"title\":\"ws-event-test\",\"agentName\":\"default-assistant\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, chatResponse.statusCode(), chatResponse.body());
    String chatId = MAPPER.readTree(chatResponse.body()).path("data").path("id").asText();

    String sessionId = UUID.randomUUID().toString();
    String threadId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    String createBody =
        """
        {
          "owner":{"type":"CHAT","chatId":"%s"},
          "target":{
            "type":"NEW_SESSION",
            "sessionId":"%s",
            "threadId":"%s",
            "rootSettings":{
              "agentName":"default-assistant",
              "model":{
                "modelName":"acceptance-stub",
                "variant":"default"
              },
              "environmentName":null
            },
            "yoloEnabled":false
          },
          "commands":[{
            "type":"USER_MESSAGE",
            "idempotencyKey":"%s",
            "contents":[{"type":"TEXT","text":"hello"}]
          }]
        }
        """
            .formatted(chatId, sessionId, threadId, idempotencyKey);
    HttpResponse<String> threadResponse =
        http.send(
            HttpRequest.newBuilder(uri("/api/command-batches"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, threadResponse.statusCode(), threadResponse.body());
    JsonNode created = MAPPER.readTree(threadResponse.body()).path("data");
    assertEquals(threadId, created.path("thread").path("threadId").asText());
    String version = created.path("thread").path("version").asText();

    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);
    socket
        .sendText(
            "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + threadId
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + threadId
            + "\"},\"cursor\":\""
            + version
            + "\"}",
        collector.nextText());
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  private ConnectionIdentity assertConnected(FrameCollector collector) throws Exception {
    JsonNode connected = MAPPER.readTree(collector.nextText());
    assertEquals(2, connected.path("version").asInt());
    assertEquals("connected", connected.path("type").asText());
    String eventNodeId = connected.path("eventNodeId").asText();
    String connectionId = connected.path("connectionId").asText();
    assertEquals(eventNodeId, UUID.fromString(eventNodeId).toString());
    assertEquals(connectionId, UUID.fromString(connectionId).toString());
    return ConnectionIdentity.parse(eventNodeId, connectionId);
  }

  private static String ideUseFrame(
      UUID documentId, UUID environmentId, UUID viewId, UUID claimId) {
    return """
        {"version":2,"type":"ide-use","documentId":"%s","revision":"1","views":[
          {"environmentId":"%s","viewId":"%s","claimId":"%s","bindingEpoch":"9"}]}
        """
        .formatted(documentId, environmentId, viewId, claimId);
  }

  private static boolean isBinaryPartialWriting(ILoggingEvent event) {
    if (event.getFormattedMessage().contains(BINARY_PARTIAL_WRITING)) {
      return true;
    }
    return event.getThrowableProxy() != null
        && event.getThrowableProxy().getMessage() != null
        && event.getThrowableProxy().getMessage().contains(BINARY_PARTIAL_WRITING);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private WebSocket connect(FrameCollector collector) throws Exception {
    return HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .buildAsync(
            URI.create("ws://localhost:" + port + ApplicationEventWebSocketHandler.PATH), collector)
        .get(10, TimeUnit.SECONDS);
  }

  private static final class FrameCollector implements WebSocket.Listener {

    private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> closeCodes = new LinkedBlockingQueue<>();
    private final StringBuilder currentMessage = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      currentMessage.append(data);
      if (last) {
        texts.add(currentMessage.toString());
        currentMessage.setLength(0);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closeCodes.add(statusCode);
      return CompletableFuture.completedFuture(null);
    }

    private String nextText() throws Exception {
      String text = texts.poll(10, TimeUnit.SECONDS);
      assertNotNull(text, "expected a server text frame within 10s");
      return text;
    }

    private int nextCloseCode() throws Exception {
      Integer code = closeCodes.poll(10, TimeUnit.SECONDS);
      assertNotNull(code, "expected a close frame within 10s");
      return code;
    }
  }
}
