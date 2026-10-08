package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * System settings 生产写入口的事务内 {@link PlatformNotifications#SETTINGS_CHANGED} 通知验证。
 *
 * <p>意图：{@code PostgresqlSystemSettingsRepository} 的成功 CAS 与通知共用同一事务，提交后投递写后权威 {@code version}；
 * 未提交不可见、回滚静默、陈旧 CAS 静默、无事务调用在写行前拒绝。{@code SystemSettingsServiceImpl} 的提交后快照刷新与本通知并存。
 */
class SystemSettingsChangeNotificationIntegrationTest extends PostgresSpringTestSupport {

  private static final String SELECT_VERSION = "select version from system_setting where id = 1";

  @Autowired private SystemSettingsService systemSettingsService;
  @Autowired private SystemSettingsRepository systemSettingsRepository;
  @Autowired private SystemSettingsSnapshot systemSettingsSnapshot;
  @Autowired private SystemSettingsChangeNotifier notifier;
  @Autowired private NotificationBus notificationBus;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 提交的 CAS 推进 version 并投递一次；同一事务未提交前不可见。 */
  @Test
  void committedCasNotifiesWithDecimalVersionAndUncommittedIsInvisible() throws Exception {
    BlockingQueue<Long> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            PlatformNotifications.SETTINGS_CHANGED, notifications::add, () -> {})) {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> {
            SystemSettingsRepository.SystemSettingsRecord current = systemSettingsRepository.get();
            assertTrue(systemSettingsRepository.update(withYolo(true), current.version()));
            assertNoNotification(notifications);
          });

      assertNotification(notifications, 1L);
      assertNoNotification(notifications);
      assertEquals(1L, currentVersion());
    }
  }

  /** 回滚的 CAS 绝不投递，权威 version 与配置保持不变。 */
  @Test
  void rolledBackCasIsSilent() {
    BlockingQueue<Long> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            PlatformNotifications.SETTINGS_CHANGED, notifications::add, () -> {})) {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> {
            SystemSettingsRepository.SystemSettingsRecord current = systemSettingsRepository.get();
            assertTrue(systemSettingsRepository.update(withYolo(true), current.version()));
            status.setRollbackOnly();
          });

      assertNoNotification(notifications);
      assertEquals(0L, currentVersion());
      assertFalse(systemSettingsRepository.get().settings().tool().defaultYolo());
    }
  }

  /** 陈旧 CAS 影响 0 行静默：version 未推进，没有通知。 */
  @Test
  void staleRepositoryCasIsSilent() {
    BlockingQueue<Long> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            PlatformNotifications.SETTINGS_CHANGED, notifications::add, () -> {})) {
      TransactionTemplate tx = new TransactionTemplate(transactionManager);

      tx.executeWithoutResult(
          status -> assertFalse(systemSettingsRepository.update(withYolo(true), 5L)));

      assertNoNotification(notifications);
      assertEquals(0L, currentVersion());
    }
  }

  /** 生产服务 PUT：通知落在其事务内；回滚既不投递也不刷新本节点快照，提交则同时投递并刷新。 */
  @Test
  void serviceUpdatePublishesInTransactionAndRollbackIsSilent() throws Exception {
    SystemSettingsDTO before = systemSettingsService.get();
    int beforeRetries = systemSettingsSnapshot.get().aiRuntime().retryMaxRetries();
    int changedRetries = beforeRetries == 9 ? 8 : 9;

    BlockingQueue<Long> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            PlatformNotifications.SETTINGS_CHANGED, notifications::add, () -> {})) {
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
      assertNoNotification(notifications);
      assertEquals(0L, currentVersion());
      assertSame(snapshotOnEntry, systemSettingsSnapshot.get());
      assertNotEquals(changedRetries, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());

      SystemSettingsUpdateDTO committed = updateFrom(before, "0");
      committed.getAiRuntime().setRetryMaxRetries(changedRetries);
      systemSettingsService.update(committed);

      assertNotification(notifications, 1L);
      assertNoNotification(notifications);
      assertEquals(1L, currentVersion());
      assertEquals(changedRetries, systemSettingsSnapshot.get().aiRuntime().retryMaxRetries());
    }
  }

  /** 无事务直接调用仓储 CAS：在执行 SQL 前拒绝，version 与配置原样不变，listener 静默。 */
  @Test
  void repositoryCasWithoutTransactionIsRejectedBeforeAnyWrite() {
    long versionBefore = currentVersion();
    BlockingQueue<Long> notifications = new LinkedBlockingQueue<>();
    try (NotificationSubscription ignored =
        notificationBus.subscribe(
            PlatformNotifications.SETTINGS_CHANGED, notifications::add, () -> {})) {
      assertThrows(
          IllegalStateException.class,
          () -> systemSettingsRepository.update(withYolo(true), versionBefore));

      assertEquals(versionBefore, currentVersion());
      assertFalse(systemSettingsRepository.get().settings().tool().defaultYolo());
      assertNoNotification(notifications);
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

  private static void assertNotification(BlockingQueue<Long> notifications, long expectedVersion)
      throws InterruptedException {
    assertEquals(expectedVersion, notifications.poll(2, TimeUnit.SECONDS));
    assertTrue(notifications.isEmpty());
  }

  private static void assertNoNotification(BlockingQueue<Long> notifications) {
    try {
      assertNull(notifications.poll(50, TimeUnit.MILLISECONDS));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
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
