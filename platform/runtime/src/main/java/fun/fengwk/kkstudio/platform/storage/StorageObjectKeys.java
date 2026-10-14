package fun.fengwk.kkstudio.platform.storage;

import java.util.UUID;

/**
 * 全局 Blob 存储的确定性对象键推导。
 *
 * <p>所有键都由 id 推导且永不落库：{@code uploads/{uploadId}/original} 是浏览器直传的临时对象， {@code
 * blobs/{blobId}/original} 与 {@code blobs/{blobId}/preview.webp} 是去重后的 blob 对象。 不得出现 {@code
 * kkstudio://} 或持久化 {@code s3://} 形式的 durable URI。
 *
 * @author fengwk
 */
public final class StorageObjectKeys {

  private StorageObjectKeys() {}

  /** PENDING 上传直传对象键：内容先落在这里，complete 后再复制到 blob 键并删除。 */
  public static String uploadOriginal(UUID uploadId) {
    return "uploads/" + uploadId + "/original";
  }

  /** blob 原始内容对象键（内容寻址，写入后不可变）。 */
  public static String blobOriginal(UUID blobId) {
    return "blobs/" + blobId + "/original";
  }

  /** blob 预览对象键（webp；由后续媒体集成生成，本基础只负责清理与预签名）。 */
  public static String blobPreview(UUID blobId) {
    return "blobs/" + blobId + "/preview.webp";
  }
}
