package fun.fengwk.kkstudio.core.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.mapper.StorageBlobMapper;
import fun.fengwk.kkstudio.core.storage.persistence.postgresql.model.StorageBlobDO;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlobState;

import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 的 {@code storage_blob} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlStorageBlobRepository implements StorageBlobRepository {

  private final StorageBlobMapper blobMapper;

  @Override
  public StorageBlob getById(UUID id) {
    return toModel(blobMapper.getById(id));
  }

  @Override
  public StorageBlob getActiveByHashAndSize(String sha256, long sizeBytes) {
    return toModel(blobMapper.getActiveByHashAndSize(sha256, sizeBytes));
  }

  @Override
  public boolean insertActiveCandidate(StorageBlob blob) {
    return blobMapper.insertActiveCandidate(toDO(blob)) == 1;
  }

  @Override
  public boolean incrementRefCount(UUID id) {
    return blobMapper.incrementRefCount(id) == 1;
  }

  @Override
  public boolean releaseOnce(UUID id) {
    return blobMapper.releaseOnce(id) == 1;
  }

  @Override
  public boolean deleteDeleting(UUID id) {
    return blobMapper.deleteDeleting(id) == 1;
  }

  @Override
  public List<UUID> listDeletingIds(int limit) {
    return blobMapper.listDeletingIds(limit);
  }

  private StorageBlobDO toDO(StorageBlob blob) {
    if (blob == null) {
      return null;
    }
    StorageBlobDO target = new StorageBlobDO();
    target.setId(blob.getId());
    target.setSha256(blob.getSha256());
    target.setSizeBytes(blob.getSizeBytes());
    target.setMediaType(blob.getMediaType());
    target.setWidth(blob.getWidth());
    target.setHeight(blob.getHeight());
    target.setDurationMs(blob.getDurationMs());
    target.setRefCount(blob.getRefCount());
    target.setState(blob.getState().name());
    return target;
  }

  private StorageBlob toModel(StorageBlobDO row) {
    if (row == null) {
      return null;
    }
    StorageBlob target = new StorageBlob();
    target.setId(row.getId());
    target.setSha256(row.getSha256());
    target.setSizeBytes(row.getSizeBytes());
    target.setMediaType(row.getMediaType());
    target.setWidth(row.getWidth());
    target.setHeight(row.getHeight());
    target.setDurationMs(row.getDurationMs());
    target.setRefCount(row.getRefCount());
    target.setState(StorageBlobState.valueOf(row.getState()));
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }
}
