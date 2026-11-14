package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.Map;
import java.util.UUID;

/**
 * 无法内联时 Resource 的确定性文本渲染：回退说明、图片尺寸标签、外部化文本声明与「媒体已移到 USER 消息」的 tool result 说明。
 *
 * <p>这些文本是 durable-safe 的，只描述资源身份与元数据，绝不包含实际内容；URI 一律由 {@link SessionResourceUri#format} 从 blobId
 * 派生，只暴露规范的 {@code resource://files/<blobId>}，不含 S3 object key、上传 id 或宿主路径。
 */
final class ProviderResourceTextFormat {

  private ProviderResourceTextFormat() {}

  /**
   * 外部化文本的模型声明：稳定 Session 资源 URI、名字、总量、不完整预览，以及用 read 分页读取完整输出的指引。
   *
   * <p>URI 只是标识，实际读取仍走原有 Session Blob 鉴权。
   */
  static String externalizedText(ProviderResourceBlock resource) {
    String preview = resource.preview() == null ? "" : resource.preview();
    String text =
        PromptRenderer.render(
            "fun/fengwk/kkstudio/platform/prompts/messages/output-externalized.md",
            Map.of(
                "uri", SessionResourceUri.format(resource.blobId()),
                "name", resource.name(),
                "bytes", resource.totalBytes(),
                "lines", resource.totalLines(),
                "hasPreview", !preview.isEmpty(),
                "preview", preview));
    return text.substring(0, text.length() - 1);
  }

  /** 内联图片的模型可读标签：资源 URI、原图尺寸、实际提供给模型的显示尺寸，以及缩小后坐标换算提示。 */
  static String imageLabel(
      UUID blobId, ProviderInlineBlobReader.ImageDimensions dimensions, String toolCallId) {
    String prefix = toolCallId == null ? "Image: " : "Tool call " + toolCallId + " image: ";
    StringBuilder label =
        new StringBuilder(prefix)
            .append(SessionResourceUri.format(blobId))
            .append("\nOriginal size: ")
            .append(dimensions.originalWidth())
            .append(" x ")
            .append(dimensions.originalHeight())
            .append("\nDisplay size: ")
            .append(dimensions.displayWidth())
            .append(" x ")
            .append(dimensions.displayHeight());
    if (dimensions.resized()) {
      label.append("\nMultiply coordinates … to map to the original image.");
    }
    return label.toString();
  }

  /** 被移动到后续 USER 消息的工具结果媒体在 tool result 内保留的确定性文本事实。 */
  static String deferredToolMedia(ProviderResourceBlock resource, StorageBlob blob) {
    return PromptRenderer.render(
            "fun/fengwk/kkstudio/platform/prompts/messages/media-deferred.md",
            Map.of("fallback", fallback(resource, blob)))
        .stripTrailing();
  }

  /**
   * 确定性文本回退：始终包含 name 与可读 Session 资源 URI；durable preview 非空时始终附带；mediaType/size 只在存在 ACTIVE storage
   * 事实时附带。
   */
  static String fallback(ProviderResourceBlock resource, StorageBlob blob) {
    StringBuilder text = new StringBuilder("[Resource: ").append(resource.name()).append("]");
    text.append("\nuri: ").append(SessionResourceUri.format(resource.blobId()));
    if (blob != null && blob.getState() == StorageBlobState.ACTIVE) {
      if (blob.getMediaType() != null && !blob.getMediaType().isBlank()) {
        text.append("\nmediaType: ").append(blob.getMediaType());
      }
      text.append("\nsize: ").append(blob.getSizeBytes());
    }
    if (!resource.preview().isEmpty()) {
      text.append("\npreview: ").append(resource.preview());
    }
    return text.toString();
  }
}
