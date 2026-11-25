package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.browser.ConnectionIdentity;
import fun.fengwk.kkstudio.ide.browser.IdeBrowserUseRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code /ws/events} 的真实端到端覆盖（RANDOM_PORT + testcontainers PG）：真实 Jetty 握手、connected 首帧、
 * 严格帧解码、非法帧关闭、资源错误保活、合法订阅 ack，以及原生 ping 与文本共享同一物理 writer 串行发送。
 */
class ApplicationEventWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
        "{\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.type must be a string\"}",
        collector.nextText());
    assertEquals(1002, collector.nextCloseCode(), "invalid protocol frame must close with 1002");
  }

  @Test
  void ideUseOverRealJettyRegistersExactIntentAndPongTimeoutRevokesIt() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    ConnectionIdentity identity = assertConnected(collector);
    UUID browserSessionId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    UUID environmentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    UUID viewId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    UUID claimId = UUID.fromString("66666666-6666-6666-6666-666666666666");

    socket
        .sendText(ideUseFrame(browserSessionId, environmentId, viewId, claimId), true)
        .get(10, TimeUnit.SECONDS);
    assertEquals("{\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", collector.nextText());
    assertTrue(
        browserUseRegistry
            .findExactIntent(
                identity, browserSessionId, EnvironmentId.of(environmentId), viewId, claimId)
            .isPresent());
    assertEquals(
        9L,
        browserUseRegistry
            .activeBoundProofs(identity, browserSessionId)
            .orElseThrow()
            .views()
            .getFirst()
            .bindingEpoch());

    // 同 revision 不同内容被拒绝，但连接保持可用。
    socket
        .sendText(ideUseFrame(browserSessionId, environmentId, viewId, UUID.randomUUID()), true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"type\":\"error\",\"code\":\"IDE_USE_REJECTED\",\"message\":\"ide-use revision must increase and keep identical content for the same revision\"}",
        collector.nextText());

    // 以合成时间越过 45 秒：真实连接以 1013 关闭，事件节点立即撤销证明。
    handler.ping(System.nanoTime() + TimeUnit.SECONDS.toNanos(46));
    assertEquals(1013, collector.nextCloseCode());
    assertFalse(browserUseRegistry.usage(identity).isPresent());
  }

  @Test
  void ideUseAcceptsTheMaximumViewListOverRealJetty() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);

    String browserSessionId = UUID.randomUUID().toString();
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
        "{\"type\":\"ide-use\",\"browserSessionId\":\""
            + browserSessionId
            + "\",\"revision\":\"1\",\"views\":["
            + views
            + "]}";
    assertTrue(frame.length() > 8192, "the maximum list must exercise the default text limit");

    socket.sendText(frame, true).get(10, TimeUnit.SECONDS);

    assertEquals("{\"type\":\"ide-use-accepted\",\"revision\":\"1\"}", collector.nextText());
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
            "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + unknown
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + unknown
            + "\"}}",
        collector.nextText());

    // 连接保持：第二个未知资源仍走资源级 error，而不是被关闭。
    String another = "00000000-0000-0000-0000-000000000998";
    socket
        .sendText(
            "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + another
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + another
            + "\"}}",
        collector.nextText());

    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void pingConnectedAndTextUseSerializedPhysicalWriter() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);

    // 文本与原生 ping 全部经由 AsyncTextSender 的异步 Callback 串行队列发出。
    // 连续触发文本、ping、文本，由同一物理 writer 串行排队发送，不产生并发写异常或帧损坏。
    for (int i = 1; i <= 16; i++) {
      String unknown = new UUID(0L, i).toString();
      socket
          .sendText(
              "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                  + unknown
                  + "\"}}",
              true)
          .get(10, TimeUnit.SECONDS);
      handler.ping();
    }

    for (int i = 1; i <= 16; i++) {
      String unknown = new UUID(0L, i).toString();
      assertEquals(
          "{\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
              + unknown
              + "\"}}",
          collector.nextText());
    }
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void subscribeExistingThreadAcknowledgesBeforeDurableCatchUp() throws Exception {
    // 走真实 HTTP 首载和 WS 续接，验证 ACK 先于权威 snapshot，而不是 Thread version 通知。
    HttpClient http = HttpClient.newHttpClient();
    String threadId = createThread(http, "hello");
    HttpResponse<String> initial =
        http.send(
            adminRequest(uri("/api/harness/threads/" + threadId)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, initial.statusCode());
    String base = MAPPER.readTree(initial.body()).path("thread").path("headEntryId").asText();
    assertEquals(base, UUID.fromString(base).toString());

    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    assertConnected(collector);
    socket
        .sendText(
            "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + threadId
                + "\"},\"afterEntryId\":\""
                + base
                + "\"}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + threadId
            + "\"},\"cursor\":\"0\"}",
        collector.nextText());
    JsonNode snapshot = MAPPER.readTree(collector.nextText());
    assertEquals("event", snapshot.path("type").asText());
    assertEquals("snapshot", snapshot.path("name").asText());
    assertFalse(snapshot.has("cursor"));
    assertEquals(threadId, snapshot.path("data").path("thread").path("threadId").asText());
    assertEquals(base, snapshot.path("data").path("baseEntryId").asText());
    assertTrue(snapshot.path("data").path("entries").isArray());
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);

    // Compare actual JSON types, not asText(): the browser requires decimal strings for bigint.
    HttpResponse<String> delta =
        http.send(
            adminRequest(uri("/api/harness/threads/" + threadId + "?afterEntryId=" + base))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, delta.statusCode());
    JsonNode httpSnapshot = MAPPER.readTree(delta.body());
    assertTrue(httpSnapshot.path("queuedCommands").path(0).path("sequence").isString());
    assertEquals(httpSnapshot, snapshot.path("data"));
  }

  /**
   * 测试意图：真实 Jetty + PG 上，超出本连接单帧 UTF-8 预算的 Thread 快照不再拖垮 socket，而是改发带真实 DTO version 的 {@code
   * snapshot-ref}；同一物理连接随后仍能收到另一资源的健康快照。
   */
  @Test
  void oversizedThreadSnapshotBecomesSnapshotRefAndKeepsTheRealSocketHealthy() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    String chatId = createChat(http);
    String oversizedThread = createThread(http, chatId, "o".repeat(20_000));
    String healthyThread = createThread(http, chatId, "hello");
    JsonNode oversizedSnapshot = getThreadSnapshot(http, oversizedThread);
    JsonNode healthySnapshot = getThreadSnapshot(http, healthyThread);

    // 预算由真实 HTTP 快照体积推导：健康快照放得下，超限快照严格放不下（不硬编码字节数）。
    int healthyBytes = utf8(healthySnapshot);
    int oversizedBytes = utf8(oversizedSnapshot);
    assertTrue(
        oversizedBytes > healthyBytes * 2,
        "the oversized fixture must really be much larger than the healthy one");
    long maxBytes = healthyBytes + (oversizedBytes - healthyBytes) / 2L;
    assertTrue(maxBytes > healthyBytes, "the budget must still accept the healthy snapshot");
    assertTrue(maxBytes < oversizedBytes, "the budget must reject the oversized snapshot");
    long originalMaxBytes = updateApplicationEventMaxBytes(http, maxBytes);
    try {
      FrameCollector collector = new FrameCollector();
      WebSocket socket = connect(collector);
      assertConnected(collector);

      socket.sendText(subscribeThreadFrame(oversizedThread), true).get(10, TimeUnit.SECONDS);
      assertEquals(subscribedFrame(oversizedThread), collector.nextText());

      JsonNode ref = MAPPER.readTree(collector.nextText());
      assertEquals("snapshot-ref", ref.path("type").asText());
      assertEquals("thread", ref.path("resource").path("kind").asText());
      assertEquals(oversizedThread, ref.path("resource").path("id").asText());
      // version 是顶层字段且等于权威 HTTP 快照的真实 DTO version，不是合成 cursor。
      assertEquals(oversizedSnapshot.path("version").asText(), ref.path("version").asText());
      assertEquals(
          oversizedSnapshot.path("version").asText(),
          oversizedSnapshot.path("thread").path("version").asText());
      assertTrue(ref.path("version").isString());
      assertFalse(ref.has("name"));
      assertFalse(ref.has("data"));

      // 同一物理连接继续可用：另一 Thread 的健康快照照常作为普通 event 投递。
      socket.sendText(subscribeThreadFrame(healthyThread), true).get(10, TimeUnit.SECONDS);
      assertEquals(subscribedFrame(healthyThread), collector.nextText());
      JsonNode healthyEvent = MAPPER.readTree(collector.nextText());
      assertEquals("event", healthyEvent.path("type").asText());
      assertEquals("snapshot", healthyEvent.path("name").asText());
      assertEquals(
          healthyThread, healthyEvent.path("data").path("thread").path("threadId").asText());
      assertEquals(healthySnapshot, healthyEvent.path("data"));
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
    } finally {
      updateApplicationEventMaxBytes(http, originalMaxBytes);
    }
  }

  private static String subscribeThreadFrame(String threadId) {
    return "{\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"}}";
  }

  private static String subscribedFrame(String threadId) {
    return "{\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
        + threadId
        + "\"},\"cursor\":\"0\"}";
  }

  private static int utf8(JsonNode node) {
    return node.toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private String createChat(HttpClient http) throws Exception {
    String body = "{\"title\":\"ws-oversize\",\"agentName\":\"default-assistant\"}";
    HttpResponse<String> response =
        http.send(
            adminRequest(uri("/api/chat/chats"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode(), response.body());
    return MAPPER.readTree(response.body()).path("id").asText();
  }

  private String createThread(HttpClient http, String chatId, String userText) throws Exception {
    String threadId = UUID.randomUUID().toString();
    String createBody =
        """
        {
          "owner":{"kind":"CHAT","id":"%s"},
          "target":{
            "type":"NEW_SESSION",
            "sessionId":"%s",
            "threadId":"%s",
            "rootSettings":{
              "agentName":"default-assistant",
              "execution":{
                "driver":"builtin",
                "settings":{"modelName":"acceptance-stub","variant":"default"}
              },
              "environmentName":null
            },
            "yoloEnabled":false
          },
          "commands":[{
            "type":"USER_MESSAGE",
            "idempotencyKey":"%s",
            "contents":[{"type":"TEXT","text":"%s"}]
          }]
        }
        """
            .formatted(chatId, UUID.randomUUID(), threadId, UUID.randomUUID(), userText);
    HttpResponse<String> response =
        http.send(
            adminRequest(uri("/api/harness/command-batches"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode(), response.body());
    assertEquals(
        threadId, MAPPER.readTree(response.body()).path("thread").path("threadId").asText());
    return threadId;
  }

  private JsonNode getThreadSnapshot(HttpClient http, String threadId) throws Exception {
    HttpResponse<String> response =
        http.send(
            adminRequest(uri("/api/harness/threads/" + threadId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return MAPPER.readTree(response.body());
  }

  /**
   * 通过公开设置端点调整后续连接的发送字节预算：完整回送当前聚合，只改一个 advanced 字段。
   *
   * <p>每条连接在建立时冻结当时的设置快照，因此调整必须在 {@link #connect} 之前完成。
   */
  private long updateApplicationEventMaxBytes(HttpClient http, long maxBytes) throws Exception {
    HttpResponse<String> current =
        http.send(
            adminRequest(uri("/api/platform/settings"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, current.statusCode());
    ObjectNode body = (ObjectNode) MAPPER.readTree(current.body());
    // version / createdAt / updatedAt 是只读输出，写请求 DTO 没有这些字段。
    long version = Long.parseLong(body.get("version").asString());
    long previousMaxBytes =
        Long.parseLong(
            ((ObjectNode) body.get("advanced")).get("applicationEventMaxBytes").asString());
    body.remove("version");
    body.remove("createdAt");
    body.remove("updatedAt");
    body.put("expectedVersion", Long.toString(version));
    ((ObjectNode) body.get("advanced")).put("applicationEventMaxBytes", Long.toString(maxBytes));

    HttpResponse<String> response =
        http.send(
            adminRequest(uri("/api/platform/settings"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(
        Long.toString(maxBytes),
        MAPPER
            .readTree(response.body())
            .path("advanced")
            .path("applicationEventMaxBytes")
            .asText());
    return previousMaxBytes;
  }

  private ConnectionIdentity assertConnected(FrameCollector collector) throws Exception {
    JsonNode connected = MAPPER.readTree(collector.nextText());
    assertFalse(connected.has("version"));
    assertEquals("connected", connected.path("type").asText());
    String eventNodeId = connected.path("eventNodeId").asText();
    String connectionId = connected.path("connectionId").asText();
    assertEquals(eventNodeId, UUID.fromString(eventNodeId).toString());
    assertEquals(connectionId, UUID.fromString(connectionId).toString());
    // 真实端到端握手必须声明服务端 heartbeat 间隔，前端失联预算依赖它。
    assertTrue(
        connected.path("heartbeatIntervalMillis").asLong() > 0,
        "connected frame must declare a positive heartbeatIntervalMillis");
    return ConnectionIdentity.parse(eventNodeId, connectionId);
  }

  private static String ideUseFrame(
      UUID browserSessionId, UUID environmentId, UUID viewId, UUID claimId) {
    return """
        {"type":"ide-use","browserSessionId":"%s","revision":"1","views":[
          {"environmentId":"%s","viewId":"%s","claimId":"%s","bindingEpoch":"9"}]}
        """
        .formatted(browserSessionId, environmentId, viewId, claimId);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String origin() {
    return "http://localhost:" + port;
  }

  private WebSocket connect(FrameCollector collector) throws Exception {
    return HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .header("Cookie", adminCookieHeader())
        .header("Origin", origin())
        .buildAsync(URI.create("ws://localhost:" + port + "/ws/events"), collector)
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
