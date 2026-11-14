package fun.fengwk.kkstudio.platform.catalog.skill;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * 规范化归档的暂存与引用生命周期，两个发布渠道共用。
 *
 * <p>规范化归档总是先落成暂存上传对象，blob 上记录服务端读出的条目索引；发布时在事务内 retain 该 blob 并消费暂存上传，旧归档引用随后释放。暂存对象不属于
 * 任何版本，失败时尽力取消，剩余由上传过期清理兜底。
 */
@Slf4j
final class SkillPackageArchiveStaging {

  private final StorageUploadService uploadService;
  private final StorageBlobContentService blobContentService;
  private final StorageBlobManager blobManager;
  private final StorageBlobRepository blobRepository;

  SkillPackageArchiveStaging(
      StorageUploadService uploadService,
      StorageBlobContentService blobContentService,
      StorageBlobManager blobManager,
      StorageBlobRepository blobRepository) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobContentService = Objects.requireNonNull(blobContentService, "blobContentService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
  }

  /** 规范化归档先作为暂存上传对象保留，并在 blob 上记录服务端读出的条目索引。 */
  UUID stage(String packageName, SkillArchiveBuilder.Result archive) {
    SkillArchiveIndex index = SkillArchiveIndex.from(archive.index());
    StorageUploadService.StagedUpload staged =
        uploadService.stage(
            packageName + ".zip",
            "application/zip",
            new ByteArrayInputStream(archive.zip()),
            SkillArchiveBuilder.MAX_ARCHIVE_BYTES);
    try {
      if (!blobRepository.updateArchiveIndex(staged.blobId(), index.encode())) {
        throw new IllegalStateException(
            "cannot record the archive index for a staged skill archive");
      }
    } catch (RuntimeException | Error error) {
      cancelBestEffort(staged.uploadId());
      throw error;
    }
    return staged.uploadId();
  }

  /** 在事务内 retain 暂存归档并消费暂存上传（释放上传占用的引用）。 */
  UUID consume(UUID stagingUploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(stagingUploadId);
    blobManager.retain(ready.blobId());
    uploadService.delete(stagingUploadId);
    return ready.blobId();
  }

  /** 读取并重建暂存的规范化归档；暂存 blob 必须带服务端索引。 */
  StagedArchive readStaged(UUID stagingUploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(stagingUploadId);
    StorageBlob blob = requireActiveBlob(ready.blobId());
    if (blob.getArchiveIndex() == null) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "staged archive has no server index");
    }
    byte[] bytes =
        blobContentService
            .readBlobContent(blob.getId(), SkillArchiveBuilder.MAX_ARCHIVE_BYTES)
            .getBytes();
    return new StagedArchive(blob, build(ready.filename(), bytes));
  }

  /** 读取已完成的原始上传并重建规范化归档；超限时翻译为校验错误。 */
  SkillArchiveBuilder.Result readUploaded(UUID uploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(uploadId);
    byte[] bytes;
    try {
      bytes =
          blobContentService
              .readBlobContent(ready.blobId(), SkillArchiveBuilder.MAX_ARCHIVE_BYTES)
              .getBytes();
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "uploaded archive exceeds the size limit: " + error.getMessage(),
          error);
    }
    return build(ready.filename(), bytes);
  }

  /** 暂存上传当前指向的 ACTIVE blob；用于读取归档摘要与尺寸。 */
  StorageBlob requireStagedBlob(UUID stagingUploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(stagingUploadId);
    return requireActiveBlob(ready.blobId());
  }

  /** 发布后释放旧归档引用；blob 行本身由后台清理回收。 */
  void release(SkillPackage skillPackage) {
    if (skillPackage.artifactBlobId() != null) {
      blobManager.release(skillPackage.artifactBlobId());
    }
  }

  /** 失败路径在回滚事务之外尽力取消暂存上传；剩余上传由过期清理兜底。 */
  void cancelBestEffort(UUID stagingUploadId) {
    try {
      uploadService.delete(stagingUploadId);
    } catch (RuntimeException error) {
      log.warn("failed to cancel a staged skill archive upload {}", stagingUploadId);
    }
  }

  /** 按魔数区分 gzip 与 zip，并把构建失败翻译成只描述归档本身的校验错误。 */
  static SkillArchiveBuilder.Result build(String filename, byte[] bytes) {
    try {
      boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
      SkillArchiveBuilder builder = new SkillArchiveBuilder();
      return gzip
          ? builder.fromTarGz(new ByteArrayInputStream(bytes))
          : builder.fromZip(new ByteArrayInputStream(bytes));
    } catch (IOException error) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "invalid skill archive "
              + (filename == null ? "" : filename + " ")
              + ": "
              + error.getMessage(),
          error);
    }
  }

  private StorageBlob requireActiveBlob(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null) {
      throw DomainException.notFound("storage_blob");
    }
    return blob;
  }

  record StagedArchive(StorageBlob blob, SkillArchiveBuilder.Result archive) {}
}
