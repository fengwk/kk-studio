package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway is connection/protocol transport only. Durable claim/lease/terminal lives in ToolWorker.
 */
class EnvironmentDaemonGatewayFinalTest {
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 9001L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final String GATEWAY_TOKEN = "gateway-test-token";

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonToolCapabilitiesCodec capabilitiesCodec = new DaemonToolCapabilitiesCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();

  @Test
  void readyNotifiesHandlerAndInvokeSendsProtocolThenMapsCompletion() {
    List<String> ready = new ArrayList<>();
    Fixture fixture = fixture(ready::add);
    FakeConnection connection = fixture.connectReady("connection-a");

    assertEquals(List.of(ENVIRONMENT_NAME), ready);
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));

    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope invoke = connection.envelopes().get(1);
    assertEquals(Long.toString(INVOCATION_ID), invoke.invocationId());
    assertTrue(invoke.payloadJson().contains("\"toolName\":\"read\""));

    handle.cancel();
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));

    fixture.gateway.receive(connection.connectionId(), completed(3, resultPayload("done")));
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
    assertEquals("provider-call", listener.completed.toolCallId());
  }

  @Test
  void partialIsForwardedAndArtifactPartialIsRejected() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-partial");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(3, resultPayload("chunk")));
    assertEquals("chunk", ((TextToolContent) listener.partial.contents().get(0)).text());

    String artifactPayload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new BinaryToolContent("text/plain", new byte[] {1, 2})),
                false,
                "{}",
                false),
            ignored -> new byte[] {1, 2});
    fixture.gateway.receive(connection.connectionId(), partial(4, artifactPayload));
    assertTrue(connection.closed);
  }

  @Test
  void completedInlineBinaryIsMappedWithoutDurablePersistence() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-binary");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);

    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new BinaryToolContent("text/plain", new byte[] {7, 8})),
                false,
                "{}",
                false),
            ignored -> new byte[] {7, 8});
    fixture.gateway.receive(connection.connectionId(), completed(3, payload));
    BinaryToolContent binary = (BinaryToolContent) listener.completed.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertEquals(2, binary.content().length);
  }

  @Test
  void cancelledMapsToRemoteCancelledException() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            DaemonMessageType.CANCELLED, Long.toString(INVOCATION_ID), 3, "{\"reason\":\"stop\"}"));
    assertTrue(listener.error instanceof RemoteToolCancelledException);
    assertEquals("stop", listener.error.getMessage());
  }

  @Test
  void invokeWhenOfflineThrowsUnavailable() {
    Fixture fixture = fixture();
    assertThrows(
        RemoteToolUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener()));
  }

  @Test
  void disconnectNotifiesActiveRemoteAsUncertain() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-drop");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    fixture.gateway.close(connection.connectionId());
    assertTrue(listener.error instanceof RemoteToolSendUncertainException);
    assertNull(listener.completed);
  }

  @Test
  void readyHandlerAndTerminalCallbacksRunOutsideTransportLocks() {
    AtomicBoolean readySawLockFree = new AtomicBoolean();
    AtomicBoolean completeSawLockFree = new AtomicBoolean();
    AtomicReference<EnvironmentDaemonGateway> gatewayRef = new AtomicReference<>();
    Fixture fixture =
        fixture(
            env -> {
              // Re-enter gateway monitor (loadSkill) without re-dispatching READY. Deadlocks if
              // READY still held ConnectionState or gateway locks incorrectly.
              try {
                gatewayRef.get().loadSkill(env, "missing-skill", Duration.ofMillis(20)).get();
              } catch (Exception ignored) {
                // Offline/timeout paths still exercise locked sections.
              }
              readySawLockFree.set(true);
            });
    gatewayRef.set(fixture.gateway);
    FakeConnection connection = fixture.connectReady("connection-lock-free");
    assertTrue(readySawLockFree.get());

    RecordingListener listener =
        new RecordingListener() {
          @Override
          public void onComplete(ToolResult result) {
            super.onComplete(result);
            // Re-enter close path while completing; must not run under ConnectionState lock.
            fixture.gateway.close(connection.connectionId());
            completeSawLockFree.set(true);
          }
        };
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    fixture.gateway.receive(connection.connectionId(), completed(3, resultPayload("done")));
    assertTrue(completeSawLockFree.get());
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void uncertainInvokeSendDoesNotDoubleNotifyListener() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-send");
    connection.failNextSend = true;
    RecordingListener listener = new RecordingListener();
    assertThrows(
        RemoteToolSendUncertainException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener));
    assertNull(listener.error);
    assertNull(listener.completed);
  }

  @Test
  void uncertainInvokeSendUnregistersReadyEnvironmentAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-cleanup");
    assertTrue(fixture.environmentRegistry.isReady(ENVIRONMENT_NAME));
    connection.failNextSend = true;
    assertThrows(
        RemoteToolSendUncertainException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener()));
    assertTrue(connection.closed);
    assertFalse(fixture.environmentRegistry.isReady(ENVIRONMENT_NAME));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
  }

  @Test
  void cancelIsIdempotentAndSkippedAfterTerminalComplete() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-idempotent");
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    handle.cancel();
    handle.cancel();
    long cancelCount =
        connection.envelopes().stream()
            .filter(envelope -> envelope.messageType() == DaemonMessageType.CANCEL)
            .count();
    assertEquals(1, cancelCount);

    fixture.gateway.receive(connection.connectionId(), completed(3, resultPayload("done")));
    int envelopesAfterComplete = connection.envelopes().size();
    handle.cancel();
    assertEquals(envelopesAfterComplete, connection.envelopes().size());
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  private ToolExecutionRequest request(ToolDescriptor descriptor) {
    return new ToolExecutionRequest(
        descriptor,
        new ToolCall("provider-call", descriptor.name(), "{}"),
        Duration.ofSeconds(30),
        new ToolExecutionContext(INVOCATION_ID, 7001L));
  }

  private String resultPayload(String text) {
    return resultCodec.encodeResult(
        new ToolResult(
            Long.toString(INVOCATION_ID), List.of(new TextToolContent(text)), false, "{}", false),
        ignored -> new byte[0]);
  }

  private String hello(long sequence) {
    return envelope(
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":1,\"gatewayToken\":\"" + GATEWAY_TOKEN + "\"}");
  }

  private String capabilities(long sequence, String payload) {
    return envelope(DaemonMessageType.CAPABILITIES, null, sequence, payload);
  }

  private String ready(long sequence) {
    return envelope(DaemonMessageType.READY, null, sequence, "{\"pull\":true}");
  }

  private String partial(long sequence, String payload) {
    return envelope(DaemonMessageType.PARTIAL, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String completed(long sequence, String payload) {
    return envelope(DaemonMessageType.COMPLETED, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String envelope(
      DaemonMessageType messageType, String invocationId, long sequence, String payload) {
    return envelopeCodec.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            messageType,
            ENVIRONMENT_NAME,
            invocationId,
            sequence,
            payload));
  }

  private static List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private Fixture fixture() {
    return fixture(environmentName -> {});
  }

  private Fixture fixture(EnvironmentReadyListener readyListener) {
    return new Fixture(descriptor(), readyListener);
  }

  private static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "read",
        "1",
        "read file",
        "read",
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private final class Fixture {
    private final LiveEnvironmentRegistry environmentRegistry =
        new LiveEnvironmentRegistry(capabilitiesCodec);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final EnvironmentDaemonGateway gateway;
    private final ToolDescriptor descriptor;

    private Fixture(ToolDescriptor descriptor, EnvironmentReadyListener readyListener) {
      this.descriptor = descriptor;
      EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
      gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
      gateway =
          new EnvironmentDaemonGateway(
              environmentRegistry,
              capabilitiesCodec,
              gatewayProperties,
              new Clock() {
                @Override
                public ZoneId getZone() {
                  return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                  return this;
                }

                @Override
                public Instant instant() {
                  return now.get();
                }
              },
              readyListener);
    }

    private FakeConnection connectReady(String connectionId) {
      FakeConnection connection = new FakeConnection(connectionId);
      gateway.open(connection);
      gateway.receive(connection.connectionId(), hello(0));
      gateway.receive(connection.connectionId(), capabilities(1, capabilitiesJson()));
      gateway.receive(connection.connectionId(), ready(2));
      return connection;
    }

    private String capabilitiesJson() {
      return capabilitiesCodec.encode(
          new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(descriptor), List.of()));
    }
  }

  private static class RecordingListener implements ToolExecutionListener {
    private ToolResult partial;
    private ToolResult completed;
    private Throwable error;

    @Override
    public void onPartial(ToolResult partial) {
      this.partial = partial;
    }

    @Override
    public void onComplete(ToolResult result) {
      completed = result;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }
  }

  private static final class FakeConnection implements EnvironmentDaemonConnection {
    private final String connectionId;
    private final List<DaemonEnvelope> envelopes = new ArrayList<>();
    private boolean open = true;
    private boolean closed;
    private boolean failNextSend;

    private FakeConnection(String connectionId) {
      this.connectionId = connectionId;
    }

    @Override
    public String connectionId() {
      return connectionId;
    }

    @Override
    public void sendText(String text) {
      if (!open) {
        throw new IllegalStateException("closed");
      }
      if (failNextSend) {
        failNextSend = false;
        throw new IllegalStateException("send failed");
      }
      envelopes.add(new DaemonEnvelopeCodec().decode(text));
    }

    @Override
    public void close() {
      open = false;
      closed = true;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    private List<DaemonEnvelope> envelopes() {
      return envelopes;
    }
  }
}
