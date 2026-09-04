package fun.fengwk.kkstudio.share.storage;

/** 存储上传会话的生命周期状态。 */
public enum StorageUploadState {
  /** 待上传内容，等待客户端完成预签名上传后确认。 */
  PENDING,

  /** 上传已绑定有效 Blob，文件可直接消费。 */
  READY
}
