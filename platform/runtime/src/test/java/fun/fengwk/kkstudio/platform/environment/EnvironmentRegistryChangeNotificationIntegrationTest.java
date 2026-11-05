package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.Architecture;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.protocol.EnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.protocol.Heartbeat;
import fun.fengwk.kkstudio.platform.daemon.protocol.MessageType;
import fun.fengwk.kkstudio.platform.daemon.protocol.OperatingSystem;
import fun.fengwk.kkstudio.platform.daemon.protocol.Ready;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeBody;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeSnapshot;
import fun.fengwk.kkstudio.platform.daemon.protocol.RuntimeStatus;
import fun.fengwk.kkstudio.platform.daemon.protocol.UpdateProgress;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonAdmission;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonControlChannel;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonHandshake;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonOfferResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonSession;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * environment_connection 的 Java 通知契约：状态写与 {@code environment_changed} 在事务内一起提交或回滚。
 *
 * <p>测试基座直接使用独立 LISTEN 连接观测：真实状态和 Skill 投影写入后投递通知；心跳续租、认证拒绝、活跃租约冲突、0 行围栏与自然到期保持静默；状态写的发布失败必须回滚。
 */
class EnvironmentRegistryChangeNotificationIntegrationTest
    extends EnvironmentNotificationTestSupport {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

  private static final EnvironmentId DEV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId STANDBY =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final EnvironmentId UNKNOWN =
      EnvironmentId.parse("33333333-3333-3333-3333-333333333333");
  private static final String DEV_TOKEN = "notify-registry-dev-token";
  private static final String STANDBY_TOKEN = "notify-registry-standby-token";

  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          "1.0.9",
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX,
              "UTC",
              "dev",
              "/home/dev",
              "notify test daemon.",
              "/tmp/kk-studio",
              "amd64"));

  @Autowired private EnvironmentRegistry environmentRegistry;
  @Autowired private EnvironmentDaemonServer daemonServer;

  @BeforeEach
  void seedEnvironments() {
    insertEnvironment(DEV, "notify-registry-dev", DEV_TOKEN);
    insertEnvironment(STANDBY, "notify-registry-standby", STANDBY_TOKEN);
  }

  /** 测试意图：只有真实的 Acquired upsert 才通知；认证拒绝、环境缺失与活跃租约冲突都不写行、保持静默。 */
  @Test
  void acquireNotificationsFollowRealUpserts() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      assertInstanceOf(
          LeaseBindResult.Rejected.class,
          environmentRegistry.tryAcquire(DEV, "wrong-token", LEASE_DURATION));
      listener.assertSilent();

      assertInstanceOf(
          LeaseBindResult.Rejected.class,
          environmentRegistry.tryAcquire(UNKNOWN, DEV_TOKEN, LEASE_DURATION));
      listener.assertSilent();

      LeaseBindResult.Acquired acquired =
          assertInstanceOf(
              LeaseBindResult.Acquired.class,
              environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION));
      listener.assertNotification(DEV.toString());

      // 活跃 READY 路由冲突：0 行写入，静默。
      assertTrue(
          environmentRegistry.markReady(DEV, acquired.leaseToken(), CAPABILITIES, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      assertInstanceOf(
          LeaseBindResult.RetryLater.class,
          environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION));
      listener.assertSilent();

      // 过期租约被真实接管：仍是写入，必须通知。
      expireConnectionLease(DEV);
      listener.assertSilent();
      assertInstanceOf(
          LeaseBindResult.Acquired.class,
          environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
    }
  }

  /** 测试意图：状态、事件与 Skill 投影成功时通知，心跳和 0 行围栏静默。 */
  @Test
  void fencedConnectionWritesNotifyOnlyWhenTheyWrite() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      LeaseBindResult.Acquired acquired =
          assertInstanceOf(
              LeaseBindResult.Acquired.class,
              environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      UUID token = acquired.leaseToken();
      EnvironmentEvent skillEvent =
          new EnvironmentEvent(
              Instant.now(),
              EnvironmentEvent.LEVEL_INFO,
              EnvironmentEvent.TYPE_SKILL_SYNC_STARTED,
              "notify fencing test");

      assertFalse(
          environmentRegistry.markReady(DEV, UUID.randomUUID(), CAPABILITIES, LEASE_DURATION));
      listener.assertSilent();
      assertTrue(environmentRegistry.markReady(DEV, token, CAPABILITIES, LEASE_DURATION));
      listener.assertNotification(DEV.toString());

      assertFalse(environmentRegistry.heartbeat(DEV, UUID.randomUUID(), LEASE_DURATION));
      listener.assertSilent();
      EnvironmentConnection beforeHeartbeat = environmentRegistry.find(DEV).orElseThrow();
      for (int i = 0; i < 3; i++) {
        assertTrue(environmentRegistry.heartbeat(DEV, token, Duration.ofSeconds(90 + i)));
      }
      EnvironmentConnection afterHeartbeat = environmentRegistry.find(DEV).orElseThrow();
      assertTrue(afterHeartbeat.leaseUntil().isAfter(beforeHeartbeat.leaseUntil()));
      assertTrue(afterHeartbeat.lastSeenAt().isAfter(beforeHeartbeat.lastSeenAt()));
      assertEquals(beforeHeartbeat.status(), afterHeartbeat.status());
      assertEquals(beforeHeartbeat.daemonCapabilities(), afterHeartbeat.daemonCapabilities());
      assertEquals(beforeHeartbeat.skillState(), afterHeartbeat.skillState());
      assertEquals(beforeHeartbeat.recentEvents(), afterHeartbeat.recentEvents());
      listener.assertSilent();

      assertFalse(environmentRegistry.recordEvent(DEV, UUID.randomUUID(), skillEvent));
      listener.assertSilent();
      assertTrue(environmentRegistry.recordEvent(DEV, token, skillEvent));
      listener.assertNotification(DEV.toString());

      assertFalse(
          environmentRegistry.replaceSkillState(DEV, UUID.randomUUID(), List.of(), skillEvent));
      listener.assertSilent();
      List<EnvironmentSkillState> installed =
          List.of(
              EnvironmentSkillState.installed(
                  "dev", "0123456789abcdef".repeat(4), "/home/dev/skills/dev"));
      assertTrue(environmentRegistry.replaceSkillState(DEV, token, installed, skillEvent));
      listener.assertNotification(DEV.toString());
      assertEquals(installed, environmentRegistry.find(DEV).orElseThrow().skillState());

      assertFalse(environmentRegistry.runtimeStatusChanged(DEV, UUID.randomUUID()));
      listener.assertSilent();
      assertTrue(environmentRegistry.runtimeStatusChanged(DEV, token));
      listener.assertNotification(DEV.toString());

      assertFalse(environmentRegistry.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
      listener.assertSilent();
      assertTrue(environmentRegistry.disconnect(DEV, token, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      assertFalse(environmentRegistry.runtimeStatusChanged(DEV, token));
      listener.assertSilent();

      // 同节点重连并上报新的宿主信息：CONNECTING 和 READY 都是可见变化。
      UUID reconnected =
          assertInstanceOf(
                  LeaseBindResult.Acquired.class,
                  environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION))
              .leaseToken();
      listener.assertNotification(DEV.toString());
      DaemonCapabilities changedHost =
          new DaemonCapabilities(
              "1.0.10",
              new DaemonEnvironmentInfo(
                  DaemonOperatingSystem.LINUX,
                  "UTC",
                  "test",
                  "/home/test",
                  "new host",
                  "/tmp/kk-studio",
                  "amd64"));
      assertTrue(environmentRegistry.markReady(DEV, reconnected, changedHost, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      assertEquals(changedHost, environmentRegistry.find(DEV).orElseThrow().daemonCapabilities());

      // 过期持有者：全部围栏 0 行，连同直接改期一起保持静默。
      expireConnectionLease(DEV);
      listener.assertSilent();
      assertFalse(environmentRegistry.markReady(DEV, reconnected, CAPABILITIES, LEASE_DURATION));
      assertFalse(environmentRegistry.heartbeat(DEV, reconnected, LEASE_DURATION));
      assertFalse(environmentRegistry.runtimeStatusChanged(DEV, reconnected));
      assertFalse(environmentRegistry.disconnect(DEV, reconnected, LEASE_DURATION));
      assertFalse(environmentRegistry.recordEvent(DEV, reconnected, skillEvent));
      assertFalse(environmentRegistry.replaceSkillState(DEV, reconnected, List.of(), skillEvent));
      listener.assertSilent();
    }
  }

  /** 测试意图：真实会话加 PostgreSQL 通知链路中，心跳续租静默，运行态变化在快照可读后通知。 */
  @Test
  void sessionPublishesOnlyRealRuntimeChanges() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      DaemonAdmission.Admitted admission =
          assertInstanceOf(
              DaemonAdmission.Admitted.class,
              daemonServer.admit(new DaemonHandshake(DEV_TOKEN, UUID.randomUUID().toString())));
      listener.assertNotification(DEV.toString());
      DaemonControlChannel channel = mock(DaemonControlChannel.class);
      when(channel.isOpen()).thenReturn(true);
      when(channel.offer(any())).thenReturn(DaemonOfferResult.ACCEPTED);
      DaemonSession session = daemonServer.open(admission, channel, null);
      RuntimeBody running = RuntimeBody.newBuilder().setDaemonVersion("1.0.9").build();
      RuntimeBody updating =
          running.toBuilder()
              .setUpdate(
                  UpdateProgress.newBuilder().setTargetVersion("1.0.10").setDownloadedBytes(10))
              .build();
      session.receive(
          Envelope.newBuilder()
              .setMessageType(MessageType.READY)
              .setEnvironmentId(DEV.toString())
              .setReady(
                  Ready.newBuilder()
                      .setDaemonVersion("1.0.9")
                      .setEnvironment(
                          EnvironmentInfo.newBuilder()
                              .setOperatingSystem(OperatingSystem.LINUX)
                              .setArchitecture(Architecture.AMD64)
                              .setTimeZone("UTC")
                              .setUserName("dev")
                              .setHomeDirectory("/home/dev")
                              .setTempDirectory("/tmp/kk-studio")
                              .setNote("notify test daemon."))
                      .setRuntimeStatus(snapshot(1, running)))
              .build());
      listener.assertNotification(DEV.toString());

      session.receive(heartbeat(snapshot(1, running)));
      session.receive(heartbeat(snapshot(2, running)));
      listener.assertSilent();

      session.receive(heartbeat(snapshot(3, updating)));
      listener.assertNotification(DEV.toString());
      assertEquals(
          10L, daemonServer.runtimeStatus(DEV).orElseThrow().status().update().downloadedBytes());
      session.receive(heartbeat(snapshot(3, updating)));
      listener.assertSilent();

      session.receive(
          Envelope.newBuilder()
              .setMessageType(MessageType.RUNTIME_STATUS)
              .setEnvironmentId(DEV.toString())
              .setRuntimeStatus(RuntimeStatus.newBuilder().setRuntimeStatus(snapshot(4, running)))
              .build());
      listener.assertNotification(DEV.toString());
      assertNull(daemonServer.runtimeStatus(DEV).orElseThrow().status().update());
      session.close();
      listener.assertNotification(DEV.toString());
    }
  }

  /** 测试意图：发布失败必须回滚对应围栏写（含 tryAcquire），绝不提交「已写但未通知」的中间态。 */
  @Test
  void notificationFailureRollsBackTheFencedConnectionWrite() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      LeaseBindResult.Acquired acquired =
          assertInstanceOf(
              LeaseBindResult.Acquired.class,
              environmentRegistry.tryAcquire(DEV, DEV_TOKEN, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      UUID token = acquired.leaseToken();

      EnvironmentChangeNotifier failingNotifier = mock(EnvironmentChangeNotifier.class);
      doThrow(new DataAccessResourceFailureException("environment notification unavailable"))
          .when(failingNotifier)
          .environmentChanged(any());
      EnvironmentRegistry failingRegistry =
          new EnvironmentRegistry(
              JdbcClient.create(jdbcTemplate),
              environmentRegistry.ownerNodeId(),
              Clock.systemUTC(),
              transactionManager,
              failingNotifier);

      // READY 围栏写成功但发布失败 -> 整个短事务回滚，状态与保留 metadata 都不得推进。
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> failingRegistry.markReady(DEV, token, CAPABILITIES, LEASE_DURATION));
      EnvironmentConnection connecting = environmentRegistry.find(DEV).orElseThrow();
      assertEquals(LiveEnvironmentStatus.CONNECTING, connecting.status());
      assertNull(connecting.daemonCapabilities());
      listener.assertSilent();

      // HEARTBEAT 不依赖通知器，发布故障不阻断续租。
      Instant leaseBefore = connecting.leaseUntil();
      assertTrue(failingRegistry.heartbeat(DEV, token, Duration.ofSeconds(120)));
      Instant renewedLease = environmentRegistry.find(DEV).orElseThrow().leaseUntil();
      assertTrue(renewedLease.isAfter(leaseBefore));
      listener.assertSilent();

      // 续租 SQL 自身失败时，异常必须透传且租约不变。
      assertThrows(
          DataAccessException.class, () -> failingRegistry.heartbeat(DEV, token, Duration.ZERO));
      assertEquals(renewedLease, environmentRegistry.find(DEV).orElseThrow().leaseUntil());
      listener.assertSilent();

      // disconnect 在发布失败时返回 false；捕获在事务边界之外，围栏写已回滚而不是被吞掉后提交。
      assertFalse(failingRegistry.disconnect(DEV, token, LEASE_DURATION));
      EnvironmentConnection afterFailedDisconnect = environmentRegistry.find(DEV).orElseThrow();
      assertEquals(LiveEnvironmentStatus.CONNECTING, afterFailedDisconnect.status());
      assertEquals(renewedLease, afterFailedDisconnect.leaseUntil());
      listener.assertSilent();

      // 运行态通知仅在 READY 租约下发布，发布异常向上抛出。
      assertTrue(environmentRegistry.markReady(DEV, token, CAPABILITIES, LEASE_DURATION));
      listener.assertNotification(DEV.toString());
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> failingRegistry.runtimeStatusChanged(DEV, token));
      listener.assertSilent();

      // tryAcquire 的成功 upsert 在发布失败时同样回滚，绝不留下没有通知的连接行。
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> failingRegistry.tryAcquire(STANDBY, STANDBY_TOKEN, LEASE_DURATION));
      assertTrue(environmentRegistry.find(STANDBY).isEmpty());
      listener.assertSilent();
    }
  }

  private void insertEnvironment(EnvironmentId id, String name, String token) {
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token_hash, version) values (?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), 0)",
        id.value(),
        name,
        token);
  }

  private static RuntimeSnapshot snapshot(long revision, RuntimeBody status) {
    return RuntimeSnapshot.newBuilder().setRevision(revision).setStatus(status).build();
  }

  private static Envelope heartbeat(RuntimeSnapshot snapshot) {
    return Envelope.newBuilder()
        .setMessageType(MessageType.HEARTBEAT)
        .setEnvironmentId(DEV.toString())
        .setHeartbeat(Heartbeat.newBuilder().setRuntimeStatus(snapshot))
        .build();
  }

  private void expireConnectionLease(EnvironmentId environmentId) {
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100"
            + " seconds', lease_until = statement_timestamp() - interval '1 second' where"
            + " environment_id = ?",
        environmentId.value());
  }
}
