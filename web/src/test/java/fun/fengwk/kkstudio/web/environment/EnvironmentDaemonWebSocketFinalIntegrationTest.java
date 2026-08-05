package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * End-to-end Daemon v2 WebSocket contract against the final Tool transaction port.
 *
 * <p>Uses a minimal JDK WebSocket fake daemon client so the web module does not depend on the
 * harness-daemon module. The gateway maps COMPLETED resource segments to transient binary content;
 * durable externalization happens in the ToolWorker via {@link ResourceStore}.
 */
class EnvironmentDaemonWebSocketFinalIntegrationTest extends WebPostgresTestSupport {
  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("3f8fad5b-d9cb-469f-a165-70867728950e");
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 99L;
  private static final String DAEMON_TOKEN = "test-daemon-token";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final DaemonEnvelopeCodec ENVELOPE_CODEC = new DaemonEnvelopeCodec();
  private static final DaemonSkillsCodec SKILLS_CODEC = new DaemonSkillsCodec();
  private static final DaemonToolResultCodec RESULT_CODEC = new DaemonToolResultCodec();

  @LocalServerPort private int port;

  @MockitoBean private ToolInvocationTransactions transactions;
  @MockitoBean private ResourceStore resourceStore;
  // The Gateway's READY wake is wired by the ExecutionActivation dispatcher slice; suppress here.
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;
  @Autowired private LiveEnvironmentRegistry environmentRegistry;
  @Autowired private ToolWorker toolWorker;
  private final AtomicReference<ToolResult> completedResult = new AtomicReference<>();

  @Test
  void daemonHandshakeDispatchAndCompletionReachDurableGateway() throws Exception {
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    try (FakeDaemonClient daemon =
        FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_ID, ENVIRONMENT_NAME)) {
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
  void daemonBinaryCompletionIsExternalizedByWorkerResourceStore() throws Exception {
    byte[] bytes = new byte[] {1, 2, 3};
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    when(resourceStore.put(anyString(), isNull(), any(byte[].class)))
        .thenAnswer(
            invocation -> {
              String mediaType = invocation.getArgument(0);
              byte[] content = invocation.getArgument(2);
              return new ResourceRef(
                  "file:///tmp/gateway-integration.bin",
                  mediaType,
                  null,
                  (long) content.length,
                  sha256(content));
            });
    try (FakeDaemonClient daemon =
        FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_ID, ENVIRONMENT_NAME)) {
      daemon.handshake(descriptor);
      awaitEnvironmentReady();
      dispatchTool();
      daemon.awaitInvokeAndCompleteBinary("application/octet-stream", bytes);

      ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
      verify(resourceStore, timeout(10_000))
          .put(eq("application/octet-stream"), isNull(), contentCaptor.capture());
      assertArrayEquals(bytes, contentCaptor.getValue());
      verify(transactions, timeout(10_000)).completeSuccess(any(), any(), any(), any());
      awaitCompletedResult();
      // The gateway hands the worker transient BinaryToolContent; the worker externalizes it to a
      // stable resource reference and persists the preview + reference result.
      TextToolContent preview = (TextToolContent) completedResult.get().contents().get(0);
      assertEquals("[binary output stored as resource]", preview.text());
      ResourceToolContent resource = (ResourceToolContent) completedResult.get().contents().get(1);
      assertEquals("application/octet-stream", resource.resource().mediaType());
      assertEquals(3L, resource.resource().size());
    }
  }

