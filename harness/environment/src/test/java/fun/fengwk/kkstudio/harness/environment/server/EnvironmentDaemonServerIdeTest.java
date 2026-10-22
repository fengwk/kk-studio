package fun.fengwk.kkstudio.harness.environment.server;

import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;

import java.util.List;

/**
 * 意图：IDE 元数据通道必须复用既有的 READY/lease/更新准入围栏，且作为独立于 Agent journal 的通道：命令递交成功才登记在途，
 * 回执恰好收敛一次，断线/超时不重放，迟到回执被安静忽略。
 */
class EnvironmentDaemonServerIdeTest {

  /** IDE 载荷对内核不透明：通道只负责准入、登记与回执匹配，不解释命令与回执内容。 */
  private static final String COMMAND = "{\"action\":\"OPEN_VIEW\",\"viewId\":\"view-1\"}";

  private static final String REPLY = "{\"status\":\"OK\",\"viewId\":\"view-1\"}";

  @Test
  void requestIdeWritesIdeCommandAndCompletesOnMatchingResult() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");

    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, COMMAND);

    assertEquals(DaemonMessageType.IDE_COMMAND, channel.lastEnvelope().messageType());
    assertEquals(request.invocationId().toString(), channel.lastEnvelope().invocationId());
    assertEquals(COMMAND, channel.lastEnvelope().payloadJson());
    assertFalse(request.reply().isDone());

    fixture.receive(
        channel, DaemonMessageType.IDE_RESULT, request.invocationId().toString(), REPLY);

    assertTrue(request.reply().isDone());
    assertEquals(REPLY, request.reply().join());
  }

  @Test
  void requestIdeFailsClosedWhenNoReadyConnection() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));
  }

  @Test
  void requestIdeFailsClosedWhenLeaseStoreUnavailableOrLeaseLost() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    fixture.connectReady("c1");

    fixture.leaseStore.databaseUnavailable = true;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));

    fixture.leaseStore.databaseUnavailable = false;
    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));
  }

  @Test
  void requestIdeIsRejectedWhileEnvironmentIsUpdating() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    fixture.connectReady("c1");
    fixture.server.beginUpdate(ENVIRONMENT_ID, "11111111-1111-1111-1111-111111111111");

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));
  }

  @Test
  void queueRejectionIsBusyAndLeavesNoPendingCall() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    channel.busyNextOffer = true;

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));
    // 未进入传输意味着不得保留在途登记：后续同名回执不改变任何状态。
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void transportFailureIsUncertainAndDoesNotReplay() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    channel.failNextOffer = true;

    assertThrows(
        EnvironmentCapabilitySendUncertainException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, COMMAND));
    assertTrue(channel.closed());
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void disconnectFailsPendingIdeWithoutReplay() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, COMMAND);

    fixture.server.close(channel.connectionId());

    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());

    // 同实例重连不得重放已终结的 IDE 命令。
    EnvironmentDaemonServerTestSupport.FakeChannel reconnected = fixture.reconnectReady("c2");
    assertEquals(0L, reconnected.countOf(DaemonMessageType.IDE_COMMAND));
  }

  @Test
  void abandonedCallIgnoresLateResult() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, COMMAND);

    fixture.server.abandonIde(request.invocationId());
    // 迟到回执是正常路径（Server 单方面超时），不得当作协议错误关闭连接。
    fixture.receive(
        channel, DaemonMessageType.IDE_RESULT, request.invocationId().toString(), REPLY);

    assertFalse(channel.closed());
    assertFalse(request.reply().isDone());
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void daemonSendingIdeCommandIsAProtocolViolation() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");

    fixture.receive(channel, DaemonMessageType.IDE_COMMAND, requestId(), COMMAND);

    assertTrue(channel.closed());
    assertEquals(DaemonMessageType.ERROR, channel.lastEnvelope().messageType());
  }

  @Test
  void resultForUnknownEnvironmentScopeIsRejected() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, COMMAND);

    // 用另一个 Environment 的 scope 发送回执：绑定连接校验应直接拒绝。
    fixture.server.receive(
        channel.connectionId(),
        EnvironmentDaemonServerTestSupport.encode(
            OTHER_ENVIRONMENT_ID,
            DaemonMessageType.IDE_RESULT,
            request.invocationId().toString(),
            REPLY));

    // scope 不匹配是协议违规：关闭连接，且在途 IDE 命令随之按结果不确定终结（不重放）。
    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());
    assertTrue(channel.closed());
  }

  private static String requestId() {
    return "77777777-8888-9999-aaaa-bbbbbbbbbbbb";
  }

  @Test
  void disconnectSignalsLeaseGenerationForCredentialAndStreamCleanup() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, COMMAND);

    fixture.server.close(channel.connectionId());

    // 代际关闭必须通知宿主，使其关闭绑定该 (environment, lease) 的 IDE 流与入口凭据，而非只撤销票据。
    assertEquals(List.of(ENVIRONMENT_ID), fixture.disconnectedEnvironments);
    assertEquals(List.of(request.identity().leaseToken()), fixture.disconnectedLeaseTokens);
  }
}
