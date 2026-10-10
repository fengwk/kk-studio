package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalRoute;
import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalRouteSource;
import fun.fengwk.kkstudio.harness.environment.server.terminal.ShellTopics;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDelivery;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDispatch;
import fun.fengwk.kkstudio.harness.environment.terminal.AdmissionResult;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.OperationDigest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalStatus;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate;
import fun.fengwk.kkstudio.harness.environment.terminal.WriterState;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** ShellGateway 的定向测试：Bus 唯一路径、READY 路由解析与 owner 分发、两种确定未执行、显示/操作围栏、resync 冻结不重试、scope 有界与关闭清理。 */
class ShellGatewayTest {

  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID OWNER_TWO = UUID.fromString("00000000-0000-0000-0000-000000000012");
  private static final UUID ENVIRONMENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID LEASE_TWO = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID DAEMON = UUID.fromString("00000000-0000-0000-0000-000000000008");
  private static final UUID DAEMON_TWO = UUID.fromString("00000000-0000-0000-0000-000000000009");
  private static final UUID DAEMON_OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000d");
  private static final UUID TERMINAL = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID TERMINAL_B = UUID.fromString("00000000-0000-0000-0000-00000000000e");
  private static final UUID TERMINAL_C = UUID.fromString("00000000-0000-0000-0000-00000000000f");
  private static final UUID STREAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID STREAM_B = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID STREAM_C = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final String CONNECTION = "conn-1";

  private RecordingNotificationBus bus;
  private MutableRouteSource routeSource;
  private EnvironmentDaemonServer daemonServer;
  private ShellGateway gateway;
  private final List<TerminalEvent> sink = new ArrayList<>();
  private final int[] resyncCount = {0};

  @BeforeEach
  void setUp() {
    bus = new RecordingNotificationBus(NODE);
    routeSource = new MutableRouteSource();
    daemonServer = mock(EnvironmentDaemonServer.class);
    // 默认 owner 侧接受：只有显式覆盖 CLOSED/BUSY 的测试才应产生未执行错误。
    when(daemonServer.sendShell(any())).thenReturn(DaemonOfferResult.ACCEPTED);
    gateway = new ShellGateway(bus, routeSource, daemonServer, NODE, Runnable::run);
  }

