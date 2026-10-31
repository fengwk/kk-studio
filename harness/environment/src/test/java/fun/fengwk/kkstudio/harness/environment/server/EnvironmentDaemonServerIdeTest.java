package fun.fengwk.kkstudio.harness.environment.server;

import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServerTestSupport.OTHER_INSTANCE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.harness.environment.protocol.ControlProtocolException;
import fun.fengwk.kkstudio.harness.environment.protocol.Envelope;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeAction;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeCommand;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeOpenMode;
import fun.fengwk.kkstudio.harness.environment.protocol.IdeResult;
import fun.fengwk.kkstudio.harness.environment.protocol.MessageType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 意图：IDE 元数据通道必须复用既有的 READY/lease/更新准入围栏，且作为独立于 Agent journal 的通道：命令递交成功才登记在途，
 * 回执恰好收敛一次，断线/超时不重放，迟到回执被安静忽略。
 */
class EnvironmentDaemonServerIdeTest {

  private static final String VIEW_ID = "11111111-2222-3333-4444-555555555555";

  private static IdeCommand openCommand() {
    return IdeCommand.newBuilder()
        .setAction(IdeAction.OPEN_VIEW)
        .setMode(IdeOpenMode.CREATE)
        .setViewId(VIEW_ID)
        .setDocumentId("66666666-7777-8888-9999-aaaaaaaaaaaa")
        .setEventNodeId("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
        .setConnectionId("12345678-1234-1234-1234-123456789abc")
        .setClaimId("87654321-4321-4321-4321-cba987654321")
        .build();
  }

  private static IdeResult openReply() {
    return IdeResult.newBuilder()
        .setOk(true)
        .setViewId(VIEW_ID)
        .setDocumentId("66666666-7777-8888-9999-aaaaaaaaaaaa")
        .setEventNodeId("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
        .setConnectionId("12345678-1234-1234-1234-123456789abc")
        .setClaimId("87654321-4321-4321-4321-cba987654321")
        .setBindingEpoch(1L)
        .setDirectory("/tmp")
        .setWorkspacePath("/tmp/ws")
        .build();
  }

  @Test
  void requestIdeWritesIdeCommandAndCompletesOnMatchingResult() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");

    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, openCommand());

    assertEquals(MessageType.IDE_COMMAND, channel.lastEnvelope().getMessageType());
    assertEquals(request.invocationId().toString(), channel.lastEnvelope().getInvocationId());
    assertEquals(openCommand(), channel.lastEnvelope().getIdeCommand());
    assertFalse(request.reply().isDone());

    Envelope resultEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.IDE_RESULT)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setInvocationId(request.invocationId().toString())
            .setIdeResult(openReply())
            .build();
    fixture.receive(channel, resultEnvelope);

