package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

import java.util.Objects;
import java.util.UUID;

/**
 * durable 消息中的 Resource 内容：只携带全局 Blob 存储引用与展示事实，绝不携带 URI / mediaType / size / sha256。
 *
 * <p>{@code blobId} 引用 {@code storage_blob} 的 ACTIVE 行；{@code name} 是权威文件名（消费 upload 时取自上传行，
 * 工具结果外部化时取自 ResourceRef name）；{@code preview} 是可空的小型文本预览。mediaType / size 等媒体事实在 Provider 物化时从
 * {@code storage_blob} 行读取，不随消息持久化。
 */
public record ResourceMessageContent(UUID blobId, String name, String preview)
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
  }
}
