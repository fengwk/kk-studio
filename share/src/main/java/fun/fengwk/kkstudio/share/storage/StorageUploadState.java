package fun.fengwk.kkstudio.share.storage;

/**
 * 上传生命周期状态。
 *
 * <p>{@link #PENDING} 表示客户端必须向预签名 PUT URL 上传内容后调用 complete；{@link #READY} 表示上传已绑定 blob（去重命中或
 * complete 成功），客户端可直接消费 blob 预签名 URL。
 *
 * @author fengwk
 */
public enum StorageUploadState {
  PENDING,
  READY
}
