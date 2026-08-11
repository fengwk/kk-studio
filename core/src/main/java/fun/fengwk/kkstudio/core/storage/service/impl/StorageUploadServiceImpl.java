package fun.fengwk.kkstudio.core.storage.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.core.storage.configuration.StorageProperties;
import fun.fengwk.kkstudio.core.storage.error.StorageConflictException;
import fun.fengwk.kkstudio.core.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.core.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.core.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.core.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageMediaProbe;
import fun.fengwk.kkstudio.core.storage.service.StoragePresignedUrls;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.core.storage.service.model.StorageMediaFacts;
import fun.fengwk.kkstudio.core.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

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
 * <p>事务边界全部由 {@link TransactionTemplate} 显式控制：S3 的读取（HEAD/探针）、最终对象物化（复制）与 blob 对象回收绝不发生在 DB
 * 事务内；无引用的临时/候选对象清理允许在上传行锁内进行（delete/过期路径），由行锁与 complete 的绑定串行化。blob 计数变更（retain/release/去重插入）
 * 与上传行变更在短事务内完成。
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
  private final S3StorageProperties s3Properties;
  private final StorageProperties storageProperties;
  private final TransactionTemplate transactionTemplate;

  public StorageUploadServiceImpl(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      StorageMediaProbe mediaProbe,
      S3StorageProperties s3Properties,
      StorageProperties storageProperties,
      PlatformTransactionManager transactionManager) {
    this.uploadRepository = Objects.requireNonNull(uploadRepository, "uploadRepository");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.s3PresignService = Objects.requireNonNull(s3PresignService, "s3PresignService");
    this.mediaProbe = Objects.requireNonNull(mediaProbe, "mediaProbe");
    this.s3Properties = Objects.requireNonNull(s3Properties, "s3Properties");
    this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  @Override
  public StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
    Objects.requireNonNull(request, "request must not be null");
    String filename = validateFilename(request.getFilename());
    String mediaType = normalizeMediaType(request.getMediaType());
    long sizeBytes = validateSize(request.getSizeBytes());
    String sha256 = validateSha256(request.getSha256());
    expireOnceBestEffort();

    UUID uploadId = UUID.randomUUID();
    UUID candidateBlobId = UUID.randomUUID();
    Instant expiresAt =
        Instant.now().plusSeconds(storageProperties.getEffectiveUploadExpiresSeconds());
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
      S3PresignedResponseDTO signed =
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
    expireOnceBestEffort();

    StorageUpload upload = uploadRepository.getById(uploadId);
    if (upload == null) {
      throw new StorageResourceNotFoundException("upload", uploadId.toString());
    }
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
    transactionTemplate.executeWithoutResult(
        status -> {
          StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
          if (locked == null) {
            throw new StorageResourceNotFoundException("upload", uploadId.toString());
          }
          if (locked.getBlobId() == null) {
            // PENDING：上传行锁跨无引用的临时/候选对象删除与行删除，与 complete 的绑定
            // （setBlobIdIfNull 隐式行锁）串行化，消除删除与 complete 之间的竞态。
            s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(uploadId));
            cleanupUnusedCandidate(locked);
            uploadRepository.deletePendingById(uploadId);
            return;
          }
          // READY：行锁内清理残留的临时/未使用候选对象（均无引用），删行并 release；
          // ACTIVE blob 对象由 release 的 afterCommit 回收，行锁不跨 ACTIVE 对象删除。
          s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(uploadId));
          cleanupUnusedCandidate(locked);
          if (uploadRepository.deleteById(uploadId)) {
            blobManager.release(locked.getBlobId());
          }
        });
  }

  @Override
  public int expireOnce() {
    Integer processed =
        transactionTemplate.execute(
            status -> {
              // SKIP LOCKED 批次选择与逐行处理在同一事务内：批次行锁全程持有，
              // 并发 sweeper 的批次选择会跳过本批锁定行，每行恰好由一个批次处理。
              List<StorageUpload> expired =
                  uploadRepository.listExpired(StorageUploadService.MAX_EXPIRY_BATCH);
              int count = 0;
              for (StorageUpload row : expired) {
                try {
                  if (row.getBlobId() == null) {
                    expirePendingUpload(row.getId());
                  } else {
                    expireReadyUpload(row);
                  }
                  count++;
                } catch (RuntimeException e) {
                  log.warn("storage expiry failed for upload {}: {}", row.getId(), e.getMessage());
                }
              }
              return count;
            });
    return processed == null ? 0 : processed;
  }

  /** 必须在调用方事务内执行（批次 SELECT ... FOR UPDATE SKIP LOCKED 已持有该行锁）。 */
  private void expirePendingUpload(UUID uploadId) {
    StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
    if (locked == null || locked.getBlobId() != null) {
      // 行已删除或并发 complete 已绑定：交由 READY 路径或其它请求处理。
      return;
    }
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(uploadId));
    cleanupUnusedCandidate(locked);
    uploadRepository.deletePendingById(uploadId);
  }

  /** 必须在调用方事务内执行；ACTIVE blob 对象由 release 的 afterCommit 回收，不跨批次事务删除。 */
  private void expireReadyUpload(StorageUpload row) {
    StorageUpload locked = uploadRepository.getByIdForUpdate(row.getId());
    if (locked == null || locked.getBlobId() == null) {
      // 并发 complete/delete 已改变状态或行已删除：交由其它路径处理。
      return;
    }
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(row.getId()));
    cleanupUnusedCandidate(locked);
    if (uploadRepository.deleteById(row.getId())) {
      blobManager.release(locked.getBlobId());
    }
  }

  private UUID resolveBlobAndBindUpload(StorageUpload upload, StorageMediaFacts facts) {
    // 先在绑定事务内锁定上传行：同一上传的并发 complete 在行锁上串行，保证 retain/insert 只发生在
    // 最终成功绑定的一方，杜绝对已有 ACTIVE blob 的重复计数。
    StorageUpload locked = uploadRepository.getByIdForUpdate(upload.getId());
    if (locked == null) {
      // 并发 delete/过期已删除行：上传不存在，事务回滚（含任何 blob 写入）。
      throw new StorageResourceNotFoundException("upload", upload.getId().toString());
    }
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
      if (!uploadRepository.setBlobIdIfNull(upload.getId(), active.getId())) {
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

  private void expireOnceBestEffort() {
    try {
      expireOnce();
    } catch (RuntimeException e) {
      log.warn("opportunistic storage expiry failed: {}", e.getMessage());
    }
  }

  private long presignExpiresSeconds() {
    return Math.min(
        storageProperties.getEffectiveUploadExpiresSeconds(),
        s3Properties.getEffectivePresignMaxExpiresSeconds());
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

  private record ReserveOutcome(StorageUpload upload, UUID blobId) {}
}
