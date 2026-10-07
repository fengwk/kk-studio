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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentChangeNotifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * environment_connection 六组写的 Java 通知契约：真实短事务内「围栏 SQL + environment_changed 发布」要么一起提交、要么一起回滚。
 *
 * <p>测试在隔离测试库中删除遗留触发器后，用独立 LISTEN 连接直接观测：真实写入（含 HEARTBEAT 与 Skill 投影）提交后各投递一次； 认证拒绝、活跃租约冲突、0
 * 行围栏与自然到期保持静默；发布失败必须回滚对应围栏写。
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
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "notify test daemon."));

  @Autowired private EnvironmentRegistry environmentRegistry;

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

  /** 测试意图：markReady/heartbeat/recordSkillEvent/replaceSkillState/disconnect 各自成功时通知，0 行围栏静默。 */
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
      assertTrue(environmentRegistry.heartbeat(DEV, token, LEASE_DURATION));
      listener.assertNotification(DEV.toString());

      assertFalse(environmentRegistry.recordSkillEvent(DEV, UUID.randomUUID(), skillEvent));
      listener.assertSilent();
      assertTrue(environmentRegistry.recordSkillEvent(DEV, token, skillEvent));
      listener.assertNotification(DEV.toString());

      assertFalse(
          environmentRegistry.replaceSkillState(DEV, UUID.randomUUID(), List.of(), skillEvent));
      listener.assertSilent();
      assertTrue(environmentRegistry.replaceSkillState(DEV, token, List.of(), skillEvent));
      listener.assertNotification(DEV.toString());

      assertFalse(environmentRegistry.disconnect(DEV, UUID.randomUUID(), LEASE_DURATION));
      listener.assertSilent();
      assertTrue(environmentRegistry.disconnect(DEV, token, LEASE_DURATION));
      listener.assertNotification(DEV.toString());

      // 过期持有者：全部围栏 0 行，连同直接改期一起保持静默。
      expireConnectionLease(DEV);
      listener.assertSilent();
      assertFalse(environmentRegistry.markReady(DEV, token, CAPABILITIES, LEASE_DURATION));
      assertFalse(environmentRegistry.heartbeat(DEV, token, LEASE_DURATION));
      assertFalse(environmentRegistry.disconnect(DEV, token, LEASE_DURATION));
      assertFalse(environmentRegistry.recordSkillEvent(DEV, token, skillEvent));
      assertFalse(environmentRegistry.replaceSkillState(DEV, token, List.of(), skillEvent));
      listener.assertSilent();
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

      PostgresqlEnvironmentChangeNotifier failingNotifier =
          mock(PostgresqlEnvironmentChangeNotifier.class);
      doThrow(new DataAccessResourceFailureException("environment notification unavailable"))
          .when(failingNotifier)
          .environmentChanged(any());
      EnvironmentRegistry failingRegistry =
          new EnvironmentRegistry(
              jdbcTemplate,
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

      // HEARTBEAT 同理：发布失败导致续租回滚，租约到期时间保持原值。
      Instant leaseBefore = connecting.leaseUntil();
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> failingRegistry.heartbeat(DEV, token, LEASE_DURATION));
      assertEquals(leaseBefore, environmentRegistry.find(DEV).orElseThrow().leaseUntil());
      listener.assertSilent();

      // disconnect 保留历史 false 契约，但捕获在事务边界之外：围栏写已回滚，而不是被吞掉后提交。
      assertFalse(failingRegistry.disconnect(DEV, token, LEASE_DURATION));
      EnvironmentConnection afterFailedDisconnect = environmentRegistry.find(DEV).orElseThrow();
      assertEquals(LiveEnvironmentStatus.CONNECTING, afterFailedDisconnect.status());
      assertEquals(leaseBefore, afterFailedDisconnect.leaseUntil());
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
        "insert into environment (id, name, registration_token, version) values (?, ?, ?, 0)",
        id.value(),
        name,
        token);
  }

  private void expireConnectionLease(EnvironmentId environmentId) {
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100"
            + " seconds', lease_until = statement_timestamp() - interval '1 second' where"
            + " environment_id = ?",
        environmentId.value());
  }
}
