package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.Objects;
import java.util.UUID;

/**
 * durable 消息中的 Resource 内容：携带全局 Blob 存储引用与结构化外部化事实，绝不携带 URI / mediaType / sha256。
 *
 * <p>{@code blobId} 引用 {@code storage_blob} 的 ACTIVE 行；{@code name} 是权威文件名（消费 upload 时取自上传行，
 * 工具结果外部化时取自 ResourceRef name）；普通媒体的 {@code totalBytes}、{@code totalLines} 为 null； 外部化文本上述两字段全部非
 * null 且非负，持有确切 UTF-8 字节数与物理行数。{@code preview} 是可空的小型文本预览。
 */
public record ResourceMessageContent(
    UUID blobId, String name, Long totalBytes, Long totalLines, String preview)
    implements AgentMessageContent {

  /** name 的 UTF-8 字节上限（与 {@link ResourceRef#MAX_NAME_UTF8_BYTES} 一致）。 */
  public static final int MAX_NAME_UTF8_BYTES = ResourceRef.MAX_NAME_UTF8_BYTES;

  /** preview 的 UTF-8 字节上限（与 {@link ResourceRef#MAX_PREVIEW_UTF8_BYTES} 一致）。 */
  public static final int MAX_PREVIEW_UTF8_BYTES = ResourceRef.MAX_PREVIEW_UTF8_BYTES;

  public ResourceMessageContent {
    blobId = Objects.requireNonNull(blobId, "blobId");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (name.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("name must not contain control characters");
    }
    if (ResourceRef.utf8Length(name, "name") > MAX_NAME_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "name must not exceed " + MAX_NAME_UTF8_BYTES + " UTF-8 bytes");
    }
    if (preview != null && ResourceRef.utf8Length(preview, "preview") > MAX_PREVIEW_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "preview must not exceed " + MAX_PREVIEW_UTF8_BYTES + " UTF-8 bytes");
    }
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

  /** 普通媒体便利工厂方法（无 preview）。 */
  public static ResourceMessageContent media(UUID blobId, String name) {
    return new ResourceMessageContent(blobId, name, null, null, null);
  }

  /** 普通媒体便利工厂方法（带 preview）。 */
  public static ResourceMessageContent media(UUID blobId, String name, String preview) {
    return new ResourceMessageContent(blobId, name, null, null, preview);
  }

  /** 外部化文本便利工厂方法。 */
  public static ResourceMessageContent externalizedText(
      UUID blobId, String name, long totalBytes, long totalLines, String preview) {
    return new ResourceMessageContent(blobId, name, totalBytes, totalLines, preview);
  }

  /** 是否为外部化文本。 */
  public boolean isExternalizedText() {
    return totalBytes != null && totalLines != null;
  }
}
