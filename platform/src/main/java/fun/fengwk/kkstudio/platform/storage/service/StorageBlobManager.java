package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.UUID;

/**
 * blob 生命周期与预签名入口（全局 Blob 存储的显式 owner API）。
 *
 * <p>{@link #retain} 与 {@link #release} 必须运行在事务中（Spring {@code MANDATORY} 传播）： retain 仅对 ACTIVE 行生效
 * （DELETING 为终态，不可再 retain），release 将引用减一，减到 0 时在同一事务内切换到 DELETING， 并在事务提交后由提交线程（afterCommit）按
 * 预览→原始→行的顺序回收 S3 对象并条件删除行。计数 FK（{@code storage_upload.blob_id -> storage_blob.id}）为 RESTRICT，
 * 不会围绕本管理器发生级联删除。
 */
public interface StorageBlobManager {

  /** DELETING 清扫批次上限：启动恢复据此判断是否已排空。 */
  int MAX_SWEEP_BATCH = 16;

  /**
   * 事务内 retain：{@code ref_count + 1}。
   *
   * @return retain 后的 blob 行
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException blob 不存在或已
   *     DELETING
   */
  StorageBlob retain(UUID blobId);

  /**
   * 事务内 release：{@code ref_count - 1}，减到 0 时切换 DELETING 并在提交后回收 S3 对象与行。
   *
   * @return true 表示本次调用释放了一个活跃引用；false 表示 blob 已 DELETING/不存在（幂等，调用方按已释放处理）
   */
  boolean release(UUID blobId);

  /** 读取 blob 行（不存在时返回 null；状态由调用方解释）。 */
  StorageBlob getBlob(UUID blobId);

  /** 为 ACTIVE blob 生成原始内容 GET 预签名 URL（DELETING/不存在抛 404 语义异常）。 */
  StoragePresignedUrlDTO presignOriginalUrl(UUID blobId);

  /** 为 ACTIVE blob 生成预览 GET 预签名 URL（DELETING/不存在抛 404 语义异常）。 */
  StoragePresignedUrlDTO presignPreviewUrl(UUID blobId);

  /** 启动恢复用：回收残留 DELETING blob 的 S3 对象并条件删除其行，返回处理行数。 */
  int sweepDeleting();
}