  @Test
  void openPublishesDispatchToAuthoritativeOwnerOnly() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    List<RecordingNotificationBus.Publication> commands =
        bus.publications(ShellTopics.COMMAND.name());
    assertEquals(1, commands.size());
    assertEquals(OWNER, commands.get(0).address().nodeId());
    TerminalDispatch dispatch = (TerminalDispatch) commands.get(0).payload();
    assertEquals(LEASE, dispatch.leaseToken());
    assertEquals(NODE, dispatch.request().route().appNodeId());
    assertEquals(CONNECTION, dispatch.request().route().connectionId());
    assertEquals(ENVIRONMENT, dispatch.request().command().environmentId());
  }

  @Test
  void localOwnerIsStillDeliveredThroughBusAndCallsSendShellOnce() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    when(daemonServer.sendShell(any())).thenReturn(DaemonOfferResult.ACCEPTED);
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    ArgumentCaptor<TerminalDispatch> captor = ArgumentCaptor.forClass(TerminalDispatch.class);
    verify(daemonServer, times(1)).sendShell(captor.capture());
    assertEquals(LEASE, captor.getValue().leaseToken());
    assertEquals(List.of(), sink, "ACCEPTED must not emit any local rejection");
  }

  @Test
  void ownerClosedReturnsFixedNotExecutedErrorWithNullDaemonInstance() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    when(daemonServer.sendShell(any())).thenReturn(DaemonOfferResult.CLOSED);
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    TerminalEvent event = assertSingleError(ErrorCode.ROUTE_UNAVAILABLE);
    assertNull(event.identity(), "unauthenticated NOT_EXECUTED carries no terminal identity");
    TerminalDelivery delivery =
        (TerminalDelivery) bus.publications(ShellTopics.EVENT.name()).get(0).payload();
    assertNull(
        delivery.daemonInstanceId(),
        "pre-delivery CLOSED means no authenticated Daemon, so daemonInstanceId is null");
  }

  @Test
  void ownerBusyReturnsFixedBackpressureNotExecutedError() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    when(daemonServer.sendShell(any())).thenReturn(DaemonOfferResult.BUSY);
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    assertSingleError(ErrorCode.BACKPRESSURE);
  }

  @Test
  void missingReadyRouteEmitsLocalNotExecutedWithoutPublishing() {
    routeSource.route = Optional.empty();
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    TerminalEvent event = assertSingleError(ErrorCode.ROUTE_UNAVAILABLE);
    assertNull(event.identity());
    assertTrue(
        bus.publications(ShellTopics.COMMAND.name()).isEmpty(),
        "route lookup failure before publish must not touch the command topic");
  }

  @Test
  void routeLookupFailureEmitsLocalNotExecuted() {
    routeSource.failure = new IllegalStateException("pg down");
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    assertSingleError(ErrorCode.ROUTE_UNAVAILABLE);
    assertTrue(bus.publications(ShellTopics.COMMAND.name()).isEmpty());
  }

  @Test
  void deliveryFencesOldLeaseOwnerDaemonTerminalAndDisplayStream() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    UUID request = UUID.randomUUID();
    connection.receive(openCommand(request));
    // 绑定：匹配本次 OPEN 请求的 ATTACHED 建立 terminal identity + display stream。
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    assertEquals(1, sink.size());
    sink.clear();

    // 旧 lease、同 lease 但错误 owner、旧 daemon、旧 terminal 的显示事件都被丢弃。
    deliver(exited(OWNER, LEASE_TWO, DAEMON, identity(DAEMON, TERMINAL), request));
    deliver(exited(OWNER_TWO, LEASE, DAEMON, identity(DAEMON, TERMINAL), request));
    deliver(exited(OWNER, LEASE, DAEMON_TWO, identity(DAEMON, TERMINAL), request));
    deliver(exited(OWNER, LEASE, DAEMON, identity(DAEMON, UUID.randomUUID()), request));
    // 显示事件必须匹配已 attach 的 stream。
    deliver(
        viewUpdate(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), UUID.randomUUID(), request));
    assertEquals(0, sink.size());

    // 同绑定的显示事件与 OP_ACK（不绑显示 stream）都能通过。
    deliver(viewUpdate(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    deliver(opAck(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), request));
    assertEquals(2, sink.size());
    sink.clear();

    // 无认证 Daemon 的确定未执行错误（daemonInstanceId=null）在已绑定 scope 上仍可见。
    deliver(unauthenticatedNotExecuted(LEASE, UUID.randomUUID()));
    assertEquals(1, sink.size());
  }

  @Test
  void deliveryFromWrongOwnerNodeWithSameLeaseIsRejected() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    UUID request = UUID.randomUUID();
    connection.receive(openCommand(request));
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    sink.clear();

    // 仅 lease 相同但 ownerNodeId 不是权威 owner：必须按权威 owner 围栏丢弃。
    deliver(exited(OWNER_TWO, LEASE, DAEMON, identity(DAEMON, TERMINAL), request));

    assertEquals(0, sink.size());
  }

  @Test
  void consecutiveAttachOnlyCurrentRequestBinds() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    TerminalIdentity first = identity(DAEMON, TERMINAL);
    TerminalIdentity second = identity(DAEMON, TERMINAL_B);
    UUID firstRequest = UUID.randomUUID();
    connection.receive(attachCommand(firstRequest, first));
    UUID secondRequest = UUID.randomUUID();
    connection.receive(attachCommand(secondRequest, second));

    // 乱序：被新 ATTACH 取代的旧请求 ATTACHED 不得改流。
    deliver(attached(OWNER, LEASE, DAEMON, first, STREAM, firstRequest));
    assertEquals(0, sink.size());

    // 本次 ATTACHED 建立绑定。
    deliver(attached(OWNER, LEASE, DAEMON, second, STREAM_B, secondRequest));
    assertEquals(1, sink.size());
    sink.clear();

    // 旧绑定显示流不再可用。
    deliver(viewUpdate(OWNER, LEASE, DAEMON, first, STREAM, firstRequest));
    assertEquals(0, sink.size());
  }

  @Test
  void openAcceptsNewTerminalIdentityWhileAttachRequiresDeclaredIdentity() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    UUID openRequest = UUID.randomUUID();
    connection.receive(openCommand(openRequest));

    // OPEN 允许新的 terminal identity。
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, openRequest));
    assertEquals(1, sink.size());
    sink.clear();

    // ATTACH 只接受声明 identity。
    TerminalIdentity declared = identity(DAEMON, TERMINAL_B);
    UUID attachRequest = UUID.randomUUID();
    connection.receive(attachCommand(attachRequest, declared));
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL_C), STREAM_C, attachRequest));
    assertEquals(0, sink.size());
    deliver(attached(OWNER, LEASE, DAEMON, declared, STREAM_B, attachRequest));
    assertEquals(1, sink.size());
  }

  @Test
  void lateOriginalBindingErrorIsDroppedButCurrentAttachErrorIsVisible() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    TerminalIdentity original = identity(DAEMON, TERMINAL);
    UUID originalRequest = UUID.randomUUID();
    connection.receive(attachCommand(originalRequest, original));
    deliver(attached(OWNER, LEASE, DAEMON, original, STREAM, originalRequest));
    sink.clear();

    TerminalIdentity requested = identity(DAEMON_OTHER, TERMINAL_B);
    UUID attachRequest = UUID.randomUUID();
    connection.receive(attachCommand(attachRequest, requested));

    // 原绑定终端的迟到错误（identity 不同）必须丢弃，不能复活旧绑定。
    deliver(error(OWNER, LEASE, DAEMON, original, originalRequest, ErrorCode.TERMINAL_NOT_FOUND));
    assertEquals(0, sink.size());

    // 当前 ATTACH 的 DAEMON_MISMATCH 错误必须可见（错误 identity 是当前 daemon 而非声明 identity）。
    TerminalIdentity errorIdentity = identity(DAEMON, TERMINAL_B);
    deliver(error(OWNER, LEASE, DAEMON, errorIdentity, attachRequest, ErrorCode.DAEMON_MISMATCH));
    assertEquals(1, sink.size());
    TerminalEvent errorEvent = sink.get(0);
    assertEquals(
        ErrorCode.DAEMON_MISMATCH,
        assertInstanceOf(TerminalEvent.ErrorPayload.class, errorEvent.payload()).code());
    sink.clear();

    // 错误携带的 identity 绝不建立新 stream：后续该 identity 的 VIEW_UPDATE 仍被丢弃。
    deliver(viewUpdate(OWNER, LEASE, DAEMON, errorIdentity, STREAM_B, attachRequest));
    assertEquals(0, sink.size());
  }

  @Test
  void resyncFreezesBindingsAndNeverRetriesSideEffects() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection =
        gateway.open(CONNECTION, sink::add, () -> resyncCount[0]++);
    UUID request = UUID.randomUUID();
    connection.receive(openCommand(request));
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    sink.clear();
    int commandsBefore = bus.publications(ShellTopics.COMMAND.name()).size();

    bus.resync(ShellTopics.EVENT);

    assertEquals(1, resyncCount[0], "connection recovery callback must fire exactly once");
    assertEquals(
        commandsBefore,
        bus.publications(ShellTopics.COMMAND.name()).size(),
        "resync must not retry any side effect");
    // 冻结后旧绑定回执被丢弃。
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    assertEquals(0, sink.size());
  }

  @Test
  void busResyncInvalidatesInFlightRouteLookup() {
    ManualExecutor executor = new ManualExecutor();
    gateway = new ShellGateway(bus, routeSource, daemonServer, NODE, executor);
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection =
        gateway.open(CONNECTION, sink::add, () -> resyncCount[0]++);

    connection.receive(openCommand(UUID.randomUUID())); // lookup 尚未完成
    bus.resync(ShellTopics.EVENT); // resync 递增代次
    executor.runAll();

    assertTrue(
        bus.publications(ShellTopics.COMMAND.name()).isEmpty(),
        "a lookup completing after resync must not publish or revive the cache");
    assertEquals(1, resyncCount[0]);
  }

  @Test
  void scopeCapRejectsNewScopesWithNotExecuted() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    for (int i = 0; i < ShellGateway.MAX_SCOPES_PER_CONNECTION; i++) {
      connection.receive(openWithViewer(UUID.randomUUID()));
    }
    assertEquals(0, sink.size());
    assertEquals(
        ShellGateway.MAX_SCOPES_PER_CONNECTION,
        bus.publications(ShellTopics.COMMAND.name()).size());

    connection.receive(openWithViewer(UUID.randomUUID()));

    // 第 65 个 scope 被容量拒绝：只回确定未执行的 BACKPRESSURE。
    assertEquals(1, sink.size());
    TerminalEvent.ErrorPayload error =
        assertInstanceOf(TerminalEvent.ErrorPayload.class, sink.get(0).payload());
    assertEquals(ErrorCode.BACKPRESSURE, error.code());
    assertEquals(ErrorDisposition.NOT_EXECUTED, error.disposition());
  }

  @Test
  void closeDropsBindingsAndEmitsDetachNotClose() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    UUID request = UUID.randomUUID();
    connection.receive(openCommand(request));
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    sink.clear();
    int commandsBefore = bus.publications(ShellTopics.COMMAND.name()).size();

    connection.close();

    List<RecordingNotificationBus.Publication> commands =
        bus.publications(ShellTopics.COMMAND.name());
    assertEquals(commandsBefore + 1, commands.size(), "close must emit exactly one DETACH");
    for (RecordingNotificationBus.Publication publication : commands) {
      TerminalDispatch published = (TerminalDispatch) publication.payload();
      assertFalse(
          published.request().command().payload() instanceof TerminalCommand.Close,
          "close must never CLOSE the PTY");
    }
    TerminalDispatch detach = (TerminalDispatch) commands.get(commands.size() - 1).payload();
    assertInstanceOf(TerminalCommand.Detach.class, detach.request().command().payload());
    // 关闭后回执不再投递。
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, request));
    assertEquals(0, sink.size());
  }

  @Test
  void staleAsyncLookupDoesNotReviveClosedConnectionCache() {
    ManualExecutor executor = new ManualExecutor();
    gateway = new ShellGateway(bus, routeSource, daemonServer, NODE, executor);
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));
    connection.close();
    executor.runAll();

    assertTrue(
        bus.publications(ShellTopics.COMMAND.name()).isEmpty(),
        "a lookup completing after close must not publish or revive the cache");
  }

  @Test
  void reopeningSameConnectionIdKeepsTheNewStateRegistered() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection first = gateway.open(CONNECTION, sink::add, () -> {});
    first.receive(openCommand(UUID.randomUUID()));

    ShellGateway.Connection second = gateway.open(CONNECTION, sink::add, () -> {});
    UUID secondRequest = UUID.randomUUID();
    second.receive(openCommand(secondRequest));

    // 旧会话被幂等关闭，但绝不影响同 id 的新会话注册。
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, secondRequest));
    assertEquals(1, sink.size(), "the replacement connection must still receive deliveries");
    sink.clear();

    second.close();
    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, secondRequest));
    assertEquals(0, sink.size(), "a closed connection must release its gateway registration");
  }

  @Test
  void gatewayCloseReleasesSubscriptionsAndConnections() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    gateway.open(CONNECTION, sink::add, () -> {}).receive(openCommand(UUID.randomUUID()));

    gateway.close();

    deliver(attached(OWNER, LEASE, DAEMON, identity(DAEMON, TERMINAL), STREAM, UUID.randomUUID()));
    assertEquals(0, sink.size(), "a closed gateway must not route events to any connection");
  }

  @Test
  void sendShellFailureResyncsKnownLocalConnectionAndPropagates() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    when(daemonServer.sendShell(any())).thenThrow(new IllegalStateException("transport down"));
    gateway.open(CONNECTION, sink::add, () -> resyncCount[0]++);

    // owner 侧经 Bus 收到已 publish 的命令：未知失败不得伪报「未执行」，异常必须交回 NotificationBus 恢复处理。
    TerminalCommand command = openCommand(UUID.randomUUID());
    TerminalDispatch dispatch =
        new TerminalDispatch(
            LEASE, new TerminalRequest(new TerminalRoute(NODE, CONNECTION), command));
    assertThrows(
        IllegalStateException.class,
        () -> bus.publish(ShellTopics.COMMAND, NotificationAddress.node(NODE), dispatch));

    assertEquals(0, sink.size());
    assertTrue(bus.publications(ShellTopics.EVENT.name()).isEmpty());
    assertEquals(1, resyncCount[0], "the known local originating connection must be resynced");
  }

  @Test
  void foreignAppNodeDeliveryIsIgnored() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(NODE, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    connection.receive(openCommand(UUID.randomUUID()));

    deliverFromAppNode(OWNER, identity(DAEMON, TERMINAL), exitedPayload(), UUID.randomUUID());

    assertEquals(0, sink.size(), "a delivery addressed to another App node must be ignored");
  }

  @Test
  void receiveAfterCloseIsIgnored() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    connection.close();

    connection.receive(openCommand(UUID.randomUUID()));

    assertTrue(bus.publications(ShellTopics.COMMAND.name()).isEmpty());
  }

  @Test
  void cachedRouteIsReusedForLaterNonOpenCommands() {
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});
    connection.receive(openCommand(UUID.randomUUID()));

    // 非 OPEN/ATTACH 命令复用已缓存路由，不再重新查库。
    TerminalCommand detach =
        new TerminalCommand(
            UUID.randomUUID(),
            ENVIRONMENT,
            VIEWER,
            new TerminalCommand.Detach(identity(DAEMON, TERMINAL), STREAM));
    connection.receive(detach);

    List<RecordingNotificationBus.Publication> commands =
        bus.publications(ShellTopics.COMMAND.name());
    assertEquals(2, commands.size());
    assertEquals(LEASE, ((TerminalDispatch) commands.get(1).payload()).leaseToken());
    assertInstanceOf(
        TerminalCommand.Detach.class,
        ((TerminalDispatch) commands.get(1).payload()).request().command().payload());
  }

  @Test
  void rejectedRouteExecutorEmitsLocalNotExecuted() {
    gateway =
        new ShellGateway(
            bus,
            routeSource,
            daemonServer,
            NODE,
            command -> {
              throw new RejectedExecutionException("saturated");
            });
    routeSource.route = Optional.of(new EnvironmentTerminalRoute(OWNER, LEASE));
    ShellGateway.Connection connection = gateway.open(CONNECTION, sink::add, () -> {});

    connection.receive(openCommand(UUID.randomUUID()));

    TerminalEvent event = assertSingleError(ErrorCode.BACKPRESSURE);
    assertNull(event.identity());
    assertTrue(bus.publications(ShellTopics.COMMAND.name()).isEmpty());
  }

  private TerminalEvent assertSingleError(ErrorCode code) {
    assertEquals(1, sink.size());
    TerminalEvent event = sink.get(0);
    assertEquals(ENVIRONMENT, event.environmentId());
    assertEquals(VIEWER, event.viewerId());
    TerminalEvent.ErrorPayload error =
        assertInstanceOf(TerminalEvent.ErrorPayload.class, event.payload());
    assertEquals(code, error.code());
    assertEquals(ErrorDisposition.NOT_EXECUTED, error.disposition());
    return event;
  }

  private void deliver(TerminalDelivery delivery) {
    bus.publish(ShellTopics.EVENT, NotificationAddress.node(NODE), delivery);
  }

  /** 以指定路由 appNodeId 发布一条回执：用于验证发给其他 App 节点的事件被忽略。 */
  private void deliverFromAppNode(
      UUID appNodeId, TerminalIdentity identity, TerminalEvent.Payload payload, UUID requestId) {
    TerminalEvent event = new TerminalEvent(requestId, ENVIRONMENT, VIEWER, identity, payload);
    TerminalDelivery delivery =
        new TerminalDelivery(
            OWNER,
            LEASE,
            DAEMON,
            new TerminalResponse(new TerminalRoute(appNodeId, CONNECTION), event));
    bus.publish(ShellTopics.EVENT, NotificationAddress.node(NODE), delivery);
  }

  private static TerminalEvent.Payload exitedPayload() {
    return new TerminalEvent.Exited(TerminalStatus.EXITED, 0);
  }

  private static TerminalIdentity identity(UUID daemon, UUID terminal) {
    return new TerminalIdentity(daemon, terminal);
  }

  private static TerminalCommand openCommand(UUID requestId) {
    return new TerminalCommand(requestId, ENVIRONMENT, VIEWER, new TerminalCommand.Open(null));
  }

  private static TerminalCommand attachCommand(UUID requestId, TerminalIdentity declared) {
    return new TerminalCommand(
        requestId, ENVIRONMENT, VIEWER, new TerminalCommand.Attach(declared));
  }

  private TerminalCommand openWithViewer(UUID viewer) {
    return new TerminalCommand(
        UUID.randomUUID(), ENVIRONMENT, viewer, new TerminalCommand.Open(null));
  }

  private static TerminalDelivery attached(
      UUID owner, UUID lease, UUID daemon, TerminalIdentity identity, UUID stream, UUID requestId) {
    WriterState writer = new WriterState(null, 0L, null, 0L, null, null, 0L, null, false);
    TerminalEvent.Attached payload =
        new TerminalEvent.Attached(stream, "/bin/bash", TerminalStatus.RUNNING, null, 1L, writer);
    return delivery(owner, lease, daemon, identity, payload, requestId);
  }

  private static TerminalDelivery exited(
      UUID owner, UUID lease, UUID daemon, TerminalIdentity identity, UUID requestId) {
    return delivery(
        owner,
        lease,
        daemon,
        identity,
        new TerminalEvent.Exited(TerminalStatus.EXITED, 0),
        requestId);
  }

  private static TerminalDelivery viewUpdate(
      UUID owner, UUID lease, UUID daemon, TerminalIdentity identity, UUID stream, UUID requestId) {
    TerminalViewUpdate update = resetView(identity.terminalId(), stream);
    return delivery(
        owner, lease, daemon, identity, new TerminalEvent.ViewUpdate(update), requestId);
  }

  private static TerminalDelivery opAck(
      UUID owner, UUID lease, UUID daemon, TerminalIdentity identity, UUID requestId) {
    AdmissionResult result =
        AdmissionResult.pending(1L, OperationDigest.of(new byte[OperationDigest.LENGTH]));
    TerminalEvent.OpAck payload =
        new TerminalEvent.OpAck(identity.daemonInstanceId(), result, null);
    return delivery(owner, lease, daemon, identity, payload, requestId);
  }

  private static TerminalDelivery error(
      UUID owner,
      UUID lease,
      UUID daemon,
      TerminalIdentity identity,
      UUID requestId,
      ErrorCode code) {
    return delivery(
        owner,
        lease,
        daemon,
        identity,
        new TerminalEvent.ErrorPayload(code, ErrorDisposition.NOT_EXECUTED),
        requestId);
  }

  private static TerminalDelivery unauthenticatedNotExecuted(UUID lease, UUID requestId) {
    TerminalEvent event =
        new TerminalEvent(
            requestId,
            ENVIRONMENT,
            VIEWER,
            null,
            new TerminalEvent.ErrorPayload(
                ErrorCode.ROUTE_UNAVAILABLE, ErrorDisposition.NOT_EXECUTED));
    return new TerminalDelivery(
        OWNER, lease, null, new TerminalResponse(new TerminalRoute(NODE, CONNECTION), event));
  }

  private static TerminalDelivery delivery(
      UUID owner,
      UUID lease,
      UUID daemon,
      TerminalIdentity identity,
      TerminalEvent.Payload payload,
      UUID requestId) {
    TerminalEvent event = new TerminalEvent(requestId, ENVIRONMENT, VIEWER, identity, payload);
    return new TerminalDelivery(
        owner, lease, daemon, new TerminalResponse(new TerminalRoute(NODE, CONNECTION), event));
  }

  /** 构造一条最小合法 RESET 画面更新：5 列 2 行、整屏替换、无历史。 */
  private static TerminalViewUpdate resetView(UUID terminalId, UUID streamId) {
    TerminalView.InputModes modes =
        new TerminalView.InputModes(
            false,
            false,
            false,
            false,
            false,
            TerminalView.MouseMode.NONE,
            TerminalView.MouseFormat.XTERM_EXT);
    List<TerminalView.Slot> slots = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      slots.add(TerminalView.Slot.EMPTY);
    }
    List<TerminalViewUpdate.RowChange> rows = new ArrayList<>();
    rows.add(new TerminalViewUpdate.RowChange(0, new TerminalView.Line(1L, false, slots)));
    rows.add(new TerminalViewUpdate.RowChange(1, new TerminalView.Line(2L, false, slots)));
    return new TerminalViewUpdate(
        TerminalViewUpdate.Kind.RESET,
        terminalId,
        streamId,
        null,
        1L,
        5,
        2,
        false,
        0,
        0,
        0,
        true,
        null,
        1L,
        modes,
        0,
        List.of(),
        rows);
  }

  /** 可变 READY 路由源：既能返回固定 route，也能抛错模拟 PG 失败。 */
  private static final class MutableRouteSource implements EnvironmentTerminalRouteSource {
    private Optional<EnvironmentTerminalRoute> route = Optional.empty();
    private RuntimeException failure;

    @Override
    public Optional<EnvironmentTerminalRoute> resolveReadyRoute(UUID environmentId) {
      if (failure != null) {
        throw failure;
      }
      return route;
    }
  }

  /** 手动执行的 Executor：用于验证异步查路由完成后的连接/请求代次围栏。 */
  private static final class ManualExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private void runAll() {
      while (!tasks.isEmpty()) {
        tasks.poll().run();
      }
    }
  }
}
