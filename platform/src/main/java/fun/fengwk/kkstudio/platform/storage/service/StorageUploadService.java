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
 * 去重落败时幂等清理未使用的候选对象。delete 与消费先以短事务持久化 cleanup request，READY 同事务恰好 release 上传引用，提交后仅唤醒后台；过期批次再以
 * cleanup lease 保护，事务外删除对象，最后以 token-fenced 短事务删除行。所有响应不暴露 bucket 与对象物理 key。
 */
public interface StorageUploadService {

  /** 过期批次上限：SKIP LOCKED 每批最多 claim 的行数；后台维护据此判断是否已排空。 */
  int MAX_EXPIRY_BATCH = 16;

  /** 预约上传：内容命中返回 READY，否则返回 PENDING（含预签名 PUT）。 */
  StorageUploadDTO reserve(StorageUploadReserveRequestDTO request);

  /** 完成上传：校验直传对象并绑定 blob，返回 READY。 */
  StorageUploadDTO complete(UUID uploadId);

  /**
   * 删除上传：请求线程只在短事务内持久化 cleanup request 并快速返回，后台再幂等删除临时/候选对象与上传行；READY 在首次标记时同事务 release
   * 上传引用。消费方已有事务中同样只标记请求，不得在请求/事务线程执行 S3 I/O。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException 上传不存在
   */
  void delete(UUID uploadId);

  /**
   * 事务内锁定 READY 上传（加入调用方已有事务）并返回权威消费事实：blobId 与权威文件名（文件名取自上传行，绝不信任客户端消息内容）。
   *
   * <p>调用方随后必须先为新 owner retain（Session blob ref 或 Canvas Resource），再调用 {@link #delete} 标记并 release
   * upload owner；全部在同一事务内完成，上传行保留到后台清理完成。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException 上传不存在
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException 上传仍为
   *     PENDING、已过期、 已请求 cleanup 或已被 claim
   */
  ReadyUpload lockReady(UUID uploadId);

  /** READY 上传的权威消费事实。 */
  record ReadyUpload(UUID blobId, String filename) {}

  /**
   * 后台回收（SKIP LOCKED claim 批次，上限 16）：短事务 claim 显式 cleanup request 或过期行，事务外删除对象，再逐行 token-fenced
   * finalize；显式 request 的 READY 行不重复 release，普通过期 READY 行在 finalize 中 release。返回成功 finalize
   * 的行数，单行失败保留 lease 且不中断批次。
   */
  int expireOnce();
}
