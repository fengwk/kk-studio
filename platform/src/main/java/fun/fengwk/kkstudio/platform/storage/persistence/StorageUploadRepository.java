package fun.fengwk.kkstudio.platform.storage.persistence;

import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code storage_upload} 持久化契约：预约、complete 绑定与过期回收。 */
public interface StorageUploadRepository {

  boolean insert(StorageUpload upload);

  StorageUpload getById(UUID id);

  /** 行级锁定读取（{@code for update}），用于 complete 绑定与 READY 消费。 */
  StorageUpload getByIdForUpdate(UUID id);

  /** 仅当仍为未过期、未被清理 claim 的 PENDING 时绑定 blob。 */
  boolean setBlobIdIfNull(UUID id, UUID blobId, Instant now);

  /** CAS 标记显式清理请求；调用方必须在同一事务内按 READY 状态恰好 release 一次。 */
  boolean markCleanupRequested(UUID id, Instant requestedAt);

  /** 原子 claim 一批可清理的请求/过期行；数据库事务只持有到 UPDATE RETURNING 完成。 */
  List<StorageUpload> claimExpired(int limit, Instant now, Instant leaseUntil, String cleanupToken);

  /** 按 id 原子 claim 指定清理事实；允许未过期行，但不能抢占有效 lease。 */
  StorageUpload claimById(UUID id, Instant now, Instant leaseUntil, String cleanupToken);

  /** token-fenced 删除 PENDING 清理事实。 */
  boolean finalizePending(UUID id, String cleanupToken);

  /** token/blob-fenced 删除 READY 清理事实；成功后调用方在同一短事务 release blob。 */
  boolean finalizeReady(UUID id, UUID blobId, String cleanupToken);

  /** token-fenced 主动释放 cleanup lease。 */
  boolean releaseCleanupClaim(UUID id, String cleanupToken);
}
