package fun.fengwk.kkstudio.harness.runtime.model.provider;

import java.util.Objects;
import java.util.UUID;

/**
 * 携带全局 Blob 存储引用的 Provider 内容块（durable-safe：只含 blobId / name / artifactPath / totalBytes /
 * totalLines / preview，绝不含 URL）。
 *
 * <p>由 {@code ProviderMessageProjector} 从 durable {@code ResourceMessageContent} 投影；Provider
 * attempt 物化（platform 的 ProviderResourceMaterializer）在每次 attempt 时按 {@code storage_blob}
 * 事实把普通媒体转换为携带新鲜预签名 HTTPS URL 的 image/audio/video 块或确定性文本回退，而文本工件始终转换为规范模型声明与 raw preview。
 */
public record ProviderResourceBlock(
    UUID blobId, String name, String artifactPath, Long totalBytes, Long totalLines, String preview)
    implements ProviderContentBlock {

  public ProviderResourceBlock {
    blobId = Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(preview, "preview");
    if (artifactPath != null || totalBytes != null || totalLines != null) {
      if (artifactPath == null || totalBytes == null || totalLines == null) {
        throw new IllegalArgumentException("artifact fields must all be present or all be null");
      }
    }
  }

  public ProviderResourceBlock(UUID blobId, String name, String preview) {
    this(blobId, name, null, null, null, preview);
  }

  /** 普通媒体便利工厂。 */
  public static ProviderResourceBlock media(UUID blobId, String name, String preview) {
    return new ProviderResourceBlock(blobId, name, null, null, null, preview);
  }

  /** 文本工件便利工厂。 */
  public static ProviderResourceBlock artifact(
      UUID blobId,
      String name,
      String artifactPath,
      long totalBytes,
      long totalLines,
      String preview) {
    return new ProviderResourceBlock(blobId, name, artifactPath, totalBytes, totalLines, preview);
  }

  /** 是否为文本工件。 */
  public boolean isTextArtifact() {
    return artifactPath != null;
  }
}
