package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocolException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryEntry;
import fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway 仅承担连接/协议传输职责。durable 的 claim/lease/terminal 由 Harness Runtime 负责。路由使用 HELLO 时确定的
 * canonical EnvironmentId；display name 永不参与路由。
 */
class EnvironmentDaemonGatewayFinalTest extends PostgresSchemaSupport {

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    jdbc.update(
        "insert into environment (id, name, registration_token, version) values (?, 'env-1', 'gateway-test-token', 0)",
        ENVIRONMENT_NAME.value());
    jdbc.update(
        "insert into environment (id, name, registration_token, version) values (?, 'env-2', 'other-token', 0)",
        OTHER_ENVIRONMENT_NAME.value());
  }

  private static final EnvironmentId ENVIRONMENT_NAME =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER_ENVIRONMENT_NAME =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final EnvironmentBinding ENVIRONMENT =
      new EnvironmentBinding(ENVIRONMENT_NAME, ".");
  private static final EnvironmentBinding OTHER_ENVIRONMENT =
      new EnvironmentBinding(OTHER_ENVIRONMENT_NAME, ".");
  private static final UUID INVOCATION_ID = new UUID(0L, 9001L);
  private static final UUID SECOND_INVOCATION_ID = new UUID(0L, 9002L);
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final String GATEWAY_TOKEN = "gateway-test-token";
  private static final List<DaemonSkillDescriptor> ADVERTISED_SKILLS =
      List.of(
          new DaemonSkillDescriptor("dev", "Developer rules"),
          new DaemonSkillDescriptor("ops", "Operations rules"));
  private static final DaemonCapabilities ADVERTISED_CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "Asia/Shanghai", "Linux environment.", "/home/dev"),
          ADVERTISED_SKILLS);

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonCapabilityInvokeCodec invokeCodec = new DaemonCapabilityInvokeCodec();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void readyNotifiesHandlerAndInvokeSendsProtocolThenMapsCompletion() {
    List<EnvironmentId> ready = new ArrayList<>();
    Fixture fixture = fixture(ready::add);
    FakeConnection connection = fixture.connectReady("connection-a");

    assertEquals(List.of(ENVIRONMENT_NAME), ready);
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));

    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope invoke = connection.envelopes().get(1);
    assertEquals(ENVIRONMENT_NAME, invoke.environmentId());
    assertEquals(INVOCATION_ID.toString(), invoke.invocationId());
    DaemonCapabilityInvokeCodec.InvokeRequest decoded = invokeCodec.decode(invoke.payloadJson());
    assertEquals("fs.read", decoded.capabilityId().value());
    assertEquals("1", decoded.capabilityVersion());
    assertEquals(".", decoded.workspacePath());
    assertEquals("{\"path\":\"README.md\"}", decoded.argumentsJson());
    assertEquals(Duration.ofSeconds(30), decoded.timeout());

    handle.cancel();
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    assertEquals("done", ((TextResultContent) listener.completed.contents().get(0)).text());
    assertEquals(INVOCATION_ID.toString(), listener.completed.callId());
  }

  @Test
  void partialIsForwardedAndResourcePartialIsRejected() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-partial");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(2, resultPayload("chunk")));
    assertEquals("chunk", ((TextResultContent) listener.partials.get(0).contents().get(0)).text());

    String resourcePayload =
        resultCodec.encodeCompleted(
            new EnvironmentCapabilityResult(
                INVOCATION_ID.toString(),
                List.of(new BinaryResultContent("text/plain", new byte[] {1, 2})),
                false,
                "{}"),
            inlineResourceStore(new byte[] {1, 2}));
    fixture.gateway.receive(connection.connectionId(), partial(4, resourcePayload));
    assertTrue(connection.closed);
  }

  @Test
  void forwardsMultiplePartialsInOrderAndRejectsLatePartialAfterTerminal() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-partial-order");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(2, resultPayload("partial1")));
    fixture.gateway.receive(connection.connectionId(), partial(3, resultPayload("partial2")));
    fixture.gateway.receive(connection.connectionId(), completed(4, resultPayload("complete")));

    assertEquals(List.of("partial1", "partial2", "complete"), listener.events);
    assertEquals(2, listener.partials.size());
    assertEquals("complete", ((TextResultContent) listener.completed.contents().get(0)).text());
    assertNull(listener.error);

    // terminal 后的迟到 PARTIAL 没有 active invocation：按协议 contract 关闭连接，不得复活回调。
    fixture.gateway.receive(connection.connectionId(), partial(5, resultPayload("late")));

    assertEquals(List.of("partial1", "partial2", "complete"), listener.events);
    assertEquals(2, listener.partials.size());
    assertEquals("complete", ((TextResultContent) listener.completed.contents().get(0)).text());
    assertTrue(connection.closed);
    assertEquals(1, connection.closeAfterFlushCount);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
  }

  @Test
  void completedResourceIsMappedToTransientBinaryWithoutDurablePersistence() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-binary");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    String payload =
        resultCodec.encodeCompleted(
            new EnvironmentCapabilityResult(
                INVOCATION_ID.toString(),
                List.of(new BinaryResultContent("text/plain", new byte[] {7, 8})),
                false,
                "{}"),
            inlineResourceStore(new byte[] {7, 8}));
    fixture.gateway.receive(connection.connectionId(), completed(2, payload));
    BinaryResultContent binary = (BinaryResultContent) listener.completed.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertEquals(2, binary.content().length);
    assertEquals(1, listener.completed.contents().size());
  }

  @Test
  void malformedCompletedKeepsInvocationOwnedUntilConnectionCleanupReportsUncertain() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-malformed-completed");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    // 终态 payload 解码失败时不能先丢失 active ownership，否则连接关闭后调用方永远收不到终态。
    fixture.gateway.receive(connection.connectionId(), completed(2, "{}"));

    assertTrue(connection.closed);
    assertInstanceOf(EnvironmentCapabilitySendUncertainException.class, listener.error);
    assertNull(listener.completed);
  }

  @Test
  void cancelledMapsToRemoteCancelledException() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.CANCELLED,
            INVOCATION_ID.toString(),
            2,
            "{\"reason\":\"stop\"}"));
    assertTrue(listener.error instanceof EnvironmentCapabilityCancelledException);
    assertEquals("stop", listener.error.getMessage());
  }

  @Test
  void failedMapsToRemoteFailedExceptionAndReleasesCapabilitySlot() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-failed");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.FAILED,
            INVOCATION_ID.toString(),
            2,
            "{\"message\":\"daemon failed\"}"));

    assertTrue(listener.error instanceof EnvironmentCapabilityFailedException);
    assertEquals("daemon failed", listener.error.getMessage());
    EnvironmentCapabilityExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT,
            request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
            new RecordingListener());
    assertNotNull(next);
  }

  @Test
  void startedAcceptsEmptyAndReplayedTruePayloadsWhileKeepingInvocationActive() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-started");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(ENVIRONMENT_NAME, DaemonMessageType.STARTED, INVOCATION_ID.toString(), 2, "{}"));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.STARTED,
            INVOCATION_ID.toString(),
            3,
            "{\"replayed\":true}"));
    fixture.gateway.receive(connection.connectionId(), completed(4, resultPayload("done")));

    assertFalse(connection.closed);
    assertEquals("done", ((TextResultContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void startedRejectsFalseReplayFlagAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-started-invalid-payload");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.STARTED,
            INVOCATION_ID.toString(),
            2,
            "{\"replayed\":false}"));
    assertTrue(connection.closed);
  }

  @Test
  void startedRejectsCallbackForAnotherInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-started-wrong-id");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.STARTED,
            UUID.randomUUID().toString(),
            2,
            "{\"replayed\":true}"));
    assertTrue(connection.closed);
  }

  @Test
  void invokeWhenOfflineThrowsUnavailable() {
    Fixture fixture = fixture();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
  }

  @Test
  void invokeWithDescriptorDriftThrowsUnavailableBeforeWireSend() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-drift");
    EnvironmentCapabilityDescriptor drifted =
        new EnvironmentCapabilityDescriptor(
            EnvironmentCapabilityIds.FS_READ,
            "999",
            fixture.descriptor.inputSchema(),
            Duration.ofSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request(drifted), new RecordingListener()));
  }

  @Test
  void invokeWithNonNullWorkdirFailsBeforeWireSend() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-non-null-workdir");
    EnvironmentCapabilityExecutionRequest bad =
        new EnvironmentCapabilityExecutionRequest(
            fixture.descriptor,
            new EnvironmentCapabilityCall(INVOCATION_ID.toString(), "{\"path\":\"README.md\"}"),
            Duration.ofSeconds(30),
            Path.of("/abs/path"));
    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, bad, new RecordingListener()));
  }

  @Test
  void invokeWithNonUuidCallIdFailsBeforeWireSend() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-non-uuid-call-id");
    EnvironmentCapabilityExecutionRequest bad =
        new EnvironmentCapabilityExecutionRequest(
            fixture.descriptor,
            new EnvironmentCapabilityCall("not-a-uuid", "{\"path\":\"README.md\"}"),
            Duration.ofSeconds(30),
            null);
    assertThrows(
        DaemonProtocolException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, bad, new RecordingListener()));
  }

  @Test
  void openingDuplicateConnectionIdCleansPreviousAndCloseIsIdempotent() {
    Fixture fixture = fixture();
    FakeConnection first = new FakeConnection("same-id");
    fixture.gateway.open(first);
    FakeConnection second = new FakeConnection("same-id");
    fixture.gateway.open(second);
    assertTrue(first.closed);
    assertFalse(second.closed);

    fixture.gateway.close("same-id");
    assertTrue(second.closed);
    fixture.gateway.close("same-id");
  }

  @Test
  void cancelAfterTransportCloseUsesNotSentOutcomeAndClosesActiveLifecycle() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-close-before-cancel");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.close(connection.connectionId());
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    handle.cancel();
    assertTrue(handle.isCancelled());
  }

  @Test
  void invalidInvokePayloadDoesNotReserveEnvironmentSlot() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-invalid-invoke");
    assertThrows(
        DaemonProtocolException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                new EnvironmentCapabilityExecutionRequest(
                    fixture.descriptor,
                    new EnvironmentCapabilityCall("bad-uuid", "{\"path\":\"README.md\"}"),
                    Duration.ofSeconds(30),
                    null),
                new RecordingListener()));

    EnvironmentCapabilityExecutionHandle next =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), new RecordingListener());
    assertNotNull(next);
  }

  @Test
  void sameEnvironmentSecondInvokeIsBusyWithoutWireSendOrActiveOverwrite() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-busy");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
                new RecordingListener()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    assertEquals("done", ((TextResultContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void cancelledTerminalReleasesEnvironmentSlotForNextInvoke() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-release");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.CANCELLED,
            INVOCATION_ID.toString(),
            2,
            "{\"reason\":\"stop\"}"));
    assertTrue(listener.error instanceof EnvironmentCapabilityCancelledException);

    EnvironmentCapabilityExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT,
            request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
            new RecordingListener());
    assertNotNull(next);
  }

  @Test
  void closeWaitsForInFlightHelloThenLeavesNoGhostBinding() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-hello-inflight");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.close(connection.connectionId());
    assertTrue(connection.closed);
  }

  @Test
  void disconnectNotifiesActiveRemoteAsUncertain() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-disconnect-uncertain");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.close(connection.connectionId());
    assertTrue(connection.closed);
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  @Test
  void readyHandlerAndTerminalCallbacksRunOutsideTransportLocks() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-locks");
    RecordingListener listener =
        new RecordingListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            super.onComplete(result);
            // 验证在回调内部允许无死锁调用 gateway
            fixture.gateway.localReadyEnvironments();
          }
        };
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    assertEquals("done", ((TextResultContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void uncertainInvokeSendDoesNotDoubleNotifyListener() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-invoke");
    connection.failNextSend = true;
    RecordingListener listener = new RecordingListener();

    assertThrows(
        EnvironmentCapabilitySendUncertainException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener));
    assertTrue(connection.closed);
    assertNull(listener.error);
  }

  @Test
  void uncertainInvokeSendUnregistersReadyEnvironmentAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-unregister");
    connection.failNextSend = true;

    assertThrows(
        EnvironmentCapabilitySendUncertainException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
    assertTrue(connection.closed);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  @Test
  void blockedConnectionSendDoesNotBlockOtherConnectionBind() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connectionA = fixture.connectReady("connection-block-a");
    connectionA.blockNextSend = true;

    FakeConnection connectionB = new FakeConnection("connection-b");
    fixture.gateway.open(connectionB);
    fixture.gateway.receive(connectionB.connectionId(), hello(0, OTHER_ENVIRONMENT_NAME));

    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connectionB.envelopes()));
    connectionA.allowSend.countDown();
  }

  @Test
  void rejectedCancelClosesConnectionAndFailsActiveInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-reject");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    connection.failNextSend = true;

    handle.cancel();
    assertTrue(connection.closed);
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
  }

  @Test
  void rejectedWelcomeClosesConnectionAndRemovesBinding() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-welcome-rejected");
    connection.failNextSend = true;
    fixture.gateway.open(connection);

    fixture.gateway.receive(connection.connectionId(), hello(0));

    assertTrue(connection.closed);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  @Test
  void cancelIsIdempotentAndSkippedAfterTerminalComplete() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-idempotent");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
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
    assertEquals("done", ((TextResultContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void connectingPublishesStaticCapabilitiesAndReadyPublishesMetadata() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-metadata");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));

    var connecting = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, connecting.status());
    assertEquals(EnvironmentCapabilityCatalog.descriptors(), connecting.capabilities());
    assertTrue(connecting.skills().isEmpty());

    fixture.gateway.receive(connection.connectionId(), ready(1, ENVIRONMENT_NAME));
    var ready = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertEquals(ADVERTISED_CAPABILITIES, ready.daemonCapabilities());
  }

  @Test
  void readyRegistersAdvertisedSkillsAndStaticCapabilities() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skills");
    assertTrue(fixture.environmentRegistry.hasReadyLease(ENVIRONMENT_NAME));
    var registered = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(ENVIRONMENT_NAME, registered.environmentId());
    assertEquals(ADVERTISED_SKILLS, registered.skills());
    assertEquals(EnvironmentCapabilityCatalog.descriptors(), registered.capabilities());
  }

  @Test
  void loadSkillSuccessCompletesLoadedResultFromGenericInvoke() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-loaded");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope invokeEnvelope = connection.envelopes().get(1);
    assertEquals(DaemonMessageType.INVOKE, invokeEnvelope.messageType());

    String completedPayload =
        resultCodec.encodeCompleted(
            EnvironmentCapabilityResult.text(invokeEnvelope.invocationId(), "# Developer rules"),
            inlineResourceStore(new byte[0]));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.COMPLETED,
            invokeEnvelope.invocationId(),
            2,
            completedPayload));

    EnvironmentSkillLoadResult result = future.join();
    EnvironmentSkillLoadResult.Loaded loaded =
        assertInstanceOf(EnvironmentSkillLoadResult.Loaded.class, result);
    assertEquals("dev", loaded.skillName());
    assertEquals("# Developer rules", loaded.content());
    assertFalse(connection.closed);
  }

  @Test
  void loadSkillFailureCompletesFailedResult() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-load-failed");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));

    DaemonEnvelope invokeEnvelope = connection.envelopes().get(1);

    String errorPayload =
        resultCodec.encodeCompleted(
            EnvironmentCapabilityResult.error(
                invokeEnvelope.invocationId(), "skill file is unreadable"),
            inlineResourceStore(new byte[0]));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.COMPLETED,
            invokeEnvelope.invocationId(),
            2,
            errorPayload));

    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
    assertEquals("dev", failed.skillName());
    assertEquals("skill file is unreadable", failed.message());
    assertFalse(connection.closed);
  }

  @Test
  void loadSkillTimeoutCompletesFailedResult() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-timeout");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(50));

    EnvironmentSkillLoadResult result = future.join();
    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertEquals("dev", failed.skillName());
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.CANCEL),
        messageTypes(connection.envelopes()));
  }

  @Test
  void loadSkillWhenOfflineReturnsFailedResult() {
    Fixture fixture = fixture();
    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertEquals("dev", result.skillName());
  }

  @Test
  void loadSkillSendRejectionClosesConnectionAndCompletesFailedResult() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-reject");
    connection.failNextSend = true;

    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();

    assertTrue(connection.closed);
    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertEquals("dev", failed.skillName());
  }

  @Test
  void helloUnsupportedProtocolIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-bad-protocol");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithVersion("99", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
  }

  @Test
  void helloCatalogVersionMismatchIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-bad-catalog-version");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithCatalogVersion("bad-catalog", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
  }

  @Test
  void wrongGatewayTokenIsRejectedBeforeEnvironmentBinding() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-bad-token");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithToken("bad-token", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
  }

  @Test
  void duplicateHelloAndReadyAreRejectedAsSingleUseHandshakeMessages() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dup-handshake");
    fixture.gateway.receive(connection.connectionId(), hello(2));
    assertTrue(connection.closed);
  }

  @Test
  void errorBeforeReadyAcceptsOnlyExactMessagePayload() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-error-handshake");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ERROR,
            null,
            1,
            "{\"message\":\"something failed\"}"));
    assertFalse(connection.closed);
  }

  @Test
  void errorWithUnexpectedPayloadFieldClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-error-bad");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ERROR,
            null,
            1,
            "{\"message\":\"fail\",\"extra\":1}"));
    assertTrue(connection.closed);
  }

  @Test
  void ackAcceptsSentSequenceAndRejectsUnsentOrOwnedInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-ack");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(ENVIRONMENT_NAME, DaemonMessageType.ACK, null, 2, "{\"acknowledgedSequence\":0}"));
    assertFalse(connection.closed);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME, DaemonMessageType.ACK, null, 3, "{\"acknowledgedSequence\":999}"));
    assertTrue(connection.closed);
  }

  @Test
  void ackRejectsUnexpectedPayloadFields() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-ack-extra");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ACK,
            null,
            2,
            "{\"acknowledgedSequence\":0,\"extra\":true}"));
    assertTrue(connection.closed);
  }

  @Test
  void concurrentHelloWhileActiveIsRejectedWithRetryLater() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertTrue(second.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(second.envelopes()));
    assertEquals(
        DaemonProtocol.ERROR_CODE_RETRY_LATER,
        envelopeCodec.readPayload(second.envelopes().get(0)).path("code").asText());
    assertEquals(
        ENVIRONMENT_NAME,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().environmentId());
    assertEquals(
        LiveEnvironmentStatus.READY,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(first.envelopes()));
  }

  @Test
  void sameNameReconnectIsAcceptedAfterClose() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    fixture.gateway.close(first.connectionId());
    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());

    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, ENVIRONMENT_NAME));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    fixture.gateway.receive(second.connectionId(), ready(1, ENVIRONMENT_NAME));
    assertEquals(
        ENVIRONMENT_NAME,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().environmentId());
    assertTrue(fixture.environmentRegistry.hasReadyLease(ENVIRONMENT_NAME));
  }

  @Test
  void expiredLeaseHolderIsTakenOverByHelloAndDisplacedConnectionIsClosedExactlyOnce() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-lease-expired");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());
    FakeConnection second = new FakeConnection("connection-lease-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());

    fixture.gateway.receive(second.connectionId(), ready(1));
    assertTrue(fixture.environmentRegistry.hasReadyLease(ENVIRONMENT_NAME));
    RecordingListener secondListener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), secondListener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(second.envelopes()));

    fixture.gateway.close(first.connectionId());
    assertTrue(second.isOpen());
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());
  }

  @Test
  void closedHolderConnectionIsTakenOverAndFreshConnectingClaimIsNeverStolen() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-dead");
    first.close();
    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());

    FakeConnection second = new FakeConnection("connection-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());

    FakeConnection third = new FakeConnection("connection-sneaky");
    fixture.gateway.open(third);
    fixture.gateway.receive(third.connectionId(), hello(0));
    assertTrue(third.closed);
    assertEquals(
        DaemonProtocol.ERROR_CODE_RETRY_LATER,
        envelopeCodec.readPayload(third.envelopes().get(0)).path("code").asText());
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());
    assertTrue(second.isOpen());
  }

  @Test
  void loadSkillUsesSameHeartbeatFreshnessRuleAsInvokeAndQuery() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-skill-stale");
    assertTrue(fixture.environmentRegistry.hasReadyLease(ENVIRONMENT_NAME));
    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());

    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
  }

  /**
   * 测试意图：证明当应用注入的时钟 (Clock) 滞后于实际数据库时间（时钟漂移）、但数据库中 lease_until 已经过期时， Gateway 依据 PostgreSQL 现在时
   * holdsReadyLease 必须 fail-closed 抛出 unavailable， 绝不能因为应用时钟落后而误判为就绪并发出 INVOKE 消息。
   */
  @Test
  void invokeFailsClosedWhenDatabaseLeaseIsExpiredEvenIfInjectedClockIsLagging() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("conn-clock-lag");

    // 将数据库租约到期时间调整为过去（以 statement_timestamp() 为准已过期）
    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());

    // 模拟应用时钟严重滞后（停留在过去）
    fixture.now.set(Instant.now().minus(Duration.ofMinutes(10)));

    // 验证 invoke 必须以 DB 现在时为准 fail-closed，抛出 Unavailable
    RecordingListener listener = new RecordingListener();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener));

    // 确保连接上没有被发出任何 INVOKE 帧（仅有先前的 WELCOME）
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
    assertNull(listener.error);
  }

  @Test
  void wrongNameOnBoundConnectionIsRejectedWithoutRerouting() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-wrong-name");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(OTHER_ENVIRONMENT_NAME, DaemonMessageType.HEARTBEAT, null, 2, "{}"));
    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertEquals(1, connection.closeAfterFlushCount);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  @Test
  void mismatchedHeartbeatScopeClosesConnectionAndFreesName() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-scope-mismatch");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(OTHER_ENVIRONMENT_NAME, DaemonMessageType.HEARTBEAT, null, 2, "{}"));
    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  /**
   * 测试意图：模拟在 gateway 预读出有效 token 对应的 Environment 后、真正执行 tryAcquire 前， 数据库中的 token 已被并发轮换；此时
   * tryAcquire 必须原子校验出 token 失效并返回 Rejected， Gateway 必须发送 REGISTRATION_REJECTED
   * 错误包并关闭连接，且不得创建或认领连接路由。
   */
  @Test
  void helloRejectedWhenTokenRotatedBetweenPreReadAndAtomicAcquire() {
    Fixture fixture = fixture();
    AtomicBoolean tokenRotated = new AtomicBoolean(false);
    EnvironmentRepository repoWrapper =
        new EnvironmentRepository() {
          @Override
          public Environment getByRegistrationToken(String token) {
            Environment env = fixture.environmentRepository.getByRegistrationToken(token);
            if (env != null && !tokenRotated.get()) {
              tokenRotated.set(true);
              // 模拟在此刻并发完成 token 轮换并写入数据库
              fixture.jdbc.update(
                  "update environment set registration_token = 'rotated-new-token' where id = ?",
                  env.getId());
            }
            return env;
          }

          @Override
          public Environment getById(UUID id) {
            return fixture.environmentRepository.getById(id);
          }

          @Override
          public Environment lockById(UUID id) {
            return fixture.environmentRepository.lockById(id);
          }

          @Override
          public Environment lockForKeyShare(UUID id) {
            return fixture.environmentRepository.lockForKeyShare(id);
          }

          @Override
          public List<Environment> listNewestFirst() {
            return fixture.environmentRepository.listNewestFirst();
          }

          @Override
          public Environment getByName(String name) {
            return fixture.environmentRepository.getByName(name);
          }

          @Override
          public boolean existsByName(String name) {
            return false;
          }

          @Override
          public boolean existsByNameExcludingId(String name, UUID excludeId) {
            return false;
          }

          @Override
          public boolean create(Environment environment) {
            return false;
          }

          @Override
          public boolean updateById(Environment environment, long expectedVersion) {
            return false;
          }

          @Override
          public boolean deleteById(UUID id, long expectedVersion) {
            return false;
          }
        };

    EnvironmentDaemonGateway raceGateway =
        new EnvironmentDaemonGateway(
            fixture.environmentRegistry,
            repoWrapper,
            new EnvironmentGatewayProperties(),
            new SystemSettingsSnapshot(SystemSettings.DEFAULT),
            Clock.systemUTC(),
            envId -> {});

    FakeConnection conn = new FakeConnection("conn-race");
    raceGateway.open(conn);
    raceGateway.receive(conn.connectionId(), hello(0));

    // 连接必须被关闭
    assertTrue(conn.closed);
    // 收到且仅收到 ERROR 包（REGISTRATION_REJECTED），不得收到 WELCOME
    assertEquals(1, conn.envelopes().size());
    DaemonEnvelope errorEnv = conn.envelopes().get(0);
    assertEquals(DaemonMessageType.ERROR, errorEnv.messageType());
    assertTrue(errorEnv.payloadJson().contains(DaemonProtocol.ERROR_CODE_REGISTRATION_REJECTED));

    // 数据库中绝不能存在该环境的 connection 记录
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
  }

  @Test
  void distinctNamesRemainConcurrentAndRouteByExactName() {
    Fixture fixture = fixture();
    FakeConnection connectionA = fixture.connectReady("connection-a");
    FakeConnection connectionB = new FakeConnection("connection-b");
    fixture.gateway.open(connectionB);
    fixture.gateway.receive(connectionB.connectionId(), hello(0, OTHER_ENVIRONMENT_NAME));
    fixture.gateway.receive(connectionB.connectionId(), ready(1, OTHER_ENVIRONMENT_NAME));

    RecordingListener listenerA = new RecordingListener();
    RecordingListener listenerB = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listenerA);
    fixture.gateway.invoke(OTHER_ENVIRONMENT, request(fixture.descriptor), listenerB);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionA.envelopes()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionB.envelopes()));
  }

  @Test
  void heartbeatKeepsEnvironmentAliveAfterReady() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-heartbeat");
    assertTrue(fixture.environmentRegistry.hasReadyLease(ENVIRONMENT_NAME));
    fixture.gateway.receive(connection.connectionId(), heartbeat(2));
    assertNotNull(fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().lastSeenAt());
  }

  @Test
  void listDirectorySendsGenericInvokeAndMapsListingDto() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-success");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope request = connection.envelopes().get(1);
    assertEquals(INVOCATION_ID.toString().length(), request.invocationId().length());

    EnvironmentDirectoryListing listing =
        new EnvironmentDirectoryListing(
            "src",
            "src",
            ".",
            true,
            "main",
            List.of(new EnvironmentDirectoryEntry("main", "src/main")));
    String listingJson;
    try {
      listingJson = objectMapper.writeValueAsString(listing);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
    String payload =
        resultCodec.encodeCompleted(
            EnvironmentCapabilityResult.json(request.invocationId(), listingJson),
            inlineResourceStore(new byte[0]));

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME, DaemonMessageType.COMPLETED, request.invocationId(), 2, payload));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    EnvironmentDirectoryDTO dto = ((EnvironmentDirectoryListResult.Loaded) result).listing();
    assertEquals("src", dto.getPath());
    assertEquals("src", dto.getDisplayPath());
    assertEquals(".", dto.getParentPath());
    assertTrue(dto.isTruncated());
    assertEquals("main", dto.getGitBranch());
    assertEquals(1, dto.getEntries().size());
    assertEquals("main", dto.getEntries().getFirst().getName());
    assertEquals("src/main", dto.getEntries().getFirst().getPath());
    assertFalse(connection.closed);
  }

  @Test
  void listDirectoryRejectsInvalidPathBeforeWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-invalid-path");

    for (String invalid :
        List.of("/abs", "", "   ", "a//b", "a/./b", "a/../b", "a\\b", "\0", "\n")) {
      EnvironmentDirectoryListResult result =
          fixture.gateway.listDirectory(ENVIRONMENT_NAME, invalid, Duration.ofSeconds(5)).join();
      assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
      assertEquals(
          EnvironmentDirectoryFailureCode.INVALID_PATH,
          ((EnvironmentDirectoryListResult.Failed) result).code());
    }
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
    assertFalse(connection.closed);
  }

  @Test
  void listDirectoryUnknownEnvironmentFailsWithoutWireSend() {
    Fixture fixture = fixture();
    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(OTHER_ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  @Test
  void listDirectoryNotReadyEnvironmentFailsWithoutWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-not-ready");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void listDirectoryExpiredHeartbeatFailsAsUnavailable() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-stale");
    fixture.jdbc.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_id = ?",
        ENVIRONMENT_NAME.value());

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void listDirectoryFailureMapsCode() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-failures");

    List<String> errorMessages =
        List.of(
            "NoSuchFileException: path does not exist",
            "NotDirectoryException: path is a file",
            "escapes environment root",
            "disk read error");
    List<EnvironmentDirectoryFailureCode> expectedCodes =
        List.of(
            EnvironmentDirectoryFailureCode.NOT_FOUND,
            EnvironmentDirectoryFailureCode.NOT_DIRECTORY,
            EnvironmentDirectoryFailureCode.INVALID_PATH,
            EnvironmentDirectoryFailureCode.IO_ERROR);

    for (int i = 0; i < errorMessages.size(); i++) {
      CompletableFuture<EnvironmentDirectoryListResult> future =
          fixture.gateway.listDirectory(ENVIRONMENT_NAME, "path" + i, Duration.ofSeconds(5));
      DaemonEnvelope req = connection.envelopes().get(connection.envelopes().size() - 1);
      String errorPayload =
          resultCodec.encodeCompleted(
              EnvironmentCapabilityResult.error(req.invocationId(), errorMessages.get(i)),
              inlineResourceStore(new byte[0]));
      fixture.gateway.receive(
          connection.connectionId(),
          envelope(
              ENVIRONMENT_NAME,
              DaemonMessageType.COMPLETED,
              req.invocationId(),
              i + 2L,
              errorPayload));
      EnvironmentDirectoryListResult.Failed failed =
          assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, future.join());
      assertEquals(expectedCodes.get(i), failed.code());
    }
    assertFalse(connection.closed);
  }

  @Test
  void listDirectoryTimeoutCancelsAndReleasesSlotForTheNextInvocation() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-timeout");
    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofMillis(50));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.TIMEOUT,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    DaemonEnvelope timedOutInvoke = connection.envelopes().get(1);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.CANCEL),
        messageTypes(connection.envelopes()));

    // timeout 必须立即释放单 active slot；旧 invocation 的迟到 CANCELLED 由 tombstone 吸收。
    CompletableFuture<EnvironmentDirectoryListResult> next =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5));
    DaemonEnvelope nextInvoke = connection.envelopes().get(3);
    assertEquals(
        List.of(
            DaemonMessageType.WELCOME,
            DaemonMessageType.INVOKE,
            DaemonMessageType.CANCEL,
            DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.CANCELLED,
            timedOutInvoke.invocationId(),
            2,
            "{\"reason\":\"cancelled\"}"));
    EnvironmentDirectoryListing listing =
        new EnvironmentDirectoryListing(".", ".", ".", false, null, List.of());
    String listingJson = objectMapper.writeValueAsString(listing);
    String payload =
        resultCodec.encodeCompleted(
            EnvironmentCapabilityResult.json(nextInvoke.invocationId(), listingJson),
            inlineResourceStore(new byte[0]));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME, DaemonMessageType.COMPLETED, nextInvoke.invocationId(), 3, payload));

    EnvironmentDirectoryListResult.Loaded loaded =
        assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, next.join());
    assertEquals(".", loaded.listing().getPath());
    assertFalse(connection.closed);
  }

  @Test
  void listDirectoryPendingIsCleanedOnDisconnect() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-disconnect");
    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));

    fixture.gateway.close(connection.connectionId());
    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  @Test
  void invokeRejectsWhenRouteTokenOrOwnerNodeIdLostInDatabase() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-fence-lost");

    // 篡改数据库中的 owner_node_id 为其他节点
    fixture.jdbc.update(
        "update environment_connection set owner_node_id = ? where environment_id = ?",
        UUID.randomUUID(),
        ENVIRONMENT_NAME.value());

    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
  }

  @Test
  void routeFenceRejectsStaleTokenUpdates() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-route-fence");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));

    // 在数据库中篡改 lease_token
    fixture.jdbc.update(
        "update environment_connection set lease_token = ? where environment_id = ?",
        UUID.randomUUID(),
        ENVIRONMENT_NAME.value());

    // 发送 READY -> 应当因为 fence 失败而被关闭
    fixture.gateway.receive(connection.connectionId(), ready(1, ENVIRONMENT_NAME));

    assertTrue(connection.closed);
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapabilityDescriptor descriptor) {
    return request(descriptor, Duration.ofSeconds(30));
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapabilityDescriptor descriptor, Duration timeout) {
    return request(descriptor, timeout, INVOCATION_ID);
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapabilityDescriptor descriptor, UUID invocationId, String ignoredModelCallId) {
    return request(descriptor, Duration.ofSeconds(30), invocationId);
  }

  private EnvironmentCapabilityExecutionRequest request(
      EnvironmentCapabilityDescriptor descriptor, Duration timeout, UUID invocationId) {
    return new EnvironmentCapabilityExecutionRequest(
        descriptor,
        new EnvironmentCapabilityCall(invocationId.toString(), "{\"path\":\"README.md\"}"),
        timeout,
        null);
  }

  private String resultPayload(String text) {
    return resultCodec.encodeCompleted(
        new EnvironmentCapabilityResult(
            INVOCATION_ID.toString(), List.of(new TextResultContent(text)), false, "{}"),
        inlineResourceStore(new byte[0]));
  }

  /** 端侧最小的 daemon 资源 store：仅存/读测试用内存字节。 */
  private static DaemonResourceStore inlineResourceStore(byte[] bytes) {
    return new DaemonResourceStore() {
      @Override
      public DaemonResourceRef store(byte[] storedBytes, String mediaType) throws IOException {
        return new DaemonResourceRef(
            "file:///inline", mediaType, null, (long) storedBytes.length, sha256(storedBytes));
      }

      @Override
      public byte[] read(DaemonResourceRef ref) throws IOException {
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
    return helloWithToken(GATEWAY_TOKEN, sequence);
  }

  private String hello(long sequence, EnvironmentId environmentName) {
    return helloWithToken(
        environmentName.equals(OTHER_ENVIRONMENT_NAME) ? "other-token" : GATEWAY_TOKEN, sequence);
  }

  private String helloWithVersion(String protocolVersion, long sequence) {
    return envelope(
        null,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"protocolVersion\":"
            + protocolVersion
            + ",\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\",\"registrationToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithCatalogVersion(String catalogVersion, long sequence) {
    return envelope(
        null,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"capabilityCatalogVersion\":\""
            + catalogVersion
            + "\",\"registrationToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithToken(String token, long sequence) {
    return envelope(
        null,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\",\"registrationToken\":\""
            + token
            + "\"}");
  }

  private String ready(long sequence) {
    return ready(sequence, ENVIRONMENT_NAME);
  }

  private String ready(long sequence, EnvironmentId environmentName) {
    return envelope(
        environmentName,
        DaemonMessageType.READY,
        null,
        sequence,
        capabilitiesCodec.encode(ADVERTISED_CAPABILITIES));
  }

  private String heartbeat(long sequence) {
    return envelope(ENVIRONMENT_NAME, DaemonMessageType.HEARTBEAT, null, sequence, "{}");
  }

  private String partial(long sequence, String payload) {
    return envelope(
        ENVIRONMENT_NAME, DaemonMessageType.PARTIAL, INVOCATION_ID.toString(), sequence, payload);
  }

  private String completed(long sequence, String payload) {
    return envelope(
        ENVIRONMENT_NAME, DaemonMessageType.COMPLETED, INVOCATION_ID.toString(), sequence, payload);
  }

  private String envelope(
      EnvironmentId environmentName,
      DaemonMessageType messageType,
      String invocationId,
      long sequence,
      String payload) {
    return envelopeCodec.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION, messageType, environmentName, invocationId, sequence, payload));
  }

  private static List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private Fixture fixture() {
    return fixture(ignoredEnvironmentId -> {});
  }

  private Fixture fixture(EnvironmentReadyListener readyListener) {
    return new Fixture(descriptor(), readyListener);
  }

  private static EnvironmentCapabilityDescriptor descriptor() {
    return EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
  }

  private final class Fixture {
    final SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    final JdbcTemplate jdbc = new JdbcTemplate(ds);
    final UUID nodeId = UUID.randomUUID();
    final EnvironmentRegistry environmentRegistry = new EnvironmentRegistry(jdbc, nodeId);
    final EnvironmentRepository environmentRepository =
        new EnvironmentRepository() {
          @Override
          public List<Environment> listNewestFirst() {
            throw new UnsupportedOperationException();
          }

          @Override
          public Environment getById(UUID id) {
            List<Environment> l =
                jdbc.query(
                    "select id, name, registration_token, version, created_at as create_time, updated_at as update_time from environment where id = ?",
                    (rs, rowNum) -> {
                      Environment e = new Environment();
                      e.setId((UUID) rs.getObject("id"));
                      e.setName(rs.getString("name"));
                      e.setRegistrationToken(rs.getString("registration_token"));
                      e.setVersion(rs.getLong("version"));
                      return e;
                    },
                    id);
            return l.isEmpty() ? null : l.get(0);
          }

          @Override
          public Environment getByName(String name) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Environment getByRegistrationToken(String token) {
            List<Environment> l =
                jdbc.query(
                    "select id, name, registration_token, version, created_at as create_time, updated_at as update_time from environment where registration_token = ?",
                    (rs, rowNum) -> {
                      Environment e = new Environment();
                      e.setId((UUID) rs.getObject("id"));
                      e.setName(rs.getString("name"));
                      e.setRegistrationToken(rs.getString("registration_token"));
                      e.setVersion(rs.getLong("version"));
                      return e;
                    },
                    token);
            return l.isEmpty() ? null : l.get(0);
          }

          @Override
          public Environment lockById(UUID id) {
            return getById(id);
          }

          @Override
          public Environment lockForKeyShare(UUID id) {
            return getById(id);
          }

          @Override
          public boolean existsByName(String name) {
            return false;
          }

          @Override
          public boolean existsByNameExcludingId(String name, UUID excludeId) {
            return false;
          }

          @Override
          public boolean create(Environment environment) {
            return false;
          }

          @Override
          public boolean updateById(Environment environment, long expectedVersion) {
            return false;
          }

          @Override
          public boolean deleteById(UUID id, long expectedVersion) {
            return false;
          }
        };
    final AtomicReference<Instant> now = new AtomicReference<>();
    final Duration heartbeatTimeout = Duration.ofSeconds(60);
    final EnvironmentDaemonGateway gateway;
    final EnvironmentCapabilityDescriptor descriptor;

    private Fixture(
        EnvironmentCapabilityDescriptor descriptor, EnvironmentReadyListener readyListener) {
      this.descriptor = descriptor;
      EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
      gateway =
          new EnvironmentDaemonGateway(
              environmentRegistry,
              environmentRepository,
              gatewayProperties,
              new SystemSettingsSnapshot(SystemSettings.DEFAULT),
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
                  Instant current = now.get();
                  return current != null ? current : Instant.now();
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

  private static class RecordingListener implements EnvironmentCapabilityExecutionListener {
    private final List<EnvironmentCapabilityResult> partials = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private EnvironmentCapabilityResult completed;
    private Throwable error;

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      partials.add(partial);
      events.add(((TextResultContent) partial.contents().get(0)).text());
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      events.add("complete");
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
    private int closeCount;
    private int closeAfterFlushCount;
    private boolean failNextSend;
    private boolean blockNextSend;
    private final CountDownLatch sendEntered = new CountDownLatch(1);
    private final CountDownLatch allowSend = new CountDownLatch(1);

    private FakeConnection(String connectionId) {
      this.connectionId = connectionId;
    }

    @Override
    public String connectionId() {
      return connectionId;
    }

    @Override
    public boolean sendText(String text) {
      if (!open) {
        throw new IllegalStateException("closed");
      }
      if (failNextSend) {
        failNextSend = false;
        return false;
      }
      if (blockNextSend) {
        blockNextSend = false;
        sendEntered.countDown();
        try {
          allowSend.await();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(error);
        }
      }
      envelopes.add(new DaemonEnvelopeCodec().decode(text));
      return true;
    }

    @Override
    public void close() {
      open = false;
      closed = true;
      closeCount += 1;
    }

    @Override
    public void closeAfterFlush() {
      closeAfterFlushCount += 1;
      close();
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