  @Test
  void daemonFailureUsesFencedFailureTransition() throws Exception {
    ToolDescriptor descriptor = descriptor();
    completedResult.set(null);
    configureClaimedInvocation(descriptor);
    try (FakeDaemonClient daemon =
        FakeDaemonClient.connect(endpointUri(), ENVIRONMENT_ID, ENVIRONMENT_NAME)) {
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
    while (!environmentRegistry.isReady(ENVIRONMENT_ID) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(
        environmentRegistry.isReady(ENVIRONMENT_ID),
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
    return EnvironmentToolCatalog.require("read");
  }

  private static ToolInvocation running(ToolDescriptor descriptor, String workerToken) {
    Instant now = Instant.now();
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        103L,
        0,
        "provider-call",
        descriptor,
        "{\"path\":\"README.md\"}",
        ENVIRONMENT_ID,
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

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /**
   * Minimal daemon-side peer for the v2 WebSocket protocol used by EnvironmentDaemonGateway.
   *
   * <p>Only implements HELLO/WELCOME/READY/HEARTBEAT plus one INVOKE response path needed by this
   * test.
   */
  private static final class FakeDaemonClient implements AutoCloseable {
    private final EnvironmentId environmentId;
    private final String environmentName;
    private final WebSocket socket;
    private final FrameListener listener;
    private final AtomicLong outboundSequence = new AtomicLong();

    private FakeDaemonClient(
        EnvironmentId environmentId,
        String environmentName,
        WebSocket socket,
        FrameListener listener) {
      this.environmentId = environmentId;
      this.environmentName = environmentName;
      this.socket = socket;
      this.listener = listener;
    }

    static FakeDaemonClient connect(
        URI endpoint, EnvironmentId environmentId, String environmentName) throws Exception {
      FrameListener listener = new FrameListener();
      WebSocket socket =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .buildAsync(endpoint, listener)
              .get(10, TimeUnit.SECONDS);
      return new FakeDaemonClient(environmentId, environmentName, socket, listener);
    }

    void handshake(ToolDescriptor descriptor) throws Exception {
      send(
          DaemonMessageType.HELLO,
          null,
          "{"
              + "\"daemonId\":\"websocket-integration-daemon\","
              + "\"protocolVersion\":2,"
              + "\"toolCatalogVersion\":\""
              + EnvironmentToolCatalog.version()
              + "\","
              + "\"gatewayToken\":\""
              + DAEMON_TOKEN
              + "\"}");
      String welcome = listener.awaitText(5_000);
      assertEquals("WELCOME", messageType(welcome));

      String skills =
          SKILLS_CODEC.encode(
              List.of(new DaemonSkillDescriptor(descriptor.name(), descriptor.description())));
      send(DaemonMessageType.READY, null, skills);
      send(DaemonMessageType.HEARTBEAT, null, "{}");
    }

    void awaitInvokeAndCompleteText(String text) throws Exception {
      DaemonEnvelope invoke = awaitInvoke();
      send(DaemonMessageType.STARTED, invoke.invocationId(), "{}");
      String payload =
          RESULT_CODEC.encodeCompleted(
              new ToolResult(
                  invoke.invocationId(), List.of(new TextToolContent(text)), false, "{}", false),
              inlineResourceStore(new byte[0]));
      send(DaemonMessageType.COMPLETED, invoke.invocationId(), payload);
    }

    void awaitInvokeAndCompleteBinary(String mediaType, byte[] bytes) throws Exception {
      DaemonEnvelope invoke = awaitInvoke();
      send(DaemonMessageType.STARTED, invoke.invocationId(), "{}");
      String payload =
          RESULT_CODEC.encodeCompleted(
              new ToolResult(
                  invoke.invocationId(),
                  List.of(new BinaryToolContent(mediaType, bytes)),
                  false,
                  "{}",
                  false),
              inlineResourceStore(bytes));
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
        if (envelope.messageType() == DaemonMessageType.ERROR) {
          throw new AssertionError(
              "gateway rejected fake daemon exchange: " + envelope.payloadJson());
        }
      }
      throw new AssertionError("timed out waiting for INVOKE");
    }

    private void send(DaemonMessageType type, String invocationId, String payloadJson)
        throws Exception {
      String encoded =
          ENVELOPE_CODEC.encode(
              new DaemonEnvelope(
                  DaemonProtocol.VERSION_2,
                  type,
                  environmentId,
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

    /** Daemon-side resource bytes: stores/reads only the in-memory test payload. */
    private static DaemonResourceStore inlineResourceStore(byte[] bytes) {
      return new DaemonResourceStore() {
        @Override
        public ResourceRef store(byte[] storedBytes, String mediaType) throws IOException {
          return new ResourceRef(
              "file:///tmp/fake-daemon.bin",
              mediaType,
              null,
              (long) storedBytes.length,
              sha256(storedBytes));
        }

        @Override
        public byte[] read(ResourceRef ref) throws IOException {
          return bytes;
        }
      };
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
