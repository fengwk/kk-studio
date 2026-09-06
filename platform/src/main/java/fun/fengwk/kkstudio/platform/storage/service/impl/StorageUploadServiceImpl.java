package fun.fengwk.kkstudio.platform.storage.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.S3PresignedUrl;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.error.StorageConflictException;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.service.StoragePresignedUrls;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageMediaFacts;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局 Blob 存储上传契约服务的默认实现。
 *
 * <p>事务边界全部由 {@link TransactionTemplate} 显式控制：S3 的 HEAD/探针/复制/删除绝不发生在数据库事务或上传行锁内。显式删除/消费先在短事务内持久化
 * cleanup request，READY 恰好 release 上传引用并提交后唤醒后台；过期与请求清理再 claim cleanup lease，事务外幂等删除临时/候选对象，最后以
 * token-fenced 短事务删除上传事实，显式请求 READY 不重复 release。
 *
 * <p>complete 先校验并探针临时对象，再复制候选 blob 的最终对象，最后才在事务内去重插入/并发消解并绑定上传 —— DB 绝不引用缺失的最终对象； 同一上传的并发 complete
 * 通过 {@code setBlobIdIfNull} 只成功一次。READY 重试路径幂等并自愈（最终对象缺失时从临时对象补复制），并清理残留的临时/未使用候选对象。
 *
 * @author fengwk
 */
@Slf4j
public class StorageUploadServiceImpl implements StorageUploadService {

  private static final int MAX_DEDUP_ATTEMPTS = 3;

  private final StorageUploadRepository uploadRepository;
  private final StorageBlobRepository blobRepository;
  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;
  private final S3PresignService s3PresignService;
  private final StorageMediaProbe mediaProbe;
  private final ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups;
  private final S3StorageProperties s3Properties;
  private final SystemSettings.StorageMedia storageMedia;
  private final Clock clock;
  private final Duration cleanupLease;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate mandatoryTransactionTemplate;

