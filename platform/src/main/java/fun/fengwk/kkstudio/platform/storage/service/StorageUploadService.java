package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.share.storage.StorageUploadDTO;
import fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO;

import java.util.UUID;

/**
 * 全局 Blob 存储的上传契约服务。
 *
 * <p>reserve 在 ACTIVE 内容命中时直接返回 READY（同事务 retain + 插入上传行），未命中时返回 PENDING 与 携带 {@code
 * x-amz-checksum-sha256} 和 {@code If-None-Match: *} 的浏览器直传预签名 PUT；complete 先以 checksum mode HEAD
 * 校验对象大小/校验和，再探针权威媒体事实，然后先物化候选 blob 的最终对象（S3 复制），最后在事务内完成去重插入或并发消解并绑定上传 —— DB 绝不引用缺失的最终对象；
 * 去重落败时幂等清理未使用的候选对象。delete 与过期批次先以短事务 claim cleanup lease，事务外删除对象，再以 token-fenced 短事务删除行。所有响应不暴露
 * bucket 与对象物理 key。
 */
public interface StorageUploadService {

  /** 过期批次上限：SKIP LOCKED 每批最多 claim 的行数；后台维护据此判断是否已排空。 */
  int MAX_EXPIRY_BATCH = 16;

  /** 预约上传：内容命中返回 READY，否则返回 PENDING（含预签名 PUT）。 */
  StorageUploadDTO reserve(StorageUploadReserveRequestDTO request);

  /** 完成上传：校验直传对象并绑定 blob，返回 READY。 */
  StorageUploadDTO complete(UUID uploadId);

  /**
   * 删除上传：请求线程在数据库事务外幂等删除临时/候选对象，再短事务删行；READY 同事务 release blob。消费方已有事务中只允许删除已由 {@link #lockReady}
   * 锁定的 READY 行。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException 上传不存在
   */
  void delete(UUID uploadId);

  /**
   * 事务内锁定 READY 上传（加入调用方已有事务）并返回权威消费事实：blobId 与权威文件名（文件名取自上传行，绝不信任客户端消息内容）。
   *
   * <p>调用方随后必须先为新 owner retain（Session blob ref 或 Canvas Resource），再删除已消费上传行并 release upload
   * owner，全部在同一事务内完成。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException 上传不存在
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException 上传仍为 PENDING
   *     或已过期
   */
  ReadyUpload lockReady(UUID uploadId);

  /** READY 上传的权威消费事实。 */
  record ReadyUpload(UUID blobId, String filename) {}

  /**
   * 后台过期回收（SKIP LOCKED claim 批次，上限 16）：短事务写 cleanup lease，事务外删除对象，再逐行 token-fenced finalize；返回成功
   * finalize 的行数，单行失败保留 lease 且不中断批次。
   */
  int expireOnce();
}
