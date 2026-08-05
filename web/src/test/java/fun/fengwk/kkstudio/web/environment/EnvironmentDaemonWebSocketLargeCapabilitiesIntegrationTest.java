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

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real {@link WebEnvironment#RANDOM_PORT} end-to-end proof that the Daemon WebSocket adapter
 * tolerates {@code READY} (skills) frames larger than Tomcat's 8 KiB default text buffer.
 *
 * <p>Without the configured buffer raise, the embedded Tomcat closes the connection with close code
 * 1009 the instant it receives the oversized frame, so the live registry never reaches READY. This
 * test sends a {@code READY} frame carrying a fat skills payload well above the default threshold
 * and asserts that {@link LiveEnvironmentRegistry#isReady(EnvironmentId)} becomes {@code true}
 * within a deadline. Any future change that drops or weakens the buffer initializer will surface
 * here as a registry that never reaches READY together with an observed close code 1009.
 */
class EnvironmentDaemonWebSocketLargeCapabilitiesIntegrationTest extends WebPostgresTestSupport {

  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("2f8fad5b-d9cb-469f-a165-70867728950e");
  private static final String ENVIRONMENT_NAME = "env-large-caps";
  private static final String DAEMON_TOKEN = "test-daemon-token";
  private static final int TOMCAT_DEFAULT_TEXT_BUFFER_BYTES = 8 * 1024;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonSkillsCodec SKILLS_CODEC = new DaemonSkillsCodec();

  @LocalServerPort private int port;

  @Autowired private LiveEnvironmentRegistry registry;
  @Autowired private EnvironmentGatewayProperties properties;

  @MockitoBean private ToolInvocationTransactions transactions;
  @MockitoBean private ResourceStore resourceStore;
  // The Gateway's READY wake is wired by the ExecutionActivation dispatcher slice; suppress here.
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  /**
   * A {@code READY} skills frame comfortably larger than 8 KiB is accepted by the embedded
   * container and the handshake reaches READY; close code 1009 must never be observed.
   */
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

      // The dangerous frame: must NOT trigger close code 1009 because the buffer was raised.
      socket.sendText(readyEnvelope, true).get(5, TimeUnit.SECONDS);
      // poll for up to 10s for the registry to mark READY
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
        properties.requireMaxMessageBytes() > TOMCAT_DEFAULT_TEXT_BUFFER_BYTES,
        "premise: gateway buffer limit must be raised above the 8 KiB default");
  }

  private static void closeQuietly(WebSocket socket) {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // Server may have closed already; the test only cares about whether the handshake
      // completed without close code 1009, which is asserted above.
    }
  }

  private boolean awaitReady() throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadlineNanos) {
      if (registry.isReady(ENVIRONMENT_ID)) {
        return true;
      }
      Thread.sleep(50);
    }
    return registry.isReady(ENVIRONMENT_ID);
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
    return SKILLS_CODEC.encode(List.of(fat));
  }

  private static DaemonEnvelope helloEnvelope(long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.HELLO,
        ENVIRONMENT_ID,
        ENVIRONMENT_NAME,
        null,
        sequence,
        "{"
            + "\"daemonId\":\"daemon-large-caps\","
            + "\"protocolVersion\":2,"
            + "\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
            + "\","
            + "\"gatewayToken\":\""
            + DAEMON_TOKEN
            + "\"}");
  }

  private static DaemonEnvelope readyEnvelope(String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION_2,
        DaemonMessageType.READY,
        ENVIRONMENT_ID,
        ENVIRONMENT_NAME,
        null,
        1,
        payloadJson);
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
