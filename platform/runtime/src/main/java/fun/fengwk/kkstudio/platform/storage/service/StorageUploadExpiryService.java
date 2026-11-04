package fun.fengwk.kkstudio.platform.storage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 过期或已请求清理的 upload 回收。
 *
 * <p>候选扫描只读快照；逐行先取 upload 操作锁，再以同一份当前 TTL 快照重新判定是否 due 并 claim cleanup lease。对象删除在事务外执行，最后以
 * token-fenced 短事务删除上传事实；锁忙或单行失败只跳过本轮，下一次周期扫描继续收敛。
 *
 * <p>过期判定统一走 {@link #isExpired(Instant, Instant, long)}：{@code created_at + 当前 TTL <= now}。显式
 * cleanup request 与已 claim 的行不受 TTL 影响，cleanup 一旦开始就不会因延长 TTL 而复活。
 */
@Slf4j
final class StorageUploadExpiryService {

  /** 过期批次上限：每次后台回收最多检查的候选 upload 行数；后台维护据此判断是否已排空。 */
  static final int MAX_EXPIRY_BATCH = 16;

  private final StorageUploadRepository uploadRepository;
  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;
  private final StorageObjectCleanupService objectCleanupService;
  private final StorageUploadOperationLock operationLock;
  private final SystemSettingsSnapshot settingsSnapshot;
  private final Clock clock;
  private final Duration cleanupLease;
  private final TransactionTemplate transactionTemplate;

  StorageUploadExpiryService(
      StorageUploadRepository uploadRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      StorageObjectCleanupService objectCleanupService,
      StorageUploadOperationLock operationLock,
      SystemSettingsSnapshot settingsSnapshot,
      Clock clock,
      Duration cleanupLease,
      TransactionTemplate transactionTemplate) {
    this.uploadRepository = Objects.requireNonNull(uploadRepository, "uploadRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.objectCleanupService =
        Objects.requireNonNull(objectCleanupService, "objectCleanupService");
    this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
    this.settingsSnapshot = Objects.requireNonNull(settingsSnapshot, "settingsSnapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.cleanupLease = requirePositiveDuration(cleanupLease, "cleanupLease");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  /**
   * 后台回收：先列出可清理的候选行，再逐行先取该 upload 的操作锁、锁内重读当下事实并 claim，事务外删除对象，最后以 token-fenced 短事务删除行。
   * 锁忙（complete/stage 正在写对象或另一节点在处理）的行跳过本轮，绝不在无锁状态下删除证据。返回成功 finalize 的行数，单行失败保留 lease 且不中断批次。
   */
  int expireOnce() {
    long scanTtl = settingsSnapshot.get().storageMedia().uploadExpiresSeconds();
    String cleanupToken = UUID.randomUUID().toString();
    List<UUID> candidates =
        transactionTemplate.execute(
            status -> {
              Instant now = clock.instant();
              return uploadRepository.listCleanupCandidateIds(
                  MAX_EXPIRY_BATCH, now, expiryCutoff(now, scanTtl));
            });
    int finalized = 0;
    for (UUID uploadId : candidates) {
      try (StorageUploadOperationLock.Handle handle = operationLock.tryAcquire(uploadId)) {
        if (handle == null) {
          continue;
        }
        try {
          if (cleanupClaimedUpload(uploadId, cleanupToken)) {
            finalized++;
          }
        } catch (RuntimeException error) {
          // 对象或 finalize 失败时保留 lease；过期后由任意节点幂等重试。
          log.warn(
              "storage expiry failed for upload {}: {}",
              uploadId,
              error.getClass().getSimpleName());
        }
      }
    }
    return finalized;
  }

  /**
   * 持锁后重新读取当下事实与最新 policy 并 claim 单行清理事实；不再满足 claim 条件时跳过，绝不使用过期快照。
   *
   * <p>这里现读一份 TTL 快照：扫描之后、claim 之前发生的 extend 会让本行不再过期从而被跳过（不误删）；已请求清理或已 claim 的行不受 TTL 影响，只用于把清理续完。
   */
  private boolean cleanupClaimedUpload(UUID uploadId, String cleanupToken) {
    Instant claimNow = clock.instant();
    long uploadTtl = settingsSnapshot.get().storageMedia().uploadExpiresSeconds();
    StorageUpload claimed =
        transactionTemplate.execute(
            status -> {
              StorageUpload fresh = uploadRepository.getById(uploadId);
              if (!isCleanupDue(fresh, claimNow, uploadTtl)) {
                return null;
              }
              StorageUpload claimedRow =
                  uploadRepository.claimById(
                      uploadId, claimNow, claimNow.plus(cleanupLease), cleanupToken);
              if (claimedRow != null) {
                // 与 claim 同一事务登记：claim 冻结了之后的绑定（setBlobIdIfNull 要求未 claim），
                // 因此这一刻起该 upload 的对象 key 不再可能被绑定为事实；迟到的 PUT/COPY 由耐久记录收敛。
                enqueueCleanupTargets(claimedRow);
              }
              return claimedRow;
            });
    if (claimed == null) {
      return false;
    }
    cleanupUploadObjects(claimed);
    return finalizeClaimed(claimed, cleanupToken);
  }

  /**
   * 登记该 upload 的确定性对象 key：临时对象，以及未被绑定为自身 blob 的 candidate 原始/预览对象。
   *
   * <p>{@code candidate_blob_id} 一旦等于 {@code blob_id} 就是被正式引用的 ACTIVE blob，其对象由 blob 生命周期负责，
   * 绝不在此登记；其余情况下 candidate 行不存在或注定被回收，登记后即使迟到写入也会被清扫。
   */
  private void enqueueCleanupTargets(StorageUpload upload) {
    objectCleanupService.enqueue(StorageObjectKeys.uploadOriginal(upload.getId()));
    UUID candidateBlobId = upload.getCandidateBlobId();
    if (candidateBlobId != null && !candidateBlobId.equals(upload.getBlobId())) {
      objectCleanupService.enqueue(StorageObjectKeys.blobPreview(candidateBlobId));
      objectCleanupService.enqueue(StorageObjectKeys.blobOriginal(candidateBlobId));
    }
  }

  /**
   * 当下事实是否可清理：显式 cleanup request、已 claim（cleanup_token 非空）或已过期；且没有有效 lease。
   *
   * <p>已请求/已 claim 的行不受 TTL 影响：cleanup 一旦开始就只沿 finalize 收敛，延长 TTL 绝不复活；即使 claim lease
   * 已过期，也只是让任意节点续完清理，绝不回到可存活/可绑定状态。
   */
  static boolean isCleanupDue(StorageUpload upload, Instant now, long uploadTtlSeconds) {
    if (upload == null) {
      return false;
    }
    boolean cleanupStarted =
        upload.getCleanupRequestedAt() != null || upload.getCleanupToken() != null;
    if (!cleanupStarted && !isExpired(upload.getCreateTime(), now, uploadTtlSeconds)) {
      return false;
    }
    return upload.getCleanupToken() == null || !upload.getCleanupUntil().isAfter(now);
  }

  /** 过期权威事实：{@code created_at + 当前 TTL <= now}。 */
  static boolean isExpired(Instant createdAt, Instant now, long uploadTtlSeconds) {
    return !createdAt.isAfter(expiryCutoff(now, uploadTtlSeconds));
  }

  /** 绑定/候选的索引阈值：{@code created_at <= expiryCutoff} 即过期。 */
  static Instant expiryCutoff(Instant now, long uploadTtlSeconds) {
    return now.minusSeconds(uploadTtlSeconds);
  }

  /** 幂等清理未使用的候选对象：仅当候选 id 未被绑定为最终 blob 时删除。 */
  void cleanupUnusedCandidate(StorageUpload upload) {
    if (upload.getBlobId() == null || !upload.getBlobId().equals(upload.getCandidateBlobId())) {
      cleanupCandidateObjects(upload.getCandidateBlobId());
    }
  }

  /** 幂等清理候选对象的预览与原始内容：预览先于原始，与两阶段删除顺序一致。 */
  void cleanupCandidateObjects(UUID candidateBlobId) {
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobPreview(candidateBlobId));
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobOriginal(candidateBlobId));
  }

  void cleanupCandidateObjectsBestEffort(UUID candidateBlobId) {
    deleteObjectBestEffort(StorageObjectKeys.blobPreview(candidateBlobId));
    deleteObjectBestEffort(StorageObjectKeys.blobOriginal(candidateBlobId));
  }

  void deleteObjectBestEffort(String key) {
    try {
      s3StorageService.deleteObjectIfExists(key);
    } catch (RuntimeException error) {
      log.warn("deferred cleanup for storage object {}: {}", key, error.getClass().getSimpleName());
    }
  }

  private void cleanupUploadObjects(StorageUpload upload) {
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(upload.getId()));
    cleanupUnusedCandidate(upload);
  }

  private boolean finalizeClaimed(StorageUpload upload, String cleanupToken) {
    Boolean finalized =
        transactionTemplate.execute(
            status -> {
              if (upload.getBlobId() == null) {
                return uploadRepository.finalizePending(upload.getId(), cleanupToken);
              }
              if (!uploadRepository.finalizeReady(
                  upload.getId(), upload.getBlobId(), cleanupToken)) {
                return false;
              }
              if (upload.getCleanupRequestedAt() == null) {
                blobManager.release(upload.getBlobId());
              }
              return true;
            });
    return Boolean.TRUE.equals(finalized);
  }

  private static Duration requirePositiveDuration(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
