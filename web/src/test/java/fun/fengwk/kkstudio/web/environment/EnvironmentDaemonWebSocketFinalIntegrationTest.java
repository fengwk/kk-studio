package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * End-to-end Daemon v1 WebSocket contract against the final Tool transaction port.
 *
 * <p>Uses a minimal JDK WebSocket fake daemon client so the web module does not depend on the
 * harness-daemon module.
 */
class EnvironmentDaemonWebSocketFinalIntegrationTest extends WebPostgresTestSupport {
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 99L;
  private static final String DAEMON_TOKEN = "test-daemon-token";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonToolCapabilitiesCodec CAPABILITIES_CODEC =
      new DaemonToolCapabilitiesCodec();
  private static final DaemonToolResultCodec RESULT_CODEC = new DaemonToolResultCodec();

  @LocalServerPort private int port;

  @MockitoBean private ToolInvocationTransactions transactions;
  @MockitoBean private ArtifactStore artifactStore;
  // The Gateway's READY wake is wired by the durable-target dispatcher slice; suppress here.
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;
  @Autowired private LiveEnvironmentRegistry environmentRegistry;
  @Autowired private ToolWorker toolWorker;
  private final AtomicReference<ToolResult> completedResult = new AtomicReference<>();

  @Test
  void daemonHandshakeDispatchAndCompletionReachDurableGateway() throws Exception {
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    try (FakeDaemonClient daemon = FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_NAME)) {
      daemon.handshake(descriptor);
      awaitEnvironmentReady();
      dispatchTool();
      daemon.awaitInvokeAndCompleteText("daemon completed");

      verify(transactions, timeout(10_000)).claim(eq(INVOCATION_ID), anyString(), any(), any());
      verify(transactions, timeout(10_000)).completeSuccess(any(), any(), any(), any());
      awaitCompletedResult();
      assertEquals("provider-call", completedResult.get().toolCallId());
      assertEquals(
          "daemon completed", ((TextToolContent) completedResult.get().contents().get(0)).text());
    }
  }

  @Test
  void daemonArtifactCompletionIsPersistedByGateway() throws Exception {
    byte[] bytes = new byte[] {1, 2, 3};
    ArtifactRef global =
        new ArtifactRef("global-artifact", "application/octet-stream", bytes.length);
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    when(artifactStore.save(anyString(), anyString(), any())).thenReturn(global);
    try (FakeDaemonClient daemon = FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_NAME)) {
      daemon.handshake(descriptor);
      awaitEnvironmentReady();
      dispatchTool();
      daemon.awaitInvokeAndCompleteBinary("application/octet-stream", bytes);

      ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
      verify(artifactStore, timeout(10_000))
          .save(eq("application/octet-stream"), eq("identity"), contentCaptor.capture());
      assertArrayEquals(bytes, contentCaptor.getValue());
      verify(transactions, timeout(10_000)).completeSuccess(any(), any(), any(), any());
      ArtifactToolContent artifact = (ArtifactToolContent) completedResult.get().contents().get(0);
      assertEquals(global, artifact.artifact());
    }
  }

  @Test
  void daemonFailureUsesFencedFailureTransition() throws Exception {
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    try (FakeDaemonClient daemon = FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_NAME)) {
      daemon.handshake(descriptor);
      awaitEnvironmentReady();
      dispatchTool();
      daemon.awaitInvokeAndFail("daemon failed");

      ArgumentCaptor<ToolInvocationError> errorCaptor =
          ArgumentCaptor.forClass(ToolInvocationError.class);
      verify(transactions, timeout(10_000))
          .completeFailure(any(), errorCaptor.capture(), any(), any());
      assertEquals("EXECUTION_FAILED", errorCaptor.getValue().kind());
      assertEquals("daemon failed", errorCaptor.getValue().message());
    }
  }

  private void configureClaimedInvocation(ToolDescriptor descriptor) {
    when(transactions.claim(eq(INVOCATION_ID), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              String token = invocation.getArgument(1);
              return Optional.of(
                  new ClaimedToolInvocation(
                      running(descriptor, token), InvocationStatus.QUEUED, false));
            });
    when(transactions.completeSuccess(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<ToolResult> resultSupplier = invocation.getArgument(1);
              completedResult.set(resultSupplier.get());
              return ToolInvocationUpdateOutcome.APPLIED;
            });
    when(transactions.completeFailure(any(), any(), any(), any()))
        .thenReturn(ToolInvocationUpdateOutcome.APPLIED);
    when(transactions.renew(any(), any(), any())).thenReturn(ToolInvocationUpdateOutcome.APPLIED);
    when(transactions.recordActivity(any(), any(), any()))
        .thenReturn(ToolInvocationUpdateOutcome.APPLIED);
  }

  private void dispatchTool() {
    assertTrue(toolWorker.dispatch(INVOCATION_ID));
  }

  private void awaitEnvironmentReady() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!environmentRegistry.isReady(ENVIRONMENT_NAME) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(
        environmentRegistry.isReady(ENVIRONMENT_NAME),
        "environment did not reach READY before direct ToolWorker dispatch");
  }

  private void awaitCompletedResult() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (completedResult.get() == null && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    if (completedResult.get() == null) {
      throw new AssertionError("timed out waiting for completed tool result");
    }
  }

  private URI endpointUri() {
    return URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH);
  }

  private static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "echo",
        "1",
        "echo",
        "echo",
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(10));
  }

  private static ToolInvocation running(ToolDescriptor descriptor, String workerToken) {
    Instant now = Instant.now();
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        0,
        "provider-call",
        descriptor,
        "{}",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        1L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(workerToken, now.plusSeconds(15)),
        now.plusSeconds(30),
        now,
        null,
        null,
        null,
        now.minusMillis(1),
        now,
        null,
        ToolPermissionState.ALLOWED,
        false);
  }

  /**
   * Minimal daemon-side peer for the v1 WebSocket protocol used by EnvironmentDaemonGateway.
   *
   * <p>Only implements HELLO/CAPABILITIES/READY plus one INVOKE response path needed by this test.
   */
  private static final class FakeDaemonClient implements AutoCloseable {
    private final String environmentName;
    private final WebSocket socket;
    private final FrameListener listener;
    private final AtomicLong outboundSequence = new AtomicLong();

    private FakeDaemonClient(String environmentName, WebSocket socket, FrameListener listener) {
      this.environmentName = environmentName;
      this.socket = socket;
      this.listener = listener;
    }

    static FakeDaemonClient connect(URI endpoint, String environmentName) throws Exception {
      FrameListener listener = new FrameListener();
      WebSocket socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .buildAsync(endpoint, listener)
              .get(10, TimeUnit.SECONDS);
      return new FakeDaemonClient(environmentName, socket, listener);
    }

    void handshake(ToolDescriptor descriptor) throws Exception {
      send(
          DaemonMessageType.HELLO,
          null,
          "{"
              + "\"daemonId\":\"websocket-integration-daemon\","
              + "\"protocolVersion\":1,"
              + "\"gatewayToken\":\""
              + DAEMON_TOKEN
              + "\"}");
      String welcome = listener.awaitText(5_000);
      assertEquals("WELCOME", messageType(welcome));

      String capabilities =
          CAPABILITIES_CODEC.encode(new DaemonToolCapabilities(List.of(descriptor), List.of()));
      send(DaemonMessageType.CAPABILITIES, null, capabilities);
      send(DaemonMessageType.READY, null, "{\"pull\":true}");
    }

    void awaitInvokeAndCompleteText(String text) throws Exception {
      DaemonEnvelope invoke = awaitInvoke();
      send(DaemonMessageType.STARTED, invoke.invocationId(), "{}");
      String payload =
          RESULT_CODEC.encodeResult(
              new ToolResult(
                  invoke.invocationId(), List.of(new TextToolContent(text)), false, "{}", false),
              ignored -> new byte[0]);
      send(DaemonMessageType.COMPLETED, invoke.invocationId(), payload);
    }

    void awaitInvokeAndCompleteBinary(String mediaType, byte[] bytes) throws Exception {
      DaemonEnvelope invoke = awaitInvoke();
      send(DaemonMessageType.STARTED, invoke.invocationId(), "{}");
      String payload =
          RESULT_CODEC.encodeResult(
              new ToolResult(
                  invoke.invocationId(),
                  List.of(new BinaryToolContent(mediaType, bytes)),
                  false,
                  "{}",
                  false),
              ignored -> bytes);
      send(DaemonMessageType.COMPLETED, invoke.invocationId(), payload);
    }

    void awaitInvokeAndFail(String message) throws Exception {
      DaemonEnvelope invoke = awaitInvoke();
      send(DaemonMessageType.STARTED, invoke.invocationId(), "{}");
      send(
          DaemonMessageType.FAILED,
          invoke.invocationId(),
          "{\"message\":" + OBJECT_MAPPER.writeValueAsString(message) + "}");
    }

    private DaemonEnvelope awaitInvoke() throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        String raw = listener.awaitText(Math.max(1, remainingMillis(deadline)));
        DaemonEnvelope envelope = ENVELOPE_CODEC.decode(raw);
        if (envelope.messageType() == DaemonMessageType.INVOKE) {
          return envelope;
        }
      }
      throw new AssertionError("timed out waiting for INVOKE");
    }

    private void send(DaemonMessageType type, String invocationId, String payloadJson)
        throws Exception {
      String encoded =
          ENVELOPE_CODEC.encode(
              new DaemonEnvelope(
                  DaemonProtocol.VERSION_1,
                  type,
                  environmentName,
                  invocationId,
                  outboundSequence.getAndIncrement(),
                  payloadJson));
      socket.sendText(encoded, true).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // Server may already have closed after terminal protocol handling.
      }
    }

    private static String messageType(String envelopeJson) throws Exception {
      JsonNode root = OBJECT_MAPPER.readTree(envelopeJson);
      JsonNode node = root.get("messageType");
      if (node == null || !node.isTextual()) {
        throw new AssertionError("envelope must carry messageType: " + envelopeJson);
      }
      return node.asText();
    }

    private static long remainingMillis(long deadlineNanos) {
      return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }
  }

  private static final class FrameListener implements WebSocket.Listener {
    private final StringBuilder currentMessage = new StringBuilder();
    private final List<String> inbox = new ArrayList<>();
    private final Object lock = new Object();

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
        synchronized (lock) {
          inbox.add(complete);
          lock.notifyAll();
        }
      }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }

    String awaitText(long timeoutMillis) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      synchronized (lock) {
        while (inbox.isEmpty()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new AssertionError("timed out waiting for websocket text frame");
          }
          lock.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
        return inbox.remove(0);
      }
    }
  }
}
