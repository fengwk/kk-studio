package fun.fengwk.kkstudio.harness.runtime.session;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * durable 消息中的 Resource 内容：携带全局 Blob 存储引用与结构化工件事实，绝不携带 URI / mediaType / sha256。
 *
 * <p>{@code blobId} 引用 {@code storage_blob} 的 ACTIVE 行；{@code name} 是权威文件名（消费 upload 时取自上传行，
 * 工具结果外部化时取自 ResourceRef name）；普通媒体的 {@code artifactPath}、{@code totalBytes}、{@code totalLines} 为
 * null；文本工件上述三字段全部非 null，持有规范的 {@code /.artifacts/tool-results/{threadId}/{invocationId}.txt|json}
 * 路径与确切 UTF-8 字节数与物理行数。{@code preview} 是可空的小型文本预览。
 */
public record ResourceMessageContent(
    UUID blobId, String name, String artifactPath, Long totalBytes, Long totalLines, String preview)
    implements AgentMessageContent {

  /** name 的 UTF-8 字节上限（与 {@link ResourceRef#MAX_NAME_UTF8_BYTES} 一致）。 */
  public static final int MAX_NAME_UTF8_BYTES = ResourceRef.MAX_NAME_UTF8_BYTES;

  /** preview 的 UTF-8 字节上限（与 {@link ResourceRef#MAX_PREVIEW_UTF8_BYTES} 一致）。 */
  public static final int MAX_PREVIEW_UTF8_BYTES = ResourceRef.MAX_PREVIEW_UTF8_BYTES;

  private static final Pattern ARTIFACT_PATH_PATTERN =
      Pattern.compile(
          "^/\\.artifacts/tool-results/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(txt|json)$");

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
    if (artifactPath != null || totalBytes != null || totalLines != null) {
      if (artifactPath == null || totalBytes == null || totalLines == null) {
        throw new IllegalArgumentException(
            "artifact fields (artifactPath, totalBytes, totalLines) must all be present or all be null");
      }
      validateArtifactPath(artifactPath);
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
    return new ResourceMessageContent(blobId, name, null, null, null, null);
  }

  /** 普通媒体便利工厂方法（带 preview）。 */
  public static ResourceMessageContent media(UUID blobId, String name, String preview) {
    return new ResourceMessageContent(blobId, name, null, null, null, preview);
  }

  /** 文本工件便利工厂方法。 */
  public static ResourceMessageContent artifact(
      UUID blobId,
      String name,
      String artifactPath,
      long totalBytes,
      long totalLines,
      String preview) {
    return new ResourceMessageContent(blobId, name, artifactPath, totalBytes, totalLines, preview);
  }

  /** 是否为文本工件。 */
  public boolean isTextArtifact() {
    return artifactPath != null;
  }

  private static void validateArtifactPath(String path) {
    Matcher matcher = ARTIFACT_PATH_PATTERN.matcher(path);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "artifactPath must be a canonical tool artifact path: " + path);
    }
    try {
      UUID threadId = UUID.fromString(matcher.group(1));
      UUID invocationId = UUID.fromString(matcher.group(2));
      if (!threadId.toString().equals(matcher.group(1))
          || !invocationId.toString().equals(matcher.group(2))) {
        throw new IllegalArgumentException(
            "artifactPath must contain lowercase canonical UUIDs: " + path);
      }
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("artifactPath must contain valid UUIDs: " + path, error);
    }
  }
}
