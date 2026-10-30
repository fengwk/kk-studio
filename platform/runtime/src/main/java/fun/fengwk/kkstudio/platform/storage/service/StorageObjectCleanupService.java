package fun.fengwk.kkstudio.platform.storage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 对象物理删除的耐久登记与后台收敛。
 *
 * <p>凡是「对象 key 的最后一个数据库事实即将消失」的路径，都必须在同一事务内先 {@link #enqueue(String)}：登记记录本身永不按 TTL 删除，因此迟到的浏览器直传
 * PUT、服务端 COPY 或预览写入得以在后续清扫中最终被删除，而不是在无数据库引用的情况下长期残留。 调用方不得为仍被任何 ACTIVE 行引用的对象登记清理。
 *
 * <p>{@link #enqueue(String)} 只是调用方事务内的一条幂等 insert，因此与事实变更同生共死；对象删除永远发生在 {@link #sweepOnce()}
 * 的数据库事务之外， 清理路径不会在 S3 I/O 期间持有任何事务或行锁。服务不持有 JVM 时钟：认领与重试都只把非负毫秒延后交给数据库，由 {@code
 * statement_timestamp()} 换算下次尝试时间，因此节点时钟偏移不会让记录在过去被反复即时认领。
 *
 * @author fengwk
 */
@Slf4j
public class StorageObjectCleanupService {

  /** 单次清扫最多认领的到期记录数；后台维护据此判断是否已排空。 */
  public static final int MAX_CLEANUP_BATCH = 16;

  private final StorageObjectCleanupRepository cleanupRepository;
  private final S3StorageService s3StorageService;
  private final long cleanupIntervalMillis;
  private final long retryDelayMillis;

  public StorageObjectCleanupService(
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

  /**
   * 幂等登记一个不可再绑定的对象 key；必须与「移除该 key 最后一个数据库事实」的变更处于同一事务，回滚时登记一并撤销。
   * 调用方不在活动事务中时直接拒绝，避免出现「事实已回滚但清理记录已提交」的伪证据。
   *
   * @param objectKey 由 {@link fun.fengwk.kkstudio.platform.storage.StorageObjectKeys} 推导的确定性对象 key
   */
  public void enqueue(String objectKey) {
    Assert.hasText(objectKey, "objectKey must not be blank");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "storage object cleanup enqueue requires an active transaction so the record commits"
              + " with the fact it protects");
    }
    cleanupRepository.insertIfAbsent(objectKey);
  }

  /**
   * 认领一批到期记录并在事务外幂等删除对应对象：到期判定与下次尝试时间全部由数据库时间换算（调用方只提供毫秒延后）， 因此节点时钟偏移不会让记录在过去被反复即时认领；认领先把 {@code
   * next_attempt_at} 推到未来，失败再改到短重试截止点，批次排空有界且失败不造成头部阻塞。返回本次认领的记录数。
   *
   * <p>该方法必须在事务外调用：对象删除是 S3 I/O，不得在数据库事务内执行。
   */
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