  public StorageUploadServiceImpl(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      StorageMediaProbe mediaProbe,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups,
      S3StorageProperties s3Properties,
      SystemSettings.StorageMedia storageMedia,
      StorageMaintenanceProperties maintenanceProperties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.uploadRepository = Objects.requireNonNull(uploadRepository, "uploadRepository");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.s3PresignService = Objects.requireNonNull(s3PresignService, "s3PresignService");
    this.mediaProbe = Objects.requireNonNull(mediaProbe, "mediaProbe");
    this.maintenanceWakeups = Objects.requireNonNull(maintenanceWakeups, "maintenanceWakeups");
    this.s3Properties = Objects.requireNonNull(s3Properties, "s3Properties");
    this.storageMedia = Objects.requireNonNull(storageMedia, "storageMedia");
    this.cleanupLease =
        requirePositiveDuration(
            Objects.requireNonNull(maintenanceProperties, "maintenanceProperties")
                .getCleanupLease(),
            "cleanupLease");
    this.clock = Objects.requireNonNull(clock, "clock");
    PlatformTransactionManager requiredTransactionManager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    this.transactionTemplate = new TransactionTemplate(requiredTransactionManager);
    this.mandatoryTransactionTemplate = new TransactionTemplate(requiredTransactionManager);
    this.mandatoryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_MANDATORY);
  }

  @Override
  public StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
    Objects.requireNonNull(request, "request must not be null");
    String filename = validateFilename(request.getFilename());
    String mediaType = normalizeMediaType(request.getMediaType());
    long sizeBytes = validateSize(request.getSizeBytes());
    String sha256 = validateSha256(request.getSha256());

    UUID uploadId = UUID.randomUUID();
    UUID candidateBlobId = UUID.randomUUID();
    Instant expiresAt = clock.instant().plusSeconds(storageMedia.uploadExpiresSeconds());
    ReserveOutcome outcome =
        transactionTemplate.execute(
            status -> {
              UUID hitBlobId = null;
              StorageBlob active = blobRepository.getActiveByHashAndSize(sha256, sizeBytes);
              if (active != null) {
                try {
                  blobManager.retain(active.getId());
                  hitBlobId = active.getId();
                } catch (StorageResourceNotFoundException e) {
                  // ACTIVE 行在并发中被释放到 DELETING：按未命中处理。
                }
              }
              StorageUpload upload =
                  newUpload(
                      uploadId,
                      candidateBlobId,
                      hitBlobId,
                      filename,
                      mediaType,
                      sizeBytes,
                      sha256,
                      expiresAt);
              if (!uploadRepository.insert(upload)) {
                throw new IllegalStateException("insert storage upload failed: " + uploadId);
              }
              return new ReserveOutcome(upload, hitBlobId);
            });

    StoragePresignedUrlDTO presignedPut = null;
    if (outcome.blobId == null) {
      S3PresignedUrl signed =
          s3PresignService.presignChecksummedCreateOnlyUpload(
              StorageObjectKeys.uploadOriginal(uploadId),
              mediaType,
              Base64.getEncoder().encodeToString(decodeHex(sha256)),
              presignExpiresSeconds());
      presignedPut = StoragePresignedUrls.from(signed);
    }
    return toDTO(
        outcome.upload.getId(), outcome.blobId, outcome.upload.getExpiresAt(), presignedPut);
  }

  @Override
  public StorageUploadDTO complete(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");

    StorageUpload upload = uploadRepository.getById(uploadId);
    if (upload == null) {
      throw new StorageResourceNotFoundException("upload", uploadId.toString());
    }
    requireCleanupAvailable(upload);
    if (upload.getBlobId() != null) {
      // READY 重试：幂等并自愈最终对象，随后清理残留的临时对象与未使用的候选对象。
      ensureBlobOriginal(upload.getBlobId(), uploadId);
      s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(uploadId));
      cleanupUnusedCandidate(upload);
      return toDTO(uploadId, upload.getBlobId(), upload.getExpiresAt(), null);
    }

    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    S3ObjectMetadata head = headObjectWithChecksumOrFail(uploadId, tempKey);
    verifyObject(head, upload);
    StorageMediaFacts facts = mediaProbe.probe(tempKey, head);

    // 先物化候选 blob 的最终对象，再让 DB 引用它：DB 绝不引用缺失的最终对象。
    String candidateKey = StorageObjectKeys.blobOriginal(upload.getCandidateBlobId());
    s3StorageService.copyObject(tempKey, candidateKey);

    UUID blobId = transactionTemplate.execute(status -> resolveBlobAndBindUpload(upload, facts));
    if (!blobId.equals(upload.getCandidateBlobId())) {
      // 去重落败：未使用的候选对象幂等清理（预览先于原始）；READY 上传行（candidate_blob_id）
      // 是崩溃窗口的恢复证据。
      cleanupCandidateObjects(upload.getCandidateBlobId());
    }
    s3StorageService.deleteObjectIfExists(tempKey);
    return toDTO(uploadId, blobId, upload.getExpiresAt(), null);
  }

  @Override
  public void delete(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      requestCleanupInCurrentTransaction(uploadId);
      return;
    }
    transactionTemplate.execute(
        status -> {
          requestCleanupInCurrentTransaction(uploadId);
          return null;
        });
  }

  @Override
  public ReadyUpload lockReady(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    return mandatoryTransactionTemplate.execute(
        status -> {
          StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
          if (locked == null) {
            throw new StorageResourceNotFoundException("upload", uploadId.toString());
          }
          if (locked.getBlobId() == null) {
            throw new StorageVerificationException(
                "upload " + uploadId + " is PENDING; complete it before consuming");
          }
          requireCleanupAvailable(locked);
          return new ReadyUpload(locked.getBlobId(), locked.getFilename());
        });
  }

  @Override
  public int expireOnce() {
    String cleanupToken = UUID.randomUUID().toString();
    Instant now = clock.instant();
    List<StorageUpload> claimable =
        transactionTemplate.execute(
            status ->
                uploadRepository.claimExpired(
                    StorageUploadService.MAX_EXPIRY_BATCH,
                    now,
                    now.plus(cleanupLease),
                    cleanupToken));
    int finalized = 0;
    for (StorageUpload row : claimable) {
      try {
        cleanupUploadObjects(row);
        if (finalizeClaimed(row, cleanupToken)) {
          finalized++;
        }
      } catch (RuntimeException error) {
        // 对象或 finalize 失败时保留 lease；过期后由任意节点幂等重试。
        log.warn("storage expiry failed for upload {}: {}", row.getId(), error.getMessage());
      }
    }
    return finalized;
  }

  /** 在上传行锁保护的事务内消解或创建去重 Blob，并与当前上传记录安全绑定。 */
  private UUID resolveBlobAndBindUpload(StorageUpload upload, StorageMediaFacts facts) {
    // 先在绑定事务内锁定上传行：同一上传的并发 complete 在行锁上串行，保证 retain/insert 只发生在
    // 最终成功绑定的一方，杜绝对已有 ACTIVE blob 的重复计数。
    StorageUpload locked = uploadRepository.getByIdForUpdate(upload.getId());
    if (locked == null) {
      // 并发 delete/过期已删除行：上传不存在，事务回滚（含任何 blob 写入）。
      throw new StorageResourceNotFoundException("upload", upload.getId().toString());
    }
    requireCleanupAvailable(locked);
    if (locked.getBlobId() != null) {
      // 并发 complete 已绑定：直接取其结果，不重复 retain。
      return locked.getBlobId();
    }
    for (int attempt = 1; attempt <= MAX_DEDUP_ATTEMPTS; attempt++) {
      boolean inserted = blobRepository.insertActiveCandidate(newCandidateBlob(upload, facts));
      StorageBlob active =
          blobRepository.getActiveByHashAndSize(
              upload.getDeclaredSha256(), upload.getDeclaredSize());
      if (active == null) {
        // 消解出的 ACTIVE 行在并发中被回收：下一轮重新插入候选行。
        if (attempt == MAX_DEDUP_ATTEMPTS) {
          throw new StorageConflictException(
              "blob dedup resolution failed for upload " + upload.getId());
        }
        continue;
      }
      if (!inserted
          && !active.getId().equals(upload.getCandidateBlobId())
          && !blobRepository.incrementRefCount(active.getId())) {
        // 消解出的行恰好被并发 release 到 DELETING：下一轮重新插入候选行。
        if (attempt == MAX_DEDUP_ATTEMPTS) {
          throw new StorageConflictException(
              "blob dedup resolution failed for upload " + upload.getId());
        }
        continue;
      }
      // 行锁在手：setBlobIdIfNull 不可能被并发 complete 抢走，失败即上传行状态异常。
      if (!uploadRepository.setBlobIdIfNull(upload.getId(), active.getId(), clock.instant())) {
        throw new IllegalStateException("upload " + upload.getId() + " changed while completing");
      }
      return active.getId();
    }
    throw new StorageConflictException("blob dedup resolution failed for upload " + upload.getId());
  }

  /**
   * 幂等清理未使用的候选对象：仅当候选 id 未被绑定为最终 blob 时删除。
   *
   * <p>候选对象在 complete 预复制后可能因去重落败或事务失败而未被引用，此时上传行（{@code candidate_blob_id}）仍保留着恢复证据，
   * delete/过期路径与本方法据此清理，避免孤儿对象。
   */
  private void cleanupUnusedCandidate(StorageUpload upload) {
    if (upload.getBlobId() == null || !upload.getBlobId().equals(upload.getCandidateBlobId())) {
      cleanupCandidateObjects(upload.getCandidateBlobId());
    }
  }

  /** 幂等清理候选对象的预览与原始内容：预览先于原始，与两阶段删除顺序一致。 */
  private void cleanupCandidateObjects(UUID candidateBlobId) {
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobPreview(candidateBlobId));
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobOriginal(candidateBlobId));
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

  private void requestCleanupInCurrentTransaction(UUID uploadId) {
    StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
    if (locked == null) {
      throw new StorageResourceNotFoundException("upload", uploadId.toString());
    }
    if (locked.getCleanupToken() != null) {
      throw new StorageVerificationException("upload " + uploadId + " cleanup is already claimed");
    }
    if (locked.getCleanupRequestedAt() != null) {
      return;
    }
    if (!uploadRepository.markCleanupRequested(uploadId, clock.instant())) {
      throw new IllegalStateException("upload " + uploadId + " cleanup request was lost");
    }
    if (locked.getBlobId() != null) {
      blobManager.release(locked.getBlobId());
    }
    registerMaintenanceWakeup();
  }

  private void requireCleanupAvailable(StorageUpload upload) {
    if (upload.getCleanupRequestedAt() != null) {
      throw new StorageVerificationException("upload " + upload.getId() + " cleanup was requested");
    }
    if (upload.getCleanupToken() != null) {
      throw new StorageVerificationException(
          "upload " + upload.getId() + " cleanup is already claimed");
    }
    if (!upload.getExpiresAt().isAfter(clock.instant())) {
      throw new StorageVerificationException("upload " + upload.getId() + " expired");
    }
  }

  private void registerMaintenanceWakeup() {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              StorageMaintenanceWakeup wakeup = maintenanceWakeups.getIfAvailable();
              if (wakeup != null) {
                wakeup.wake();
              }
            } catch (RuntimeException error) {
              // API 事务已经提交：本地唤醒只是低延迟提示，失败由 periodic poll 恢复且绝不向调用方冒泡。
              log.warn("storage upload maintenance wake failed", error);
            }
          }
        });
  }

  private void ensureBlobOriginal(UUID blobId, UUID uploadId) {
    String originalKey = StorageObjectKeys.blobOriginal(blobId);
    if (s3StorageService.exists(originalKey)) {
      return;
    }
    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    if (!s3StorageService.exists(tempKey)) {
      throw new StorageVerificationException(
          "upload " + uploadId + " original object is missing; re-upload and complete again");
    }
    s3StorageService.copyObject(tempKey, originalKey);
  }

  private S3ObjectMetadata headObjectWithChecksumOrFail(UUID uploadId, String tempKey) {
    try {
      return s3StorageService.headObjectWithChecksum(tempKey);
    } catch (NoSuchKeyException e) {
      throw new StorageVerificationException(
          "upload " + uploadId + " object not found; PUT the content first");
    }
  }

  private void verifyObject(S3ObjectMetadata head, StorageUpload upload) {
    if (head.contentLength() != upload.getDeclaredSize()) {
      throw new StorageVerificationException(
          "upload "
              + upload.getId()
              + " size mismatch: declared "
              + upload.getDeclaredSize()
              + " but object has "
              + head.contentLength());
    }
    byte[] expected = decodeHex(upload.getDeclaredSha256());
    byte[] actual;
    try {
      actual = Base64.getDecoder().decode(head.checksumSha256());
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new StorageVerificationException(
          "upload " + upload.getId() + " checksum unavailable or malformed");
    }
    if (!Arrays.equals(expected, actual)) {
      throw new StorageVerificationException("upload " + upload.getId() + " checksum mismatch");
    }
  }

  private long presignExpiresSeconds() {
    return Math.min(storageMedia.uploadExpiresSeconds(), storageMedia.s3PresignMaxExpiresSeconds());
  }

  private StorageUpload newUpload(
      UUID uploadId,
      UUID candidateBlobId,
      UUID blobId,
      String filename,
      String mediaType,
      long sizeBytes,
      String sha256,
      Instant expiresAt) {
    StorageUpload upload = new StorageUpload();
    upload.setId(uploadId);
    upload.setCandidateBlobId(candidateBlobId);
    upload.setBlobId(blobId);
    upload.setFilename(filename);
    upload.setDeclaredMediaType(mediaType);
    upload.setDeclaredSize(sizeBytes);
    upload.setDeclaredSha256(sha256);
    upload.setExpiresAt(expiresAt);
    return upload;
  }

  private StorageBlob newCandidateBlob(StorageUpload upload, StorageMediaFacts facts) {
    StorageBlob blob = new StorageBlob();
    blob.setId(upload.getCandidateBlobId());
    blob.setSha256(upload.getDeclaredSha256());
    blob.setSizeBytes(upload.getDeclaredSize());
    blob.setMediaType(facts.mediaType());
    blob.setWidth(facts.width());
    blob.setHeight(facts.height());
    blob.setDurationMs(facts.durationMs());
    blob.setRefCount(1L);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  private static StorageUploadDTO toDTO(
      UUID uploadId, UUID blobId, Instant expiresAt, StoragePresignedUrlDTO presignedPut) {
    return StorageUploadDTO.builder()
        .id(uploadId.toString())
        .state(blobId == null ? StorageUploadState.PENDING : StorageUploadState.READY)
        .blobId(blobId == null ? null : blobId.toString())
        .presignedPut(presignedPut)
        .expiresAt(expiresAt)
        .build();
  }

  private static String validateFilename(String filename) {
    Assert.hasText(filename, "filename must not be blank");
    Assert.isTrue(filename.length() <= 512, "filename must not exceed 512 characters");
    return filename;
  }

  private static long validateSize(Long sizeBytes) {
    Assert.isTrue(sizeBytes != null, "sizeBytes must not be null");
    Assert.isTrue(sizeBytes >= 0L, "sizeBytes must be greater than or equal to 0");
    return sizeBytes;
  }

  private static String validateSha256(String sha256) {
    Assert.hasText(sha256, "sha256 must not be blank");
    String normalized = sha256.trim().toLowerCase(Locale.ROOT);
    Assert.isTrue(
        normalized.matches("[0-9a-f]{64}"), "sha256 must be a 64-character hexadecimal digest");
    return normalized;
  }

  private static String normalizeMediaType(String mediaType) {
    Assert.hasText(mediaType, "mediaType must not be blank");
    for (int i = 0; i < mediaType.length(); i++) {
      Assert.isTrue(
          !Character.isISOControl(mediaType.charAt(i)),
          "mediaType must not contain control characters");
    }
    String normalized = mediaType.trim();
    Assert.isTrue(normalized.length() <= 255, "mediaType must not exceed 255 characters");
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("mediaType must be a valid media type", e);
    }
  }

  private static byte[] decodeHex(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < bytes.length; i++) {
      int high = Character.digit(hex.charAt(i * 2), 16);
      int low = Character.digit(hex.charAt(i * 2 + 1), 16);
      bytes[i] = (byte) ((high << 4) | low);
    }
    return bytes;
  }

  private static Duration requirePositiveDuration(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private record ReserveOutcome(StorageUpload upload, UUID blobId) {}
}
