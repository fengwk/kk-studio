package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;
import fun.fengwk.kkstudio.platform.storage.S3ObjectContent;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider attempt 的 Resource 物化：把 durable-safe 的 {@link
 * ProviderResourceBlock}（blobId/name/preview）按 当前所选 model 的 {@code inputModalities} 与 {@code
 * storage_blob} 事实转换为本次 attempt 的有效 provider 内容。
 *
 * <p>每次 attempt 调用（provider 解析阶段，PlatformModelGateway 事务外）：支持的图片从 Blob 存储受限读取并生成为 attempt-only data
 * URI，避免远端 Provider 无法访问本地/私网存储；audio/video 暂沿用新鲜预签名 URL。否则生成确定性文本回退（含
 * name/blobId/mediaType/size/preview）。这些瞬时 source 绝不持久化（durable invocation request 只保存 {@link
 * ProviderResourceBlock}）。通用 DOCUMENT 模态保持不支持：不产生任何 media 块。
 *
 * <p>本类由 S3 装配（{@code S3StorageConfiguration}）以 bean 形式提供。
 */
public final class ProviderResourceMaterializer {

  static final long MAX_INLINE_IMAGE_BYTES = 30L * 1024L * 1024L;

  private final StorageBlobManager blobManager;
  private final S3StorageService storageService;

  public ProviderResourceMaterializer(
      StorageBlobManager blobManager, S3StorageService storageService) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.storageService = Objects.requireNonNull(storageService, "storageService");
  }

  /** 基于全局 Blob 存储事实的物化器。 */
  public static ProviderResourceMaterializer withStorage(
      StorageBlobManager blobManager, S3StorageService storageService) {
    return new ProviderResourceMaterializer(blobManager, storageService);
  }

  /** 把请求消息中的 Resource 块物化为本次 attempt 的有效内容块；消息与块顺序保持不变。 */
  public List<ProviderMessage> materialize(
      List<ProviderMessage> messages, Set<ModelInputModality> inputModalities) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(inputModalities, "inputModalities");
    List<ProviderMessage> result = new ArrayList<>(messages.size());
    for (ProviderMessage message : messages) {
      result.add(
          new ProviderMessage(
              message.role(), materializeContents(message.contents(), inputModalities)));
    }
    return List.copyOf(result);
  }

  private List<ProviderContentBlock> materializeContents(
      List<ProviderContentBlock> contents, Set<ModelInputModality> inputModalities) {
    List<ProviderContentBlock> result = new ArrayList<>(contents.size());
    for (ProviderContentBlock content : contents) {
      result.add(materializeBlock(content, inputModalities));
    }
    return List.copyOf(result);
  }

  private ProviderContentBlock materializeBlock(
      ProviderContentBlock block, Set<ModelInputModality> inputModalities) {
    if (block instanceof ProviderToolResultBlock toolResult) {
      List<ProviderContentBlock> contents =
          materializeContents(toolResult.contents(), inputModalities);
      if (contents.equals(toolResult.contents())) {
        return block;
      }
      return new ProviderToolResultBlock(
          toolResult.toolCallId(),
          toolResult.toolName(),
          contents,
          toolResult.error(),
          toolResult.detailsJson());
    }
    if (!(block instanceof ProviderResourceBlock resource)) {
      return block;
    }
    if (resource.isExternalizedText()) {
      return new ProviderTextBlock(formatExternalizedText(resource));
    }
    StorageBlob blob = blobManager.getBlob(resource.blobId());
    String mediaType =
        blob == null || blob.getState() != StorageBlobState.ACTIVE ? null : blob.getMediaType();
    // 只在媒体类型与所选模态都匹配时才读取/签名；任何回退路径绝不触发存储内容读取或 presign。
    if (mediaType != null) {
      if (mediaType.startsWith("image/") && inputModalities.contains(ModelInputModality.IMAGE)) {
        return new ProviderImageBlock(mediaType, inlineImage(resource, blob));
      }
      if (mediaType.startsWith("audio/") && inputModalities.contains(ModelInputModality.AUDIO)) {
        return new ProviderAudioBlock(mediaType, presign(resource));
      }
      if (mediaType.startsWith("video/") && inputModalities.contains(ModelInputModality.VIDEO)) {
        return new ProviderVideoBlock(mediaType, presign(resource));
      }
    }
    return new ProviderTextBlock(fallbackText(resource, blob));
  }

  private String presign(ProviderResourceBlock resource) {
    return blobManager.presignOriginalUrl(resource.blobId()).getUrl();
  }

  private String inlineImage(ProviderResourceBlock resource, StorageBlob blob) {
    if (blob.getSizeBytes() > MAX_INLINE_IMAGE_BYTES) {
      throw new IllegalArgumentException(
          "provider image resource must not exceed " + MAX_INLINE_IMAGE_BYTES + " bytes");
    }
    S3ObjectContent content =
        storageService.download(
            StorageObjectKeys.blobOriginal(resource.blobId()), MAX_INLINE_IMAGE_BYTES);
    return "data:"
        + blob.getMediaType()
        + ";base64,"
        + Base64.getEncoder().encodeToString(content.getBytes());
  }

  private static String formatExternalizedText(ProviderResourceBlock resource) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "[Output externalized. The preview below is incomplete; do not treat it as the full"
            + " result.\n\n");
    sb.append("The complete output has been saved as a downloadable user attachment: ")
        .append(resource.name())
        .append("\n");
    sb.append("Size: ")
        .append(resource.totalBytes())
        .append(" bytes, ")
        .append(resource.totalLines())
        .append(" lines]");
    sb.append("\n\n--- preview ---");
    if (resource.preview() != null && !resource.preview().isEmpty()) {
      sb.append("\n").append(resource.preview());
    }
    return sb.toString();
  }

  /** 确定性文本回退：始终包含 name/blobId；durable preview 非空时始终附带；mediaType/size 只在存在 ACTIVE storage 事实时附带。 */
  private static String fallbackText(ProviderResourceBlock resource, StorageBlob blob) {
    StringBuilder text = new StringBuilder("[Resource: ").append(resource.name()).append("]");
    text.append("\nblobId: ").append(resource.blobId());
    if (blob != null && blob.getState() == StorageBlobState.ACTIVE) {
      text.append("\nmediaType: ").append(blob.getMediaType());
      text.append("\nsize: ").append(blob.getSizeBytes());
    }
    if (!resource.preview().isEmpty()) {
      text.append("\npreview: ").append(resource.preview());
    }
    return text.toString();
  }
}
