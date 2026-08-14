package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;
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
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolBusyException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gateway 仅承担连接/协议传输职责。durable 的 claim/lease/terminal 由 Harness Runtime 负责。路由使用 HELLO 时确定的
 * canonical EnvironmentName；display name 永不参与路由。
 */
class EnvironmentDaemonGatewayFinalTest {
  private static final EnvironmentName ENVIRONMENT_NAME = new EnvironmentName("env-1");
  private static final EnvironmentName OTHER_ENVIRONMENT_NAME = new EnvironmentName("env-2");
  private static final UUID INVOCATION_ID = new UUID(0L, 9001L);
  private static final UUID SECOND_INVOCATION_ID = new UUID(0L, 9002L);
  private static final UUID THREAD_ID = new UUID(0L, 7001L);
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
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();
  private final DaemonDirectoryCodec directoryCodec = new DaemonDirectoryCodec();

  @Test
  void readyNotifiesHandlerAndInvokeSendsProtocolThenMapsCompletion() {
    List<EnvironmentName> ready = new ArrayList<>();
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
    assertEquals(ENVIRONMENT_NAME, invoke.environmentName());
    assertEquals(INVOCATION_ID.toString(), invoke.invocationId());
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);

    fixture.gateway.receive(connection.connectionId(), partial(2, resultPayload("chunk")));
    assertEquals("chunk", ((TextToolContent) listener.partial.contents().get(0)).text());

