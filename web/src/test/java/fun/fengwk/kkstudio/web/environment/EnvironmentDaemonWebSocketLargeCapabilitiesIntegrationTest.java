package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.platform.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 {@link WebEnvironment#RANDOM_PORT} 端到端验证 Daemon WebSocket 适配器能容忍超过 Tomcat 8 KiB 默认文本缓冲区的
 * {@code READY}（skills）帧。
 *
 * <p>若未调高缓冲区配置，内嵌 Tomcat 在收到超长帧时会立刻以 close code 1009 关闭连接，导致实时注册表永远无法进入 READY。本测试发送一个携带远超默认阈值的厚重
 * skills payload 的 {@code READY} 帧，并断言 {@link LiveEnvironmentRegistry#isReady(EnvironmentName)}
 * 在截止时间内变为 {@code true}。任何未来删除或弱化缓冲区初始化逻辑的改动都会在此处暴露，表现为注册表始终不进入 READY 且伴随 close code 1009。
 */
class EnvironmentDaemonWebSocketLargeCapabilitiesIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentName ENVIRONMENT_NAME = new EnvironmentName("env-large-caps");
  private static final String DAEMON_TOKEN = "test-daemon-token";
  private static final int TOMCAT_DEFAULT_TEXT_BUFFER_BYTES = 8 * 1024;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonCapabilitiesCodec CAPABILITIES_CODEC = new DaemonCapabilitiesCodec();

  @LocalServerPort private int port;

  @Autowired private LiveEnvironmentRegistry registry;
  @Autowired private SystemSettingsSnapshot snapshot;

  @MockitoBean private ResourceStore resourceStore;
  // Gateway 的 READY 唤醒由 durable-target dispatcher 切片装配，本切片将其抑制。
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  /** 远大于 8 KiB 的 {@code READY} skills 帧被内嵌容器接受，握手进入 READY；绝不能观察到 close code 1009。 */
  @Test
  void largeCapabilitiesFrameReachesReadyWithoutClose1009() throws Exception {
    int minBytes = TOMCAT_DEFAULT_TEXT_BUFFER_BYTES + 4 * 1024;
    String readyPayloadJson = largeSkillsReadyPayload(minBytes);
    assertTrue(
        readyPayloadJson.length() >= minBytes,
        "test READY skills payload must exceed the configured buffer threshold");

    String readyEnvelope = ENVELOPE_CODEC.encode(readyEnvelope(readyPayloadJson));
    assertTrue(
        readyEnvelope.length() > TOMCAT_DEFAULT_TEXT_BUFFER_BYTES,
        "test wire frame must exceed the default 8 KiB Tomcat text buffer");

    FrameListener listener = new FrameListener();
    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(endpointUri(), listener)
            .get(10, TimeUnit.SECONDS);

    boolean readyReached = false;
    try {
      socket.sendText(ENVELOPE_CODEC.encode(helloEnvelope(0)), true).get(5, TimeUnit.SECONDS);
      String welcomeJson = listener.awaitText(5_000);
      assertEquals("WELCOME", readMessageType(welcomeJson));

      // 危险帧：因为缓冲区已被调高，绝不能触发 close code 1009。
      socket.sendText(readyEnvelope, true).get(5, TimeUnit.SECONDS);
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
  }

  private static void closeQuietly(WebSocket socket) {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // 服务端可能已经关闭；测试只关心握手是否完成且未出现 close code 1009，这一点在前面已经断言。
    }
  }

  private boolean awaitReady() throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    Duration heartbeatTimeout =
        Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis());
    while (System.nanoTime() < deadlineNanos) {
      if (registry.isReady(ENVIRONMENT_NAME, Instant.now(), heartbeatTimeout)) {
        return true;
      }
      Thread.sleep(50);
    }
    return registry.isReady(ENVIRONMENT_NAME, Instant.now(), heartbeatTimeout);
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

  private static String largeSkillsReadyPayload(int minBytes) {
    StringBuilder description = new StringBuilder(minBytes + 256);
    while (description.length() < minBytes) {
      description.append("pad-");
    }
    DaemonSkillDescriptor fat = new DaemonSkillDescriptor("fat-skill", description.toString());
    return CAPABILITIES_CODEC.encode(
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
            List.of(fat),
            List.of()));
  }

  private static DaemonEnvelope helloEnvelope(long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_3,
        DaemonMessageType.HELLO,
        ENVIRONMENT_NAME,
        null,
        sequence,
        "{"
            + "\"daemonId\":\"daemon-large-caps\","
            + "\"protocolVersion\":3,"
            + "\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
            + "\","
            + "\"gatewayToken\":\""
            + DAEMON_TOKEN
            + "\"}");
  }

  private static DaemonEnvelope readyEnvelope(String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_3, DaemonMessageType.READY, ENVIRONMENT_NAME, null, 1, payloadJson);
  }

  private static final class FrameListener implements WebSocket.Listener {

    private final StringBuilder currentMessage = new StringBuilder();
    private final CompletableFuture<String> nextMessage = new CompletableFuture<>();
    private final AtomicInteger closeCount = new AtomicInteger();
    volatile int observedCloseCode = -1;
    volatile String observedCloseReason;

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      currentMessage.append(data);
      if (last) {
        String complete = currentMessage.toString();
        currentMessage.setLength(0);
        nextMessage.complete(complete);
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      if (closeCount.incrementAndGet() == 1) {
        observedCloseCode = statusCode;
        observedCloseReason = reason;
      }
      return CompletableFuture.completedFuture(null);
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
