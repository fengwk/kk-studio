package fun.fengwk.kkstudio.platform.storage.service;

import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.decodeHex;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.normalizeMediaType;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateFilename;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateSha256;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.validateSize;
import static fun.fengwk.kkstudio.platform.storage.service.StorageUploadValidation.verifyObject;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.hash.Sha256;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;
import fun.fengwk.kkstudio.platform.storage.configuration.StorageMaintenanceProperties;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.media.FfmpegStorageMediaProbe;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.s3.S3ObjectMetadata;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.s3.S3PresignedUrl;
import fun.fengwk.kkstudio.platform.storage.s3.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageMediaFacts;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局 Blob 存储的上传契约服务。
 *
 * <p>事务边界全部由 {@link TransactionTemplate} 显式控制：S3 的 HEAD/探针/复制/删除绝不发生在数据库事务或上传行锁内。显式删除/消费先在短事务内持久化
 * cleanup request，READY 恰好 release 上传引用并提交后唤醒后台；过期与请求清理再在 upload 操作锁内 claim cleanup
 * lease，事务外幂等删除临时/候选对象，最后以 token-fenced 短事务删除上传事实，显式请求 READY 不重复 release。
 *
 * <p>complete/stage 与后台清理共用 {@link StorageUploadOperationLock}：complete/stage 在「写对象 +
 * 绑定」的整个窗口独占锁，stage 连行登记都发生在取锁之后（锚点一出现就受锁保护，不存在「行已登记但未被锁保护」的间隙），后台清理在 claim 前
 * 非阻塞尝试同一把锁并跳过忙的行，因此删除证据（临时对象、候选对象、上传行）永远不可能与写对象交错。锁在任何业务事务之前取得，绝不在事务或行锁内做 S3 I/O。
 *
 * <p>complete 先校验并探针临时对象，再复制候选 blob 的最终对象，最后才在事务内去重插入/并发消解并绑定上传 —— DB 绝不引用缺失的最终对象； 同一上传的并发 complete
 * 通过 {@code setBlobIdIfNull} 只成功一次。READY 重试路径幂等并自愈（最终对象缺失时从临时对象补复制），并清理残留的临时/未使用候选对象。绑定失败时只依赖上传行
 * （{@code candidate_blob_id}）作为耐久锚点，任何对象清理都留给后台按行事实执行，绝不按异常分支盲目删除候选对象。
 *
 * <p>过期没有持久化的权威列：未请求清理且未被 claim 的上传在 {@code created_at + 当前 upload TTL} 之后过期，{@code created_at}
 * 与所有「当下」判定都由同一注入 {@link Clock} 写入/换算，避免节点时钟漂移。上传契约只负责在 reserve/stage/complete/consume 时读取当前 TTL
 * 快照并拒绝过期事实；候选扫描、 cleanup lease 与对象回收由 {@link StorageUploadExpiryService} 承担。
 *
 * @author fengwk
 */
@Slf4j
public class StorageUploadService {

  /** 过期批次上限：每次后台回收最多检查的候选 upload 行数；后台维护据此判断是否已排空。 */
  public static final int MAX_EXPIRY_BATCH = StorageUploadExpiryService.MAX_EXPIRY_BATCH;

  private final StorageUploadRepository uploadRepository;
  private final StorageBlobRepository blobRepository;
  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;
  private final S3PresignService s3PresignService;
  private final FfmpegStorageMediaProbe mediaProbe;
  private final StorageBlobPreviewService blobPreviewService;
  private final StorageUploadOperationLock operationLock;
  private final ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups;
  private final S3StorageProperties s3Properties;
  private final SystemSettingsSnapshot settingsSnapshot;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final TransactionTemplate mandatoryTransactionTemplate;
  private final StorageUploadExpiryService expiryService;

