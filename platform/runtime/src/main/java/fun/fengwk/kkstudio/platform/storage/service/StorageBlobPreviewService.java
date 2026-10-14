package fun.fengwk.kkstudio.platform.storage.service;

import java.util.UUID;

/** 为不可变 Blob 原始内容幂等生成轻量预览对象。 */
public interface StorageBlobPreviewService {

  /**
   * 确保 Blob 的预览对象存在。
   *
   * @return {@code true} 表示本次生成；{@code false} 表示已存在或该媒体类型不支持预览
   */
  boolean ensurePreview(UUID blobId, String mediaType);
}
