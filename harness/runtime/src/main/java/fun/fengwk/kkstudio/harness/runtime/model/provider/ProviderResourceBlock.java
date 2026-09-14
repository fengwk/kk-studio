package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * 携带全局 Blob 存储引用的 Provider 内容块（durable-safe：只含 blobId / name / totalBytes / totalLines /
 * preview，绝不含 URL）。
 *
 * <p>由 {@code ProviderMessageProjector} 从 durable {@code ResourceMessageContent} 投影；Provider
 * attempt 物化（platform 的 ProviderResourceMaterializer）在每次 attempt 时按 {@code storage_blob}
 * 事实把普通媒体转换为携带新鲜预签名 HTTPS URL 的 image/audio/video 块或确定性文本回退，而外部化文本转换为规范模型声明与 raw preview。
 */
public record ProviderResourceBlock(
    UUID blobId, String name, Long totalBytes, Long totalLines, String preview)
    implements ProviderContentBlock {

  public ProviderResourceBlock {
    blobId = Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(preview, "preview");
    if (totalBytes != null || totalLines != null) {
      if (totalBytes == null || totalLines == null) {
        throw new IllegalArgumentException(
            "totalBytes and totalLines must both be present or both be null");
      }
      if (totalBytes < 0) {
        throw new IllegalArgumentException("totalBytes must not be negative");
      }
      if (totalLines < 0) {
        throw new IllegalArgumentException("totalLines must not be negative");
      }
    }
  }

  public ProviderResourceBlock(UUID blobId, String name, String preview) {
    this(blobId, name, null, null, preview);
  }

  /** 普通媒体便利工厂。 */
  public static ProviderResourceBlock media(UUID blobId, String name, String preview) {
    return new ProviderResourceBlock(blobId, name, null, null, preview);
  }

  /** 外部化文本便利工厂。 */
  public static ProviderResourceBlock externalizedText(
      UUID blobId, String name, long totalBytes, long totalLines, String preview) {
    return new ProviderResourceBlock(blobId, name, totalBytes, totalLines, preview);
  }

  /** 是否为外部化文本。 */
  public boolean isExternalizedText() {
    return totalBytes != null && totalLines != null;
  }
}