  public StorageUploadService(
      StorageUploadRepository uploadRepository,
      StorageBlobRepository blobRepository,
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      FfmpegStorageMediaProbe mediaProbe,
      StorageBlobPreviewService blobPreviewService,
      StorageObjectCleanupService objectCleanupService,
      StorageUploadOperationLock operationLock,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups,
      S3StorageProperties s3Properties,
      SystemSettingsSnapshot settingsSnapshot,
      StorageMaintenanceProperties maintenanceProperties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.uploadRepository = Objects.requireNonNull(uploadRepository, "uploadRepository");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.s3PresignService = Objects.requireNonNull(s3PresignService, "s3PresignService");
    this.mediaProbe = Objects.requireNonNull(mediaProbe, "mediaProbe");
    this.blobPreviewService = Objects.requireNonNull(blobPreviewService, "blobPreviewService");
    this.operationLock = Objects.requireNonNull(operationLock, "operationLock");
    this.maintenanceWakeups = Objects.requireNonNull(maintenanceWakeups, "maintenanceWakeups");
    this.s3Properties = Objects.requireNonNull(s3Properties, "s3Properties");
    this.settingsSnapshot = Objects.requireNonNull(settingsSnapshot, "settingsSnapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(maintenanceProperties, "maintenanceProperties");
    PlatformTransactionManager requiredTransactionManager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    this.transactionTemplate = new TransactionTemplate(requiredTransactionManager);
    this.mandatoryTransactionTemplate = new TransactionTemplate(requiredTransactionManager);
    this.mandatoryTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_MANDATORY);
    this.expiryService =
        new StorageUploadExpiryService(
            uploadRepository,
            blobManager,
            s3StorageService,
            objectCleanupService,
            operationLock,
            settingsSnapshot,
            clock,
            maintenanceProperties.getCleanupLease(),
            transactionTemplate);
  }

