package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;

import java.util.Objects;
import java.util.UUID;

/**
 * 携带全局 Blob 存储引用的 Provider 内容块（durable-safe：只含 blobId / name / totalBytes / totalLines / preview /
 * imageTier，绝不含 URL 与 Base64）。
 *
 * <p>由 {@code ProviderMessageProjector} 从 durable {@code ResourceMessageContent} 投影；Provider
 * attempt 物化（platform 的 ProviderResourceMaterializer）在每次 attempt 时按 {@code storage_blob}
 * 事实把普通媒体转换为内联 image/audio/video 块或确定性文本回退，而外部化文本转换为规范模型声明与 raw preview。
 *
 * <p>{@code imageTier} 是冻结的图片输入档位，只对图片媒体有意义：外部化文本必须为 null；图片为 null 时按平台默认（720P） 物化，非图片媒体的档位在消费
 * upload/复用 resource 时已收敛为 null。
 */
public record ProviderResourceBlock(
    UUID blobId,
    String name,
    Long totalBytes,
    Long totalLines,
    String preview,
    ImageInputTier imageTier)
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
      if (imageTier != null) {
        throw new IllegalArgumentException("externalized text must not carry an image input tier");
      }
    }
  }

  public ProviderResourceBlock(UUID blobId, String name, String preview) {
    this(blobId, name, null, null, preview, null);
  }

  /** 普通媒体便利工厂（图片输入档位由平台默认决定）。 */
  public static ProviderResourceBlock media(UUID blobId, String name, String preview) {
    return new ProviderResourceBlock(blobId, name, null, null, preview, null);
  }

  /** 普通媒体便利工厂（带显式图片输入档位）。 */
  public static ProviderResourceBlock media(
      UUID blobId, String name, String preview, ImageInputTier imageTier) {
    return new ProviderResourceBlock(blobId, name, null, null, preview, imageTier);
  }

  /** 外部化文本便利工厂（文本工件不携带图片输入档位）。 */
  public static ProviderResourceBlock externalizedText(
      UUID blobId, String name, long totalBytes, long totalLines, String preview) {
    return new ProviderResourceBlock(blobId, name, totalBytes, totalLines, preview, null);
  }

  /** 是否为外部化文本。 */
  public boolean isExternalizedText() {
    return totalBytes != null && totalLines != null;
  }
}
