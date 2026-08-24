package fun.fengwk.kkstudio.platform.storage.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.error.StorageConflictException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 基于 PostgreSQL + S3 的 {@link StorageBlobIngestService}。
 *
 * <p>每次摄入在调用方事务内完成：先查 ACTIVE 命中（命中走 retainRef），未命中则写入候选 blob 对象并以 {@code ref_count = 1}
 * 去重插入新行、同事务配对 session ref。S3 put 通过 NOT_SUPPORTED 模板暂停数据库事务；去重落败或外层事务回滚时，afterCompletion 只以独立短事务写入
 * DELETING 清理事实并本地 wake，绝不执行 S3。blob 计数变更全部经由 {@link StorageBlobManager} / {@link
 * SessionBlobRefManager} 的显式事务方法。
 *
 * @author fengwk
 */
@Slf4j
public class PostgresqlStorageBlobIngestService implements StorageBlobIngestService {

  private static final int MAX_DEDUP_ATTEMPTS = 3;

  private static final Pattern CANONICAL_MEDIA_TYPE =
      Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");

  private final StorageBlobRepository blobRepository;
  private final StorageBlobManager blobManager;
  private final SessionBlobRefManager refManager;
  private final S3StorageService s3StorageService;
  private final ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups;
  private final TransactionTemplate nonTransactional;
  private final TransactionTemplate cleanupTransaction;

  public PostgresqlStorageBlobIngestService(
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      SessionBlobRefManager refManager,
      S3StorageService s3StorageService,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups,
      PlatformTransactionManager transactionManager) {
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.maintenanceWakeups = Objects.requireNonNull(maintenanceWakeups, "maintenanceWakeups");
    PlatformTransactionManager requiredTransactionManager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    this.nonTransactional = new TransactionTemplate(requiredTransactionManager);
    this.nonTransactional.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    this.cleanupTransaction = new TransactionTemplate(requiredTransactionManager);
    this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID ingest(UUID sessionId, byte[] bytes, String mediaType) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(bytes, "bytes");
    if (mediaType == null || !CANONICAL_MEDIA_TYPE.matcher(mediaType).matches()) {
      throw new IllegalArgumentException(
          "mediaType must be canonical lowercase type/subtype without parameters");
    }
    if (bytes.length == 0) {
      throw new IllegalArgumentException("bytes must not be empty");
    }
    String sha256 = sha256Hex(bytes);
    long sizeBytes = bytes.length;
    UUID candidateBlobId = null;
    AtomicBoolean candidateKeepOnCommit = null;
    for (int attempt = 1; attempt <= MAX_DEDUP_ATTEMPTS; attempt++) {
      StorageBlob active = blobRepository.getActiveByHashAndSize(sha256, sizeBytes);
      if (active != null) {
        // 命中既有 ACTIVE 行：insert-if-absent + retain-once（Session 已引用时不重复计数）。
        refManager.retainRef(sessionId, active.getId());
        return active.getId();
      }
      if (candidateBlobId == null) {
        candidateBlobId = UUID.randomUUID();
        // 暂停外层数据库事务执行 S3 put；成功后注册事务完成回调，失败/去重落败只写 DELETING
        // 清理事实并唤醒后台，不在提交线程删除对象。
        UUID putCandidateBlobId = candidateBlobId;
        nonTransactional.executeWithoutResult(
            status ->
                s3StorageService.putObject(
                    StorageObjectKeys.blobOriginal(putCandidateBlobId),
                    new ByteArrayInputStream(bytes),
                    sizeBytes,
                    mediaType));
        candidateKeepOnCommit = new AtomicBoolean(false);
        registerCandidateCleanup(
            newCandidateBlob(candidateBlobId, sha256, sizeBytes, mediaType), candidateKeepOnCommit);
      }
      StorageBlob candidate = newCandidateBlob(candidateBlobId, sha256, sizeBytes, mediaType);
      if (blobRepository.insertActiveCandidate(candidate)) {
        // 新行 ref_count = 1 已计入即将插入的 ref：只插入 ref 行，不重复 retain。
        if (!refManager.insertRefIfAbsent(sessionId, candidateBlobId)) {
          throw new IllegalStateException(
              "session blob ref unexpectedly existed for a new blob " + candidateBlobId);
        }
        // 新行 + ref 已同事务配对成功：仅在提交时保留对象，回滚仍清理。
        candidateKeepOnCommit.set(true);
        return candidateBlobId;
      }
      // 去重落败：候选对象从未被引用（keepOnCommit 保持 false），事务完成后清理；下一轮改读既有 ACTIVE 行。
    }
    throw new StorageConflictException("blob dedup resolution failed for ingest");
  }

  private StorageBlob newCandidateBlob(UUID id, String sha256, long sizeBytes, String mediaType) {
    StorageBlob blob = new StorageBlob();
    blob.setId(id);
    blob.setSha256(sha256);
    blob.setSizeBytes(sizeBytes);
    blob.setMediaType(mediaType);
    blob.setWidth(null);
    blob.setHeight(null);
    blob.setDurationMs(null);
    blob.setRefCount(1L);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  /**
   * 每个候选对象只注册一次 durable cleanup：putObject 成功后立即注册。keepOnCommit 默认 false（任何失败/去重落败都写 DELETING 事实）；新行
   * + ref 配对成功后翻转为 true（仅提交保留），绝不在事务完成线程执行 S3。
   */
  private void registerCandidateCleanup(StorageBlob candidate, AtomicBoolean keepOnCommit) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (!keepOnCommit.get() || status != TransactionSynchronization.STATUS_COMMITTED) {
              try {
                cleanupTransaction.executeWithoutResult(
                    ignored -> blobRepository.insertDeletingCandidate(candidate));
                StorageMaintenanceWakeup wakeup = maintenanceWakeups.getIfAvailable();
                if (wakeup != null) {
                  wakeup.wake();
                }
              } catch (RuntimeException error) {
                log.warn(
                    "storage ingest candidate maintenance enqueue failed for {}: {}",
                    candidate.getId(),
                    error.getMessage());
              }
            }
          }
        });
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
