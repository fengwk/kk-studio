package fun.fengwk.kkstudio.platform.harness.model;

import com.github.benmanes.caffeine.cache.Ticker;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDocumentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Provider attempt 内联媒体的读取与记账：把已判定可送达的 Resource 读成 attempt-only 媒体块，并让同一次 attempt 的所有 data URI
 * 共用一份字符预算。
 *
 * <p>本类不判断模态、位置能力与 ACTIVE 状态，这些判定由 {@link ProviderResourceMaterializer} 在调用前完成；这里只做单文件大小与 request
 * 预算校验、有界读取（{@link ProviderInlineBlobReader}）与块构造。预算按 attempt 记账：重复与嵌套引用同样计入，attempt 结束后不残留。
 */
final class ProviderInlineMediaReader {

  private final ProviderInlineBlobReader blobReader;
  private final ProviderImageModelViewService imageModelViewService;
  private final Supplier<ImageInputTier> defaultImageTier;

  ProviderInlineMediaReader(
      StorageBlobContentService blobContentService,
      Supplier<ImageInputTier> defaultImageTier,
      ProviderImageModelViewService imageModelViewService,
      ProviderInlineBlobReader.Limits limits,
      Ticker ticker) {
    this.blobReader = new ProviderInlineBlobReader(blobContentService, limits, ticker);
    this.defaultImageTier = Objects.requireNonNull(defaultImageTier, "defaultImageTier");
    this.imageModelViewService = imageModelViewService;
  }

  /** 一次 attempt 的独立字符预算。 */
  Budget newBudget(long maxChars) {
    return new Budget(maxChars);
  }

  /**
   * 仅在 model 模态与 adapter 位置能力同时支持时才读取内容并构造媒体块。
   *
   * <p>调用方已完成位置能力判定；本方法只做单文件大小与 request 预算校验、有界读取与块构造。
   */
  ProviderContentBlock mediaBlock(
      ProviderResourceBlock resource,
      StorageBlob blob,
      ModelInputModality modality,
      Budget budget) {
    ProviderInlineBlobReader.Limits limits = blobReader.limits();
    if (blob.getSizeBytes() < 0 || blob.getSizeBytes() > limits.maxBlobBytes()) {
      throw new IllegalArgumentException(
          "blob size "
              + blob.getSizeBytes()
              + " bytes is outside the allowed inline range 0.."
              + limits.maxBlobBytes()
              + " bytes");
    }
    if (modality == ModelInputModality.IMAGE) {
      return image(resource, blob, budget).block();
    }
    long required =
        ProviderInlineBlobReader.estimatedDataUriChars(blob.getMediaType(), blob.getSizeBytes());
    // 先按完整 Base64 长度记账再读取；读取器校验实际长度，任一失败都会终止整个 attempt 物化。
    budget.reserve(required);
    String source =
        blobReader.readDataUri(
            resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), required);
    return switch (modality) {
      case AUDIO -> new ProviderAudioBlock(blob.getMediaType(), source);
      case VIDEO -> new ProviderVideoBlock(blob.getMediaType(), source);
      case DOCUMENT -> new ProviderDocumentBlock(blob.getMediaType(), source);
      case TEXT, IMAGE -> null;
    };
  }

  /**
   * 图片按冻结档位物化：{@link ImageInputTier#ORIGINAL} 与未指定档位的媒体一样保留原字节，字符数由声明事实确定，因此先记账再读取；
   * 缩放档位的实际字节数只有读取完成后才知道，先把本次剩余预算交给读取器，再按实际 data URI 字符数记账。
   */
  Projection image(ProviderResourceBlock resource, StorageBlob blob, Budget budget) {
    if (resource.displayWidth() != null) {
      long required =
          ProviderInlineBlobReader.estimatedDataUriChars(blob.getMediaType(), blob.getSizeBytes());
      budget.reserve(required);
      String source =
          blobReader.readDataUri(
              resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), required);
      return new Projection(
          new ProviderImageBlock(blob.getMediaType(), source),
          new ProviderInlineBlobReader.ImageDimensions(
              resource.originalWidth(),
              resource.originalHeight(),
              resource.displayWidth(),
              resource.displayHeight(),
              !resource.originalWidth().equals(resource.displayWidth())
                  || !resource.originalHeight().equals(resource.displayHeight())));
    }
    // 未指定图片档位的媒体按系统设置的默认档位；显式档位保持冻结值。
    ImageInputTier tier =
        resource.imageTier() == null ? defaultImageTier.get() : resource.imageTier();
    if (imageModelViewService != null) {
      ProviderImageModelViewService.ModelView view =
          imageModelViewService.resolve(
              resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), tier);
      long required =
          ProviderInlineBlobReader.estimatedDataUriChars(view.mediaType(), view.sizeBytes());
      budget.reserve(required);
      String source =
          blobReader.readDataUri(view.blobId(), view.mediaType(), view.sizeBytes(), required);
      return new Projection(
          new ProviderImageBlock(view.mediaType(), source),
          new ProviderInlineBlobReader.ImageDimensions(
              view.originalWidth(),
              view.originalHeight(),
              view.displayWidth(),
              view.displayHeight(),
              view.resized()));
    }
    boolean original = tier.isOriginal();
    long allowedChars =
        original
            ? ProviderInlineBlobReader.estimatedDataUriChars(
                blob.getMediaType(), blob.getSizeBytes())
            : budget.remaining();
    if (original) {
      budget.reserve(allowedChars);
    }
    ProviderInlineBlobReader.InlineBlob inline =
        blobReader.readImage(
            resource.blobId(), blob.getMediaType(), blob.getSizeBytes(), tier, allowedChars);
    if (!original) {
      budget.reserve(inline.dataUri().length());
    }
    ProviderInlineBlobReader.ImageDimensions dimensions = inline.dimensions();
    if (dimensions == null) {
      if (blob.getWidth() == null || blob.getHeight() == null) {
        throw new IllegalStateException(
            "image dimensions are unavailable for " + resource.blobId());
      }
      int width = Math.toIntExact(blob.getWidth());
      int height = Math.toIntExact(blob.getHeight());
      dimensions =
          new ProviderInlineBlobReader.ImageDimensions(width, height, width, height, false);
    }
    return new Projection(new ProviderImageBlock(inline.mediaType(), inline.dataUri()), dimensions);
  }

  /** 单次 attempt 的内联字符预算：重复与嵌套引用同样计入，attempt 结束后不残留。 */
  static final class Budget {

    private final long maxChars;
    private long usedChars;

    private Budget(long maxChars) {
      this.maxChars = maxChars;
    }

    private long remaining() {
      return maxChars - usedChars;
    }

    private void reserve(long chars) {
      if (chars > maxChars - usedChars) {
        throw new IllegalArgumentException(
            "request inline data URI payload must not exceed "
                + maxChars
                + " characters; requires at least "
                + chars
                + " more characters");
      }
      usedChars += chars;
    }
  }

  /** 已内联的图片与其权威缩放尺寸（用于模型可读标签）。 */
  record Projection(
      ProviderImageBlock block, ProviderInlineBlobReader.ImageDimensions dimensions) {}
}
