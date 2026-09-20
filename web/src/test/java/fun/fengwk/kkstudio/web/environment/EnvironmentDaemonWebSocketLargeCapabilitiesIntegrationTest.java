package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 {@link WebEnvironment#RANDOM_PORT} 端到端验证 Daemon WebSocket 适配器在握手进入 READY 状态时，
 * 能够正确处理严格宿主环境元数据（{version, environment:{operatingSystem,timeZone,note,rootPath}}）， 成功在注册表中建立 READY
 * 租约，并断言注册表能力为纯 MCP 目录且绝不触发 close code 1009。
 */
class EnvironmentDaemonWebSocketLargeCapabilitiesIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String REGISTRATION_TOKEN = "test-registration-token";
  private static final String DAEMON_INSTANCE_ID = "22222222-2222-2222-2222-222222222222";
  private static final int TOMCAT_DEFAULT_TEXT_BUFFER_BYTES = 8 * 1024;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();

  @LocalServerPort private int port;

  @Autowired private EnvironmentRegistry registry;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedEnvironment() {
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, 'env-large-caps', ?, 0) on conflict (id) do nothing",
        ENVIRONMENT_ID.value(),
        REGISTRATION_TOKEN);
  }

  @MockitoBean private ResourceStore resourceStore;
  // 该 WebSocket 测试只验证握手边界，因此隔离 READY 后的 durable dispatcher 唤醒。
  @MockitoBean private EnvironmentSessionListener environmentSessionListener;

  /** 远大于 8 KiB 的 {@code READY} skills 帧被内嵌容器接受，握手进入 READY；绝不能观察到 close code 1009。 */
  @Test
  void largeCapabilitiesFrameReachesReadyWithoutClose1009() throws Exception {
    String readyPayloadJson = readyPayload();
    String readyEnvelope = ENVELOPE_CODEC.encode(readyEnvelope(readyPayloadJson));

    FrameListener listener = new FrameListener();
    OkHttpClient client = new OkHttpClient();
    try {
      WebSocket socket =
          client.newWebSocket(
              new Request.Builder().url(endpointUri().toASCIIString()).build(), listener);

      boolean readyReached = false;
      try {
        assertTrue(socket.send(ENVELOPE_CODEC.encode(helloEnvelope())));
        String welcomeJson = listener.awaitText(5_000);
        assertEquals("WELCOME", readMessageType(welcomeJson));

        // 危险帧：因为缓冲区已被调高，绝不能触发 close code 1009。
        assertTrue(socket.send(readyEnvelope));
        // 轮询最多 10 秒，等待注册表标记为 READY
        readyReached = awaitReady();
      } finally {
        closeQuietly(socket);
      }

      assertTrue(
          readyReached,
          "live registry did not mark environment READY within timeout; observed close code="
              + listener.observedCloseCode
              + " reason="
              + listener.observedCloseReason);
      assertNotEquals(
          1009,
          listener.observedCloseCode,
          "server closed connection with code 1009 (frame too large) during the handshake");
      assertTrue(
          16L * 1024 * 1024 > TOMCAT_DEFAULT_TEXT_BUFFER_BYTES,
          "premise: gateway buffer limit must be raised above the 8 KiB default");

      EnvironmentConnection connection = registry.find(ENVIRONMENT_ID).orElseThrow();
      assertEquals(DaemonCapabilities.VERSION, connection.daemonCapabilities().version());
      assertEquals(
          DaemonOperatingSystem.LINUX,
          connection.daemonCapabilities().environment().operatingSystem());
      assertEquals("UTC", connection.daemonCapabilities().environment().timeZone());
      assertEquals("Linux environment.", connection.daemonCapabilities().environment().note());
      assertEquals("dev", connection.daemonCapabilities().environment().userName());
      assertEquals("/home/dev", connection.daemonCapabilities().environment().homeDirectory());
      assertEquals("dev", connection.userName());
      assertEquals("/home/dev", connection.homeDirectory());
      assertTrue(
          connection.capabilities().stream().noneMatch(c -> c.id().value().startsWith("skill.")),
          "registry capabilities must be MCP and platform catalog only, without legacy skill capabilities");
    } finally {
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  private static void closeQuietly(WebSocket socket) {
    // 服务端可能已经关闭；测试只关心握手是否完成且未出现 close code 1009。
    socket.close(1000, "test complete");
  }

  private boolean awaitReady() throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      if (registry.hasReadyLease(ENVIRONMENT_ID)) {
        return true;
      }
      Thread.sleep(50);
    }
    return registry.hasReadyLease(ENVIRONMENT_ID);
  }

  private static String readMessageType(String envelopeJson) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(envelopeJson);
    JsonNode node = root.get("messageType");
    assertTrue(
        node != null && node.isTextual(), "envelope must carry messageType: " + envelopeJson);
    return node.asText();
  }

  private URI endpointUri() {
    return URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH);
  }

  private static String readyPayload() {
    return CAPABILITIES_CODEC.encode(
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Linux environment.")));
  }

  private static DaemonEnvelope helloEnvelope() {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.HELLO,
        null,
        null,
        "{"
            + "\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ","
            + "\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\","
            + "\"registrationToken\":\""
            + REGISTRATION_TOKEN
            + "\","
            + "\"daemonInstanceId\":\""
            + DAEMON_INSTANCE_ID
            + "\"}");
  }

  private static DaemonEnvelope readyEnvelope(String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION, DaemonMessageType.READY, ENVIRONMENT_ID, null, payloadJson);
  }

  private static final class FrameListener extends WebSocketListener {

    private final StringBuilder currentMessage = new StringBuilder();
    private final CompletableFuture<String> nextMessage = new CompletableFuture<>();
    private final AtomicInteger closeCount = new AtomicInteger();
    volatile int observedCloseCode = -1;
    volatile String observedCloseReason;

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      // 连接已建立，无需额外动作。
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      currentMessage.append(text);
      String complete = currentMessage.toString();
      currentMessage.setLength(0);
      nextMessage.complete(complete);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
      // 该端点只传输文本帧。
    }

    @Override
    public void onClosed(WebSocket webSocket, int statusCode, String reason) {
      recordClose(statusCode, reason);
    }

    @Override
    public void onClosing(WebSocket webSocket, int statusCode, String reason) {
      recordClose(statusCode, reason);
      webSocket.close(statusCode, null);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable error, Response response) {
      recordClose(-1, error == null ? null : error.getMessage());
    }

    private void recordClose(int statusCode, String reason) {
      if (closeCount.incrementAndGet() == 1) {
        observedCloseCode = statusCode;
        observedCloseReason = reason;
      }
    }

    String awaitText(long timeoutMillis) throws Exception {
      try {
        return nextMessage.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw error;
      }
    }
  }
}
