package fun.fengwk.kkstudio.harness.environment.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.FakeChannel;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDispatch;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalStatus;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link EnvironmentDaemonServer} shell 围栏的定向测试：出站 {@code sendShell} 的 READY/租约/代际/失败收敛，以及入站 {@code
 * SHELL_EVENT} 的方向、scope、身份校验与顺序回调。
 *
 * <p>复用 {@link EnvironmentDaemonServerTestSupport} 的内存基座，不触碰 PG/Spring；只断言核心真正拥有的围栏语义。
 */
class EnvironmentShellServerTest {

  private static final UUID APP_NODE = UUID.fromString("a0a0a0a0-0000-0000-0000-0000000000a1");
  private static final UUID VIEWER = UUID.fromString("b0b0b0b0-0000-0000-0000-0000000000b1");
  private static final UUID TERMINAL = UUID.fromString("c0c0c0c0-0000-0000-0000-0000000000c1");
  private static final UUID STREAM = UUID.fromString("d0d0d0d0-0000-0000-0000-0000000000d1");

  private static final UUID DAEMON =
      UUID.fromString(EnvironmentDaemonServerTestSupport.INSTANCE_ID);
  private static final UUID OTHER_DAEMON =
      UUID.fromString(EnvironmentDaemonServerTestSupport.OTHER_INSTANCE_ID);

  private static final TerminalControlCodec CONTROL_CODEC = new TerminalControlCodec();

  private static TerminalIdentity identity() {
    return new TerminalIdentity(DAEMON, TERMINAL);
  }

  private static TerminalRequest request(TerminalCommand.Payload payload) {
    return new TerminalRequest(
        new TerminalRoute(APP_NODE, "conn-1"),
        new TerminalCommand(
            UUID.randomUUID(),
            EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID.value(),
            VIEWER,
            payload));
  }

  private static TerminalDispatch dispatch(UUID leaseToken, TerminalCommand.Payload payload) {
    return new TerminalDispatch(leaseToken, request(payload));
  }

  private static TerminalResponse response(
      TerminalIdentity identity, TerminalEvent.Payload payload) {
    return new TerminalResponse(
        new TerminalRoute(APP_NODE, "conn-1"),
        new TerminalEvent(
            UUID.randomUUID(),
            EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID.value(),
            VIEWER,
            identity,
            payload));
  }

  // ---------------------------------------------------------------------
  // sendShell：READY / 租约 / 代际 / 失败收敛
  // ---------------------------------------------------------------------

