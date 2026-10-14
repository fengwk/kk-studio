package fun.fengwk.kkstudio.platform.storage.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 基于 PostgreSQL 记录与 S3 幂等删除的 {@link StorageObjectCleanupService}。
 *
 * <p>{@link #enqueue(String)} 只是调用方事务内的一条幂等 insert，因此与事实变更同生共死；对象删除永远发生在 {@link #sweepOnce()}
 * 的数据库事务之外， 清理路径不会在 S3 I/O 期间持有任何事务或行锁。
 *
 * <p>服务不持有 JVM 时钟：认领与重试都只把非负毫秒延后交给数据库，由 {@code statement_timestamp()} 换算下次尝试时间，
 * 因此节点时钟偏移不会让记录在过去被反复即时认领。
 *
 * @author fengwk
 */
@Slf4j
public class PostgresqlStorageObjectCleanupService implements StorageObjectCleanupService {

  private final StorageObjectCleanupRepository cleanupRepository;
  private final S3StorageService s3StorageService;
  private final long cleanupIntervalMillis;
  private final long retryDelayMillis;

  public PostgresqlStorageObjectCleanupService(
      StorageObjectCleanupRepository cleanupRepository,
      S3StorageService s3StorageService,
      StorageMaintenanceProperties maintenanceProperties) {
    this.cleanupRepository =
        Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
    this.s3StorageService =
        Objects.requireNonNull(s3StorageService, "s3StorageService must not be null");
    Objects.requireNonNull(maintenanceProperties, "maintenanceProperties must not be null");
    this.cleanupIntervalMillis =
        toDelayMillis(maintenanceProperties.getObjectCleanupInterval(), "objectCleanupInterval");
    this.retryDelayMillis =
        toDelayMillis(
            maintenanceProperties.getObjectCleanupRetryDelay(), "objectCleanupRetryDelay");
  }

  @Override
  public void enqueue(String objectKey) {
    Assert.hasText(objectKey, "objectKey must not be blank");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "storage object cleanup enqueue requires an active transaction so the record commits"
              + " with the fact it protects");
    }
    cleanupRepository.insertIfAbsent(objectKey);
  }

  @Override
  public int sweepOnce() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "storage object cleanup sweep must run outside a transaction; object deletion is S3 I/O");
    }
    List<String> claimed = cleanupRepository.claimDue(MAX_CLEANUP_BATCH, cleanupIntervalMillis);
    for (String objectKey : claimed) {
      try {
        s3StorageService.deleteObjectIfExists(objectKey);
      } catch (RuntimeException error) {
        // 认领已经把 next_attempt_at 推出，这里再改到短重试；即使该更新失败，记录仍在下一个长周期后被重试。
        log.warn("storage object cleanup failed for key {}", objectKey);
        try {
          cleanupRepository.reschedule(objectKey, retryDelayMillis);
        } catch (RuntimeException rescheduleError) {
          log.warn("storage object cleanup reschedule failed for key {}", objectKey);
        }
      }
    }
    return claimed.size();
  }

  /** 把配置时长换算成 SQL 安全参数：必须为正、至少 1 毫秒且 {@link Duration#toMillis()} 不溢出， 否则直接拒绝配置而不是让错误值进入数据库。 */
  private static long toDelayMillis(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    long millis;
    try {
      millis = value.toMillis();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(name + " is too large", error);
    }
    if (millis <= 0L) {
      throw new IllegalArgumentException(name + " must be at least 1 millisecond");
    }
    return millis;
  }
}
