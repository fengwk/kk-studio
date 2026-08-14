package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * {@code /api/events/v1} 的真实端到端覆盖（RANDOM_PORT + testcontainers PG）：真实握手、严格帧解码、 非法帧关闭、资源错误保活与合法订阅
 * ack。
 */
class ApplicationEventWebSocketEndpointIntegrationTest extends WebPostgresTestSupport {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @LocalServerPort private int port;

  @Test
  void handshakeDecodesStrictlyAndClosesOnInvalidFrame() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);

    socket.sendText("{\"op\":\"subscribe\"}", true).get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"INVALID_FRAME\",\"message\":\"invalid frame: frame must contain exactly [version, type, resource] fields\"}",
        collector.nextText());
    assertEquals(1008, collector.nextCloseCode(), "invalid protocol frame must close with 1008");
  }

  @Test
  void subscribeUnknownThreadSendsResourceErrorAndKeepsConnectionOpen() throws Exception {
    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);

    String unknown = "00000000-0000-0000-0000-000000000999";
    socket
        .sendText(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + unknown
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"unknown thread: "
            + unknown
            + "\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + unknown
            + "\"}}",
        collector.nextText());

    // 连接保持：第二个未知资源仍走资源级 error，而不是被关闭。
    String another = "00000000-0000-0000-0000-000000000998";
    socket
        .sendText(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + another
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);
    assertEquals(
        "{\"version\":1,\"type\":\"error\",\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"unknown thread: "
            + another
            + "\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + another
            + "\"}}",
        collector.nextText());

    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
  }

  @Test
  void subscribeExistingThreadReturnsSubscribedAckWithCursor() throws Exception {
    // 经真实 API 创建 Chat + Thread（workers disabled 下同步完成），再经 WS 订阅验证 ack。
    HttpClient http = HttpClient.newHttpClient();
    HttpResponse<String> chatResponse =
        http.send(
            HttpRequest.newBuilder(uri("/api/ai/chat"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"title\":\"ws-event-test\",\"agentName\":\"default-assistant\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, chatResponse.statusCode(), chatResponse.body());
    String chatId = MAPPER.readTree(chatResponse.body()).path("data").path("id").asText();

    String createBody =
        "{\"branchSettings\":{\"environmentName\":null,\"agentName\":\"default-assistant\","
            + "\"model\":{\"providerName\":\"stub\",\"modelName\":\"acceptance-stub\",\"variant\":\"default\"},"
            + "\"activeTools\":[]},\"yoloEnabled\":false}";
    HttpResponse<String> threadResponse =
        http.send(
            HttpRequest.newBuilder(uri("/api/ai/chat/" + chatId + "/threads"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, threadResponse.statusCode(), threadResponse.body());
    JsonNode created = MAPPER.readTree(threadResponse.body()).path("data");
    String threadId = created.path("thread").path("threadId").asText();
    assertNotNull(threadId);

    FrameCollector collector = new FrameCollector();
    WebSocket socket = connect(collector);
    socket
        .sendText(
            "{\"version\":1,\"type\":\"subscribe\",\"resource\":{\"kind\":\"thread\",\"id\":\""
                + threadId
                + "\"}}",
            true)
        .get(10, TimeUnit.SECONDS);

    assertEquals(
        "{\"version\":1,\"type\":\"subscribed\",\"resource\":{\"kind\":\"thread\",\"id\":\""
            + threadId
            + "\"},\"cursor\":\"0\"}",
        collector.nextText());
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(10, TimeUnit.SECONDS);
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