  @Test
  void sendShellClosedWithoutBoundConnection() {
    Fixture fixture = new Fixture();
    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(dispatch(UUID.randomUUID(), new TerminalCommand.Open(null))));
  }

  @Test
  void sendShellClosedWhenConnectionNotReady() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("c-not-ready");
    fixture.server.open(channel);
    fixture.receiveHello(
        channel,
        EnvironmentDaemonServerTestSupport.TOKEN,
        EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID);

    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(
            dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(null))));
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellClosedWhenTokenDoesNotMatchCurrentLease() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");

    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(dispatch(UUID.randomUUID(), new TerminalCommand.Open(null))));
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellClosedWhenLeaseNoLongerHeld() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    fixture.leaseStore.holdsReadyLease = false;

    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(
            dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(null))));
    assertEquals(1, fixture.leaseStore.holdsReadyLeaseCalls);
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellFailsClosedWhenLeaseStoreUnavailable() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    fixture.leaseStore.databaseUnavailable = true;

    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(
            dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(null))));
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellDeliversOpenWithControlCodecRoundTrip() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalDispatch dispatch =
        dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(identity()));

    assertEquals(DaemonOfferResult.ACCEPTED, fixture.server.sendShell(dispatch));
    assertEquals(1, channel.countOf(DaemonMessageType.SHELL_COMMAND));
    DaemonEnvelope envelope = channel.lastEnvelope();
    assertEquals(EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID, envelope.environmentId());
    assertNull(envelope.invocationId());
    assertEquals(dispatch.request(), CONTROL_CODEC.decodeRequest(envelope.payloadJson()));
  }

  @Test
  void sendShellReturnsBusyWithoutDelivering() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    channel.busyNextOffer = true;

    assertEquals(
        DaemonOfferResult.BUSY,
        fixture.server.sendShell(
            dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(null))));
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
    assertFalse(channel.closed());
  }

  @Test
  void sendShellClosesConnectionWhenTransportThrows() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    channel.failNextOffer = true;

    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(
            dispatch(fixture.leaseStore.lastLeaseToken, new TerminalCommand.Open(null))));
    assertEquals(0, channel.countOf(DaemonMessageType.SHELL_COMMAND));
    assertTrue(channel.closed());
  }

  @Test
  void sendShellObservationOnlyCommandsSkipLeaseLookup() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    UUID token = fixture.leaseStore.lastLeaseToken;

    assertEquals(
        DaemonOfferResult.ACCEPTED,
        fixture.server.sendShell(dispatch(token, new TerminalCommand.Detach(identity(), STREAM))));
    assertEquals(
        DaemonOfferResult.ACCEPTED,
        fixture.server.sendShell(
            dispatch(token, new TerminalCommand.ViewApplied(identity(), STREAM, 1L))));
    assertEquals(0, fixture.leaseStore.holdsReadyLeaseCalls);
    assertEquals(2, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellSideEffectCommandsCheckLeaseLookup() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    UUID token = fixture.leaseStore.lastLeaseToken;

    assertEquals(
        DaemonOfferResult.ACCEPTED,
        fixture.server.sendShell(
            dispatch(token, new TerminalCommand.Claim(identity(), STREAM, null))));
    assertEquals(
        DaemonOfferResult.ACCEPTED,
        fixture.server.sendShell(
            dispatch(token, new TerminalCommand.Keepalive(identity(), STREAM, null))));
    assertEquals(2, fixture.leaseStore.holdsReadyLeaseCalls);
    assertEquals(2, channel.countOf(DaemonMessageType.SHELL_COMMAND));
  }

  @Test
  void sendShellAbortsWhenConnectionReplacedDuringLeaseLookup() {
    Fixture fixture = new Fixture();
    FakeChannel oldChannel = fixture.connectReady("old");
    UUID oldToken = fixture.leaseStore.lastLeaseToken;
    FakeChannel[] replacement = new FakeChannel[1];
    fixture.leaseStore.onHoldsReadyLease = () -> replacement[0] = fixture.reconnectReady("new");

    // PG 查询在锁外进行；期间连接换代后，回到发送锁必须复核代际，旧 offer 绝不落到任何一代连接。
    assertEquals(
        DaemonOfferResult.CLOSED,
        fixture.server.sendShell(dispatch(oldToken, new TerminalCommand.Open(null))));
    assertEquals(0, oldChannel.countOf(DaemonMessageType.SHELL_COMMAND));
    assertTrue(oldChannel.closed());
    assertEquals(0, replacement[0].countOf(DaemonMessageType.SHELL_COMMAND));
    assertFalse(replacement[0].closed());
  }

  // ---------------------------------------------------------------------
  // 入站 SHELL_EVENT：方向 / scope / 身份 / 顺序
  // ---------------------------------------------------------------------

  @Test
  void shellEventRequiresReadyBinding() {
    Fixture fixture = new Fixture();
    FakeChannel channel = new FakeChannel("c-not-ready");
    fixture.server.open(channel);
    fixture.receiveHello(
        channel,
        EnvironmentDaemonServerTestSupport.TOKEN,
        EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID);

    fixture.receiveShellEvent(
        channel, response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0)));

    assertTrue(fixture.terminalResponses.isEmpty());
    assertTrue(channel.closed());
  }

  @Test
  void shellEventRejectsEnvelopeScopeMismatch() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");

    fixture.receiveShellEvent(
        channel,
        EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID,
        response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0)));

    assertTrue(fixture.terminalResponses.isEmpty());
    assertTrue(channel.closed());
  }

  @Test
  void shellEventRejectsBodyEnvironmentMismatch() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalResponse mismatch =
        new TerminalResponse(
            new TerminalRoute(APP_NODE, "conn-1"),
            new TerminalEvent(
                UUID.randomUUID(),
                EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID.value(),
                VIEWER,
                identity(),
                new TerminalEvent.Exited(TerminalStatus.EXITED, 0)));

    fixture.receiveShellEvent(channel, mismatch);

    assertTrue(fixture.terminalResponses.isEmpty());
    assertTrue(channel.closed());
  }

  @Test
  void shellEventRejectsDaemonIdentityMismatch() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalIdentity foreign = new TerminalIdentity(OTHER_DAEMON, TERMINAL);

    fixture.receiveShellEvent(
        channel, response(foreign, new TerminalEvent.Exited(TerminalStatus.EXITED, 0)));

    assertTrue(fixture.terminalResponses.isEmpty());
    assertTrue(channel.closed());
  }

  @Test
  void shellEventDeliversInOrderWithoutLeaseLookup() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalResponse first =
        response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0));
    TerminalResponse second =
        response(identity(), new TerminalEvent.Exited(TerminalStatus.FAILED, 1));

    fixture.receiveShellEvent(channel, first);
    fixture.receiveShellEvent(channel, second);

    assertEquals(2, fixture.terminalResponses.size());
    assertEquals(first, fixture.terminalResponses.get(0));
    assertEquals(second, fixture.terminalResponses.get(1));
    assertEquals(0, fixture.leaseStore.holdsReadyLeaseCalls);
    assertFalse(channel.closed());
  }

  @Test
  void shellEventAfterConnectionReplacedIsNotDelivered() {
    Fixture fixture = new Fixture();
    FakeChannel oldChannel = fixture.connectReady("old");
    fixture.reconnectReady("new");

    fixture.receiveShellEvent(
        oldChannel, response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0)));

    assertTrue(fixture.terminalResponses.isEmpty());
  }

  @Test
  void queuedOldGenerationEventIsDroppedAfterTakeover() throws Exception {
    Fixture fixture = new Fixture();
    FakeChannel oldChannel = fixture.connectReady("old");
    TerminalResponse first =
        response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0));
    TerminalResponse second =
        response(identity(), new TerminalEvent.Exited(TerminalStatus.FAILED, 1));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    fixture.onTerminalResponse =
        event -> {
          if (first.equals(event)) {
            entered.countDown();
            try {
              assertTrue(released.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new AssertionError(error);
            }
          }
        };
    CompletableFuture<Void> receiving =
        CompletableFuture.runAsync(() -> fixture.receiveShellEvent(oldChannel, first));
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      fixture.receiveShellEvent(oldChannel, second);
      fixture.reconnectReady("new");
    } finally {
      released.countDown();
      receiving.get(5, TimeUnit.SECONDS);
    }
    // 已开始的回调不撤销；排队中的旧连接事件不能越过接管。
    assertEquals(List.of(first), fixture.terminalResponses);
  }

  @Test
  void errorEventWithNullIdentityIsDeliveredWithBoundInstance() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalResponse error =
        response(
            null,
            new TerminalEvent.ErrorPayload(
                ErrorCode.ROUTE_UNAVAILABLE, ErrorDisposition.NOT_EXECUTED));

    fixture.receiveShellEvent(channel, error);

    // ERROR 事件的 identity 允许为 null；listener 必须收到服务端认证的绑定实例而非事件 body。
    assertEquals(1, fixture.terminalResponses.size());
    assertEquals(error, fixture.terminalResponses.get(0));
    assertEquals(DAEMON, fixture.terminalDaemonInstanceIds.get(0));
    assertEquals(fixture.leaseStore.lastLeaseToken, fixture.terminalLeaseTokens.get(0));
    assertFalse(channel.closed());
  }

  @Test
  void shellEventWithNonNullIdentityUsesBoundInstanceNotBodyIdentity() {
    Fixture fixture = new Fixture();
    FakeChannel channel = fixture.connectReady("c1");
    TerminalResponse event =
        response(identity(), new TerminalEvent.Exited(TerminalStatus.EXITED, 0));

    fixture.receiveShellEvent(channel, event);

    assertEquals(1, fixture.terminalResponses.size());
    assertEquals(DAEMON, fixture.terminalDaemonInstanceIds.get(0));
  }
}