    assertTrue(request.reply().isDone());
    assertEquals(openReply(), request.reply().join());
  }

  @Test
  void invalidIdeCommandThrowsToCallerAndKeepsConnectionOpen() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");

    IdeCommand invalid =
        IdeCommand.newBuilder().setAction(IdeAction.IDE_ACTION_UNSPECIFIED).build();

    assertThrows(
        ControlProtocolException.class, () -> fixture.server.requestIde(ENVIRONMENT_ID, invalid));

    assertTrue(channel.isOpen());
    assertFalse(channel.closed());
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void requestIdeFailsClosedWhenNoReadyConnection() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));
  }

  @Test
  void requestIdeFailsClosedWhenLeaseStoreUnavailableOrLeaseLost() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    fixture.connectReady("c1");

    fixture.leaseStore.databaseUnavailable = true;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));

    fixture.leaseStore.databaseUnavailable = false;
    fixture.leaseStore.holdsReadyLease = false;
    assertThrows(
        EnvironmentCapabilityUnavailableException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));
  }

  @Test
  void requestIdeIsRejectedWhileEnvironmentIsUpdating() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    fixture.connectReady("c1");
    fixture.server.beginUpdate(ENVIRONMENT_ID, "11111111-1111-1111-1111-111111111111");

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));
  }

  @Test
  void queueRejectionIsBusyAndLeavesNoPendingCall() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");
    channel.busyNextOffer = true;

    assertThrows(
        EnvironmentCapabilityBusyException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void transportFailureIsUncertainAndDoesNotReplay() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");
    channel.failNextOffer = true;

    assertThrows(
        EnvironmentCapabilitySendUncertainException.class,
        () -> fixture.server.requestIde(ENVIRONMENT_ID, openCommand()));
    assertTrue(channel.closed());
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void disconnectFailsPendingIdeWithoutReplay() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, openCommand());

    fixture.close(channel);

    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());

    EnvironmentDaemonServerTestSupport.FakeControlChannel reconnected =
        fixture.reconnectReady("c2");
    assertEquals(0L, reconnected.countOf(MessageType.IDE_COMMAND));
  }

  @Test
  void abandonedCallIgnoresLateResult() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, openCommand());

    fixture.server.abandonIde(request.invocationId());
    Envelope lateResult =
        Envelope.newBuilder()
            .setMessageType(MessageType.IDE_RESULT)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setInvocationId(request.invocationId().toString())
            .setIdeResult(openReply())
            .build();
    fixture.receive(channel, lateResult);

    assertFalse(channel.closed());
    assertFalse(request.reply().isDone());
    assertEquals(0, fixture.server.activeIdeCount());
  }

  @Test
  void daemonSendingIdeCommandIsAProtocolViolation() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");

    Envelope commandEnvelope =
        Envelope.newBuilder()
            .setMessageType(MessageType.IDE_COMMAND)
            .setEnvironmentId(ENVIRONMENT_ID.value().toString())
            .setInvocationId(UUID.randomUUID().toString())
            .setIdeCommand(openCommand())
            .build();
    fixture.receive(channel, commandEnvelope);

    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  @Test
  void resultForUnknownEnvironmentScopeIsRejected() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");
    DaemonIdeCall request = fixture.server.requestIde(ENVIRONMENT_ID, openCommand());

    Envelope mismatchedScope =
        Envelope.newBuilder()
            .setMessageType(MessageType.IDE_RESULT)
            .setEnvironmentId(OTHER_ENVIRONMENT_ID.value().toString())
            .setInvocationId(request.invocationId().toString())
            .setIdeResult(openReply())
            .build();
    fixture.receive(channel, mismatchedScope);

    assertTrue(request.reply().isCompletedExceptionally());
    assertInstanceOf(
        EnvironmentCapabilitySendUncertainException.class, request.reply().exceptionNow());
    assertTrue(channel.closed());
  }

  @Test
  void heartbeatPublishesLeaseRoundOnlyAfterAcceptedReadyHeartbeat() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel channel = fixture.connectReady("c1");

    fixture.receive(
        channel,
        EnvironmentDaemonServerTestSupport.heartbeatEnvelope(
            2L,
            DaemonRuntimeStatus.running(EnvironmentDaemonServerTestSupport.DAEMON_VERSION),
            7L));

    assertEquals(
        List.of(
            new EnvironmentDaemonServerTestSupport.HeartbeatNotification(
                ENVIRONMENT_ID,
                new DaemonConnectionIdentity(
                    ENVIRONMENT_ID, EnvironmentDaemonServerTestSupport.INSTANCE_ID),
                7L)),
        fixture.heartbeatNotifications);

    fixture.leaseStore.heartbeatResult = false;
    fixture.receive(
        channel,
        EnvironmentDaemonServerTestSupport.heartbeatEnvelope(
            3L,
            DaemonRuntimeStatus.running(EnvironmentDaemonServerTestSupport.DAEMON_VERSION),
            8L));

    assertEquals(1, fixture.heartbeatNotifications.size());
    assertTrue(channel.closed());
  }

  @Test
  void sameInstanceReconnectKeepsTheIdeGeneration() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel first = fixture.connectReady("c1");
    DaemonConnectionIdentity before =
        fixture.server.requestIde(ENVIRONMENT_ID, openCommand()).identity();

    fixture.close(first);
    assertTrue(fixture.server.connectionIdentity(ENVIRONMENT_ID).isEmpty());
    fixture.connectReady("c2");

    assertEquals(Optional.of(before), fixture.server.connectionIdentity(ENVIRONMENT_ID));
    assertEquals(List.of(), fixture.replacedInstances);
  }

  @Test
  void differentInstanceTakeoverEndsThePreviousIdeGeneration() {
    EnvironmentDaemonServerTestSupport.Fixture fixture =
        new EnvironmentDaemonServerTestSupport.Fixture();
    EnvironmentDaemonServerTestSupport.FakeControlChannel first = fixture.connectReady("c1");
    DaemonConnectionIdentity before =
        fixture.server.requestIde(ENVIRONMENT_ID, openCommand()).identity();

    fixture.close(first);
    fixture.connectReady("c2", OTHER_INSTANCE_ID);

    assertEquals(List.of(before), fixture.replacedInstances);
    assertEquals(
        Optional.of(new DaemonConnectionIdentity(ENVIRONMENT_ID, OTHER_INSTANCE_ID)),
        fixture.server.connectionIdentity(ENVIRONMENT_ID));
  }
}