    String resourcePayload =
        resultCodec.encodeCompleted(
            new ToolResult(
                INVOCATION_ID.toString(),
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);

    String payload =
        resultCodec.encodeCompleted(
            new ToolResult(
                INVOCATION_ID.toString(),
                List.of(new BinaryToolContent("text/plain", new byte[] {7, 8})),
                false,
                "{}",
                false),
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.CANCELLED,
            INVOCATION_ID.toString(),
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
                ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener()));
  }

  @Test
  void invokeWithDescriptorDriftThrowsUnavailableBeforeWireSend() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-descriptor-drift");
    ToolDescriptor current = fixture.descriptor;
    ToolDescriptor drifted =
        new ToolDescriptor(
            current.name(),
            current.version(),
            current.type(),
            current.description() + " drifted",
            current.rendererKey(),
            current.inputSchema(),
            current.sideEffect(),
            current.timeout());

    assertThrows(
        RemoteToolUnavailableException.class,
        () -> fixture.gateway.invoke(ENVIRONMENT_NAME, request(drifted), new RecordingListener()));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  @Test
  void invalidInvokePayloadDoesNotReserveEnvironmentSlot() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-invalid-payload");

    assertThrows(
        ArithmeticException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME,
                request(fixture.descriptor, Duration.ofSeconds(Long.MAX_VALUE)),
                new RecordingListener()));

    ToolExecutionHandle handle =
        fixture.gateway.invoke(
            ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener());
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), firstListener);

    assertThrows(
        RemoteToolBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME,
                request(fixture.descriptor, SECOND_INVOCATION_ID, "provider-call-2"),
                secondListener));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("first")));
    assertEquals("first", ((TextToolContent) firstListener.completed.contents().getFirst()).text());
    assertNull(secondListener.completed);
    assertNull(secondListener.error);

    ToolExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT_NAME,
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
    ToolExecutionHandle first =
        fixture.gateway.invoke(
            ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener());
    first.cancel();

    assertThrows(
        RemoteToolBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME,
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

    ToolExecutionHandle next =
        fixture.gateway.invoke(
            ENVIRONMENT_NAME,
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
          public void onComplete(ToolResult result) {
            super.onComplete(result);
            // 在完成时重新进入 close 路径；不能在 ConnectionState 锁内执行。
            fixture.gateway.close(connection.connectionId());
            completeSawLockFree.set(true);
          }
        };
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
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
        () -> fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener));
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
        RemoteToolSendUncertainException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener()));
    assertTrue(connection.closed);
    assertFalse(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
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

    fixture.gateway.receive(connection.connectionId(), completed(2, resultPayload("done")));
    int envelopesAfterComplete = connection.envelopes().size();
    handle.cancel();
    assertEquals(envelopesAfterComplete, connection.envelopes().size());
    assertEquals("done", ((TextToolContent) listener.completed.contents().get(0)).text());
  }

  @Test
  void connectingDoesNotFabricateCapabilitiesAndReadyPublishesMetadata() {
    Fixture fixture = fixture();
    FakeConnection connection = new FakeConnection("connection-metadata");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));

    var connecting = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(LiveEnvironmentStatus.CONNECTING, connecting.status());
    assertNull(connecting.capabilities());
    assertTrue(connecting.skills().isEmpty());
    assertTrue(connecting.mcpServers().isEmpty());

    fixture.gateway.receive(connection.connectionId(), ready(1, ENVIRONMENT_NAME));
    var ready = fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow();
    assertEquals(LiveEnvironmentStatus.READY, ready.status());
    assertEquals(ADVERTISED_CAPABILITIES, ready.capabilities());
  }

  @Test
  void readyRegistersAdvertisedSkillsAndStaticTools() {
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
    // 工具来自静态 EnvironmentToolCatalog；wire READY 不会对外声明它们。
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
  void sameNameConcurrentBindIsTerminalConflictWithTypedCode() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-first");
    FakeConnection second = new FakeConnection("connection-second");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0, ENVIRONMENT_NAME));
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);
    CompletableFuture<EnvironmentSkillLoadResult> pendingSkill =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5));
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LOAD_SKILL),
        messageTypes(first.envelopes()));

    // 心跳超过配置超时：连接仍打开，但租约过期——HELLO 原子接管而非冲突。
    fixture.now.set(NOW.plus(fixture.heartbeatTimeout).plusSeconds(1));
    FakeConnection second = new FakeConnection("connection-lease-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    // 被替换的旧连接恰好关闭一次；active remote 与 pending skill load 不泄漏。
    assertEquals(1, first.closeCount);
    assertTrue(listener.error instanceof RemoteToolSendUncertainException);
    assertTrue(pendingSkill.join() instanceof EnvironmentSkillLoadResult.Failed);
    assertEquals(
        second.connectionId(),
        fixture
            .environmentRegistry
            .find(ENVIRONMENT_NAME)
            .orElseThrow()
            .connection()
            .connectionId());

    // 新 holder 完整可用：READY 后 invoke 路由到新连接。
    fixture.gateway.receive(second.connectionId(), ready(1));
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    RecordingListener secondListener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), secondListener);
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(second.envelopes()));

    // 旧连接的迟到 close 回调不得影响新 holder。
    fixture.gateway.close(first.connectionId());
    assertEquals(1, first.closeCount);
    assertTrue(second.isOpen());
    assertEquals(
        second.connectionId(),
        fixture
            .environmentRegistry
            .find(ENVIRONMENT_NAME)
            .orElseThrow()
            .connection()
            .connectionId());
  }

  @Test
  void closedHolderConnectionIsTakenOverAndFreshConnectingClaimIsNeverStolen() {
    Fixture fixture = fixture();
    FakeConnection first = fixture.connectReady("connection-dead");
    // transport 死亡但 gateway/registry 尚未清理（例如 close 事件丢失）。
    first.close();

    FakeConnection second = new FakeConnection("connection-taker");
    fixture.gateway.open(second);
    fixture.gateway.receive(second.connectionId(), hello(0));
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(second.envelopes()));
    assertEquals(
        2, first.closeCount, "displaced connection must be closed exactly once by takeover");
    assertEquals(
        second.connectionId(),
        fixture
            .environmentRegistry
            .find(ENVIRONMENT_NAME)
            .orElseThrow()
            .connection()
            .connectionId());

    // CONNECTING 的新鲜声明（打开 + 心跳未过期）绝不能被抢走：typed 冲突。
    FakeConnection third = new FakeConnection("connection-sneaky");
    fixture.gateway.open(third);
    fixture.gateway.receive(third.connectionId(), hello(0));
    assertTrue(third.closed);
    assertEquals(
        DaemonProtocol.ERROR_CODE_ENVIRONMENT_NAME_CONFLICT,
        envelopeCodec.readPayload(third.envelopes().get(0)).path("code").asText());
    assertEquals(
        second.connectionId(),
        fixture
            .environmentRegistry
            .find(ENVIRONMENT_NAME)
            .orElseThrow()
            .connection()
            .connectionId());
    assertTrue(second.isOpen());
  }

  @Test
  void loadSkillUsesSameHeartbeatFreshnessRuleAsInvokeAndQuery() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-skill-stale");
    assertTrue(
        fixture.environmentRegistry.isReady(
            ENVIRONMENT_NAME, fixture.now.get(), fixture.heartbeatTimeout));
    fixture.now.set(NOW.plus(fixture.heartbeatTimeout).plusSeconds(1));

    // 心跳过期：与 invoke/query 完全相同的规则下立即 Failed，绝不发送 LOAD_SKILL。
    EnvironmentSkillLoadResult result =
        fixture.gateway.loadSkill(ENVIRONMENT_NAME, "dev", Duration.ofSeconds(5)).join();
    assertInstanceOf(EnvironmentSkillLoadResult.Failed.class, result);
    assertThrows(
        RemoteToolUnavailableException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME, request(fixture.descriptor), new RecordingListener()));
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
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
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
    assertTrue(fixture.environmentRegistry.find(ENVIRONMENT_NAME).isEmpty());
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
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listenerA);
    fixture.gateway.invoke(OTHER_ENVIRONMENT_NAME, request(fixture.descriptor), listenerB);
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
    Instant heartbeatAt = NOW.plusSeconds(10);
    fixture.now.set(heartbeatAt);
    fixture.gateway.receive(connection.connectionId(), heartbeat(2));
    assertEquals(
        heartbeatAt, fixture.environmentRegistry.find(ENVIRONMENT_NAME).orElseThrow().lastSeenAt());
  }

  /**
   * LIST_DIRECTORY 是 control-plane：requestId 关联 DIRECTORY_LISTED 并映射为应用 DTO，不占用 active tool slot。
   */
  @Test
  void listDirectorySendsControlPlaneRequestAndMapsDirectoryListed() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-success");
    String payload =
        directoryCodec.encodeListed(
            new DaemonDirectoryCodec.DirectoryListed(
                "src",
                "src",
                ".",
                true,
                "main",
                List.of(new DaemonDirectoryCodec.DirectoryEntry("src/main", "main"))));

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.LIST_DIRECTORY),
        messageTypes(connection.envelopes()));
    DaemonEnvelope request = connection.envelopes().get(1);
    assertNotNull(request.invocationId());
    assertTrue(request.payloadJson().contains("\"path\":\"src\""));

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            request.invocationId(),
            2,
            payload));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, result);
    EnvironmentDirectoryDTO dto = ((EnvironmentDirectoryListResult.Loaded) result).listing();
    assertEquals("src", dto.getPath());
    assertEquals("src", dto.getDisplayPath());
    assertEquals(".", dto.getParentPath());
    assertTrue(dto.isTruncated());
    assertEquals("main", dto.getGitBranch());
    assertEquals(1, dto.getEntries().size());
    assertEquals("src/main", dto.getEntries().getFirst().getPath());
    assertEquals("main", dto.getEntries().getFirst().getDisplayPath());
  }

  /** 目录浏览与 active invocation 并行：不检查、不占用 active tool slot。 */
  @Test
  void listDirectoryRunsConcurrentlyWithActiveInvocation() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-parallel");
    RecordingListener listener = new RecordingListener();
    fixture.gateway.invoke(ENVIRONMENT_NAME, request(fixture.descriptor), listener);

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5));
    assertEquals(
        List.of(
            DaemonMessageType.WELCOME, DaemonMessageType.INVOKE, DaemonMessageType.LIST_DIRECTORY),
        messageTypes(connection.envelopes()));

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            connection.envelopes().get(2).invocationId(),
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(".", ".", ".", false, null, List.of()))));
    assertInstanceOf(EnvironmentDirectoryListResult.Loaded.class, future.join());

    // active 槽位仍被 invocation 占用：目录浏览不释放它。
    assertThrows(
        RemoteToolBusyException.class,
        () ->
            fixture.gateway.invoke(
                ENVIRONMENT_NAME,
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
        DaemonDirectoryFailureCode.INVALID_PATH,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(connection.envelopes()));
  }

  /** Environment 不存在或未 READY 时立即 Failed(OFFLINE)，不发送 wire。 */
  @Test
  void listDirectoryOfflineFailsWithoutWireSend() {
    Fixture fixture = fixture();

    EnvironmentDirectoryListResult result =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofSeconds(5)).join();

    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        DaemonDirectoryFailureCode.OFFLINE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** daemon 无响应时按 timeout 完成 Failed(TIMEOUT)，并清理 pending；迟到响应按协议失败关闭连接。 */
  @Test
  void listDirectoryTimeoutFailsAndCleansPending() throws Exception {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-timeout");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, ".", Duration.ofMillis(30));
    EnvironmentDirectoryListResult result = future.get(5, TimeUnit.SECONDS);

    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        DaemonDirectoryFailureCode.TIMEOUT,
        ((EnvironmentDirectoryListResult.Failed) result).code());

    // 迟到的 DIRECTORY_LISTED 找不到 pending：协议失败并关闭连接。
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            connection.envelopes().get(1).invocationId(),
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(".", ".", ".", false, null, List.of()))));
    assertTrue(connection.closed);
  }

  /** 断线必须清理 pending 目录请求并按 OFFLINE 完成，不泄漏 Future。 */
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
        DaemonDirectoryFailureCode.OFFLINE,
        ((EnvironmentDirectoryListResult.Failed) result).code());
  }

  /** DIRECTORY_LISTED 回显 path 与请求不一致属于协议失败：连接关闭且 Future 不完成。 */
  @Test
  void listDirectoryMismatchedPathIsProtocolFailure() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-mismatch");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "src", Duration.ofSeconds(5));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LISTED,
            connection.envelopes().get(1).invocationId(),
            2,
            directoryCodec.encodeListed(
                new DaemonDirectoryCodec.DirectoryListed(
                    "other", "other", ".", false, null, List.of()))));

    assertTrue(connection.closed);
    assertFalse(future.isDone());
  }

  /** daemon 的 DIRECTORY_LIST_FAILED 分类原样映射为 Failed。 */
  @Test
  void listDirectoryMapsDaemonFailureCode() {
    Fixture fixture = fixture();
    FakeConnection connection = fixture.connectReady("connection-dir-daemon-failure");

    CompletableFuture<EnvironmentDirectoryListResult> future =
        fixture.gateway.listDirectory(ENVIRONMENT_NAME, "missing", Duration.ofSeconds(5));
    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            ENVIRONMENT_NAME,
            DaemonMessageType.DIRECTORY_LIST_FAILED,
            connection.envelopes().get(1).invocationId(),
            2,
            directoryCodec.encodeFailed(
                new DaemonDirectoryCodec.DirectoryListFailed(
                    "missing", DaemonDirectoryFailureCode.NOT_FOUND, "path does not exist"))));

    EnvironmentDirectoryListResult result = future.join();
    assertInstanceOf(EnvironmentDirectoryListResult.Failed.class, result);
    assertEquals(
        DaemonDirectoryFailureCode.NOT_FOUND,
        ((EnvironmentDirectoryListResult.Failed) result).code());
    assertEquals("path does not exist", ((EnvironmentDirectoryListResult.Failed) result).message());
  }

  private ToolExecutionRequest request(ToolDescriptor descriptor) {
    return request(descriptor, Duration.ofSeconds(30));
  }

  private ToolExecutionRequest request(ToolDescriptor descriptor, Duration timeout) {
    return request(descriptor, timeout, INVOCATION_ID, "provider-call");
  }

  private ToolExecutionRequest request(
      ToolDescriptor descriptor, UUID invocationId, String toolCallId) {
    return request(descriptor, Duration.ofSeconds(30), invocationId, toolCallId);
  }

  private ToolExecutionRequest request(
      ToolDescriptor descriptor, Duration timeout, UUID invocationId, String toolCallId) {
    return new ToolExecutionRequest(
        descriptor,
        new ToolCall(toolCallId, descriptor.name(), "{\"path\":\"README.md\"}"),
        timeout,
        new ToolExecutionContext(invocationId, THREAD_ID));
  }

  private String resultPayload(String text) {
    return resultCodec.encodeCompleted(
        new ToolResult(
            INVOCATION_ID.toString(), List.of(new TextToolContent(text)), false, "{}", false),
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
    return envelope(
        environmentName,
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"d1\",\"protocolVersion\":3,\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
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
            + ",\"toolCatalogVersion\":\""
            + EnvironmentToolCatalog.version()
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
        "{\"daemonId\":\"d1\",\"protocolVersion\":3,\"toolCatalogVersion\":\""
            + catalogVersion
            + "\",\"gatewayToken\":\""
            + GATEWAY_TOKEN
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
            DaemonProtocol.VERSION_3,
            messageType,
            environmentName,
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
    return EnvironmentToolCatalog.require("read");
  }

  private final class Fixture {
    final LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry();
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    final Duration heartbeatTimeout = Duration.ofSeconds(60);
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
    private int closeCount;
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
      closeCount += 1;
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
