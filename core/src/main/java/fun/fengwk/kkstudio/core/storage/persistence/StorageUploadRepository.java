package fun.fengwk.kkstudio.core.storage.persistence;

import fun.fengwk.kkstudio.core.storage.service.model.StorageUpload;

import java.util.List;
import java.util.UUID;

/** {@code storage_upload} 持久化契约：预约、complete 绑定与过期回收。 */
public interface StorageUploadRepository {

  boolean insert(StorageUpload upload);

  StorageUpload getById(UUID id);

  /** 行级锁定读取（{@code for update}），用于 delete/expire 时固定 PENDING/READY 状态。 */
  StorageUpload getByIdForUpdate(UUID id);

  /** 仅当仍为 PENDING 时绑定 blob，返回是否发生绑定（保证同一上传只 complete 成功一次）。 */
  boolean setBlobIdIfNull(UUID id, UUID blobId);

  /** 删除 PENDING 行（临时对象必须先删除）。 */
  boolean deletePendingById(UUID id);

  /** 删除行（READY 路径，调用方已持有行锁并负责 release blob）。 */
  boolean deleteById(UUID id);

  /** 过期批次：按过期时间取不超过 {@code limit} 行并加锁（{@code for update skip locked}）。 */
  List<StorageUpload> listExpired(int limit);
}