  /** 预约上传：内容命中返回 READY，否则返回 PENDING（含预签名 PUT）。 */
  public StorageUploadDTO reserve(StorageUploadReserveRequestDTO request) {
    Objects.requireNonNull(request, "request must not be null");
    String filename = validateFilename(request.getFilename());
    String mediaType = normalizeMediaType(request.getMediaType());
    long sizeBytes = validateSize(request.getSizeBytes());
    String sha256 = validateSha256(request.getSha256());

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
                      createdAt);
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
              presignExpiresSeconds(media));
      presignedPut = StoragePresignedUrls.from(signed);
    } else {
      generatePreviewBestEffort(outcome.blobId);
    }
    return toDTO(
        outcome.upload.getId(),
        outcome.blobId,
        expiresAt(outcome.upload.getCreateTime(), uploadTtl),
        presignedPut);
  }

  /** 完成上传：校验直传对象并绑定 blob，返回 READY。 */
  public StorageUploadDTO complete(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    // 一次操作读取同一份 TTL 快照，complete 全程用它判定过期与绑定阈值。
    long uploadTtl = settingsSnapshot.get().storageMedia().uploadExpiresSeconds();
    // 整个「校验/写对象 + 绑定」窗口独占持有该 upload 的操作锁（必须先于任何业务事务取锁）：
    // 后台清理只有拿到同一把锁才能 claim、删对象或删行，因此 copy 落盘与 bind 之间不可能出现「行已被删、对象成孤儿」的交错。
    try (StorageUploadOperationLock.Handle ignored = operationLock.acquire(uploadId)) {
      return completeLocked(uploadId, uploadTtl);
    }
  }

  private StorageUploadDTO completeLocked(UUID uploadId, long uploadTtl) {
    StorageUpload upload = uploadRepository.getById(uploadId);
    if (upload == null) {
      throw DomainException.notFound("upload", "unknown upload: " + uploadId);
    }
    requireCleanupAvailable(upload, uploadTtl);
    if (upload.getBlobId() != null) {
      // READY 重试：幂等并自愈最终对象，随后清理残留的临时对象与未使用的候选对象。
      ensureBlobOriginal(upload.getBlobId(), uploadId);
      s3StorageService.deleteObjectIfExists(StorageObjectKeys.uploadOriginal(uploadId));
      expiryService.cleanupUnusedCandidate(upload);
      generatePreviewBestEffort(upload.getBlobId());
      return toDTO(
          uploadId, upload.getBlobId(), expiresAt(upload.getCreateTime(), uploadTtl), null);
    }

    String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
    S3ObjectMetadata head = headObjectWithChecksumOrFail(uploadId, tempKey);
    verifyObject(head, upload);
    StorageMediaFacts facts = mediaProbe.probe(tempKey, head);
    UUID blobId = materializeAndBind(upload, facts, false, uploadTtl);
    return toDTO(upload.getId(), blobId, expiresAt(upload.getCreateTime(), uploadTtl), null);
  }

  /**
   * 服务端有界 spool 输入流并准备一个可消费的 READY upload。调用前不得存在活动事务；实现先在 upload 操作锁内以独立短事务登记 PENDING
   * candidate，再在事务外执行对象写入、媒体探针与复制，最后以短事务完成去重绑定（登记失败在写入任何对象之前直接上抛，不做对象清理）。输入流由调用方关闭。
   *
   * @param maxBytes 允许的最大字节数
   */
  public StagedUpload stage(String filename, String mediaType, InputStream content, long maxBytes) {
    rejectActiveTransaction();
    Objects.requireNonNull(content, "content must not be null");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    String normalizedFilename = validateFilename(filename);
    String normalizedMediaType = normalizeMediaType(mediaType);
    Path spool = null;
    try {
      spool = Files.createTempFile("storage-stage-", ".tmp");
      MessageDigest digest = Sha256.newDigest();
      long total = 0;
      try (OutputStream output = Files.newOutputStream(spool)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = content.read(buffer)) != -1) {
          total += read;
          if (total > maxBytes) {
            throw new IllegalArgumentException(
                "staged content must not exceed " + maxBytes + " bytes");
          }
          digest.update(buffer, 0, read);
          output.write(buffer, 0, read);
        }
      }
      validateSize(total);
      return stageSpooled(
          normalizedFilename,
          normalizedMediaType,
          spool,
          new FileFacts(total, HexFormat.of().formatHex(digest.digest())),
          settingsSnapshot.get().storageMedia().uploadExpiresSeconds());
    } catch (IOException error) {
      throw new UncheckedIOException("failed to spool staged content", error);
    } finally {
      if (spool != null) {
        try {
          Files.deleteIfExists(spool);
        } catch (IOException error) {
          log.warn("failed to delete storage stage spool {}", spool);
        }
      }
    }
  }

  private StagedUpload stageSpooled(
      String filename, String mediaType, Path content, FileFacts fileFacts, long uploadTtl) {
    UUID uploadId = UUID.randomUUID();
    UUID candidateBlobId = UUID.randomUUID();
    Instant createdAt = clock.instant();
    StorageUpload upload =
        newUpload(
            uploadId,
            candidateBlobId,
            null,
            filename,
            mediaType,
            fileFacts.sizeBytes(),
            fileFacts.sha256(),
            createdAt);
    // 先取操作锁再登记行：行登记完成的那一刻就已经在锁保护内，后台清理不可能在「行已登记、写窗口还没开始」的间隙
    // claim 并删除锚点（否则登记后线程停顿超过 TTL，GC 可先删行，随后 PUT/copy 落盘的就是无事实的孤儿对象）。
    try (StorageUploadOperationLock.Handle ignored = operationLock.acquire(uploadId)) {
      // 登记失败直接上抛：失败发生在任何对象写入之前，没有对象需要（也不应该）删除。
      transactionTemplate.executeWithoutResult(
          status -> {
            if (!uploadRepository.insert(upload)) {
              throw new IllegalStateException("insert storage upload failed: " + uploadId);
            }
          });

      // 失败清理（mark cleanup request，必要时兜底幂等删除已知对象键）也必须在同一把锁内：
      // 任何对象删除路径都不能与后台清理或其它写窗口并发。
      try {
        String tempKey = StorageObjectKeys.uploadOriginal(uploadId);
        try (InputStream input = Files.newInputStream(content)) {
          // content 是本方法私有 spool：摘要与长度在写完并关闭后冻结，PUT 读取的正是该文件。
          s3StorageService.putObject(
              tempKey,
              input,
              fileFacts.sizeBytes(),
              mediaType,
              Base64.getEncoder().encodeToString(HexFormat.of().parseHex(fileFacts.sha256())));
        } catch (IOException error) {
          throw new UncheckedIOException("failed to read staged content", error);
        }
        S3ObjectMetadata head = headObjectWithChecksumOrFail(uploadId, tempKey);
        verifyObject(head, upload);
        StorageMediaFacts facts = mediaProbe.probe(tempKey, head);
        UUID blobId = materializeAndBind(upload, facts, true, uploadTtl);
        StorageBlob blob = blobManager.getBlob(blobId);
        if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
          throw new IllegalStateException("staged upload resolved to a missing blob");
        }
        return new StagedUpload(
            uploadId, blobId, filename, blob.getMediaType(), blob.getSizeBytes(), blob.getSha256());
      } catch (RuntimeException failure) {
        requestFailedStageCleanup(upload);
        throw failure;
      }
    }
  }

  private UUID materializeAndBind(
      StorageUpload upload,
      StorageMediaFacts facts,
      boolean bestEffortObjectCleanup,
      long uploadTtl) {
    String tempKey = StorageObjectKeys.uploadOriginal(upload.getId());
    // 先物化候选 blob 的最终对象，再让 DB 引用它：DB 绝不引用缺失的最终对象。
    s3StorageService.copyObject(
        tempKey, StorageObjectKeys.blobOriginal(upload.getCandidateBlobId()));
    UUID blobId =
        transactionTemplate.execute(status -> resolveBlobAndBindUpload(upload, facts, uploadTtl));
    if (!blobId.equals(upload.getCandidateBlobId())) {
      // 去重落败：未使用的候选对象幂等清理（预览先于原始）；READY 上传行（candidate_blob_id）
      // 是崩溃窗口的恢复证据。
      if (bestEffortObjectCleanup) {
        expiryService.cleanupCandidateObjectsBestEffort(upload.getCandidateBlobId());
      } else {
        expiryService.cleanupCandidateObjects(upload.getCandidateBlobId());
      }
    }
    if (bestEffortObjectCleanup) {
      expiryService.deleteObjectBestEffort(tempKey);
    } else {
      s3StorageService.deleteObjectIfExists(tempKey);
    }
    generatePreviewBestEffort(blobId);
    return blobId;
  }

  /** Blob 已提交且对象可读后生成轻量预览；失败不改变 READY 上传事实，展示层回退原件。 */
  private void generatePreviewBestEffort(UUID blobId) {
    try {
      StorageBlob blob = blobManager.getBlob(blobId);
      if (blob == null
          || blob.getState() != StorageBlobState.ACTIVE
          || !isPreviewable(blob.getMediaType())) {
        return;
      }
      blobPreviewService.ensurePreview(blobId, blob.getMediaType());
    } catch (RuntimeException error) {
      log.warn(
          "blob preview generation failed blobId={} type={}",
          blobId,
          error.getClass().getSimpleName());
    }
  }

  private static boolean isPreviewable(String mediaType) {
    return mediaType != null && (mediaType.startsWith("image/") || mediaType.startsWith("video/"));
  }

  /**
   * 删除上传：请求线程只在短事务内持久化 cleanup request 并快速返回，后台再幂等删除临时/候选对象与上传行；READY 在首次标记时同事务 release
   * 上传引用。消费方已有事务中同样只标记请求，不得在请求/事务线程执行 S3 I/O。
   *
   * @throws DomainException 上传不存在
   */
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

  /**
   * 事务内锁定 READY 上传（加入调用方已有事务）并返回权威消费事实：blobId 与权威文件名（文件名取自上传行，绝不信任客户端消息内容）。
   *
   * <p>调用方随后必须先为新 owner retain（Session blob ref 或 Canvas Resource），再调用 {@link #delete} 标记并 release
   * upload owner；全部在同一事务内完成，上传行保留到后台清理完成。
   *
   * @throws DomainException 上传不存在
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException 上传仍为
   *     PENDING、已过期、 已请求 cleanup 或已被 claim
   */
  public ReadyUpload lockReady(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    long uploadTtl = settingsSnapshot.get().storageMedia().uploadExpiresSeconds();
    return mandatoryTransactionTemplate.execute(
        status -> {
          StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
          if (locked == null) {
            throw DomainException.notFound("upload", "unknown upload: " + uploadId);
          }
          if (locked.getBlobId() == null) {
            throw new StorageVerificationException(
                "upload " + uploadId + " is PENDING; complete it before consuming");
          }
          requireCleanupAvailable(locked, uploadTtl);
          return new ReadyUpload(locked.getBlobId(), locked.getFilename());
        });
  }

  /**
   * 只读查看 READY 上传的权威事实（blobId 与权威文件名）：不加行锁、不标记 cleanup、不 release、不产生任何写入，也不需要活动事务。
   *
   * <p>与 {@link #lockReady} 共用同一份 READY 可消费判定（PENDING / 过期 / 已请求 cleanup / 已被 claim 一律拒绝），供「点击时快照」
   * 类只读预览使用；调用方绝不能据此获得 owner 权限——真正的消费仍必须走 {@link #lockReady}。
   *
   * @throws DomainException 上传不存在
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException 上传仍为
   *     PENDING、已过期、 已请求 cleanup 或已被 claim
   */
  public ReadyUpload peekReady(UUID uploadId) {
    Objects.requireNonNull(uploadId, "uploadId must not be null");
    long uploadTtl = settingsSnapshot.get().storageMedia().uploadExpiresSeconds();
    // 只读：不起事务、不加行锁、不推进任何 lifecycle marker；最终消费仍由 lockReady 独占。
    StorageUpload upload = uploadRepository.getById(uploadId);
    if (upload == null) {
      throw DomainException.notFound("upload", "unknown upload: " + uploadId);
    }
    if (upload.getBlobId() == null) {
      throw new StorageVerificationException(
          "upload " + uploadId + " is PENDING; complete it before previewing");
    }
    requireCleanupAvailable(upload, uploadTtl);
    return new ReadyUpload(upload.getBlobId(), upload.getFilename());
  }

  /** 后台回收入口：由 {@link StorageUploadExpiryService} 执行候选扫描、claim 与对象回收。 */
  public int expireOnce() {
    return expiryService.expireOnce();
  }

  /** 在上传行锁保护的事务内消解或创建去重 Blob，并与当前上传记录安全绑定。 */
  private UUID resolveBlobAndBindUpload(
      StorageUpload upload, StorageMediaFacts facts, long uploadTtl) {
    // 先在绑定事务内锁定上传行：同一上传的并发 complete 在行锁上串行，保证 retain/insert 只发生在
    // 最终成功绑定的一方，杜绝对已有 ACTIVE blob 的重复计数。
    StorageUpload locked = uploadRepository.getByIdForUpdate(upload.getId());
    if (locked == null) {
      // 并发 delete/过期已删除行：上传不存在，事务回滚（含任何 blob 写入）。
      throw DomainException.notFound("upload", "unknown upload: " + upload.getId());
    }
    requireCleanupAvailable(locked, uploadTtl);
    if (locked.getBlobId() != null) {
      // 并发 complete 已绑定：直接取其结果，不重复 retain。
      return locked.getBlobId();
    }
    // 唯一索引冲突与 retain 在一条语句内完成；与最后一次 release 在 blob 行上串行化。
    UUID blobId = blobRepository.retainOrInsertActive(newCandidateBlob(upload, facts));
    // 行锁在手：setBlobIdIfNull 不可能被并发 complete 抢走，失败即上传行状态异常。
    if (!uploadRepository.setBlobIdIfNull(
        upload.getId(),
        blobId,
        StorageUploadExpiryService.expiryCutoff(clock.instant(), uploadTtl))) {
      throw new IllegalStateException("upload " + upload.getId() + " changed while completing");
    }
    return blobId;
  }

  /**
   * Stage 失败时只留下耐久 cleanup request：登记事务会同样登记该 upload 的清理记录，迟到写入由清扫收敛。
   *
   * <p>回收器可能已 claim 或删除该行（此时 claim 事务已经登记了清理记录），因此这里绝不按异常分支盲目删除对象 —— 那会删掉一个已被绑定为 ACTIVE 的
   * candidate。
   */
  private void requestFailedStageCleanup(StorageUpload upload) {
    try {
      delete(upload.getId());
    } catch (StorageVerificationException ignored) {
      log.warn("deferred storage cleanup for staged upload {}", upload.getId());
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.NOT_FOUND && "upload".equals(error.resource())) {
        log.warn("deferred storage cleanup for staged upload {}", upload.getId());
      } else {
        throw error;
      }
    } catch (RuntimeException error) {
      log.warn("failed to request cleanup for staged upload {}", upload.getId());
    }
  }

  private void requestCleanupInCurrentTransaction(UUID uploadId) {
    StorageUpload locked = uploadRepository.getByIdForUpdate(uploadId);
    if (locked == null) {
      throw DomainException.notFound("upload", "unknown upload: " + uploadId);
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

  private void requireCleanupAvailable(StorageUpload upload, long uploadTtl) {
    if (upload.getCleanupRequestedAt() != null) {
      throw new StorageVerificationException("upload " + upload.getId() + " cleanup was requested");
    }
    if (upload.getCleanupToken() != null) {
      throw new StorageVerificationException(
          "upload " + upload.getId() + " cleanup is already claimed");
    }
    if (StorageUploadExpiryService.isExpired(upload.getCreateTime(), clock.instant(), uploadTtl)) {
      throw new StorageVerificationException("upload " + upload.getId() + " expired");
    }
  }

  private void registerMaintenanceWakeup() {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              StorageMaintenanceWakeup wakeup = maintenanceWakeups.getObject();
              wakeup.wake();
            } catch (RuntimeException error) {
              // API 事务已经提交：本地唤醒只是低延迟提示，失败由 periodic poll 恢复且绝不向调用方冒泡。
              log.warn(
                  "storage upload maintenance wake failed: {}", error.getClass().getSimpleName());
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

  private static long presignExpiresSeconds(SystemSettings.StorageMedia media) {
    return Math.min(media.uploadExpiresSeconds(), media.s3PresignMaxExpiresSeconds());
  }

  /** DTO 快照到期时刻：仅按当下 TTL 换算的展示值，绝不作为过期权威事实。 */
  private static Instant expiresAt(Instant createdAt, long uploadTtlSeconds) {
    return createdAt.plusSeconds(uploadTtlSeconds);
  }

  private StorageUpload newUpload(
      UUID uploadId,
      UUID candidateBlobId,
      UUID blobId,
      String filename,
      String mediaType,
      long sizeBytes,
      String sha256,
      Instant createTime) {
    StorageUpload upload = new StorageUpload();
    upload.setId(uploadId);
    upload.setCandidateBlobId(candidateBlobId);
    upload.setBlobId(blobId);
    upload.setFilename(filename);
    upload.setDeclaredMediaType(mediaType);
    upload.setDeclaredSize(sizeBytes);
    upload.setDeclaredSha256(sha256);
    upload.setCreateTime(createTime);
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

  private static void rejectActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("storage staging must run without an active transaction");
    }
  }

  private record FileFacts(long sizeBytes, String sha256) {}

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

  private record ReserveOutcome(StorageUpload upload, UUID blobId) {}

  /** 服务端 staging 完成后的权威事实；媒体类型来自探针，不信任调用方声明。 */
  public record StagedUpload(
      UUID uploadId,
      UUID blobId,
      String filename,
      String mediaType,
      long sizeBytes,
      String sha256) {}

  /** READY 上传的权威消费事实。 */
  public record ReadyUpload(UUID blobId, String filename) {}
}
