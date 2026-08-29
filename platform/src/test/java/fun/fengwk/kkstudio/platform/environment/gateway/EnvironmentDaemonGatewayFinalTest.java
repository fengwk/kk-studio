package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonDirectoryFailureCode;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonResourceStore;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillLoadCodec;
import fun.fengwk.kkstudio.platform.environment.registry.BindResult;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway 仅承担连接/协议传输职责。durable 的 claim/lease/terminal 由 Harness Runtime 负责。路由使用 HELLO 时确定的
 * canonical EnvironmentName；display name 永不参与路由。
 */
class EnvironmentDaemonGatewayFinalTest extends PostgresSchemaSupport {

  @BeforeEach
  void setUp() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  private static final EnvironmentName ENVIRONMENT_NAME = new EnvironmentName("env-1");
  private static final EnvironmentName OTHER_ENVIRONMENT_NAME = new EnvironmentName("env-2");
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
          ADVERTISED_SKILLS,
          List.of(
              new DaemonMcpServerDescriptor(
                  "fs",
                  DaemonMcpServerStatus.READY,
                  null,
                  List.of(new DaemonMcpToolDescriptor("read_file", "Read a file"))),
              new DaemonMcpServerDescriptor(
                  "broken", DaemonMcpServerStatus.FAILED, "cannot connect", List.of())));

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonDirectoryCodec directoryCodec = new DaemonDirectoryCodec();
  private final DaemonSkillLoadCodec skillLoadCodec = new DaemonSkillLoadCodec();

  @Test
  void readyNotifiesHandlerAndInvokeSendsProtocolThenMapsCompletion() {
    List<EnvironmentName> ready = new ArrayList<>();
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
    assertEquals(ENVIRONMENT_NAME, invoke.environmentName());
    assertEquals(INVOCATION_ID.toString(), invoke.invocationId());
    DaemonCapabilityInvokeCodec.InvokeRequest decoded =
        new DaemonCapabilityInvokeCodec().decode(invoke.payloadJson());
    assertEquals("fs.read", decoded.capabilityId().value());
    assertEquals("1", decoded.capabilityVersion());
    assertEquals(".", decoded.workspacePath());
    assertEquals("{\"path\":\"README.md\"}", decoded.argumentsJson());
    assertEquals(Duration.ofSeconds(30), decoded.timeout());

    handle.cancel();
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
    assertEquals(INVOCATION_ID.toString(), listener.completed.callId());
  }

  @Test
  void partialIsForwardedAndResourcePartialIsRejected() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-partial");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(2, resultPayload("chunk")));
    assertEquals("chunk", ((TextToolContent) listener.partials.get(0).contents().get(0)).text());

    String resourcePayload =
        resultCodec.encodeCompleted(
            new EnvironmentCapabilityResult(
                INVOCATION_ID.toString(),
                List.of(new BinaryToolContent("text/plain", new byte[] {1, 2})),
                false,
                "{}"),
            inlineResourceStore(new byte[] {1, 2}));
    fixture.gateway.receive(connection.connectionId(), partial(4, resourcePayload));
    assertTrue(connection.closed);
  }

  /**
   * Capability 重构不可退化的 fitness gate：Environment daemon 的多个 PARTIAL 必须保序透传，terminal 后迟到 PARTIAL
   * 按协议失败处理且不得回调 listener。
   */
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
    assertEquals("complete", ((TextToolContent) listener.completed.contents().get(0)).text());
    assertNull(listener.error);

    // terminal 后的迟到 PARTIAL 没有 active invocation：按现有协议 contract 关闭连接，不得复活回调。
    fixture.gateway.receive(connection.connectionId(), partial(5, resultPayload("late")));

    assertEquals(List.of("partial1", "partial2", "complete"), listener.events);
    assertEquals(2, listener.partials.size());
    assertEquals("complete", ((TextToolContent) listener.completed.contents().get(0)).text());
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
                List.of(new BinaryToolContent("text/plain", new byte[] {7, 8})),
                false,
                "{}"),
            inlineResourceStore(new byte[] {7, 8}));
    fixture.gateway.receive(connection.connectionId(), completed(2, payload));
    BinaryToolContent binary = (BinaryToolContent) listener.completed.contents().get(0);
    assertEquals("text/plain", binary.mediaType());
    assertEquals(2, binary.content().length);
    // 网关不触碰任何 durable store，结果保持为内存中的 Binary。
    assertEquals(1, listener.completed.contents().size());
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

    // FAILED 是 daemon 已确认的 terminal failure，必须投影为 typed error 并释放 active 槽位。
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

    // STARTED 只确认 daemon 已开始执行；空 payload 与 replayed=true 都不应释放 active 槽位。
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
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void startedRejectsFalseReplayFlagAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-started-invalid-payload");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    // replayed 字段一旦出现只能为 true，false 是严格协议错误而不是普通 capability failure。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.STARTED,
            INVOCATION_ID.toString(),
            2,
            "{\"replayed\":false}"));

    assertTrue(connection.closed);
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
  }

  @Test
  void startedRejectsCallbackForAnotherInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-started-wrong-owner");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    // active invocation 的 UUID 是 wire ownership；陌生回调不得驱动当前 listener。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME, DaemonMessageType.STARTED, SECOND_INVOCATION_ID.toString(), 2, "{}"));

    assertTrue(connection.closed);
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
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
    FakeConnection connection = fixture.connectReady("connection-descriptor-drift");
    EnvironmentCapabilityDescriptor current = fixture.descriptor;
    EnvironmentCapabilityDescriptor drifted =
        new EnvironmentCapabilityDescriptor(
            current.id(), current.version() + "-drifted", current.inputSchema(), current.timeout());

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request(drifted), new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void invokeWithNonNullWorkdirFailsBeforeWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-workdir");
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            fixture.descriptor,
            new EnvironmentCapabilityCall(INVOCATION_ID.toString(), "{\"path\":\"README.md\"}"),
            Duration.ofSeconds(30),
            Path.of("/workspace"));

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request, new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void invokeWithNonUuidCallIdFailsBeforeWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-invalid-call-id");
    EnvironmentCapabilityExecutionRequest request =
        new EnvironmentCapabilityExecutionRequest(
            fixture.descriptor,
            new EnvironmentCapabilityCall("model-call", "{\"path\":\"README.md\"}"),
            Duration.ofSeconds(30),
            null);

    assertThrows(
        IllegalArgumentException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request, new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void openingDuplicateConnectionIdCleansPreviousAndCloseIsIdempotent() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-duplicate");
    FakeConnection replacement = new FakeConnection("connection-duplicate");

    // 同一 transport connectionId 的 open 必须清理旧 state，后续 close 只能清理新 state 一次。
    fixture.gateway.open(replacement);
    fixture.gateway.close(replacement.connectionId());
    fixture.gateway.close(replacement.connectionId());

    assertEquals(1, first.closeCount);
    assertEquals(1, replacement.closeCount);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  @Test
  void cancelAfterTransportCloseUsesNotSentOutcomeAndClosesActiveLifecycle() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-transport-closed");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    connection.close();

    // transport 已经关闭但 gateway 尚未收到 close 事件时，CANCEL 不能伪造一帧；随后清理仍须收敛 active。
    handle.cancel();
    assertTrue(handle.isCancelled());
    assertEquals(
        0,
        connection.envelopes().stream()
            .filter(envelope -> envelope.messageType() == DaemonMessageType.CANCEL)
            .count());
    fixture.gateway.close(connection.connectionId());
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
  }

  @Test
  void invalidInvokePayloadDoesNotReserveEnvironmentSlot() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-invalid-payload");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                request(fixture.descriptor, Duration.ofSeconds(Long.MAX_VALUE)),
                new RecordingListener()));

    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), new RecordingListener());
    assertNotNull(handle);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
  }

  /** 同一 Environment 的第二个 admission 是 typed Busy，不能发第二个 INVOKE 或覆盖首个 active。 */
  @Test
  void sameEnvironmentSecondInvokeIsBusyWithoutWireSendOrActiveOverwrite() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-busy");
    RecordingListener firstListener = new RecordingListener();
    RecordingListener secondListener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), firstListener);

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
                secondListener));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("first")));
    assertEquals("first", ((TextToolContent) firstListener.completed.contents().getFirst()).text());
    assertNull(secondListener.completed);
    assertNull(secondListener.error);

    EnvironmentCapabilityExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT,
            request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
            secondListener);
    assertNotNull(next);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
  }

  /** CANCEL 请求本身不释放槽位；收到 CANCELLED terminal 后，下一次 admission 才能开始。 */
  @Test
  void cancelledTerminalReleasesEnvironmentSlotForNextInvoke() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-release");
    EnvironmentCapabilityExecutionHandle first =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), new RecordingListener());
    first.cancel();

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
                new RecordingListener()));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.CANCELLED,
            INVOCATION_ID.toString(),
            2,
            "{\"reason\":\"stop\"}"));

    EnvironmentCapabilityExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT,
            request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
            new RecordingListener());
    assertNotNull(next);
    assertEquals(
        List.of(
            DaemonMessageType.WELCOME,
            DaemonMessageType.INVOKE,
            DaemonMessageType.CANCEL,
            DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
  }

  /** close 必须等 in-flight HELLO 的 tryBind 完成后再摘 registry：否则 unbound 清理会放过随后绑定，留下幽灵占用。 */
  @Test
  void closeWaitsForInFlightHelloThenLeavesNoGhostBinding() throws Exception {
    CountDownLatch bindEntered = new CountDownLatch(1);
    CountDownLatch allowBind = new CountDownLatch(1);
    SingleConnectionDataSource ds =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    LiveEnvironmentRegistry registry =
        new LiveEnvironmentRegistry(jdbc, UUID.randomUUID()) {
          @Override
          public BindResult tryAcquire(
              EnvironmentName environmentName, String daemonId, Duration leaseDuration) {
            // 必须在 registry monitor 之外等待：否则测试线程的 find() 会和 in-flight HELLO 死锁，
            // finally 永远无法释放 allowBind。
            bindEntered.countDown();
            try {
              allowBind.await();
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(error);
            }
            return super.tryAcquire(environmentName, daemonId, leaseDuration);
          }
        };
    EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
    gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
    EnvironmentDaemonGateway gateway =
        new EnvironmentDaemonGateway(
            registry,
            gatewayProperties,
            new SystemSettingsSnapshot(SystemSettings.DEFAULT),
            Clock.fixed(NOW, ZoneOffset.UTC),
            environmentName -> {});
    FakeConnection connection = new FakeConnection("connection-race");
    gateway.open(connection);
    Thread receiveThread =
        new Thread(() -> gateway.receive(connection.connectionId(), hello(0)), "hello-receive");
    Thread closeThread = new Thread(() -> gateway.close(connection.connectionId()), "close-race");
    try {
      receiveThread.start();
      assertTrue(bindEntered.await(5, TimeUnit.SECONDS), "receive must enter tryBind");
      closeThread.start();
      // close 必须卡在 receive 持有的 ConnectionState 上（BLOCKED），此时 registry 仍空、连接仍开。
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (closeThread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(
          Thread.State.BLOCKED,
          closeThread.getState(),
          "close must contend on ConnectionState held by in-flight receive");
      assertTrue(registry.find(ENVIRONMENT_NAME).isEmpty());
      assertTrue(connection.isOpen());
    } finally {
      allowBind.countDown();
      receiveThread.join(TimeUnit.SECONDS.toMillis(5));
      closeThread.join(TimeUnit.SECONDS.toMillis(5));
    }
    assertFalse(receiveThread.isAlive());
    assertFalse(closeThread.isAlive());
    assertEquals(
        LiveEnvironmentStatus.CONNECTING, registry.find(ENVIRONMENT_NAME).orElseThrow().status());
    assertFalse(connection.isOpen());
    assertEquals(1, connection.closeCount);
  }

  @Test
  void disconnectNotifiesActiveRemoteAsUncertain() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-drop");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    fixture.gateway.close(connection.connectionId());
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    assertNull(listener.completed);
  }

  @Test
  void readyHandlerAndTerminalCallbacksRunOutsideTransportLocks() {
    AtomicBoolean readySawLockFree = new AtomicBoolean();
    AtomicBoolean completeSawLockFree = new AtomicBoolean();
    AtomicReference<EnvironmentDaemonGateway> gatewayRef = new AtomicReference<>();
    Fixture fixture =
        fixture(
            environmentName -> {
              // 重新进入 gateway monitor（loadSkill）但不再分派 READY。若 READY 仍持有
              // ConnectionState 或 gateway 锁，则会发生死锁。
              try {
                gatewayRef
                    .get()
                    .loadSkill(environmentName, "missing-skill", Duration.ofMillis(20))
                    .get();
              } catch (Exception ignored) {
                // Offline/timeout 路径仍会执行加锁区段。
              }
              readySawLockFree.set(true);
            });
    gatewayRef.set(fixture.gateway);
    FakeConnection connection = fixture.connectReady("connection-lock-free");
    assertTrue(readySawLockFree.get());

    RecordingListener listener =
        new RecordingListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            super.onComplete(result);
            // 在完成时重新进入 close 路径；不能在 ConnectionState 锁内执行。
            fixture.gateway.close(connection.connectionId());
            completeSawLockFree.set(true);
          }
        };
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
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
        EnvironmentCapabilitySendUncertainException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener));
    assertNull(listener.error);
    assertNull(listener.completed);
  }

  @Test
  void uncertainInvokeSendUnregistersReadyEnvironmentAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-uncertain-cleanup");
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    connection.failNextSend = true;
    assertThrows(
        EnvironmentCapabilitySendUncertainException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
    assertTrue(connection.closed);
    assertFalse(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  /** 阻塞的单连接 enqueue 不持有 Gateway 全局 monitor；其它连接仍可完成 HELLO/READY 绑定。 */
  @Test
  void blockedConnectionSendDoesNotBlockOtherConnectionBind() throws Exception {
    Fixture fixture = fixture();
    FakeConnection blocked = fixture.connectReady("connection-blocked-send");
    blocked.blockNextSend = true;
    AtomicReference<Throwable> invokeError = new AtomicReference<>();
    Thread invokeThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    fixture.gateway.invoke(
                        ENVIRONMENT, request(fixture.descriptor), new RecordingListener());
                  } catch (Throwable error) {
                    invokeError.set(error);
                  }
                });
    try {
      assertTrue(blocked.sendEntered.await(5, TimeUnit.SECONDS));

      FakeConnection other = new FakeConnection("connection-other-bind");
      fixture.gateway.open(other);
      fixture.gateway.receive(other.connectionId(), hello(0, OTHER_ENVIRONMENT_NAME));
      fixture.gateway.receive(other.connectionId(), ready(1, OTHER_ENVIRONMENT_NAME));
      assertTrue(
          fixture.environmentRegistry.isReady(
              OTHER_ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    } finally {
      blocked.allowSend.countDown();
      invokeThread.join(TimeUnit.SECONDS.toMillis(5));
    }
    assertFalse(invokeThread.isAlive());
    assertNull(invokeError.get());
  }

  /** CANCEL 入队拒绝不能静默丢帧：连接关闭，active invocation 立即按 uncertain 失败。 */
  @Test
  void rejectedCancelClosesConnectionAndFailsActiveInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-cancel-rejected");
    RecordingListener listener = new RecordingListener();
    EnvironmentCapabilityExecutionHandle handle =
        fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    connection.failNextSend = true;

    handle.cancel();

    assertTrue(connection.closed);
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    assertEquals(
        LiveEnvironmentStatus.CONNECTING,
        fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().status());
  }

  /** LIST_DIRECTORY 入队拒绝关闭连接，并立即以 ENVIRONMENT_UNAVAILABLE 完成 pending。 */
  @Test
  void rejectedDirectoryFrameClosesConnectionAndFailsPending() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-directory-rejected");
    connection.failNextSend = true;

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();

    assertTrue(connection.closed);
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** WELCOME 入队拒绝同样使刚绑定的连接整体失败，不能留下 CONNECTING registry 条目。 */
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
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
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
    assertTrue(connecting.mcpServers().isEmpty());

    fixture.gateway.receive(connection.connectionId(), ready(1, ENVIRONMENT_NAME));
    var ready = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertEquals(ADVERTISED_CAPABILITIES, ready.daemonCapabilities());
  }

  @Test
  void readyRegistersAdvertisedSkillsAndStaticCapabilities() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skills");
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    var registered = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(ENVIRONMENT_NAME, registered.name());
    assertEquals(ENVIRONMENT_NAME, registered.name());
    assertEquals(ADVERTISED_SKILLS, registered.skills());
    // READY 只发布 MCP server 摘要（无 headers/命令/URL/完整 schema）。
    assertEquals(
        List.of("fs", "broken"), registered.mcpServers().stream().map(s -> s.name()).toList());
    assertEquals(DaemonMcpServerStatus.READY, registered.mcpServers().get(0).status());
    assertEquals(
        List.of("read_file"),
        registered.mcpServers().get(0).tools().stream().map(t -> t.name()).toList());
    assertEquals(DaemonMcpServerStatus.FAILED, registered.mcpServers().get(1).status());
    assertEquals("cannot connect", registered.mcpServers().get(1).error());
    assertTrue(registered.mcpServers().get(1).tools().isEmpty());
    // Capability 来自静态 EnvironmentCapabilityCatalog；wire READY 不会对外声明它们。
    assertEquals(EnvironmentCapabilityCatalog.descriptors(), registered.capabilities());
  }

  @Test
  void loadSkillSuccessCompletesLoadedResultFromOwnedCallback() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-loaded");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));
    String requestId = lastSkillLoadRequestId(connection);

    // SKILL_LOADED 必须按 requestId 完成对应 future，并保留 daemon 返回的正文。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOADED,
            requestId,
            2,
            skillLoadCodec.encodeLoaded(
                new DaemonSkillLoadCodec.SkillLoaded("dev", "# Developer rules"))));

    EnvironmentSkillLoadResult result = future.join();
    EnvironmentSkillLoadResult.Loaded loaded =
        assertInstanceOf(EnvironmentSkillLoadResult.Loaded.class, result);
    assertEquals("dev", loaded.skillName());
    assertEquals("# Developer rules", loaded.content());
    assertFalse(connection.closed);
  }

  @Test
  void loadSkillFailureCompletesFailedResultFromOwnedCallback() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-load-failed");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));
    String requestId = lastSkillLoadRequestId(connection);

    // SKILL_LOAD_FAILED 是 daemon 的确定性结果，应原样映射为应用层 Failed。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOAD_FAILED,
            requestId,
            2,
            skillLoadCodec.encodeFailed(
                new DaemonSkillLoadCodec.SkillLoadFailed("dev", "skill file is unreadable"))));

    EnvironmentSkillLoadResult.Failed failed =
        assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
    assertEquals("dev", failed.skillName());
    assertEquals("skill file is unreadable", failed.message());
    assertFalse(connection.closed);
  }

  @Test
  void loadSkillMismatchedCallbackNameIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-name-mismatch");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(50));
    String requestId = lastSkillLoadRequestId(connection);

    // requestId 所属的 skill name 也必须严格匹配，不能借同一连接转交另一 skill 的正文。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOADED,
            requestId,
            2,
            skillLoadCodec.encodeLoaded(
                new DaemonSkillLoadCodec.SkillLoaded("ops", "# Operations rules"))));

    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.LOAD_SKILL, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
  }

  @Test
  void loadSkillMismatchedFailedCallbackNameIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-failure-name-mismatch");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(50));
    String requestId = lastSkillLoadRequestId(connection);

    // 成功和失败回执都受同一 name ownership 约束，失败回执不能绕过校验。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOAD_FAILED,
            requestId,
            2,
            skillLoadCodec.encodeFailed(
                new DaemonSkillLoadCodec.SkillLoadFailed("ops", "wrong skill"))));

    assertTrue(connection.closed);
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
  }

  @Test
  void loadSkillCallbackFromAnotherConnectionIsRejectedByOwnership() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-skill-owner");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(50));
    String requestId = lastSkillLoadRequestId(first);

    FakeConnection second = new FakeConnection("connection-skill-other-owner");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, OTHER_ENVIRONMENT_NAME));
    fixture.gateway.receive(second.connectionId(), ready(1, OTHER_ENVIRONMENT_NAME));

    // 另一个 Environment 的连接即使知道 requestId，也不能消费 first connection 的 pending。
    fixture.gateway.receive(
        second.connectionId(),
        envelope(
            OTHER_ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOADED,
            requestId,
            2,
            skillLoadCodec.encodeLoaded(
                new DaemonSkillLoadCodec.SkillLoaded("dev", "wrong owner"))));

    assertTrue(second.closed);
    assertFalse(first.closed);
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
  }

  @Test
  void loadSkillTimeoutThenLateCallbackIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-timeout");
    CompletableFuture<EnvironmentSkillLoadResult> future =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(30));
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, future.join());
    String requestId = lastSkillLoadRequestId(connection);

    // skill load 没有 directory tombstone：超时后同 requestId 的迟到回执是 unsolicited protocol error。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.SKILL_LOADED,
            requestId,
            2,
            skillLoadCodec.encodeLoaded(new DaemonSkillLoadCodec.SkillLoaded("dev", "late"))));

    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.LOAD_SKILL, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
  }

  @Test
  void rejectedSkillLoadFrameClosesConnectionAndFailsPending() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-send-rejected");
    connection.failNextSend = true;

    // transport 拒绝 LOAD_SKILL 后必须关闭连接并立即完成 pending，而不能等待超时。
    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();

    assertTrue(connection.closed);
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
  }

  @Test
  void helloUnsupportedProtocolIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-unsupported-protocol");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithVersion("3", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
  }

  @Test
  void helloCatalogVersionMismatchIsRejectedAndClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-catalog-mismatch");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithCatalogVersion("999", 0));
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
  }

  @Test
  void wrongGatewayTokenIsRejectedBeforeEnvironmentBinding() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-wrong-token");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), helloWithToken("wrong-token", 0));

    // token 校验发生在 registry bind 之前，认证失败不得产生 CONNECTING 条目。
    assertTrue(connection.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(connection.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
  }

  @Test
  void duplicateHelloAndReadyAreRejectedAsSingleUseHandshakeMessages() {
    Fixture helloFixture = fixture();
    FakeConnection helloConnection = helloFixture.connectReady("connection-duplicate-hello");
    helloFixture.gateway.receive(helloConnection.connectionId(), hello(2));
    assertTrue(helloConnection.closed);

    Fixture readyFixture = fixture();
    FakeConnection readyConnection = readyFixture.connectReady("connection-duplicate-ready");
    readyFixture.gateway.receive(readyConnection.connectionId(), ready(2));
    assertTrue(readyConnection.closed);
  }

  @Test
  void errorBeforeReadyAcceptsOnlyExactMessagePayload() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-error-before-ready");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));

    // ERROR 是 daemon 在握手阶段报告自身失败的合法 inbound 消息，只接受唯一 message 字段。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ERROR,
            null,
            1,
            "{\"message\":\"daemon boot failed\"}"));

    assertFalse(connection.closed);
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void errorWithUnexpectedPayloadFieldClosesConnection() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-error-invalid-payload");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ERROR,
            null,
            2,
            "{\"message\":\"bad\",\"extra\":true}"));

    // ERROR payload 多字段会触发 strict decoder，且协议错误必须 drain ERROR 后关闭连接。
    assertTrue(connection.closed);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.ERROR),
        messageTypes(connection.envelopes()));
    assertEquals(1, connection.closeAfterFlushCount);
  }

  @Test
  void ackAcceptsSentSequenceAndRejectsUnsentOrOwnedInvocation() {
    Fixture validFixture = fixture();
    FakeConnection validConnection = validFixture.connectReady("connection-ack-valid");
    validFixture.gateway.receive(
        validConnection.connectionId(),
        envelope(ENVIRONMENT_NAME, DaemonMessageType.ACK, null, 2, "{\"acknowledgedSequence\":0}"));
    assertFalse(validConnection.closed);

    Fixture unsentFixture = fixture();
    FakeConnection unsentConnection = unsentFixture.connectReady("connection-ack-unsent");
    unsentFixture.gateway.receive(
        unsentConnection.connectionId(),
        envelope(ENVIRONMENT_NAME, DaemonMessageType.ACK, null, 2, "{\"acknowledgedSequence\":1}"));
    assertTrue(unsentConnection.closed);

    Fixture ownedFixture = fixture();
    FakeConnection ownedConnection = ownedFixture.connectReady("connection-ack-owned");
    ownedFixture.gateway.receive(
        ownedConnection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ACK,
            INVOCATION_ID.toString(),
            2,
            "{\"acknowledgedSequence\":0}"));
    assertTrue(ownedConnection.closed);
  }

  @Test
  void ackRejectsUnexpectedPayloadFields() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-ack-extra-field");
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.ACK,
            null,
            2,
            "{\"acknowledgedSequence\":0,\"extra\":true}"));

    assertTrue(connection.closed);
    assertEquals(1, connection.closeAfterFlushCount);
  }

  @Test
  void sameNameConcurrentBindIsTerminalConflictWithTypedCode() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), helloWithDaemon("d2", 0, ENVIRONMENT_NAME));
    assertTrue(second.closed);
    assertEquals(List.of(DaemonMessageType.ERROR), messageTypes(second.envelopes()));
    assertEquals(
        DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT,
        envelopeCodec.readPayload(second.envelopes().get(0)).path("code").asText());
    assertEquals(
        ENVIRONMENT_NAME, fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().name());
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
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());

    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, ENVIRONMENT_NAME));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    fixture.gateway.receive(second.connectionId(), ready(1, ENVIRONMENT_NAME));
    assertEquals(
        ENVIRONMENT_NAME, fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().name());
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
  }

  @Test
  void expiredLeaseHolderIsTakenOverByHelloAndDisplacedConnectionIsClosedExactlyOnce() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-lease-expired");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);
    CompletableFuture<EnvironmentSkillLoadResult> pendingSkill =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LOAD_SKILL),
        messageTypes(first.envelopes()));

    // 心跳超过配置超时：租约过期——HELLO 原子接管而非冲突。
    fixture.jdbc.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());
    FakeConnection second = new FakeConnection("connection-lease-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    // 被替换的旧连接在后续操作中安全关闭；active remote 与 pending skill load 不泄漏。
    assertTrue(listener.error instanceof EnvironmentCapabilitySendUncertainException);
    assertTrue(pendingSkill.join() instanceof EnvironmentSkillLoadResult.Failed);
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());

    // 新 holder 完整可用：READY 后 invoke 路由到新连接。
    fixture.gateway.receive(second.connectionId(), ready(1));
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    RecordingListener secondListener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), secondListener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(second.envelopes()));

    // 旧连接的迟到 close 回调不得影响新 holder。
    fixture.gateway.close(first.connectionId());
    assertTrue(second.isOpen());
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());
  }

  @Test
  void closedHolderConnectionIsTakenOverAndFreshConnectingClaimIsNeverStolen() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-dead");
    // transport 死亡但 gateway/registry 尚未清理（例如 close 事件丢失）。
    first.close();
    fixture.jdbc.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());

    FakeConnection second = new FakeConnection("connection-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());

    // CONNECTING 的新鲜声明（打开 + 心跳未过期）绝不能被抢走：typed 冲突。
    FakeConnection third = new FakeConnection("connection-sneaky");
    fixture.gateway.open(third);
    fixture.gateway.receive(third.connectionId(), helloWithDaemon("d3", 0, ENVIRONMENT_NAME));
    assertTrue(third.closed);
    assertEquals(
        DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT,
        envelopeCodec.readPayload(third.envelopes().get(0)).path("code").asText());
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isPresent());
    assertTrue(second.isOpen());
  }

  @Test
  void loadSkillUsesSameHeartbeatFreshnessRuleAsInvokeAndQuery() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-stale");
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    fixture.jdbc.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());

    // 心跳过期：与 invoke/query 完全相同的规则下立即 Failed，绝不发送 LOAD_SKILL。
    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT, request(fixture.descriptor), new RecordingListener()));
    assertFalse(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void wrongNameOnBoundConnectionIsRejectedWithoutRerouting() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-wrong-name");
    // 已绑定连接绝不能接受作用域为其他环境名称的 envelope。
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
    // 已绑定连接收到不同名称的 HEARTBEAT 属于协议一致性失败：连接被关闭、名称被释放，
    // 新连接可立即重新绑定。
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
    fixture.jdbc.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());
    FakeConnection rebind = new FakeConnection("connection-rebind");
    fixture.gateway.open(rebind);
    fixture.gateway.receive(rebind.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(rebind.envelopes()));
  }

  @Test
  void distinctNamesRemainConcurrentAndRouteByExactName() {
    Fixture fixture = fixture();
    FakeConnection connectionA = fixture.connectReady("connection-a");
    FakeConnection connectionB = new FakeConnection("connection-b");
    fixture.gateway.open(connectionB);
    // 每个 canonical 名称只绑定一个连接；路由按精确名称寻址。
    fixture.gateway.receive(connectionB.connectionId(), hello(0, OTHER_ENVIRONMENT_NAME));
    fixture.gateway.receive(connectionB.connectionId(), ready(1, OTHER_ENVIRONMENT_NAME));

    RecordingListener listenerA = new RecordingListener();
    RecordingListener listenerB = new RecordingListener();
    // A 尚未 terminal 时 B 仍可发送 INVOKE，证明 active 槽位按 Environment 隔离。
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listenerA);
    fixture.gateway.invoke(OTHER_ENVIRONMENT, request(fixture.descriptor), listenerB);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionA.envelopes()));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connectionB.envelopes()));

    fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofMillis(100));
    fixture.gateway.loadSkill(OTHER_ENVIRONMENT_NAME, "dev", Duration.ofMillis(100));
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
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    fixture.gateway.receive(connection.connectionId(), heartbeat(2));
    assertNotNull(fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().lastSeenAt());
  }

  /**
   * LIST_DIRECTORY 是 control-plane：requestId 关联 DIRECTORY_LISTED 并映射为应用 DTO，不占用 active tool slot。
   */
  @Test
  void listDirectorySendsControlPlaneRequestAndMapsDirectoryListed() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-success");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.LIST_DIRECTORY),
        messageTypes(connection.envelopes()));
    DaemonEnvelope request = connection.envelopes().get(1);
    // 目录控制面不属于 invocation：envelope invocationId 必须为 null，关联 ID 在 payload requestId。
    assertNull(request.invocationId());
    assertTrue(request.payloadJson().contains("\"requestId\":\""));
    assertTrue(request.payloadJson().contains("\"path\":\"src\""));
    String requestId = directoryCodec.decodeRequest(request.payloadJson()).requestId();

    String payload =
        directoryCodec.encodeListed(
            new DaemonDirectoryCodec.DirectoryListed(
                requestId,
                "src",
                "src",
                ".",
                true,
                "main",
                List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src/main"))));

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(ENVIRONMENT_NAME, DaemonMessageType.DIRECTORY_LISTED, null, 2, payload));

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

  /** 目录浏览与 active invocation 并行：不检查、不占用 active tool slot。 */
  @Test
  void listDirectoryRunsConcurrentlyWithActiveInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-parallel");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT, request(fixture.descriptor), listener);

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5));
    assertEquals(
        List.of(
            DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LIST_DIRECTORY),
        messageTypes(connection.envelopes()));

    DaemonEnvelope listRequest = connection.envelopes().get(2);
    String requestId = directoryCodec.decodeRequest(listRequest.payloadJson()).requestId();
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestId, ".", ".", ".", false, null, List.of()))));
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, future.join());

    // active 槽位仍被 invocation 占用：目录浏览不释放它。
    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT,
                request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
                new RecordingListener()));
  }

  /** 非法 wire 路径在发端立即失败为 INVALID_PATH，绝不发送 LIST_DIRECTORY。 */
  @Test
  void listDirectoryRejectsInvalidPathBeforeWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-invalid");

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "../escape", Duration.ofSeconds(5)).join();

    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.INVALID_PATH,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  /** registry 中不存在该 Environment 时立即 Failed(ENVIRONMENT_NOT_FOUND)，不发送 wire。 */
  @Test
  void listDirectoryUnknownEnvironmentFailsWithoutWireSend() {
    Fixture fixture = fixture();

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();

    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_NOT_FOUND,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** Environment 已绑定但未 READY（CONNECTING）时立即 Failed(ENVIRONMENT_UNAVAILABLE)，不发送 wire。 */
  @Test
  void listDirectoryNotReadyEnvironmentFailsWithoutWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-dir-connecting");
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

  /** 心跳租约过期（READY 条目未刷新）视为不可用：Failed(ENVIRONMENT_UNAVAILABLE)，不发送 wire。 */
  @Test
  void listDirectoryExpiredHeartbeatFailsAsUnavailable() {
    Fixture fixture = fixture();
    fixture.connectReady("connection-dir-expired-heartbeat");
    fixture.jdbc.update(
        "update live_environment set last_seen_at = statement_timestamp() - interval '100 seconds', lease_until = statement_timestamp() - interval '1 second' where environment_name = ?",
        ENVIRONMENT_NAME.value());

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();

    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  private String lastListDirectoryRequestId(FakeConnection connection) {
    return directoryCodec
        .decodeRequest(connection.envelopes().get(connection.envelopes().size() - 1).payloadJson())
        .requestId();
  }

  private String lastSkillLoadRequestId(FakeConnection connection) {
    return connection.envelopes().get(connection.envelopes().size() - 1).invocationId();
  }

  /** daemon 无响应时按 timeout 完成 Failed(TIMEOUT) 并清理 pending；随后同连接迟到成功响应被 tombstone 静默丢弃，不关闭连接。 */
  @Test
  void listDirectoryTimeoutThenLateSuccessIsDroppedWithoutDisconnect() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-timeout-late-success");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofMillis(30));
    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.TIMEOUT,
        ((EnvironmentDirectoryListResult.Failed) result).code());

    String requestId = lastListDirectoryRequestId(connection);
    // 迟到成功响应：严格 decode/校验 requestId/path 后按 tombstone 静默丢弃。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestId, ".", ".", ".", false, null, List.of()))));
    assertFalse(connection.closed);

    // 连接仍然健康：下一次请求成功完成。
    CompletableFuture<EnvironmentDirectoryListResult> next =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5));
    String nextRequestId = lastListDirectoryRequestId(connection);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            3,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    nextRequestId, ".", ".", ".", false, null, List.of()))));
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, next.join());
    assertFalse(connection.closed);
  }

  /** timeout 后的迟到失败响应同样被 tombstone 静默丢弃，不关闭连接。 */
  @Test
  void listDirectoryTimeoutThenLateFailureIsDroppedWithoutDisconnect() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-timeout-late-failure");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofMillis(30));
    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.TIMEOUT,
        ((EnvironmentDirectoryListResult.Failed) result).code());

    String requestId = lastListDirectoryRequestId(connection);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LIST_FAILED,
            null,
            2,
            directoryCodec.encodeFailed(
                new DaemonDirectoryCodec.DirectoryListFailed(
                    requestId, ".", DaemonDirectoryFailureCode.NOT_FOUND, "late failure"))));
    assertFalse(connection.closed);
  }

  /** 未知 requestId（既无 pending 也无 tombstone）仍是协议失败：连接关闭。 */
  @Test
  void listDirectoryUnknownRequestIdIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-unknown-request");

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    UUID.randomUUID().toString(), ".", ".", ".", false, null, List.of()))));
    assertTrue(connection.closed);
  }

  /** 断线必须清理 pending 目录请求并按 ENVIRONMENT_UNAVAILABLE 完成，不泄漏 Future。 */
  @Test
  void listDirectoryPendingIsCleanedOnDisconnect() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-drop");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5));
    fixture.gateway.close(connection.connectionId());

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** DIRECTORY_LISTED 回显 path 与请求不一致属于协议失败：连接关闭，且 pending 由断线清理立即以 ENVIRONMENT_UNAVAILABLE 完成。 */
  @Test
  void listDirectoryMismatchedPathIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-mismatch");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));
    String requestId = lastListDirectoryRequestId(connection);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestId, "other", "other", ".", false, null, List.of()))));

    assertTrue(connection.closed);
    // pending 未被提前移走：协议关闭路径立即完成 Future，而不是等待 timeout。
    assertTrue(future.isDone());
    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** malformed DIRECTORY_LISTED（decode 失败）不得把 pending 移走：协议关闭路径立即完成 Future，且无残留。 */
  @Test
  void listDirectoryMalformedPayloadCompletesPendingImmediately() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-malformed");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME, DaemonMessageType.DIRECTORY_LISTED, null, 2, "{\"path\":\"src\"}"));

    assertTrue(connection.closed);
    assertTrue(future.isDone());
    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** 目录回调必须不带 envelope invocationId：携带即协议失败并关闭连接，pending 立即完成。 */
  @Test
  void listDirectoryCallbackWithEnvelopeInvocationIdIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-invocation-id");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));
    String requestId = lastListDirectoryRequestId(connection);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            requestId,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestId, "src", "src", ".", false, null, List.of()))));

    assertTrue(connection.closed);
    assertTrue(future.isDone());
    assertEquals(
        EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
        ((EnvironmentDirectoryListResult.Failed) future.join()).code());
  }

  /** daemon 的 DIRECTORY_LIST_FAILED 分类原样映射为 Failed。 */
  @Test
  void listDirectoryMapsDaemonFailureCode() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-daemon-failure");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "missing", Duration.ofSeconds(5));
    String requestId = lastListDirectoryRequestId(connection);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LIST_FAILED,
            null,
            2,
            directoryCodec.encodeFailed(
                new DaemonDirectoryCodec.DirectoryListFailed(
                    requestId,
                    "missing",
                    DaemonDirectoryFailureCode.NOT_FOUND,
                    "path does not exist"))));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        EnvironmentDirectoryFailureCode.NOT_FOUND,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals("path does not exist", ((EnvironmentDirectoryListResult.Failed) result).message());
  }

  @Test
  void listDirectoryMapsEveryDaemonFailureCode() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-all-failure-codes");
    List<DaemonDirectoryFailureCode> wireCodes = List.of(DaemonDirectoryFailureCode.values());
    List<EnvironmentDirectoryFailureCode> applicationCodes =
        List.of(
            EnvironmentDirectoryFailureCode.INVALID_PATH,
            EnvironmentDirectoryFailureCode.NOT_FOUND,
            EnvironmentDirectoryFailureCode.NOT_DIRECTORY,
            EnvironmentDirectoryFailureCode.IO_ERROR);

    // wire/application failure code 是稳定的一一投影，四种类型都必须保留语义。
    for (int index = 0; index < wireCodes.size(); index++) {
      String path = "missing-" + index;
      CompletableFuture<EnvironmentDirectoryListResult> future =
          fixture.gateway.listDirectory(ENVIRONMENT_NAME, path, Duration.ofSeconds(5));
      String requestId = lastListDirectoryRequestId(connection);
      fixture.gateway.receive(
          connection.connectionId(),
          envelope(
              ENVIRONMENT_NAME,
              DaemonMessageType.DIRECTORY_LIST_FAILED,
              null,
              index + 2L,
              directoryCodec.encodeFailed(
                  new DaemonDirectoryCodec.DirectoryListFailed(
                      requestId, path, wireCodes.get(index), "failure-" + index))));

      EnvironmentDirectoryListResult.Failed failed =
          assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, future.join());
      assertEquals(applicationCodes.get(index), failed.code());
      assertEquals("failure-" + index, failed.message());
    }
    assertFalse(connection.closed);
  }

  /**
   * root 内 symlink alias 的显式请求经 gateway 成功：daemon 按冻结契约回显请求的 canonical wire 路径（alias 本身）而不是 real
   * path，gateway 不得把它当作 path mismatch 协议失败断开连接。
   */
  @Test
  void listDirectorySymlinkAliasInsideRootSucceedsWithoutDisconnect() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-alias");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "alias", Duration.ofSeconds(5));
    DaemonEnvelope request = connection.envelopes().get(1);
    assertTrue(request.payloadJson().contains("\"path\":\"alias\""));
    String requestId = directoryCodec.decodeRequest(request.payloadJson()).requestId();

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestId,
                    "alias",
                    "alias",
                    ".",
                    false,
                    null,
                    List.of(new DaemonDirectoryCodec.DirectoryEntry("child", "alias/child"))))));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    EnvironmentDirectoryDTO dto = ((EnvironmentDirectoryListResult.Loaded) result).listing();
    assertEquals("alias", dto.getPath());
    assertEquals("alias", dto.getDisplayPath());
    assertEquals("alias/child", dto.getEntries().getFirst().getPath());
    assertEquals("child", dto.getEntries().getFirst().getName());
    assertFalse(connection.closed);
  }

  /** 超时 tombstone FIFO 有界：未淘汰前迟到响应静默丢弃；最旧条目被淘汰后其迟到响应重新按未知 requestId 协议失败。 */
  @Test
  void directoryTombstonesAreBoundedAndFifoEvicted() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-tombstone-bounded");

    List<String> requestIds = new ArrayList<>();
    for (int index = 0; index < EnvironmentDaemonGateway.MAX_DIRECTORY_TOMBSTONES; index++) {
      CompletableFuture<EnvironmentDirectoryListResult> future =
          fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofMillis(1));
      assertInstanceOf(
          EnvironmentDirectoryListResult.Failed.class, future.get(5, TimeUnit.SECONDS));
      requestIds.add(
          directoryCodec
              .decodeRequest(connection.envelopes().get(index + 1).payloadJson())
              .requestId());
    }

    // 未淘汰前：最旧 tombstone 的迟到成功响应被静默丢弃，连接保持打开。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestIds.getFirst(), ".", ".", ".", false, null, List.of()))));
    assertFalse(connection.closed);

    // 第 1025 个超时淘汰最旧 tombstone：其迟到响应重新按未知 requestId 协议失败并关闭连接。
    CompletableFuture<EnvironmentDirectoryListResult> overflow =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofMillis(1));
    assertInstanceOf(
        EnvironmentDirectoryListResult.Failed.class, overflow.get(5, TimeUnit.SECONDS));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            null,
            3,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    requestIds.getFirst(), ".", ".", ".", false, null, List.of()))));
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
            INVOCATION_ID.toString(), List.of(new TextToolContent(text)), false, "{}"),
        inlineResourceStore(new byte[0]));
  }

  /** 端侧最小的 daemon 资源 store：仅存/读测试用内存字节。 */
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
    return hello(sequence, ENVIRONMENT_NAME);
  }

  private String hello(long sequence, EnvironmentName environmentName) {
    return helloWithDaemon("d1", sequence, environmentName);
  }

  private String helloWithDaemon(String daemonId, long sequence, EnvironmentName environmentName) {
    return envelope(
        environmentName,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\""
            + daemonId
            + "\",\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithVersion(String protocolVersion, long sequence) {
    return envelope(
        ENVIRONMENT_NAME,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":"
            + protocolVersion
            + ",\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithCatalogVersion(String catalogVersion, long sequence) {
    return envelope(
        ENVIRONMENT_NAME,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"capabilityCatalogVersion\":\""
            + catalogVersion
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String helloWithToken(String token, long sequence) {
    return envelope(
        ENVIRONMENT_NAME,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"capabilityCatalogVersion\":\""
            + EnvironmentCapabilityCatalog.version()
            + "\",\"gatewayToken\":\""
            + token
            + "\"}");
  }

  private String ready(long sequence) {
    return ready(sequence, ENVIRONMENT_NAME);
  }

  private String ready(long sequence, EnvironmentName environmentName) {
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
      EnvironmentName environmentName,
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
    return fixture(environmentName -> {});
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
    final LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry(jdbc, nodeId);
    final AtomicReference<Instant> now = new AtomicReference<>();
    final Duration heartbeatTimeout = Duration.ofSeconds(60);
    final EnvironmentDaemonGateway gateway;
    final EnvironmentCapabilityDescriptor descriptor;

    private Fixture(
        EnvironmentCapabilityDescriptor descriptor, EnvironmentReadyListener readyListener) {
      this.descriptor = descriptor;
      EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
      gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
      gateway =
          new EnvironmentDaemonGateway(
              environmentRegistry,
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
      events.add(((TextToolContent) partial.contents().get(0)).text());
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
