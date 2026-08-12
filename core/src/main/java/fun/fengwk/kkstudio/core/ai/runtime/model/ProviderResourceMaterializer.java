package fun.fengwk.kkstudio.core.ai.runtime.model;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAudioBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderVideoBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider attempt 的 Resource 物化：把 durable-safe 的 {@link
 * ProviderResourceBlock}（blobId/name/preview）按 当前所选 model 的 {@code inputModalities} 与 {@code
 * storage_blob} 事实转换为本次 attempt 的有效 provider 内容。
 *
 * <p>每次 attempt 调用（provider 解析阶段，CoreModelGateway 事务外）：blob 媒体类型为 image/audio/video 且模型支持
 * 对应模态时，生成携带 <em>新鲜预签名 HTTPS URL</em> 的 media 块；否则生成确定性文本回退（含 name/blobId/mediaType/
 * size/preview）。生成的 URL 只存在于本次 attempt 的有效请求，绝不持久化（durable invocation request 只保存 {@link
 * ProviderResourceBlock}）。通用 DOCUMENT 模态保持不支持：不产生任何 media 块。
 *
 * <p>本类由 S3 装配（{@code S3StorageConfiguration}）以 bean 形式提供；Storage 不可用（S3 未启用）时使用 {@link
 * #withoutStorage()} 的 no-op 物化端口：所有 Resource 块降级为不含媒体事实的确定性文本回退，模型仍可感知资源存在，但没有任何 URL / 媒体事实可用。
 */
public final class ProviderResourceMaterializer {

  private final StorageBlobManager blobManager;

  private ProviderResourceMaterializer(StorageBlobManager blobManager) {
    this.blobManager = blobManager;
  }

  /** 基于全局 Blob 存储事实的物化器（S3 启用时装配）。 */
  public static ProviderResourceMaterializer withStorage(StorageBlobManager blobManager) {
    return new ProviderResourceMaterializer(Objects.requireNonNull(blobManager, "blobManager"));
  }

  /** Storage 不可用时的 no-op 物化端口：全部 Resource 块降级为确定性文本回退。 */
  public static ProviderResourceMaterializer withoutStorage() {
    return new ProviderResourceMaterializer(null);
  }

  /** 把请求消息中的 Resource 块物化为本次 attempt 的有效内容块；消息与块顺序保持不变。 */
  public List<ProviderMessage> materialize(
      List<ProviderMessage> messages, Set<ModelInputModality> inputModalities) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(inputModalities, "inputModalities");
    List<ProviderMessage> result = new ArrayList<>(messages.size());
    for (ProviderMessage message : messages) {
      List<ProviderContentBlock> blocks = new ArrayList<>(message.contents().size());
      for (ProviderContentBlock block : message.contents()) {
        blocks.add(materializeBlock(block, inputModalities));
      }
      result.add(new ProviderMessage(message.role(), List.copyOf(blocks)));
    }
    return List.copyOf(result);
  }

  private ProviderContentBlock materializeBlock(
      ProviderContentBlock block, Set<ModelInputModality> inputModalities) {
    if (!(block instanceof ProviderResourceBlock resource)) {
      return block;
    }
    StorageBlob blob = blobManager == null ? null : blobManager.getBlob(resource.blobId());
    String mediaType =
        blob == null || blob.getState() != StorageBlobState.ACTIVE ? null : blob.getMediaType();
    // 只在媒体类型与所选模态都匹配时才 mint 新鲜预签名 URL；任何回退路径绝不触发 presign。
    if (mediaType != null) {
      if (mediaType.startsWith("image/") && inputModalities.contains(ModelInputModality.IMAGE)) {
        return new ProviderImageBlock(mediaType, presign(resource));
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

  /**
   * 确定性文本回退：始终包含 name/blobId；durable preview 非空时始终附带（即使 Storage 不可用 / blob 缺失）；mediaType/size 只在存在
   * ACTIVE storage 事实时附带。
   */
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
