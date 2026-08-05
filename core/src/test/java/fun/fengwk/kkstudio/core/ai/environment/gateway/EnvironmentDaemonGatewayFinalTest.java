package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
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
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway is connection/protocol transport only. Durable claim/lease/terminal lives in Harness
 * Runtime. Routing uses the canonical EnvironmentId bound at HELLO; the display name never routes.
 */
class EnvironmentDaemonGatewayFinalTest {
  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final EnvironmentId OTHER_ENVIRONMENT_ID =
      new EnvironmentId("1f8fad5b-d9cb-469f-a165-70867728950e");
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 9001L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final String GATEWAY_TOKEN = "gateway-test-token";
  private static final List<DaemonSkillDescriptor> ADVERTISED_SKILLS =
      List.of(
          new DaemonSkillDescriptor("dev", "Developer rules"),
          new DaemonSkillDescriptor("ops", "Operations rules"));

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonSkillsCodec skillsCodec = new DaemonSkillsCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();

  @Test
  void readyNotifiesHandlerAndInvokeSendsProtocolThenMapsCompletion() {
    List<EnvironmentId> ready = new ArrayList<>();
    Fixture fixture = fixture(ready::add);
    FakeConnection connection = fixture.connectReady("connection-a");

    assertEquals(List.of(ENVIRONMENT_ID), ready);
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));

    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope invoke = connection.envelopes().get(1);
    assertEquals(ENVIRONMENT_ID, invoke.environmentId());
    assertEquals(ENVIRONMENT_NAME, invoke.environmentName());
    assertEquals(Long.toString(INVOCATION_ID), invoke.invocationId());
    assertTrue(invoke.payloadJson().contains("\"toolName\":\"read\""));

    handle.cancel();
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
    assertEquals("provider-call", listener.completed.toolCallId());
  }

  @Test
  void partialIsForwardedAndResourcePartialIsRejected() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-partial");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(2, resultPayload("chunk")));
    assertEquals("chunk", ((TextToolContent) listener.partial.contents().get(0)).text());

    String resourcePayload =
        resultCodec.encodeCompleted(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new BinaryToolContent("text/plain", new byte[] {1, 2})),
                false,
                "{}",
                false),
            inlineResourceStore(new byte[] {1, 2}));
    fixture.gateway.receive(connection.connectionId(), partial(4, resourcePayload));
    assertTrue(connection.closed);
  }

  @Test
  void completedResourceIsMappedToTransientBinaryWithoutDurablePersistence() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-binary");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);

    String payload =
        resultCodec.encodeCompleted(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new BinaryToolContent("text/plain", new byte[] {7, 8})),
                false,
                "{}",
                false),
            inlineResourceStore(new byte[] {7, 8}));
    fixture.gateway.receive(connection.connectionId(), completed(2, payload));
    BinaryToolContent binary = (BinaryToolContent) listener.completed.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertEquals(2, binary.content().length);
    // No durable store is touched by the gateway; the result stays an in-memory Binary.
    assertEquals(1, listener.completed.contents().size());
  }

  @Test
  void cancelledMapsToRemoteCancelledException() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_ID,
            DaemonMessageType.CANCELLED,
            Long.toString(INVOCATION_ID),
            2,
            "{\"reason\":\"stop\"}"));
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
                ENVIRONMENT_ID, request(fixture.descriptor), new RecordingListener()));
  }

  @Test
  void invalidInvokePayloadDoesNotReserveEnvironmentSlot() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-invalid-payload");

    assertThrows(
        ArithmeticException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_ID,
                request(fixture.descriptor, Duration.ofSeconds(Long.MAX_VALUE)),
                new RecordingListener()));

    ToolExecutionHandle handle =
        fixture.gateway.invoke(
            ENVIRONMENT_ID, request(fixture.descriptor), new RecordingListener());
    assertNotNull(handle);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
  }

  @Test
  void disconnectNotifiesActiveRemoteAsUncertain() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-drop");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);
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
            environmentId -> {
              // Re-enter gateway monitor (loadSkill) without re-dispatching READY. Deadlocks if
              // READY still held ConnectionState or gateway locks incorrectly.
              try {
                gatewayRef
                    .get()
                    .loadSkill(environmentId, "missing-skill", Duration.ofMillis(20))
                    .get();
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
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);
    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
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
        () -> fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener));
    assertNull(listener.error);
    assertNull(listener.completed);
  }

  @Test
  void uncertainInvokeSendUnregistersReadyEnvironmentAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-cleanup");
    assertTrue(fixture.environmentRegistry.isReady(ENVIRONMENT_ID));
    connection.failNextSend = true;
    assertThrows(
        RemoteToolSendUncertainException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_ID, request(fixture.descriptor), new RecordingListener()));
    assertTrue(connection.closed);
    assertFalse(fixture.environmentRegistry.isReady(ENVIRONMENT_ID));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_ID).isEmpty());
  }

  @Test
  void cancelIsIdempotentAndSkippedAfterTerminalComplete() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-idempotent");
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listener);
    handle.cancel();
    handle.cancel();
    long cancelCount =
        connection.envelopes().stream()
            .filter(envelope -> envelope.messageType() == DaemonMessageType.CANCEL)
            .count();
    assertEquals(1, cancelCount);

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    int envelopesAfterComplete = connection.envelopes().size();
    handle.cancel();
    assertEquals(envelopesAfterComplete, connection.envelopes().size());
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void readyRegistersAdvertisedSkillsAndStaticTools() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skills");
    assertTrue(fixture.environmentRegistry.isReady(ENVIRONMENT_ID));
    var registered = fixture.environmentRegistry.find(ENVIRONMENT_ID).orElseThrow();
    assertEquals(ENVIRONMENT_ID, registered.id());
    assertEquals(ENVIRONMENT_NAME, registered.name());
    assertEquals(ADVERTISED_SKILLS, registered.skills());
    // Tools come from the static EnvironmentToolCatalog; wire READY does not advertise them.
    assertEquals(EnvironmentToolCatalog.descriptors(), registered.tools());
  }

  @Test
  void helloV1ProtocolIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-v1");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithVersion("1", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_ID).isEmpty());
  }

  @Test
  void helloCatalogVersionMismatchIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-catalog-mismatch");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithCatalogVersion("2", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_ID).isEmpty());
  }

  @Test
  void sameIdConcurrentBindIsRejectedWithoutDisplacingFirstConnection() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, ENVIRONMENT_ID, "renamed"));
    assertTrue(second.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(second.envelopes()));
    assertEquals(
        ENVIRONMENT_NAME, fixture.environmentRegistry.find(ENVIRONMENT_ID).orElseThrow().name());
    assertEquals(
        LiveEnvironmentStatus.READY,
        fixture.environmentRegistry.find(ENVIRONMENT_ID).orElseThrow().status());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(first.envelopes()));
  }

  @Test
  void sameIdReconnectWithChangedNameIsAcceptedAfterClose() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    fixture.gateway.close(first.connectionId());

    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, ENVIRONMENT_ID, "renamed"));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    fixture.gateway.receive(second.connectionId(), ready(1, ENVIRONMENT_ID, "renamed"));
    assertEquals("renamed", fixture.environmentRegistry.find(ENVIRONMENT_ID).orElseThrow().name());
    assertTrue(fixture.environmentRegistry.isReady(ENVIRONMENT_ID));
  }

  @Test
  void wrongIdOnBoundConnectionIsRejectedWithoutRerouting() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-wrong-id");
    // A bound connection must never accept envelopes scoped to another environment id.
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(OTHER_ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, 2, "{}"));
    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_ID).isEmpty());
    assertTrue(fixture.environmentRegistry.find(OTHER_ENVIRONMENT_ID).isEmpty());
  }

  @Test
  void nameMismatchWithinConnectionProtocolFailsWithoutChangingRoute() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-name-mismatch");
    // Same canonical id with a different display name is a protocol-consistency failure: the
    // connection is closed and the route is never re-keyed to the presented name.
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, 2, "{}", "renamed"));
    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_ID).isEmpty());
    // The id is immediately re-bindable with a fresh connection.
    FakeConnection rebind = new FakeConnection("connection-rebind");
    fixture.gateway.open(rebind);
    fixture.gateway.receive(rebind.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(rebind.envelopes()));
  }

  @Test
  void sameDisplayNameOnTwoIdsRoutesInvokeAndSkillLoadByExactId() {
    Fixture fixture = fixture();
    FakeConnection connectionA = fixture.connectReady("connection-a");
    FakeConnection connectionB = new FakeConnection("connection-b");
    fixture.gateway.open(connectionB);
    // Both environments advertise the same display name; only the canonical id routes.
    fixture.gateway.receive(
        connectionB.connectionId(), hello(0, OTHER_ENVIRONMENT_ID, ENVIRONMENT_NAME));
    fixture.gateway.receive(
        connectionB.connectionId(), ready(1, OTHER_ENVIRONMENT_ID, ENVIRONMENT_NAME));

    RecordingListener listenerA = new RecordingListener();
    RecordingListener listenerB = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_ID, request(fixture.descriptor), listenerA);
    fixture.gateway.invoke(OTHER_ENVIRONMENT_ID, request(fixture.descriptor), listenerB);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionA.envelopes()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionB.envelopes()));

    fixture.gateway.loadSkill(ENVIRONMENT_ID, "dev", Duration.ofMillis(100));
    fixture.gateway.loadSkill(OTHER_ENVIRONMENT_ID, "dev", Duration.ofMillis(100));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LOAD_SKILL),
        messageTypes(connectionA.envelopes()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LOAD_SKILL),
        messageTypes(connectionB.envelopes()));
  }

  @Test
  void heartbeatKeepsEnvironmentAliveAfterReady() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-heartbeat");
    assertTrue(fixture.environmentRegistry.isReady(ENVIRONMENT_ID));
    Instant heartbeatAt = NOW.plusSeconds(10);
    fixture.now.set(heartbeatAt);
    fixture.gateway.receive(connection.connectionId(), heartbeat(2));
    assertEquals(
        heartbeatAt, fixture.environmentRegistry.find(ENVIRONMENT_ID).orElseThrow().lastSeenAt());
  }

  private ToolExecutionRequest request(ToolDescriptor descriptor) {
    return request(descriptor, Duration.ofSeconds(30));
  }

  private ToolExecutionRequest request(ToolDescriptor descriptor, Duration timeout) {
    return new ToolExecutionRequest(
        descriptor,
        new ToolCall("provider-call", descriptor.name(), "{\"path\":\"README.md\"}"),
        timeout,
        new ToolExecutionContext(INVOCATION_ID, 7001L));
  }

  private String resultPayload(String text) {
    return resultCodec.encodeCompleted(
        new ToolResult(
            Long.toString(INVOCATION_ID), List.of(new TextToolContent(text)), false, "{}", false),
        inlineResourceStore(new byte[0]));
  }

  /** Minimal daemon-side resource store: stores/reads only the in-memory test bytes. */
  private static DaemonResourceStore inlineResourceStore(byte[] bytes) {
    return new DaemonResourceStore() {
      @Override
      public ResourceRef store(byte[] storedBytes, String mediaType) throws IOException {
        return new ResourceRef(
            "file:///inline", mediaType, null, (long) storedBytes.length, sha256(storedBytes));
      }

      @Override
      public byte[] read(ResourceRef ref) throws IOException {
        return bytes;
      }
    };
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private String hello(long sequence) {
    return hello(sequence, ENVIRONMENT_ID, ENVIRONMENT_NAME);
  }

  private String hello(long sequence, EnvironmentId environmentId, String environmentName) {
    return envelope(
        environmentId,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":2,\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}",
        environmentName);
  }

  private String helloWithVersion(String protocolVersion, long sequence) {
    return envelope(
        ENVIRONMENT_ID,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":"
            + protocolVersion
            + ",\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithCatalogVersion(String catalogVersion, long sequence) {
    return envelope(
        ENVIRONMENT_ID,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":2,\"toolCatalogVersion\":\""
            + catalogVersion
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String ready(long sequence) {
    return ready(sequence, ENVIRONMENT_ID, ENVIRONMENT_NAME);
  }

  private String ready(long sequence, EnvironmentId environmentId, String environmentName) {
    return envelope(
        environmentId,
        DaemonMessageType.READY,
        null,
        sequence,
        skillsCodec.encode(ADVERTISED_SKILLS),
        environmentName);
  }

  private String heartbeat(long sequence) {
    return envelope(ENVIRONMENT_ID, DaemonMessageType.HEARTBEAT, null, sequence, "{}");
  }

  private String partial(long sequence, String payload) {
    return envelope(
        ENVIRONMENT_ID, DaemonMessageType.PARTIAL, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String completed(long sequence, String payload) {
    return envelope(
        ENVIRONMENT_ID,
        DaemonMessageType.COMPLETED,
        Long.toString(INVOCATION_ID),
        sequence,
        payload);
  }

  private String envelope(
      EnvironmentId environmentId,
      DaemonMessageType messageType,
      String invocationId,
      long sequence,
      String payload) {
    return envelope(environmentId, messageType, invocationId, sequence, payload, ENVIRONMENT_NAME);
  }

  private String envelope(
      EnvironmentId environmentId,
      DaemonMessageType messageType,
      String invocationId,
      long sequence,
      String payload,
      String environmentName) {
    return envelopeCodec.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_2,
            messageType,
            environmentId,
            environmentName,
            invocationId,
            sequence,
            payload));
  }

  private static List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private Fixture fixture() {
    return fixture(environmentId -> {});
  }

  private Fixture fixture(EnvironmentReadyListener readyListener) {
    return new Fixture(descriptor(), readyListener);
  }

  private static ToolDescriptor descriptor() {
    return EnvironmentToolCatalog.require("read");
  }

  private final class Fixture {
    final LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry();
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    final EnvironmentDaemonGateway gateway;
    final ToolDescriptor descriptor;

    private Fixture(ToolDescriptor descriptor, EnvironmentReadyListener readyListener) {
      this.descriptor = descriptor;
      EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
      gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
      gateway =
          new EnvironmentDaemonGateway(
              environmentRegistry,
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

    FakeConnection connectReady(String connectionId) {
      FakeConnection connection = new FakeConnection(connectionId);
      gateway.open(connection);
      gateway.receive(connection.connectionId(), hello(0));
      gateway.receive(connection.connectionId(), ready(1));
      return connection;
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
