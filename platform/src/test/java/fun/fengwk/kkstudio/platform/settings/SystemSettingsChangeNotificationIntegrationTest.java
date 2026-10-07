package fun.fengwk.kkstudio.platform.settings;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * System settings 生产写入口的事务内 {@code system_settings_changed} 通知验证。
 *
 * <p>意图：{@code PostgresqlSystemSettingsRepository} 的成功 CAS 与通知共用同一事务 Connection，提交后投递写后权威 {@code
 * version} 的十进制字符串；未提交不可见、回滚静默、陈旧 CAS 静默。{@code SystemSettingsServiceImpl} 的提交后快照刷新保持不变，但绝不替代事务内
 * NOTIFY。 夹具在隔离的每测试数据库里删除旧的 {@code trg_system_setting_version_notify}，因此观测到的通知只能来自 Java 写入口。
 */
class SystemSettingsChangeNotificationIntegrationTest extends PostgresSpringTestSupport {

  private static final String SELECT_VERSION = "select version from system_setting where id = 1";

  @Autowired private SystemSettingsService systemSettingsService;
  @Autowired private SystemSettingsRepository systemSettingsRepository;
  @Autowired private SystemSettingsSnapshot systemSettingsSnapshot;
  @Autowired private PostgresqlSystemSettingsChangeNotifier notifier;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void dropLegacyTrigger() {
    // N6 删除生产触发器前，只在隔离的每测试库移除它，使断言真正证明 Java 写入口的发布行为。
    jdbcTemplate.execute(
        "drop trigger if exists trg_system_setting_version_notify on system_setting");
  }

  /** 提交的 CAS 推进 version 并投递一次；同一事务未提交前对其它连接不可见。 */
  @Test
  void committedCasNotifiesWithDecimalVersionAndUncommittedIsInvisible() throws Exception {
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + SystemSettingsChangeHandler.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> {
            SystemSettingsRepository.SystemSettingsRecord current = systemSettingsRepository.get();
            assertTrue(systemSettingsRepository.update(withYolo(true), current.version()));
            assertNoNotification(pg);
          });

      assertNotification(pg, "1");
      assertNoNotification(pg);
      assertEquals(1L, currentVersion());
    }
  }

  /** 回滚的 CAS 绝不投递，权威 version 与配置保持不变。 */
  @Test
  void rolledBackCasIsSilent() throws Exception {
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + SystemSettingsChangeHandler.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> {
            SystemSettingsRepository.SystemSettingsRecord current = systemSettingsRepository.get();
            assertTrue(systemSettingsRepository.update(withYolo(true), current.version()));
            status.setRollbackOnly();
          });

      assertNoNotification(pg);
      assertEquals(0L, currentVersion());
      assertFalse(systemSettingsRepository.get().settings().tool().defaultYolo());
    }
  }

  /** 陈旧 CAS 影响 0 行静默：version 未推进，没有通知。 */
  @Test
  void staleRepositoryCasIsSilent() throws Exception {
    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + SystemSettingsChangeHandler.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> assertFalse(systemSettingsRepository.update(withYolo(true), 5L)));

      assertNoNotification(pg);
      assertEquals(0L, currentVersion());
    }
  }

  /** 生产服务 PUT：通知落在其事务内；回滚既不投递也不刷新本节点快照，提交则同时投递并刷新。 */
  @Test
  void serviceUpdatePublishesInTransactionAndRollbackIsSilent() throws Exception {
    SystemSettingsDTO before = systemSettingsService.get();
    int beforeRetries = systemSettingsSnapshot.get().aiRuntime().retryMaxRetries();
    int changedRetries = beforeRetries == 9 ? 8 : 9;

    try (Connection listener = newConnection();
        Statement statement = listener.createStatement()) {
      statement.execute("listen " + SystemSettingsChangeHandler.CHANNEL);
      PGConnection pg = listener.unwrap(PGConnection.class);
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      SystemSettings snapshotOnEntry = systemSettingsSnapshot.get();
      tx.executeWithoutResult(
          status -> {
            SystemSettingsUpdateDTO update = updateFrom(before, "0");
            update.getAiRuntime().setRetryMaxRetries(changedRetries);
            systemSettingsService.update(update);
            status.setRollbackOnly();
          });

      // 回滚：无通知，且 afterCommit 未触发，本节点快照与权威 version 都不变。
      assertNoNotification(pg);
      assertEquals(0L, currentVersion());
      assertSame(snapshotOnEntry, systemSettingsSnapshot.get());
      assertNotEquals(changedRetries, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());

      SystemSettingsUpdateDTO committed = updateFrom(before, "0");
      committed.getAiRuntime().setRetryMaxRetries(changedRetries);
      systemSettingsService.update(committed);

      assertNotification(pg, "1");
      assertNoNotification(pg);
      assertEquals(1L, currentVersion());
      assertEquals(changedRetries, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
    }
  }

  /** 未开启真实事务直接发布必须拒绝。 */
  @Test
  void notificationRequiresActiveTransaction() {
    assertThrows(IllegalStateException.class, () -> notifier.versionChanged(1L));
  }

  private long currentVersion() {
    Long version = jdbcTemplate.queryForObject(SELECT_VERSION, Long.class);
    if (version == null) {
      throw new AssertionError("system_setting seed row must exist");
    }
    return version;
  }

  private static void assertNotification(PGConnection pg, String expectedPayload) {
    List<PGNotification> notifications = drain(pg, 2000);
    assertEquals(1, notifications.size());
    assertEquals(SystemSettingsChangeHandler.CHANNEL, notifications.get(0).getName());
    assertEquals(expectedPayload, notifications.get(0).getParameter());
  }

  private static void assertNoNotification(PGConnection pg) {
    assertTrue(drain(pg, 200).isEmpty());
  }

  private static List<PGNotification> drain(PGConnection pg, int timeoutMillis) {
    try {
      PGNotification[] notifications = pg.getNotifications(timeoutMillis);
      return notifications == null ? List.of() : List.of(notifications);
    } catch (SQLException error) {
      throw new AssertionError("cannot read PostgreSQL notifications", error);
    }
  }

  private static SystemSettings withYolo(boolean yolo) {
    SystemSettings.Tool base = SystemSettings.DEFAULT.tool();
    return new SystemSettings(
        new SystemSettings.Tool(
            base.permission(),
            yolo,
            base.modelGatewayBusyRetryMillis(),
            base.toolGatewayBusyRetryMillis(),
            base.toolGatewayOverloadRetryMillis()),
        SystemSettings.DEFAULT.aiRuntime(),
        SystemSettings.DEFAULT.environment(),
        SystemSettings.DEFAULT.network(),
        SystemSettings.DEFAULT.integrations(),
        SystemSettings.DEFAULT.storageMedia(),
        SystemSettings.DEFAULT.advanced());
  }

  private static SystemSettingsUpdateDTO updateFrom(
      SystemSettingsDTO current, String expectedVersion) {
    SystemSettingsUpdateDTO update = new SystemSettingsUpdateDTO();
    update.setExpectedVersion(expectedVersion);
    update.setTool(current.getTool());
    update.setAiRuntime(current.getAiRuntime());
    update.setEnvironment(current.getEnvironment());
    update.setNetwork(current.getNetwork());
    update.setIntegrations(current.getIntegrations());
    update.setStorageMedia(current.getStorageMedia());
    update.setAdvanced(current.getAdvanced());
    return update;
  }
}
