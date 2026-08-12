package fun.fengwk.kkstudio.core.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.mapper.StorageUploadMapper;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.model.StorageUploadDO;
import fun.fengwk.kkstudio.core.storage.service.model.StorageUpload;

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
  public boolean setBlobIdIfNull(UUID id, UUID blobId) {
    return uploadMapper.setBlobIdIfNull(id, blobId) == 1;
  }

  @Override
  public boolean deletePendingById(UUID id) {
    return uploadMapper.deletePendingById(id) == 1;
  }

  @Override
  public boolean deleteById(UUID id) {
    return uploadMapper.deleteById(id) == 1;
  }

  @Override
  public List<StorageUpload> listExpired(int limit) {
    return uploadMapper.listExpired(limit).stream().map(this::toModel).collect(Collectors.toList());
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
    target.setCreateTime(row.getCreateTime());
    return target;
  }
}
