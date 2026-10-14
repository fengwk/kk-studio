package fun.fengwk.kkstudio.platform.storage.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;

import java.time.Duration;
import java.util.List;

/**
 * 对象清理服务只把「非负毫秒延后」交给数据库，不携带任何 JVM 绝对时间。
 *
 * <p>测试意图：认领/重试参数必须是配置时长换算出的毫秒值（数据库用它加 {@code statement_timestamp()}），因此节点时钟偏移无法把记录推到过去；
 * 同时校验事务契约（enqueue 必须有活动事务、sweep 必须在事务外）与不安全配置的拒绝路径。
 */
class PostgresqlStorageObjectCleanupServiceTest {

  private static final long INTERVAL_MILLIS = 7_200_000L;
  private static final long RETRY_MILLIS = 45_000L;

  @AfterEach
  void clearTransactionState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void sweepClaimsWithTheConfiguredDatabaseDelayAndDeletesOutsideTransactions() {
    StorageObjectCleanupRepository repository = mock(StorageObjectCleanupRepository.class);
    when(repository.claimDue(StorageObjectCleanupService.MAX_CLEANUP_BATCH, INTERVAL_MILLIS))
        .thenReturn(List.of("a", "b"));
    S3StorageService s3 = mock(S3StorageService.class);
    PostgresqlStorageObjectCleanupService service =
        new PostgresqlStorageObjectCleanupService(
            repository, s3, properties(Duration.ofHours(2), Duration.ofSeconds(45)));

    assertEquals(2, service.sweepOnce());

    verify(repository).claimDue(StorageObjectCleanupService.MAX_CLEANUP_BATCH, INTERVAL_MILLIS);
    verify(s3).deleteObjectIfExists("a");
    verify(s3).deleteObjectIfExists("b");
    verify(repository, never()).reschedule(anyString(), anyLong());
  }

  @Test
  void failedDeleteReschedulesWithTheShortRetryDelay() {
    StorageObjectCleanupRepository repository = mock(StorageObjectCleanupRepository.class);
    when(repository.claimDue(StorageObjectCleanupService.MAX_CLEANUP_BATCH, INTERVAL_MILLIS))
        .thenReturn(List.of("a"));
    S3StorageService s3 = mock(S3StorageService.class);
    doThrow(new IllegalStateException("S3 unavailable")).when(s3).deleteObjectIfExists("a");
    PostgresqlStorageObjectCleanupService service =
        new PostgresqlStorageObjectCleanupService(
            repository, s3, properties(Duration.ofHours(2), Duration.ofSeconds(45)));

    assertEquals(1, service.sweepOnce());

    verify(repository).reschedule("a", RETRY_MILLIS);
  }

  @Test
  void aFailingRescheduleNeverEscapesTheSweep() {
    StorageObjectCleanupRepository repository = mock(StorageObjectCleanupRepository.class);
    when(repository.claimDue(StorageObjectCleanupService.MAX_CLEANUP_BATCH, INTERVAL_MILLIS))
        .thenReturn(List.of("a"));
    when(repository.reschedule("a", RETRY_MILLIS))
        .thenThrow(new IllegalStateException("database unavailable"));
    S3StorageService s3 = mock(S3StorageService.class);
    doThrow(new IllegalStateException("S3 unavailable")).when(s3).deleteObjectIfExists("a");
    PostgresqlStorageObjectCleanupService service =
        new PostgresqlStorageObjectCleanupService(
            repository, s3, properties(Duration.ofHours(2), Duration.ofSeconds(45)));

    // 认领已经把 next_attempt_at 推后，重试更新失败也不能让异常逃出去打断本轮清扫。
    assertEquals(1, service.sweepOnce());

    verify(repository).reschedule("a", RETRY_MILLIS);
  }

  @Test
  void enqueueRequiresAnActiveTransaction() {
    StorageObjectCleanupRepository repository = mock(StorageObjectCleanupRepository.class);
    PostgresqlStorageObjectCleanupService service =
        new PostgresqlStorageObjectCleanupService(
            repository, mock(S3StorageService.class), properties());

    assertThrows(
        IllegalStateException.class,
        () -> service.enqueue("uploads/x/original"),
        "a record must never be committed outside the fact it protects");
    verify(repository, never()).insertIfAbsent(anyString());

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    service.enqueue("uploads/x/original");
    verify(repository).insertIfAbsent("uploads/x/original");
  }

  @Test
  void sweepRefusesToRunInsideADatabaseTransaction() {
    PostgresqlStorageObjectCleanupService service =
        new PostgresqlStorageObjectCleanupService(
            mock(StorageObjectCleanupRepository.class), mock(S3StorageService.class), properties());

    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThrows(
        IllegalStateException.class,
        service::sweepOnce,
        "object deletion is S3 I/O and must never run inside a database transaction");
  }

  @Test
  void rejectsUnsafeConfiguredDelays() {
    StorageObjectCleanupRepository repository = mock(StorageObjectCleanupRepository.class);
    S3StorageService s3 = mock(S3StorageService.class);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlStorageObjectCleanupService(
                repository, s3, properties(Duration.ZERO, Duration.ofSeconds(45))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlStorageObjectCleanupService(
                repository, s3, properties(Duration.ofNanos(1), Duration.ofSeconds(45))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlStorageObjectCleanupService(
                repository,
                s3,
                properties(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(45))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PostgresqlStorageObjectCleanupService(
                repository, s3, properties(Duration.ofHours(2), Duration.ZERO)));
    assertThrows(
        NullPointerException.class,
        () -> new PostgresqlStorageObjectCleanupService(repository, s3, null));
  }

  private static StorageMaintenanceProperties properties() {
    return properties(Duration.ofHours(2), Duration.ofSeconds(30));
  }

  private static StorageMaintenanceProperties properties(
      Duration cleanupInterval, Duration retryDelay) {
    StorageMaintenanceProperties properties = new StorageMaintenanceProperties();
    properties.setObjectCleanupInterval(cleanupInterval);
    properties.setObjectCleanupRetryDelay(retryDelay);
    return properties;
  }
}
