package fun.fengwk.kkstudio.platform.daemon.server;

import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.DAEMON_VERSION;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.ENVIRONMENT_ID;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.heartbeatEnvelope;
import static fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.runtimeStatusEnvelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationError;
import fun.fengwk.kkstudio.platform.daemon.DaemonConfigurationStatus;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.DaemonConfiguration;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.FakeControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServerTestSupport.Fixture;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 配置 v2 生命周期回归：初始/READY/通知查询失败、入队失败、CONNECTING 租约围栏与快照 revision。 */
class DaemonConnectionSessionConfigLifecycleTest {

  @Test
  void initialLookupFailureKeepsConnectionAndHeartbeatRetries() {
    Fixture fixture = new Fixture();
    fixture.configLookupFailure = new IllegalStateException("config source unavailable");
    FakeControlChannel channel = fixture.openSession("initial-config-lookup-failure");
    assertEquals(List.of(MessageType.SETTINGS), channel.allMessageTypes());

    fixture.configLookupFailure = null;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 2L));
    fixture.receive(channel, heartbeatEnvelope());
    assertEquals(1, channel.countOf(MessageType.ENVIRONMENT_CONFIG));
    assertEquals(2L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
  }

  @Test
  void readyLookupFailureKeepsReadyAndHeartbeatRetries() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("ready-config-lookup-failure");
    fixture.configLookupFailure = new IllegalStateException("config source unavailable");
    fixture.receiveReady(channel);
    assertTrue(fixture.server.isReady(ENVIRONMENT_ID));

    fixture.configLookupFailure = null;
    fixture.configs.put(
        ENVIRONMENT_ID,
        EnvironmentDaemonServerTestSupport.Fixture.configSnapshot(ENVIRONMENT_ID, 4L));
    fixture.receive(channel, heartbeatEnvelope());
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

  /** 出站协议拒绝必须记录字段路径与规则，且不回显配置值，而不是无声关闭连接。 */
  @Test
  void protocolRejectedEnvironmentConfigIsLoggedWithoutEchoingTheValue() {
    Fixture fixture = new Fixture();
    FakeControlChannel channel = fixture.openSession("config-protocol-rejection");
    fixture.configs.put(
        ENVIRONMENT_ID,
        new EnvironmentConfigSnapshot(
            3L,
            DaemonConfiguration.newBuilder()
                .setStudioUrl("https://studio.example.org")
                .setBashExecutable(" /opt/secret-token ")
                .build()));

    try (ConfigLog log = new ConfigLog()) {
      fixture.server.environmentChanged(ENVIRONMENT_ID);

      assertTrue(channel.closed(), "a rejected frame must force a reconnect");
      assertEquals(
          1,
          channel.countOf(MessageType.ENVIRONMENT_CONFIG),
          "the rejected frame is not delivered");
      assertEquals(0L, channel.lastEnvelope().getEnvironmentConfig().getVersion());
      assertEquals(
          List.of(
              "environment daemon environment config version 3 delivery rejected:"
                  + " environmentConfig.config.bashExecutable must not have surrounding"
                  + " whitespace"),
          log.messages());
      log.assertSafe();
    }
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
    assertEquals(2L, fixture.server.runtimeStatus(ENVIRONMENT_ID).orElseThrow().revision());

    // 迟到的低 revision 不能被接受覆盖当前快照。
    fixture.receive(
        channel, runtimeStatusEnvelope(1L, new DaemonRuntimeStatus(DAEMON_VERSION, null)));
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

  /**
   * 接管环境会话诊断日志的 JUL 端口：安全失败日志是普通 {@code System.Logger} 记录，只有挂接处理器才能断言其内容。
   *
   * <p>{@link #close()} 恢复接管前的级别与父处理器设置，避免影响其它测试。
   */
  private static final class ConfigLog implements AutoCloseable {

    private static final String SECRET = "secret-token";

    private final Logger logger = Logger.getLogger(DaemonConnectionSession.class.getName());
    private final Level previousLevel = logger.getLevel();
    private final boolean previousUseParentHandlers = logger.getUseParentHandlers();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    private ConfigLog() {
      handler.setLevel(Level.ALL);
      logger.addHandler(handler);
      logger.setLevel(Level.ALL);
      logger.setUseParentHandlers(false);
    }

    List<String> messages() {
      return records.stream().map(LogRecord::getMessage).toList();
    }

    /** 每条拒绝恰好一条 WARNING，只携带固定阶段、字段路径与规则：没有原始 throwable，也不回显配置值。 */
    void assertSafe() {
      assertEquals(1, records.size(), "the rejection must be logged exactly once");
      for (LogRecord record : records) {
        assertEquals(Level.WARNING, record.getLevel());
        assertNull(record.getThrown(), "a raw throwable must never be logged");
        assertFalse(
            record.getMessage().contains(SECRET), "the diagnostic must not echo the config value");
      }
    }

    @Override
    public void close() {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
      logger.setUseParentHandlers(previousUseParentHandlers);
    }
  }
}
