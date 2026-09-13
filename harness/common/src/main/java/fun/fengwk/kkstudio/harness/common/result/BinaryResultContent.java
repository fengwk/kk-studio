package fun.fengwk.kkstudio.harness.common.result;

import java.util.Arrays;
import java.util.Objects;

/**
 * 内联二进制结果内容单元。
 *
 * <p>作为适配边界可外部化的瞬时字节载荷，内容仅在内存中暂存。可选 {@code textMetadata} 标识字节来自完整文本工件； 消费方仍须严格复核 UTF-8 与行数。
 */
public record BinaryResultContent(
    String mediaType, byte[] content, TextArtifactMetadata textMetadata) implements ResultContent {

  public BinaryResultContent(String mediaType, byte[] content) {
    this(mediaType, content, null);
  }

  public BinaryResultContent {
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    content = Objects.requireNonNull(content, "content").clone();
    if (textMetadata != null && textMetadata.totalBytes() != content.length) {
      throw new IllegalArgumentException(
          "textMetadata.totalBytes must match binary content length");
    }
  }

  @Override
  public byte[] content() {
    return content.clone();
  }

  /** 原始字节长度；不复制、不物化，供尺寸校验直接使用。 */
  public int size() {
    return content.length;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BinaryResultContent that)) {
      return false;
    }
    return mediaType.equals(that.mediaType)
        && Arrays.equals(content, that.content)
        && Objects.equals(textMetadata, that.textMetadata);
  }

  @Override
  public int hashCode() {
    int result = 31 * mediaType.hashCode() + Arrays.hashCode(content);
    return 31 * result + Objects.hashCode(textMetadata);
  }

  @Override
  public String toString() {
    return "BinaryResultContent[mediaType="
        + mediaType
        + ", content.length="
        + content.length
        + ", textMetadata="
        + textMetadata
        + "]";
  }
}
