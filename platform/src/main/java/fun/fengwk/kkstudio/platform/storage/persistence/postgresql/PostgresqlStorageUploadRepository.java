package fun.fengwk.kkstudio.platform.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper.StorageUploadMapper;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageUploadDO;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** 基于 PostgreSQL 的 {@code storage_upload} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlStorageUploadRepository implements StorageUploadRepository {

  private final StorageUploadMapper uploadMapper;

  @Override
  public boolean insert(StorageUpload upload) {
    return uploadMapper.insert(toDO(upload)) == 1;
  }

  @Override
  public StorageUpload getById(UUID id) {
    return toModel(uploadMapper.getById(id));
  }

  @Override
  public StorageUpload getByIdForUpdate(UUID id) {
    return toModel(uploadMapper.getByIdForUpdate(id));
  }

  @Override
  public boolean setBlobIdIfNull(UUID id, UUID blobId, Instant now) {
    return uploadMapper.setBlobIdIfNull(id, blobId, now) == 1;
  }

  @Override
  public boolean markCleanupRequested(UUID id, Instant requestedAt) {
    return uploadMapper.markCleanupRequested(id, requestedAt) == 1;
  }

  @Override
  public List<StorageUpload> claimExpired(
      int limit, Instant now, Instant leaseUntil, String cleanupToken) {
    return uploadMapper.claimExpired(limit, now, leaseUntil, cleanupToken).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public StorageUpload claimById(UUID id, Instant now, Instant leaseUntil, String cleanupToken) {
    return toModel(uploadMapper.claimById(id, now, leaseUntil, cleanupToken));
  }

  @Override
  public boolean finalizePending(UUID id, String cleanupToken) {
    return uploadMapper.finalizePending(id, cleanupToken) == 1;
  }

  @Override
  public boolean finalizeReady(UUID id, UUID blobId, String cleanupToken) {
    return uploadMapper.finalizeReady(id, blobId, cleanupToken) == 1;
  }

  @Override
  public boolean releaseCleanupClaim(UUID id, String cleanupToken) {
    return uploadMapper.releaseCleanupClaim(id, cleanupToken) == 1;
  }

  private StorageUploadDO toDO(StorageUpload upload) {
    if (upload == null) {
      return null;
    }
    StorageUploadDO target = new StorageUploadDO();
    target.setId(upload.getId());
    target.setCandidateBlobId(upload.getCandidateBlobId());
    target.setBlobId(upload.getBlobId());
    target.setFilename(upload.getFilename());
    target.setDeclaredMediaType(upload.getDeclaredMediaType());
    target.setDeclaredSize(upload.getDeclaredSize());
    target.setDeclaredSha256(upload.getDeclaredSha256());
    target.setExpiresAt(upload.getExpiresAt());
    target.setCleanupRequestedAt(upload.getCleanupRequestedAt());
    target.setCleanupToken(upload.getCleanupToken());
    target.setCleanupUntil(upload.getCleanupUntil());
    return target;
  }

  private StorageUpload toModel(StorageUploadDO row) {
    if (row == null) {
      return null;
    }
    StorageUpload target = new StorageUpload();
    target.setId(row.getId());
    target.setCandidateBlobId(row.getCandidateBlobId());
    target.setBlobId(row.getBlobId());
    target.setFilename(row.getFilename());
    target.setDeclaredMediaType(row.getDeclaredMediaType());
    target.setDeclaredSize(row.getDeclaredSize());
    target.setDeclaredSha256(row.getDeclaredSha256());
    target.setExpiresAt(row.getExpiresAt());
    target.setCleanupRequestedAt(row.getCleanupRequestedAt());
    target.setCleanupToken(row.getCleanupToken());
    target.setCleanupUntil(row.getCleanupUntil());
    target.setCreateTime(row.getCreateTime());
    return target;
  }
}
