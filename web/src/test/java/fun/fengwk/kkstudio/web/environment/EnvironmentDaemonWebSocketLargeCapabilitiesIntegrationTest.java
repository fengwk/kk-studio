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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 {@link WebEnvironment#RANDOM_PORT} 端到端验证 Daemon WebSocket 适配器能容忍超过 Tomcat 8 KiB 默认文本缓冲区的
 * {@code READY}（skills）帧。
 *
 * <p>若未调高缓冲区配置，内嵌 Tomcat 在收到超长帧时会立刻以 close code 1009 关闭连接，导致实时注册表永远无法进入 READY。本测试发送一个携带远超默认阈值的厚重
 * skills payload 的 {@code READY} 帧，并断言 {@link EnvironmentRegistry#hasReadyLease(EnvironmentId)}
 * 在截止时间内变为 {@code true}。任何未来删除或弱化缓冲区初始化逻辑的改动都会在此处暴露，表现为注册表始终不进入 READY 且伴随 close code 1009。
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
  private static final UUID FAT_SOURCE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final String REVISION = "a".repeat(64);

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

  /**
   * 生成超过 {@code minBytes} 的合法 READY payload。
   *
   * <p>厚帧必须由真实来源快照构成：descriptor 要携带完整身份（sourceId/sourceVersion/baseDirectory/revision）， 否则严格 codec
   * 会拒绝。单条 description 被限制在 {@link DaemonSkillDescriptor#MAX_DESCRIPTION_CHARS}，因此厚度来自多个合规
   * Skill，而不是一条超长描述。
   */
  private static String largeSkillsReadyPayload(int minBytes) {
    for (int skillCount = 64; skillCount <= 4096; skillCount += 64) {
      String payload = fatSkillsReadyPayload(skillCount);
      if (payload.length() >= minBytes) {
        return payload;
      }
    }
    throw new IllegalStateException("cannot build a READY payload of " + minBytes + " bytes");
  }

  private static String fatSkillsReadyPayload(int skillCount) {
    List<DaemonSkillDescriptor> skills = new ArrayList<>(skillCount);
    for (int index = 0; index < skillCount; index++) {
      skills.add(
          new DaemonSkillDescriptor(
              FAT_SOURCE_ID,
              1,
              "fat-skill-" + index,
              "Fat skill " + index + " description.",
              "/home/dev/skills/fat-" + index,
              REVISION));
    }
    return CAPABILITIES_CODEC.encode(
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
            1,
            List.of(new DaemonSkillSourceSnapshot(FAT_SOURCE_ID, 1, REVISION, skills, List.of()))));
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
