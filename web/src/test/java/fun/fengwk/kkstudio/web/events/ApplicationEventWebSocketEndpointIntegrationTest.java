package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code /api/events/v1} 的真实端到端覆盖（RANDOM_PORT + testcontainers PG）：真实握手、唯一 carrier 载体上的 v2 严格帧解码、
 * 非法帧关闭、资源错误保活与合法订阅 ack。
 */
class ApplicationEventWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-00000000c11e");

  @LocalServerPort private int port;

  @Autowired private DataSource dataSource;

  @Autowired private NotificationBus notificationBus;

  @Test
  void handshakeDecodesStrictlyAndClosesOnInvalidFrame() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);

    socket.sendText(carrier("{\"op\":\"subscribe\"}"), true).get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame.version must be the integer 2\"}",
        body(collector.nextText()));
    assertEquals(1002, collector.nextCloseCode(), "invalid protocol frame must close with 1002");
  }

  @Test
  void subscribeUnknownThreadSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);

    String unknown = "00000000-0000-0000-0000-000000000999";
    socket
        .sendText(
            carrier(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + unknown
                    + "\"}}"),
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + unknown
            + "\"}}",
        body(collector.nextText()));

    // 连接保持：第二个未知资源仍走资源级 error，而不是被关闭。
    String another = "00000000-0000-0000-0000-000000000998";
    socket
        .sendText(
            carrier(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + another
                    + "\"}}"),
            true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"version\":2,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Resource not found\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + another
            + "\"}}",
        body(collector.nextText()));

    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void subscribeExistingThreadReturnsSubscribedAckWithCursor() throws Exception {
    // 经真实 API 创建 Chat，并单次提交 command-batch 创建 Session/Thread；再经 WS 订阅验证 ack。
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> chatResponse =
        http.send(
            HttpRequest.newBuilder(uri("/api/ai/chats"))
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
                "providerName":"stub",
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
            HttpRequest.newBuilder(uri("/api/harness/command-batches"))
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
    socket
        .sendText(
            carrier(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                    + threadId
                    + "\"}}"),
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":2,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + threadId
            + "\"},\"cursor\":\""
            + version
            + "\"}",
        body(collector.nextText()));
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  /**
   * 真实 PG 触发的 NotificationBus resync：断开唯一 LISTEN 连接后，打开的浏览器连接必须以 1012 SERVICE_RESTART 关闭， 而不是被静默保留为
   * open；browser 随后自行重连并显式 ATTACH。
   */
  @Test
  void busResyncClosesOpenConnectionWithServiceRestart() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    // 探针：完成一次资源错误往返，确保服务端连接已登记到 ShellGateway。
    socket
        .sendText(
            carrier(
                "{\"version\":2,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\"00000000-0000-0000-0000-000000000999\"}}"),
            true)
        .get(10, TimeUnit.SECONDS);
    assertTrue(body(collector.nextText()).contains("RESOURCE_NOT_FOUND"));

    assertTrue(terminateBusListenBackends() > 0, "expected a live bus LISTEN backend");

    assertEquals(1012, collector.nextCloseCode(), "bus resync must close with SERVICE_RESTART");
  }

  /** 终止所有应用 NotificationBus 的 LISTEN 后端连接，触发真实传输重连与 resync。 */
  private int terminateBusListenBackends() throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      int terminated = terminateListenBackendsOnce();
      if (terminated > 0) {
        return terminated;
      }
      Thread.sleep(100);
    }
    return 0;
  }

  private int terminateListenBackendsOnce() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement listener =
            connection.prepareStatement(
                "select pid from pg_stat_activity"
                    + " where datname = current_database()"
                    + " and application_name = ?"
                    + " and pid <> pg_backend_pid()");
        Statement statement = connection.createStatement()) {
      listener.setString(1, "kk-studio-notification-" + notificationBus.nodeId());
      List<Integer> pids = new ArrayList<>();
      try (ResultSet rs = listener.executeQuery()) {
        while (rs.next()) {
          pids.add(rs.getInt(1));
        }
      }
      int terminated = 0;
      for (int pid : pids) {
        try (ResultSet rs = statement.executeQuery("select pg_terminate_backend(" + pid + ")")) {
          if (rs.next() && rs.getBoolean(1)) {
            terminated++;
          }
        }
      }
      return terminated;
    }
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  /** 把逻辑正文包成唯一物理 carrier：publisher=浏览器、target=广播、固定 topic {@code app.events.v2}。 */
  private static String carrier(String logicalBody) {
    byte[] bytes = logicalBody.getBytes(StandardCharsets.UTF_8);
    return new NotificationCarrier(
            CLIENT,
            null,
            ApplicationEventWebSocketHandler.CARRIER_TOPIC,
            UUID.randomUUID(),
            0,
            NotificationCarrier.count(bytes.length),
            bytes.length,
            bytes)
        .encode();
  }

  /** 解出服务端 carrier 的逻辑正文（用浏览器身份解码，避免 own-echo 丢弃）。 */
  private static String body(String rawFrame) {
    NotificationCarrier carrier =
        NotificationCarrier.decode(rawFrame, NotificationLimits.defaults(), CLIENT);
    assertNotNull(carrier, "expected a carrier frame from the server");
    assertEquals(1, carrier.count(), "small test bodies must be a single fragment");
    return new String(carrier.bytes(), StandardCharsets.UTF_8);
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
