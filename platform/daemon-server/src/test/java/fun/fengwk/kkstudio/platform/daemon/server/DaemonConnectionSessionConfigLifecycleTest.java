package fun.fengwk.kkstudio.platform.daemon.server;

import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.DAEMON_VERSION;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.heartbeatEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.runtimeStatusEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.FakeControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.Fixture;

import java.util.List;

/** 配置 v2 生命周期回归：初始/READY/通知查询失败、入队失败、CONNECTING 租约围栏与快照 revision。 */
class DaemonConnectionSessionConfigLifecycleTest {

  @Test
  void initialLookupFailureKeepsConnectionAndHeartbeatRetries() {
    Fixture fixture = new Fixture();
    fixture.configLookupFailure = new IllegalStateException("config source unavailable");
    FakeControlChannel channel = fixture.openSession("initial-config-lookup-failure");
    assertTrue(channel.isOpen());
    assertEquals(List.of(MessageType.SETTINGS), channel.allMessageTypes());

    fixture.configLookupFailure = null;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 2L));
    fixture.receive(channel, heartbeatEnvelope());
    assertTrue(channel.isOpen());
    assertEquals(1, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(2L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  @Test
  void readyLookupFailureKeepsReadyAndHeartbeatRetries() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("ready-config-lookup-failure");
    fixture.configLookupFailure = new IllegalStateException("config source unavailable");
    fixture.receiveReady(channel);
    assertTrue(channel.isOpen());
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));

    fixture.configLookupFailure = null;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 4L));
    fixture.receive(channel, heartbeatEnvelope());
    assertTrue(channel.isOpen());
    assertEquals(2, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(4L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  @Test
  void configOfferExceptionClosesSession() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-offer-exception");
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 3L));
    channel.failNextOffer = true;

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertTrue(channel.closed());
  }

  @Test
  void missingConfigNotificationOfferExceptionStillClosesSession() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("missing-config-offer-exception");
    fixture.configs.remove(ENVIRONMENT_ID);
    channel.failNextOffer = true;

    fixture.server.environmentChanged(ENVIRONMENT_ID);
    assertTrue(channel.closed());
  }

  @Test
  void connectingRuntimeStatusUsesActiveLeaseAndRejectsStaleOrConflictingRevision() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("connecting-runtime-revision");
    fixture.receive(
        channel, runtimeStatusEnvelope(2L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertTrue(channel.isOpen());
    assertEquals(2L, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());

    // 迟到的低 revision 不能被接受覆盖当前快照。
    fixture.receive(
        channel, runtimeStatusEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertTrue(channel.isOpen());
    assertEquals(2L, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());

    // 同 revision 但配置状态不同，说明内容在未推进 revision 时发生变化，必须拒绝。
    fixture.receive(
        channel,
        runtimeStatusEnvelope(
            2L,
            new DaemonRuntimeStatus(
                DAEMON_VERSION,
                null,
                new DaemonConfigurationStatus(2L, "http://127.0.0.1:7890", null))));
    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  @Test
  void connectingRuntimeStatusLeaseAndVersionFencesCloseSession() {
    Fixture leaseFixture = new Fixture();
    FakeControlChannel leaseChannel = leaseFixture.openSession("connecting-lease-fence");
    leaseFixture.leaseStore.activeLeaseToken = false;
    leaseFixture.receive(
        leaseChannel, runtimeStatusEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertTrue(leaseChannel.closed());
    assertEquals(MessageType.ERROR, leaseChannel.lastEnvelope().getMessageType());

    Fixture databaseFixture = new Fixture();
    FakeControlChannel databaseChannel = databaseFixture.openSession("connecting-database-failure");
    databaseFixture.leaseStore.databaseUnavailable = true;
    databaseFixture.receive(
        databaseChannel, runtimeStatusEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    assertTrue(databaseChannel.closed());

    Fixture versionFixture = new Fixture();
    FakeControlChannel versionChannel = versionFixture.openSession("connecting-version-change");
    versionFixture.receive(
        versionChannel, runtimeStatusEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
    versionFixture.receive(
        versionChannel, runtimeStatusEnvelope(2L, new DaemonRuntimeStatus("9.9.9", null)));
    assertTrue(versionChannel.closed());
    assertEquals(MessageType.ERROR, versionChannel.lastEnvelope().getMessageType());
  }

  @Test
  void readyRuntimeStatusRejectsDaemonVersionMismatch() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.connectReady("ready-version-mismatch");

    fixture.receive(channel, heartbeatEnvelope(2L, new DaemonRuntimeStatus("9.9.9", null)));
    assertTrue(channel.closed());
    assertEquals(MessageType.ERROR, channel.lastEnvelope().getMessageType());
  }

  @Test
  void runtimeConfigurationSnapshotRoundTrips() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("runtime-configuration-roundtrip");

    DaemonConfigurationStatus full =
        new DaemonConfigurationStatus(
            7L,
            "http://127.0.0.1:7890",
            new DaemonConfigurationError(8L, "INVALID_LSP_COMMAND", "lsp"));
    DaemonRuntimeSnapshot first =
        new DaemonRuntimeSnapshot(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null, full));
    fixture.receive(channel, heartbeatEnvelope(1L, first.status()));
    assertEquals(first, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow());

    DaemonConfigurationStatus noField =
        new DaemonConfigurationStatus(
            null, null, new DaemonConfigurationError(9L, "INVALID_CONFIG", null));
    DaemonRuntimeSnapshot second =
        new DaemonRuntimeSnapshot(2L, new DaemonRuntimeStatus(DAEMON_VERSION, null, noField));
    fixture.receive(channel, heartbeatEnvelope(2L, second.status()));
    assertEquals(second, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow());

    DaemonConfigurationStatus noError = new DaemonConfigurationStatus(10L, null, null);
    DaemonRuntimeSnapshot third =
        new DaemonRuntimeSnapshot(3L, new DaemonRuntimeStatus(DAEMON_VERSION, null, noError));
    fixture.receive(channel, heartbeatEnvelope(3L, third.status()));
    assertEquals(third, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow());
  }
}
