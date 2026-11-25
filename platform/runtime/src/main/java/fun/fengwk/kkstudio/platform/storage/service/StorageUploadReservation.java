package fun.fengwk.kkstudio.platform.storage.service;

import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadImageMetadata.validate;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.expiresAt;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.newUpload;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadService.toDTO;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.decodeHex;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.normalizeMediaType;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateFilename;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateSha256;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateSize;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.verifyProbedImage;

import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO;
import fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignedUrl;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 上传预约：输入校验、去重命中、登记上传行、签发直传票据，以及成功后与失败后的副作用。
 *
 * <p>事务边界与上传契约一致：去重 retain 与行登记在同一条短事务内完成，S3 预签名在事务外执行。上传行已登记但签票失败时登记耐久清理请求， 让后台 TTL
 * 继续兜底回收；清理请求本身失败不得掩盖签票失败。
 *
 * <p>预览生成与失败清理属于上传契约的后台语义，由构造方以回调注入，避免预约路径反向依赖完整的上传服务。
 */
final class StorageUploadReservation {

  private final StorageUploadRepository uploadRepository;
  private final StorageBlobRepository blobRepository;
  private final StorageBlobManager blobManager;
  private final S3PresignService s3PresignService;
  private final SystemSettingsSnapshot settingsSnapshot;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final Consumer<UUID> generatePreviewBestEffort;
  private final Consumer<StorageUpload> requestFailedStageCleanup;

  StorageUploadReservation(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3PresignService s3PresignService,
      SystemSettingsSnapshot settingsSnapshot,
      Clock clock,
      TransactionTemplate transactionTemplate,
      Consumer<UUID> generatePreviewBestEffort,
      Consumer<StorageUpload> requestFailedStageCleanup) {
    this.uploadRepository = uploadRepository;
    this.blobRepository = blobRepository;
    this.blobManager = blobManager;
    this.s3PresignService = s3PresignService;
    this.settingsSnapshot = settingsSnapshot;
    this.clock = clock;
    this.transactionTemplate = transactionTemplate;
    this.generatePreviewBestEffort = generatePreviewBestEffort;
    this.requestFailedStageCleanup = requestFailedStageCleanup;
  }

  /** 预约用户上传：图片必须携带客户端缩放后的 MIME 与像素事实。 */
  StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
    return reserve(request, true, null);
  }

  StorageUploadDTO reserve(StorageUploadReserveRequestDTO request, UUID userId) {
    return reserve(request, true, Objects.requireNonNull(userId, "userId"));
  }

  /** 预约服务端受控 staging：媒体事实由服务端在 complete 时探测。 */
  StorageUploadDTO reserveStaged(StorageUploadReserveRequestDTO request) {
    return reserve(request, false);
  }

  private StorageUploadDTO reserve(
      StorageUploadReserveRequestDTO request, boolean requireImageMetadata) {
    return reserve(request, requireImageMetadata, null);
  }

  private StorageUploadDTO reserve(
      StorageUploadReserveRequestDTO request, boolean requireImageMetadata, UUID ownerUserId) {
    Objects.requireNonNull(request, "request must not be null");
    String filename = validateFilename(request.getFilename());
    String mediaType = normalizeMediaType(request.getMediaType());
    long sizeBytes = validateSize(request.getSizeBytes());
    String sha256 = validateSha256(request.getSha256());
    StorageUploadImageMetadata.Facts imageMetadata =
        validate(
            mediaType,
            request.getFormat(),
            request.getOriginalWidth(),
            request.getOriginalHeight(),
            request.getStoredWidth(),
            request.getStoredHeight(),
            requireImageMetadata);

    // 一次操作读取同一份 TTL 快照：created_at 与响应快照到期时刻都由它换算。
    SystemSettings.StorageMedia media = settingsSnapshot.get().storageMedia();
    long uploadTtl = media.uploadExpiresSeconds();
    UUID uploadId = UUID.randomUUID();
    UUID candidateBlobId = UUID.randomUUID();
    Instant createdAt = clock.instant();
    ReserveOutcome outcome =
        transactionTemplate.execute(
            status -> {
              UUID hitBlobId = null;
              StorageBlob active = blobRepository.getActiveByHashAndSize(sha256, sizeBytes);
              if (active != null) {
                if (!requireImageMetadata) {
                  verifyProbedImage(
                      mediaType, active.getMediaType(), active.getWidth(), active.getHeight());
                }
                try {
                  blobManager.retain(active.getId());
                  hitBlobId = active.getId();
                } catch (DomainException e) {
                  if (e.code() != DomainErrorCode.NOT_FOUND || !"blob".equals(e.resource())) {
                    throw e;
                  }
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
                      imageMetadata,
                      createdAt);
              upload.setOwnerUserId(ownerUserId);
              if (!uploadRepository.insert(upload)) {
                throw new IllegalStateException("insert storage upload failed: " + uploadId);
              }
              return new ReserveOutcome(upload, hitBlobId);
            });

    StoragePresignedUrlDTO presignedPut = null;
    if (outcome.blobId == null) {
      try {
        presignedPut = sign(outcome.upload, presignExpiresSeconds(media));
      } catch (RuntimeException failure) {
        // 上传行已登记但签票失败：调用方拿不到 uploadId，不能让它无诊断地等 TTL；
        // 这里登记耐久清理请求，后台 TTL 继续兜底回收；清理请求本身失败不得掩盖签票失败。
        try {
          requestFailedStageCleanup.accept(outcome.upload);
        } catch (RuntimeException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    } else {
      generatePreviewBestEffort.accept(outcome.blobId);
    }
    return toDTO(
        outcome.upload,
        outcome.blobId,
        expiresAt(outcome.upload.getCreatedAt(), uploadTtl),
        presignedPut);
  }

  StoragePresignedUrlDTO sign(StorageUpload upload, long expiresInSeconds) {
    S3PresignedUrl signed =
        s3PresignService.presignChecksummedCreateOnlyUpload(
            StorageObjectKeys.uploadOriginal(upload.getId()),
            upload.getDeclaredMediaType(),
            Base64.getEncoder().encodeToString(decodeHex(upload.getDeclaredSha256())),
            expiresInSeconds);
    return StoragePresignedUrls.from(signed);
  }

  private static long presignExpiresSeconds(SystemSettings.StorageMedia media) {
    return Math.min(media.uploadExpiresSeconds(), media.s3PresignMaxExpiresSeconds());
  }

  private record ReserveOutcome(StorageUpload upload, UUID blobId) {}
}
